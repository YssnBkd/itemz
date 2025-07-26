package com.jetbrains.receiptscanner.data.vision

import kotlin.math.abs
import kotlin.math.min

/**
 * Multi-level confidence scoring system for receipt parsing
 */
class ConfidenceScorer {

    companion object {
        // Weight factors for different confidence components
        private const val OCR_CONFIDENCE_WEIGHT = 0.3f
        private const val STRUCTURAL_CONFIDENCE_WEIGHT = 0.25f
        private const val MATHEMATICAL_CONFIDENCE_WEIGHT = 0.25f
        private const val MERCHANT_CONFIDENCE_WEIGHT = 0.2f

        // Thresholds for confidence levels
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.8f
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.6f

        // Known grocery chain patterns for merchant validation
        private val KNOWN_GROCERY_CHAINS = setOf(
            "walmart", "target", "kroger", "safeway", "costco", "whole foods",
            "trader joe's", "publix", "wegmans", "harris teeter", "giant",
            "stop & shop", "food lion", "albertsons", "vons", "ralphs",
            "king soopers", "fred meyer", "qfc", "smith's", "fry's"
        )
    }

    /**
     * Calculate overall confidence score for parsed receipt data
     */
    fun calculateOverallConfidence(
        receiptData: ReceiptData,
        ocrResult: TextRecognitionResult
    ): ConfidenceScore {
        val ocrConfidence = calculateOCRConfidence(ocrResult)
        val structuralConfidence = calculateStructuralConfidence(receiptData)
        val mathematicalConfidence = calculateMathematicalConfidence(receiptData)
        val merchantConfidence = calculateMerchantConfidence(receiptData.merchantInfo)

        val overallScore = (
            ocrConfidence.score * OCR_CONFIDENCE_WEIGHT +
            structuralConfidence.score * STRUCTURAL_CONFIDENCE_WEIGHT +
            mathematicalConfidence.score * MATHEMATICAL_CONFIDENCE_WEIGHT +
            merchantConfidence.score * MERCHANT_CONFIDENCE_WEIGHT
        )

        return ConfidenceScore(
            overallScore = overallScore,
            level = determineConfidenceLevel(overallScore),
            components = ConfidenceComponents(
                ocrConfidence = ocrConfidence,
                structuralConfidence = structuralConfidence,
                mathematicalConfidence = mathematicalConfidence,
                merchantConfidence = merchantConfidence
            ),
            issues = identifyConfidenceIssues(
                ocrConfidence, structuralConfidence,
                mathematicalConfidence, merchantConfidence
            )
        )
    }

    /**
     * Calculate OCR character confidence aggregation from PaddleOCR
     */
    private fun calculateOCRConfidence(ocrResult: TextRecognitionResult): ComponentConfidence {
        if (ocrResult.recognizedLines.isEmpty()) {
            return ComponentConfidence(
                score = 0.1f,
                details = "No text recognized",
                factors = emptyMap()
            )
        }

        val lineConfidences = ocrResult.recognizedLines.map { it.confidence }
        val averageConfidence = lineConfidences.average().toFloat()
        val minConfidence = lineConfidences.minOrNull() ?: 0f
        val confidenceVariance = calculateVariance(lineConfidences)

        // Penalize high variance in confidence scores
        val variancePenalty = min(confidenceVariance * 2f, 0.3f)
        val adjustedScore = (averageConfidence - variancePenalty).coerceIn(0f, 1f)

        val factors = mapOf(
            "average_confidence" to averageConfidence,
            "min_confidence" to minConfidence,
            "confidence_variance" to confidenceVariance,
            "lines_count" to ocrResult.recognizedLines.size.toFloat()
        )

        val details = when {
            adjustedScore >= HIGH_CONFIDENCE_THRESHOLD -> "High OCR confidence across all text"
            adjustedScore >= MEDIUM_CONFIDENCE_THRESHOLD -> "Moderate OCR confidence with some uncertainty"
            else -> "Low OCR confidence, text may be unclear or damaged"
        }

        return ComponentConfidence(adjustedScore, details, factors)
    }

