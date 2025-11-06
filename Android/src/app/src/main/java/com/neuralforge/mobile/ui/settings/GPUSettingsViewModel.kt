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

package com.neuralforge.mobile.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuralforge.mobile.core.Accelerator
import com.neuralforge.mobile.core.HardwareAccelerationManager
import com.neuralforge.mobile.core.HardwareCapabilities
import com.neuralforge.mobile.data.DataStoreRepository
import com.neuralforge.mobile.proto.Accelerator as ProtoAccelerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "GPUSettingsViewModel"

/**
 * Convert proto Accelerator to core Accelerator
 */
private fun ProtoAccelerator.toCore(): Accelerator {
    return when (this) {
        ProtoAccelerator.ACCELERATOR_CPU -> Accelerator.CPU
        ProtoAccelerator.ACCELERATOR_GPU -> Accelerator.GPU
        ProtoAccelerator.ACCELERATOR_NPU -> Accelerator.NPU
        ProtoAccelerator.ACCELERATOR_DSP -> Accelerator.DSP
        ProtoAccelerator.ACCELERATOR_NNAPI -> Accelerator.NNAPI
        ProtoAccelerator.ACCELERATOR_UNSPECIFIED -> Accelerator.CPU
        ProtoAccelerator.UNRECOGNIZED -> Accelerator.CPU
    }
}

/**
 * Convert core Accelerator to proto Accelerator
 */
private fun Accelerator.toProto(): ProtoAccelerator {
    return when (this) {
        Accelerator.CPU -> ProtoAccelerator.ACCELERATOR_CPU
        Accelerator.GPU -> ProtoAccelerator.ACCELERATOR_GPU
        Accelerator.NPU -> ProtoAccelerator.ACCELERATOR_NPU
        Accelerator.DSP -> ProtoAccelerator.ACCELERATOR_DSP
        Accelerator.NNAPI -> ProtoAccelerator.ACCELERATOR_NNAPI
    }
}

/**
 * ViewModel for GPU and Hardware Acceleration Settings
 */
