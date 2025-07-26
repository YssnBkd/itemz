package com.jetbrains.receiptscanner.data.camera

import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessment
import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessor
import com.jetbrains.receiptscanner.data.vision.ReceiptDetectionResult
import com.jetbrains.receiptscanner.data.vision.ReceiptDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * iOS implementation of CameraController using AVFoundation
 * This is a simplified implementation that provides the interface
 * but delegates actual camera functionality to native iOS code
 */
class CameraControllerImpl(
    private val receiptDetector: ReceiptDetector,
    private val imageQualityAssessor: ImageQualityAssessor
) : CameraController {

    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Initializing)

    private var isAutoCaptureEnabled = false
    private var qualityCallback: ((ImageQualityAssessment) -> Unit)? = null
    private var receiptDetectionCallback: ((ReceiptDetectionResult) -> Unit)? = null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            _cameraState.value = CameraState.Initializing

            // Initialize receipt detector
            receiptDetector.initialize().getOrThrow()

            _cameraState.value = CameraState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            _cameraState.value = CameraState.Error("Failed to initialize camera", e)
            Result.failure(e)
        }
    }

    override suspend fun startPreview(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            _cameraState.value = CameraState.Previewing
            Result.success(Unit)
        } catch (e: Exception) {
            _cameraState.value = CameraState.Error("Failed to start preview", e)
            Result.failure(e)
        }
    }

    override suspend fun stopPreview() {
        withContext(Dispatchers.Main) {
            _cameraState.value = CameraState.Ready
        }
    }

    override suspend fun captureImage(): Result<CaptureResult> = withContext(Dispatchers.Main) {
        try {
            _cameraState.value = CameraState.Capturing

            // Simulate image capture with placeholder data
            val imageBytes = ByteArray(1024) // Placeholder
            val qualityAssessment = imageQualityAssessor.assessQuality(imageBytes).first()

            val result = CaptureResult(
                imageBytes = imageBytes,
                timestamp = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                quality = qualityAssessment
            )

            _cameraState.value = CameraState.Previewing
            Result.success(result)

        } catch (e: Exception) {
            _cameraState.value = CameraState.Error("Failed to capture image", e)
            Result.failure(e)
        }
    }

    override fun setAutoCaptureEnabled(enabled: Boolean) {
        isAutoCaptureEnabled = enabled
    }

    override fun setQualityCallback(callback: (ImageQualityAssessment) -> Unit) {
        qualityCallback = callback
    }

    override fun setReceiptDetectionCallback(callback: (ReceiptDetectionResult) -> Unit) {
        receiptDetectionCallback = callback
    }

    override fun getCameraState(): Flow<CameraState> = _cameraState.asStateFlow()

    override suspend fun release() {
        analysisScope.cancel()
        receiptDetector.release()
        _cameraState.value = CameraState.Ready
    }

    // Method to process video frames - to be called from native iOS code
    fun processVideoFrame(imageBytes: ByteArray) {
        analysisScope.launch {
            try {
                // Process image for receipt detection and quality assessment in parallel
                val detectionDeferred = async {
                    receiptDetector.detectReceipt(imageBytes).getOrNull()
                }

                val qualityDeferred = async {
                    imageQualityAssessor.assessQuality(imageBytes).first()
                }

                val detectionResult = detectionDeferred.await()
                val qualityAssessment = qualityDeferred.await()

                // Notify callbacks on main thread
                withContext(Dispatchers.Main) {
                    qualityCallback?.invoke(qualityAssessment)
                    detectionResult?.let { receiptDetectionCallback?.invoke(it) }

                    // Auto capture if conditions are met
                    if (isAutoCaptureEnabled && shouldAutoCapture(qualityAssessment, detectionResult)) {
                        launch {
                            captureImage()
                        }
                    }
                }

            } catch (e: Exception) {
                // Log error but don't crash the analysis pipeline
                println("Error in video frame analysis: ${e.message}")
            }
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

/**
 * iOS factory implementation
 */
actual object CameraControllerFactory {
    actual fun create(): CameraController {
        throw IllegalStateException("Use createIOS() method for iOS platform")
    }

    fun createIOS(
        receiptDetector: ReceiptDetector,
        imageQualityAssessor: ImageQualityAssessor
    ): CameraController {
        return CameraControllerImpl(receiptDetector, imageQualityAssessor)
    }
}
