package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiptParserSimpleTest {

    @Test
    fun testReceiptParserCreation() {
        val receiptParser = ReceiptParserImpl()
        assertNotNull(receiptParser)
    }

    @Test
    fun testItemLineParserCreation() {
        val itemLineParser = ItemLineParser()
        assertNotNull(itemLineParser)
    }

    @Test
    fun testConfidenceScorerCreation() {
        val confidenceScorer = ConfidenceScorer()
        assertNotNull(confidenceScorer)
    }

    @Test
    fun testBasicItemLineParsing() {
        val itemLineParser = ItemLineParser()
        val mockLine = RecognizedTextLine(
            text = "BANANAS $2.98",
            boundingBox = Rectangle(0f, 0f, 100f, 20f),
            confidence = 0.9f
        )

        val result = itemLineParser.parseItemLine(mockLine)
        assertNotNull(result)
        assertTrue(result.name.contains("BANANAS"))
        assertTrue(result.lineTotal > 0.0)
    }

    @Test
    fun testReceiptLayoutClassification() {
        val receiptParser = ReceiptParserImpl()
        val mockLines = listOf(
            RecognizedTextLine("WALMART SUPERCENTER", Rectangle(0f, 0f, 100f, 20f), 0.9f)
        )

        val layout = receiptParser.classifyReceiptLayout(mockLines)
        assertTrue(layout == ReceiptLayout.WALMART || layout == ReceiptLayout.GENERIC_GROCERY)
    }
}
