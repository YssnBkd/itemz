package com.jetbrains.receiptscanner.presentation

import com.jetbrains.receiptscanner.domain.usecase.ParsedReceipt
import com.jetbrains.receiptscanner.domain.usecase.ScanProgress
import com.jetbrains.receiptscanner.domain.usecase.ScanReceiptUseCase
import com.rickclephas.kmp.observableviewmodel.ViewModel
import com.rickclephas.kmp.observableviewmodel.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReceiptScannerViewModel(
    private val scanReceiptUseCase: ScanReceiptUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptScannerUiState())
    val uiState: StateFlow<ReceiptScannerUiState> = _uiState.asStateFlow()

    fun scanReceipt(imageBytes: ByteArray) {
        viewModelScope.coroutineScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, scanProgress = null)

            scanReceiptUseCase.execute(imageBytes).collect { progress ->
                when (progress) {
                    is ScanProgress.Initializing -> {
                        _uiState.value = _uiState.value.copy(scanProgress = "Initializing...")
                    }
                    is ScanProgress.Processing -> {
                        _uiState.value = _uiState.value.copy(scanProgress = progress.message)
                    }
                    is ScanProgress.Completed -> {
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            scanProgress = null,
                            scannedReceipt = progress.receipt
                        )
                    }
                    is ScanProgress.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            scanProgress = null,
                            error = progress.message
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class ReceiptScannerUiState(
    val isScanning: Boolean = false,
    val scanProgress: String? = null,
    val scannedReceipt: ParsedReceipt? = null,
    val error: String? = null
)
