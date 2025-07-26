package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.time.measureTime

/**
 * Coordinates multi-stage OCR processing pipeline
 */
interface OCRStageCoordinator {
    /**
     * Execute complete OCR pipeline with progress tracking
     */
    fun processImage(imageBytes: ByteArray): Flow<OCRProcessingStage>

    /**
     * Execute only Stage 1: Fast text detection
     */
    suspend fun executeStage1(imageBytes: ByteArray): Result<TextDetectionResult>

    /**
     * Execute only Stage 2: Full text recognition
     */
    suspend fun executeStage2(imageBytes: ByteArray, textRegions: List<TextRegion>? = null): Result<TextRecognitionResult>

    /**
     * Get processing time estimates based on image complexity
     */
    suspend fun estimateProcessingTime(imageBytes: ByteArray): ProcessingTimeEstimate
}

/**
 * Processing time estimates
 */
data class ProcessingTimeEstimate(
    val stage1EstimateMs: Long,
    val stage2EstimateMs: Long,
    val totalEstimateMs: Long,
    val complexity: ImageComplexity
)

/**
 * Image complexity assessment
 */
enum class ImageComplexity {
    LOW,    // Simple receipt, few text lines
    MEDIUM, // Standard receipt, moderate text density
    HIGH,   // Complex receipt, high text density
    VERY_HIGH // Very dense receipt or poor quality
}

/**
 * Default implementation of OCR stage coordinator
 */
