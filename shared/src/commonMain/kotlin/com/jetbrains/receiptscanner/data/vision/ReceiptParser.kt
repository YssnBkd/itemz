package com.jetbrains.receiptscanner.data.vision

import com.jetbrains.receiptscanner.domain.model.ItemCategory
import kotlinx.datetime.Instant

/**
 * Receipt-specific text structure analysis system
 */
interface ReceiptParser {
    /**
     * Parse OCR results into structured receipt data
     */
    suspend fun parseReceipt(ocrResult: TextRecognitionResult): Result<ReceiptData>

    /**
     * Classify receipt layout based on merchant patterns
     */
    fun classifyReceiptLayout(lines: List<RecognizedTextLine>): ReceiptLayout

    /**
     * Extract merchant information from header section
     */
    fun extractMerchantInfo(headerLines: List<RecognizedTextLine>): MerchantInfo

    /**
     * Parse item lines with intelligent quantity and price detection
     */
    fun parseItemLines(itemLines: List<RecognizedTextLine>): List<ParsedReceiptItem>

    /**
     * Extract totals section (subtotal, tax, final total)
     */
    fun extractTotals(totalLines: List<RecognizedTextLine>): ReceiptTotals

    /**
     * Calculate confidence score for parsed receipt
     */
    fun calculateConfidenceScore(receiptData: ReceiptData, ocrResult: TextRecognitionResult): Float
}

/**
 * Structured receipt data output
 */
data class ReceiptData(
    val merchantInfo: MerchantInfo,
    val items: List<ParsedReceiptItem>,
    val totals: ReceiptTotals,
    val dateTime: Instant?,
    val layout: ReceiptLayout,
    val textRegions: ReceiptTextRegions,
    val confidenceScore: Float,
    val validationResults: List<ValidationResult>
)

/**
 * Merchant information extracted from receipt header
 */
data class MerchantInfo(
    val name: String,
    val address: String?,
    val chainId: String?,
    val phoneNumber: String?,
    val confidence: Float
)

/**
 * Parsed receipt item with intelligent extraction
 */
data class ParsedReceiptItem(
    val name: String,
    val normalizedName: String,
    val quantity: Int,
    val unitPrice: Double,
    val lineTotal: Double,
    val category: ItemCategory,
    val confidence: Float,
    val quantityDetection: QuantityDetection?,
    val priceValidation: PriceValidation,
    val boundingBox: Rectangle
)

/**
 * Quantity detection information
 */
data class QuantityDetection(
    val rawText: String, // e.g., "2x", "3 @", "4 for"
    val detectedQuantity: Int,
    val confidence: Float
)

/**
 * Price validation information
 */
data class PriceValidation(
    val isValid: Boolean,
    val calculatedTotal: Double,
    val actualTotal: Double,
    val discrepancy: Double,
    val confidence: Float
)

/**
 * Receipt totals with validation
 */
data class ReceiptTotals(
    val subtotal: Double,
    val taxAmount: Double,
    val total: Double,
    val discounts: Double = 0.0,
    val confidence: Float,
    val validation: TotalsValidation
)

/**
 * Totals validation information
 */
data class TotalsValidation(
    val itemsSum: Double,
    val calculatedSubtotal: Double,
    val calculatedTotal: Double,
    val isValid: Boolean,
    val discrepancy: Double
)

/**
 * Receipt layout classification
 */
enum class ReceiptLayout {
    WALMART,
    TARGET,
    KROGER,
    SAFEWAY,
    COSTCO,
    GENERIC_GROCERY,
    UNKNOWN
}

/**
 * Text regions classification
 */
data class ReceiptTextRegions(
    val header: List<RecognizedTextLine>,
    val items: List<RecognizedTextLine>,
    val totals: List<RecognizedTextLine>,
    val footer: List<RecognizedTextLine>
)

/**
 * Validation result for different aspects of parsing
 */
data class ValidationResult(
    val type: ValidationType,
    val isValid: Boolean,
    val confidence: Float,
    val message: String?
)

/**
 * Types of validation performed
 */
enum class ValidationType {
    MERCHANT_HEADER,
    ITEM_STRUCTURE,
    PRICE_FORMAT,
    MATHEMATICAL_CONSISTENCY,
    RECEIPT_COMPLETENESS,
    DATE_FORMAT
}
