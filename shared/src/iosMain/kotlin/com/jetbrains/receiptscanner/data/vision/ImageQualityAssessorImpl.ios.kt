package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * iOS factory for creating ImageQualityAssessor
 */
actual object ImageQualityAssessorFactory {
    actual fun create(): ImageQualityAssessor = ImageQualityAssessorImpl()
}

/**
 * iOS implementation of ImageQualityAssessor using simplified native APIs
 */
class ImageQualityAssessorImpl : ImageQualityAssessor {

    private val qualityThresholds = QualityThresholds()

    override fun assessQuality(imageBytes: ByteArray): Flow<ImageQualityAssessment> = flow {
        val assessment = withContext(Dispatchers.Default) {
            // Simplified quality assessment for iOS
            // In a real implementation, would use Core Image and Vision framework

            val detailedMetrics = calculateDetailedMetrics()
            val basicAssessment = createBasicAssessment(detailedMetrics)

            basicAssessment
        }
        emit(assessment)
    }

    override fun getQualityThresholds(): QualityThresholds = qualityThresholds

    override suspend fun isQualitySufficient(assessment: ImageQualityAssessment): Boolean {
        return assessment.lightingScore >= qualityThresholds.minimumLightingScore &&
                assessment.focusScore >= qualityThresholds.minimumFocusScore &&
                assessment.angleScore >= qualityThresholds.minimumAngleScore &&
                assessment.distanceScore >= qualityThresholds.minimumDistanceScore &&
                assessment.overallScore >= qualityThresholds.minimumOverallScore
    }

    private fun calculateDetailedMetrics(): DetailedQualityMetrics {
        // Simplified metrics for iOS
        return DetailedQualityMetrics(
            brightness = 0.8f,
            contrast = 0.8f,
            sharpness = 0.8f,
            noiseLevel = 0.2f,
            uniformity = 0.8f,
            edgeStrength = 0.8f,
            textDensity = 0.6f,
            receiptCoverage = 0.8f
        )
    }

    private fun createBasicAssessment(metrics: DetailedQualityMetrics): ImageQualityAssessment {
        val lightingScore = (metrics.brightness + metrics.uniformity) / 2.0f
        val focusScore = (metrics.sharpness + (1.0f - metrics.noiseLevel)) / 2.0f
        val angleScore = (metrics.edgeStrength + metrics.receiptCoverage) / 2.0f
        val distanceScore = metrics.receiptCoverage

        val overallScore = (lightingScore + focusScore + angleScore + distanceScore) / 4.0f
        val feedback = determineFeedback(lightingScore, focusScore, angleScore, distanceScore)

        return ImageQualityAssessment(
            lightingScore = lightingScore,
            focusScore = focusScore,
            angleScore = angleScore,
            distanceScore = distanceScore,
            overallScore = overallScore,
            feedback = feedback
        )
    }

    private fun determineFeedback(
        lightingScore: Float,
        focusScore: Float,
        angleScore: Float,
        distanceScore: Float
    ): QualityFeedback {
        val overallScore = (lightingScore + focusScore + angleScore + distanceScore) / 4.0f

        return when {
            overallScore > 0.8f -> QualityFeedback.Perfect
            lightingScore < 0.5f -> QualityFeedback.ImproveLighting
            focusScore < 0.5f -> QualityFeedback.HoldSteady
            angleScore < 0.5f -> QualityFeedback.AdjustAngle
            distanceScore < 0.4f -> QualityFeedback.MoveCloser
            distanceScore > 0.8f -> QualityFeedback.MoveFurther
            else -> QualityFeedback.ShowFullReceipt
        }
    }
}
