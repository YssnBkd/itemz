package com.jetbrains.receiptscanner.data.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = ReceiptScannerDatabase.Schema,
            context = context,
            name = "receipt_scanner.db"
        )
    }
}
