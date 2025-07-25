package com.jetbrains.receiptscanner.data.database

import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase
import com.jetbrains.receiptscanner.data.platform.DatabaseDriverFactory

class DatabaseFactory(private val driverFactory: DatabaseDriverFactory) {

    fun createDatabase(): ReceiptScannerDatabase {
        val driver = driverFactory.createDriver()
        return ReceiptScannerDatabase(driver)
    }
}
