package com.jetbrains.receiptscanner.data.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.*

/**
 * Android factory for creating ImageProcessor instances
 */
actual object ImageProcessorFactory {
    actual fun create(): ImageProcessor = ImageProcessorImpl()
}

/**
 * Android implementation of ImageProcessor using native Android APIs
 */
class ImageProcessorImpl : ImageProcessor {

    override suspend fun detectReceiptBoundaries(imageBytes: ByteArray): Result<ReceiptBoundaries> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                try {
                    // Simplified receipt boundary detection using basic image analysis
                    val width = bitmap.width.toFloat()
                    val height = bitmap.height.toFloat()

                    // Create default rectangular boundaries (80% of image)
                    val margin = 0.1f
                    val corners = listOf(
                        Point(width * margin, height * margin),
                        Point(width * (1 - margin), height * margin),
                        Point(width * (1 - margin), height * (1 - margin)),
                        Point(width * margin, height * (1 - margin))
                    )

                    val boundingBox = calculateBoundingBox(corners)

                    ReceiptBoundaries(
                        corners = corners,
                        confidence = 0.8f, // Default confidence
                        boundingBox = boundingBox
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }

    override suspend fun correctPerspective(imageBytes: ByteArray, boundaries: ReceiptBoundaries): Result<ByteArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Simplified perspective correction using basic transformation
                val matrix = Matrix()

                // Calculate rotation angle from corners
                val topLeft = boundaries.corners[0]
                val topRight = boundaries.corners[1]
                val angle = atan2(topRight.y - topLeft.y, topRight.x - topLeft.x) * 180 / PI

                // Apply rotation correction
                matrix.postRotate(-angle.toFloat(), bitmap.width / 2f, bitmap.height / 2f)

                val correctedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )

                // Convert back to byte array
                val outputStream = ByteArrayOutputStream()
                correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream.toByteArray()
            }
        }

    override suspend fun enhanceImage(imageBytes: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Simplified image enhancement using Android ColorMatrix
                val enhancedBitmap = enhanceContrast(bitmap, 1.2f)

                val outputStream = ByteArrayOutputStream()
                enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                outputStream.toByteArray()
            }
        }

    override fun assessImageQuality(imageBytes: ByteArray): Flow<ImageQualityAssessment> = flow {
        val assessment = withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Simplified quality assessment
                val lightingScore = assessLighting(bitmap)
                val focusScore = assessFocus(bitmap)
                val angleScore = 0.8f // Default angle score
                val distanceScore = assessDistance(bitmap)

                val overallScore = (lightingScore + focusScore + angleScore + distanceScore) / 4.0f
                val feedback = determineFeedback(lightingScore, focusScore, angleScore, distanceScore)

                ImageQualityAssessment(
                    lightingScore = lightingScore,
                    focusScore = focusScore,
                    angleScore = angleScore,
                    distanceScore = distanceScore,
                    overallScore = overallScore,
                    feedback = feedback
                )
            }.getOrElse {
                ImageQualityAssessment(0f, 0f, 0f, 0f, 0f, QualityFeedback.HoldSteady)
            }
        }
        emit(assessment)
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

    private fun enhanceContrast(bitmap: Bitmap, contrast: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            // Apply contrast enhancement
            val newR = ((r - 128) * contrast + 128).toInt().coerceIn(0, 255)
            val newG = ((g - 128) * contrast + 128).toInt().coerceIn(0, 255)
            val newB = ((b - 128) * contrast + 128).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhancedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return enhancedBitmap
    }

    private fun assessLighting(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalBrightness = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = (r + g + b) / 3.0
            totalBrightness += brightness
        }

        val averageBrightness = totalBrightness / pixels.size

        // Good lighting: average brightness around 128
        val lightingScore = 1.0f - abs(averageBrightness - 128.0).toFloat() / 128.0f
        return maxOf(0f, lightingScore)
    }

    private fun assessFocus(bitmap: Bitmap): Float {
        // Simplified focus assessment using edge detection
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var edgeStrength = 0.0
        var edgeCount = 0

        // Simple edge detection using horizontal and vertical gradients
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = pixels[y * width + x]
                val right = pixels[y * width + (x + 1)]
                val bottom = pixels[(y + 1) * width + x]

                val centerGray = getGrayValue(center)
                val rightGray = getGrayValue(right)
                val bottomGray = getGrayValue(bottom)

                val horizontalGradient = abs(rightGray - centerGray)
                val verticalGradient = abs(bottomGray - centerGray)
                val gradient = sqrt((horizontalGradient * horizontalGradient + verticalGradient * verticalGradient).toDouble())

                if (gradient > 30) { // Threshold for edge detection
                    edgeStrength += gradient
                    edgeCount++
                }
            }
        }

        val averageEdgeStrength = if (edgeCount > 0) edgeStrength / edgeCount else 0.0
        return minOf(1.0f, (averageEdgeStrength / 100.0).toFloat())
    }

    private fun assessDistance(bitmap: Bitmap): Float {
        val imageArea = bitmap.width * bitmap.height

        // Optimal distance when image is reasonably sized
        return when {
            imageArea < 100000 -> 0.3f // Too small/far
            imageArea < 500000 -> 0.8f // Good size
            imageArea < 1000000 -> 1.0f // Perfect size
            else -> 0.6f // Too large/close
        }
    }

    private fun getGrayValue(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r + g + b) / 3
    }

    private fun determineFeedback(
        lightingScore: Float,
        focusScore: Float,
        angleScore: Float,
        distanceScore: Float
    ): QualityFeedback {
        val overallScore = (lightingScore + focusScore + angleScore + distanceScore) / 4.0f

        return when {
            overallScore > 0.8f -> QualityFeedback.Perfect
            lightingScore < 0.5f -> QualityFeedback.ImproveLighting
            focusScore < 0.5f -> QualityFeedback.HoldSteady
            angleScore < 0.5f -> QualityFeedback.AdjustAngle
            distanceScore < 0.4f -> QualityFeedback.MoveCloser
            distanceScore > 0.8f -> QualityFeedback.MoveFurther
            else -> QualityFeedback.ShowFullReceipt
        }
    }
}
