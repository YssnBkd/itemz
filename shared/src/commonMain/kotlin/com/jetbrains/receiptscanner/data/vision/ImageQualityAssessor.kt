package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.flow.Flow

/**
 * Interface for real-time image quality assessment
 */
interface ImageQualityAssessor {
    /**
     * Assess image quality in real-time
     */
    fun assessQuality(imageBytes: ByteArray): Flow<ImageQualityAssessment>

    /**
     * Get quality thresholds for different metrics
     */
    fun getQualityThresholds(): QualityThresholds

    /**
     * Determine if image quality is sufficient for OCR processing
     */
    suspend fun isQualitySufficient(assessment: ImageQualityAssessment): Boolean
}

/**
 * Quality thresholds for different assessment metrics
 */
data class QualityThresholds(
    val minimumLightingScore: Float = 0.6f,
    val minimumFocusScore: Float = 0.7f,
    val minimumAngleScore: Float = 0.6f,
    val minimumDistanceScore: Float = 0.5f,
    val minimumOverallScore: Float = 0.7f
)

/**
 * Detailed quality metrics for advanced assessment
 */
data class DetailedQualityMetrics(
    val brightness: Float, // 0.0 to 1.0
    val contrast: Float, // 0.0 to 1.0
    val sharpness: Float, // 0.0 to 1.0
    val noiseLevel: Float, // 0.0 to 1.0 (lower is better)
    val uniformity: Float, // 0.0 to 1.0
    val edgeStrength: Float, // 0.0 to 1.0
    val textDensity: Float, // 0.0 to 1.0
    val receiptCoverage: Float // 0.0 to 1.0
)

/**
 * Enhanced image quality assessment with detailed metrics
 */
data class EnhancedImageQualityAssessment(
    val basicAssessment: ImageQualityAssessment,
    val detailedMetrics: DetailedQualityMetrics,
    val recommendations: List<QualityRecommendation>
)

/**
 * Specific recommendations for improving image quality
 */
sealed class QualityRecommendation {
    object IncreaseLighting : QualityRecommendation()
    object DecreaseLighting : QualityRecommendation()
    object HoldSteadier : QualityRecommendation()
    object MoveCloserToReceipt : QualityRecommendation()
    object MoveFurtherFromReceipt : QualityRecommendation()
    object AdjustAngleToReceipt : QualityRecommendation()
    object EnsureFullReceiptVisible : QualityRecommendation()
    object CleanCameraLens : QualityRecommendation()
    object AvoidShadows : QualityRecommendation()
    object UseFlashlight : QualityRecommendation()
}

/**
 * Platform-specific factory for creating ImageQualityAssessor
 */
expect object ImageQualityAssessorFactory {
    fun create(): ImageQualityAssessor
}
