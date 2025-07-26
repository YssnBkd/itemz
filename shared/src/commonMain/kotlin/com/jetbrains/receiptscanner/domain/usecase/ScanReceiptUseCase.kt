package com.jetbrains.receiptscanner.domain.usecase

import com.jetbrains.receiptscanner.data.vision.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Use case for scanning receipts using multi-stage OCR processing
 */
class ScanReceiptUseCase(
    private val ocrEngine: OCREngine,
    private val receiptParser: ReceiptParser
) {
    /**
     * Scan receipt with progress tracking
     */
    fun execute(imageBytes: ByteArray): Flow<ScanProgress> = flow {
        try {
            emit(ScanProgress.Initializing)

            // Initialize OCR engine if needed
            ocrEngine.initialize().getOrThrow()

            // Process image through OCR pipeline
            ocrEngine.processImage(imageBytes).collect { stage ->
                when (stage) {
                    is OCRProcessingStage.Initializing -> {
                        emit(ScanProgress.Processing("Initializing OCR models..."))
                    }
                    is OCRProcessingStage.DetectingText -> {
                        emit(ScanProgress.Processing("Detecting text regions... ${(stage.progress * 100).toInt()}%"))
                    }
                    is OCRProcessingStage.RecognizingText -> {
                        val remainingSeconds = stage.estimatedTimeRemainingMs / 1000
                        emit(ScanProgress.Processing("Recognizing text... ${(stage.progress * 100).toInt()}% (~${remainingSeconds}s remaining)"))
                    }
                    is OCRProcessingStage.PostProcessing -> {
                        emit(ScanProgress.Processing("Processing results... ${(stage.progress * 100).toInt()}%"))
                    }
                    is OCRProcessingStage.Completed -> {
                        emit(ScanProgress.Processing("Parsing receipt structure..."))

                        // Parse OCR results into structured receipt data
                        val receipt = receiptParser.parseReceipt(stage.result)

                        emit(ScanProgress.Completed(receipt))
                    }
                    is OCRProcessingStage.Error -> {
                        emit(ScanProgress.Error(stage.error.message ?: "OCR processing failed"))
                    }
                }
            }

        } catch (error: Exception) {
            emit(ScanProgress.Error(error.message ?: "Receipt scanning failed"))
        }
    }
}

/**
 * Receipt scanning progress states
 */
sealed class ScanProgress {
    object Initializing : ScanProgress()
    data class Processing(val message: String) : ScanProgress()
    data class Completed(val receipt: ParsedReceipt) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}

/**
 * Parsed receipt data structure
 */
data class ParsedReceipt(
    val storeName: String,
    val storeAddress: String?,
    val transactionDate: String?,
    val items: List<ReceiptItem>,
    val subtotal: Double?,
    val tax: Double?,
    val total: Double?,
    val confidence: Float
)

/**
 * Individual receipt item
 */
data class ReceiptItem(
    val name: String,
    val price: Double,
    val quantity: Int = 1,
    val category: String? = null,
    val confidence: Float
)

/**
 * Interface for parsing OCR results into structured receipt data
 */
interface ReceiptParser {
    suspend fun parseReceipt(ocrResult: TextRecognitionResult): ParsedReceipt
}

/**
 * Default implementation of receipt parser
 */
class ReceiptParserImpl : ReceiptParser {

    companion object {
        private val PRICE_PATTERN = Regex("""\$?(\d+\.\d{2})""")
        private val TOTAL_KEYWORDS = listOf("total", "amount due", "balance")
        private val STORE_CHAINS = listOf("walmart", "target", "kroger", "safeway", "publix")
    }

    override suspend fun parseReceipt(ocrResult: TextRecognitionResult): ParsedReceipt {
        val lines = ocrResult.recognizedLines

        // Extract store information
        val storeName = extractStoreName(lines)
        val storeAddress = extractStoreAddress(lines)
        val transactionDate = extractTransactionDate(lines)

        // Extract items and prices
        val items = extractItems(lines)

        // Extract totals
        val totals = extractTotals(lines)

        return ParsedReceipt(
            storeName = storeName,
            storeAddress = storeAddress,
            transactionDate = transactionDate,
            items = items,
            subtotal = totals.subtotal,
            tax = totals.tax,
            total = totals.total,
            confidence = ocrResult.overallConfidence
        )
    }

    private fun extractStoreName(lines: List<RecognizedTextLine>): String {
        // Look for store name in first few lines
        val topLines = lines.take(5)

        for (line in topLines) {
            val text = line.text.lowercase()
            for (chain in STORE_CHAINS) {
                if (text.contains(chain)) {
                    return line.text.trim()
                }
            }
        }

        // Fallback to first line if no known chain found
        return lines.firstOrNull()?.text?.trim() ?: "Unknown Store"
    }

    private fun extractStoreAddress(lines: List<RecognizedTextLine>): String? {
        // Look for address patterns in first 10 lines
        val topLines = lines.take(10)

        for (line in topLines) {
            val text = line.text.lowercase()
            if (text.contains(Regex("""\d+\s+\w+\s+(st|street|ave|avenue|rd|road|blvd|boulevard)"""))) {
                return line.text.trim()
            }
        }

        return null
    }

    private fun extractTransactionDate(lines: List<RecognizedTextLine>): String? {
        // Look for date patterns
        val datePattern = Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")

        for (line in lines) {
            val match = datePattern.find(line.text)
            if (match != null) {
                return match.value
            }
        }

        return null
    }

    private fun extractItems(lines: List<RecognizedTextLine>): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()

        for (line in lines) {
            val text = line.text
            val priceMatch = PRICE_PATTERN.find(text)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull()
                if (price != null && price > 0) {
                    // Extract item name (text before price)
                    val itemName = text.substring(0, priceMatch.range.first).trim()

                    if (itemName.isNotBlank() && !isTotal(itemName)) {
                        items.add(
                            ReceiptItem(
                                name = itemName,
                                price = price,
                                quantity = 1,
                                confidence = line.confidence
                            )
                        )
                    }
                }
            }
        }

        return items
    }

    private fun extractTotals(lines: List<RecognizedTextLine>): ReceiptTotals {
        var subtotal: Double? = null
        var tax: Double? = null
        var total: Double? = null

        for (line in lines) {
            val text = line.text.lowercase()
            val priceMatch = PRICE_PATTERN.find(line.text)
            val price = priceMatch?.groupValues?.get(1)?.toDoubleOrNull()

            if (price != null) {
                when {
                    text.contains("subtotal") -> subtotal = price
                    text.contains("tax") -> tax = price
                    TOTAL_KEYWORDS.any { text.contains(it) } -> total = price
                }
            }
        }

        return ReceiptTotals(subtotal, tax, total)
    }

    private fun isTotal(text: String): Boolean {
        val lowerText = text.lowercase()
        return TOTAL_KEYWORDS.any { lowerText.contains(it) } ||
               lowerText.contains("subtotal") ||
               lowerText.contains("tax")
    }

    private data class ReceiptTotals(
        val subtotal: Double?,
        val tax: Double?,
        val total: Double?
    )
}
