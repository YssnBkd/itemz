package com.jetbrains.receiptscanner.data.repository

import com.jetbrains.receiptscanner.data.datasource.BankDataSource
import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction
import com.jetbrains.receiptscanner.domain.repository.BankRepository

class BankRepositoryImpl(
    private val dataSource: BankDataSource
) : BankRepository {

    override suspend fun connectAccount(publicToken: String): Result<BankAccount> {
        return dataSource.connectAccount(publicToken).onSuccess { account ->
            // Save the connected account locally
            dataSource.saveBankAccount(account)
        }
    }

    override suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>> {
        return dataSource.fetchTransactions(accountId).onSuccess { transactions ->
            // Categorize and save transactions locally
            val categorizedTransactions = dataSource.categorizeTransactions(transactions)
            dataSource.saveBankTransactions(categorizedTransactions)
        }
    }

    override suspend fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction> {
        return dataSource.categorizeTransactions(transactions)
    }

    override suspend fun disconnectAccount(accountId: String): Result<Unit> {
        return dataSource.disconnectAccount(accountId)
    }

    // Additional methods for local data access
    suspend fun getBankAccounts(): Result<List<BankAccount>> {
        return dataSource.getBankAccounts()
    }

    suspend fun getGroceryTransactions(limit: Int = 50, offset: Int = 0): Result<List<BankTransaction>> {
        return dataSource.getGroceryTransactions(limit, offset)
    }

    suspend fun getUnmatchedGroceryTransactions(): Result<List<BankTransaction>> {
        return dataSource.getUnmatchedGroceryTransactions()
    }

    suspend fun matchTransactionToReceipt(transactionId: String, receiptId: String): Result<Unit> {
        return dataSource.matchTransactionToReceipt(transactionId, receiptId)
    }

    suspend fun unmatchTransaction(transactionId: String): Result<Unit> {
        return dataSource.unmatchTransaction(transactionId)
    }
}