    /**
     * Calculate structural validation confidence based on receipt format compliance
     */
    private fun calculateStructuralConfidence(receiptData: ReceiptData): ComponentConfidence {
        var structuralScore = 0f
        val factors = mutableMapOf<String, Float>()
        val issues = mutableListOf<String>()

        // Check for essential receipt sections
        val hasHeader = receiptData.textRegions.header.isNotEmpty()
        val hasItems = receiptData.textRegions.items.isNotEmpty()
        val hasTotals = receiptData.textRegions.totals.isNotEmpty()

        if (hasHeader) structuralScore += 0.2f else issues.add("Missing header section")
        if (hasItems) structuralScore += 0.4f else issues.add("Missing items section")
        if (hasTotals) structuralScore += 0.2f else issues.add("Missing totals section")

        factors["has_header"] = if (hasHeader) 1f else 0f
        factors["has_items"] = if (hasItems) 1f else 0f
        factors["has_totals"] = if (hasTotals) 1f else 0f

        // Check item structure quality
        val validItems = receiptData.items.count { it.priceValidation.isValid }
        val itemStructureScore = if (receiptData.items.isNotEmpty()) {
            validItems.toFloat() / receiptData.items.size
        } else 0f

        structuralScore += itemStructureScore * 0.2f
        factors["item_structure_score"] = itemStructureScore

        if (itemStructureScore < 0.5f) {
            issues.add("Many items have structural issues")
        }

        val details = if (issues.isEmpty()) {
            "Receipt structure is well-formed"
        } else {
            "Structural issues: ${issues.joinToString(", ")}"
        }

        return ComponentConfidence(structuralScore, details, factors)
    }

    /**
     * Calculate mathematical validation confidence (totals, calculations)
     */
    private fun calculateMathematicalConfidence(receiptData: ReceiptData): ComponentConfidence {
        var mathScore = 0f
        val factors = mutableMapOf<String, Float>()
        val issues = mutableListOf<String>()

        // Validate item price calculations
        val itemCalculationScores = receiptData.items.map { item ->
            val expectedTotal = item.unitPrice * item.quantity
            val discrepancy = abs(expectedTotal - item.lineTotal)
            val tolerance = 0.02f // 2 cent tolerance

            if (discrepancy <= tolerance) 1f else 0f
        }

        val itemCalculationScore = if (itemCalculationScores.isNotEmpty()) {
            itemCalculationScores.average().toFloat()
        } else 0f

        mathScore += itemCalculationScore * 0.4f
        factors["item_calculation_score"] = itemCalculationScore

        if (itemCalculationScore < 0.8f) {
            issues.add("Item price calculations don't match")
        }

        // Validate receipt totals
        val itemsSum = receiptData.items.sumOf { it.lineTotal }
        val subtotalDiscrepancy = abs(itemsSum - receiptData.totals.subtotal)
        val subtotalValid = subtotalDiscrepancy <= 0.02

        if (subtotalValid) mathScore += 0.3f else issues.add("Subtotal doesn't match items sum")
        factors["subtotal_valid"] = if (subtotalValid) 1f else 0f

        // Validate final total calculation
        val expectedTotal = receiptData.totals.subtotal + receiptData.totals.taxAmount - receiptData.totals.discounts
        val totalDiscrepancy = abs(expectedTotal - receiptData.totals.total)
        val totalValid = totalDiscrepancy <= 0.02

        if (totalValid) mathScore += 0.3f else issues.add("Final total calculation is incorrect")
        factors["total_valid"] = if (totalValid) 1f else 0f

        val details = if (issues.isEmpty()) {
            "All mathematical calculations are consistent"
        } else {
            "Mathematical issues: ${issues.joinToString(", ")}"
        }

        return ComponentConfidence(mathScore, details, factors)
    }

    /**
     * Calculate merchant pattern matching confidence against known grocery chains
     */
    private fun calculateMerchantConfidence(merchantInfo: MerchantInfo): ComponentConfidence {
        val merchantName = merchantInfo.name.lowercase().trim()

        // Check for exact matches
        val exactMatch = KNOWN_GROCERY_CHAINS.any { chain ->
            merchantName.contains(chain) || chain.contains(merchantName)
        }

        // Check for partial matches
        val partialMatch = KNOWN_GROCERY_CHAINS.any { chain ->
            val chainWords = chain.split(" ")
            val merchantWords = merchantName.split(" ")

            chainWords.any { chainWord ->
                merchantWords.any { merchantWord ->
                    chainWord.length > 3 && merchantWord.length > 3 &&
                    (chainWord.contains(merchantWord) || merchantWord.contains(chainWord))
                }
            }
        }

        val merchantScore = when {
            exactMatch -> 1.0f
            partialMatch -> 0.7f
            merchantName.length > 3 -> 0.4f // At least has some merchant name
            else -> 0.1f
        }

        val factors = mapOf(
            "exact_match" to if (exactMatch) 1f else 0f,
            "partial_match" to if (partialMatch) 1f else 0f,
            "name_length" to merchantName.length.toFloat(),
            "base_confidence" to merchantInfo.confidence
        )

        // Factor in the original OCR confidence for the merchant name
        val adjustedScore = (merchantScore * 0.7f + merchantInfo.confidence * 0.3f).coerceIn(0f, 1f)

        val details = when {
            exactMatch -> "Recognized known grocery chain: $merchantName"
            partialMatch -> "Partially matches known grocery chain pattern"
            merchantName.length > 3 -> "Merchant name detected but not in known chains"
            else -> "Merchant name unclear or missing"
        }

        return ComponentConfidence(adjustedScore, details, factors)
    }

