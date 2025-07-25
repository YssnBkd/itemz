package com.jetbrains.receiptscanner.data.datasource

import com.jetbrains.receiptscanner.data.security.SecureStorage
import com.jetbrains.receiptscanner.data.security.SecureStorageKeys
import com.jetbrains.receiptscanner.data.service.PlaidService
import com.jetbrains.receiptscanner.data.service.TransactionCategorizationService
import com.jetbrains.receiptscanner.data.service.toBankAccount
import com.jetbrains.receiptscanner.data.service.toBankTransaction
import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase
import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class BankDataSourceImpl(
    private val database: ReceiptScannerDatabase,
    private val plaidService: PlaidService,
    private val secureStorage: SecureStorage,
    private val categorizationService: TransactionCategorizationService
) : BankDataSource {

    // Remote operations (Plaid API)
    override suspend fun connectAccount(publicToken: String): Result<BankAccount> {
        return plaidService.exchangePublicToken(publicToken).mapCatching { accountInfo ->
            // Store access token securely
            val primaryAccount = accountInfo.accounts.first()
            val accessTokenKey = SecureStorageKeys.accessTokenKey(primaryAccount.accountId)
            val itemIdKey = SecureStorageKeys.itemIdKey(primaryAccount.accountId)

            secureStorage.store(accessTokenKey, accountInfo.accessToken).getOrThrow()
            secureStorage.store(itemIdKey, accountInfo.itemId).getOrThrow()

            // Convert to domain model and return the primary account
            primaryAccount.toBankAccount(primaryAccount.institutionName ?: "Unknown Bank")
        }
    }

    override suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>> {
        return runCatching {
            val accessTokenKey = SecureStorageKeys.accessTokenKey(accountId)
            val accessToken = secureStorage.retrieve(accessTokenKey).getOrThrow()
                ?: throw IllegalStateException("No access token found for account $accountId")

            val plaidTransactions = plaidService.fetchTransactions(accessToken, accountId).getOrThrow()
            plaidTransactions.map { it.toBankTransaction() }
        }
    }

    override suspend fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction> {
        return categorizationService.categorizeTransactions(transactions)
    }

    override suspend fun disconnectAccount(accountId: String): Result<Unit> {
        return runCatching {
            val accessTokenKey = SecureStorageKeys.accessTokenKey(accountId)
            val itemIdKey = SecureStorageKeys.itemIdKey(accountId)

            val accessToken = secureStorage.retrieve(accessTokenKey).getOrNull()

            // Remove from Plaid if we have the access token
            accessToken?.let { token ->
                plaidService.removeAccount(token).getOrThrow()
            }

            // Remove stored credentials
            secureStorage.remove(accessTokenKey).getOrThrow()
            secureStorage.remove(itemIdKey).getOrThrow()

            // Deactivate locally
            deactivateBankAccount(accountId).getOrThrow()
        }
    }

    // Local database operations
    override suspend fun saveBankAccount(account: BankAccount): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            val now = Clock.System.now().epochSeconds

            database.receiptScannerDatabaseQueries.insertBankAccount(
                id = account.id,
                institution_name = account.institutionName,
                account_name = account.accountName,
                account_type = account.accountType,
                last_four = account.lastFour,
                is_active = if (account.isActive) 1L else 0L,
                created_at = now,
                updated_at = now
            )
            account.id
        }
    }

    override suspend fun getBankAccounts(): Result<List<BankAccount>> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.selectAllBankAccounts()
                .executeAsList()
                .map { row ->
                    BankAccount(
                        id = row.id,
                        institutionName = row.institution_name,
                        accountName = row.account_name,
                        accountType = row.account_type,
                        lastFour = row.last_four,
                        isActive = row.is_active == 1L
                    )
                }
        }
    }

    override suspend fun getBankAccountById(id: String): Result<BankAccount?> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.selectBankAccountById(id)
                .executeAsOneOrNull()
                ?.let { row ->
                    BankAccount(
                        id = row.id,
                        institutionName = row.institution_name,
                        accountName = row.account_name,
                        accountType = row.account_type,
                        lastFour = row.last_four,
                        isActive = row.is_active == 1L
                    )
                }
        }
    }

    override suspend fun deactivateBankAccount(id: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val now = Clock.System.now().epochSeconds
            database.receiptScannerDatabaseQueries.deactivateBankAccount(
                updated_at = now,
                id = id
            )
        }
    }

    override suspend fun saveBankTransactions(transactions: List<BankTransaction>): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            database.transaction {
                transactions.forEach { transaction ->
                    val now = Clock.System.now().epochSeconds
                    database.receiptScannerDatabaseQueries.insertBankTransaction(
                        id = transaction.id,
                        account_id = transaction.accountId,
                        merchant_name = transaction.merchantName,
                        amount = transaction.amount,
                        transaction_date = transaction.date.epochSeconds,
                        is_grocery_related = if (transaction.isGroceryRelated) 1L else 0L,
                        matched_receipt_id = transaction.matchedReceiptId,
                        created_at = now
                    )
                }
            }
        }
    }

    override suspend fun getTransactionsByAccount(accountId: String, limit: Int, offset: Int): Result<List<BankTransaction>> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.selectTransactionsByAccount(
                account_id = accountId,
                value_ = limit.toLong(),
                value__ = offset.toLong()
            ).executeAsList().map { row ->
                BankTransaction(
                    id = row.id,
                    merchantName = row.merchant_name,
                    amount = row.amount,
                    date = Instant.fromEpochSeconds(row.transaction_date),
                    accountId = row.account_id,
                    isGroceryRelated = row.is_grocery_related == 1L,
                    matchedReceiptId = row.matched_receipt_id
                )
            }
        }
    }

    override suspend fun getGroceryTransactions(limit: Int, offset: Int): Result<List<BankTransaction>> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.selectGroceryTransactions(
                value_ = limit.toLong(),
                value__ = offset.toLong()
            ).executeAsList().map { row ->
                BankTransaction(
                    id = row.id,
                    merchantName = row.merchant_name,
                    amount = row.amount,
                    date = Instant.fromEpochSeconds(row.transaction_date),
                    accountId = row.account_id,
                    isGroceryRelated = row.is_grocery_related == 1L,
                    matchedReceiptId = row.matched_receipt_id
                )
            }
        }
    }

    override suspend fun getUnmatchedGroceryTransactions(): Result<List<BankTransaction>> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.selectUnmatchedGroceryTransactions()
                .executeAsList().map { row ->
                    BankTransaction(
                        id = row.id,
                        merchantName = row.merchant_name,
                        amount = row.amount,
                        date = Instant.fromEpochSeconds(row.transaction_date),
                        accountId = row.account_id,
                        isGroceryRelated = row.is_grocery_related == 1L,
                        matchedReceiptId = row.matched_receipt_id
                    )
                }
        }
    }

    override suspend fun matchTransactionToReceipt(transactionId: String, receiptId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.matchTransactionToReceipt(
                matched_receipt_id = receiptId,
                id = transactionId
            )
        }
    }

    override suspend fun unmatchTransaction(transactionId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            database.receiptScannerDatabaseQueries.unmatchTransaction(transactionId)
        }
    }


}
