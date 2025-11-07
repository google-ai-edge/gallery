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

package com.neuralforge.mobile.core

import android.content.Context
import android.util.Log
import com.neuralforge.mobile.converter.ModelConverter
import com.neuralforge.mobile.downloader.ModelDownloadManager
import com.neuralforge.mobile.execution.ONNXInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Neural Forge Engine - The central hub for all model operations
 *
 * This is the main entry point for:
 * - Model discovery and download
 * - Format detection and conversion
 * - Model loading and inference
 * - Hardware optimization
 */
@Singleton
class NeuralForgeEngine @Inject constructor(
    private val context: Context,
    private val downloadManager: ModelDownloadManager,
    private val formatDetector: ModelFormatDetector,
    private val modelConverter: ModelConverter,
    private val onnxEngine: ONNXInferenceEngine
) {

    private val modelRegistry = mutableMapOf<String, ModelWrapper>()
    private val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        Log.d(TAG, "Neural Forge Engine initialized")
        Log.d(TAG, "Version: ${BuildConfig.VERSION_NAME}")
    }

    /**
     * Download a model from a source
     */
    fun downloadModel(
        url: String,
        modelName: String,
        enableResume: Boolean = true
    ): Flow<ModelDownloadManager.DownloadState> {
        Log.d(TAG, "Starting download for: $modelName")
        return downloadManager.downloadModel(url, modelName, enableResume)
    }

    /**
     * Load a model from file
     */
    suspend fun loadModel(
        modelId: String,
        modelName: String,
        optimizationPreset: OptimizationPreset = OptimizationPreset.BALANCED
    ): Result<ModelWrapper> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading model: $modelName")

            // Get model file
            val modelFile = downloadManager.getModelFile(modelName)
                ?: return@withContext Result.failure(
                    IllegalStateException("Model file not found: $modelName")
                )

            // Detect format
            val format = formatDetector.detectFormat(modelFile)
            Log.d(TAG, "Detected format: $format")

            if (!formatDetector.isFormatSupported(format)) {
                return@withContext Result.failure(
                    UnsupportedOperationException("Format not supported: $format")
                )
            }

            // Optimize for mobile if needed
            val optimizedFile = modelConverter.optimizeForMobile(
                modelFile,
                format,
                optimizationPreset
            ).getOrNull() ?: modelFile

            // Create model wrapper
            val wrapper = ModelWrapper(
                id = modelId,
                name = modelName,
                format = format,
                file = optimizedFile,
                metadata = null // Will be populated by specific loaders
            )

            // Register model
            modelRegistry[modelId] = wrapper

            Log.d(TAG, "Model loaded successfully: $modelName")
            Result.success(wrapper)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelName", e)
            Result.failure(e)
        }
    }

    /**
     * Get a loaded model by ID
     */
    fun getModel(modelId: String): ModelWrapper? {
        return modelRegistry[modelId]
    }

    /**
     * Get all loaded models
     */
    fun getAllModels(): List<ModelWrapper> {
        return modelRegistry.values.toList()
    }

    /**
     * Unload a model and free resources
     */
    fun unloadModel(modelId: String): Boolean {
        val wrapper = modelRegistry.remove(modelId)
        return if (wrapper != null) {
            // Close any active interpreters
            wrapper.interpreter?.let { interpreter ->
                when (wrapper.format) {
                    is ModelFormat.ONNX -> {
                        try {
                            onnxEngine.closeSession(interpreter as ai.onnxruntime.OrtSession)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing ONNX session", e)
                        }
                    }
                    else -> {
                        // Handle other formats
                    }
                }
            }
            Log.d(TAG, "Model unloaded: $modelId")
            true
        } else {
            Log.w(TAG, "Model not found for unload: $modelId")
            false
        }
    }

    /**
     * Delete a downloaded model
     */
    fun deleteModel(modelName: String): Boolean {
        return downloadManager.deleteModel(modelName)
    }

    /**
     * Check if a model is downloaded
     */
    fun isModelDownloaded(modelName: String): Boolean {
        return downloadManager.isModelDownloaded(modelName)
    }

    /**
     * Get device capabilities
     */
    fun getDeviceCapabilities(): DeviceCapabilities {
        return DeviceCapabilities(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalMemory = Runtime.getRuntime().totalMemory(),
            availableMemory = Runtime.getRuntime().freeMemory(),
            supportedAccelerators = getSupportedAccelerators()
        )
    }

    /**
     * Get supported hardware accelerators
     */
    private fun getSupportedAccelerators(): Set<Accelerator> {
        val accelerators = mutableSetOf<Accelerator>()

        // CPU is always available
        accelerators.add(Accelerator.CPU)

        // Check for GPU support (will be implemented based on device capabilities)
        try {
            // GPU delegate availability check
            accelerators.add(Accelerator.GPU)
        } catch (e: Exception) {
            Log.d(TAG, "GPU not available")
        }

        // Check for NNAPI support
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            accelerators.add(Accelerator.NNAPI)
        }

        return accelerators
    }

    /**
     * Get model statistics
     */
    fun getModelStats(): ModelStats {
        val totalModels = modelRegistry.size
        val formatBreakdown = modelRegistry.values
            .groupBy { it.format }
            .mapValues { it.value.size }

        return ModelStats(
            totalModelsLoaded = totalModels,
            formatBreakdown = formatBreakdown
        )
    }

    companion object {
        private const val TAG = "NeuralForgeEngine"
    }
}

/**
 * Device capabilities information
 */
data class DeviceCapabilities(
    val cpuCores: Int,
    val totalMemory: Long,
    val availableMemory: Long,
    val supportedAccelerators: Set<Accelerator>
)

/**
 * Model statistics
 */
data class ModelStats(
    val totalModelsLoaded: Int,
    val formatBreakdown: Map<ModelFormat, Int>
)

/**
 * Build configuration placeholder
 */
object BuildConfig {
    const val VERSION_NAME = "1.0.0"
}
