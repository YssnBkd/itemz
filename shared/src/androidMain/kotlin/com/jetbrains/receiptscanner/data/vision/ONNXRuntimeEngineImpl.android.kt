package com.jetbrains.receiptscanner.data.vision

import ai.onnxruntime.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.FloatBuffer

/**
 * Android implementation of ONNX Runtime engine
 */
class ONNXRuntimeEngineImpl(private val context: Context?) : ONNXRuntimeEngine {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    companion object {
        private const val MODEL_THREAD_COUNT = 4
    }

    override suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Clean up existing session if any
            ortSession?.close()
            ortSession = null

            // Initialize ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Create session options
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(MODEL_THREAD_COUNT)
                setInterOpNumThreads(MODEL_THREAD_COUNT)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            // Load model from assets
            val modelBytes = loadModelFromAssets(modelPath)

            // Create ONNX Runtime session
            ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
                ?: throw IllegalStateException("Failed to create ONNX Runtime session")
        }
    }

    override suspend fun runInference(inputTensor: FloatArray, inputShape: IntArray): Result<FloatArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                val session = ortSession ?: throw IllegalStateException("Model not loaded")
                val environment = ortEnvironment ?: throw IllegalStateException("Environment not initialized")

                // Create input tensor
                val inputBuffer = FloatBuffer.wrap(inputTensor)
                val inputOnnxTensor = OnnxTensor.createTensor(
                    environment,
                    inputBuffer,
                    inputShape.map { it.toLong() }.toLongArray()
                )

                try {
                    // Prepare input map
                    val inputName = session.inputNames.iterator().next()
                    val inputs = mapOf(inputName to inputOnnxTensor)

                    // Run inference
                    val results = session.run(inputs)
                    try {
                        // Extract output
                        val outputName = session.outputNames.iterator().next()
                        val output = results.get(outputName) as OnnxTensor
                        output.floatBuffer.array()
                    } finally {
                        results.close()
                    }
                } finally {
                    inputOnnxTensor.close()
                }
            }
        }

    override suspend fun releaseModel() = withContext(Dispatchers.IO) {
        ortSession?.close()
        ortSession = null
        ortEnvironment?.close()
        ortEnvironment = null
    }

    private fun loadModelFromAssets(modelPath: String): ByteArray {
        if (context == null) {
            // For testing purposes, return empty array
            return ByteArray(0)
        }
        val inputStream: InputStream = context.assets.open(modelPath)
        return inputStream.use { it.readBytes() }
    }
}
