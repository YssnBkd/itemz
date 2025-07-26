package com.jetbrains.receiptscanner.data.vision

import com.jetbrains.receiptscanner.domain.model.ItemCategory
import kotlin.math.abs

/**
 * Intelligent item line parsing with quantity detection and price validation
 */
class ItemLineParser {

    companion object {
        // Regex patterns for price detection
        private val PRICE_PATTERNS = listOf(
            Regex("""\$(\d+\.\d{2})"""), // $12.34
            Regex("""(\d+\.\d{2})\$"""), // 12.34$
            Regex("""(\d+\.\d{2})"""),   // 12.34
            Regex("""\$(\d+)"""),        // $12
            Regex("""(\d+)\$""")         // 12$
        )

        // Regex patterns for quantity detection
        private val QUANTITY_PATTERNS = listOf(
            Regex("""(\d+)x\s*(.+)""", RegexOption.IGNORE_CASE), // 2x Bananas
            Regex("""(\d+)\s*@\s*(.+)""", RegexOption.IGNORE_CASE), // 3 @ Apples
            Regex("""(\d+)\s*for\s*(.+)""", RegexOption.IGNORE_CASE), // 4 for Oranges
            Regex("""(\d+)\s*ea\s*(.+)""", RegexOption.IGNORE_CASE), // 2 ea Milk
            Regex("""(\d+)\s*each\s*(.+)""", RegexOption.IGNORE_CASE), // 3 each Bread
            Regex("""(\d+)\s*pc\s*(.+)""", RegexOption.IGNORE_CASE), // 5 pc Chicken
            Regex("""(\d+)\s*pcs\s*(.+)""", RegexOption.IGNORE_CASE) // 5 pcs Donuts
        )

        // Common grocery item patterns for categorization
        private val CATEGORY_PATTERNS = mapOf(
            ItemCategory.PRODUCE to listOf(
                "banana", "apple", "orange", "grape", "strawberry", "lettuce", "tomato",
                "onion", "potato", "carrot", "broccoli", "spinach", "avocado", "lemon"
            ),
            ItemCategory.DAIRY to listOf(
                "milk", "cheese", "yogurt", "butter", "cream", "eggs", "sour cream"
            ),
            ItemCategory.MEAT to listOf(
                "chicken", "beef", "pork", "fish", "turkey", "ham", "bacon", "sausage"
            ),
            ItemCategory.BEVERAGES to listOf(
                "soda", "juice", "water", "coffee", "tea", "beer", "wine", "energy drink"
            ),
            ItemCategory.HOUSEHOLD to listOf(
                "detergent", "soap", "shampoo", "toothpaste", "toilet paper", "paper towel"
            ),
            ItemCategory.SNACKS to listOf(
                "chips", "cookies", "candy", "crackers", "nuts", "popcorn", "chocolate"
            )
        )
    }

    /**
     * Parse individual item line with quantity detection and price validation
     */
    fun parseItemLine(line: RecognizedTextLine): ParsedReceiptItem? {
        val text = line.text.trim()
        if (text.isEmpty()) return null

        // Extract quantity information
        val quantityDetection = detectQuantity(text)
        val itemName = quantityDetection?.let {
            extractItemNameAfterQuantity(text, it)
        } ?: text

        // Extract prices from the line
        val prices = extractPrices(text)
        if (prices.isEmpty()) return null

        // Determine unit price and line total
        val (unitPrice, lineTotal) = determinePrices(prices, quantityDetection?.detectedQuantity ?: 1)

        // Validate price calculation
        val priceValidation = validatePriceCalculation(
            quantity = quantityDetection?.detectedQuantity ?: 1,
            unitPrice = unitPrice,
            lineTotal = lineTotal
        )

        // Categorize item
        val category = categorizeItem(itemName)

        // Calculate confidence score
        val confidence = calculateItemConfidence(
            line = line,
            quantityDetection = quantityDetection,
            priceValidation = priceValidation,
            prices = prices
        )

        return ParsedReceiptItem(
            name = itemName,
            normalizedName = normalizeItemName(itemName),
            quantity = quantityDetection?.detectedQuantity ?: 1,
            unitPrice = unitPrice,
            lineTotal = lineTotal,
            category = category,
            confidence = confidence,
            quantityDetection = quantityDetection,
            priceValidation = priceValidation,
            boundingBox = line.boundingBox
        )
    }

