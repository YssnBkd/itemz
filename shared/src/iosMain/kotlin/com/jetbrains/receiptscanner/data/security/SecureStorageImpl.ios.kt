package com.jetbrains.receiptscanner.data.security

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.posix.memcpy

/**
 * iOS implementation of SecureStorage using Keychain Services
 * This provides secure storage backed by the iOS Keychain
 */
class SecureStorageImpl : SecureStorage {

    private val serviceName = "com.jetbrains.receiptscanner.secure"

    override suspend fun store(key: String, value: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val data = value.encodeToByteArray().toNSData()

            // First try to update existing item
            val updateQuery = mutableMapOf<Any?, Any?>().apply {
                put(kSecClass, kSecClassGenericPassword)
                put(kSecAttrService, serviceName)
                put(kSecAttrAccount, key)
            }

            val updateAttributes = mutableMapOf<Any?, Any?>().apply {
                put(kSecValueData, data)
            }

            val updateStatus = SecItemUpdate(updateQuery as CFDictionaryRef, updateAttributes as CFDictionaryRef)

            if (updateStatus == errSecItemNotFound) {
                // Item doesn't exist, create new one
                val addQuery = mutableMapOf<Any?, Any?>().apply {
                    put(kSecClass, kSecClassGenericPassword)
                    put(kSecAttrService, serviceName)
                    put(kSecAttrAccount, key)
                    put(kSecValueData, data)
                    put(kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                }

                val addStatus = SecItemAdd(addQuery as CFDictionaryRef, null)
                if (addStatus != errSecSuccess) {
                    throw Exception("Failed to store item in keychain: $addStatus")
                }
            } else if (updateStatus != errSecSuccess) {
                throw Exception("Failed to update item in keychain: $updateStatus")
            }
        }
    }

    override suspend fun retrieve(key: String): Result<String?> = withContext(Dispatchers.Default) {
        runCatching {
            val query = mutableMapOf<Any?, Any?>().apply {
                put(kSecClass, kSecClassGenericPassword)
                put(kSecAttrService, serviceName)
                put(kSecAttrAccount, key)
                put(kSecReturnData, kCFBooleanTrue)
                put(kSecMatchLimit, kSecMatchLimitOne)
            }

            val result = memScoped {
                val resultPtr = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query as CFDictionaryRef, resultPtr.ptr)

                when (status) {
                    errSecSuccess -> {
                        val data = resultPtr.value as NSData
                        data.toByteArray().decodeToString()
                    }
                    errSecItemNotFound -> null
                    else -> throw Exception("Failed to retrieve item from keychain: $status")
                }
            }

            result
        }
    }

    override suspend fun remove(key: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val query = mutableMapOf<Any?, Any?>().apply {
                put(kSecClass, kSecClassGenericPassword)
                put(kSecAttrService, serviceName)
                put(kSecAttrAccount, key)
            }

            val status = SecItemDelete(query as CFDictionaryRef)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw Exception("Failed to remove item from keychain: $status")
            }
        }
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.Default) {
        val query = mutableMapOf<Any?, Any?>().apply {
            put(kSecClass, kSecClassGenericPassword)
            put(kSecAttrService, serviceName)
            put(kSecAttrAccount, key)
            put(kSecReturnData, kCFBooleanFalse)
        }

        val status = SecItemCopyMatching(query as CFDictionaryRef, null)
        status == errSecSuccess
    }

    override suspend fun clear(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val query = mutableMapOf<Any?, Any?>().apply {
                put(kSecClass, kSecClassGenericPassword)
                put(kSecAttrService, serviceName)
            }

            val status = SecItemDelete(query as CFDictionaryRef)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw Exception("Failed to clear keychain: $status")
            }
        }
    }
}

// Extension function to convert NSData to ByteArray
private fun NSData.toByteArray(): ByteArray {
    return ByteArray(length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), bytes, length)
        }
    }
}

// Extension function to convert ByteArray to NSData
private fun ByteArray.toNSData(): NSData {
    return memScoped {
        NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
    }
}