class OCRStageCoordinatorImpl(
    private val ocrEngine: OCREngine,
    private val imagePreprocessor: ImagePreprocessor,
    private val performanceTracker: OCRPerformanceTracker
) : OCRStageCoordinator {

    companion object {
        private const val STAGE1_TARGET_TIME_MS = 500L
        private const val STAGE2_BASE_TIME_MS = 1000L
        private const val STAGE2_MAX_TIME_MS = 3000L
    }

    override fun processImage(imageBytes: ByteArray): Flow<OCRProcessingStage> = flow {
        try {
            emit(OCRProcessingStage.Initializing)

            // Stage 1: Fast text detection
            emit(OCRProcessingStage.DetectingText(0.1f))

            val detectionResult = withContext(Dispatchers.Default) {
                executeStage1(imageBytes)
            }.getOrThrow()

            emit(OCRProcessingStage.DetectingText(1.0f))

            // Estimate Stage 2 processing time
            val timeEstimate = estimateStage2Time(detectionResult)

            // Stage 2: Full text recognition
            emit(OCRProcessingStage.RecognizingText(0.1f, timeEstimate))

            val recognitionResult = withContext(Dispatchers.Default) {
                executeStage2WithProgress(imageBytes, detectionResult.textRegions) { progress ->
                    val remainingTime = (timeEstimate * (1.0f - progress)).toLong()
                    emit(OCRProcessingStage.RecognizingText(progress, remainingTime))
                }
            }.getOrThrow()

            // Post-processing
            emit(OCRProcessingStage.PostProcessing(0.5f))

            val finalResult = postProcessRecognitionResult(recognitionResult)

            emit(OCRProcessingStage.PostProcessing(1.0f))
            emit(OCRProcessingStage.Completed(finalResult))

        } catch (error: Exception) {
            val ocrError = when (error) {
                is OCRError -> error
                else -> OCRError.InferenceFailed("pipeline", error)
            }
            emit(OCRProcessingStage.Error(ocrError))
        }
    }

    override suspend fun executeStage1(imageBytes: ByteArray): Result<TextDetectionResult> {
        return withContext(Dispatchers.Default) {
            runCatching {
                val startTime = Clock.System.now().toEpochMilliseconds()

                // Preprocess image for detection
                val preprocessedImage = imagePreprocessor.imageToTensor(
                    imageBytes,
                    intArrayOf(1, 3, 640, 640)
                ).getOrThrow()

                // Run text detection
                val detectionResult = ocrEngine.detectTextRegions(imageBytes).getOrThrow()

                val processingTime = Clock.System.now().toEpochMilliseconds() - startTime

                // Track performance
                performanceTracker.recordStage1Performance(
                    processingTimeMs = processingTime,
                    textRegionsDetected = detectionResult.textRegions.size,
                    confidence = detectionResult.confidence
                )

                detectionResult.copy(processingTimeMs = processingTime)
            }
        }
    }

    override suspend fun executeStage2(imageBytes: ByteArray, textRegions: List<TextRegion>?): Result<TextRecognitionResult> {
        return withContext(Dispatchers.Default) {
            runCatching {
                val startTime = Clock.System.now().toEpochMilliseconds()

                val recognitionResult = ocrEngine.recognizeText(imageBytes, textRegions).getOrThrow()

                val processingTime = Clock.System.now().toEpochMilliseconds() - startTime

                // Track performance
                performanceTracker.recordStage2Performance(
                    processingTimeMs = processingTime,
                    textLinesRecognized = recognitionResult.recognizedLines.size,
                    averageConfidence = recognitionResult.overallConfidence
                )

                recognitionResult.copy(processingTimeMs = processingTime)
            }
        }
    }

    override suspend fun estimateProcessingTime(imageBytes: ByteArray): ProcessingTimeEstimate {
        return withContext(Dispatchers.Default) {
            // Quick analysis to estimate complexity
            val imageSize = imageBytes.size
            val complexity = when {
                imageSize < 500_000 -> ImageComplexity.LOW
                imageSize < 1_500_000 -> ImageComplexity.MEDIUM
                imageSize < 3_000_000 -> ImageComplexity.HIGH
                else -> ImageComplexity.VERY_HIGH
            }

            val stage1Estimate = when (complexity) {
                ImageComplexity.LOW -> 200L
                ImageComplexity.MEDIUM -> 350L
                ImageComplexity.HIGH -> 450L
                ImageComplexity.VERY_HIGH -> 500L
            }

            val stage2Estimate = when (complexity) {
                ImageComplexity.LOW -> 800L
                ImageComplexity.MEDIUM -> 1500L
                ImageComplexity.HIGH -> 2500L
                ImageComplexity.VERY_HIGH -> 3000L
            }

            ProcessingTimeEstimate(
                stage1EstimateMs = stage1Estimate,
                stage2EstimateMs = stage2Estimate,
                totalEstimateMs = stage1Estimate + stage2Estimate,
                complexity = complexity
            )
        }
    }

    private suspend fun executeStage2WithProgress(
        imageBytes: ByteArray,
        textRegions: List<TextRegion>,
        onProgress: suspend (Float) -> Unit
    ): Result<TextRecognitionResult> {
        return withContext(Dispatchers.Default) {
            runCatching {
                val startTime = Clock.System.now().toEpochMilliseconds()
                val totalRegions = textRegions.size
                val recognizedLines = mutableListOf<RecognizedTextLine>()

                // Process text regions in batches for progress tracking
                val batchSize = maxOf(1, totalRegions / 10) // 10 progress updates

                textRegions.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                    // Process batch
                    val batchResult = ocrEngine.recognizeText(imageBytes, batch).getOrThrow()
                    recognizedLines.addAll(batchResult.recognizedLines)

                    // Update progress
                    val progress = (batchIndex + 1).toFloat() / (totalRegions / batchSize).toFloat()
                    onProgress(minOf(0.9f, progress)) // Cap at 90% for final processing
                }

                val processingTime = Clock.System.now().toEpochMilliseconds() - startTime
                val overallConfidence = recognizedLines.map { it.confidence }.average().toFloat()

                TextRecognitionResult(
                    recognizedLines = recognizedLines,
                    processingTimeMs = processingTime,
                    overallConfidence = overallConfidence
                )
            }
        }
    }

    private fun estimateStage2Time(detectionResult: TextDetectionResult): Long {
        val baseTime = STAGE2_BASE_TIME_MS
        val textDensityMultiplier = 1.0f + detectionResult.textDensity
        val regionCountMultiplier = 1.0f + (detectionResult.textRegions.size / 20.0f)

        val estimatedTime = (baseTime * textDensityMultiplier * regionCountMultiplier).toLong()
        return minOf(estimatedTime, STAGE2_MAX_TIME_MS)
    }

    private suspend fun postProcessRecognitionResult(result: TextRecognitionResult): TextRecognitionResult {
        return withContext(Dispatchers.Default) {
            // Apply post-processing improvements
            val improvedLines = result.recognizedLines.map { line ->
                line.copy(
                    text = cleanupRecognizedText(line.text),
                    confidence = adjustConfidenceScore(line.confidence, line.text)
                )
            }.filter { it.text.isNotBlank() } // Remove empty lines

            result.copy(
                recognizedLines = improvedLines,
                overallConfidence = improvedLines.map { it.confidence }.average().toFloat()
            )
        }
    }

    private fun cleanupRecognizedText(text: String): String {
        return text
            .trim()
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .replace(Regex("[^\\p{Print}]"), "") // Remove non-printable characters
    }

    private fun adjustConfidenceScore(originalConfidence: Float, text: String): Float {
        // Adjust confidence based on text characteristics
        var adjustedConfidence = originalConfidence

        // Penalize very short text (likely OCR errors)
        if (text.length < 3) {
            adjustedConfidence *= 0.8f
        }

        // Boost confidence for text with common receipt patterns
        if (text.contains(Regex("\\$\\d+\\.\\d{2}"))) { // Price pattern
            adjustedConfidence *= 1.1f
        }

        return minOf(1.0f, adjustedConfidence)
    }
}

