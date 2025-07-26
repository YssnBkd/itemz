package com.jetbrains.receiptscanner.domain.usecase

import com.jetbrains.receiptscanner.data.vision.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ScanReceiptUseCaseTest {

    @Test
    fun testReceiptScanningFlow() = runTest {
        // Create OCR engine and receipt parser
        val ocrEngine = OCREngineFactory.create()
        val receiptParser = ReceiptParserImpl()
        val scanReceiptUseCase = ScanReceiptUseCase(ocrEngine, receiptParser)

        // Create dummy receipt image data
        val dummyImageBytes = ByteArray(2048) { (it % 256).toByte() }

        // Test the complete scanning flow
        var progressCount = 0
        var finalReceipt: ParsedReceipt? = null

        scanReceiptUseCase.execute(dummyImageBytes).collect { progress ->
            progressCount++
            when (progress) {
                is ScanProgress.Initializing -> {
                    // Expected first stage
                }
                is ScanProgress.Processing -> {
                    assertTrue(progress.message.isNotBlank(), "Progress message should not be empty")
                }
                is ScanProgress.Completed -> {
                    finalReceipt = progress.receipt
                    assertTrue(progress.receipt.storeName.isNotBlank(), "Store name should not be empty")
                    assertTrue(progress.receipt.confidence >= 0f, "Confidence should be non-negative")
                }
                is ScanProgress.Error -> {
                    throw AssertionError("Receipt scanning should not fail: ${progress.message}")
                }
            }
        }

        assertTrue(progressCount > 0, "Should have at least one progress update")
        assertTrue(finalReceipt != null, "Should have a final receipt result")

        // Verify receipt structure
        finalReceipt?.let { receipt ->
            assertTrue(receipt.storeName.isNotEmpty(), "Store name should not be empty")
            assertTrue(receipt.items.isNotEmpty(), "Should have at least one item")
            assertTrue(receipt.confidence >= 0f && receipt.confidence <= 1f, "Confidence should be between 0 and 1")
        }
    }

    @Test
    fun testReceiptParserWithSampleOCRResult() = runTest {
        val receiptParser = ReceiptParserImpl()

        // Create sample OCR result
        val sampleLines = listOf(
            RecognizedTextLine("WALMART SUPERCENTER", Rectangle(0f, 0f, 200f, 30f), 0.95f),
            RecognizedTextLine("123 MAIN ST", Rectangle(0f, 30f, 150f, 20f), 0.85f),
            RecognizedTextLine("BANANAS ORGANIC $2.99", Rectangle(0f, 100f, 180f, 25f), 0.90f),
            RecognizedTextLine("MILK 2% GALLON $3.49", Rectangle(0f, 125f, 170f, 25f), 0.88f),
            RecognizedTextLine("SUBTOTAL $6.48", Rectangle(0f, 200f, 120f, 25f), 0.92f),
            RecognizedTextLine("TAX $0.52", Rectangle(0f, 225f, 80f, 25f), 0.90f),
            RecognizedTextLine("TOTAL $7.00", Rectangle(0f, 250f, 100f, 25f), 0.95f)
        )

        val ocrResult = TextRecognitionResult(
            recognizedLines = sampleLines,
            processingTimeMs = 1500L,
            overallConfidence = 0.91f
        )

        // Parse the receipt
        val parsedReceipt = receiptParser.parseReceipt(ocrResult)

        // Verify parsing results
        assertTrue(parsedReceipt.storeName.contains("WALMART"), "Should identify Walmart as store")
        assertTrue(parsedReceipt.storeAddress?.contains("MAIN ST") == true, "Should extract address")
        assertTrue(parsedReceipt.items.size >= 2, "Should extract at least 2 items")
        assertTrue(parsedReceipt.subtotal == 6.48, "Should extract correct subtotal")
        assertTrue(parsedReceipt.tax == 0.52, "Should extract correct tax")
        assertTrue(parsedReceipt.total == 7.00, "Should extract correct total")

        // Verify items
        val bananaItem = parsedReceipt.items.find { it.name.contains("BANANAS") }
        assertTrue(bananaItem != null, "Should find banana item")
        assertTrue(bananaItem?.price == 2.99, "Should extract correct banana price")

        val milkItem = parsedReceipt.items.find { it.name.contains("MILK") }
        assertTrue(milkItem != null, "Should find milk item")
        assertTrue(milkItem?.price == 3.49, "Should extract correct milk price")
    }
}
