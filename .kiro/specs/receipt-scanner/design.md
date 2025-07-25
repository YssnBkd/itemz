# Design Document

## Overview

The Receipt Scanner system is a Kotlin Multiplatform Mobile (KMP) application that combines bank account integration with intelligent receipt scanning to transform grocery expense tracking. The system consists of three core subsystems: secure bank connectivity via Plaid API, advanced receipt digitization using OCR services, and intelligent transaction matching algorithms. The architecture prioritizes performance (3-second OCR processing), accuracy (90%+ item recognition), and user experience through real-time camera guidance and engaging processing feedback.

## Architecture

### Project Foundation

The system builds upon the [Kotlin KMP App Template Native](https://github.com/kotlin/KMP-App-Template-Native) which provides:
- Pre-configured KMP project structure with Gradle version catalogs
- Native UI implementations (Compose Multiplatform + SwiftUI)
- Shared business logic foundation
- Platform-specific dependency injection setup
- Testing infrastructure for both platforms

### High-Level Architecture

The system follows Clean Architecture principles with KMP for shared business logic and native UI implementations:

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │   Android (Compose) │    │      iOS (SwiftUI)          │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│                 Shared Business Logic (KMP)                 │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                  Domain Layer                           │ │
│  │  • Use Cases  • Models  • Repository Interfaces        │ │
│  └─────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                   Data Layer                            │ │
│  │  • Repositories  • Data Sources  • Database            │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│                  Platform-Specific                          │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │  Android Platform   │    │     iOS Platform            │ │
│  │  • Camera API       │    │  • Camera API               │ │
│  │  • SQLite/Room      │    │  • SQLite/Core Data         │ │
│  │  • Biometric Auth   │    │  • Biometric Auth           │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### External Services Integration

```
┌─────────────────────────────────────────────────────────────┐
│                    External Services                        │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │   Plaid Banking API │    │   OCR Service               │ │
│  │   • Authentication  │    │   • Google Vision API       │ │
│  │   • Transaction Sync│    │   • AWS Textract (fallback)│ │
│  │   • Account Info    │    │   • Image Processing        │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### Core Domain Models

```kotlin
// Shared domain models
data class Receipt(
    val id: String,
    val storeInfo: StoreInfo,
    val items: List<ReceiptItem>,
    val totals: ReceiptTotals,
    val timestamp: Instant,
    val imageUrl: String?,
    val processingStatus: ProcessingStatus
)

data class ReceiptItem(
    val name: String,
    val normalizedName: String,
    val price: Double,
    val quantity: Int,
    val category: ItemCategory,
    val confidence: Float
)

data class BankTransaction(
    val id: String,
    val merchantName: String,
    val amount: Double,
    val date: Instant,
    val accountId: String,
    val isGroceryRelated: Boolean,
    val matchedReceiptId: String? = null
)

data class StoreInfo(
    val name: String,
    val address: String?,
    val chainId: String?
)

enum class ItemCategory {
    PRODUCE, DAIRY, MEAT, PACKAGED_GOODS,
    HOUSEHOLD, BEVERAGES, SNACKS, OTHER
}
```

### Repository Interfaces

```kotlin
interface BankRepository {
    suspend fun connectAccount(publicToken: String): Result<BankAccount>
    suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>>
    suspend fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction>
    suspend fun disconnectAccount(accountId: String): Result<Unit>
}

interface ReceiptRepository {
    suspend fun saveReceipt(receipt: Receipt): Result<String>
    suspend fun getReceipts(limit: Int, offset: Int): Result<List<Receipt>>
    suspend fun getReceiptById(id: String): Result<Receipt?>
    suspend fun searchReceipts(query: String): Result<List<Receipt>>
    suspend fun deleteReceipt(id: String): Result<Unit>
}

interface OCRService {
    suspend fun processReceiptImage(imageBytes: ByteArray): Result<OCRResult>
    suspend fun preprocessImage(imageBytes: ByteArray): Result<PreprocessedImage>
    suspend fun validateOCRResult(result: OCRResult): ValidationResult
}

interface ImagePreprocessor {
    suspend fun cropReceipt(imageBytes: ByteArray): Result<ByteArray>
    suspend fun deskewImage(imageBytes: ByteArray): Result<ByteArray>
    suspend fun binarizeImage(imageBytes: ByteArray): Result<ByteArray>
    suspend fun enhanceContrast(imageBytes: ByteArray): Result<ByteArray>
}

interface OCRValidator {
    fun validateCurrency(amount: String): Boolean
    fun validateTotalVsSum(items: List<ReceiptItem>, total: Double): Boolean
    fun validateItemStructure(items: List<ReceiptItem>): List<ValidationIssue>
    fun validateMerchantName(merchantName: String): Boolean
}

interface TransactionMatcher {
    suspend fun findMatches(receipt: Receipt, transactions: List<BankTransaction>): List<MatchCandidate>
    suspend fun confirmMatch(receiptId: String, transactionId: String): Result<Unit>
}
```

### OCR Technology Stack (2025)

#### Primary OCR Engine: PaddleOCR PP-OCRv4+ Mobile

Based on current open-source OCR research, PaddleOCR PP-OCRv4+ provides:
- **13-point accuracy gain** over previous versions
- **Mobile-optimized deployment** via LiteRT (TensorFlow Lite)
- **80+ language support** with receipt-specific optimizations
- **Lightweight models** (15-25MB) suitable for mobile deployment

#### Secondary Engine: EasyOCR Mobile

EasyOCR serves as fallback for challenging receipts:
- **Excellent performance** on noisy/crumpled receipts
- **Organized text recognition** optimized for structured documents
- **Lightweight deployment** compatible with mobile constraints

#### Mobile Deployment: LiteRT Framework

LiteRT addresses key mobile ML constraints:
- **Latency**: On-device processing <3 seconds
- **Privacy**: No data leaves the device
- **Size**: Optimized model compression
- **Power**: Efficient battery usage

### OCR Implementation Strategy

#### Image Preprocessing Pipeline

```kotlin
class ImagePreprocessorImpl : ImagePreprocessor {
    override suspend fun cropReceipt(imageBytes: ByteArray): Result<ByteArray> = withContext(Dispatchers.Default) {
        // Use OpenCV or platform-specific APIs for receipt detection and cropping
        // Detect receipt boundaries using edge detection and contour analysis
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val croppedBitmap = detectAndCropReceipt(bitmap)
            croppedBitmap.toByteArray()
        }
    }

    override suspend fun deskewImage(imageBytes: ByteArray): Result<ByteArray> = withContext(Dispatchers.Default) {
        // Correct image rotation and perspective distortion
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val deskewedBitmap = correctPerspective(bitmap)
            deskewedBitmap.toByteArray()
        }
    }

    override suspend fun binarizeImage(imageBytes: ByteArray): Result<ByteArray> = withContext(Dispatchers.Default) {
        // Convert to black and white for better OCR accuracy
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val binarizedBitmap = applyAdaptiveThreshold(bitmap)
            binarizedBitmap.toByteArray()
        }
    }
}
```

#### OCR Service with Caching

```kotlin
class OCRServiceImpl(
    private val googleVisionClient: VisionClient,
    private val awsTextractClient: TextractClient,
    private val ocrCache: OCRCache
) : OCRService {

    override suspend fun processReceiptImage(imageBytes: ByteArray): Result<OCRResult> = withContext(Dispatchers.Default) {
        val imageHash = imageBytes.sha256()

        // Check cache first
        ocrCache.get(imageHash)?.let { cachedResult ->
            return@withContext Result.success(cachedResult)
        }

        runCatching {
            // Primary OCR with Google Vision
            val primaryResult = googleVisionClient.processImage(imageBytes)

            if (primaryResult.confidence < 0.8) {
                // Fallback to AWS Textract for low confidence results
                val fallbackResult = awsTextractClient.processImage(imageBytes)
                if (fallbackResult.confidence > primaryResult.confidence) {
                    ocrCache.put(imageHash, fallbackResult)
                    fallbackResult
                } else {
                    ocrCache.put(imageHash, primaryResult)
                    primaryResult
                }
            } else {
                ocrCache.put(imageHash, primaryResult)
                primaryResult
            }
        }
    }
}
```

#### OCR Validation with Heuristics

```kotlin
class OCRValidatorImpl : OCRValidator {
    override fun validateCurrency(amount: String): Boolean {
        val currencyRegex = Regex("""^\$?\d+\.\d{2}$""")
        return currencyRegex.matches(amount.trim())
    }

