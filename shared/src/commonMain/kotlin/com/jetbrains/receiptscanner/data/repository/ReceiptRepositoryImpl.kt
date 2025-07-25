package com.jetbrains.receiptscanner.data.repository

import com.jetbrains.receiptscanner.data.datasource.ReceiptDataSource
import com.jetbrains.receiptscanner.domain.model.Receipt
import com.jetbrains.receiptscanner.domain.repository.ReceiptRepository

class ReceiptRepositoryImpl(
    private val dataSource: ReceiptDataSource
) : ReceiptRepository {

    override suspend fun saveReceipt(receipt: Receipt): Result<String> {
        return dataSource.saveReceipt(receipt)
    }

    override suspend fun getReceipts(limit: Int, offset: Int): Result<List<Receipt>> {
        return dataSource.getReceipts(limit, offset)
    }

    override suspend fun getReceiptById(id: String): Result<Receipt?> {
        return dataSource.getReceiptById(id)
    }

    override suspend fun searchReceipts(query: String): Result<List<Receipt>> {
        return dataSource.searchReceipts(query)
    }

    override suspend fun deleteReceipt(id: String): Result<Unit> {
        return dataSource.deleteReceipt(id)
    }
}
