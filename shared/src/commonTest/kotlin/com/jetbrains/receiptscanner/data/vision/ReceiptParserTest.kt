package com.jetbrains.receiptscanner.data.vision

import com.jetbrains.receiptscanner.domain.model.ItemCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiptParserTest {

    private val receiptParser = ReceiptParserImpl()

    @Test
    fun testParseSimpleGroceryReceipt() = runTest {
        // Mock OCR result for a simple grocery receipt
        val mockOcrResult = createMockOcrResult(
            listOf(
                "WALMART SUPERCENTER",
                "123 MAIN ST",
                "ANYTOWN, ST 12345",
                "",
                "2x BANANAS         $2.98",
                "MILK GALLON        $3.49",
                "BREAD LOAF         $1.99",
                "",
                "SUBTOTAL           $8.46",
                "TAX                $0.54",
                "TOTAL              $9.00",
                "",
                "01/15/2024 14:30"
            )
        )

        val result = receiptParser.parseReceipt(mockOcrResult)

        assertTrue(result.isSuccess)
        val receiptData = result.getOrThrow()

        // Verify merchant info
        assertEquals("WALMART SUPERCENTER", receiptData.merchantInfo.name)
        assertEquals(ReceiptLayout.WALMART, receiptData.layout)

        // Verify items
        assertEquals(3, receiptData.items.size)

        val bananas = receiptData.items.find { it.name.contains("BANANAS") }
        assertNotNull(bananas)
        assertEquals(2, bananas.quantity)
        assertEquals(1.49, bananas.unitPrice, 0.01)
        assertEquals(2.98, bananas.lineTotal, 0.01)
        assertEquals(ItemCategory.PRODUCE, bananas.category)

        val milk = receiptData.items.find { it.name.contains("MILK") }
        assertNotNull(milk)
        assertEquals(1, milk.quantity)
        assertEquals(3.49, milk.unitPrice, 0.01)
        assertEquals(ItemCategory.DAIRY, milk.category)

        // Verify totals
        assertEquals(8.46, receiptData.totals.subtotal, 0.01)
        assertEquals(0.54, receiptData.totals.taxAmount, 0.01)
        assertEquals(9.00, receiptData.totals.total, 0.01)

        // Verify confidence score is reasonable
        assertTrue(receiptData.confidenceScore > 0.5f)
    }

    @Test
    fun testItemLineParsingWithQuantities() {
        val itemLineParser = ItemLineParser()

        // Test various quantity patterns
        val testCases = listOf(
            "2x BANANAS $2.98" to Pair(2, "BANANAS"),
            "3 @ APPLES $4.50" to Pair(3, "APPLES"),
            "4 for ORANGES $6.00" to Pair(4, "ORANGES"),
            "5 ea MILK $17.45" to Pair(5, "MILK"),
            "BREAD LOAF $1.99" to Pair(1, "BREAD LOAF")
        )

        testCases.forEach { (input, expected) ->
            val mockLine = RecognizedTextLine(
                text = input,
                boundingBox = Rectangle(0f, 0f, 100f, 20f),
                confidence = 0.9f
            )

            val result = itemLineParser.parseItemLine(mockLine)
            assertNotNull(result, "Failed to parse: $input")
            assertEquals(expected.first, result.quantity, "Quantity mismatch for: $input")
            assertTrue(result.name.contains(expected.second), "Name mismatch for: $input")
        }
    }

    @Test
    fun testConfidenceScoring() {
        val confidenceScorer = ConfidenceScorer()

        // Create mock receipt data with high confidence
        val highConfidenceData = createMockReceiptData(
            merchantName = "WALMART SUPERCENTER",
            itemsValid = true,
            mathValid = true
        )

        val mockOcrResult = createMockOcrResult(listOf("High confidence text"))

        val confidenceScore = confidenceScorer.calculateOverallConfidence(highConfidenceData, mockOcrResult)

        assertTrue(confidenceScore.overallScore > 0.7f)
        assertEquals(ConfidenceLevel.HIGH, confidenceScore.level)
        assertTrue(confidenceScore.issues.isEmpty())
    }

    @Test
    fun testMerchantPatternMatching() {
        val testCases = mapOf(
            "WALMART SUPERCENTER" to ReceiptLayout.WALMART,
            "TARGET STORE" to ReceiptLayout.TARGET,
            "KROGER #123" to ReceiptLayout.KROGER,
            "SAFEWAY STORE" to ReceiptLayout.SAFEWAY,
            "COSTCO WHOLESALE" to ReceiptLayout.COSTCO,
            "UNKNOWN STORE" to ReceiptLayout.GENERIC_GROCERY
        )

        testCases.forEach { (merchantName, expectedLayout) ->
            val mockLines = listOf(
                RecognizedTextLine(merchantName, Rectangle(0f, 0f, 100f, 20f), 0.9f)
            )

            val layout = receiptParser.classifyReceiptLayout(mockLines)
            assertEquals(expectedLayout, layout, "Layout mismatch for: $merchantName")
        }
    }

    @Test
    fun testPriceValidation() {
        val itemLineParser = ItemLineParser()

        // Test price validation with different scenarios
        val testCases = listOf(
            "2x ITEM $4.00 $8.00" to true,  // Valid: 2 * $4.00 = $8.00
            "3x ITEM $2.00 $6.01" to false, // Invalid: 3 * $2.00 ≠ $6.01
            "ITEM $5.99" to true            // Valid: single price
        )

        testCases.forEach { (input, expectedValid) ->
            val mockLine = RecognizedTextLine(input, Rectangle(0f, 0f, 100f, 20f), 0.9f)
            val result = itemLineParser.parseItemLine(mockLine)

            assertNotNull(result, "Failed to parse: $input")
            assertEquals(expectedValid, result.priceValidation.isValid, "Price validation mismatch for: $input")
        }
    }

    private fun createMockOcrResult(lines: List<String>): TextRecognitionResult {
        val recognizedLines = lines.mapIndexed { index, text ->
            RecognizedTextLine(
                text = text,
                boundingBox = Rectangle(0f, index * 20f, 200f, (index + 1) * 20f),
                confidence = 0.9f
            )
        }

        return TextRecognitionResult(
            recognizedLines = recognizedLines,
            processingTimeMs = 1000L,
            overallConfidence = 0.9f
        )
    }

    private fun createMockReceiptData(
        merchantName: String,
        itemsValid: Boolean,
        mathValid: Boolean
    ): ReceiptData {
        val merchantInfo = MerchantInfo(
            name = merchantName,
            address = "123 Main St",
            chainId = "walmart",
            phoneNumber = null,
            confidence = 0.9f
        )

        val items = listOf(
            ParsedReceiptItem(
                name = "Test Item",
                normalizedName = "test item",
                quantity = 1,
                unitPrice = 5.99,
                lineTotal = 5.99,
                category = ItemCategory.PACKAGED_GOODS,
                confidence = 0.9f,
                quantityDetection = null,
                priceValidation = PriceValidation(itemsValid, 5.99, 5.99, 0.0, 0.9f),
                boundingBox = Rectangle(0f, 0f, 100f, 20f)
            )
        )

        val totals = ReceiptTotals(
            subtotal = 5.99,
            taxAmount = 0.48,
            total = if (mathValid) 6.47 else 6.50, // Introduce math error if needed
            confidence = 0.9f,
            validation = TotalsValidation(5.99, 5.99, 6.47, mathValid, if (mathValid) 0.0 else 0.03)
        )

        return ReceiptData(
            merchantInfo = merchantInfo,
            items = items,
            totals = totals,
            dateTime = null,
            layout = ReceiptLayout.WALMART,
            textRegions = ReceiptTextRegions(emptyList(), emptyList(), emptyList(), emptyList()),
            confidenceScore = 0.9f,
            validationResults = emptyList()
        )
    }
}
