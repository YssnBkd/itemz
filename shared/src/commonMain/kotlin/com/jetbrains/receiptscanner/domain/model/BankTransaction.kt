package com.jetbrains.receiptscanner.domain.model

import kotlinx.datetime.Instant

data class BankTransaction(
    val id: String,
    val merchantName: String,
    val amount: Double,
    val date: Instant,
    val accountId: String,
    val isGroceryRelated: Boolean,
    val matchedReceiptId: String? = null
)

data class BankAccount(
    val id: String,
    val institutionName: String,
    val accountName: String,
    val accountType: String,
    val lastFour: String,
    val isActive: Boolean
)
