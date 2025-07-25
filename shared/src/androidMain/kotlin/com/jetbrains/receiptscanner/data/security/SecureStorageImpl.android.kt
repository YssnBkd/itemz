package com.jetbrains.receiptscanner.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of SecureStorage using EncryptedSharedPreferences
 * This provides hardware-backed encryption when available
 */
class SecureStorageImpl(private val context: Context) : SecureStorage {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "receipt_scanner_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun store(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sharedPreferences.edit()
                .putString(key, value)
                .apply()
        }
    }

    override suspend fun retrieve(key: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            sharedPreferences.getString(key, null)
        }
    }

    override suspend fun remove(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sharedPreferences.edit()
                .remove(key)
                .apply()
        }
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        sharedPreferences.contains(key)
    }

    override suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sharedPreferences.edit()
                .clear()
                .apply()
        }
    }
}
