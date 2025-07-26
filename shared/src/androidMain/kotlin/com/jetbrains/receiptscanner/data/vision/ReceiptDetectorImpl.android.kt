package com.jetbrains.receiptscanner.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Android factory for creating vision components
 */
actual object VisionComponentFactory {
    actual fun createReceiptDetector(onnxEngine: ONNXRuntimeEngine, imagePreprocessor: ImagePreprocessor): ReceiptDetector {
        // Note: Context would need to be injected in real implementation
        return ReceiptDetectorImpl(null, onnxEngine, imagePreprocessor)
    }

    actual fun createONNXRuntimeEngine(): ONNXRuntimeEngine {
        // Note: Context would need to be injected in real implementation
        return ONNXRuntimeEngineImpl(null)
    }

    actual fun createImagePreprocessor(): ImagePreprocessor {
        return ImagePreprocessorImpl()
    }
}

/**
 * Android implementation of ReceiptDetector using PaddleOCR PP-OCRv5 detection model
 */
class ReceiptDetectorImpl(
    private val context: Context?,
    private val onnxEngine: ONNXRuntimeEngine,
    private val imagePreprocessor: ImagePreprocessor
) : ReceiptDetector {

    companion object {
        private const val DETECTION_MODEL_PATH = "models/paddleocr_detection.onnx"
        private const val INPUT_WIDTH = 640
        private const val INPUT_HEIGHT = 640
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val NMS_THRESHOLD = 0.4f
    }

    private var isModelLoaded = false

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isModelLoaded) {
                onnxEngine.loadModel(DETECTION_MODEL_PATH).getOrThrow()
                isModelLoaded = true
            }
        }
    }

    override fun detectReceiptRealTime(imageBytes: ByteArray): Flow<ReceiptDetectionResult> = flow {
        val result = detectReceipt(imageBytes).getOrElse {
            ReceiptDetectionResult(
                isReceiptDetected = false,
                boundaries = null,
                confidence = 0f,
                processingTimeMs = 0L
            )
        }
        emit(result)
    }

    override suspend fun detectReceipt(imageBytes: ByteArray): Result<ReceiptDetectionResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                if (!isModelLoaded) {
                    throw IllegalStateException("Model not initialized. Call initialize() first.")
                }

                var detectionResults: ReceiptDetectionResult? = null

                val processingTime = measureTimeMillis {
                    // Preprocess image for ONNX model
                    val inputTensor = imagePreprocessor.imageToTensor(
                        imageBytes,
                        intArrayOf(1, 3, INPUT_HEIGHT, INPUT_WIDTH)
                    ).getOrThrow()

                    // Run inference
                    val outputTensor = onnxEngine.runInference(
                        inputTensor,
                        intArrayOf(1, 3, INPUT_HEIGHT, INPUT_WIDTH)
                    ).getOrThrow()

                    // Post-process detection results
                    detectionResults = postProcessDetection(outputTensor, imageBytes)
                }

                detectionResults?.copy(processingTimeMs = processingTime)
                    ?: throw IllegalStateException("Detection results were not computed")
            }
        }

    override suspend fun release() {
        onnxEngine.releaseModel()
        isModelLoaded = false
    }

    private fun postProcessDetection(outputTensor: FloatArray, originalImageBytes: ByteArray): ReceiptDetectionResult {
        // Parse PaddleOCR detection output
        // Output format: [batch_size, num_detections, 4, 2] for bounding boxes
        // Each detection contains 4 corner points with x,y coordinates

        val bitmap = BitmapFactory.decodeByteArray(originalImageBytes, 0, originalImageBytes.size)
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Find the largest detection that could be a receipt
        val detections = parseDetectionOutput(outputTensor)
        val receiptDetection = findBestReceiptDetection(detections)

        return if (receiptDetection != null && receiptDetection.confidence > CONFIDENCE_THRESHOLD) {
            // Scale coordinates back to original image size
            val scaledCorners = scaleCoordinates(
                receiptDetection.corners,
                INPUT_WIDTH, INPUT_HEIGHT,
                originalWidth, originalHeight
            )

            val boundaries = ReceiptBoundaries(
                corners = scaledCorners,
                confidence = receiptDetection.confidence,
                boundingBox = calculateBoundingBox(scaledCorners)
            )

            ReceiptDetectionResult(
                isReceiptDetected = true,
                boundaries = boundaries,
                confidence = receiptDetection.confidence,
                processingTimeMs = 0L // Will be set by caller
            )
        } else {
            ReceiptDetectionResult(
                isReceiptDetected = false,
                boundaries = null,
                confidence = 0f,
                processingTimeMs = 0L
            )
        }
    }

    private fun parseDetectionOutput(outputTensor: FloatArray): List<Detection> {
        val detections = mutableListOf<Detection>()

        // PaddleOCR detection output parsing
        // Assuming output format: [num_detections, 8] where each detection has 4 points (x1,y1,x2,y2,x3,y3,x4,y4)
        val numDetections = outputTensor.size / 8

        for (i in 0 until numDetections) {
            val baseIndex = i * 8

            val corners = listOf(
                Point(outputTensor[baseIndex], outputTensor[baseIndex + 1]),
                Point(outputTensor[baseIndex + 2], outputTensor[baseIndex + 3]),
                Point(outputTensor[baseIndex + 4], outputTensor[baseIndex + 5]),
                Point(outputTensor[baseIndex + 6], outputTensor[baseIndex + 7])
            )

            // Calculate confidence based on detection area and shape regularity
            val confidence = calculateDetectionConfidence(corners)

            if (confidence > 0.1f) { // Filter out very low confidence detections
                detections.add(Detection(corners, confidence))
            }
        }

        return detections
    }

    private fun findBestReceiptDetection(detections: List<Detection>): Detection? {
        return detections
            .filter { isReceiptLikeShape(it.corners) }
            .maxByOrNull { it.confidence * calculateArea(it.corners) }
    }

    private fun isReceiptLikeShape(corners: List<Point>): Boolean {
        if (corners.size != 4) return false

        // Check if shape is roughly rectangular
        val area = calculateArea(corners)
        val perimeter = calculatePerimeter(corners)

        // Receipt should have reasonable aspect ratio (height > width typically)
        val width = maxOf(
            distance(corners[0], corners[1]),
            distance(corners[2], corners[3])
        )
        val height = maxOf(
            distance(corners[1], corners[2]),
            distance(corners[3], corners[0])
        )

        val aspectRatio = height / width

        // Receipts are typically taller than wide (aspect ratio > 1.2)
        return aspectRatio > 1.2 && aspectRatio < 5.0 && area > 1000
    }

    private fun calculateDetectionConfidence(corners: List<Point>): Float {
        if (corners.size != 4) return 0f

        // Calculate confidence based on shape regularity
        val area = calculateArea(corners)
        val perimeter = calculatePerimeter(corners)

        // More regular rectangles have higher confidence
        val idealRatio = 4 * kotlin.math.sqrt(area) / perimeter
        return kotlin.math.min(1f, idealRatio)
    }

    private fun calculateArea(corners: List<Point>): Float {
        if (corners.size != 4) return 0f

        // Shoelace formula for polygon area
        var area = 0f
        for (i in corners.indices) {
            val j = (i + 1) % corners.size
            area += corners[i].x * corners[j].y
            area -= corners[j].x * corners[i].y
        }
        return kotlin.math.abs(area) / 2f
    }

    private fun calculatePerimeter(corners: List<Point>): Float {
        if (corners.size != 4) return 0f

        var perimeter = 0f
        for (i in corners.indices) {
            val j = (i + 1) % corners.size
            perimeter += distance(corners[i], corners[j])
        }
        return perimeter
    }

    private fun distance(p1: Point, p2: Point): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun scaleCoordinates(
        corners: List<Point>,
        modelWidth: Int, modelHeight: Int,
        originalWidth: Int, originalHeight: Int
    ): List<Point> {
        val scaleX = originalWidth.toFloat() / modelWidth
        val scaleY = originalHeight.toFloat() / modelHeight

        return corners.map { corner ->
            Point(
                x = corner.x * scaleX,
                y = corner.y * scaleY
            )
        }
    }

    private fun calculateBoundingBox(corners: List<Point>): Rectangle {
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }

        return Rectangle(
            x = minX,
            y = minY,
            width = maxX - minX,
            height = maxY - minY
        )
    }

    private data class Detection(
        val corners: List<Point>,
        val confidence: Float
    )
}
