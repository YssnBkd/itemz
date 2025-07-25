package com.jetbrains.receiptscanner.data.database

import app.cash.sqldelight.db.SqlDriver

object DatabaseMigrations {

    const val CURRENT_VERSION = 1

    fun migrate(driver: SqlDriver, oldVersion: Int, newVersion: Int) {
        // Future migrations will be added here
        // For now, SQLDelight handles the initial schema creation
    }
}
