/*
 * Copyright 2025 Neural Forge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.neuralforge.mobile.execution

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import com.neuralforge.mobile.core.ModelWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime inference engine for Neural Forge
 */
@Singleton
class ONNXInferenceEngine @Inject constructor(
    private val context: Context
) {

    private val ortEnvironment: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    /**
     * Create session options with specified accelerator
     */
    fun createSessionOptions(accelerator: String = "CPU"): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            // Use all available CPU threads
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
            setInterOpNumThreads(Runtime.getRuntime().availableProcessors())

            // Set graph optimization level
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // Configure execution provider based on accelerator type
            when (accelerator.uppercase()) {
                "NNAPI", "NPU" -> {
                    try {
                        // Add NNAPI execution provider for NPU acceleration
                        addNnapi()
                        Log.d(TAG, "NNAPI execution provider enabled for NPU acceleration")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable NNAPI, falling back to CPU", e)
                    }
                }
                "GPU" -> {
                    Log.d(TAG, "GPU acceleration requested but not yet implemented in ONNX Runtime Android")
                    // GPU execution provider not available in standard Android build
                    // Fallback to CPU with optimizations
                }
                else -> {
                    // CPU - enable optimizations
                    addConfigEntry("session.intra_op.allow_spinning", "1")
                    addConfigEntry("session.inter_op.allow_spinning", "1")
                    Log.d(TAG, "Using CPU execution provider with optimizations")
                }
            }
        }
    }

    private val sessionOptions: OrtSession.SessionOptions by lazy {
        createSessionOptions("CPU")
    }

    /**
     * Load an ONNX model with default CPU execution
     */
    suspend fun loadModel(modelFile: File): Result<OrtSession> = loadModel(modelFile, "CPU")

    /**
     * Load an ONNX model with specified accelerator
     * @param modelFile The ONNX model file to load
     * @param accelerator The accelerator to use: "CPU", "GPU", "NPU", "NNAPI"
     */
    suspend fun loadModel(modelFile: File, accelerator: String): Result<OrtSession> = withContext(Dispatchers.IO) {
        try {
            val options = createSessionOptions(accelerator)
            val session = ortEnvironment.createSession(
                modelFile.absolutePath,
                options
            )

            Log.d(TAG, "ONNX model loaded successfully: ${modelFile.name} with $accelerator accelerator")
            Log.d(TAG, "Input names: ${session.inputNames}")
            Log.d(TAG, "Output names: ${session.outputNames}")

            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model: ${modelFile.name} with $accelerator", e)
            Result.failure(e)
        }
    }

    /**
     * Run inference on ONNX model with float input
     */
    suspend fun runInference(
        session: OrtSession,
        inputData: FloatArray,
        inputShape: LongArray
    ): Result<FloatArray> = withContext(Dispatchers.Default) {
        try {
            val inputName = session.inputNames.first()

            // Create tensor from input data
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                inputData,
                inputShape
            )

            // Run inference
            val results = session.run(mapOf(inputName to inputTensor))

            // Extract output
            val outputTensor = results.first().value as OnnxTensor
            val outputData = outputTensor.floatBuffer.array()

            // Clean up
            inputTensor.close()
            results.close()

            Result.success(outputData)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed", e)
            Result.failure(e)
        }
    }

    /**
     * Run inference with multiple inputs
     */
    suspend fun runInferenceMultiInput(
        session: OrtSession,
        inputs: Map<String, OnnxTensor>
    ): Result<List<OnnxValue>> = withContext(Dispatchers.Default) {
        try {
            val results = session.run(inputs)
            val outputList = results.map { it.value }

            Result.success(outputList)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX multi-input inference failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get model input information
     */
    fun getInputInfo(session: OrtSession): List<InputInfo> {
        return session.inputInfo.map { (name, info) ->
            InputInfo(
                name = name,
                shape = (info.info as TensorInfo).shape,
                type = (info.info as TensorInfo).type.toString()
            )
        }
    }

    /**
     * Get model output information
     */
    fun getOutputInfo(session: OrtSession): List<OutputInfo> {
        return session.outputInfo.map { (name, info) ->
            OutputInfo(
                name = name,
                shape = (info.info as TensorInfo).shape,
                type = (info.info as TensorInfo).type.toString()
            )
        }
    }

    /**
     * Close a session and free resources
     */
    fun closeSession(session: OrtSession) {
        try {
            session.close()
            Log.d(TAG, "ONNX session closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session", e)
        }
    }

    data class InputInfo(
        val name: String,
        val shape: LongArray,
        val type: String
    )

    data class OutputInfo(
        val name: String,
        val shape: LongArray,
        val type: String
    )

    companion object {
        private const val TAG = "ONNXInferenceEngine"
    }
}

/**
 * Inference result wrapper
 */
sealed class InferenceResult {
    data class Success(val outputs: Map<String, FloatArray>) : InferenceResult()
    data class Error(val exception: Throwable) : InferenceResult()
}

/**
 * Inference configuration
 */
data class InferenceConfig(
    val useGPU: Boolean = false,
    val numThreads: Int = Runtime.getRuntime().availableProcessors(),
    val optimizationLevel: OptimizationLevel = OptimizationLevel.ALL
)

enum class OptimizationLevel {
    NO_OPT,
    BASIC_OPT,
    EXTENDED_OPT,
    ALL
}
