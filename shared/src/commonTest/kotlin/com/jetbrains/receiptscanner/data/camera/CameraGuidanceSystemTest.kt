package com.jetbrains.receiptscanner.data.camera

import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessment
import com.jetbrains.receiptscanner.data.vision.QualityFeedback
import com.jetbrains.receiptscanner.data.vision.ReceiptBoundaries
import com.jetbrains.receiptscanner.data.vision.ReceiptDetectionResult
import com.jetbrains.receiptscanner.data.vision.Point
import com.jetbrains.receiptscanner.data.vision.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraGuidanceSystemTest {

    private val guidanceSystem = CameraGuidanceSystemImpl()

    @Test
    fun `should provide no receipt detected guidance when receipt is not detected`() {
        // Given
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.9f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.9f,
            overallScore = 0.9f,
            feedback = QualityFeedback.Perfect
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = false,
            boundaries = null,
            confidence = 0.3f,
            processingTimeMs = 100L
        )

        // When
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertEquals(CameraGuidance.NoReceiptDetected, state.guidance)
        assertEquals("Position receipt in camera view", state.message)
        assertFalse(state.isReadyForCapture)
        assertEquals(IndicatorStatus.POOR, state.qualityIndicators.receiptDetectionStatus)
    }

    @Test
    fun `should provide perfect guidance when receipt is detected with high quality`() {
        // Given
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.9f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.9f,
            overallScore = 0.9f,
            feedback = QualityFeedback.Perfect
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = true,
            boundaries = createReceiptBoundaries(),
            confidence = 0.9f,
            processingTimeMs = 100L
        )

        // When
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertEquals(CameraGuidance.Perfect, state.guidance)
        assertEquals("Perfect! Hold steady", state.message)
        assertTrue(state.isReadyForCapture)
        assertEquals(IndicatorStatus.GOOD, state.qualityIndicators.receiptDetectionStatus)
        assertEquals(IndicatorStatus.GOOD, state.qualityIndicators.lightingStatus)
        assertEquals(IndicatorStatus.GOOD, state.qualityIndicators.focusStatus)
    }

    @Test
    fun `should provide move closer guidance when distance score is low`() {
        // Given
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.9f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.4f,
            overallScore = 0.6f,
            feedback = QualityFeedback.MoveCloser
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = true,
            boundaries = createReceiptBoundaries(),
            confidence = 0.8f,
            processingTimeMs = 100L
        )

        // When
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertEquals(CameraGuidance.MoveCloser, state.guidance)
        assertEquals("Move closer to receipt", state.message)
        assertFalse(state.isReadyForCapture)
        assertEquals(IndicatorStatus.POOR, state.qualityIndicators.distanceStatus)
    }

    @Test
    fun `should provide improve lighting guidance when lighting score is low`() {
        // Given
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.4f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.9f,
            overallScore = 0.6f,
            feedback = QualityFeedback.ImproveLighting
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = true,
            boundaries = createReceiptBoundaries(),
            confidence = 0.8f,
            processingTimeMs = 100L
        )

        // When
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertEquals(CameraGuidance.ImproveLighting, state.guidance)
        assertEquals("Improve lighting", state.message)
        assertFalse(state.isReadyForCapture)
        assertEquals(IndicatorStatus.POOR, state.qualityIndicators.lightingStatus)
    }

    @Test
    fun `should not be ready for auto capture when confidence is low`() {
        // Given
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.9f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.9f,
            overallScore = 0.9f,
            feedback = QualityFeedback.Perfect
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = true,
            boundaries = createReceiptBoundaries(),
            confidence = 0.7f, // Below threshold
            processingTimeMs = 100L
        )

        // When
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertFalse(state.isReadyForCapture)
        assertFalse(guidanceSystem.shouldAutoCapture())
    }

    @Test
    fun `should reset guidance state correctly`() {
        // Given - set up some state first
        val qualityAssessment = createQualityAssessment(
            lightingScore = 0.9f,
            focusScore = 0.9f,
            angleScore = 0.9f,
            distanceScore = 0.9f,
            overallScore = 0.9f,
            feedback = QualityFeedback.Perfect
        )
        val detectionResult = ReceiptDetectionResult(
            isReceiptDetected = true,
            boundaries = createReceiptBoundaries(),
            confidence = 0.9f,
            processingTimeMs = 100L
        )
        guidanceSystem.processAssessment(qualityAssessment, detectionResult)

        // When
        guidanceSystem.reset()

        // Then
        val state = guidanceSystem.getGuidanceState().value
        assertEquals(CameraGuidance.NoReceiptDetected, state.guidance)
        assertEquals("Position receipt in camera view", state.message)
        assertFalse(state.isReadyForCapture)
        assertEquals(IndicatorStatus.POOR, state.qualityIndicators.receiptDetectionStatus)
    }

    private fun createQualityAssessment(
        lightingScore: Float,
        focusScore: Float,
        angleScore: Float,
        distanceScore: Float,
        overallScore: Float,
        feedback: QualityFeedback
    ): ImageQualityAssessment {
        return ImageQualityAssessment(
            lightingScore = lightingScore,
            focusScore = focusScore,
            angleScore = angleScore,
            distanceScore = distanceScore,
            overallScore = overallScore,
            feedback = feedback
        )
    }

    private fun createReceiptBoundaries(): ReceiptBoundaries {
        return ReceiptBoundaries(
            corners = listOf(
                Point(100f, 100f),
                Point(300f, 100f),
                Point(300f, 500f),
                Point(100f, 500f)
            ),
            confidence = 0.9f,
            boundingBox = Rectangle(100f, 100f, 200f, 400f)
        )
    }
}
