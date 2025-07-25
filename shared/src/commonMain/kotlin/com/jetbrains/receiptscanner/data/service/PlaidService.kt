package com.jetbrains.receiptscanner.data.service

import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction

/**
 * Interface for Plaid API integration
 * This provides a common interface for both Android and iOS implementations
 */
interface PlaidService {
    /**
     * Exchange public token for access token and get account information
     */
    suspend fun exchangePublicToken(publicToken: String): Result<PlaidAccountInfo>

    /**
     * Fetch transactions for a specific account
     */
    suspend fun fetchTransactions(
        accessToken: String,
        accountId: String,
        startDate: String? = null,
        endDate: String? = null
    ): Result<List<PlaidTransaction>>

    /**
     * Get account information
     */
    suspend fun getAccounts(accessToken: String): Result<List<PlaidAccount>>

    /**
     * Remove/disconnect account
     */
    suspend fun removeAccount(accessToken: String): Result<Unit>
}

/**
 * Plaid account information returned from token exchange
 */
data class PlaidAccountInfo(
    val accessToken: String,
    val itemId: String,
    val accounts: List<PlaidAccount>
)

/**
 * Plaid account data
 */
data class PlaidAccount(
    val accountId: String,
    val name: String,
    val officialName: String?,
    val type: String,
    val subtype: String?,
    val mask: String?,
    val institutionId: String?,
    val institutionName: String?
)

/**
 * Plaid transaction data
 */
data class PlaidTransaction(
    val transactionId: String,
    val accountId: String,
    val amount: Double,
    val date: String,
    val name: String,
    val merchantName: String?,
    val category: List<String>?,
    val subcategory: List<String>?
)

/**
 * Extension functions to convert Plaid models to domain models
 */
fun PlaidAccount.toBankAccount(institutionName: String): BankAccount {
    return BankAccount(
        id = accountId,
        institutionName = institutionName,
        accountName = name,
        accountType = type,
        lastFour = mask ?: "****",
        isActive = true
    )
}

fun PlaidTransaction.toBankTransaction(): BankTransaction {
    return BankTransaction(
        id = transactionId,
        merchantName = merchantName ?: name,
        amount = amount,
        date = kotlinx.datetime.Instant.parse("${date}T00:00:00Z"),
        accountId = accountId,
        isGroceryRelated = false, // Will be determined by categorization logic
        matchedReceiptId = null
    )
}
