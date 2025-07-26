package com.jetbrains.receiptscanner.data.camera

import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessment
import com.jetbrains.receiptscanner.data.vision.QualityFeedback
import com.jetbrains.receiptscanner.data.vision.ReceiptDetectionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System for providing real-time camera guidance to users
 */
interface CameraGuidanceSystem {
    /**
     * Process quality assessment and detection results to provide guidance
     */
    fun processAssessment(
        qualityAssessment: ImageQualityAssessment,
        detectionResult: ReceiptDetectionResult?
    )

    /**
     * Get current guidance state
     */
    fun getGuidanceState(): StateFlow<GuidanceState>

    /**
     * Check if conditions are met for automatic capture
     */
    fun shouldAutoCapture(): Boolean

    /**
     * Reset guidance state
     */
    fun reset()
}

/**
 * Current guidance state with visual and haptic feedback
 */
data class GuidanceState(
    val guidance: CameraGuidance,
    val message: String,
    val isReadyForCapture: Boolean,
    val qualityIndicators: QualityIndicators,
    val shouldShowHapticFeedback: Boolean = false
)

/**
 * Visual quality indicators for the camera UI
 */
data class QualityIndicators(
    val lightingStatus: IndicatorStatus,
    val focusStatus: IndicatorStatus,
    val angleStatus: IndicatorStatus,
    val distanceStatus: IndicatorStatus,
    val receiptDetectionStatus: IndicatorStatus
)

/**
 * Status of individual quality indicators
 */
enum class IndicatorStatus {
    GOOD,    // Green indicator
    WARNING, // Yellow indicator
    POOR     // Red indicator
}

/**
 * Implementation of camera guidance system
 */
class CameraGuidanceSystemImpl : CameraGuidanceSystem {
    private val _guidanceState = MutableStateFlow(
        GuidanceState(
            guidance = CameraGuidance.NoReceiptDetected,
            message = "Position receipt in camera view",
            isReadyForCapture = false,
            qualityIndicators = QualityIndicators(
                lightingStatus = IndicatorStatus.POOR,
                focusStatus = IndicatorStatus.POOR,
                angleStatus = IndicatorStatus.POOR,
                distanceStatus = IndicatorStatus.POOR,
                receiptDetectionStatus = IndicatorStatus.POOR
            )
        )
    )

    private var lastGuidance: CameraGuidance? = null

    override fun processAssessment(
        qualityAssessment: ImageQualityAssessment,
        detectionResult: ReceiptDetectionResult?
    ) {
        val indicators = createQualityIndicators(qualityAssessment, detectionResult)
        val guidance = determineGuidance(qualityAssessment, detectionResult)
        val message = getGuidanceMessage(guidance)
        val isReadyForCapture = shouldAutoCapture(qualityAssessment, detectionResult)
        val shouldShowHaptic = guidance != lastGuidance && isReadyForCapture

        _guidanceState.value = GuidanceState(
            guidance = guidance,
            message = message,
            isReadyForCapture = isReadyForCapture,
            qualityIndicators = indicators,
            shouldShowHapticFeedback = shouldShowHaptic
        )

        lastGuidance = guidance
    }

    override fun getGuidanceState(): StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    override fun shouldAutoCapture(): Boolean = _guidanceState.value.isReadyForCapture

    override fun reset() {
        lastGuidance = null
        _guidanceState.value = GuidanceState(
            guidance = CameraGuidance.NoReceiptDetected,
            message = "Position receipt in camera view",
            isReadyForCapture = false,
            qualityIndicators = QualityIndicators(
                lightingStatus = IndicatorStatus.POOR,
                focusStatus = IndicatorStatus.POOR,
                angleStatus = IndicatorStatus.POOR,
                distanceStatus = IndicatorStatus.POOR,
                receiptDetectionStatus = IndicatorStatus.POOR
            )
        )
    }

    private fun createQualityIndicators(
        quality: ImageQualityAssessment,
        detection: ReceiptDetectionResult?
    ): QualityIndicators {
        return QualityIndicators(
            lightingStatus = getIndicatorStatus(quality.lightingScore),
            focusStatus = getIndicatorStatus(quality.focusScore),
            angleStatus = getIndicatorStatus(quality.angleScore),
            distanceStatus = getIndicatorStatus(quality.distanceScore),
            receiptDetectionStatus = if (detection?.isReceiptDetected == true) {
                getIndicatorStatus(detection.confidence)
            } else {
                IndicatorStatus.POOR
            }
        )
    }

    private fun getIndicatorStatus(score: Float): IndicatorStatus {
        return when {
            score >= 0.8f -> IndicatorStatus.GOOD
            score >= 0.6f -> IndicatorStatus.WARNING
            else -> IndicatorStatus.POOR
        }
    }

    private fun determineGuidance(
        quality: ImageQualityAssessment,
        detection: ReceiptDetectionResult?
    ): CameraGuidance {
        // First check if receipt is detected
        if (detection?.isReceiptDetected != true) {
            return CameraGuidance.NoReceiptDetected
        }

        // Receipt is detected, now check quality
        return when (quality.feedback) {
            QualityFeedback.Perfect -> CameraGuidance.Perfect
            QualityFeedback.MoveCloser -> CameraGuidance.MoveCloser
            QualityFeedback.MoveFurther -> CameraGuidance.MoveFurther
            QualityFeedback.ImproveLighting -> CameraGuidance.ImproveLighting
            QualityFeedback.HoldSteady -> CameraGuidance.HoldSteady
            QualityFeedback.AdjustAngle -> CameraGuidance.AdjustAngle
            QualityFeedback.ShowFullReceipt -> CameraGuidance.ShowFullReceipt
        }
    }

    private fun getGuidanceMessage(guidance: CameraGuidance): String {
        return when (guidance) {
            CameraGuidance.Perfect -> "Perfect! Hold steady"
            CameraGuidance.MoveCloser -> "Move closer to receipt"
            CameraGuidance.MoveFurther -> "Move further from receipt"
            CameraGuidance.ImproveLighting -> "Improve lighting"
            CameraGuidance.HoldSteady -> "Hold camera steady"
            CameraGuidance.AdjustAngle -> "Adjust camera angle"
            CameraGuidance.ShowFullReceipt -> "Show full receipt"
            CameraGuidance.ReceiptDetected -> "Receipt detected"
            CameraGuidance.NoReceiptDetected -> "Position receipt in camera view"
        }
    }

    private fun shouldAutoCapture(
        quality: ImageQualityAssessment,
        detection: ReceiptDetectionResult?
    ): Boolean {
        return detection?.isReceiptDetected == true &&
                detection.confidence >= 0.8f &&
                quality.overallScore >= 0.8f
    }
}
