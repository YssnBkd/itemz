package com.jetbrains.receiptscanner.domain.model

import kotlinx.datetime.Instant

data class Receipt(
    val id: String,
    val storeInfo: StoreInfo,
    val items: List<ReceiptItem>,
    val totals: ReceiptTotals,
    val timestamp: Instant,
    val imageUrl: String?,
    val processingStatus: ProcessingStatus
)

data class ReceiptItem(
    val id: String,
    val name: String,
    val normalizedName: String,
    val price: Double,
    val quantity: Int,
    val category: ItemCategory,
    val confidence: Float
)

data class ReceiptTotals(
    val subtotal: Double,
    val taxAmount: Double,
    val total: Double
)

data class StoreInfo(
    val name: String,
    val address: String?,
    val chainId: String?
)

enum class ItemCategory {
    PRODUCE, DAIRY, MEAT, PACKAGED_GOODS,
    HOUSEHOLD, BEVERAGES, SNACKS, OTHER
}

enum class ProcessingStatus {
    PENDING, PROCESSING, COMPLETED, REQUIRES_REVIEW, FAILED
}