    override fun validateTotalVsSum(items: List<ReceiptItem>, total: Double): Boolean {
        val calculatedSum = items.sumOf { it.price * it.quantity }
        val tolerance = 0.02 // Allow 2 cent tolerance for rounding
        return abs(calculatedSum - total) <= tolerance
    }

    override fun validateItemStructure(items: List<ReceiptItem>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        items.forEach { item ->
            if (item.name.isBlank()) {
                issues.add(ValidationIssue.EmptyItemName(item))
            }
            if (item.price <= 0) {
                issues.add(ValidationIssue.InvalidPrice(item))
            }
            if (item.quantity <= 0) {
                issues.add(ValidationIssue.InvalidQuantity(item))
            }
        }

        return issues
    }
}
```

### Use Cases

```kotlin
class ScanReceiptUseCase(
    private val ocrService: OCRService,
    private val receiptRepository: ReceiptRepository,
    private val itemCategorizer: ItemCategorizer
) {
    suspend fun execute(imageBytes: ByteArray): Flow<ScanProgress> = flow {
        emit(ScanProgress.Processing("Reading receipt..."))

        val ocrResult = ocrService.processReceiptImage(imageBytes)
            .getOrThrow()

        emit(ScanProgress.Processing("Extracting items..."))

        val categorizedItems = itemCategorizer.categorizeItems(ocrResult.items)

        emit(ScanProgress.Processing("Organizing data..."))

        val receipt = Receipt(
            id = generateId(),
            storeInfo = ocrResult.storeInfo,
            items = categorizedItems,
            totals = ocrResult.totals,
            timestamp = Clock.System.now(),
            imageUrl = null,
            processingStatus = ProcessingStatus.COMPLETED
        )

        receiptRepository.saveReceipt(receipt)

        emit(ScanProgress.Completed(receipt))
    }
}

