# Claude Code Development Specification - Itemz KMP App

## Project Overview
You are tasked with developing an **MVP** cross-platform mobile application using **Kotlin Multiplatform (KMP)** for Android and iOS. This is a grocery expense tracking app called "Itemz."

**MVP Focus**: Build an outstanding receipt scanning experience with world-class accuracy. Everything else is secondary to nailing the scanner.

## Core Application Logic
**Primary Function**: Help users understand their grocery spending by scanning receipts and matching them to bank transactions.

**Key User Flow**:
1. User connects bank account → App identifies grocery transactions
2. User sees `"KROGER - $147.31"` in their feed
3. User taps `[Scan Receipt]` → Camera opens
4. User photographs receipt → OCR processes it in <3 seconds
5. App matches receipt items to bank transaction
6. User sees detailed breakdown: 23 items categorized (produce, dairy, etc.)
7. App shows insights: spending patterns, trends, comparisons

## Technical Architecture Requirements

### Project Structure (KMP)
```
itemz-kmp/
├── shared/
│   ├── src/commonMain/kotlin/
│   │   ├── data/
│   │   │   ├── database/
│   │   │   ├── network/
│   │   │   └── repository/
│   │   ├── domain/
│   │   │   ├── models/
│   │   │   ├── usecases/
│   │   │   └── repository/
│   │   └── presentation/
│   │       └── viewmodels/
│   ├── src/androidMain/kotlin/
│   └── src/iosMain/kotlin/
├── androidApp/
│   └── src/main/kotlin/ (Jetpack Compose UI)
└── iosApp/
    └── iosApp/ (SwiftUI UI)
```

### Core Data Models
```kotlin
// Shared data models you need to implement
data class BankTransaction(
    val id: String,
    val merchantName: String,
    val amount: Double,
    val date: LocalDateTime,
    val isGroceryRelated: Boolean,
    val receiptId: String? = null
)

data class Receipt(
    val id: String,
    val transactionId: String,
    val items: List<ReceiptItem>,
    val total: Double,
    val tax: Double,
    val storeName: String,
    val date: LocalDateTime
)

data class ReceiptItem(
    val name: String,
    val price: Double,
    val quantity: Int,
    val category: GroceryCategory,
    val normalizedName: String
)

data class Merchant(
    val id: String,
    val name: String,
    val address: String,
    val chainId: String? = null, // For linking to parent chain (e.g., "kroger-chain")
    val category: String = "GROCERY",
    val coordinates: Pair<Double, Double>? = null
)
    PRODUCE, DAIRY, MEAT, PACKAGED_GOODS, HOUSEHOLD, BEVERAGES, SNACKS
}
```

### Critical Performance Requirements
- **Receipt OCR processing**: Must complete in <3 seconds
- **OCR accuracy**: >95% correct item recognition (TOP PRIORITY)
- **Camera experience**: Real-time receipt detection and guided capture
- **Processing feedback**: Engaging animations during OCR processing
- **App launch time**: <2 seconds cold start
- **Database queries**: <100ms for most operations

## Required Features Implementation

### 1. Bank Integration Module
**API**: Use Plaid SDK for bank connections
**Functionality**:
- Secure authentication with major banks
- Real-time transaction fetching
- ML-based grocery merchant detection (keywords: "KROGER", "WALMART", "SAFEWAY", etc.)
- Filter and display only grocery transactions

**Key Methods to Implement**:
```kotlin
interface BankRepository {
    suspend fun connectAccount(publicToken: String): Result<Account>
    suspend fun fetchTransactions(): Result<List<BankTransaction>>
    suspend fun identifyGroceryTransactions(transactions: List<BankTransaction>): List<BankTransaction>
}
```

### 2. Receipt Scanner Module (TOP PRIORITY - MVP CORE)
**OCR**: Integrate Google Vision API or AWS Textract
**Camera**: Platform-specific camera implementations with magical UX