@HiltViewModel
class GPUSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareManager: HardwareAccelerationManager,
    private val dataStoreRepository: DataStoreRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GPUSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        detectHardware()
        loadSelectedAccelerator()
    }

    /**
     * Detect hardware capabilities
     */
    private fun detectHardware() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDetecting = true) }

            try {
                val capabilities = hardwareManager.detectCapabilities()

                _uiState.update {
                    it.copy(
                        hardwareCapabilities = capabilities,
                        isDetecting = false
                    )
                }

                Log.d(TAG, "Hardware detection complete")
                logHardwareInfo(capabilities)
            } catch (e: Exception) {
                Log.e(TAG, "Hardware detection failed", e)
                _uiState.update {
                    it.copy(
                        isDetecting = false,
                        error = "Failed to detect hardware: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Load selected accelerator from preferences
     */
    private fun loadSelectedAccelerator() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get saved accelerator preference from DataStore
                val protoAccelerator = dataStoreRepository.readPreferredAccelerator()
                val savedAccelerator = protoAccelerator.toCore()

                _uiState.update {
                    it.copy(selectedAccelerator = savedAccelerator)
                }

                Log.d(TAG, "Loaded accelerator preference: $savedAccelerator")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load accelerator preference", e)
                // Default to CPU on error
                _uiState.update {
                    it.copy(selectedAccelerator = Accelerator.CPU)
                }
            }
        }
    }

    /**
     * Select an accelerator
     */
    fun selectAccelerator(accelerator: Accelerator) {
        viewModelScope.launch {
            val capabilities = _uiState.value.hardwareCapabilities

            if (capabilities == null) {
                Log.w(TAG, "Cannot select accelerator: capabilities not detected")
                return@launch
            }

            // Verify accelerator is available
            val isAvailable = hardwareManager.isAcceleratorAvailable(accelerator, capabilities)

            if (!isAvailable) {
                Log.w(TAG, "Cannot select $accelerator: not available on this device")
                _uiState.update {
                    it.copy(error = "$accelerator is not available on this device")
                }
                return@launch
            }

            // Update state
            _uiState.update {
                it.copy(selectedAccelerator = accelerator)
            }

            // Save preference
            saveAcceleratorPreference(accelerator)

            Log.d(TAG, "Selected accelerator: $accelerator")
        }
    }

    /**
     * Save accelerator preference
     */
    private suspend fun saveAcceleratorPreference(accelerator: Accelerator) {
        try {
            val protoAccelerator = accelerator.toProto()
            dataStoreRepository.savePreferredAccelerator(protoAccelerator)
            Log.d(TAG, "Saved accelerator preference: $accelerator")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save accelerator preference", e)
        }
    }

    /**
     * Get recommended accelerator for current device
     */
    fun getRecommendation() {
        viewModelScope.launch {
            val capabilities = _uiState.value.hardwareCapabilities

            if (capabilities == null) {
                Log.w(TAG, "Cannot get recommendation: capabilities not detected")
                return@launch
            }

            // Get recommendation based on most common model format (TFLite)
            val recommended = hardwareManager.getRecommendedAccelerator(
                format = com.neuralforge.mobile.core.ModelFormat.TensorFlowLite,
                capabilities = capabilities
            )

            Log.d(TAG, "Recommended accelerator: $recommended")

            _uiState.update {
                it.copy(
                    recommendedAccelerator = recommended,
                    showRecommendation = true
                )
            }
        }
    }

    /**
     * Dismiss recommendation
     */
    fun dismissRecommendation() {
        _uiState.update {
            it.copy(showRecommendation = false)
        }
    }

    /**
     * Clear error
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Refresh hardware detection
     */
    fun refresh() {
        detectHardware()
    }

    /**
     * Log hardware information for debugging
     */
    private fun logHardwareInfo(capabilities: HardwareCapabilities) {
        Log.d(TAG, "=== Hardware Capabilities ===")
        Log.d(TAG, "CPU: ${capabilities.cpu.name}")
        Log.d(TAG, "  - Cores: ${capabilities.cpu.cores}")
        Log.d(TAG, "  - Architecture: ${capabilities.cpu.architecture}")
        Log.d(TAG, "  - Chipset: ${capabilities.cpu.chipset}")

        capabilities.gpu?.let { gpu ->
            Log.d(TAG, "GPU: ${gpu.name}")
            Log.d(TAG, "  - Vendor: ${gpu.vendor}")
            Log.d(TAG, "  - TFLite Support: ${gpu.supportsTFLite}")
        } ?: Log.d(TAG, "GPU: Not detected")

        capabilities.npu?.let { npu ->
            Log.d(TAG, "NPU: ${npu.name}")
            Log.d(TAG, "  - Vendor: ${npu.vendor}")
            Log.d(TAG, "  - Available: ${npu.available}")
        } ?: Log.d(TAG, "NPU: Not available")

        capabilities.dsp?.let { dsp ->
            Log.d(TAG, "DSP: ${dsp.name}")
            Log.d(TAG, "  - Vendor: ${dsp.vendor}")
            Log.d(TAG, "  - Available: ${dsp.available}")
        } ?: Log.d(TAG, "DSP: Not available")

        capabilities.nnapi?.let { nnapi ->
            Log.d(TAG, "NNAPI: Version ${nnapi.version}")
            Log.d(TAG, "  - API Level: ${nnapi.apiLevel}")
        } ?: Log.d(TAG, "NNAPI: Not available")

        Log.d(TAG, "===========================")
    }
}

/**
 * UI State for GPU Settings
 */
data class GPUSettingsUiState(
    val hardwareCapabilities: HardwareCapabilities? = null,
    val selectedAccelerator: Accelerator = Accelerator.CPU,
    val recommendedAccelerator: Accelerator? = null,
    val isDetecting: Boolean = false,
    val showRecommendation: Boolean = false,
    val error: String? = null
)
