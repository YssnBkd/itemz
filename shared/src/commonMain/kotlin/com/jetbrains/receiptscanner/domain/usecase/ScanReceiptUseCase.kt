package com.jetbrains.receiptscanner.domain.usecase

import com.jetbrains.receiptscanner.domain.model.Receipt
import com.jetbrains.receiptscanner.domain.repository.ReceiptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ScanReceiptUseCase(
    private val receiptRepository: ReceiptRepository
) {
    suspend fun execute(imageBytes: ByteArray): Flow<ScanProgress> = flow {
        emit(ScanProgress.Processing("Reading receipt..."))

        // TODO: Implement OCR processing in future tasks
        // For now, this is a placeholder structure

        emit(ScanProgress.Processing("Extracting items..."))

        // TODO: Process image and extract receipt data

        emit(ScanProgress.Processing("Organizing data..."))

        // TODO: Save receipt to repository

        emit(ScanProgress.Completed(null)) // Placeholder
    }
}

sealed class ScanProgress {
    data class Processing(val message: String) : ScanProgress()
    data class Completed(val receipt: Receipt?) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}
