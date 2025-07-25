package com.jetbrains.receiptscanner.data.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Android factory for creating ImageQualityAssessor
 */
actual object ImageQualityAssessorFactory {
    actual fun create(): ImageQualityAssessor = ImageQualityAssessorImpl()
}

/**
 * Android implementation of ImageQualityAssessor using native Android APIs
 */
class ImageQualityAssessorImpl : ImageQualityAssessor {

    private val qualityThresholds = QualityThresholds()

    override fun assessQuality(imageBytes: ByteArray): Flow<ImageQualityAssessment> = flow {
        val assessment = withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: throw IllegalArgumentException("Failed to decode image")

                val detailedMetrics = calculateDetailedMetrics(bitmap)
                val basicAssessment = createBasicAssessment(detailedMetrics)
                val recommendations = generateRecommendations(detailedMetrics)

                basicAssessment
            }.getOrElse {
                ImageQualityAssessment(0f, 0f, 0f, 0f, 0f, QualityFeedback.HoldSteady)
            }
        }
        emit(assessment)
    }

    override fun getQualityThresholds(): QualityThresholds = qualityThresholds

    override suspend fun isQualitySufficient(assessment: ImageQualityAssessment): Boolean {
        return assessment.lightingScore >= qualityThresholds.minimumLightingScore &&
                assessment.focusScore >= qualityThresholds.minimumFocusScore &&
                assessment.angleScore >= qualityThresholds.minimumAngleScore &&
                assessment.distanceScore >= qualityThresholds.minimumDistanceScore &&
                assessment.overallScore >= qualityThresholds.minimumOverallScore
    }

    private fun calculateDetailedMetrics(bitmap: Bitmap): DetailedQualityMetrics {
        return DetailedQualityMetrics(
            brightness = calculateBrightness(bitmap),
            contrast = calculateContrast(bitmap),
            sharpness = calculateSharpness(bitmap),
            noiseLevel = calculateNoiseLevel(bitmap),
            uniformity = calculateUniformity(bitmap),
            edgeStrength = calculateEdgeStrength(bitmap),
            textDensity = calculateTextDensity(bitmap),
            receiptCoverage = calculateReceiptCoverage(bitmap)
        )
    }

    private fun calculateBrightness(bitmap: Bitmap): Float {
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

        // Optimal brightness is around 128 (middle gray)
        val brightnessScore = 1.0f - abs(averageBrightness - 128.0).toFloat() / 128.0f
        return maxOf(0f, brightnessScore)
    }

    private fun calculateContrast(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayValues = pixels.map { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (r + g + b) / 3.0
        }

        val mean = grayValues.average()
        val variance = grayValues.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)

        // Higher standard deviation indicates better contrast
        return minOf(1.0f, (stdDev / 64.0).toFloat())
    }

    private fun calculateSharpness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalVariance = 0.0
        var count = 0

        // Calculate Laplacian variance for sharpness
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayValue(pixels[y * width + x])
                val top = getGrayValue(pixels[(y - 1) * width + x])
                val bottom = getGrayValue(pixels[(y + 1) * width + x])
                val left = getGrayValue(pixels[y * width + (x - 1)])
                val right = getGrayValue(pixels[y * width + (x + 1)])

                val laplacian = abs(4 * center - top - bottom - left - right)
                totalVariance += laplacian * laplacian
                count++
            }
        }

        val variance = if (count > 0) totalVariance / count else 0.0
        return minOf(1.0f, (variance / 2000.0).toFloat())
    }

    private fun calculateNoiseLevel(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalNoise = 0.0
        var count = 0

        // Estimate noise using local variance
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayValue(pixels[y * width + x])
                val neighbors = listOf(
                    getGrayValue(pixels[(y - 1) * width + x]),
                    getGrayValue(pixels[(y + 1) * width + x]),
                    getGrayValue(pixels[y * width + (x - 1)]),
                    getGrayValue(pixels[y * width + (x + 1)])
                )

                val meanNeighbor = neighbors.average()
                val variance = neighbors.map { (it - meanNeighbor).pow(2) }.average()
                totalNoise += sqrt(variance)
                count++
            }
        }

        val averageNoise = if (count > 0) totalNoise / count else 0.0
        // Lower noise is better, so invert the score
        return maxOf(0f, 1.0f - (averageNoise / 50.0).toFloat())
    }

    private fun calculateUniformity(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Calculate histogram for uniformity assessment
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val gray = getGrayValue(pixel)
            histogram[gray]++
        }

        // Calculate histogram entropy
        var entropy = 0.0
        val totalPixels = pixels.size

        for (count in histogram) {
            if (count > 0) {
                val probability = count.toDouble() / totalPixels
                entropy -= probability * ln(probability)
            }
        }

        // Normalize entropy (max entropy for uniform distribution is ln(256))
        return (entropy / ln(256.0)).toFloat()
    }

    private fun calculateEdgeStrength(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalEdgeStrength = 0.0
        var count = 0

        // Simple Sobel edge detection
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayValue(pixels[y * width + x])
                val right = getGrayValue(pixels[y * width + (x + 1)])
                val bottom = getGrayValue(pixels[(y + 1) * width + x])

                val horizontalGradient = abs(right - center)
                val verticalGradient = abs(bottom - center)
                val gradient = sqrt((horizontalGradient * horizontalGradient + verticalGradient * verticalGradient).toDouble())

                totalEdgeStrength += gradient
                count++
            }
        }

        val averageEdgeStrength = if (count > 0) totalEdgeStrength / count else 0.0
        return minOf(1.0f, (averageEdgeStrength / 100.0).toFloat())
    }

    private fun calculateTextDensity(bitmap: Bitmap): Float {
        // Simplified text density estimation using edge patterns
        val edgeStrength = calculateEdgeStrength(bitmap)
        val contrast = calculateContrast(bitmap)

        // Text areas typically have high edge strength and good contrast
        return (edgeStrength + contrast) / 2.0f
    }

    private fun calculateReceiptCoverage(bitmap: Bitmap): Float {
        val imageArea = bitmap.width * bitmap.height

        // Simplified coverage estimation based on image size
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

    private fun createBasicAssessment(metrics: DetailedQualityMetrics): ImageQualityAssessment {
        val lightingScore = (metrics.brightness + metrics.uniformity) / 2.0f
        val focusScore = (metrics.sharpness + (1.0f - metrics.noiseLevel)) / 2.0f
        val angleScore = (metrics.edgeStrength + metrics.receiptCoverage) / 2.0f
        val distanceScore = metrics.receiptCoverage

        val overallScore = (lightingScore + focusScore + angleScore + distanceScore) / 4.0f
        val feedback = determineFeedback(lightingScore, focusScore, angleScore, distanceScore)

        return ImageQualityAssessment(
            lightingScore = lightingScore,
            focusScore = focusScore,
            angleScore = angleScore,
            distanceScore = distanceScore,
            overallScore = overallScore,
            feedback = feedback
        )
    }

    private fun generateRecommendations(metrics: DetailedQualityMetrics): List<QualityRecommendation> {
        val recommendations = mutableListOf<QualityRecommendation>()

        // Lighting recommendations
        when {
            metrics.brightness < 0.3f -> recommendations.add(QualityRecommendation.IncreaseLighting)
            metrics.brightness > 0.9f -> recommendations.add(QualityRecommendation.DecreaseLighting)
            metrics.uniformity < 0.5f -> recommendations.add(QualityRecommendation.AvoidShadows)
        }

        // Focus recommendations
        if (metrics.sharpness < 0.6f) {
            recommendations.add(QualityRecommendation.HoldSteadier)
        }

        if (metrics.noiseLevel > 0.7f) {
            recommendations.add(QualityRecommendation.CleanCameraLens)
        }

        // Distance recommendations
        when {
            metrics.receiptCoverage < 0.3f -> recommendations.add(QualityRecommendation.MoveCloserToReceipt)
            metrics.receiptCoverage > 0.9f -> recommendations.add(QualityRecommendation.MoveFurtherFromReceipt)
        }

        // Angle recommendations
        if (metrics.edgeStrength < 0.5f) {
            recommendations.add(QualityRecommendation.AdjustAngleToReceipt)
        }

        return recommendations
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