/**
 * Performance tracking for OCR operations
 */
interface OCRPerformanceTracker {
    fun recordStage1Performance(processingTimeMs: Long, textRegionsDetected: Int, confidence: Float)
    fun recordStage2Performance(processingTimeMs: Long, textLinesRecognized: Int, averageConfidence: Float)
    fun getPerformanceMetrics(): OCRPerformanceMetrics
}

/**
 * Default implementation of performance tracker
 */
class OCRPerformanceTrackerImpl : OCRPerformanceTracker {
    private val stage1Times = mutableListOf<Long>()
    private val stage2Times = mutableListOf<Long>()
    private var totalTextRegions = 0
    private var totalTextLines = 0
    private var totalConfidence = 0f
    private var operationCount = 0

    override fun recordStage1Performance(processingTimeMs: Long, textRegionsDetected: Int, confidence: Float) {
        stage1Times.add(processingTimeMs)
        totalTextRegions += textRegionsDetected
        updateConfidence(confidence)
    }

    override fun recordStage2Performance(processingTimeMs: Long, textLinesRecognized: Int, averageConfidence: Float) {
        stage2Times.add(processingTimeMs)
        totalTextLines += textLinesRecognized
        updateConfidence(averageConfidence)
    }

    override fun getPerformanceMetrics(): OCRPerformanceMetrics {
        return OCRPerformanceMetrics(
            detectionTimeMs = stage1Times.average().toLong(),
            recognitionTimeMs = stage2Times.average().toLong(),
            totalProcessingTimeMs = (stage1Times.sum() + stage2Times.sum()) / maxOf(1, operationCount),
            memoryUsageMB = 0f, // Would be implemented with platform-specific memory tracking
            textRegionsDetected = totalTextRegions / maxOf(1, operationCount),
            textLinesRecognized = totalTextLines / maxOf(1, operationCount),
            averageConfidence = totalConfidence / maxOf(1, operationCount)
        )
    }

    private fun updateConfidence(confidence: Float) {
        totalConfidence += confidence
        operationCount++
    }
}
