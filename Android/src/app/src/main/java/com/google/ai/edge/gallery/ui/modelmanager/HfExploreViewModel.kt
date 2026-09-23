/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.ui.modelmanager

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGHfExploreVM"
private val SEARCH_DEBOUNCE_DURATION = 300.milliseconds

data class HfExploreUiState(
  val searchQuery: String = "",
  val selectedSort: HfSortOptionProto = HfSortOptionProto.HF_SORT_OPTION_TRENDING,
  val models: List<HfModelItemProto> = emptyList(),
  val isLoading: Boolean = false,
  val isFetchingDetails: Boolean = false,
  val hasError: Boolean = false,
  val selectedModelForDetails: HfModelItemProto? = null,
)

@HiltViewModel
class HfExploreViewModel
@Inject
constructor(
  private val huggingFaceApiClient: HuggingFaceApiClient,
  private val dataStoreRepository: DataStoreRepository,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

  private val _uiState = MutableStateFlow(HfExploreUiState())
  val uiState: StateFlow<HfExploreUiState> = _uiState.asStateFlow()

  private var searchJob: Job? = null

  init {
    refreshModels()
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_DURATION)
      loadModels(query = query, sort = _uiState.value.selectedSort)
    }
  }

  fun onSortOptionSelected(sort: HfSortOptionProto) {
    if (_uiState.value.selectedSort == sort) return
    _uiState.update { it.copy(selectedSort = sort) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      loadModels(query = _uiState.value.searchQuery, sort = sort)
    }
  }

  fun refreshModels() {
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      loadModels(query = _uiState.value.searchQuery, sort = _uiState.value.selectedSort)
    }
  }

  fun selectModelCard(model: HfModelItemProto) {
    if (_uiState.value.isFetchingDetails) return
    viewModelScope.launch {
      _uiState.update { it.copy(isFetchingDetails = true) }
      try {
        val accessToken =
          withContext(ioDispatcher) { dataStoreRepository.readAccessTokenData()?.accessToken }
        val detailed =
          huggingFaceApiClient.getModelDetails(modelId = model.id, accessToken = accessToken)
        val resolved = detailed ?: model
        _uiState.update { it.copy(selectedModelForDetails = resolved) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch model details for ${model.id}", e)
        _uiState.update { it.copy(selectedModelForDetails = model) }
      } finally {
        _uiState.update { it.copy(isFetchingDetails = false) }
      }
    }
  }

  fun onModelDetailsShown() {
    _uiState.update { it.copy(selectedModelForDetails = null) }
  }

  private suspend fun loadModels(query: String, sort: HfSortOptionProto) {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    try {
      val accessToken =
        withContext(ioDispatcher) { dataStoreRepository.readAccessTokenData()?.accessToken }
      val results =
        huggingFaceApiClient.searchModels(query = query, sort = sort, accessToken = accessToken)
      _uiState.update { it.copy(models = results, isLoading = false, hasError = false) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to search Hugging Face models", e)
      _uiState.update { it.copy(isLoading = false, hasError = true) }
    }
  }
}
