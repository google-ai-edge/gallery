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

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supported model formats in Neural Forge
 */
sealed class ModelFormat {
    object TensorFlowLite : ModelFormat() {
        override fun toString() = "TensorFlow Lite"
    }
    object ONNX : ModelFormat() {
        override fun toString() = "ONNX"
    }
    object PyTorchMobile : ModelFormat() {
        override fun toString() = "PyTorch Mobile"
    }
    object LiteRTLM : ModelFormat() {
        override fun toString() = "LiteRT-LM"
    }
    object Unknown : ModelFormat() {
        override fun toString() = "Unknown"
    }
}

/**
 * Quantization types for model optimization
 */
enum class QuantizationType {
    FLOAT32,
    FLOAT16,
    INT8,
    INT4,
    MIXED_PRECISION,
    DYNAMIC
}

/**
 * Hardware accelerators available on device
 */
enum class Accelerator {
    CPU,
    GPU,
    NPU,
    DSP,
    NNAPI
}

/**
 * Model metadata information
 */
data class ModelMetadata(
    val inputShapes: List<IntArray>,
    val outputShapes: List<IntArray>,
    val quantization: QuantizationType,
    val memoryRequirement: Long,
    val supportedAccelerators: Set<Accelerator>,
    val modelCard: ModelCard? = null
)

/**
 * Model card with additional information
 */
data class ModelCard(
    val name: String,
    val description: String,
    val author: String?,
    val license: String?,
    val isGenerative: Boolean = false,
    val taskType: String?
)

/**
 * Model wrapper containing runtime information
 */
data class ModelWrapper(
    val id: String,
    val name: String,
    val format: ModelFormat,
    val file: File,
    val metadata: ModelMetadata?,
    val interpreter: Any? = null
)

/**
 * Detects model format from file
 */
@Singleton
class ModelFormatDetector @Inject constructor() {

    /**
     * Detect model format from file
     */
    fun detectFormat(file: File): ModelFormat {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: ${file.path}")
            return ModelFormat.Unknown
        }

        return when {
            file.extension == "tflite" -> ModelFormat.TensorFlowLite
            file.extension == "onnx" -> ModelFormat.ONNX
            file.extension == "pt" || file.extension == "pth" -> ModelFormat.PyTorchMobile
            file.extension == "litertlm" -> ModelFormat.LiteRTLM
            else -> detectFromMagicBytes(file)
        }
    }

    /**
     * Detect format from file magic bytes
     */
    private fun detectFromMagicBytes(file: File): ModelFormat {
        try {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val bytesRead = input.read(header)

                if (bytesRead < 4) {
                    return ModelFormat.Unknown
                }

                // Check TFLite magic bytes (0x54464C33 = "TFL3")
                if (header[0] == 0x54.toByte() &&
                    header[1] == 0x46.toByte() &&
                    header[2] == 0x4C.toByte() &&
                    header[3] == 0x33.toByte()) {
                    return ModelFormat.TensorFlowLite
                }

                // Check ONNX magic bytes (Protocol Buffers)
                if (header[0] == 0x08.toByte()) {
                    return ModelFormat.ONNX
                }

                // Check PyTorch magic bytes (ZIP file signature)
                if (header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte()) {
                    return ModelFormat.PyTorchMobile
                }

                return ModelFormat.Unknown
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting format from magic bytes", e)
            return ModelFormat.Unknown
        }
    }

    /**
     * Validate if format is supported
     */
    fun isFormatSupported(format: ModelFormat): Boolean {
        return when (format) {
            ModelFormat.TensorFlowLite,
            ModelFormat.ONNX,
            ModelFormat.LiteRTLM -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "ModelFormatDetector"
    }
}

/**
 * Model source types
 */
sealed class ModelSource {
    data class HuggingFace(val modelId: String) : ModelSource()
    data class TensorFlowHub(val modelId: String) : ModelSource()
    data class Direct(val url: String) : ModelSource()
    data class Local(val path: String) : ModelSource()
}

/**
 * Optimization presets for model conversion
 */
enum class OptimizationPreset {
    SPEED,      // Maximize inference speed
    BALANCED,   // Balance speed and accuracy
    QUALITY,    // Preserve maximum quality
    MEMORY      // Minimize memory usage
}
