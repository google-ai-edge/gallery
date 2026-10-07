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

import android.R as AndroidR
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.services.photolibrary.DefaultPhotoLibraryService
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@VisibleForTesting
internal fun selectActiveWorkInfo(workInfos: List<WorkInfo>): WorkInfo? {
  for (info in workInfos) {
    if (!info.state.isFinished) {
      return info
    }
  }
  return workInfos.firstOrNull()
}

data class SampleDownloadProgress(
  val isDownloading: Boolean = false,
  val receivedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val errorMessage: String? = null,
)

open class SmartAlbumViewModel(
  val photoLibraryService: PhotoLibraryService,
  val semanticRetrievalService: SemanticRetrievalService,
  val sampleAlbumStorageManager: SampleAlbumStorageManager? = null,
  val semanticRetrievalServiceProvider: SemanticRetrievalServiceProvider? = null,
  private val application: Application? = null,
  val model: Model? = null,
  val maxInputSequenceLength: Int = DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
  val visionTokenBudget: Int = DEFAULT_VISION_TOKEN_BUDGET,
  private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  private val _userIndexingProgress = MutableStateFlow(IndexingProgressUpdate())
  val indexingProgress: StateFlow<IndexingProgressUpdate> = _userIndexingProgress.asStateFlow()
  private val _recentAssets = MutableStateFlow<List<PhotoAsset>>(emptyList())
  val recentAssets: StateFlow<List<PhotoAsset>> = _recentAssets.asStateFlow()

  private val _sampleIndexingProgress = MutableStateFlow(IndexingProgressUpdate())
  val sampleIndexingProgress: StateFlow<IndexingProgressUpdate> =
    _sampleIndexingProgress.asStateFlow()
  private val _recentSampleAssets = MutableStateFlow<List<PhotoAsset>>(emptyList())
  val recentSampleAssets: StateFlow<List<PhotoAsset>> = _recentSampleAssets.asStateFlow()

  private val _sampleAssets = MutableStateFlow<List<PhotoAsset>>(emptyList())
  val sampleAssets: StateFlow<List<PhotoAsset>> = _sampleAssets.asStateFlow()

  private val _sampleDownloadProgress = MutableStateFlow(SampleDownloadProgress())
  val sampleDownloadProgress: StateFlow<SampleDownloadProgress> =
    _sampleDownloadProgress.asStateFlow()

  private var sampleDownloadJob: Job? = null

  val totalIndexingProgress: StateFlow<IndexingProgressUpdate> =
    combine(indexingProgress, sampleIndexingProgress) { userProg, sampleProg ->
        val userTotal = userProg.totalCount
        val sampleTotal = sampleProg.totalCount
        val userProcessed = userProg.processedCount
        val sampleProcessed = sampleProg.processedCount
        val total = userTotal + sampleTotal
        val processed = userProcessed + sampleProcessed
        val combinedRecentIds = (userProg.recentIndexedIds + sampleProg.recentIndexedIds).distinct()
        val isInitializing =
          (userProg.isInitializing && userProcessed < userTotal) ||
            (sampleProg.isInitializing && sampleProcessed < sampleTotal)
        IndexingProgressUpdate(
          processedCount = processed,
          totalCount = total,
          recentIndexedIds = combinedRecentIds,
          isInitializing = isInitializing,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = IndexingProgressUpdate(),
      )

  private val _activeSource = MutableStateFlow(loadInitialSource())
  val activeSource: StateFlow<SmartAlbumSource> = _activeSource.asStateFlow()

  val isSampleAlbumAvailable: Boolean
    get() =
      (sampleAlbumStorageManager?.isSampleAlbumAvailable ?: false) ||
        _sampleAssets.value.isNotEmpty()

  private val _isNotificationRequested = MutableStateFlow(false)
  val isNotificationRequested: StateFlow<Boolean> = _isNotificationRequested.asStateFlow()

  private val _isUserIndexingPaused = MutableStateFlow(false)
  val isUserIndexingPaused: StateFlow<Boolean> = _isUserIndexingPaused.asStateFlow()

  private val _isSampleIndexingPaused = MutableStateFlow(false)
  val isSampleIndexingPaused: StateFlow<Boolean> = _isSampleIndexingPaused.asStateFlow()

  private val _showAnalyzeAllConfirmSheet = MutableStateFlow(false)
  val showAnalyzeAllConfirmSheet: StateFlow<Boolean> = _showAnalyzeAllConfirmSheet.asStateFlow()

  private val _photosToAnalyzeCount = MutableStateFlow(0)
  val photosToAnalyzeCount: StateFlow<Int> = _photosToAnalyzeCount.asStateFlow()

  fun isIndexingPaused(source: SmartAlbumSource): StateFlow<Boolean> =
    when (source) {
      SmartAlbumSource.USER_PHOTOS -> isUserIndexingPaused
      SmartAlbumSource.SAMPLE_ALBUM -> isSampleIndexingPaused
    }

  val isIndexingPaused: StateFlow<Boolean>
    get() = isIndexingPaused(_activeSource.value)

  val isAllIndexingPaused: StateFlow<Boolean> =
    combine(
        _isUserIndexingPaused,
        _isSampleIndexingPaused,
        indexingProgress,
        sampleIndexingProgress,
      ) { userPaused, samplePaused, userProg, sampleProg ->
        val userIncomplete =
          userProg.totalCount > 0 && userProg.processedCount < userProg.totalCount
        val sampleIncomplete =
          sampleProg.totalCount > 0 && sampleProg.processedCount < sampleProg.totalCount
        when {
          userIncomplete && sampleIncomplete -> userPaused && samplePaused
          userIncomplete -> userPaused
          sampleIncomplete -> samplePaused
          else -> userPaused && samplePaused
        }
      }
      .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = false)

  fun indexingProgress(source: SmartAlbumSource): StateFlow<IndexingProgressUpdate> =
    when (source) {
      SmartAlbumSource.USER_PHOTOS -> indexingProgress
      SmartAlbumSource.SAMPLE_ALBUM -> sampleIndexingProgress
    }

  init {
    loadInitialCounts()
    loadUserAssets()
    loadSampleAssets()
    observeIndexedIdsFallback()
    observeWorkManagerProgress()
    observeProgress()
    observeSampleProgress()
    observeTotalProgressForNotifications()
  }

  private fun loadUserAssets() {
    viewModelScope.launch {
      val ids = photoLibraryService.fetchAllAssetIdentifiers().take(5)
      val assets = photoLibraryService.fetchAssets(ids)
      _recentAssets.value = assets
    }
  }

  private fun loadInitialCounts() {
    viewModelScope.launch {
      val userIds = photoLibraryService.fetchAllAssetIdentifiers()
      val userIndexed = semanticRetrievalService.fetchRecordIdentifiers()
      val userTotal = userIds.size
      val userProcessed = userIds.count { it in userIndexed }
      _userIndexingProgress.value =
        IndexingProgressUpdate(
          processedCount = userProcessed,
          totalCount = userTotal,
          recentIndexedIds = emptyList(),
          isInitializing = userProcessed < userTotal && !_isUserIndexingPaused.value,
        )

      if (sampleAlbumStorageManager != null) {
        val sampleItems = sampleAlbumStorageManager.fetchSampleAssets()
        val sampleRetrieval =
          semanticRetrievalServiceProvider?.sampleSemanticRetrievalService
            ?: semanticRetrievalService
        val sampleIndexed = sampleRetrieval.fetchRecordIdentifiers()
        val sampleTotal = sampleItems.size
        _sampleIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = minOf(sampleIndexed.size, sampleTotal),
            totalCount = sampleTotal,
            recentIndexedIds = emptyList(),
            isInitializing = sampleIndexed.size < sampleTotal && !_isSampleIndexingPaused.value,
          )
      }
      triggerPrioritizedIndexing()
    }
  }

  fun refreshUserPhotos() {
    loadInitialCounts()
    loadUserAssets()
  }

  fun refreshSamplePhotos() {
    sampleAlbumStorageManager?.invalidateCachedAssets()
    loadSampleAssets()
    loadInitialCounts()
  }

  private fun observeIndexedIdsFallback() {
    if (application != null) return
    viewModelScope.launch {
      semanticRetrievalService.indexedIds.collect { ids ->
        val total = photoLibraryService.fetchAllAssetIdentifiers().size
        _userIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = minOf(ids.size, total),
            totalCount = total,
            recentIndexedIds = emptyList(),
            isInitializing = ids.size < total && _userIndexingProgress.value.isInitializing,
          )
      }
    }
    if (semanticRetrievalServiceProvider != null) {
      viewModelScope.launch {
        semanticRetrievalServiceProvider.sampleSemanticRetrievalService.indexedIds.collect { ids ->
          val total = sampleAlbumStorageManager?.fetchSampleAssets()?.size ?: 0
          _sampleIndexingProgress.value =
            IndexingProgressUpdate(
              processedCount = minOf(ids.size, total),
              totalCount = total,
              recentIndexedIds = emptyList(),
              isInitializing = ids.size < total && _sampleIndexingProgress.value.isInitializing,
            )
        }
      }
    }
  }

  private fun observeWorkManagerProgress() {
    val app = application ?: return
    try {
      val workManager = WorkManager.getInstance(app)
      viewModelScope.launch {
        workManager
          .getWorkInfosForUniqueWorkFlow(
            "${SmartAlbumIndexingWorker.UNIQUE_WORK_NAME_PREFIX}${SmartAlbumSource.USER_PHOTOS.rawValue}"
          )
          .collect { workInfos ->
            val workInfo = selectActiveWorkInfo(workInfos) ?: return@collect
            val processed =
              workInfo.progress.getInt(SmartAlbumIndexingWorker.PROGRESS_KEY_PROCESSED, -1)
            val total = workInfo.progress.getInt(SmartAlbumIndexingWorker.PROGRESS_KEY_TOTAL, -1)
            val isInitializing =
              workInfo.progress.getBoolean(
                SmartAlbumIndexingWorker.PROGRESS_KEY_IS_INITIALIZING,
                false,
              )
            val recentArray =
              workInfo.progress.getNullableStringArray(
                SmartAlbumIndexingWorker.PROGRESS_KEY_RECENT_IDS
              )
            recentArray?.filterNotNull()?.let { newIds ->
              if (newIds.isNotEmpty()) {
                semanticRetrievalService.addIndexedIds(newIds)
              }
            }
            if (total >= 0 && processed >= 0) {
              val current = _userIndexingProgress.value
              _userIndexingProgress.value =
                IndexingProgressUpdate(
                  processedCount = maxOf(processed, current.processedCount),
                  totalCount = maxOf(total, current.totalCount),
                  recentIndexedIds =
                    recentArray?.filterNotNull()?.takeIf { it.isNotEmpty() }
                      ?: current.recentIndexedIds,
                  isInitializing = isInitializing && !workInfo.state.isFinished && processed < total,
                )
            } else if (isInitializing && !workInfo.state.isFinished) {
              _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = true)
            }
            if (workInfo.state.isFinished) {
              semanticRetrievalService.loadFromDatabase()
              _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = false)
              if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                triggerPrioritizedIndexing()
              }
            }
          }
      }
      viewModelScope.launch {
        workManager
          .getWorkInfosForUniqueWorkFlow(
            "${SmartAlbumIndexingWorker.UNIQUE_WORK_NAME_PREFIX}${SmartAlbumSource.SAMPLE_ALBUM.rawValue}"
          )
          .collect { workInfos ->
            val workInfo = selectActiveWorkInfo(workInfos) ?: return@collect
            val processed =
              workInfo.progress.getInt(SmartAlbumIndexingWorker.PROGRESS_KEY_PROCESSED, -1)
            val total = workInfo.progress.getInt(SmartAlbumIndexingWorker.PROGRESS_KEY_TOTAL, -1)
            val isInitializing =
              workInfo.progress.getBoolean(
                SmartAlbumIndexingWorker.PROGRESS_KEY_IS_INITIALIZING,
                false,
              )
            val recentArray =
              workInfo.progress.getNullableStringArray(
                SmartAlbumIndexingWorker.PROGRESS_KEY_RECENT_IDS
              )
            recentArray?.filterNotNull()?.let { newIds ->
              if (newIds.isNotEmpty()) {
                val sampleRetrieval =
                  semanticRetrievalServiceProvider?.sampleSemanticRetrievalService
                    ?: semanticRetrievalService
                sampleRetrieval.addIndexedIds(newIds)
              }
            }
            if (total >= 0 && processed >= 0) {
              val current = _sampleIndexingProgress.value
              _sampleIndexingProgress.value =
                IndexingProgressUpdate(
                  processedCount = maxOf(processed, current.processedCount),
                  totalCount = maxOf(total, current.totalCount),
                  recentIndexedIds =
                    recentArray?.filterNotNull()?.takeIf { it.isNotEmpty() }
                      ?: current.recentIndexedIds,
                  isInitializing = isInitializing && !workInfo.state.isFinished && processed < total,
                )
            } else if (isInitializing && !workInfo.state.isFinished) {
              _sampleIndexingProgress.value =
                _sampleIndexingProgress.value.copy(isInitializing = true)
            }
            if (workInfo.state.isFinished) {
              val sampleRetrieval =
                semanticRetrievalServiceProvider?.sampleSemanticRetrievalService
                  ?: semanticRetrievalService
              sampleRetrieval.loadFromDatabase()
              _sampleIndexingProgress.value =
                _sampleIndexingProgress.value.copy(isInitializing = false)
              if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                triggerPrioritizedIndexing()
              }
            }
          }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to observe WorkManager progress: ${e.message}")
    }
  }

  private fun loadInitialSource(): SmartAlbumSource {
    val app = application ?: return SmartAlbumSource.USER_PHOTOS
    val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val rawValue =
      prefs.getString(KEY_LAST_OPENED_SOURCE, null) ?: return SmartAlbumSource.USER_PHOTOS
    return if (rawValue == SmartAlbumSource.SAMPLE_ALBUM.rawValue) {
      SmartAlbumSource.SAMPLE_ALBUM
    } else {
      SmartAlbumSource.USER_PHOTOS
    }
  }

  fun selectSource(source: SmartAlbumSource) {
    if (source == SmartAlbumSource.USER_PHOTOS) {
      refreshUserPhotos()
    }
    val sourceChanged = _activeSource.value != source
    _activeSource.value = source
    if (sourceChanged) {
      persistLastOpenedSource(source)
    }
    triggerPrioritizedIndexing()
  }

  private fun persistLastOpenedSource(source: SmartAlbumSource) {
    val app = application ?: return
    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
      putString(KEY_LAST_OPENED_SOURCE, source.rawValue)
    }
  }

  fun onPermissionResult(
    isGranted: Boolean,
    isFullAccess: Boolean,
    onPermissionGranted: () -> Unit = {},
  ) {
    if (isFullAccess) {
      viewModelScope.launch {
        photoLibraryService.setFullLibraryAccessEnabled(false)
        var total = 0
        for (attempt in 0 until 10) {
          try {
            total = photoLibraryService.fetchMediaStoreAssetCount()
            if (total > 0) break
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch MediaStore asset count", e)
          }
          delay(100)
        }
        if (total == 0) {
          photoLibraryService.setFullLibraryAccessEnabled(true)
          photoLibraryService.clearRemovedAssets()
          refreshUserPhotos()
          selectSource(SmartAlbumSource.USER_PHOTOS)
          onPermissionGranted()
        } else {
          _photosToAnalyzeCount.value = total
          _showAnalyzeAllConfirmSheet.value = true
        }
      }
    } else {
      viewModelScope.launch {
        photoLibraryService.setFullLibraryAccessEnabled(true)
        if (isGranted) {
          photoLibraryService.clearRemovedAssets()
          refreshUserPhotos()
          selectSource(SmartAlbumSource.USER_PHOTOS)
          onPermissionGranted()
        }
      }
    }
  }

  open fun confirmAnalyzeAllPhotos(selectedCount: Int = Int.MAX_VALUE) {
    _showAnalyzeAllConfirmSheet.value = false
    if (selectedCount <= 0) return
    _isUserIndexingPaused.value = false
    viewModelScope.launch {
      photoLibraryService.selectRecentAssets(selectedCount)
      refreshUserPhotos()
      selectSource(SmartAlbumSource.USER_PHOTOS)
      triggerPrioritizedIndexing()
    }
  }

  fun dismissAnalyzeAllConfirmation() {
    _showAnalyzeAllConfirmSheet.value = false
  }

  fun retryIndexingIfNeeded() {
    viewModelScope.launch {
      refreshUserPhotos()
      triggerPrioritizedIndexing()
      for (attempt in 0 until 5) {
        if (_userIndexingProgress.value.totalCount > 0) break
        delay(200)
        refreshUserPhotos()
        triggerPrioritizedIndexing()
      }
    }
  }

  fun triggerPrioritizedIndexing() {
    val hasPendingUserPhotos =
      _userIndexingProgress.value.totalCount > 0 &&
        _userIndexingProgress.value.processedCount < _userIndexingProgress.value.totalCount
    val hasPendingSamplePhotos =
      _sampleIndexingProgress.value.totalCount > 0 &&
        _sampleIndexingProgress.value.processedCount < _sampleIndexingProgress.value.totalCount

    when (_activeSource.value) {
      SmartAlbumSource.USER_PHOTOS -> {
        if (!_isUserIndexingPaused.value && hasPendingUserPhotos) {
          stopSampleIndexing()
          startIndexing()
        } else if (
          !_isSampleIndexingPaused.value &&
            hasPendingSamplePhotos &&
            sampleAlbumStorageManager != null
        ) {
          stopIndexing()
          startSampleIndexing()
        }
      }
      SmartAlbumSource.SAMPLE_ALBUM -> {
        if (sampleAlbumStorageManager != null) {
          if (!_isSampleIndexingPaused.value && hasPendingSamplePhotos) {
            stopIndexing()
            startSampleIndexing()
          } else if (!_isUserIndexingPaused.value && hasPendingUserPhotos) {
            stopSampleIndexing()
            startIndexing()
          }
        } else if (!_isUserIndexingPaused.value && hasPendingUserPhotos) {
          stopSampleIndexing()
          startIndexing()
        }
      }
    }
  }

  fun pauseIndexing(source: SmartAlbumSource = _activeSource.value) {
    when (source) {
      SmartAlbumSource.USER_PHOTOS -> {
        _isUserIndexingPaused.value = true
        _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = false)
        stopIndexing()
        triggerPrioritizedIndexing()
      }
      SmartAlbumSource.SAMPLE_ALBUM -> {
        _isSampleIndexingPaused.value = true
        _sampleIndexingProgress.value = _sampleIndexingProgress.value.copy(isInitializing = false)
        stopSampleIndexing()
        triggerPrioritizedIndexing()
      }
    }
  }

  fun resumeIndexing(source: SmartAlbumSource = _activeSource.value) {
    when (source) {
      SmartAlbumSource.USER_PHOTOS -> {
        _isUserIndexingPaused.value = false
        _activeSource.value = SmartAlbumSource.USER_PHOTOS
        if (!_isSampleIndexingPaused.value) {
          stopSampleIndexing()
        }
        startIndexing()
      }
      SmartAlbumSource.SAMPLE_ALBUM -> {
        _isSampleIndexingPaused.value = false
        _activeSource.value = SmartAlbumSource.SAMPLE_ALBUM
        if (!_isUserIndexingPaused.value) {
          stopIndexing()
        }
        startSampleIndexing()
      }
    }
  }

  fun startNow(source: SmartAlbumSource) {
    resumeIndexing(source)
  }

  fun pauseAllIndexing() {
    _isUserIndexingPaused.value = true
    _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = false)
    stopIndexing()
    _isSampleIndexingPaused.value = true
    _sampleIndexingProgress.value = _sampleIndexingProgress.value.copy(isInitializing = false)
    stopSampleIndexing()
  }

  fun resumeAllIndexing() {
    _isUserIndexingPaused.value = false
    _isSampleIndexingPaused.value = false
    triggerPrioritizedIndexing()
  }

  fun loadSampleAssets() {
    val manager = sampleAlbumStorageManager ?: return
    viewModelScope.launch {
      val assets = manager.fetchSampleAssets()
      _sampleAssets.value = assets
      _recentSampleAssets.value = assets.take(12)
      val sampleRetrieval =
        semanticRetrievalServiceProvider?.sampleSemanticRetrievalService ?: semanticRetrievalService
      val sampleIndexed = sampleRetrieval.fetchRecordIdentifiers()
      _sampleIndexingProgress.value =
        IndexingProgressUpdate(
          processedCount = sampleIndexed.size,
          totalCount = maxOf(assets.size, sampleIndexed.size),
          recentIndexedIds = emptyList(),
        )
    }
  }

  fun downloadSamplePhotos() {
    if (_sampleDownloadProgress.value.isDownloading) return
    val manager = sampleAlbumStorageManager ?: return
    val expectedBytes = manager.getSamplesTotalBytes()
    _sampleDownloadProgress.value =
      SampleDownloadProgress(isDownloading = true, receivedBytes = 0L, totalBytes = expectedBytes)

    sampleDownloadJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val success =
            manager.downloadAndExtractSamples(
              onProgress = { received, total ->
                _sampleDownloadProgress.value =
                  SampleDownloadProgress(
                    isDownloading = true,
                    receivedBytes = received,
                    totalBytes = total,
                  )
              }
            )

          if (success) {
            _sampleDownloadProgress.value = SampleDownloadProgress(isDownloading = false)
            loadSampleAssets()
            startSampleIndexing()
          } else {
            _sampleDownloadProgress.value =
              SampleDownloadProgress(
                isDownloading = false,
                errorMessage = "Failed to download sample photos",
              )
          }
        } catch (e: CancellationException) {
          Log.d(TAG, "Sample download was cancelled")
          _sampleDownloadProgress.value = SampleDownloadProgress(isDownloading = false)
        } catch (e: Exception) {
          Log.e(TAG, "Sample download failed: ${e.message}", e)
          _sampleDownloadProgress.value =
            SampleDownloadProgress(isDownloading = false, errorMessage = e.message)
        }
      }
  }

  fun cancelDownloadSamplePhotos() {
    sampleDownloadJob?.cancel()
    sampleDownloadJob = null
    _sampleDownloadProgress.value = SampleDownloadProgress(isDownloading = false)
  }

  fun startIndexing(forceRestart: Boolean = false) {
    if (_isUserIndexingPaused.value) return
    val app = application ?: return
    val currentModel = model ?: return
    val modelPath = currentModel.getPath(app)
    val accelerator = getDefaultAccelerator(currentModel)
    if (_userIndexingProgress.value.totalCount == 0) {
      refreshUserPhotos()
      return
    }
    if (_userIndexingProgress.value.processedCount >= _userIndexingProgress.value.totalCount) {
      _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = false)
      return
    }
    _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = true)
    SmartAlbumIndexingWorker.enqueue(
      app,
      modelPath,
      SmartAlbumSource.USER_PHOTOS,
      accelerator,
      forceRestart = forceRestart,
      maxInputSequenceLength = maxInputSequenceLength,
      visionTokenBudget = visionTokenBudget,
      visionAccelerator = currentModel.backendSpec.visionAccelerator,
      audioAccelerator = currentModel.backendSpec.audioAccelerator,
    )
  }

  fun stopIndexing() {
    val app = application ?: return
    SmartAlbumIndexingWorker.cancel(app, SmartAlbumSource.USER_PHOTOS)
  }

  fun startSampleIndexing(forceRestart: Boolean = false) {
    if (_isSampleIndexingPaused.value) return
    if (sampleAlbumStorageManager != null) {
      val app = application ?: return
      val currentModel = model ?: return
      val modelPath = currentModel.getPath(app)
      val accelerator = getDefaultAccelerator(currentModel)
      if (_sampleIndexingProgress.value.totalCount == 0) {
        return
      }
      if (
        _sampleIndexingProgress.value.processedCount >= _sampleIndexingProgress.value.totalCount
      ) {
        _sampleIndexingProgress.value = _sampleIndexingProgress.value.copy(isInitializing = false)
        return
      }
      _sampleIndexingProgress.value = _sampleIndexingProgress.value.copy(isInitializing = true)
      SmartAlbumIndexingWorker.enqueue(
        app,
        modelPath,
        SmartAlbumSource.SAMPLE_ALBUM,
        accelerator,
        forceRestart = forceRestart,
        maxInputSequenceLength = maxInputSequenceLength,
        visionTokenBudget = visionTokenBudget,
        visionAccelerator = currentModel.backendSpec.visionAccelerator,
        audioAccelerator = currentModel.backendSpec.audioAccelerator,
      )
    }
  }

  fun stopSampleIndexing() {
    val app = application ?: return
    SmartAlbumIndexingWorker.cancel(app, SmartAlbumSource.SAMPLE_ALBUM)
  }

  fun stopAllIndexing() {
    stopIndexing()
    stopSampleIndexing()
    _userIndexingProgress.value = _userIndexingProgress.value.copy(isInitializing = false)
    _sampleIndexingProgress.value = _sampleIndexingProgress.value.copy(isInitializing = false)
  }

  fun requestIndexingNotification() {
    _isNotificationRequested.value = true
  }

  fun cancelIndexingNotification() {
    _isNotificationRequested.value = false
  }

  fun clearAndRestartIndexing() {
    when (_activeSource.value) {
      SmartAlbumSource.SAMPLE_ALBUM -> _isSampleIndexingPaused.value = false
      SmartAlbumSource.USER_PHOTOS -> _isUserIndexingPaused.value = false
    }
    viewModelScope.launch {
      if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
        stopSampleIndexing()
        val sampleRetrieval =
          semanticRetrievalServiceProvider?.sampleSemanticRetrievalService
            ?: semanticRetrievalService
        sampleRetrieval.deleteAllRecords()
        sampleAlbumStorageManager?.deleteSamplePhotos()
        _sampleAssets.value = emptyList()
        _recentSampleAssets.value = emptyList()
        _sampleIndexingProgress.value = IndexingProgressUpdate(isInitializing = true)
        startSampleIndexing(forceRestart = true)
      } else {
        stopIndexing()
        semanticRetrievalService.deleteAllRecords()
        _recentAssets.value = emptyList()
        val total = photoLibraryService.fetchAllAssetIdentifiers().size
        _userIndexingProgress.value =
          IndexingProgressUpdate(totalCount = total, isInitializing = total > 0)
        startIndexing(forceRestart = true)
      }
    }
  }

  suspend fun removeAllPhotos() {
    if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
      removeSamplePhotos()
    } else {
      stopIndexing()
      semanticRetrievalService.deleteAllRecords()
      _recentAssets.value = emptyList()
      val allIds = photoLibraryService.fetchAllAssetIdentifiers()
      photoLibraryService.removeAssets(allIds.toSet())
      reloadUserAssets()
      _userIndexingProgress.value = IndexingProgressUpdate()
    }
  }

  suspend fun removeSamplePhotos() {
    cancelDownloadSamplePhotos()
    if (_activeSource.value == SmartAlbumSource.SAMPLE_ALBUM) {
      selectSource(SmartAlbumSource.USER_PHOTOS)
    }
    _sampleAssets.value = emptyList()
    _recentSampleAssets.value = emptyList()
    _sampleIndexingProgress.value = IndexingProgressUpdate()
    stopSampleIndexing()
    val sampleRetrieval =
      semanticRetrievalServiceProvider?.sampleSemanticRetrievalService ?: semanticRetrievalService
    sampleRetrieval.deleteAllRecords()
    sampleAlbumStorageManager?.deleteSamplePhotos()
  }

  suspend fun reloadSampleAssets() {
    val manager = sampleAlbumStorageManager ?: return
    val assets = manager.fetchSampleAssets()
    _sampleAssets.value = assets
    _recentSampleAssets.value = assets.take(12)
  }

  suspend fun reloadUserAssets() {
    val allIds = photoLibraryService.fetchAllAssetIdentifiers()
    val updated = photoLibraryService.fetchAssets(allIds)
    _recentAssets.value = updated.take(12)
  }

  suspend fun removeAssetsFromCurrentSource(ids: Set<String>) {
    when (_activeSource.value) {
      SmartAlbumSource.SAMPLE_ALBUM -> {
        val sampleRetrieval =
          semanticRetrievalServiceProvider?.sampleSemanticRetrievalService
            ?: semanticRetrievalService
        for (id in ids) {
          sampleRetrieval.deleteRecord(id)
        }
        sampleRetrieval.saveToDisk()
        sampleAlbumStorageManager?.removeSampleAssets(ids)
        reloadSampleAssets()
        val remaining = _sampleAssets.value.size
        _sampleIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = minOf(_sampleIndexingProgress.value.processedCount, remaining),
            totalCount = remaining,
            isInitializing = false,
          )
        if (_sampleAssets.value.isEmpty()) {
          selectSource(SmartAlbumSource.USER_PHOTOS)
        }
      }
      SmartAlbumSource.USER_PHOTOS -> {
        for (id in ids) {
          semanticRetrievalService.deleteRecord(id)
        }
        semanticRetrievalService.saveToDisk()
        photoLibraryService.removeAssets(ids)
        reloadUserAssets()
        val allIds = photoLibraryService.fetchAllAssetIdentifiers()
        _userIndexingProgress.value =
          IndexingProgressUpdate(
            processedCount = minOf(_userIndexingProgress.value.processedCount, allIds.size),
            totalCount = allIds.size,
            isInitializing = false,
          )
      }
    }
  }

  @VisibleForTesting
  internal fun updateProgressForTesting(
    userProgress: IndexingProgressUpdate? = null,
    sampleProgress: IndexingProgressUpdate? = null,
  ) {
    if (userProgress != null) {
      _userIndexingProgress.value = userProgress
    }
    if (sampleProgress != null) {
      _sampleIndexingProgress.value = sampleProgress
    }
  }

  private fun observeSampleProgress() {
    viewModelScope.launch {
      sampleIndexingProgress.collect { update ->
        val lastIds = update.recentIndexedIds.takeLast(5)
        if (lastIds.isNotEmpty()) {
          val currentIds = _recentSampleAssets.value.map { it.id }
          if (lastIds != currentIds) {
            val previousIds = currentIds.toSet()
            val assets =
              withContext(Dispatchers.IO) {
                val fetched = lastIds.mapNotNull { id ->
                  _sampleAssets.value.find { it.id == id }
                    ?: sampleAlbumStorageManager?.fetchAsset(id)
                }
                for (asset in fetched) {
                  if (asset.id !in previousIds) {
                    photoLibraryService.loadBitmap(asset, targetDimension = 512)
                  }
                }
                fetched
              }
            _recentSampleAssets.value = assets
          }
        } else {
          if (_recentSampleAssets.value.isEmpty()) {
            val sampleList = _sampleAssets.value
            _recentSampleAssets.value = sampleList.take(5)
          }
        }

        if (
          !_isUserIndexingPaused.value &&
            _activeSource.value == SmartAlbumSource.SAMPLE_ALBUM &&
            update.totalCount > 0 &&
            update.processedCount >= update.totalCount
        ) {
          val userUpdate = indexingProgress.value
          if (userUpdate.totalCount == 0 || userUpdate.processedCount < userUpdate.totalCount) {
            startIndexing()
          }
        }
      }
    }
  }

  private fun observeProgress() {
    viewModelScope.launch {
      indexingProgress.collect { update ->
        val lastIds = update.recentIndexedIds.takeLast(5)
        if (lastIds.isNotEmpty()) {
          // While new photos are being added/indexed, display ONLY the newly indexed photos in the
          // stack animation
          val currentIds = _recentAssets.value.map { it.id }
          if (lastIds != currentIds) {
            val previousIds = currentIds.toSet()
            val assets =
              withContext(Dispatchers.IO) {
                val fetched = photoLibraryService.fetchAssets(lastIds)
                for (asset in fetched) {
                  if (asset.id !in previousIds) {
                    photoLibraryService.loadBitmap(asset, targetDimension = 512)
                  }
                }
                fetched
              }
            _recentAssets.value = assets
          }
        } else {
          // When no new photos are being indexed in this run, show a sample of existing assets for
          // the photo stack
          if (_recentAssets.value.isEmpty()) {
            val assets =
              withContext(Dispatchers.IO) {
                val ids = photoLibraryService.fetchAllAssetIdentifiers().take(5)
                photoLibraryService.fetchAssets(ids)
              }
            _recentAssets.value = assets
          }
        }

        if (
          !_isSampleIndexingPaused.value &&
            _activeSource.value == SmartAlbumSource.USER_PHOTOS &&
            update.totalCount > 0 &&
            update.processedCount >= update.totalCount &&
            sampleAlbumStorageManager != null
        ) {
          val sampleUpdate = sampleIndexingProgress.value
          if (
            sampleUpdate.totalCount == 0 || sampleUpdate.processedCount < sampleUpdate.totalCount
          ) {
            startSampleIndexing()
          }
        }
      }
    }
  }

  private fun observeTotalProgressForNotifications() {
    viewModelScope.launch {
      totalIndexingProgress.collect { update ->
        if (
          _isNotificationRequested.value &&
            update.totalCount > 0 &&
            update.processedCount >= update.totalCount
        ) {
          _isNotificationRequested.value = false
          sendCompletionNotification()
        }
      }
    }
  }

  private fun sendCompletionNotification() {
    val app = application ?: return
    val notificationManager =
      app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

    val channelId = "ai_edge_gallery_notification_channel"
    val channelName = "AI Edge Gallery Notifications"

    val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
    notificationManager.createNotificationChannel(channel)

    val notification =
      NotificationCompat.Builder(app, channelId)
        .setSmallIcon(AndroidR.drawable.ic_dialog_info)
        .setContentTitle(app.getString(R.string.task_label_smart_album))
        .setContentText(app.getString(R.string.smartalbum_indexing_complete_content))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(NOTIFICATION_ID_INDEXING_COMPLETE, notification)
  }

  suspend fun addCustomPhotos(uris: List<Uri>): List<PhotoAsset> {
    val newAssets = uris.map { uri -> photoLibraryService.createAssetFromUri(uri) }
    photoLibraryService.addCustomAssets(newAssets)
    reloadUserAssets()
    val allIds = photoLibraryService.fetchAllAssetIdentifiers()
    val userIndexed = semanticRetrievalService.fetchRecordIdentifiers()
    val total = allIds.size
    val processed = allIds.count { it in userIndexed }
    _userIndexingProgress.value =
      _userIndexingProgress.value.copy(
        processedCount = processed,
        totalCount = total,
        isInitializing = !_isUserIndexingPaused.value && processed < total,
      )
    if (!_isUserIndexingPaused.value) {
      startIndexing()
    }
    return newAssets
  }

  public override fun onCleared() {
    cancelDownloadSamplePhotos()
    stopAllIndexing()
    if (semanticRetrievalServiceProvider != null) {
      if (semanticRetrievalServiceProvider is AutoCloseable) {
        try {
          semanticRetrievalServiceProvider.close()
        } catch (e: Exception) {
          Log.w(TAG, "Error closing SemanticRetrievalServiceProvider: ${e.message}", e)
        }
      }
    } else {
      try {
        semanticRetrievalService.close()
      } catch (e: Exception) {
        Log.w(TAG, "Error closing SemanticRetrievalService: ${e.message}", e)
      }
    }
  }

  fun cleanup() {
    onCleared()
  }

  companion object {
    private const val TAG = "SmartAlbumViewModel"
    private const val PREFS_NAME = "smart_album_prefs"
    private const val KEY_LAST_OPENED_SOURCE = "key_last_opened_source"
    const val DEFAULT_MAX_INPUT_SEQUENCE_LENGTH = 256
    const val DEFAULT_VISION_TOKEN_BUDGET = 70
    private const val NOTIFICATION_ID_INDEXING_COMPLETE = 1001

    fun create(
      context: Context,
      model: Model,
      maxInputSequenceLength: Int = DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
      visionTokenBudget: Int = DEFAULT_VISION_TOKEN_BUDGET,
    ): SmartAlbumViewModel {
      val photoService = DefaultPhotoLibraryService(context)
      val provider =
        DefaultSemanticRetrievalServiceProvider.create(
          context = context,
          model = model,
          maxInputSequenceLength = maxInputSequenceLength,
          visionTokenBudget = visionTokenBudget,
          visionAccelerator = model.backendSpec.visionAccelerator,
          audioAccelerator = model.backendSpec.audioAccelerator,
        )
      val modelPath = model.getPath(context)
      val sampleManager = SampleAlbumStorageManager(context, model, modelPath = modelPath)
      return SmartAlbumViewModel(
        photoLibraryService = photoService,
        semanticRetrievalService = provider.userSemanticRetrievalService,
        sampleAlbumStorageManager = sampleManager,
        semanticRetrievalServiceProvider = provider,
        application = context.applicationContext as? Application,
        model = model,
        maxInputSequenceLength = maxInputSequenceLength,
        visionTokenBudget = visionTokenBudget,
      )
    }

    /**
     * Returns the default accelerator for Instant Photo Search: [Accelerator.TPU] on Pixel 11,
     * [Accelerator.CPU] on Exynos chips or Mali GPUs, and [Accelerator.GPU] on other chips.
     */
    fun getDefaultAccelerator(model: Model? = null): Accelerator =
      GemmaEmbeddingModelStore.getDefaultAccelerator(model)
  }
}
