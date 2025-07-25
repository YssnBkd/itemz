package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.flow.Flow

/**
 * Interface for receipt detection using computer vision models
 */
interface ReceiptDetector {
    /**
     * Initialize the detection model
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Detect receipt in real-time camera feed
     */
    fun detectReceiptRealTime(imageBytes: ByteArray): Flow<ReceiptDetectionResult>

    /**
     * Process single image for receipt detection
     */
    suspend fun detectReceipt(imageBytes: ByteArray): Result<ReceiptDetectionResult>

    /**
     * Release model resources
     */
    suspend fun release()
}

/**
 * Receipt detection result
 */
data class ReceiptDetectionResult(
    val isReceiptDetected: Boolean,
    val boundaries: ReceiptBoundaries?,
    val confidence: Float,
    val processingTimeMs: Long
)

/**
 * ONNX Runtime interface for cross-platform ML inference
 */
interface ONNXRuntimeEngine {
    /**
     * Load ONNX model from assets
     */
    suspend fun loadModel(modelPath: String): Result<Unit>

    /**
     * Run inference on preprocessed image
     */
    suspend fun runInference(inputTensor: FloatArray, inputShape: IntArray): Result<FloatArray>

    /**
     * Release model resources
     */
    suspend fun releaseModel()
}

/**
 * Image preprocessing for ONNX model input
 */
interface ImagePreprocessor {
    /**
     * Resize image to model input size
     */
    suspend fun resizeImage(imageBytes: ByteArray, targetWidth: Int, targetHeight: Int): Result<ByteArray>

    /**
     * Normalize pixel values for model input
     */
    suspend fun normalizeImage(imageBytes: ByteArray): Result<FloatArray>

    /**
     * Convert image to tensor format
     */
    suspend fun imageToTensor(imageBytes: ByteArray, inputShape: IntArray): Result<FloatArray>
}
/**
 * Platform-specific factories for computer vision components
 */
expect object VisionComponentFactory {
    fun createReceiptDetector(onnxEngine: ONNXRuntimeEngine, imagePreprocessor: ImagePreprocessor): ReceiptDetector
    fun createONNXRuntimeEngine(): ONNXRuntimeEngine
    fun createImagePreprocessor(): ImagePreprocessor
}
