package com.jetbrains.receiptscanner.data.security

/**
 * Interface for secure storage of sensitive data like bank tokens
 * Platform-specific implementations will use Android Keystore and iOS Keychain
 */
interface SecureStorage {
    /**
     * Store encrypted data with a key
     */
    suspend fun store(key: String, value: String): Result<Unit>

    /**
     * Retrieve and decrypt data by key
     */
    suspend fun retrieve(key: String): Result<String?>

    /**
     * Remove data by key
     */
    suspend fun remove(key: String): Result<Unit>

    /**
     * Check if key exists
     */
    suspend fun contains(key: String): Boolean

    /**
     * Clear all stored data
     */
    suspend fun clear(): Result<Unit>
}

/**
 * Keys for storing different types of secure data
 */
object SecureStorageKeys {
    const val PLAID_ACCESS_TOKEN_PREFIX = "plaid_access_token_"
    const val PLAID_ITEM_ID_PREFIX = "plaid_item_id_"

    fun accessTokenKey(accountId: String) = "$PLAID_ACCESS_TOKEN_PREFIX$accountId"
    fun itemIdKey(accountId: String) = "$PLAID_ITEM_ID_PREFIX$accountId"
}