**Camera Experience That Feels Alive**:
- **Instant receipt detection**: Rectangle overlay snaps to receipt edges automatically
- **Real-time feedback**: "Move closer," "Good lighting," "Perfect! Hold steady..."
- **Auto-capture**: No shutter button - automatically captures when receipt is perfectly framed
- **Visual feedback**: Subtle haptic buzz when receipt is detected, satisfying "click" sound when captured
- **Guided framing**: Animated guides show optimal receipt positioning
- **Quality indicators**: Real-time image quality meter (lighting, focus, angle)

**Processing That Builds Anticipation**:
Instead of boring loading spinner, implement:
- **Item-by-item reveal**: "Found bananas... Found milk... Found bread..." (like slot machine)
- **Smart predictions**: "Looks like a big shopping trip! Processing 20+ items..."
- **Progress storytelling**: "Reading receipt... Organizing items... Almost done..."
- **Animated processing**: Visual elements that build excitement during OCR

**Key Components**:
- Camera capture with receipt detection
- Image preprocessing (crop, enhance, rotate)
- OCR text extraction
- Receipt parsing and item extraction
- Receipt format recognition for major chains

**Critical Implementation**:
```kotlin
interface ReceiptProcessor {
    suspend fun scanReceipt(imageBytes: ByteArray): Result<Receipt>
    suspend fun matchToTransaction(receipt: Receipt, transactions: List<BankTransaction>): BankTransaction?
    suspend fun categorizeItems(items: List<ReceiptItem>): List<ReceiptItem>
}
```

### 3. Data Insights Engine
**Purpose**: Generate spending insights from transaction/receipt data

**Required Analytics**:
- Monthly spending trends by category
- Average basket analysis
- Shopping frequency patterns
- Price tracking for frequent items
- Comparative spending analysis

**Implementation Target**:
```kotlin
interface InsightsEngine {
    suspend fun generateSpendingTrends(userId: String, period: DateRange): SpendingTrends
    suspend fun calculateCategoryBreakdown(receipts: List<Receipt>): CategoryBreakdown
    suspend fun analyzeShoppingPatterns(transactions: List<BankTransaction>): ShoppingPatterns
}
```

### 4. Database Schema (SQLite)
**Tables to Implement**:
```sql
-- Merchants table
CREATE TABLE merchants (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    address TEXT NOT NULL,
    chain_id TEXT,
    category TEXT DEFAULT 'GROCERY',
    latitude REAL,
    longitude REAL,
    created_at INTEGER
);

-- Users table
CREATE TABLE users (
    id TEXT PRIMARY KEY,
    created_at INTEGER,
    bank_connected BOOLEAN
);

-- Bank transactions
CREATE TABLE bank_transactions (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    merchant_id TEXT,
    merchant_name TEXT,
    amount REAL,
    date INTEGER,
    is_grocery BOOLEAN,
    receipt_id TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

-- Receipts
CREATE TABLE receipts (
    id TEXT PRIMARY KEY,
    transaction_id TEXT,
    merchant_id TEXT,
    store_name TEXT,
    total REAL,
    tax REAL,
    date INTEGER,
    image_path TEXT,
    FOREIGN KEY (transaction_id) REFERENCES bank_transactions(id),
    FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

-- Receipt items
CREATE TABLE receipt_items (
    id TEXT PRIMARY KEY,
    receipt_id TEXT,
    name TEXT,
    normalized_name TEXT,
    price REAL,
    quantity INTEGER,
    category TEXT,
    FOREIGN KEY (receipt_id) REFERENCES receipts(id)
);
```

### 5. User Interface Requirements

**Android (Jetpack Compose)**:
- Transaction feed with scan buttons
- Camera screen with receipt capture
- Insights dashboard with charts
- Settings and account management

**iOS (SwiftUI)**:
- Identical functionality with native iOS design patterns
- Consistent user experience across platforms

**Key Screens to Build**:
1. **Transaction Feed**: List of grocery transactions with scan prompts
2. **Camera Scanner**: Receipt capture with real-time feedback
3. **Receipt Review**: OCR results with edit capability
4. **Insights Dashboard**: Charts and spending analysis
5. **Settings**: Bank account management, preferences

