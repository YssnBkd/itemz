package com.jetbrains.receiptscanner.di

import com.jetbrains.receiptscanner.data.database.DatabaseFactory
import com.jetbrains.receiptscanner.data.datasource.BankDataSource
import com.jetbrains.receiptscanner.data.datasource.BankDataSourceImpl
import com.jetbrains.receiptscanner.data.datasource.ReceiptDataSource
import com.jetbrains.receiptscanner.data.datasource.ReceiptDataSourceImpl
import com.jetbrains.receiptscanner.data.repository.BankRepositoryImpl
import com.jetbrains.receiptscanner.data.repository.ReceiptRepositoryImpl
import com.jetbrains.receiptscanner.data.service.TransactionCategorizationService
import com.jetbrains.receiptscanner.data.service.TransactionSyncService
import com.jetbrains.receiptscanner.database.ReceiptScannerDatabase
import com.jetbrains.receiptscanner.domain.repository.BankRepository
import com.jetbrains.receiptscanner.domain.repository.ReceiptRepository
import com.jetbrains.receiptscanner.domain.usecase.ScanReceiptUseCase
import com.jetbrains.receiptscanner.presentation.ReceiptScannerViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val receiptScannerModule = module {
    // Database
    singleOf(::DatabaseFactory)
    single<ReceiptScannerDatabase> { get<DatabaseFactory>().createDatabase() }

    // Services
    singleOf(::TransactionCategorizationService)
    single { TransactionSyncService(bankDataSource = get()) }

    // Data Sources
    singleOf(::ReceiptDataSourceImpl) bind ReceiptDataSource::class
    single<BankDataSource> {
        BankDataSourceImpl(
            database = get(),
            plaidService = get(),
            secureStorage = get(),
            categorizationService = get()
        )
    }

    // Repositories
    singleOf(::ReceiptRepositoryImpl) bind ReceiptRepository::class
    singleOf(::BankRepositoryImpl) bind BankRepository::class

    // Use Cases
    factoryOf(::ScanReceiptUseCase)

    // ViewModels
    factoryOf(::ReceiptScannerViewModel)
}
