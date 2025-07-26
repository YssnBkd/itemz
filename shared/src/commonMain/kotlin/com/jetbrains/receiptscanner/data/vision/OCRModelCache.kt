package com.jetbrains.receiptscanner.data.vision

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OCR model cache and memory management system
 */
interface OCRModelCache {
    /**
     * Load and cache OCR model
     */
    suspend fun loadModel(modelPath: String, modelType: OCRModelType): Result<OCRModel>

    /**
     * Get cached model if available
     */
    suspend fun getCachedModel(modelPath: String): OCRModel?

    /**
     * Warm up models by preloading them
     */
    suspend fun warmUpModels(modelPaths: List<String>): Result<Unit>

    /**
     * Release specific model from cache
     */
    suspend fun releaseModel(modelPath: String)

    /**
     * Release all cached models
     */
    suspend fun releaseAllModels()

    /**
     * Get current memory usage
     */
    fun getMemoryUsage(): MemoryUsageInfo
}

/**
 * OCR model types
 */
enum class OCRModelType {
    TEXT_DETECTION,
    TEXT_RECOGNITION
}

/**
 * Abstract OCR model interface
 */
interface OCRModel {
    val modelPath: String
    val modelType: OCRModelType
    val memorySizeMB: Float
    val isLoaded: Boolean

    suspend fun runInference(input: ModelInput): Result<ModelOutput>
    suspend fun release()
}

/**
 * Model input data
 */
sealed class ModelInput {
    data class ImageTensor(
        val tensor: FloatArray,
        val shape: IntArray,
        val originalImageSize: Pair<Int, Int>
    ) : ModelInput()

    data class TextRegionBatch(
        val regions: List<FloatArray>,
        val shapes: List<IntArray>
    ) : ModelInput()
}

/**
 * Model output data
 */
sealed class ModelOutput {
    data class DetectionOutput(
        val textRegions: List<TextRegion>,
        val confidence: Float
    ) : ModelOutput()

    data class RecognitionOutput(
        val recognizedText: List<RecognizedTextLine>
    ) : ModelOutput()
}

/**
 * Memory usage information
 */
data class MemoryUsageInfo(
    val totalCachedModelsMB: Float,
    val availableMemoryMB: Float,
    val memoryPressure: MemoryPressure
)

/**
 * Memory pressure levels
 */
enum class MemoryPressure {
    LOW,    // < 50% memory used
    MEDIUM, // 50-80% memory used
    HIGH,   // 80-95% memory used
    CRITICAL // > 95% memory used
}

/**
 * Default implementation of OCR model cache
 */
class OCRModelCacheImpl(
    private val maxCacheSizeMB: Float = 100f,
    private val modelLoader: OCRModelLoader
) : OCRModelCache {

    private val modelCache = mutableMapOf<String, OCRModel>()
    private val cacheMutex = Mutex()
    private var totalCacheSizeMB = 0f

    override suspend fun loadModel(modelPath: String, modelType: OCRModelType): Result<OCRModel> {
        return cacheMutex.withLock {
            // Check if model is already cached
            modelCache[modelPath]?.let { cachedModel ->
                if (cachedModel.isLoaded) {
                    return@withLock Result.success(cachedModel)
                }
            }

            // Load new model
            modelLoader.loadModel(modelPath, modelType).fold(
                onSuccess = { model ->
                    // Check if we need to free memory
                    ensureMemoryAvailable(model.memorySizeMB)

                    // Cache the model
                    modelCache[modelPath] = model
                    totalCacheSizeMB += model.memorySizeMB

                    Result.success(model)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        }
    }

    override suspend fun getCachedModel(modelPath: String): OCRModel? {
        return cacheMutex.withLock {
            modelCache[modelPath]?.takeIf { it.isLoaded }
        }
    }

    override suspend fun warmUpModels(modelPaths: List<String>): Result<Unit> {
        return runCatching {
            modelPaths.forEach { modelPath ->
                val modelType = when {
                    modelPath.contains("detection") -> OCRModelType.TEXT_DETECTION
                    modelPath.contains("recognition") -> OCRModelType.TEXT_RECOGNITION
                    else -> OCRModelType.TEXT_DETECTION
                }
                loadModel(modelPath, modelType).getOrThrow()
            }
        }
    }

    override suspend fun releaseModel(modelPath: String) {
        cacheMutex.withLock {
            modelCache[modelPath]?.let { model ->
                model.release()
                totalCacheSizeMB -= model.memorySizeMB
                modelCache.remove(modelPath)
            }
        }
    }

    override suspend fun releaseAllModels() {
        cacheMutex.withLock {
            modelCache.values.forEach { model ->
                model.release()
            }
            modelCache.clear()
            totalCacheSizeMB = 0f
        }
    }

    override fun getMemoryUsage(): MemoryUsageInfo {
        val availableMemory = getAvailableMemoryMB()
        val memoryUsageRatio = totalCacheSizeMB / (totalCacheSizeMB + availableMemory)

        val memoryPressure = when {
            memoryUsageRatio < 0.5f -> MemoryPressure.LOW
            memoryUsageRatio < 0.8f -> MemoryPressure.MEDIUM
            memoryUsageRatio < 0.95f -> MemoryPressure.HIGH
            else -> MemoryPressure.CRITICAL
        }

        return MemoryUsageInfo(
            totalCachedModelsMB = totalCacheSizeMB,
            availableMemoryMB = availableMemory,
            memoryPressure = memoryPressure
        )
    }

    private suspend fun ensureMemoryAvailable(requiredMemoryMB: Float) {
        if (totalCacheSizeMB + requiredMemoryMB > maxCacheSizeMB) {
            // Implement LRU eviction strategy
            val modelsToEvict = modelCache.values
                .sortedBy { it.memorySizeMB } // Evict smaller models first
                .takeWhile { totalCacheSizeMB + requiredMemoryMB - it.memorySizeMB > maxCacheSizeMB }

            modelsToEvict.forEach { model ->
                releaseModel(model.modelPath)
            }
        }
    }

    private fun getAvailableMemoryMB(): Float {
        // Platform-specific implementation would go here
        return 512f // Default assumption
    }
}

/**
 * Interface for loading OCR models
 */
interface OCRModelLoader {
    suspend fun loadModel(modelPath: String, modelType: OCRModelType): Result<OCRModel>
}

/**
 * Platform-specific factory for creating model cache
 */
expect object OCRModelCacheFactory {
    fun create(maxCacheSizeMB: Float = 100f): OCRModelCache
}
