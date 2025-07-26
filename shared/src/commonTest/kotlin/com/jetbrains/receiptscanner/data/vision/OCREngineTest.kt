package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OCREngineTest {

    @Test
    fun testOCREngineInitialization() = runTest {
        val config = OCRModelConfig()
        val ocrEngine = OCREngineFactory.create(config)

        // Test initialization
        val initResult = ocrEngine.initialize()
        assertTrue(initResult.isSuccess, "OCR engine should initialize successfully")

        // Clean up
        ocrEngine.release()
    }

    @Test
    fun testTextDetection() = runTest {
        val config = OCRModelConfig()
        val ocrEngine = OCREngineFactory.create(config)

        // Initialize engine
        ocrEngine.initialize().getOrThrow()

        // Create dummy image data
        val dummyImageBytes = ByteArray(1024) { (it % 256).toByte() }

        // Test text detection
        val detectionResult = ocrEngine.detectTextRegions(dummyImageBytes)
        assertTrue(detectionResult.isSuccess, "Text detection should succeed")

        val result = detectionResult.getOrThrow()
        assertTrue(result.confidence >= 0f, "Confidence should be non-negative")
        assertTrue(result.textDensity >= 0f, "Text density should be non-negative")

        // Clean up
        ocrEngine.release()
    }

    @Test
    fun testTextRecognition() = runTest {
        val config = OCRModelConfig()
        val ocrEngine = OCREngineFactory.create(config)

        // Initialize engine
        ocrEngine.initialize().getOrThrow()

        // Create dummy image data
        val dummyImageBytes = ByteArray(1024) { (it % 256).toByte() }

        // Test text recognition
        val recognitionResult = ocrEngine.recognizeText(dummyImageBytes)
        assertTrue(recognitionResult.isSuccess, "Text recognition should succeed")

        val result = recognitionResult.getOrThrow()
        assertTrue(result.overallConfidence >= 0f, "Overall confidence should be non-negative")

        // Clean up
        ocrEngine.release()
    }

    @Test
    fun testCompleteOCRPipeline() = runTest {
        val config = OCRModelConfig()
        val ocrEngine = OCREngineFactory.create(config)

        // Initialize engine
        ocrEngine.initialize().getOrThrow()

        // Create dummy image data
        val dummyImageBytes = ByteArray(1024) { (it % 256).toByte() }

        // Test complete pipeline
        var stageCount = 0
        ocrEngine.processImage(dummyImageBytes).collect { stage ->
            stageCount++
            when (stage) {
                is OCRProcessingStage.Initializing -> {
                    // Expected first stage
                }
                is OCRProcessingStage.DetectingText -> {
                    assertTrue(stage.progress >= 0f && stage.progress <= 1f, "Progress should be between 0 and 1")
                }
                is OCRProcessingStage.RecognizingText -> {
                    assertTrue(stage.progress >= 0f && stage.progress <= 1f, "Progress should be between 0 and 1")
                    assertTrue(stage.estimatedTimeRemainingMs >= 0, "Estimated time should be non-negative")
                }
                is OCRProcessingStage.PostProcessing -> {
                    assertTrue(stage.progress >= 0f && stage.progress <= 1f, "Progress should be between 0 and 1")
                }
                is OCRProcessingStage.Completed -> {
                    assertTrue(stage.result.overallConfidence >= 0f, "Final confidence should be non-negative")
                }
                is OCRProcessingStage.Error -> {
                    throw AssertionError("OCR processing should not fail: ${stage.error}")
                }
            }
        }

        assertTrue(stageCount > 0, "Should have processed at least one stage")

        // Clean up
        ocrEngine.release()
    }
}
