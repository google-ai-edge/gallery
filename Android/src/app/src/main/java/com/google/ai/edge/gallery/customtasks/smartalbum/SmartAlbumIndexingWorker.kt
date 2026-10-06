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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.services.photolibrary.DefaultPhotoLibraryService
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.gallery.services.semanticretrieval.OnDeviceEmbedder
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class IndexingProgressUpdate(
  val processedCount: Int = 0,
  val totalCount: Int = 0,
  val recentIndexedIds: List<String> = emptyList(),
  val isInitializing: Boolean = false,
)

/**
 * Background [CoroutineWorker] that executes photo indexing in a foreground service via
 * [WorkManager].
 */
class SmartAlbumIndexingWorker
@JvmOverloads
constructor(
  context: Context,
  params: WorkerParameters,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CoroutineWorker(context, params) {

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val notificationId: Int = params.id.hashCode()

  init {
    ensureNotificationChannels()
  }

  private fun ensureNotificationChannels() {
    val channelName = applicationContext.getString(R.string.smartalbum_notification_channel_name)
    val channelDesc = applicationContext.getString(R.string.smartalbum_notification_channel_desc)
    val foregroundChannel =
      NotificationChannel(
          FOREGROUND_NOTIFICATION_CHANNEL_ID,
          channelName,
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply { description = channelDesc }
    notificationManager.createNotificationChannel(foregroundChannel)
  }

  override suspend fun doWork(): Result =
    withContext(ioDispatcher) {
      try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to set background thread priority", e)
      }
      val modelPath = inputData.getString(KEY_MODEL_PATH)
      val sourceRaw = inputData.getString(KEY_SOURCE) ?: SmartAlbumSource.USER_PHOTOS.rawValue
      val source =
        if (sourceRaw == SmartAlbumSource.SAMPLE_ALBUM.rawValue) {
          SmartAlbumSource.SAMPLE_ALBUM
        } else {
          SmartAlbumSource.USER_PHOTOS
        }

      if (modelPath == null) {
        Log.e(TAG, "Missing model path for SmartAlbumIndexingWorker")
        return@withContext Result.failure()
      }

      val dbFileName =
        if (source == SmartAlbumSource.SAMPLE_ALBUM) {
          DefaultSemanticRetrievalServiceProvider.SAMPLE_DATABASE_NAME
        } else {
          DefaultSemanticRetrievalServiceProvider.USER_DATABASE_NAME
        }
      val dbPath =
        GemmaEmbeddingModelStore.getDatabasePath(
          modelPath = modelPath,
          databaseName = dbFileName,
          context = applicationContext,
        )

      val accelerator =
        Accelerator.fromLabel(inputData.getString(KEY_ACCELERATOR)) ?: Accelerator.GPU

      val maxInputSequenceLength =
        inputData.getInt(
          KEY_MAX_INPUT_SEQUENCE_LENGTH,
          SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
        )
      val visionTokenBudget =
        inputData.getInt(KEY_VISION_TOKEN_BUDGET, SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET)

      val visionAccelerator = Accelerator.fromLabel(inputData.getString(KEY_VISION_ACCELERATOR))
      val audioAccelerator = Accelerator.fromLabel(inputData.getString(KEY_AUDIO_ACCELERATOR))

      val embedder =
        GemmaEmbeddingModelStore.getOrCreateEmbedder(
          modelPath = modelPath,
          accelerator = accelerator,
          context = applicationContext,
          maxInputSequenceLength = maxInputSequenceLength,
          visionTokenBudget = visionTokenBudget,
          visionAccelerator = visionAccelerator,
          audioAccelerator = audioAccelerator,
        )
      if (!embedder.isAvailable()) {
        Log.e(TAG, "Embedding model not available at path: $modelPath")
        return@withContext Result.failure()
      }

      val photoService: PhotoLibraryService =
        DefaultPhotoLibraryService(applicationContext, ioDispatcher = ioDispatcher)
      val retrievalService =
        SemanticRetrievalService(
          context = applicationContext,
          customEmbedder = embedder,
          databaseName = dbPath,
          ioDispatcher = ioDispatcher,
        )

      val sampleManager =
        if (source == SmartAlbumSource.SAMPLE_ALBUM) {
          SampleAlbumStorageManager(applicationContext, modelPath = modelPath)
        } else {
          null
        }

      try {
        val indexedCount =
          if (source == SmartAlbumSource.SAMPLE_ALBUM && sampleManager != null) {
            indexSamples(sampleManager, photoService, retrievalService, embedder)
          } else {
            indexUserPhotos(photoService, retrievalService, embedder)
          }

        if (indexedCount > 0) {
          retrievalService.saveToDisk()
        }
        Result.success()
      } catch (e: Exception) {
        Log.e(TAG, "Indexing worker encountered an error: ${e.message}", e)
        retrievalService.saveToDisk()
        if (isStopped) {
          Result.success()
        } else {
          Result.failure()
        }
      } finally {
        retrievalService.close()
      }
    }

  private suspend fun indexUserPhotos(
    photoLibraryService: PhotoLibraryService,
    semanticRetrievalService: SemanticRetrievalService,
    embedder: OnDeviceEmbedder,
  ): Int {
    val allIds = photoLibraryService.fetchAllAssetIdentifiers()
    val indexedSet = semanticRetrievalService.fetchRecordIdentifiers()
    val unindexedIds = allIds.filter { !indexedSet.contains(it) }
    val totalCount = allIds.size
    var processedCount = allIds.size - unindexedIds.size

    if (unindexedIds.isEmpty()) {
      Log.d(TAG, "All ${allIds.size} user photos already indexed; skipping background worker")
      setProgress(
        workDataOf(
          PROGRESS_KEY_PROCESSED to processedCount,
          PROGRESS_KEY_TOTAL to totalCount,
          PROGRESS_KEY_IS_INITIALIZING to false,
          PROGRESS_KEY_RECENT_IDS to emptyArray<String>(),
        )
      )
      return 0
    }

    val recentIds = mutableListOf<String>()
    setProgress(
      workDataOf(
        PROGRESS_KEY_PROCESSED to processedCount,
        PROGRESS_KEY_TOTAL to totalCount,
        PROGRESS_KEY_IS_INITIALIZING to true,
        PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
      )
    )
    safeSetForeground(
      createForegroundInfo(
        processedCount = processedCount,
        totalCount = totalCount,
        isInitializing = true,
      )
    )

    embedder.initialize()

    if (isStopped) {
      return 0
    }

    Log.d(
      TAG,
      "Background indexing user photos: $processedCount already indexed, ${unindexedIds.size} remaining",
    )

    setProgress(
      workDataOf(
        PROGRESS_KEY_PROCESSED to processedCount,
        PROGRESS_KEY_TOTAL to totalCount,
        PROGRESS_KEY_IS_INITIALIZING to false,
        PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
      )
    )
    safeSetForeground(
      createForegroundInfo(
        processedCount = processedCount,
        totalCount = totalCount,
        isInitializing = false,
      )
    )

    var newlyIndexed = 0
    for (id in unindexedIds) {
      if (isStopped) {
        Log.d(TAG, "Worker stopped; saving progress and exiting user photo indexing loop")
        break
      }
      while (SmartAlbumIndexingCoordinator.isScrollInProgress) {
        if (isStopped) break
        delay(100)
      }
      if (isStopped) break

      try {
        val asset = photoLibraryService.fetchAsset(id)
        if (asset == null) {
          processedCount++
          setProgress(
            workDataOf(
              PROGRESS_KEY_PROCESSED to processedCount,
              PROGRESS_KEY_TOTAL to totalCount,
              PROGRESS_KEY_IS_INITIALIZING to false,
              PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
            )
          )
          continue
        }
        val keyframes = photoLibraryService.loadKeyframes(asset, targetDimension = 1024)
        if (keyframes.isEmpty()) {
          processedCount++
          setProgress(
            workDataOf(
              PROGRESS_KEY_PROCESSED to processedCount,
              PROGRESS_KEY_TOTAL to totalCount,
              PROGRESS_KEY_IS_INITIALIZING to false,
              PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
            )
          )
          continue
        }
        val imageDataList = keyframes.map { ImageUtils.encodeTga(it) }

        semanticRetrievalService.addRecord(
          id = id,
          imageDataList = imageDataList,
          metadata =
            mapOf(
              "displayName" to asset.displayName,
              "locationName" to (asset.locationName ?: ""),
              "isVideo" to asset.isVideo.toString(),
            ),
        )

        processedCount++
        newlyIndexed++
        recentIds.add(id)
        if (recentIds.size > MAX_RECENT_INDEXED_IDS) {
          recentIds.removeAt(0)
        }
        setProgress(
          workDataOf(
            PROGRESS_KEY_PROCESSED to processedCount,
            PROGRESS_KEY_TOTAL to totalCount,
            PROGRESS_KEY_IS_INITIALIZING to false,
            PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
          )
        )
        safeSetForeground(
          createForegroundInfo(processedCount = processedCount, totalCount = totalCount)
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "*** Failed to index user photo $id: ${e.message}", e)
        processedCount++
        setProgress(
          workDataOf(
            PROGRESS_KEY_PROCESSED to processedCount,
            PROGRESS_KEY_TOTAL to totalCount,
            PROGRESS_KEY_IS_INITIALIZING to false,
            PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
          )
        )
      }
      val pauseDuration = if (SmartAlbumIndexingCoordinator.isGalleryVisible) 100L else 20L
      delay(pauseDuration)
    }
    return newlyIndexed
  }

  private suspend fun indexSamples(
    sampleManager: SampleAlbumStorageManager,
    photoLibraryService: PhotoLibraryService,
    semanticRetrievalService: SemanticRetrievalService,
    embedder: OnDeviceEmbedder,
  ): Int {
    val sampleAssets = sampleManager.fetchSampleAssets()
    val allSampleIds = sampleAssets.map { it.id }
    val indexedSet = semanticRetrievalService.fetchRecordIdentifiers()
    val unindexedIds = allSampleIds.filter { !indexedSet.contains(it) }
    val totalCount = indexedSet.size + unindexedIds.size
    var processedCount = indexedSet.size

    if (unindexedIds.isEmpty()) {
      Log.d(
        TAG,
        "All ${allSampleIds.size} sample photos already indexed; skipping background worker",
      )
      setProgress(
        workDataOf(
          PROGRESS_KEY_PROCESSED to processedCount,
          PROGRESS_KEY_TOTAL to totalCount,
          PROGRESS_KEY_IS_INITIALIZING to false,
          PROGRESS_KEY_RECENT_IDS to emptyArray<String>(),
        )
      )
      return 0
    }

    val recentIds = mutableListOf<String>()
    val sampleMap = sampleAssets.associateBy { it.id }

    setProgress(
      workDataOf(
        PROGRESS_KEY_PROCESSED to processedCount,
        PROGRESS_KEY_TOTAL to totalCount,
        PROGRESS_KEY_IS_INITIALIZING to true,
        PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
      )
    )
    safeSetForeground(
      createForegroundInfo(
        processedCount = processedCount,
        totalCount = totalCount,
        isInitializing = true,
      )
    )

    embedder.initialize()

    if (isStopped) {
      return 0
    }

    Log.d(
      TAG,
      "Background indexing sample photos: $processedCount already indexed, ${unindexedIds.size} remaining",
    )

    setProgress(
      workDataOf(
        PROGRESS_KEY_PROCESSED to processedCount,
        PROGRESS_KEY_TOTAL to totalCount,
        PROGRESS_KEY_IS_INITIALIZING to false,
        PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
      )
    )
    safeSetForeground(
      createForegroundInfo(
        processedCount = processedCount,
        totalCount = totalCount,
        isInitializing = false,
      )
    )

    var newlyIndexed = 0
    for (id in unindexedIds) {
      if (isStopped) {
        Log.d(TAG, "Worker stopped; saving progress and exiting sample photo indexing loop")
        break
      }

      while (SmartAlbumIndexingCoordinator.isScrollInProgress) {
        if (isStopped) break
        delay(100)
      }
      if (isStopped) break

      try {
        val asset = sampleMap[id] ?: photoLibraryService.fetchAsset(id)
        if (asset == null) {
          processedCount++
          setProgress(
            workDataOf(
              PROGRESS_KEY_PROCESSED to processedCount,
              PROGRESS_KEY_TOTAL to totalCount,
              PROGRESS_KEY_IS_INITIALIZING to false,
              PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
            )
          )
          continue
        }
        val keyframes =
          sampleManager.loadBitmap(asset, targetDimension = 1024)?.let { listOf(it) }
            ?: photoLibraryService.loadKeyframes(asset, targetDimension = 1024)
        if (keyframes.isEmpty()) {
          processedCount++
          setProgress(
            workDataOf(
              PROGRESS_KEY_PROCESSED to processedCount,
              PROGRESS_KEY_TOTAL to totalCount,
              PROGRESS_KEY_IS_INITIALIZING to false,
              PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
            )
          )
          continue
        }
        val imageDataList = keyframes.map { ImageUtils.encodeTga(it) }

        semanticRetrievalService.addRecord(
          id = id,
          imageDataList = imageDataList,
          metadata =
            mapOf(
              "displayName" to asset.displayName,
              "locationName" to (asset.locationName ?: ""),
              "isVideo" to asset.isVideo.toString(),
            ),
        )

        processedCount++
        newlyIndexed++
        recentIds.add(id)
        if (recentIds.size > MAX_RECENT_INDEXED_IDS) {
          recentIds.removeAt(0)
        }
        setProgress(
          workDataOf(
            PROGRESS_KEY_PROCESSED to processedCount,
            PROGRESS_KEY_TOTAL to totalCount,
            PROGRESS_KEY_IS_INITIALIZING to false,
            PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
          )
        )
        safeSetForeground(
          createForegroundInfo(processedCount = processedCount, totalCount = totalCount)
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "*** Failed to index sample asset $id: ${e.message}", e)
        processedCount++
        setProgress(
          workDataOf(
            PROGRESS_KEY_PROCESSED to processedCount,
            PROGRESS_KEY_TOTAL to totalCount,
            PROGRESS_KEY_IS_INITIALIZING to false,
            PROGRESS_KEY_RECENT_IDS to recentIds.toTypedArray(),
          )
        )
      }
    }
    val pauseDuration = if (SmartAlbumIndexingCoordinator.isGalleryVisible) 100L else 20L
    delay(pauseDuration)
    return newlyIndexed
  }

  private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo) {
    try {
      setForeground(foregroundInfo)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "Unable to run as foreground service: ${e.message}")
    }
  }

  private fun createForegroundInfo(
    processedCount: Int,
    totalCount: Int,
    isIndeterminate: Boolean = false,
    isInitializing: Boolean = false,
  ): ForegroundInfo {
    val title = applicationContext.getString(R.string.task_label_smart_album)
    val contentText =
      if (isInitializing) {
        applicationContext.getString(R.string.smartalbum_initializing_model)
      } else if (isIndeterminate || totalCount == 0) {
        applicationContext.getString(R.string.smartalbum_preparing_photo_index)
      } else {
        val pct = (processedCount * 100) / totalCount
        applicationContext.getString(
          R.string.smartalbum_indexing_photos_progress,
          processedCount,
          totalCount,
          pct,
        )
      }

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(contentText)
        .setSmallIcon(AndroidR.drawable.ic_dialog_info)
        .setOngoing(true)
        .setProgress(totalCount, processedCount, isIndeterminate || isInitializing)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  companion object {
    private const val TAG = "SmartAlbumIndexingWork"

    /** Maximum number of recently indexed photo IDs kept in progress updates for UI preview. */
    private const val MAX_RECENT_INDEXED_IDS = 12

    const val KEY_MODEL_PATH = "key_smart_album_model_path"
    const val KEY_SOURCE = "key_smart_album_source"
    const val KEY_ACCELERATOR = "key_smart_album_accelerator"
    const val KEY_MAX_INPUT_SEQUENCE_LENGTH = "key_smart_album_max_input_sequence_length"
    const val KEY_VISION_TOKEN_BUDGET = "key_smart_album_vision_token_budget"
    const val KEY_VISION_ACCELERATOR = "key_smart_album_vision_accelerator"
    const val KEY_AUDIO_ACCELERATOR = "key_smart_album_audio_accelerator"
    const val PROGRESS_KEY_PROCESSED = "progress_processed"
    const val PROGRESS_KEY_TOTAL = "progress_total"
    const val PROGRESS_KEY_RECENT_IDS = "progress_recent_ids"
    const val PROGRESS_KEY_IS_INITIALIZING = "progress_is_initializing"

    private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "smart_album_indexing_foreground"
    const val UNIQUE_WORK_NAME_PREFIX = "smart_album_indexing_work_"

    fun enqueue(
      context: Context,
      modelPath: String,
      source: SmartAlbumSource = SmartAlbumSource.USER_PHOTOS,
      accelerator: Accelerator = Accelerator.GPU,
      forceRestart: Boolean = false,
      maxInputSequenceLength: Int = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
      visionTokenBudget: Int = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
      visionAccelerator: Accelerator? = null,
      audioAccelerator: Accelerator? = null,
    ) {
      val dataPairs = buildList {
        add(KEY_MODEL_PATH to modelPath)
        add(KEY_SOURCE to source.rawValue)
        add(KEY_ACCELERATOR to accelerator.label)
        add(KEY_MAX_INPUT_SEQUENCE_LENGTH to maxInputSequenceLength)
        add(KEY_VISION_TOKEN_BUDGET to visionTokenBudget)
        if (visionAccelerator != null) {
          add(KEY_VISION_ACCELERATOR to visionAccelerator.label)
        }
        if (audioAccelerator != null) {
          add(KEY_AUDIO_ACCELERATOR to audioAccelerator.label)
        }
      }
      val workRequest =
        OneTimeWorkRequestBuilder<SmartAlbumIndexingWorker>()
          .setInputData(workDataOf(*dataPairs.toTypedArray()))
          .addTag(UNIQUE_WORK_NAME_PREFIX + source.rawValue)
          .build()

      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          UNIQUE_WORK_NAME_PREFIX + source.rawValue,
          if (forceRestart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
          workRequest,
        )
    }

    fun cancel(context: Context, source: SmartAlbumSource) {
      try {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME_PREFIX + source.rawValue)
      } catch (e: IllegalStateException) {
        // WorkManager may not be initialized in certain test environments.
      }
    }
  }
}
