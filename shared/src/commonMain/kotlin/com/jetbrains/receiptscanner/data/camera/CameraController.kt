package com.jetbrains.receiptscanner.data.camera

import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessment
import com.jetbrains.receiptscanner.data.vision.ReceiptDetectionResult
import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform camera controller interface
 */
interface CameraController {
    /**
     * Initialize camera with preview
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Start camera preview
     */
    suspend fun startPreview(): Result<Unit>

    /**
     * Stop camera preview
     */
    suspend fun stopPreview()

    /**
     * Capture image
     */
    suspend fun captureImage(): Result<CaptureResult>

    /**
     * Enable/disable automatic capture when receipt is properly framed
     */
    fun setAutoCaptureEnabled(enabled: Boolean)

    /**
     * Set callback for real-time image quality assessment
     */
    fun setQualityCallback(callback: (ImageQualityAssessment) -> Unit)

    /**
     * Set callback for receipt detection
     */
    fun setReceiptDetectionCallback(callback: (ReceiptDetectionResult) -> Unit)

    /**
     * Get camera state flow
     */
    fun getCameraState(): Flow<CameraState>

    /**
     * Release camera resources
     */
    suspend fun release()
}

/**
 * Camera capture result
 */
data class CaptureResult(
    val imageBytes: ByteArray,
    val timestamp: Long,
    val quality: ImageQualityAssessment
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CaptureResult

        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (timestamp != other.timestamp) return false
        if (quality != other.quality) return false

        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + quality.hashCode()
        return result
    }
}

/**
 * Camera state
 */
sealed class CameraState {
    object Initializing : CameraState()
    object Ready : CameraState()
    object Previewing : CameraState()
    object Capturing : CameraState()
    data class Error(val message: String, val cause: Throwable? = null) : CameraState()
}

/**
 * Camera permission state
 */
sealed class CameraPermissionState {
    object Granted : CameraPermissionState()
    object Denied : CameraPermissionState()
    object NotRequested : CameraPermissionState()
}

/**
 * Camera guidance messages for user feedback
 */
sealed class CameraGuidance {
    object Perfect : CameraGuidance()
    object MoveCloser : CameraGuidance()
    object MoveFurther : CameraGuidance()
    object ImproveLighting : CameraGuidance()
    object HoldSteady : CameraGuidance()
    object AdjustAngle : CameraGuidance()
    object ShowFullReceipt : CameraGuidance()
    object ReceiptDetected : CameraGuidance()
    object NoReceiptDetected : CameraGuidance()
}

/**
 * Platform-specific factory for creating camera controller
 */
expect object CameraControllerFactory {
    fun create(): CameraController
}