## Development Priorities (In Order) - MVP Focus

### Phase 1: Receipt Scanner Foundation (HIGHEST PRIORITY)
1. Set up KMP project structure with basic modules
2. Implement core receipt scanning with OCR integration
3. Build magical camera UI with real-time receipt detection
4. Create engaging processing animations and feedback
5. Achieve >95% OCR accuracy with major grocery chains

### Phase 2: Basic Data Layer
1. Implement database schema with merchants table
2. Create basic data models and repositories
3. Add receipt storage and retrieval
4. Build simple item categorization

### Phase 3: Minimal Bank Integration
1. Basic Plaid integration for transaction fetching
2. Simple transaction-to-receipt matching
3. Grocery transaction filtering
4. Transaction feed UI

### Phase 4: Basic Insights
1. Simple spending categorization
2. Basic transaction enrichment (bank → receipt items)
3. Minimal insights dashboard
4. Core user flow completion

### Phase 5: MVP Polish
1. Error handling for scanning edge cases
2. Basic offline receipt storage
3. Simple user onboarding
4. MVP testing and bug fixes

## Technical Specifications

### Dependencies (build.gradle.kts)
```kotlin
// Shared module dependencies
commonMain {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("io.ktor:ktor-client-core:2.3.4")
    implementation("com.squareup.sqldelight:runtime:2.0.0")
    implementation("io.insert-koin:koin-core:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
}

androidMain {
    implementation("io.ktor:ktor-client-android:2.3.4")
    implementation("com.squareup.sqldelight:android-driver:2.0.0")
    implementation("com.plaid.link:sdk-core:4.1.0")
    implementation("com.google.cloud:google-cloud-vision:3.7.2")
}

iosMain {
    implementation("io.ktor:ktor-client-darwin:2.3.4")
    implementation("com.squareup.sqldelight:native-driver:2.0.0")
}
```

### Security Requirements
- All financial data encrypted at rest (AES-256)
- Network requests use certificate pinning
- Biometric authentication for app access
- No sensitive data in logs or crash reports
- PCI DSS compliance for payment data handling

### Error Handling Strategy
- Comprehensive error handling for all async operations
- User-friendly error messages
- Automatic retry logic for network failures
- Graceful degradation when services unavailable
- Detailed logging for debugging (non-sensitive data only)

## Success Criteria
**The MVP is ready when:**
1. **Receipt scanning works reliably with >95% accuracy** (PRIMARY SUCCESS METRIC)
2. **Camera experience feels magical** with real-time detection and engaging processing
3. Users can scan a receipt and see categorized items
4. Basic transaction matching works for major grocery chains
5. App doesn't crash and handles common error cases
6. Core user flow (scan → process → view results) works smoothly

## API Integrations Required

### Plaid (Banking)
- Account linking and authentication
- Transaction fetching with real-time updates
- Account balance monitoring
- Webhook handling for new transactions

### Google Vision API (OCR)
- Receipt text extraction
- Structured data parsing
- Image preprocessing optimization
- Error handling for poor image quality

### Push Notifications
- FCM for Android, APNS for iOS
- Transaction alerts and scanning reminders
- User engagement notifications

## Development Best Practices
- Follow Clean Architecture principles
- Implement proper error handling everywhere
- Write comprehensive unit tests for business logic
- Use proper logging (structured, no sensitive data)
- Implement proper state management (MVVM pattern)
- Code should be production-ready, not prototype quality
- Follow platform-specific UI guidelines
- Implement proper offline handling and data sync

## Final Notes
This is an **MVP focused on receipt scanning excellence**, not a full production app. The primary goal is to prove that you can build the world's best receipt scanning experience. Focus on:

1. **OCR accuracy above all else** - this is what will make or break the app
2. **Magical camera UX** - make scanning feel delightful, not like work
3. **Engaging processing feedback** - turn the wait time into anticipation
4. **Solid core functionality** - basic features that work reliably

Everything else (advanced analytics, social features, monetization) comes later. Build the best receipt scanner in the world first.