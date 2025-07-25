package com.jetbrains.receiptscanner.di

import com.jetbrains.receiptscanner.data.platform.DatabaseDriverFactory
import org.koin.dsl.module

actual val platformModule = module {
    single { DatabaseDriverFactory(get()) }
}
