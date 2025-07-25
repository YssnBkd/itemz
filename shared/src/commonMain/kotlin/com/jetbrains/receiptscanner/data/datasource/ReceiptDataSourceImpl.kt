package com.jetbrains.receiptscanner.data.datasource

import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase
import com.jetbrains.receiptscanner.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class ReceiptDataSourceImpl(
    private val database: ReceiptScannerDatabase
) : ReceiptDataSource {

    override suspend fun saveReceipt(receipt: Receipt): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            database.transaction {
                val now = Clock.System.now().epochSeconds

                // Insert or update receipt
                database.receiptScannerDatabaseQueries.insertReceipt(
                    id = receipt.id,
                    store_name = receipt.storeInfo.name,
                    store_address = receipt.storeInfo.address,
                    store_chain_id = receipt.storeInfo.chainId,
                    subtotal = receipt.totals.subtotal,
                    tax_amount = receipt.totals.taxAmount,
                    total_amount = receipt.totals.total,
                    receipt_date = receipt.timestamp.epochSeconds,
                    image_path = receipt.imageUrl,
                    processing_status = receipt.processingStatus.name,
                    created_at = now,
                    updated_at = now
                )

                // Delete existing items and insert new ones
                database.receiptScannerDatabaseQueries.deleteReceiptItems(receipt.id)

                receipt.items.forEachIndexed { index, item ->
                    database.receiptScannerDatabaseQueries.insertReceiptItem(
                        id = item.id,
                        receipt_id = receipt.id,
                        item_name = item.name,
                        normalized_name = item.normalizedName,
                        price = item.price,
                        quantity = item.quantity.toLong(),
                        category = item.category.name,
                        confidence = item.confidence.toDouble(),
                        line_number = index.toLong()
                    )
                }
            }
            receipt.id
        }
    }

    override suspend fun getReceipts(limit: Int, offset: Int): Result<List<Receipt>> = withContext(Dispatchers.Default) {
        runCatching {
            val receipts = database.receiptScannerDatabaseQueries.selectAllReceipts(
                value_ = limit.toLong(),
                value__ = offset.toLong()
            ).executeAsList()

            receipts.map { receiptRow ->
                val items = database.receiptScannerDatabaseQueries.selectReceiptItems(receiptRow.id)
                    .executeAsList()
                    .map { itemRow ->
                        ReceiptItem(
                            id = itemRow.id,
                            name = itemRow.item_name,
                            normalizedName = itemRow.normalized_name,
                            price = itemRow.price,
                            quantity = itemRow.quantity.toInt(),
                            category = ItemCategory.valueOf(itemRow.category),
                            confidence = itemRow.confidence.toFloat()
                        )
                    }

                Receipt(
                    id = receiptRow.id,
                    storeInfo = StoreInfo(
                        name = receiptRow.store_name,
                        address = receiptRow.store_address,
                        chainId = receiptRow.store_chain_id
                    ),
                    items = items,
                    totals = ReceiptTotals(
                        subtotal = receiptRow.subtotal,
                        taxAmount = receiptRow.tax_amount,
                        total = receiptRow.total_amount
                    ),
                    timestamp = Instant.fromEpochSeconds(receiptRow.receipt_date),
                    imageUrl = receiptRow.image_path,
                    processingStatus = ProcessingStatus.valueOf(receiptRow.processing_status)
                )
            }
        }
    }

    override suspend fun getReceiptById(id: String): Result<Receipt?> = withContext(Dispatchers.Default) {
        runCatching {
            val receiptRow = database.receiptScannerDatabaseQueries.selectReceiptById(id)
                .executeAsOneOrNull() ?: return@runCatching null

            val items = database.receiptScannerDatabaseQueries.selectReceiptItems(id)
                .executeAsList()
                .map { itemRow ->
                    ReceiptItem(
                        id = itemRow.id,
                        name = itemRow.item_name,
                        normalizedName = itemRow.normalized_name,
                        price = itemRow.price,
                        quantity = itemRow.quantity.toInt(),
                        category = ItemCategory.valueOf(itemRow.category),
                        confidence = itemRow.confidence.toFloat()
                    )
                }

            Receipt(
                id = receiptRow.id,
                storeInfo = StoreInfo(
                    name = receiptRow.store_name,
                    address = receiptRow.store_address,
                    chainId = receiptRow.store_chain_id
                ),
                items = items,
                totals = ReceiptTotals(
                    subtotal = receiptRow.subtotal,
                    taxAmount = receiptRow.tax_amount,
                    total = receiptRow.total_amount
                ),
                timestamp = Instant.fromEpochSeconds(receiptRow.receipt_date),
                imageUrl = receiptRow.image_path,
                processingStatus = ProcessingStatus.valueOf(receiptRow.processing_status)
            )
        }
    }

    override suspend fun searchReceipts(query: String): Result<List<Receipt>> = withContext(Dispatchers.Default) {
        runCatching {
            val receipts = database.receiptScannerDatabaseQueries.searchReceiptsByStore(query)
                .executeAsList()

            receipts.map { receiptRow ->
                val items = database.receiptScannerDatabaseQueries.selectReceiptItems(receiptRow.id)
                    .executeAsList()
                    .map { itemRow ->
                        ReceiptItem(
                            id = itemRow.id,
                            name = itemRow.item_name,
                            normalizedName = itemRow.normalized_name,
                            price = itemRow.price,
                            quantity = itemRow.quantity.toInt(),
                            category = ItemCategory.valueOf(itemRow.category),
                            confidence = itemRow.confidence.toFloat()
                        )
                    }

                Receipt(
                    id = receiptRow.id,
                    storeInfo = StoreInfo(
                        name = receiptRow.store_name,
                        address = receiptRow.store_address,
                        chainId = receiptRow.store_chain_id
                    ),
                    items = items,
                    totals = ReceiptTotals(
                        subtotal = receiptRow.subtotal,
                        taxAmount = receiptRow.tax_amount,
                        total = receiptRow.total_amount
                    ),
                    timestamp = Instant.fromEpochSeconds(receiptRow.receipt_date),
                    imageUrl = receiptRow.image_path,
                    processingStatus = ProcessingStatus.valueOf(receiptRow.processing_status)
                )
            }
        }
    }

    override suspend fun deleteReceipt(id: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            database.transaction {
                database.receiptScannerDatabaseQueries.deleteReceiptItems(id)
                database.receiptScannerDatabaseQueries.deleteReceipt(id)
            }
        }
    }
}
