# Receipt Scanner KMP Project Structure

## Overview
This is a Kotlin Multiplatform Mobile (KMP) project for a receipt scanning application that follows Clean Architecture principles.

## Project Structure

### Root Level
- `build.gradle.kts` - Root build configuration
- `settings.gradle.kts` - Project settings and module configuration
- `gradle/libs.versions.toml` - Version catalog for dependency management
- `local.properties` - Local Android SDK configuration

### Modules

#### `shared/` - Shared Business Logic (KMP)
Contains the core business logic shared between Android and iOS platforms.

**Clean Architecture Layers:**

1. **Domain Layer** (`shared/src/commonMain/kotlin/com/jetbrains/receiptscanner/domain/`)
   - `model/` - Core business entities (Receipt, BankTransaction, etc.)
   - `repository/` - Repository interfaces
   - `usecase/` - Business use cases

2. **Data Layer** (`shared/src/commonMain/kotlin/com/jetbrains/receiptscanner/data/`)
   - `datasource/` - Data source interfaces
   - `repository/` - Repository implementations
   - `platform/` - Platform-specific implementations (expect/actual)

3. **Presentation Layer** (`shared/src/commonMain/kotlin/com/jetbrains/receiptscanner/presentation/`)
   - ViewModels using KMP-ObservableViewModel

4. **Dependency Injection** (`shared/src/commonMain/kotlin/com/jetbrains/receiptscanner/di/`)
   - Koin modules for dependency injection

5. **Database** (`shared/src/commonMain/sqldelight/`)
   - SQLDelight database schema and queries

#### `composeApp/` - Android Application
- Jetpack Compose UI for Android
- Platform-specific Android implementations

#### `iosApp/` - iOS Application
- SwiftUI implementation for iOS
- iOS-specific native code

## Key Technologies

### Shared (KMP)
- **Kotlin Multiplatform** - Code sharing between platforms
- **Kotlinx Coroutines** - Asynchronous programming
- **Kotlinx Serialization** - JSON serialization
- **Kotlinx DateTime** - Date/time handling
- **Ktor Client** - HTTP networking
- **SQLDelight** - Type-safe SQL database
- **Koin** - Dependency injection
- **KMP-ObservableViewModel** - Shared ViewModels

### Android
- **Jetpack Compose** - Modern UI toolkit
- **Material Design 3** - UI components
- **CameraX** - Camera functionality
- **Room** - Local database (if needed)

### iOS
- **SwiftUI** - Native iOS UI
- **AVFoundation** - Camera functionality
- **Core Data** - Local database integration

## Build Configuration

### Version Catalog (`gradle/libs.versions.toml`)
Centralized dependency management with version catalogs for:
- Kotlin and KMP versions
- AndroidX libraries
- Compose Multiplatform
- Networking libraries
- Database libraries

### Platform-Specific Builds
- **Android**: Minimum SDK 24, Target SDK 35
- **iOS**: iOS 14+ support with multiple architectures (x64, arm64, simulator)

## Development Setup

1. **Prerequisites:**
   - Android Studio with KMP plugin
   - Xcode (for iOS development)
   - Android SDK configured in `local.properties`

2. **Build Commands:**
   ```bash
   # Build Android
   ./gradlew :composeApp:assembleDebug

   # Build shared module
   ./gradlew :shared:compileDebugKotlinAndroid

   # Full build (requires Xcode for iOS)
   ./gradlew clean build
   ```

## Architecture Benefits

1. **Code Sharing**: Business logic shared between platforms
2. **Type Safety**: Compile-time safety across the entire stack
3. **Maintainability**: Clean separation of concerns
4. **Testability**: Easy to unit test business logic
5. **Platform Optimization**: Native UI on each platform

## Next Steps

This foundation is ready for implementing the receipt scanning features:
- Camera integration
- OCR processing
- Bank account connectivity
- Transaction matching
- Data persistence

The Clean Architecture structure ensures that new features can be added systematically while maintaining code quality and testability.
