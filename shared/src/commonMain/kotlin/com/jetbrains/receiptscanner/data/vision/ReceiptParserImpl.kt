package com.jetbrains.receiptscanner.data.vision

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs

/**
 * Implementation of receipt-specific text structure analysis system
 */
class ReceiptParserImpl(
    private val itemLineParser: ItemLineParser = ItemLineParser()
) : ReceiptParser {

    companion object {
        // Date patterns for extraction
        private val DATE_PATTERNS = listOf(
            Regex("""(\d{1,2})/(\d{1,2})/(\d{4})"""), // MM/DD/YYYY or DD/MM/YYYY
            Regex("""(\d{1,2})-(\d{1,2})-(\d{4})"""), // MM-DD-YYYY or DD-MM-YYYY
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""), // YYYY-MM-DD
            Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""") // MM.DD.YYYY or DD.MM.YYYY
        )

        // Time patterns
        private val TIME_PATTERNS = listOf(
            Regex("""(\d{1,2}):(\d{2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE)
        )

        // Merchant chain patterns
        private val MERCHANT_PATTERNS = mapOf(
            ReceiptLayout.WALMART to listOf("walmart", "wal-mart", "wal mart"),
            ReceiptLayout.TARGET to listOf("target", "target corp"),
            ReceiptLayout.KROGER to listOf("kroger", "king soopers", "ralphs", "fred meyer"),
            ReceiptLayout.SAFEWAY to listOf("safeway", "vons", "pavilions"),
            ReceiptLayout.COSTCO to listOf("costco", "costco wholesale")
        )

        // Total section keywords
        private val TOTAL_KEYWORDS = listOf(
            "subtotal", "sub total", "sub-total",
            "tax", "sales tax", "total tax",
            "total", "grand total", "amount due",
            "discount", "savings", "coupon"
        )
    }

    override suspend fun parseReceipt(ocrResult: TextRecognitionResult): Result<ReceiptData> {
        return try {
            val lines = ocrResult.recognizedLines

            // Classify receipt layout
            val layout = classifyReceiptLayout(lines)

            // Classify text regions
            val textRegions = classifyTextRegions(lines)

            // Extract merchant information
            val merchantInfo = extractMerchantInfo(textRegions.header)

            // Parse item lines
            val items = parseItemLines(textRegions.items)

            // Extract totals
            val totals = extractTotals(textRegions.totals)

            // Extract date/time
            val dateTime = extractDateTime(lines)

            // Validate receipt data
            val validationResults = validateReceiptData(items, totals, merchantInfo)

            val receiptData = ReceiptData(
                merchantInfo = merchantInfo,
                items = items,
                totals = totals,
                dateTime = dateTime,
                layout = layout,
                textRegions = textRegions,
                confidenceScore = 0f, // Will be calculated next
                validationResults = validationResults
            )

            // Calculate overall confidence score
            val confidenceScore = calculateConfidenceScore(receiptData, ocrResult)
            val finalReceiptData = receiptData.copy(confidenceScore = confidenceScore)

            Result.success(finalReceiptData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun classifyReceiptLayout(lines: List<RecognizedTextLine>): ReceiptLayout {
        val allText = lines.joinToString(" ") { it.text }.lowercase()

        for ((layout, patterns) in MERCHANT_PATTERNS) {
            for (pattern in patterns) {
                if (allText.contains(pattern)) {
                    return layout
                }
            }
        }

        return ReceiptLayout.GENERIC_GROCERY
    }

    override fun extractMerchantInfo(headerLines: List<RecognizedTextLine>): MerchantInfo {
        if (headerLines.isEmpty()) {
            return MerchantInfo("Unknown", null, null, null, 0.1f)
        }

        // Usually the merchant name is in the first few lines with highest confidence
        val merchantLine = headerLines
            .filter { it.text.trim().length > 3 }
            .maxByOrNull { it.confidence }

        val merchantName = merchantLine?.text?.trim() ?: "Unknown"

        // Extract address (usually follows merchant name)
        val addressLines = headerLines.drop(1).take(3)
            .filter { line ->
                line.text.contains(Regex("""\d+.*\w+.*\w+""")) // Contains numbers and words (address pattern)
            }
        val address = addressLines.joinToString(", ") { it.text.trim() }.takeIf { it.isNotEmpty() }

        // Extract phone number
        val phonePattern = Regex("""\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}""")
        val phoneNumber = headerLines.firstNotNullOfOrNull { line ->
            phonePattern.find(line.text)?.value
        }

        // Determine chain ID based on merchant name
        val chainId = determineChainId(merchantName)

        val confidence = merchantLine?.confidence ?: 0.1f

        return MerchantInfo(
            name = merchantName,
            address = address,
            chainId = chainId,
            phoneNumber = phoneNumber,
            confidence = confidence
        )
    }

    override fun parseItemLines(itemLines: List<RecognizedTextLine>): List<ParsedReceiptItem> {
        return itemLines.mapNotNull { line ->
            itemLineParser.parseItemLine(line)
        }
    }

    override fun extractTotals(totalLines: List<RecognizedTextLine>): ReceiptTotals {
        var subtotal = 0.0
        var taxAmount = 0.0
        var total = 0.0
        var discounts = 0.0
        var confidence = 0.0f
        var validLinesCount = 0

        for (line in totalLines) {
            val text = line.text.lowercase()
            val prices = extractPricesFromLine(line.text)

            if (prices.isNotEmpty()) {
                val price = prices.last() // Usually the rightmost price

                when {
                    text.contains("subtotal") || text.contains("sub total") -> {
                        subtotal = price
                        confidence += line.confidence
                        validLinesCount++
                    }
                    text.contains("tax") -> {
                        taxAmount = price
                        confidence += line.confidence
                        validLinesCount++
                    }
                    text.contains("total") && !text.contains("subtotal") -> {
                        total = price
                        confidence += line.confidence
                        validLinesCount++
                    }
                    text.contains("discount") || text.contains("savings") || text.contains("coupon") -> {
                        discounts += price
                        confidence += line.confidence
                        validLinesCount++
                    }
                }
            }
        }

        // Calculate average confidence
        confidence = if (validLinesCount > 0) confidence / validLinesCount else 0.1f

        // Validate totals
        val validation = validateTotals(subtotal, taxAmount, total, discounts)

        return ReceiptTotals(
            subtotal = subtotal,
            taxAmount = taxAmount,
            total = total,
            discounts = discounts,
            confidence = confidence,
            validation = validation
        )
    }

    override fun calculateConfidenceScore(receiptData: ReceiptData, ocrResult: TextRecognitionResult): Float {
        val confidenceScorer = ConfidenceScorer()
        val confidenceScore = confidenceScorer.calculateOverallConfidence(receiptData, ocrResult)
        return confidenceScore.overallScore
    }

    /**
     * Get detailed confidence score breakdown
     */
    fun getDetailedConfidenceScore(receiptData: ReceiptData, ocrResult: TextRecognitionResult): ConfidenceScore {
        val confidenceScorer = ConfidenceScorer()
        return confidenceScorer.calculateOverallConfidence(receiptData, ocrResult)
    }

    /**
     * Classify text regions based on position and content
     */
    private fun classifyTextRegions(lines: List<RecognizedTextLine>): ReceiptTextRegions {
        if (lines.isEmpty()) {
            return ReceiptTextRegions(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val sortedLines = lines.sortedBy { it.boundingBox.top }
        val totalHeight = sortedLines.last().boundingBox.bottom - sortedLines.first().boundingBox.top

        val header = mutableListOf<RecognizedTextLine>()
        val items = mutableListOf<RecognizedTextLine>()
        val totals = mutableListOf<RecognizedTextLine>()
        val footer = mutableListOf<RecognizedTextLine>()

        for (line in sortedLines) {
            val relativePosition = (line.boundingBox.top - sortedLines.first().boundingBox.top) / totalHeight
            val text = line.text.lowercase()

            when {
                // Header: top 20% or contains merchant info
                relativePosition < 0.2f || containsMerchantInfo(text) -> header.add(line)

                // Totals: bottom 20% or contains total keywords
                relativePosition > 0.8f || containsTotalKeywords(text) -> totals.add(line)

                // Footer: very bottom or contains footer info
                relativePosition > 0.95f || containsFooterInfo(text) -> footer.add(line)

                // Items: everything else with price patterns
                containsPricePattern(text) -> items.add(line)

                // Default to items if in middle section
                relativePosition in 0.2f..0.8f -> items.add(line)

                else -> footer.add(line)
            }
        }

        return ReceiptTextRegions(header, items, totals, footer)
    }

    private fun containsMerchantInfo(text: String): Boolean {
        return MERCHANT_PATTERNS.values.flatten().any { text.contains(it) }
    }

    private fun containsTotalKeywords(text: String): Boolean {
        return TOTAL_KEYWORDS.any { text.contains(it) }
    }

    private fun containsFooterInfo(text: String): Boolean {
        return text.contains("thank") || text.contains("visit") || text.contains("receipt")
    }

    private fun containsPricePattern(text: String): Boolean {
        return text.contains(Regex("""\$?\d+\.\d{2}"""))
    }

    private fun extractPricesFromLine(text: String): List<Double> {
        val prices = mutableListOf<Double>()
        val pricePattern = Regex("""\$?(\d+\.\d{2})""")

        pricePattern.findAll(text).forEach { match ->
            match.groupValues[1].toDoubleOrNull()?.let { prices.add(it) }
        }

        return prices
    }

    private fun extractDateTime(lines: List<RecognizedTextLine>): Instant? {
        var date: LocalDate? = null
        var time: String? = null

        for (line in lines) {
            val text = line.text

            // Extract date
            if (date == null) {
                for (pattern in DATE_PATTERNS) {
                    val match = pattern.find(text)
                    if (match != null) {
                        date = parseDate(match.groupValues)
                        break
                    }
                }
            }

            // Extract time
            if (time == null) {
                for (pattern in TIME_PATTERNS) {
                    val match = pattern.find(text)
                    if (match != null) {
                        time = match.value
                        break
                    }
                }
            }

            if (date != null && time != null) break
        }

        return date?.let { d ->
            try {
                val dateTime = LocalDateTime(d.year, d.month, d.dayOfMonth, 12, 0) // Default to noon if no time
                dateTime.toInstant(TimeZone.currentSystemDefault())
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseDate(groups: List<String>): LocalDate? {
        return try {
            when (groups.size) {
                4 -> {
                    val first = groups[1].toInt()
                    val second = groups[2].toInt()
                    val year = groups[3].toInt()

                    // Try MM/DD/YYYY first, then DD/MM/YYYY
                    if (first <= 12 && second <= 31) {
                        LocalDate(year, first, second)
                    } else if (second <= 12 && first <= 31) {
                        LocalDate(year, second, first)
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun determineChainId(merchantName: String): String? {
        val name = merchantName.lowercase()
        return MERCHANT_PATTERNS.entries.firstOrNull { (_, patterns) ->
            patterns.any { name.contains(it) }
        }?.key?.name?.lowercase()
    }

    private fun validateTotals(subtotal: Double, taxAmount: Double, total: Double, discounts: Double): TotalsValidation {
        val calculatedSubtotal = subtotal
        val calculatedTotal = subtotal + taxAmount - discounts
        val discrepancy = abs(calculatedTotal - total)
        val tolerance = 0.02 // 2 cent tolerance

        return TotalsValidation(
            itemsSum = 0.0, // Will be calculated when items are available
            calculatedSubtotal = calculatedSubtotal,
            calculatedTotal = calculatedTotal,
            isValid = discrepancy <= tolerance,
            discrepancy = discrepancy
        )
    }

    private fun validateReceiptData(
        items: List<ParsedReceiptItem>,
        totals: ReceiptTotals,
        merchantInfo: MerchantInfo
    ): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        // Validate merchant header
        results.add(ValidationResult(
            type = ValidationType.MERCHANT_HEADER,
            isValid = merchantInfo.name != "Unknown",
            confidence = merchantInfo.confidence,
            message = if (merchantInfo.name == "Unknown") "Merchant name not detected" else null
        ))

        // Validate item structure
        val validItems = items.count { it.priceValidation.isValid }
        results.add(ValidationResult(
            type = ValidationType.ITEM_STRUCTURE,
            isValid = validItems > 0,
            confidence = if (items.isNotEmpty()) validItems.toFloat() / items.size else 0f,
            message = if (validItems == 0) "No valid items detected" else null
        ))

        // Validate mathematical consistency
        val itemsSum = items.sumOf { it.lineTotal }
        val totalDiscrepancy = abs(itemsSum - totals.subtotal)
        results.add(ValidationResult(
            type = ValidationType.MATHEMATICAL_CONSISTENCY,
            isValid = totalDiscrepancy <= 0.02,
            confidence = if (totalDiscrepancy <= 0.02) 0.9f else 0.3f,
            message = if (totalDiscrepancy > 0.02) "Items sum doesn't match subtotal" else null
        ))

        return results
    }
}
