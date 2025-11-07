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
import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware acceleration manager for Neural Forge
 *
 * Detects and manages hardware accelerators available on the device:
 * - CPU (always available)
 * - GPU (Adreno, Mali, PowerVR, etc.)
 * - NPU (Neural Processing Unit - Snapdragon, Exynos, etc.)
 * - DSP (Digital Signal Processor - Hexagon)
 * - NNAPI (Android Neural Networks API)
 */
@Singleton
class HardwareAccelerationManager @Inject constructor(
    private val context: Context
) {

    private var detectedCapabilities: HardwareCapabilities? = null

    /**
     * Detect all available hardware accelerators
     */
    fun detectCapabilities(): HardwareCapabilities {
        if (detectedCapabilities != null) {
            return detectedCapabilities!!
        }

        Log.d(TAG, "Detecting hardware capabilities...")

        val capabilities = HardwareCapabilities(
            cpu = detectCPU(),
            gpu = detectGPU(),
            npu = detectNPU(),
            dsp = detectDSP(),
            nnapi = detectNNAPI()
        )

        detectedCapabilities = capabilities

        Log.d(TAG, "Hardware detection complete:")
        Log.d(TAG, "  CPU: ${capabilities.cpu.name} (${capabilities.cpu.cores} cores)")
        Log.d(TAG, "  GPU: ${if (capabilities.gpu != null) capabilities.gpu.name else "Not detected"}")
        Log.d(TAG, "  NPU: ${if (capabilities.npu != null) "Available" else "Not available"}")
        Log.d(TAG, "  DSP: ${if (capabilities.dsp != null) "Available" else "Not available"}")
        Log.d(TAG, "  NNAPI: ${if (capabilities.nnapi != null) "API Level ${capabilities.nnapi.apiLevel}" else "Not available"}")

        return capabilities
    }

    /**
     * Get recommended accelerator for a specific model format
     */
    fun getRecommendedAccelerator(
        format: ModelFormat,
        capabilities: HardwareCapabilities = detectCapabilities()
    ): Accelerator {
        return when (format) {
            is ModelFormat.TensorFlowLite -> {
                // TFLite works best with GPU or NNAPI
                when {
                    capabilities.gpu != null && capabilities.gpu.supportsTFLite -> Accelerator.GPU
                    capabilities.nnapi != null -> Accelerator.NNAPI
                    else -> Accelerator.CPU
                }
            }
            is ModelFormat.ONNX -> {
                // ONNX Runtime supports CPU, GPU
                when {
                    capabilities.gpu != null -> Accelerator.GPU
                    else -> Accelerator.CPU
                }
            }
            is ModelFormat.LiteRTLM -> {
                // LiteRT optimized for CPU but can use GPU
                when {
                    capabilities.gpu != null -> Accelerator.GPU
                    else -> Accelerator.CPU
                }
            }
            else -> Accelerator.CPU
        }
    }

    /**
     * Check if a specific accelerator is available
     */
    fun isAcceleratorAvailable(
        accelerator: Accelerator,
        capabilities: HardwareCapabilities = detectCapabilities()
    ): Boolean {
        return when (accelerator) {
            Accelerator.CPU -> true // Always available
            Accelerator.GPU -> capabilities.gpu != null
            Accelerator.NPU -> capabilities.npu != null
            Accelerator.DSP -> capabilities.dsp != null
            Accelerator.NNAPI -> capabilities.nnapi != null
        }
    }

    private fun detectCPU(): CPUInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        // Try to detect CPU name from system properties
        val cpuName = try {
            System.getProperty("ro.hardware") ?: "Unknown CPU"
        } catch (e: Exception) {
            "Unknown CPU"
        }

        val chipset = detectChipset()

        return CPUInfo(
            name = cpuName,
            cores = cores,
            architecture = architecture,
            chipset = chipset
        )
    }

    private fun detectChipset(): Chipset {
        val hardware = try {
            System.getProperty("ro.hardware") ?: ""
        } catch (e: Exception) {
            ""
        }

        val board = Build.BOARD.lowercase()
        val device = Build.DEVICE.lowercase()

        return when {
            // Snapdragon detection
            hardware.contains("qcom") || board.contains("qcom") ||
            device.contains("qcom") -> {
                val model = Build.MODEL.lowercase()
                when {
                    model.contains("8 gen 3") -> Chipset.SNAPDRAGON_8_GEN_3
                    model.contains("8 gen 2") -> Chipset.SNAPDRAGON_8_GEN_2
                    model.contains("8 gen 1") -> Chipset.SNAPDRAGON_8_GEN_1
                    model.contains("888") -> Chipset.SNAPDRAGON_888
                    else -> Chipset.SNAPDRAGON_OTHER
                }
            }
            // Exynos detection
            hardware.contains("exynos") || board.contains("exynos") -> Chipset.EXYNOS
            // MediaTek detection
            hardware.contains("mt") || board.contains("mt") -> Chipset.MEDIATEK
            // Google Tensor
            hardware.contains("tensor") || device.contains("tensor") -> Chipset.GOOGLE_TENSOR
            else -> Chipset.UNKNOWN
        }
    }

    private fun detectGPU(): GPUInfo? {
        // GPU detection is complex on Android
        // For now, we assume GPU is available and try to detect vendor

        val chipset = detectChipset()

        return when (chipset) {
            Chipset.SNAPDRAGON_8_GEN_3 -> GPUInfo("Adreno 750", GPUVendor.QUALCOMM, true)
            Chipset.SNAPDRAGON_8_GEN_2 -> GPUInfo("Adreno 740", GPUVendor.QUALCOMM, true)
            Chipset.SNAPDRAGON_8_GEN_1 -> GPUInfo("Adreno 730", GPUVendor.QUALCOMM, true)
            Chipset.SNAPDRAGON_888 -> GPUInfo("Adreno 660", GPUVendor.QUALCOMM, true)
            Chipset.EXYNOS -> GPUInfo("Mali", GPUVendor.ARM, true)
            Chipset.MEDIATEK -> GPUInfo("Mali", GPUVendor.ARM, true)
            Chipset.GOOGLE_TENSOR -> GPUInfo("Mali", GPUVendor.ARM, true)
            else -> GPUInfo("Unknown GPU", GPUVendor.UNKNOWN, false)
        }
    }

    private fun detectNPU(): NPUInfo? {
        val chipset = detectChipset()

        return when (chipset) {
            Chipset.SNAPDRAGON_8_GEN_3,
            Chipset.SNAPDRAGON_8_GEN_2,
            Chipset.SNAPDRAGON_8_GEN_1 -> {
                NPUInfo("Hexagon NPU", NPUVendor.QUALCOMM, true)
            }
            Chipset.EXYNOS -> {
                NPUInfo("Exynos NPU", NPUVendor.SAMSUNG, true)
            }
            Chipset.GOOGLE_TENSOR -> {
                NPUInfo("Tensor Processing Unit", NPUVendor.GOOGLE, true)
            }
            Chipset.MEDIATEK -> {
                NPUInfo("APU", NPUVendor.MEDIATEK, true)
            }
            else -> null
        }
    }

    private fun detectDSP(): DSPInfo? {
        val chipset = detectChipset()

        return when (chipset) {
            Chipset.SNAPDRAGON_8_GEN_3,
            Chipset.SNAPDRAGON_8_GEN_2,
            Chipset.SNAPDRAGON_8_GEN_1,
            Chipset.SNAPDRAGON_888,
            Chipset.SNAPDRAGON_OTHER -> {
                DSPInfo("Hexagon DSP", DSPVendor.QUALCOMM, true)
            }
            else -> null
        }
    }

    private fun detectNNAPI(): NNAPIInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NNAPIInfo(
                apiLevel = Build.VERSION.SDK_INT,
                version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "1.2+" else "1.0"
            )
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "HardwareAcceleration"
    }
}

