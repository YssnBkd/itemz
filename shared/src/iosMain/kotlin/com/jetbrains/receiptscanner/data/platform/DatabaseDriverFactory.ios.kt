package com.jetbrains.receiptscanner.data.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = ReceiptScannerDatabase.Schema,
            name = "receipt_scanner.db"
        )
    }
}