    /**
     * Determine confidence level based on overall score
     */
    private fun determineConfidenceLevel(score: Float): ConfidenceLevel {
        return when {
            score >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceLevel.HIGH
            score >= MEDIUM_CONFIDENCE_THRESHOLD -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    /**
     * Identify specific confidence issues that need attention
     */
    private fun identifyConfidenceIssues(
        ocrConfidence: ComponentConfidence,
        structuralConfidence: ComponentConfidence,
        mathematicalConfidence: ComponentConfidence,
        merchantConfidence: ComponentConfidence
    ): List<ConfidenceIssue> {
        val issues = mutableListOf<ConfidenceIssue>()

        if (ocrConfidence.score < MEDIUM_CONFIDENCE_THRESHOLD) {
            issues.add(ConfidenceIssue(
                type = ConfidenceIssueType.LOW_OCR_CONFIDENCE,
                severity = if (ocrConfidence.score < 0.3f) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                description = ocrConfidence.details,
                suggestion = "Consider retaking the photo with better lighting or focus"
            ))
        }

        if (structuralConfidence.score < MEDIUM_CONFIDENCE_THRESHOLD) {
            issues.add(ConfidenceIssue(
                type = ConfidenceIssueType.STRUCTURAL_ISSUES,
                severity = if (structuralConfidence.score < 0.3f) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                description = structuralConfidence.details,
                suggestion = "Manual review recommended for receipt structure"
            ))
        }

        if (mathematicalConfidence.score < MEDIUM_CONFIDENCE_THRESHOLD) {
            issues.add(ConfidenceIssue(
                type = ConfidenceIssueType.MATHEMATICAL_INCONSISTENCY,
                severity = IssueSeverity.HIGH,
                description = mathematicalConfidence.details,
                suggestion = "Review and correct item prices and totals"
            ))
        }

        if (merchantConfidence.score < MEDIUM_CONFIDENCE_THRESHOLD) {
            issues.add(ConfidenceIssue(
                type = ConfidenceIssueType.UNKNOWN_MERCHANT,
                severity = IssueSeverity.LOW,
                description = merchantConfidence.details,
                suggestion = "Verify merchant name is correct"
            ))
        }

        return issues
    }

    /**
     * Calculate variance of a list of float values
     */
    private fun calculateVariance(values: List<Float>): Float {
        if (values.size < 2) return 0f

        val mean = values.average().toFloat()
        val squaredDifferences = values.map { (it - mean) * (it - mean) }
        return squaredDifferences.average().toFloat()
    }
}

/**
 * Overall confidence score with detailed breakdown
 */
data class ConfidenceScore(
    val overallScore: Float,
    val level: ConfidenceLevel,
    val components: ConfidenceComponents,
    val issues: List<ConfidenceIssue>
)

/**
 * Individual component confidence score
 */
data class ComponentConfidence(
    val score: Float,
    val details: String,
    val factors: Map<String, Float>
)

/**
 * Breakdown of confidence components
 */
data class ConfidenceComponents(
    val ocrConfidence: ComponentConfidence,
    val structuralConfidence: ComponentConfidence,
    val mathematicalConfidence: ComponentConfidence,
    val merchantConfidence: ComponentConfidence
)

/**
 * Confidence level categories
 */
enum class ConfidenceLevel {
    HIGH,    // >= 0.8 - Very reliable, minimal review needed
    MEDIUM,  // >= 0.6 - Mostly reliable, some review recommended
    LOW      // < 0.6 - Requires manual review and correction
}

/**
 * Specific confidence issue that needs attention
 */
data class ConfidenceIssue(
    val type: ConfidenceIssueType,
    val severity: IssueSeverity,
    val description: String,
    val suggestion: String
)

/**
 * Types of confidence issues
 */
enum class ConfidenceIssueType {
    LOW_OCR_CONFIDENCE,
    STRUCTURAL_ISSUES,
    MATHEMATICAL_INCONSISTENCY,
    UNKNOWN_MERCHANT
}

/**
 * Severity levels for confidence issues
 */
enum class IssueSeverity {
    LOW,     // Minor issue, doesn't significantly impact usability
    MEDIUM,  // Moderate issue, may require user attention
    HIGH     // Major issue, likely requires manual correction
}
