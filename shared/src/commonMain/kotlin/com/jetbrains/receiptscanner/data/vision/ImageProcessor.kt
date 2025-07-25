package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for image processing operations
 */
interface ImageProcessor {
    /**
     * Detect receipt boundaries in the image
     */
    suspend fun detectReceiptBoundaries(imageBytes: ByteArray): Result<ReceiptBoundaries>

    /**
     * Correct perspective distortion in receipt image
     */
    suspend fun correctPerspective(imageBytes: ByteArray, boundaries: ReceiptBoundaries): Result<ByteArray>

    /**
     * Enhance image quality for better OCR results
     */
    suspend fun enhanceImage(imageBytes: ByteArray): Result<ByteArray>

    /**
     * Assess image quality in real-time
     */
    fun assessImageQuality(imageBytes: ByteArray): Flow<ImageQualityAssessment>
}

/**
 * Receipt boundary detection result
 */
data class ReceiptBoundaries(
    val corners: List<Point>,
    val confidence: Float,
    val boundingBox: Rectangle
)

/**
 * Point coordinates
 */
data class Point(
    val x: Float,
    val y: Float
)

/**
 * Rectangle definition
 */
data class Rectangle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Image quality assessment result
 */
data class ImageQualityAssessment(
    val lightingScore: Float, // 0.0 to 1.0
    val focusScore: Float, // 0.0 to 1.0
    val angleScore: Float, // 0.0 to 1.0
    val distanceScore: Float, // 0.0 to 1.0
    val overallScore: Float, // 0.0 to 1.0
    val feedback: QualityFeedback
)

/**
 * Quality feedback for user guidance
 */
sealed class QualityFeedback {
    object Perfect : QualityFeedback()
    object MoveCloser : QualityFeedback()
    object MoveFurther : QualityFeedback()
    object ImproveLighting : QualityFeedback()
    object HoldSteady : QualityFeedback()
    object AdjustAngle : QualityFeedback()
    object ShowFullReceipt : QualityFeedback()
}

/**
 * Platform-specific factory for creating ImageProcessor instances
 */
expect object ImageProcessorFactory {
    fun create(): ImageProcessor
}
