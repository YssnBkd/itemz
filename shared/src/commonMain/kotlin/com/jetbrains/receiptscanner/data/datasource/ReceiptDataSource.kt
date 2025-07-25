package com.jetbrains.receiptscanner.data.datasource

import com.jetbrains.receiptscanner.domain.model.Receipt

interface ReceiptDataSource {
    suspend fun saveReceipt(receipt: Receipt): Result<String>
    suspend fun getReceipts(limit: Int, offset: Int): Result<List<Receipt>>
    suspend fun getReceiptById(id: String): Result<Receipt?>
    suspend fun searchReceipts(query: String): Result<List<Receipt>>
    suspend fun deleteReceipt(id: String): Result<Unit>
}
