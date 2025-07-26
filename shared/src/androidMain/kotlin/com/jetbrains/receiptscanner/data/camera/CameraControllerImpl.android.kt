package com.jetbrains.receiptscanner.data.camera

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessment
import com.jetbrains.receiptscanner.data.vision.ImageQualityAssessor
import com.jetbrains.receiptscanner.data.vision.ReceiptDetectionResult
import com.jetbrains.receiptscanner.data.vision.ReceiptDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Android implementation of CameraController using CameraX
 */
class CameraControllerImpl(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val receiptDetector: ReceiptDetector,
    private val imageQualityAssessor: ImageQualityAssessor
) : CameraController {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Initializing)

    private var isAutoCaptureEnabled = false
    private var qualityCallback: ((ImageQualityAssessment) -> Unit)? = null
    private var receiptDetectionCallback: ((ReceiptDetectionResult) -> Unit)? = null

    // Preview surface provider - to be set by UI layer
    var previewSurfaceProvider: Preview.SurfaceProvider? = null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            _cameraState.value = CameraState.Initializing

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProvider = suspendCoroutine { continuation ->
                cameraProviderFuture.addListener({
                    try {
                        continuation.resume(cameraProviderFuture.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }

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
            val provider = cameraProvider ?: return@withContext Result.failure(
                IllegalStateException("Camera not initialized")
            )

            // Unbind all use cases before rebinding
            provider.unbindAll()

            // Set up preview
            preview = Preview.Builder()
                .build()
                .also { preview ->
                    previewSurfaceProvider?.let { surfaceProvider ->
                        preview.setSurfaceProvider(surfaceProvider)
                    }
                }

            // Set up image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Set up image analysis for real-time processing
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageForAnalysis(imageProxy)
                    }
                }

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind use cases to camera
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )

            _cameraState.value = CameraState.Previewing
            Result.success(Unit)
        } catch (e: Exception) {
            _cameraState.value = CameraState.Error("Failed to start preview", e)
            Result.failure(e)
        }
    }

    override suspend fun stopPreview() {
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            _cameraState.value = CameraState.Ready
        }
    }

    override suspend fun captureImage(): Result<CaptureResult> = withContext(Dispatchers.IO) {
        try {
            _cameraState.value = CameraState.Capturing

            val imageCapture = this@CameraControllerImpl.imageCapture
                ?: return@withContext Result.failure(IllegalStateException("Image capture not initialized"))

            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                context.cacheDir.resolve("temp_receipt_${System.currentTimeMillis()}.jpg")
            ).build()

            val result = suspendCoroutine<ImageCapture.OutputFileResults> { continuation ->
                imageCapture.takePicture(
                    outputFileOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            continuation.resume(output)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }

            // Read the captured image
            val imageFile = result.savedUri?.path?.let { java.io.File(it) }
                ?: return@withContext Result.failure(IllegalStateException("Failed to save image"))

            val imageBytes = imageFile.readBytes()

            // Assess quality of captured image
            val qualityAssessment = imageQualityAssessor.assessQuality(imageBytes)
                .first()

            // Clean up temp file
            imageFile.delete()

            _cameraState.value = CameraState.Previewing

            Result.success(
                CaptureResult(
                    imageBytes = imageBytes,
                    timestamp = System.currentTimeMillis(),
                    quality = qualityAssessment
                )
            )
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
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
        }

        analysisScope.cancel()
        cameraExecutor.shutdown()
        receiptDetector.release()

        _cameraState.value = CameraState.Ready
    }

    private fun processImageForAnalysis(imageProxy: ImageProxy) {
        analysisScope.launch {
            try {
                val imageBytes = imageProxyToByteArray(imageProxy)

                // Process image for receipt detection and quality assessment in parallel
                val detectionDeferred = async {
                    receiptDetector.detectReceipt(imageBytes).getOrNull()
                }

                val qualityDeferred = async {
                    imageQualityAssessor.assessQuality(imageBytes)
                        .first()
                }

                val detectionResult = detectionDeferred.await()
                val qualityAssessment = qualityDeferred.await()

                // Notify callbacks
                qualityCallback?.invoke(qualityAssessment)
                detectionResult?.let { receiptDetectionCallback?.invoke(it) }

                // Auto capture if conditions are met
                if (isAutoCaptureEnabled && shouldAutoCapture(qualityAssessment, detectionResult)) {
                    launch(Dispatchers.Main) {
                        captureImage()
                    }
                }

            } catch (e: Exception) {
                // Log error but don't crash the analysis pipeline
                println("Error in image analysis: ${e.message}")
            } finally {
                imageProxy.close()
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

    private fun imageProxyToByteArray(imageProxy: ImageProxy): ByteArray {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}

/**
 * Android factory implementation
 */
actual object CameraControllerFactory {
    actual fun create(): CameraController {
        throw IllegalStateException("Use createAndroid() method for Android platform")
    }

    fun createAndroid(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        receiptDetector: ReceiptDetector,
        imageQualityAssessor: ImageQualityAssessor
    ): CameraController {
        return CameraControllerImpl(context, lifecycleOwner, receiptDetector, imageQualityAssessor)
    }
}