class MatchTransactionUseCase(
    private val transactionMatcher: TransactionMatcher,
    private val bankRepository: BankRepository
) {
    suspend fun execute(receipt: Receipt): Result<List<MatchCandidate>> {
        val recentTransactions = bankRepository.fetchTransactions(accountId)
            .getOrThrow()
            .filter { it.isGroceryRelated && it.matchedReceiptId == null }

        return transactionMatcher.findMatches(receipt, recentTransactions)
    }
}
```

## Data Models

### Database Schema (SQLite)

```sql
-- Bank accounts table
CREATE TABLE bank_accounts (
    id TEXT PRIMARY KEY,
    institution_name TEXT NOT NULL,
    account_name TEXT NOT NULL,
    account_type TEXT NOT NULL,
    last_four TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Bank transactions table
CREATE TABLE bank_transactions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL,
    merchant_name TEXT NOT NULL,
    amount REAL NOT NULL,
    transaction_date INTEGER NOT NULL,
    is_grocery_related BOOLEAN DEFAULT FALSE,
    matched_receipt_id TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (account_id) REFERENCES bank_accounts(id),
    FOREIGN KEY (matched_receipt_id) REFERENCES receipts(id)
);

-- Receipts table
CREATE TABLE receipts (
    id TEXT PRIMARY KEY,
    store_name TEXT NOT NULL,
    store_address TEXT,
    store_chain_id TEXT,
    subtotal REAL NOT NULL,
    tax_amount REAL NOT NULL,
    total_amount REAL NOT NULL,
    receipt_date INTEGER NOT NULL,
    image_path TEXT,
    processing_status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Receipt items table
CREATE TABLE receipt_items (
    id TEXT PRIMARY KEY,
    receipt_id TEXT NOT NULL,
    item_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    price REAL NOT NULL,
    quantity INTEGER NOT NULL,
    category TEXT NOT NULL,
    confidence REAL NOT NULL,
    line_number INTEGER NOT NULL,
    FOREIGN KEY (receipt_id) REFERENCES receipts(id)
);

-- Transaction matches table
CREATE TABLE transaction_matches (
    id TEXT PRIMARY KEY,
    receipt_id TEXT NOT NULL,
    transaction_id TEXT NOT NULL,
    match_confidence REAL NOT NULL,
    is_confirmed BOOLEAN DEFAULT FALSE,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (receipt_id) REFERENCES receipts(id),
    FOREIGN KEY (transaction_id) REFERENCES bank_transactions(id)
);

-- Indexes for performance
CREATE INDEX idx_transactions_date ON bank_transactions(transaction_date);
CREATE INDEX idx_transactions_grocery ON bank_transactions(is_grocery_related);
CREATE INDEX idx_receipts_date ON receipts(receipt_date);
CREATE INDEX idx_receipt_items_receipt ON receipt_items(receipt_id);
```

### Platform-Specific Camera Implementation

```kotlin
// Android Camera Implementation
expect class CameraController {
    fun startCamera(previewView: PreviewView)
    fun captureImage(): Flow<CaptureResult>
    fun enableReceiptDetection(enabled: Boolean)
    fun setQualityCallback(callback: (ImageQuality) -> Unit)
}

// iOS Camera Implementation (Swift interop)
actual class CameraController {
    private val avCaptureSession = AVCaptureSession()
    private val receiptDetector = ReceiptDetector()

    actual fun startCamera(previewView: PreviewView) {
        // AVFoundation implementation
    }

    actual fun captureImage(): Flow<CaptureResult> = flow {
        // Capture and process image
    }
}
```

### Manual Correction UI Components

The system provides an intuitive interface for users to review and correct OCR results:

```kotlin
// Shared UI state for manual correction
data class ManualCorrectionState(
    val receipt: Receipt,
    val editableItems: List<EditableReceiptItem>,
    val totalCalculation: TotalCalculation,
    val validationErrors: List<ValidationIssue>
)

data class EditableReceiptItem(
    val id: String,
    val name: String,
    val price: String, // String for editing
    val quantity: String, // String for editing
    val category: ItemCategory,
    val hasError: Boolean,
    val errorMessage: String?
)

// Use case for manual correction
class ManualCorrectionUseCase(
    private val receiptRepository: ReceiptRepository,
    private val ocrValidator: OCRValidator
) {
    suspend fun updateItem(receiptId: String, itemId: String, updates: ItemUpdates): Result<Receipt> {
        val receipt = receiptRepository.getReceiptById(receiptId).getOrThrow()
        val updatedItems = receipt.items.map { item ->
            if (item.id == itemId) {
                item.copy(
                    name = updates.name ?: item.name,
                    price = updates.price ?: item.price,
                    quantity = updates.quantity ?: item.quantity,
                    category = updates.category ?: item.category
                )
            } else item
        }

        val updatedReceipt = receipt.copy(items = updatedItems)
        val validationResult = ocrValidator.validateOCRResult(updatedReceipt.toOCRResult())

        val finalReceipt = updatedReceipt.copy(
            processingStatus = if (validationResult.hasIssues) {
                ProcessingStatus.REQUIRES_REVIEW
            } else ProcessingStatus.COMPLETED
        )

        return receiptRepository.saveReceipt(finalReceipt)
    }
}
```

### Performance Optimizations

#### Threaded Processing Architecture

```kotlin
class OptimizedScanReceiptUseCase(
    private val imagePreprocessor: ImagePreprocessor,
    private val ocrService: OCRService,
    private val ocrValidator: OCRValidator,
    private val receiptRepository: ReceiptRepository,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun execute(imageBytes: ByteArray): Flow<ScanProgress> = flow {
        emit(ScanProgress.Processing("Preparing image..."))

        // All heavy processing on background threads to avoid blocking UI
        val preprocessedImage = withContext(backgroundDispatcher) {
            imagePreprocessor.cropReceipt(imageBytes)
                .flatMap { imagePreprocessor.deskewImage(it) }
                .flatMap { imagePreprocessor.binarizeImage(it) }
                .flatMap { imagePreprocessor.enhanceContrast(it) }
        }.getOrThrow()

        emit(ScanProgress.Processing("Reading receipt..."))

        // OCR processing with caching
        val ocrResult = withContext(backgroundDispatcher) {
            ocrService.processReceiptImage(preprocessedImage)
        }.getOrThrow()

        emit(ScanProgress.Processing("Validating data..."))

        // Validation in background
        val validationResult = withContext(backgroundDispatcher) {
            ocrValidator.validateOCRResult(ocrResult)
        }

        // Continue with categorization and saving...
        emit(ScanProgress.Completed(receipt))
    }
}
```

#### OCR Model Caching Strategy

```kotlin
class OCRModelCache {
    private val modelCache = LRUCache<String, OCRModel>(maxSize = 3)
    private val resultCache = LRUCache<String, OCRResult>(maxSize = 50)

    suspend fun getOrLoadModel(modelType: OCRModelType): OCRModel {
        return modelCache.get(modelType.name) ?: run {
            val model = loadModel(modelType)
            modelCache.put(modelType.name, model)
            model
        }
    }

    fun cacheResult(imageHash: String, result: OCRResult) {
        resultCache.put(imageHash, result)
    }

    fun getCachedResult(imageHash: String): OCRResult? {
        return resultCache.get(imageHash)
    }
}
```

## Error Handling

### Error Types and Recovery Strategies

```kotlin
sealed class AppError : Exception() {
    data class NetworkError(val cause: Throwable) : AppError()
    data class OCRError(val message: String, val retryable: Boolean) : AppError()
    data class CameraError(val type: CameraErrorType) : AppError()
    data class BankConnectionError(val code: String, val message: String) : AppError()
    data class DatabaseError(val cause: Throwable) : AppError()
}

class ErrorHandler {
    fun handleError(error: AppError): ErrorAction {
        return when (error) {
            is AppError.NetworkError -> ErrorAction.Retry(maxAttempts = 3)
            is AppError.OCRError -> if (error.retryable) {
                ErrorAction.Retry(maxAttempts = 2)
            } else {
                ErrorAction.FallbackToManualEntry
            }
            is AppError.CameraError -> ErrorAction.ShowPermissionDialog
            is AppError.BankConnectionError -> ErrorAction.ReauthenticateBank
            is AppError.DatabaseError -> ErrorAction.ShowGenericError
        }
    }
}
```

### Offline Handling Strategy

```kotlin
class OfflineManager {
    private val pendingScans = mutableListOf<PendingScan>()

    suspend fun queueReceiptForProcessing(imageBytes: ByteArray): String {
        val scanId = generateId()
        val pendingScan = PendingScan(
            id = scanId,
            imageBytes = imageBytes,
            timestamp = Clock.System.now()
        )

        // Store locally encrypted
        localStorage.storePendingScan(pendingScan)
        pendingScans.add(pendingScan)

        return scanId
    }

    suspend fun processPendingScans() {
        if (!networkMonitor.isOnline()) return

        pendingScans.forEach { scan ->
            try {
                val result = ocrService.processReceiptImage(scan.imageBytes)
                // Process and save result
                localStorage.removePendingScan(scan.id)
            } catch (e: Exception) {
                // Keep in queue for next attempt
            }
        }
    }
}
```

## Testing Strategy

### Unit Testing Approach

```kotlin
// Domain layer testing
class ScanReceiptUseCaseTest {
    @Test
    fun `should process receipt successfully`() = runTest {
        // Given
        val mockOCRService = mockk<OCRService>()
        val mockRepository = mockk<ReceiptRepository>()
        val useCase = ScanReceiptUseCase(mockOCRService, mockRepository, mockk())

        every { mockOCRService.processReceiptImage(any()) } returns
            Result.success(mockOCRResult)

        // When
        val result = useCase.execute(mockImageBytes).toList()

        // Then
        assertThat(result.last()).isInstanceOf<ScanProgress.Completed>()
    }
}

// Repository testing with fake implementations
class FakeBankRepository : BankRepository {
    private val transactions = mutableListOf<BankTransaction>()

    override suspend fun fetchTransactions(accountId: String): Result<List<BankTransaction>> {
        return Result.success(transactions.filter { it.accountId == accountId })
    }
}
```

### Integration Testing

```kotlin
// End-to-end receipt scanning test
@Test
fun `complete receipt scanning flow`() = runTest {
    // Test the full flow from image capture to data storage
    val testImage = loadTestReceiptImage("kroger_receipt.jpg")

    val scanResult = scanReceiptUseCase.execute(testImage).last()

    assertThat(scanResult).isInstanceOf<ScanProgress.Completed>()
    val receipt = (scanResult as ScanProgress.Completed).receipt

    assertThat(receipt.items).hasSize(greaterThan(5))
    assertThat(receipt.totals.total).isGreaterThan(0.0)
}
```

### Performance Testing

```kotlin
@Test
fun `OCR processing completes within 3 seconds`() = runTest {
    val startTime = TimeSource.Monotonic.markNow()

    val result = ocrService.processReceiptImage(testImageBytes)

    val duration = startTime.elapsedNow()
    assertThat(duration).isLessThan(3.seconds)
    assertThat(result.isSuccess).isTrue()
}
```

### UI Testing Strategy

- **Android**: Espresso tests for Compose UI components
- **iOS**: XCUITest for SwiftUI components
- **Shared**: Screenshot testing for consistent UI across platforms
- **Camera Testing**: Mock camera inputs with test receipt images
- **Performance**: Measure UI responsiveness during OCR processing

The testing strategy ensures reliability across the critical user flows: bank connection, receipt scanning, and transaction matching, while maintaining the performance requirements for a delightful user experience.
