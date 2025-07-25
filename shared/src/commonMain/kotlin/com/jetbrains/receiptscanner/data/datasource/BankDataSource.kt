package com.jetbrains.receiptscanner.data.datasource

import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction

interface BankDataSource {
    // Remote operations (Plaid API)
    suspend fun connectAccount(publicToken: String): Result<BankAccount>
    suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>>
    suspend fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction>
    suspend fun disconnectAccount(accountId: String): Result<Unit>

    // Local database operations
    suspend fun saveBankAccount(account: BankAccount): Result<String>
    suspend fun getBankAccounts(): Result<List<BankAccount>>
    suspend fun getBankAccountById(id: String): Result<BankAccount?>
    suspend fun deactivateBankAccount(id: String): Result<Unit>

    suspend fun saveBankTransactions(transactions: List<BankTransaction>): Result<Unit>
    suspend fun getTransactionsByAccount(accountId: String, limit: Int, offset: Int): Result<List<BankTransaction>>
    suspend fun getGroceryTransactions(limit: Int, offset: Int): Result<List<BankTransaction>>
    suspend fun getUnmatchedGroceryTransactions(): Result<List<BankTransaction>>
    suspend fun matchTransactionToReceipt(transactionId: String, receiptId: String): Result<Unit>
    suspend fun unmatchTransaction(transactionId: String): Result<Unit>
}
