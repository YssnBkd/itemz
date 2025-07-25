package com.jetbrains.receiptscanner.data.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android implementation of ImagePreprocessor for ONNX model input preparation
 */
class ImagePreprocessorImpl : ImagePreprocessor {

    companion object {
        // PaddleOCR normalization parameters
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f) // RGB mean
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)  // RGB std
        private const val SCALE = 1.0f / 255.0f
    }

    override suspend fun resizeImage(imageBytes: ByteArray, targetWidth: Int, targetHeight: Int): Result<ByteArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Resize bitmap to exact dimensions (may distort aspect ratio)
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

                // Convert back to byte array
                val outputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream.toByteArray()
            }
        }

    override suspend fun normalizeImage(imageBytes: ByteArray): Result<FloatArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                val width = bitmap.width
                val height = bitmap.height

                // Convert bitmap to pixel array
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                // Normalize pixels according to PaddleOCR requirements
                val normalizedPixels = FloatArray(width * height * 3)

                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = ((pixel shr 16) and 0xFF) * SCALE
                    val g = ((pixel shr 8) and 0xFF) * SCALE
                    val b = (pixel and 0xFF) * SCALE

                    // Apply normalization: (pixel - mean) / std
                    normalizedPixels[i] = (r - MEAN[0]) / STD[0]
                    normalizedPixels[i + width * height] = (g - MEAN[1]) / STD[1]
                    normalizedPixels[i + 2 * width * height] = (b - MEAN[2]) / STD[2]
                }

                normalizedPixels
            }
        }

    override suspend fun imageToTensor(imageBytes: ByteArray, inputShape: IntArray): Result<FloatArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Extract dimensions from input shape [batch, channels, height, width]
                val batchSize = inputShape[0]
                val channels = inputShape[1]
                val targetHeight = inputShape[2]
                val targetWidth = inputShape[3]

                // Resize image to target dimensions
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

                // Get pixel data
                val pixels = IntArray(targetWidth * targetHeight)
                resizedBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

                // Create tensor array in NCHW format
                val tensorSize = batchSize * channels * targetHeight * targetWidth
                val tensorArray = FloatArray(tensorSize)

                // Convert pixels to tensor format with normalization
                for (c in 0 until channels) {
                    for (h in 0 until targetHeight) {
                        for (w in 0 until targetWidth) {
                            val pixelIndex = h * targetWidth + w
                            val pixel = pixels[pixelIndex]

                            val channelValue = when (c) {
                                0 -> ((pixel shr 16) and 0xFF) * SCALE // R
                                1 -> ((pixel shr 8) and 0xFF) * SCALE  // G
                                2 -> (pixel and 0xFF) * SCALE          // B
                                else -> 0f
                            }

                            val normalizedValue = (channelValue - MEAN[c]) / STD[c]
                            val tensorIndex = c * targetHeight * targetWidth + h * targetWidth + w
                            tensorArray[tensorIndex] = normalizedValue
                        }
                    }
                }

                tensorArray
            }
        }
}