/**
 * Complete hardware capabilities
 */
data class HardwareCapabilities(
    val cpu: CPUInfo,
    val gpu: GPUInfo?,
    val npu: NPUInfo?,
    val dsp: DSPInfo?,
    val nnapi: NNAPIInfo?
)

/**
 * CPU information
 */
data class CPUInfo(
    val name: String,
    val cores: Int,
    val architecture: String,
    val chipset: Chipset
)

/**
 * GPU information
 */
data class GPUInfo(
    val name: String,
    val vendor: GPUVendor,
    val supportsTFLite: Boolean
)

/**
 * NPU information
 */
data class NPUInfo(
    val name: String,
    val vendor: NPUVendor,
    val available: Boolean
)

/**
 * DSP information
 */
data class DSPInfo(
    val name: String,
    val vendor: DSPVendor,
    val available: Boolean
)

/**
 * NNAPI information
 */
data class NNAPIInfo(
    val apiLevel: Int,
    val version: String
)

/**
 * Chipset types
 */
enum class Chipset {
    SNAPDRAGON_8_GEN_3,
    SNAPDRAGON_8_GEN_2,
    SNAPDRAGON_8_GEN_1,
    SNAPDRAGON_888,
    SNAPDRAGON_OTHER,
    EXYNOS,
    MEDIATEK,
    GOOGLE_TENSOR,
    UNKNOWN
}

/**
 * GPU vendors
 */
enum class GPUVendor {
    QUALCOMM,  // Adreno
    ARM,       // Mali
    IMAGINATION, // PowerVR
    UNKNOWN
}

/**
 * NPU vendors
 */
enum class NPUVendor {
    QUALCOMM,
    SAMSUNG,
    GOOGLE,
    MEDIATEK,
    UNKNOWN
}

/**
 * DSP vendors
 */
enum class DSPVendor {
    QUALCOMM,
    UNKNOWN
}
