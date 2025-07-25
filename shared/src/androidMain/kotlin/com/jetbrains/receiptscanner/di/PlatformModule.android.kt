package com.jetbrains.receiptscanner.di

import com.jetbrains.receiptscanner.data.platform.DatabaseDriverFactory
import com.jetbrains.receiptscanner.data.security.SecureStorage
import com.jetbrains.receiptscanner.data.security.SecureStorageImpl
import com.jetbrains.receiptscanner.data.service.PlaidService
import com.jetbrains.receiptscanner.data.service.PlaidServiceImpl
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

actual val platformModule = module {
    single { DatabaseDriverFactory(get()) }

    single<SecureStorage> { SecureStorageImpl(get()) }

    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    single<PlaidService> {
        PlaidServiceImpl(
            httpClient = get(),
            clientId = "your_plaid_client_id", // TODO: Move to configuration
            secret = "your_plaid_secret", // TODO: Move to configuration
            environment = "sandbox" // TODO: Move to configuration
        )
    }

    // Vision components
    single<com.jetbrains.receiptscanner.data.vision.ImageProcessor> {
        com.jetbrains.receiptscanner.data.vision.ImageProcessorFactory.create()
    }
    single<com.jetbrains.receiptscanner.data.vision.ImagePreprocessor> {
        com.jetbrains.receiptscanner.data.vision.VisionComponentFactory.createImagePreprocessor()
    }
    single<com.jetbrains.receiptscanner.data.vision.ONNXRuntimeEngine> {
        com.jetbrains.receiptscanner.data.vision.VisionComponentFactory.createONNXRuntimeEngine()
    }
    single<com.jetbrains.receiptscanner.data.vision.ReceiptDetector> {
        com.jetbrains.receiptscanner.data.vision.VisionComponentFactory.createReceiptDetector(
            onnxEngine = get(),
            imagePreprocessor = get()
        )
    }
    single<com.jetbrains.receiptscanner.data.vision.ImageQualityAssessor> {
        com.jetbrains.receiptscanner.data.vision.ImageQualityAssessorFactory.create()
    }
}
