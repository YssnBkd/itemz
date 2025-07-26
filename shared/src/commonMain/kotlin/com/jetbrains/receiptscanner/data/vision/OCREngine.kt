package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.flow.Flow

/**
 * Multi-stage OCR processing engine interface
 */
interface OCREngine {
    /**
     * Initialize OCR models and resources
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Stage 1: Fast text detection for real-time preview
     * Target: <500ms processing time
     */
    suspend fun detectTextRegions(imageBytes: ByteArray): Result<TextDetectionResult>

    /**
     * Stage 2: Full text recognition with high accuracy
     * Target: 1-3 seconds processing time
     */
    suspend fun recognizeText(imageBytes: ByteArray, textRegions: List<TextRegion>? = null): Result<TextRecognitionResult>

    /**
     * Complete OCR processing pipeline (detection + recognition)
     */
    fun processImage(imageBytes: ByteArray): Flow<OCRProcessingStage>

    /**
     * Release OCR models and resources
     */
    suspend fun release()
}

/**
 * Text detection result from Stage 1
 */
data class TextDetectionResult(
    val textRegions: List<TextRegion>,
    val textDensity: Float, // 0.0 to 1.0
    val processingTimeMs: Long,
    val confidence: Float
)

/**
 * Individual text region detected in image
 */
data class TextRegion(
    val boundingBox: Rectangle,
    val corners: List<Point>,
    val confidence: Float,
    val estimatedTextLength: Int = 0
)

/**
 * Text recognition result from Stage 2
 */
data class TextRecognitionResult(
    val recognizedLines: List<RecognizedTextLine>,
    val processingTimeMs: Long,
    val overallConfidence: Float
)

/**
 * Individual recognized text line
 */
data class RecognizedTextLine(
    val text: String,
    val boundingBox: Rectangle,
    val confidence: Float,
    val characterConfidences: List<Float> = emptyList()
)

/**
 * OCR processing stages for progress tracking
 */
sealed class OCRProcessingStage {
    object Initializing : OCRProcessingStage()
    data class DetectingText(val progress: Float) : OCRProcessingStage()
    data class RecognizingText(val progress: Float, val estimatedTimeRemainingMs: Long) : OCRProcessingStage()
    data class PostProcessing(val progress: Float) : OCRProcessingStage()
    data class Completed(val result: TextRecognitionResult) : OCRProcessingStage()
    data class Error(val error: OCRError) : OCRProcessingStage()
}

/**
 * OCR-specific error types
 */
sealed class OCRError : Exception() {
    object ModelNotInitialized : OCRError()
    object ModelLoadingFailed : OCRError()
    data class InferenceFailed(val stage: String, override val cause: Throwable) : OCRError()
    data class ImagePreprocessingFailed(override val cause: Throwable) : OCRError()
    object InsufficientMemory : OCRError()
    data class UnsupportedImageFormat(val format: String) : OCRError()
}

/**
 * OCR model configuration
 */
data class OCRModelConfig(
    val detectionModelPath: String = "models/paddleocr_detection.onnx",
    val recognitionModelPath: String = "models/paddleocr_recognition.onnx",
    val detectionInputSize: Pair<Int, Int> = 640 to 640,
    val recognitionInputHeight: Int = 48,
    val maxTextLineWidth: Int = 512,
    val confidenceThreshold: Float = 0.5f,
    val nmsThreshold: Float = 0.4f,
    val enableModelQuantization: Boolean = true,
    val maxConcurrentInferences: Int = 2
)

/**
 * OCR performance metrics
 */
data class OCRPerformanceMetrics(
    val detectionTimeMs: Long,
    val recognitionTimeMs: Long,
    val totalProcessingTimeMs: Long,
    val memoryUsageMB: Float,
    val textRegionsDetected: Int,
    val textLinesRecognized: Int,
    val averageConfidence: Float
)

/**
 * Platform-specific factory for creating OCR engines
 */
expect object OCREngineFactory {
    fun create(config: OCRModelConfig = OCRModelConfig()): OCREngine
}