    /**
     * Detect quantity information in item line
     */
    private fun detectQuantity(text: String): QuantityDetection? {
        for (pattern in QUANTITY_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val quantity = match.groupValues[1].toIntOrNull() ?: 1
                return QuantityDetection(
                    rawText = match.groupValues[0],
                    detectedQuantity = quantity,
                    confidence = 0.9f
                )
            }
        }
        return null
    }

    /**
     * Extract item name after removing quantity prefix
     */
    private fun extractItemNameAfterQuantity(text: String, quantityDetection: QuantityDetection): String {
        return text.replace(quantityDetection.rawText, "").trim()
    }

    /**
     * Extract all price values from text line
     */
    private fun extractPrices(text: String): List<Double> {
        val prices = mutableListOf<Double>()

        for (pattern in PRICE_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val priceStr = match.groupValues[1]
                val price = priceStr.toDoubleOrNull()
                if (price != null && price > 0) {
                    prices.add(price)
                }
            }
        }

        return prices.distinct().sorted()
    }

    /**
     * Determine unit price and line total from extracted prices
     */
    private fun determinePrices(prices: List<Double>, quantity: Int): Pair<Double, Double> {
        return when {
            prices.isEmpty() -> 0.0 to 0.0
            prices.size == 1 -> {
                val price = prices[0]
                if (quantity > 1) {
                    // If quantity > 1, assume the price is line total
                    (price / quantity) to price
                } else {
                    price to price
                }
            }
            prices.size == 2 -> {
                // Assume smaller price is unit price, larger is line total
                val unitPrice = prices[0]
                val lineTotal = prices[1]
                unitPrice to lineTotal
            }
            else -> {
                // Multiple prices, use heuristics
                val lineTotal = prices.last() // Usually the rightmost/largest price
                val unitPrice = if (quantity > 1) lineTotal / quantity else lineTotal
                unitPrice to lineTotal
            }
        }
    }

    /**
     * Validate price calculation consistency
     */
    private fun validatePriceCalculation(quantity: Int, unitPrice: Double, lineTotal: Double): PriceValidation {
        val calculatedTotal = unitPrice * quantity
        val discrepancy = abs(calculatedTotal - lineTotal)
        val tolerance = 0.02 // 2 cent tolerance for rounding

        return PriceValidation(
            isValid = discrepancy <= tolerance,
            calculatedTotal = calculatedTotal,
            actualTotal = lineTotal,
            discrepancy = discrepancy,
            confidence = if (discrepancy <= tolerance) 0.95f else 0.3f
        )
    }

    /**
     * Categorize item based on name patterns
     */
    private fun categorizeItem(itemName: String): ItemCategory {
        val normalizedName = itemName.lowercase()

        for ((category, keywords) in CATEGORY_PATTERNS) {
            for (keyword in keywords) {
                if (normalizedName.contains(keyword)) {
                    return category
                }
            }
        }

        return ItemCategory.PACKAGED_GOODS // Default category
    }

    /**
     * Normalize item name for consistent storage
     */
    private fun normalizeItemName(itemName: String): String {
        return itemName.trim()
            .replace(Regex("""\s+"""), " ") // Normalize whitespace
            .lowercase()
            .replaceFirstChar { it.uppercase() } // Capitalize first letter
    }

    /**
     * Calculate confidence score for parsed item
     */
    private fun calculateItemConfidence(
        line: RecognizedTextLine,
        quantityDetection: QuantityDetection?,
        priceValidation: PriceValidation,
        prices: List<Double>
    ): Float {
        var confidence = line.confidence

        // Boost confidence if quantity was detected
        if (quantityDetection != null) {
            confidence = (confidence + quantityDetection.confidence) / 2f
        }

        // Factor in price validation
        confidence = (confidence + priceValidation.confidence) / 2f

        // Reduce confidence if no prices found
        if (prices.isEmpty()) {
            confidence *= 0.3f
        }

        // Reduce confidence if item name is too short or suspicious
        val itemName = line.text.trim()
        if (itemName.length < 3) {
            confidence *= 0.5f
        }

        return confidence.coerceIn(0f, 1f)
    }
}
