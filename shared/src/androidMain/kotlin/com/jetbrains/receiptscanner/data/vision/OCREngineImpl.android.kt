package com.jetbrains.receiptscanner.data.vision

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.system.measureTimeMillis

/**
 * Android factory for creating OCR engines
 */
actual object OCREngineFactory {
    actual fun create(config: OCRModelConfig): OCREngine {
        // Note: Context would need to be injected in real implementation
        return OCREngineImpl(null, config)
    }
}

/**
 * Android implementation of OCR engine using PaddleOCR PP-OCRv5 models
 */
class OCREngineImpl(
    private val context: Context?,
    private val config: OCRModelConfig
) : OCREngine {

    private var ortEnvironment: OrtEnvironment? = null
    private var detectionSession: OrtSession? = null
    private var recognitionSession: OrtSession? = null
    private val modelCache = OCRModelCacheFactory.create()
    private val performanceTracker = OCRPerformanceTrackerImpl()

    companion object {
        private const val DETECTION_INPUT_SIZE = 640
        private const val RECOGNITION_INPUT_HEIGHT = 48
        private const val MAX_TEXT_WIDTH = 512

        // PaddleOCR PP-OCRv5 specific constants
        private const val DETECTION_THRESHOLD = 0.3f
        private const val TEXT_THRESHOLD = 0.7f
        private const val LINK_THRESHOLD = 0.4f
    }

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Initialize ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Load detection model
            val detectionModel = modelCache.loadModel(
                config.detectionModelPath,
                OCRModelType.TEXT_DETECTION
            ).getOrThrow()

            // Load recognition model
            val recognitionModel = modelCache.loadModel(
                config.recognitionModelPath,
                OCRModelType.TEXT_RECOGNITION
            ).getOrThrow()

            // Create ONNX sessions
            detectionSession = createOrtSession(config.detectionModelPath)
            recognitionSession = createOrtSession(config.recognitionModelPath)
        }
    }

    override suspend fun detectTextRegions(imageBytes: ByteArray): Result<TextDetectionResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val session = detectionSession ?: throw OCRError.ModelNotInitialized
                val environment = ortEnvironment ?: throw OCRError.ModelNotInitialized

                var textRegions: List<TextRegion> = emptyList()
                var confidence = 0f

                val processingTime = measureTimeMillis {
                    // Preprocess image for detection
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val resizedBitmap = Bitmap.createScaledBitmap(
                        bitmap,
                        DETECTION_INPUT_SIZE,
                        DETECTION_INPUT_SIZE,
                        true
                    )

                    // Convert to tensor
                    val inputTensor = bitmapToTensor(resizedBitmap, environment)

                    try {
                        // Run detection inference
                        val inputName = session.inputNames.iterator().next()
                        val inputs = mapOf(inputName to inputTensor)

                        val results = session.run(inputs)
                        try {
                            // Process detection output
                            val outputName = session.outputNames.iterator().next()
                            val output = results.get(outputName) as OnnxTensor
                            val outputArray = output.floatBuffer.array()

                            // Parse PaddleOCR detection output
                            val detectionResult = parseDetectionOutput(
                                outputArray,
                                bitmap.width,
                                bitmap.height
                            )

                            textRegions = detectionResult.textRegions
                            confidence = detectionResult.confidence
                        } finally {
                            results.close()
                        }
                    } finally {
                        inputTensor.close()
                    }
                }

                // Calculate text density
                val totalImageArea = imageBytes.size.toFloat()
                val textArea = textRegions.sumOf { calculateRegionArea(it.boundingBox).toDouble() }.toFloat()
                val textDensity = (textArea / totalImageArea).coerceIn(0f, 1f)

                TextDetectionResult(
                    textRegions = textRegions,
                    textDensity = textDensity,
                    processingTimeMs = processingTime,
                    confidence = confidence
                )
            }
        }

    override suspend fun recognizeText(
        imageBytes: ByteArray,
        textRegions: List<TextRegion>?
    ): Result<TextRecognitionResult> = withContext(Dispatchers.Default) {
        runCatching {
            val session = recognitionSession ?: throw OCRError.ModelNotInitialized
            val environment = ortEnvironment ?: throw OCRError.ModelNotInitialized

            val regionsToProcess = textRegions ?: run {
                // If no regions provided, detect them first
                detectTextRegions(imageBytes).getOrThrow().textRegions
            }

            val recognizedLines = mutableListOf<RecognizedTextLine>()
            var totalProcessingTime = 0L

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Process each text region
            regionsToProcess.forEach { region ->
                val regionProcessingTime = measureTimeMillis {
                    try {
                        // Crop text region from image
                        val croppedBitmap = cropTextRegion(bitmap, region)

                        // Resize to recognition model input size
                        val aspectRatio = croppedBitmap.width.toFloat() / croppedBitmap.height
                        val targetWidth = minOf(MAX_TEXT_WIDTH, (RECOGNITION_INPUT_HEIGHT * aspectRatio).toInt())

                        val resizedBitmap = Bitmap.createScaledBitmap(
                            croppedBitmap,
                            targetWidth,
                            RECOGNITION_INPUT_HEIGHT,
                            true
                        )

                        // Convert to tensor
                        val inputTensor = bitmapToRecognitionTensor(resizedBitmap, environment)

                        try {
                            // Run recognition inference
                            val inputName = session.inputNames.iterator().next()
                            val inputs = mapOf(inputName to inputTensor)

                            val results = session.run(inputs)
                            try {
                                // Process recognition output
                                val outputName = session.outputNames.iterator().next()
                                val output = results.get(outputName) as OnnxTensor
                                val outputArray = output.floatBuffer.array()

                                // Decode text from PaddleOCR recognition output
                                val recognizedText = decodeRecognitionOutput(outputArray)

                                if (recognizedText.text.isNotBlank()) {
                                    recognizedLines.add(
                                        RecognizedTextLine(
                                            text = recognizedText.text,
                                            boundingBox = region.boundingBox,
                                            confidence = recognizedText.confidence,
                                            characterConfidences = recognizedText.characterConfidences
                                        )
                                    )
                                }
                            } finally {
                                results.close()
                            }
                        } finally {
                            inputTensor.close()
                        }
                    } catch (e: Exception) {
                        // Log error but continue processing other regions
                        println("Error processing text region: ${e.message}")
                    }
                }
                totalProcessingTime += regionProcessingTime
            }

            val overallConfidence = if (recognizedLines.isNotEmpty()) {
                recognizedLines.map { it.confidence }.average().toFloat()
            } else {
                0f
            }

            TextRecognitionResult(
                recognizedLines = recognizedLines,
                processingTimeMs = totalProcessingTime,
                overallConfidence = overallConfidence
            )
        }
    }

    override fun processImage(imageBytes: ByteArray): Flow<OCRProcessingStage> = flow {
        val coordinator = OCRStageCoordinatorImpl(this@OCREngineImpl, ImagePreprocessorImpl(), performanceTracker)
        coordinator.processImage(imageBytes).collect { stage ->
            emit(stage)
        }
    }

    override suspend fun release() {
        detectionSession?.close()
        recognitionSession?.close()
        ortEnvironment?.close()
        modelCache.releaseAllModels()
    }

    private fun createOrtSession(modelPath: String): OrtSession {
        val environment = ortEnvironment ?: throw OCRError.ModelNotInitialized

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)

            if (config.enableModelQuantization) {
                // Enable quantization for faster inference
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        }

        val modelBytes = loadModelFromAssets(modelPath)
        return environment.createSession(modelBytes, sessionOptions)
    }

    private fun loadModelFromAssets(modelPath: String): ByteArray {
        if (context == null) {
            // For testing purposes, return empty array
            return ByteArray(0)
        }
        return context.assets.open(modelPath).use { it.readBytes() }
    }

    private fun bitmapToTensor(bitmap: Bitmap, environment: OrtEnvironment): OnnxTensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to CHW format (Channel, Height, Width) and normalize
        val tensorData = FloatArray(3 * height * width)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // PaddleOCR normalization: (pixel / 255.0 - mean) / std
            // Using ImageNet normalization values
            tensorData[i] = (r - 0.485f) / 0.229f // R channel
            tensorData[height * width + i] = (g - 0.456f) / 0.224f // G channel
            tensorData[2 * height * width + i] = (b - 0.406f) / 0.225f // B channel
        }

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensorData), shape)
    }

    private fun bitmapToRecognitionTensor(bitmap: Bitmap, environment: OrtEnvironment): OnnxTensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale and normalize for text recognition
        val tensorData = FloatArray(height * width)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Convert to grayscale
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()

            // Normalize to [-1, 1] range for PaddleOCR recognition
            tensorData[i] = (gray / 127.5f) - 1.0f
        }

        val shape = longArrayOf(1, 1, height.toLong(), width.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensorData), shape)
    }

    private fun parseDetectionOutput(
        outputArray: FloatArray,
        originalWidth: Int,
        originalHeight: Int
    ): TextDetectionResult {
        val textRegions = mutableListOf<TextRegion>()

        // PaddleOCR detection output format: [batch_size, 1, H, W]
        // Each pixel represents the probability of being text
        val outputHeight = DETECTION_INPUT_SIZE
        val outputWidth = DETECTION_INPUT_SIZE

        // Apply threshold to create binary mask
        val binaryMask = BooleanArray(outputArray.size) { outputArray[it] > DETECTION_THRESHOLD }

        // Find connected components (text regions)
        val regions = findConnectedComponents(binaryMask, outputWidth, outputHeight)

        // Convert regions to TextRegion objects
        regions.forEach { region ->
            val scaledRegion = scaleRegionToOriginalSize(
                region,
                outputWidth, outputHeight,
                originalWidth, originalHeight
            )

            if (isValidTextRegion(scaledRegion)) {
                textRegions.add(scaledRegion)
            }
        }

        val averageConfidence = if (textRegions.isNotEmpty()) {
            textRegions.map { it.confidence }.average().toFloat()
        } else {
            0f
        }

        return TextDetectionResult(
            textRegions = textRegions,
            textDensity = 0.5f, // Placeholder
            processingTimeMs = 0L, // Will be set by caller
            confidence = averageConfidence
        )
    }

    private fun findConnectedComponents(
        binaryMask: BooleanArray,
        width: Int,
        height: Int
    ): List<TextRegion> {
        val visited = BooleanArray(binaryMask.size)
        val regions = mutableListOf<TextRegion>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (binaryMask[index] && !visited[index]) {
                    val region = floodFill(binaryMask, visited, x, y, width, height)
                    if (region != null) {
                        regions.add(region)
                    }
                }
            }
        }

        return regions
    }

    private fun floodFill(
        binaryMask: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): TextRegion? {
        val points = mutableListOf<Pair<Int, Int>>()
        val stack = mutableListOf(Pair(startX, startY))

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.size - 1)
            val index = y * width + x

            if (x < 0 || x >= width || y < 0 || y >= height || visited[index] || !binaryMask[index]) {
                continue
            }

            visited[index] = true
            points.add(Pair(x, y))

            // Add neighbors
            stack.add(Pair(x + 1, y))
            stack.add(Pair(x - 1, y))
            stack.add(Pair(x, y + 1))
            stack.add(Pair(x, y - 1))
        }

        // Create bounding box from points
        if (points.size < 10) return null // Filter out very small regions

        val minX = points.minOf { it.first }.toFloat()
        val maxX = points.maxOf { it.first }.toFloat()
        val minY = points.minOf { it.second }.toFloat()
        val maxY = points.maxOf { it.second }.toFloat()

        val boundingBox = Rectangle(minX, minY, maxX - minX, maxY - minY)
        val corners = listOf(
            Point(minX, minY),
            Point(maxX, minY),
            Point(maxX, maxY),
            Point(minX, maxY)
        )

        return TextRegion(
            boundingBox = boundingBox,
            corners = corners,
            confidence = 0.8f, // Default confidence for detected regions
            estimatedTextLength = estimateTextLength(boundingBox)
        )
    }

    private fun scaleRegionToOriginalSize(
        region: TextRegion,
        modelWidth: Int,
        modelHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): TextRegion {
        val scaleX = originalWidth.toFloat() / modelWidth
        val scaleY = originalHeight.toFloat() / modelHeight

        val scaledBoundingBox = Rectangle(
            x = region.boundingBox.x * scaleX,
            y = region.boundingBox.y * scaleY,
            width = region.boundingBox.width * scaleX,
            height = region.boundingBox.height * scaleY
        )

        val scaledCorners = region.corners.map { corner ->
            Point(corner.x * scaleX, corner.y * scaleY)
        }

        return region.copy(
            boundingBox = scaledBoundingBox,
            corners = scaledCorners
        )
    }

    private fun isValidTextRegion(region: TextRegion): Boolean {
        val area = region.boundingBox.width * region.boundingBox.height
        val aspectRatio = region.boundingBox.width / region.boundingBox.height

        // Filter out regions that are too small or have invalid aspect ratios
        return area > 100 && aspectRatio > 0.1 && aspectRatio < 20
    }

    private fun estimateTextLength(boundingBox: Rectangle): Int {
        // Rough estimate based on bounding box width
        // Assuming average character width of 8 pixels
        return (boundingBox.width / 8).toInt()
    }

    private fun cropTextRegion(bitmap: Bitmap, region: TextRegion): Bitmap {
        val box = region.boundingBox
        val x = maxOf(0, box.x.toInt())
        val y = maxOf(0, box.y.toInt())
        val width = minOf(bitmap.width - x, box.width.toInt())
        val height = minOf(bitmap.height - y, box.height.toInt())

        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    private fun decodeRecognitionOutput(outputArray: FloatArray): RecognizedText {
        // PaddleOCR recognition output decoding
        // This is a simplified implementation - real PaddleOCR uses CTC decoding

        // For now, return placeholder text
        // In a real implementation, this would:
        // 1. Apply CTC decoding to convert logits to character sequence
        // 2. Use character dictionary to map indices to characters
        // 3. Calculate character-level confidence scores

        return RecognizedText(
            text = "Sample recognized text",
            confidence = 0.85f,
            characterConfidences = listOf(0.9f, 0.8f, 0.85f, 0.9f, 0.7f)
        )
    }

    private fun calculateRegionArea(boundingBox: Rectangle): Float {
        return boundingBox.width * boundingBox.height
    }

    private data class RecognizedText(
        val text: String,
        val confidence: Float,
        val characterConfidences: List<Float>
    )
}
