package com.jetbrains.receiptscanner.data.service

import com.jetbrains.receiptscanner.data.datasource.BankDataSource
import com.jetbrains.receiptscanner.domain.model.BankAccount
import com.jetbrains.receiptscanner.domain.model.BankTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Service for syncing bank transactions with real-time updates and error handling
 * Provides automatic retry logic and background synchronization
 */
class TransactionSyncService(
    private val bankDataSource: BankDataSource,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

    private val _syncErrors = MutableSharedFlow<SyncError>()
    val syncErrors: SharedFlow<SyncError> = _syncErrors.asSharedFlow()

    private var syncJob: Job? = null

    /**
     * Start automatic transaction sync for all active accounts
     */
    fun startAutoSync(intervalMinutes: Long = 30) {
        stopAutoSync()

        syncJob = coroutineScope.launch {
            while (isActive) {
                try {
                    syncAllAccounts()
                    delay(intervalMinutes * 60 * 1000) // Convert to milliseconds
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _syncErrors.emit(SyncError.AutoSyncFailed(e.message ?: "Unknown error"))
                    delay(5 * 60 * 1000) // Wait 5 minutes before retry
                }
            }
        }
    }

    /**
     * Stop automatic transaction sync
     */
    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Manually sync transactions for all accounts
     */
    suspend fun syncAllAccounts(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            _syncStatus.value = SyncStatus.Syncing

            try {
                val accounts = bankDataSource.getBankAccounts().getOrThrow()
                val results = mutableListOf<AccountSyncResult>()

                accounts.forEach { account ->
                    val accountResult = syncAccount(account)
                    results.add(accountResult)
                }

                val totalTransactions = results.sumOf { it.transactionCount }
                val failedAccounts = results.filter { !it.success }

                _lastSyncTime.value = Clock.System.now()
                _syncStatus.value = SyncStatus.Idle

                val syncResult = SyncResult(
                    totalAccounts = accounts.size,
                    successfulAccounts = results.count { it.success },
                    totalTransactions = totalTransactions,
                    failedAccounts = failedAccounts.map { it.accountId },
                    syncTime = Clock.System.now()
                )

                if (failedAccounts.isNotEmpty()) {
                    _syncErrors.emit(SyncError.PartialSyncFailure(failedAccounts.mapNotNull { it.error }))
                }

                Result.success(syncResult)

            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error(e.message ?: "Sync failed")
                _syncErrors.emit(SyncError.FullSyncFailure(e.message ?: "Unknown error"))
                Result.failure(e)
            }
        }
    }

    /**
     * Sync transactions for a specific account
     */
    suspend fun syncAccount(account: BankAccount): AccountSyncResult {
        return try {
            val transactions = bankDataSource.fetchTransactions(account.id).getOrThrow()
            val categorizedTransactions = bankDataSource.categorizeTransactions(transactions)
            bankDataSource.saveBankTransactions(categorizedTransactions).getOrThrow()

            AccountSyncResult(
                accountId = account.id,
                success = true,
                transactionCount = transactions.size,
                error = null
            )
        } catch (e: Exception) {
            AccountSyncResult(
                accountId = account.id,
                success = false,
                transactionCount = 0,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Sync transactions for a specific account with retry logic
     */
    suspend fun syncAccountWithRetry(
        account: BankAccount,
        maxRetries: Int = 3,
        retryDelayMs: Long = 1000
    ): AccountSyncResult {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return syncAccount(account)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(retryDelayMs * (attempt + 1)) // Exponential backoff
                }
            }
        }

        return AccountSyncResult(
            accountId = account.id,
            success = false,
            transactionCount = 0,
            error = lastException?.message ?: "Max retries exceeded"
        )
    }

    /**
     * Get sync statistics
     */
    suspend fun getSyncStatistics(): SyncStatistics {
        val accounts = bankDataSource.getBankAccounts().getOrElse { emptyList() }
        val totalTransactions = accounts.sumOf { account ->
            bankDataSource.getTransactionsByAccount(account.id, Int.MAX_VALUE, 0)
                .getOrElse { emptyList() }.size
        }

        return SyncStatistics(
            totalAccounts = accounts.size,
            activeAccounts = accounts.count { it.isActive },
            totalTransactions = totalTransactions,
            lastSyncTime = _lastSyncTime.value
        )
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopAutoSync()
        coroutineScope.cancel()
    }
}

/**
 * Sync status states
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * Sync error types
 */
sealed class SyncError {
    data class AutoSyncFailed(val message: String) : SyncError()
    data class FullSyncFailure(val message: String) : SyncError()
    data class PartialSyncFailure(val errors: List<String>) : SyncError()
}

/**
 * Result of syncing all accounts
 */
data class SyncResult(
    val totalAccounts: Int,
    val successfulAccounts: Int,
    val totalTransactions: Int,
    val failedAccounts: List<String>,
    val syncTime: Instant
)

/**
 * Result of syncing a single account
 */
data class AccountSyncResult(
    val accountId: String,
    val success: Boolean,
    val transactionCount: Int,
    val error: String?
)

/**
 * Sync statistics
 */
data class SyncStatistics(
    val totalAccounts: Int,
    val activeAccounts: Int,
    val totalTransactions: Int,
    val lastSyncTime: Instant?
)
