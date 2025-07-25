package com.jetbrains.receiptscanner.domain.repository

import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction

interface BankRepository {
    suspend fun connectAccount(publicToken: String): Result<BankAccount>
    suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>>
    suspend fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction>
    suspend fun disconnectAccount(accountId: String): Result<Unit>
}
