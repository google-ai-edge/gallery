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

package com.google.ai.edge.gallery.customtasks.smartalbum

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalResult
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "SmartAlbumSearchVM"

sealed interface SearchUiState {
  data object Empty : SearchUiState

  data object Loading : SearchUiState

  data class Results(val matches: List<MatchedAssetResult>) : SearchUiState
}

data class MatchedAssetResult(val asset: PhotoAsset, val similarity: Float? = null)

data class MatchedAsset(val id: String, val similarity: Float)

/** ViewModel managing search state and query execution for Instant Media Search. */
class SmartAlbumSearchViewModel(
  val photoLibraryService: PhotoLibraryService,
  val semanticRetrievalService: SemanticRetrievalService,
  val mainViewModel: SmartAlbumViewModel? = null,
  val sampleAlbumStorageManager: SampleAlbumStorageManager? =
    mainViewModel?.sampleAlbumStorageManager,
  initialSource: SmartAlbumSource =
    mainViewModel?.activeSource?.value ?: SmartAlbumSource.USER_PHOTOS,
  private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
  private val _activeSource = MutableStateFlow(initialSource)
  val activeSource: StateFlow<SmartAlbumSource> = _activeSource.asStateFlow()

  val isSampleAlbumAvailable: Boolean
    get() = sampleAlbumStorageManager?.isSampleAlbumAvailable ?: false

  private val _searchText = MutableStateFlow("")
  val searchText: StateFlow<String> = _searchText.asStateFlow()

  private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Empty)
  val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

  private val _allAssets = MutableStateFlow<List<PhotoAsset>>(emptyList())
  val allAssets: StateFlow<List<PhotoAsset>> = _allAssets.asStateFlow()

  private val _addAllPhotosCount = MutableStateFlow(0)
  val addAllPhotosCount: StateFlow<Int> = _addAllPhotosCount.asStateFlow()

  private val _isLoadingAssets = MutableStateFlow(true)
  val isLoadingAssets: StateFlow<Boolean> = _isLoadingAssets.asStateFlow()

  private val _selectedImageAsset = MutableStateFlow<PhotoAsset?>(null)
  val selectedImageAsset: StateFlow<PhotoAsset?> = _selectedImageAsset.asStateFlow()

  private val _selectedPhotoIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedPhotoIds: StateFlow<Set<String>> = _selectedPhotoIds.asStateFlow()

  private val _isSelectionMode = MutableStateFlow(false)
  val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

  private val _isLiveCameraSearchActive = MutableStateFlow(false)
  val isLiveCameraSearchActive: StateFlow<Boolean> = _isLiveCameraSearchActive.asStateFlow()

  private val _isCameraSearchPaused = MutableStateFlow(false)
  val isCameraSearchPaused: StateFlow<Boolean> = _isCameraSearchPaused.asStateFlow()

  private val _cameraSearchResults = MutableStateFlow<List<MatchedAssetResult>>(emptyList())
  val cameraSearchResults: StateFlow<List<MatchedAssetResult>> = _cameraSearchResults.asStateFlow()

  private val _isCameraSearching = MutableStateFlow(false)
  val isCameraSearching: StateFlow<Boolean> = _isCameraSearching.asStateFlow()

  private val _isSearching = MutableStateFlow(false)
  val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

  private val _cameraSearchedBitmap = MutableStateFlow<Bitmap?>(null)
  val cameraSearchedBitmap: StateFlow<Bitmap?> = _cameraSearchedBitmap.asStateFlow()

  private var searchJob: Job? = null
  private var cameraSearchJob: Job? = null

  val currentRetrievalService: SemanticRetrievalService
    get() =
      mainViewModel?.semanticRetrievalServiceProvider?.service(_activeSource.value)
        ?: semanticRetrievalService

  val indexedIds: StateFlow<Set<String>> =
    _activeSource
      .flatMapLatest { source ->
        val service =
          mainViewModel?.semanticRetrievalServiceProvider?.service(source)
            ?: semanticRetrievalService
        service.indexedIds
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = currentRetrievalService.indexedIds.value,
      )

  private val _fallbackIndexingProgress = MutableStateFlow(IndexingProgressUpdate())
  val currentIndexingProgress: StateFlow<IndexingProgressUpdate>
    get() =
      if (mainViewModel != null) {
        mainViewModel.indexingProgress(_activeSource.value)
      } else {
        _fallbackIndexingProgress.asStateFlow()
      }

  val totalIndexingProgress: StateFlow<IndexingProgressUpdate>
    get() = mainViewModel?.totalIndexingProgress ?: _fallbackIndexingProgress.asStateFlow()

  private val _fallbackIsIndexingPaused = MutableStateFlow(false)
  val isIndexingPaused: StateFlow<Boolean>
    get() =
      if (mainViewModel != null) {
        mainViewModel.isIndexingPaused(_activeSource.value)
      } else {
        _fallbackIsIndexingPaused.asStateFlow()
      }

  val isAllIndexingPaused: StateFlow<Boolean>
    get() = mainViewModel?.isAllIndexingPaused ?: _fallbackIsIndexingPaused.asStateFlow()

  private val _fallbackIsNotificationRequested = MutableStateFlow(false)
  val isNotificationRequested: StateFlow<Boolean> =
    mainViewModel?.isNotificationRequested ?: _fallbackIsNotificationRequested.asStateFlow()

  init {
    viewModelScope.launch { loadAssetsForCurrentSource() }
    viewModelScope.launch {
      combine(_activeSource, _allAssets) { _, _ -> }.collect { updateAddAllPhotosCount() }
    }
    if (mainViewModel == null) {
      viewModelScope.launch {
        indexedIds.collect { ids ->
          val current = _fallbackIndexingProgress.value
          val processed = ids.size
          val total = maxOf(_allAssets.value.size, current.totalCount, processed)
          _fallbackIndexingProgress.value =
            IndexingProgressUpdate(
              processedCount = processed,
              totalCount = total,
              recentIndexedIds = ids.toList(),
              isInitializing = processed < total && current.isInitializing,
            )
        }
      }
    }
  }

  fun enterSelectionMode(assetId: String? = null) {
    _isSelectionMode.value = true
    _selectedPhotoIds.value = if (assetId != null) setOf(assetId) else emptySet()
  }

  fun togglePhotoSelection(assetId: String) {
    if (!_isSelectionMode.value) {
      enterSelectionMode(assetId)
      return
    }
    val current = _selectedPhotoIds.value
    if (assetId in current) {
      _selectedPhotoIds.value = current - assetId
    } else {
      _selectedPhotoIds.value = current + assetId
    }
  }

  fun selectAll() {
    _isSelectionMode.value = true
    _selectedPhotoIds.value = _allAssets.value.map { it.id }.toSet()
  }

  fun deselectAll() {
    _selectedPhotoIds.value = emptySet()
  }

  fun exitSelectionMode() {
    _isSelectionMode.value = false
    _selectedPhotoIds.value = emptySet()
  }

  fun removeSelectedPhotos() {
    val idsToRemove = _selectedPhotoIds.value
    if (idsToRemove.isEmpty()) return

    // Immediately update in-memory state so UI updates without lag
    _allAssets.value = _allAssets.value.filterNot { it.id in idsToRemove }
    val currentUiState = _uiState.value
    if (currentUiState is SearchUiState.Results) {
      _uiState.value =
        SearchUiState.Results(currentUiState.matches.filterNot { it.asset.id in idsToRemove })
    }
    exitSelectionMode()

    viewModelScope.launch {
      if (mainViewModel != null) {
        mainViewModel.removeAssetsFromCurrentSource(idsToRemove)
      } else {
        for (id in idsToRemove) {
          currentRetrievalService.deleteRecord(id)
        }
        currentRetrievalService.saveToDisk()

        if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
          sampleAlbumStorageManager?.removeSampleAssets(idsToRemove)
        } else {
          photoLibraryService.removeAssets(idsToRemove)
        }
      }

      loadAssetsForCurrentSource()
    }
  }

  fun removeAllPhotos() {
    exitSelectionMode()
    clearSearch()
    _allAssets.value = emptyList()
    viewModelScope.launch {
      if (mainViewModel != null) {
        mainViewModel.removeAllPhotos()
      } else {
        currentRetrievalService.deleteAllRecords()
        currentRetrievalService.saveToDisk()
        if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
          sampleAlbumStorageManager?.deleteSamplePhotos()
        } else {
          val allIds = photoLibraryService.fetchAllAssetIdentifiers()
          photoLibraryService.removeAssets(allIds.toSet())
        }
      }
      loadAssetsForCurrentSource()
    }
  }

  fun addCustomPhotos(uris: List<Uri>, onComplete: (List<PhotoAsset>) -> Unit = {}) {
    viewModelScope.launch {
      if (mainViewModel != null) {
        val newAssets = mainViewModel.addCustomPhotos(uris)
        _allAssets.value =
          photoLibraryService.fetchAllAssets().ifEmpty {
            val newIds = newAssets.map { it.id }.toSet()
            newAssets + _allAssets.value.filterNot { it.id in newIds }
          }
        updateAddAllPhotosCount()
        onComplete(newAssets)
      } else {
        val newAssets = uris.map { uri -> photoLibraryService.createAssetFromUri(uri) }
        photoLibraryService.addCustomAssets(newAssets)
        _allAssets.value =
          photoLibraryService.fetchAllAssets().ifEmpty {
            val newIds = newAssets.map { it.id }.toSet()
            newAssets + _allAssets.value.filterNot { it.id in newIds }
          }
        updateAddAllPhotosCount()
        onComplete(newAssets)
        indexFallbackAssets(newAssets, "smartalbum_add_custom_photos_error")
      }
    }
  }

  private suspend fun indexFallbackAssets(assetsToIndex: List<PhotoAsset>, errorEvent: String) {
    if (assetsToIndex.isEmpty()) return
    val isPaused = isIndexingPaused.value
    val current = _fallbackIndexingProgress.value
    val total = maxOf(_allAssets.value.size, current.processedCount + assetsToIndex.size)
    _fallbackIndexingProgress.value =
      current.copy(totalCount = total, isInitializing = !isPaused && current.processedCount < total)
    if (!isPaused) {
      for (asset in assetsToIndex) {
        try {
          if (asset.isVideo) {
            val keyframes = photoLibraryService.loadKeyframes(asset, targetDimension = 1024)
            if (keyframes.isNotEmpty()) {
              val imageDataList = keyframes.map { ImageUtils.encodeTga(it, defaultDispatcher) }
              currentRetrievalService.addRecord(
                id = asset.id,
                imageDataList = imageDataList,
                metadata =
                  mapOf(
                    "displayName" to asset.displayName,
                    "locationName" to (asset.locationName ?: ""),
                    "isVideo" to asset.isVideo.toString(),
                  ),
              )
            } else {
              val bitmap = photoLibraryService.loadBitmap(asset, 512)
              if (bitmap != null) {
                val imageData = ImageUtils.encodeTga(bitmap, defaultDispatcher)
                currentRetrievalService.addRecord(
                  id = asset.id,
                  imageDataList = listOf(imageData),
                  metadata =
                    mapOf(
                      "displayName" to asset.displayName,
                      "locationName" to (asset.locationName ?: ""),
                      "isVideo" to asset.isVideo.toString(),
                    ),
                )
              }
            }
          } else {
            val bitmap = photoLibraryService.loadBitmap(asset, 512)
            if (bitmap != null) {
              val imageData = ImageUtils.encodeTga(bitmap, defaultDispatcher)
              currentRetrievalService.addRecord(asset.id, imageData)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to index added media ${asset.id}", e)
          logErrorToFirebase(GalleryEvent.GENERATE_ACTION, errorEvent, e.message)
        }
      }
      currentRetrievalService.saveToDisk()
    }
  }

  fun addAllPermittedPhotos() {
    exitSelectionMode()
    viewModelScope.launch {
      photoLibraryService.setFullLibraryAccessEnabled(true)
      photoLibraryService.clearRemovedAssets()
      if (mainViewModel != null) {
        mainViewModel.confirmAnalyzeAllPhotos()
        loadAssetsForCurrentSource()
      } else {
        loadAssetsForCurrentSource()
        val unindexedAssets =
          _allAssets.value.filterNot { it.id in currentRetrievalService.indexedIds.value }
        indexFallbackAssets(unindexedAssets, "smartalbum_add_all_permitted_photos_error")
      }
    }
  }

  suspend fun refreshAddAllPhotosCount() {
    updateAddAllPhotosCount()
  }

  private suspend fun updateAddAllPhotosCount() {
    if (_activeSource.value != SmartAlbumSource.USER_PHOTOS) {
      _addAllPhotosCount.value = 0
      return
    }
    _addAllPhotosCount.value = photoLibraryService.fetchPermittedMediaStoreCountIfUnadded()
  }

  fun selectSource(newSource: SmartAlbumSource) {
    if (_activeSource.value == newSource && _allAssets.value.isNotEmpty()) return
    exitSelectionMode()
    _activeSource.value = newSource
    mainViewModel?.selectSource(newSource)
    clearSearch()
    clearSelectedImage()
    _cameraSearchResults.value = emptyList()
    viewModelScope.launch { loadAssetsForCurrentSource() }
  }

  fun refreshAssets() {
    viewModelScope.launch { loadAssetsForCurrentSource() }
  }

  private suspend fun loadAssetsForCurrentSource() {
    _isLoadingAssets.value = true
    try {
      if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
        val sampleItems = sampleAlbumStorageManager?.fetchSampleAssets() ?: emptyList()
        _allAssets.value = sampleItems
      } else {
        _allAssets.value = photoLibraryService.fetchAllAssets()
      }
      updateAddAllPhotosCount()
      if (mainViewModel == null) {
        val currentIds = indexedIds.value
        val total = _allAssets.value.size
        val processed = minOf(currentIds.size, total)
        _fallbackIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = processed,
            totalCount = total,
            recentIndexedIds = currentIds.toList(),
            isInitializing = processed < total,
          )
      }
    } finally {
      _isLoadingAssets.value = false
    }
  }

  fun openLiveCameraSearch() {
    exitSelectionMode()
    _isLiveCameraSearchActive.value = true
    _isCameraSearchPaused.value = false
    _cameraSearchResults.value = emptyList()
    _isCameraSearching.value = false
    _cameraSearchedBitmap.value = null
  }

  fun closeLiveCameraSearch() {
    cameraSearchJob?.cancel()
    _isLiveCameraSearchActive.value = false
    _isCameraSearchPaused.value = false
    _cameraSearchResults.value = emptyList()
    _isCameraSearching.value = false
    _cameraSearchedBitmap.value = null
  }

  fun toggleCameraSearchPause() {
    _isCameraSearchPaused.value = !_isCameraSearchPaused.value
  }

  fun processCameraFrame(bitmap: Bitmap) {
    if (_isCameraSearchPaused.value || !_isLiveCameraSearchActive.value) {
      return
    }
    if (!_isCameraSearching.compareAndSet(false, true)) {
      return
    }
    _cameraSearchedBitmap.value = bitmap
    cameraSearchJob = viewModelScope.launch {
      try {
        val imageData = ImageUtils.encodeTga(bitmap, defaultDispatcher)
        val results = currentRetrievalService.retrieveEntities(imageData, limit = 100)
        val currentList = _allAssets.value
        val assetMap = currentList.associateBy { it.id }
        val matched =
          results
            .mapNotNull { res ->
              val asset =
                assetMap[res.id]
                  ?: if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
                    sampleAlbumStorageManager?.fetchAsset(res.id)
                  } else {
                    photoLibraryService.fetchAsset(res.id)
                  }
                  ?: return@mapNotNull null
              MatchedAssetResult(asset = asset, similarity = res.similarityScore ?: 0f)
            }
            .distinctBy { it.asset.id }
            .sortedByDescending { it.similarity ?: 0f }
        _cameraSearchResults.value = matched
      } catch (e: CancellationException) {
        throw e // Allow standard cooperative cancellation to do its job
      } catch (e: Exception) {
        Log.e(TAG, "Failed camera search inference", e)
        logErrorToFirebase(
          GalleryEvent.GENERATE_ACTION,
          "smartalbum_camera_search_error",
          e.message,
        )
      } finally {
        _isCameraSearching.value = false
      }
    }
  }

  fun setSelectedImage(asset: PhotoAsset) {
    _selectedImageAsset.value = asset
    searchSimilarImages(asset)
  }

  fun selectImageFromUri(uri: Uri, displayName: String = uri.lastPathSegment ?: "Photo") {
    val tempAsset =
      PhotoAsset(
        id = uri.toString(),
        contentUri = uri,
        dateTaken = System.currentTimeMillis(),
        displayName = displayName,
      )
    setSelectedImage(tempAsset)
  }

  fun clearSelectedImage() {
    _selectedImageAsset.value = null
    clearSearch()
  }

  fun requestIndexingNotification() {
    _fallbackIsNotificationRequested.value = true
    mainViewModel?.requestIndexingNotification()
  }

  fun cancelIndexingNotification() {
    _fallbackIsNotificationRequested.value = false
    mainViewModel?.cancelIndexingNotification()
  }

  fun pauseIndexing() {
    _fallbackIsIndexingPaused.value = true
    _fallbackIndexingProgress.value = _fallbackIndexingProgress.value.copy(isInitializing = false)
    mainViewModel?.pauseAllIndexing()
  }

  fun resumeIndexing() {
    _fallbackIsIndexingPaused.value = false
    if (
      _fallbackIndexingProgress.value.processedCount < _fallbackIndexingProgress.value.totalCount
    ) {
      _fallbackIndexingProgress.value = _fallbackIndexingProgress.value.copy(isInitializing = true)
    }
    mainViewModel?.resumeIndexing(_activeSource.value)
  }

  fun pauseAllIndexing() {
    _fallbackIsIndexingPaused.value = true
    _fallbackIndexingProgress.value = _fallbackIndexingProgress.value.copy(isInitializing = false)
    mainViewModel?.pauseAllIndexing()
  }

  fun resumeAllIndexing() {
    _fallbackIsIndexingPaused.value = false
    if (
      _fallbackIndexingProgress.value.processedCount < _fallbackIndexingProgress.value.totalCount
    ) {
      _fallbackIndexingProgress.value = _fallbackIndexingProgress.value.copy(isInitializing = true)
    }
    mainViewModel?.resumeAllIndexing()
  }

  fun clearIndexAndRestart(mainViewModel: SmartAlbumViewModel? = null) {
    _fallbackIsIndexingPaused.value = false
    viewModelScope.launch {
      exitSelectionMode()
      if (mainViewModel != null) {
        mainViewModel.clearAndRestartIndexing()
      } else {
        currentRetrievalService.deleteAllRecords()
      }
      clearSearch()
      clearSelectedImage()
      _cameraSearchResults.value = emptyList()
      if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
        _allAssets.value = sampleAlbumStorageManager?.fetchSampleAssets() ?: emptyList()
      } else {
        val allIds = photoLibraryService.fetchAllAssetIdentifiers()
        _allAssets.value = photoLibraryService.fetchAssets(allIds)
      }
      if (mainViewModel == null) {
        _fallbackIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = 0,
            totalCount = _allAssets.value.size,
            isInitializing = _allAssets.value.isNotEmpty(),
          )
      }
    }
  }

  fun onSearchTextChanged(newText: String, immediate: Boolean = false) {
    exitSelectionMode()
    _searchText.value = newText
    searchJob?.cancel()

    if (newText.isBlank()) {
      _isSearching.value = false
      _uiState.value = SearchUiState.Empty
      return
    }

    if (_uiState.value !is SearchUiState.Results) {
      _uiState.value = SearchUiState.Loading
    }
    _isSearching.value = true
    searchJob = viewModelScope.launch {
      try {
        if (!immediate) {
          delay(300)
        }
        executeTextSearch(newText.trim())
      } finally {
        _isSearching.value = false
      }
    }
  }

  fun searchSimilarImages(asset: PhotoAsset) {
    exitSelectionMode()
    _selectedImageAsset.value = asset
    _searchText.value = ""
    searchJob?.cancel()
    if (_uiState.value !is SearchUiState.Results) {
      _uiState.value = SearchUiState.Loading
    }
    _isSearching.value = true

    firebaseAnalytics?.logEvent(
      GalleryEvent.GENERATE_ACTION.id,
      Bundle().apply {
        putString("capability_name", BuiltInTaskId.SMART_ALBUM)
        putString("action", "search_similar_image")
      },
    )

    searchJob = viewModelScope.launch {
      try {
        val bitmap =
          if (
            _activeSource.value == SmartAlbumSource.SAMPLE_ALBUM &&
              sampleAlbumStorageManager != null
          ) {
            sampleAlbumStorageManager.loadBitmap(asset, targetDimension = 1024)
              ?: photoLibraryService.loadBitmap(asset, targetDimension = 1024)
          } else {
            photoLibraryService.loadBitmap(asset, targetDimension = 1024)
          }
        if (bitmap == null) {
          _uiState.value = SearchUiState.Results(emptyList())
          return@launch
        }
        val imageData = ImageUtils.encodeTga(bitmap, defaultDispatcher)
        val results =
          currentRetrievalService.retrieveEntities(imageData, seedId = asset.id, limit = 1000)
        updateSearchResults(results)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to search similar images", e)
        logErrorToFirebase(
          GalleryEvent.GENERATE_ACTION,
          "smartalbum_similar_image_search_error",
          e.message,
        )
        if (_uiState.value !is SearchUiState.Results) {
          _uiState.value = SearchUiState.Results(emptyList())
        }
      } finally {
        _isSearching.value = false
      }
    }
  }

  fun clearSearch() {
    searchJob?.cancel()
    _isSearching.value = false
    _searchText.value = ""
    _uiState.value = SearchUiState.Empty
  }

  private suspend fun executeTextSearch(query: String) {
    firebaseAnalytics?.logEvent(
      GalleryEvent.GENERATE_ACTION.id,
      Bundle().apply {
        putString("capability_name", BuiltInTaskId.SMART_ALBUM)
        putString("action", "search")
        putInt("query_length", query.trim().length)
      },
    )
    try {
      val results = currentRetrievalService.retrieveEntities(query, limit = 1000)
      updateSearchResults(results)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logErrorToFirebase(GalleryEvent.GENERATE_ACTION, "smartalbum_text_search_error", e.message)
      if (_uiState.value !is SearchUiState.Results) {
        _uiState.value = SearchUiState.Results(emptyList())
      }
    }
  }

  private suspend fun updateSearchResults(results: List<SemanticRetrievalResult>) {
    val currentList = _allAssets.value
    val assetMap = currentList.associateBy { it.id }
    val currentMatches = (_uiState.value as? SearchUiState.Results)?.matches ?: emptyList()

    val matched =
      if (currentMatches.isNotEmpty()) {
        val scoreMap = results.associate { it.id to (it.similarityScore ?: 0f) }
        val currentIds = currentMatches.map { it.asset.id }.toSet()
        val updatedExisting = currentMatches.map { current ->
          val newScore = scoreMap[current.asset.id] ?: 0f
          current.copy(similarity = newScore)
        }
        val newMatches =
          results
            .filterNot { it.id in currentIds }
            .mapNotNull { res ->
              val itemAsset =
                assetMap[res.id]
                  ?: if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
                    sampleAlbumStorageManager?.fetchAsset(res.id)
                  } else {
                    photoLibraryService.fetchAsset(res.id)
                  }
                  ?: return@mapNotNull null
              MatchedAssetResult(asset = itemAsset, similarity = res.similarityScore ?: 0f)
            }
        (updatedExisting + newMatches)
          .distinctBy { it.asset.id }
          .sortedByDescending { it.similarity ?: 0f }
      } else {
        results
          .mapNotNull { res ->
            val itemAsset =
              assetMap[res.id]
                ?: if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
                  sampleAlbumStorageManager?.fetchAsset(res.id)
                } else {
                  photoLibraryService.fetchAsset(res.id)
                }
                ?: return@mapNotNull null
            MatchedAssetResult(asset = itemAsset, similarity = res.similarityScore ?: 0f)
          }
          .distinctBy { it.asset.id }
          .sortedByDescending { it.similarity ?: 0f }
      }

    _uiState.value = SearchUiState.Results(matched)
  }
}
