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

package com.neuralforge.mobile.ui.marketplace

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuralforge.mobile.core.ModelFormat
import com.neuralforge.mobile.core.NeuralForgeEngine
import com.neuralforge.mobile.downloader.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MarketplaceViewModel"

/**
 * ViewModel for Model Marketplace
 */
@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val neuralForgeEngine: NeuralForgeEngine,
    private val downloadManager: ModelDownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState = _uiState.asStateFlow()

    private val marketplaceDataSource = MarketplaceDataSource()

    init {
        loadModels()
    }

    /**
     * Load available models from marketplace
     */
    private fun loadModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Get all available models
                val allModels = marketplaceDataSource.getAvailableModels()

                // Get list of downloaded model names
                val downloadedModels = getDownloadedModelNames()

                // Mark models as downloaded
                val modelsWithStatus = allModels.map { model ->
                    model.copy(isDownloaded = downloadedModels.contains(model.name))
                }

                _uiState.update {
                    it.copy(
                        allModels = modelsWithStatus,
                        filteredModels = modelsWithStatus,
                        isLoading = false
                    )
                }

                Log.d(TAG, "Loaded ${allModels.size} models from marketplace")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load models", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load models: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Get list of downloaded model names
     */
    private fun getDownloadedModelNames(): Set<String> {
        return try {
            val modelDir = downloadManager.getModelDirectory()
            modelDir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get downloaded models", e)
            emptySet()
        }
    }

    /**
     * Update search query
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }

    /**
     * Update format filter
     */
    fun updateFormatFilter(format: ModelFormat?) {
        _uiState.update { it.copy(selectedFormat = format) }
        applyFilters()
    }

    /**
     * Update category filter
     */
    fun updateCategoryFilter(category: ModelCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
        applyFilters()
    }

    /**
     * Update sort option
     */
    fun updateSortOption(sortBy: SortOption) {
        _uiState.update { it.copy(sortBy = sortBy) }
        applyFilters()
    }

    /**
     * Apply all filters and sorting
     */
    private fun applyFilters() {
        val state = _uiState.value
        var filtered = state.allModels

        // Apply search query
        if (state.searchQuery.isNotBlank()) {
            val query = state.searchQuery.lowercase()
            filtered = filtered.filter {
                it.name.lowercase().contains(query) ||
                it.description.lowercase().contains(query)
            }
        }

        // Apply format filter
        state.selectedFormat?.let { format ->
            filtered = filtered.filter { it.format == format }
        }

        // Apply category filter
        state.selectedCategory?.let { category ->
            filtered = filtered.filter { it.category == category }
        }

        // Apply sorting
        filtered = when (state.sortBy) {
            SortOption.POPULAR -> filtered.sortedByDescending { it.downloads }
            SortOption.RECENT -> filtered // Assuming list is already sorted by recent
            SortOption.SIZE_ASC -> filtered.sortedBy { it.size }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.size }
            SortOption.NAME -> filtered.sortedBy { it.name }
        }

        _uiState.update { it.copy(filteredModels = filtered) }
    }

    /**
     * Download a model
     */
    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(downloadingModels = it.downloadingModels + model.id)
            }

            try {
                Log.d(TAG, "Starting download for ${model.name}")

                // In a real implementation, this would use the download manager
                // For now, we'll simulate a download
                // downloadManager.downloadModel(model.url, model.name)

                // TODO: Implement actual download flow
                // For now, just mark as downloaded after a delay (simulation)
                kotlinx.coroutines.delay(2000)

                _uiState.update { state ->
                    state.copy(
                        downloadingModels = state.downloadingModels - model.id,
                        allModels = state.allModels.map {
                            if (it.id == model.id) it.copy(isDownloaded = true) else it
                        }
                    )
                }

                applyFilters()

                Log.d(TAG, "Download completed for ${model.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.name}", e)
                _uiState.update {
                    it.copy(
                        downloadingModels = it.downloadingModels - model.id,
                        error = "Download failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Delete a model
     */
    fun deleteModel(model: ModelInfo) {
        viewModelScope.launch {
            try {
                val modelFile = downloadManager.getModelFile(model.name)
                if (modelFile.exists()) {
                    modelFile.delete()
                    Log.d(TAG, "Deleted model: ${model.name}")
                }

                _uiState.update { state ->
                    state.copy(
                        allModels = state.allModels.map {
                            if (it.id == model.id) it.copy(isDownloaded = false) else it
                        }
                    )
                }

                applyFilters()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete model ${model.name}", e)
                _uiState.update {
                    it.copy(error = "Delete failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Refresh marketplace
     */
    fun refresh() {
        loadModels()
    }
}

/**
 * UI State for Marketplace
 */
data class MarketplaceUiState(
    val allModels: List<ModelInfo> = emptyList(),
    val filteredModels: List<ModelInfo> = emptyList(),
    val searchQuery: String = "",
    val selectedFormat: ModelFormat? = null,
    val selectedCategory: ModelCategory? = null,
    val sortBy: SortOption = SortOption.POPULAR,
    val isLoading: Boolean = false,
    val downloadingModels: Set<String> = emptySet(),
    val error: String? = null
)
