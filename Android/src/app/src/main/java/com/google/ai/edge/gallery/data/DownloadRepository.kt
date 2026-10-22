/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.worker.DownloadWorker
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

private const val TAG = "AGDownloadRepository"
private const val MODEL_NAME_TAG = "modelName"
private const val TASK_ID_TAG = "taskId"
private const val VERSION_TAG = "version"

interface DownloadRepository {
  fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean = true,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  /**
   * Finds the downloads that WorkManager still has queued or running, whether this app process or
   * an earlier one started them, and passes them to [onResult]. [onResult] may run on a background
   * thread or on the calling thread, so it should switch to the thread it needs.
   *
   * [onResult] is always called, with an empty list if WorkManager can't be queried.
   */
  fun getPendingDownloads(onResult: (List<PendingDownload>) -> Unit)

  /**
   * Reports the progress of [pendingDownload] to [onStatusUpdated] without restarting it. Call on
   * the main thread.
   *
   * If this process already observes the download, e.g. because the activity was recreated, only
   * the model and callback are replaced, and the latest status is replayed to the new callback. If
   * the download has ended since it was queried, its final status is replayed. The download's
   * outcome is logged once either way.
   */
  fun observePendingDownload(
    pendingDownload: PendingDownload,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  /**
   * Cancels [pendingDownload] because its model is no longer in the allowlist.
   *
   * Downloads this process observes are kept: the user started them in this process, and their
   * model may not be listed in a task, e.g. a model being imported.
   */
  fun cancelPendingDownload(pendingDownload: PendingDownload)

  fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadExtraDataFiles(model: Model)
}

/** A model download that WorkManager has queued or is running. */
data class PendingDownload(
  /** The WorkManager ID of the download's work request. */
  val workerId: UUID,
  /** The name of the model being downloaded. */
  val modelName: String,
  /** Whether the work downloads only the model's extra data files. */
  val isExtraDataOnly: Boolean,
  /**
   * The ID of the task the download was started from: empty if it was started outside a task, from
   * the global model manager, or null if the work has no task tag.
   */
  val taskId: String? = null,
  /** The version (commit hash) of the model being downloaded, or null if the work has no tag. */
  val version: String? = null,
) {
  companion object {
    /**
     * Reads a pending download from the tags of its work request.
     *
     * @return null if the work isn't a model download.
     */
    fun fromTags(workerId: UUID, tags: Set<String>): PendingDownload? {
      val workName = tags.tagValue(MODEL_NAME_TAG) ?: return null
      val isExtraDataOnly = workName.endsWith(EXTRA_DATA_SUFFIX)
      return PendingDownload(
        workerId = workerId,
        modelName = workName.removeSuffix(EXTRA_DATA_SUFFIX),
        isExtraDataOnly = isExtraDataOnly,
        taskId = tags.tagValue(TASK_ID_TAG),
        version = tags.tagValue(VERSION_TAG),
      )
    }

    /** Returns the value of the `<key>:<value>` tag with [key], or null if there is none. */
    private fun Set<String>.tagValue(key: String): String? =
      firstOrNull { it.startsWith("$key:") }?.removePrefix("$key:")
  }
}

/** Who cancelled a download. */
internal enum class DownloadCancellation {
  /** The user stopped the download. */
  USER,
  /** The app cancelled it, e.g. by replacing it with a new download of the same model. */
  APP;

  companion object {
    /**
     * Returns who cancelled a download that ended in [state], or null if it wasn't cancelled.
     *
     * @param cancelledByUser Whether the user asked to stop the download.
     */
    fun of(state: WorkInfo.State, cancelledByUser: Boolean): DownloadCancellation? =
      when {
        state != WorkInfo.State.CANCELLED -> null
        cancelledByUser -> USER
        else -> APP
      }
  }
}

/** How a download that ended without succeeding is reported. */
internal data class UnsuccessfulDownloadOutcome(
  /** The model's download status afterwards. */
  val status: ModelDownloadStatusType,
  /** Whether to notify the user that the download failed. */
  val notifyFailure: Boolean,
  /** The `event_type` of the `model_download` analytics event. */
  val analyticsEventType: String,
  /** The `error_type` of the `model_download` analytics event, set for failures only. */
  val analyticsErrorType: String? = null,
) {
  companion object {
    /**
     * Decides how to report a download that ended without succeeding.
     *
     * @param cancellation Who cancelled the download, or null if it failed.
     * @param authError Set when Hugging Face refused the download. That isn't a failure: the UI
     *   asks the user to sign in to Hugging Face or accept the model's license, then retries the
     *   download.
     * @param errorType The worker's error category (see [KEY_MODEL_DOWNLOAD_ERROR_TYPE]), if any.
     */
    fun from(
      cancellation: DownloadCancellation?,
      authError: DownloadAuthError?,
      errorType: String? = null,
    ): UnsuccessfulDownloadOutcome =
      when {
        // Cancellations aren't failures. Logging them separately keeps the failure rate accurate.
        cancellation == DownloadCancellation.USER ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notifyFailure = false,
            analyticsEventType = EVENT_TYPE_CANCELLED,
          )
        cancellation == DownloadCancellation.APP ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notifyFailure = false,
            analyticsEventType = EVENT_TYPE_CANCELLED_BY_APP,
          )
        authError != null ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notifyFailure = false,
            analyticsEventType = EVENT_TYPE_AUTH_REQUIRED,
          )
        else ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.FAILED,
            notifyFailure = true,
            analyticsEventType = EVENT_TYPE_FAILURE,
            analyticsErrorType = errorType?.ifEmpty { null } ?: ERROR_TYPE_UNKNOWN,
          )
      }
  }
}

// The `event_type` values of the `model_download` analytics event.
private const val EVENT_TYPE_START = "start"
private const val EVENT_TYPE_SUCCESS = "success"
private const val EVENT_TYPE_FAILURE = "failure"
private const val EVENT_TYPE_CANCELLED = "cancelled"
private const val EVENT_TYPE_CANCELLED_BY_APP = "cancelled_by_app"
private const val EVENT_TYPE_AUTH_REQUIRED = "auth_required"

private const val EXTRA_DATA_SUFFIX = "_extra_data"

/** Returns the unique WorkManager work name of a model's download or extra data download. */
private fun workName(modelName: String, isExtraDataOnly: Boolean): String =
  if (isExtraDataOnly) "$modelName$EXTRA_DATA_SUFFIX" else modelName

/** Returns the tag that marks the work requests of [workName]. */
private fun modelNameTag(workName: String): String = "$MODEL_NAME_TAG:$workName"

private const val DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID = "___"

/**
 * Repository for managing model downloads using WorkManager.
 *
 * This class provides methods to initiate model downloads, cancel downloads, observe download
 * progress, and retrieve information about enqueued or running download tasks. It utilizes
 * WorkManager to handle background download operations.
 *
 * @param logAnalyticsEvent Logs an analytics event with its parameters.
 * @param clock Tells the time, for download durations. Start times outlive the app process, so this
 *   is the wall clock rather than an elapsed-time clock.
 */
class DefaultDownloadRepository(
  private val context: Context,
  private val lifecycleProvider: AppLifecycleProvider,
  private val logAnalyticsEvent: (name: String, params: Bundle) -> Unit = { name, params ->
    firebaseAnalytics?.logEvent(name, params)
  },
  private val clock: Clock = Clock.systemUTC(),
) : DownloadRepository {
  private val workManager = WorkManager.getInstance(context)
  /**
   * Stores the start time of a model download.
   *
   * We use SharedPreferences to persist the download start times. This ensures that the data is
   * still available after the app restarts. The key is the WorkManager worker ID, so a download
   * that replaces another one of the same model keeps its own start time, and the value is the
   * download start time in milliseconds.
   */
  private val downloadStartTimeSharedPreferences =
    context.getSharedPreferences("download_start_time_ms", Context.MODE_PRIVATE)

  /**
   * The downloads this process observes, by WorkManager worker ID.
   *
   * Keeping one observer per download, rather than one per caller, logs each download's outcome
   * once even when the activity is recreated and attaches to the download again.
   */
  private val observedDownloads = ConcurrentHashMap<UUID, ObservedDownload>()

  /**
   * The downloads this process observed until they ended. Attaching to one of them again, e.g. from
   * a pending download list queried just before it ended, must not log its outcome twice.
   */
  private val endedWorkerIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

  /**
   * The final status of each ended download in [endedWorkerIds] that reported one, replayed to a
   * caller that attaches after the download ended. Replaced downloads have none.
   */
  private val endedDownloadStatuses = ConcurrentHashMap<UUID, ModelDownloadStatus>()

  /** A download observed by this process. */
  private class ObservedDownload(
    @Volatile var model: Model,
    /** Whether the work downloads only the model's extra data files. */
    val isExtraDataOnly: Boolean,
    @Volatile var onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    /** Whether the user stopped this download, as opposed to the app cancelling it. */
    @Volatile var cancelledByUser: Boolean = false

    /** The last status reported to [onStatusUpdated], replayed when the callback is replaced. */
    @Volatile var lastStatus: ModelDownloadStatus? = null

    /** The unique WorkManager work name of this download. */
    val workName: String
      get() = workName(model.name, isExtraDataOnly)

    /** Reports [status] to the current callback. */
    fun report(status: ModelDownloadStatus) {
      lastStatus = status
      onStatusUpdated(model, status)
    }
  }

  override fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    // Create input data.
    val builder = Data.Builder()
    val extraFiles =
      if (includeExtraDataFiles) model.downloadInfo.extraDataFiles(task?.id) else emptyList()
    val totalBytes = model.downloadInfo.sizeInBytes + extraFiles.sumOf { it.sizeInBytes }
    val inputDataBuilder =
      builder
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.downloadInfo.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.downloadInfo.version)
        .putString(
          KEY_MODEL_DOWNLOAD_MODEL_DIR,
          if (model.downloadInfo.imported) IMPORTS_DIR else model.normalizedName,
        )
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadInfo.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.downloadInfo.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.downloadInfo.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
        .putBoolean(KEY_MODEL_IS_IMPORTED, model.downloadInfo.imported)

    if (extraFiles.isNotEmpty()) {
      inputDataBuilder
        .putString(KEY_MODEL_EXTRA_DATA_URLS, extraFiles.joinToString(",") { it.url })
        .putString(
          KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES,
          extraFiles.joinToString(",") { it.downloadFileName },
        )
    }
    if (model.downloadInfo.accessToken != null) {
      inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, model.downloadInfo.accessToken)
    }
    val inputData = inputDataBuilder.build()

    // Create worker request.
    val downloadWorkRequest =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputData)
        .addTag(modelNameTag(workName(model.name, isExtraDataOnly = false)))
        .addTag("$TASK_ID_TAG:${task?.id ?: ""}")
        .addTag("$VERSION_TAG:${model.downloadInfo.version}")
        .build()

    // Start!
    enqueueAndObserve(
      downloadWorkRequest = downloadWorkRequest,
      download =
        ObservedDownload(model = model, isExtraDataOnly = false, onStatusUpdated = onStatusUpdated),
      task = task,
      expectedTotalBytes = totalBytes,
    )
  }

  override fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val extraFiles = model.downloadInfo.extraDataFiles(task?.id)
    if (extraFiles.isEmpty()) return
    val totalBytes = extraFiles.sumOf { it.sizeInBytes }

    val inputData =
      Data.Builder()
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_COMMIT_HASH, model.downloadInfo.version)
        .putString(
          KEY_MODEL_DOWNLOAD_MODEL_DIR,
          if (model.downloadInfo.imported) IMPORTS_DIR else model.normalizedName,
        )
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
        .putBoolean(KEY_MODEL_IS_IMPORTED, model.downloadInfo.imported)
        .putBoolean(KEY_MODEL_EXTRA_DATA_ONLY, true)
        .putString(KEY_MODEL_EXTRA_DATA_URLS, extraFiles.joinToString(",") { it.url })
        .putString(
          KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES,
          extraFiles.joinToString(",") { it.downloadFileName },
        )
        .apply {
          if (model.downloadInfo.accessToken != null) {
            putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, model.downloadInfo.accessToken)
          }
        }
        .build()

    val downloadWorkRequest =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputData)
        .addTag(modelNameTag(workName(model.name, isExtraDataOnly = true)))
        .addTag("$TASK_ID_TAG:${task?.id ?: ""}")
        .addTag("$VERSION_TAG:${model.downloadInfo.version}")
        .build()

    enqueueAndObserve(
      downloadWorkRequest = downloadWorkRequest,
      download =
        ObservedDownload(model = model, isExtraDataOnly = true, onStatusUpdated = onStatusUpdated),
      task = task,
      expectedTotalBytes = totalBytes,
    )
  }

  /**
   * Enqueues [downloadWorkRequest], replacing any download of the same work, logs its start, and
   * reports its progress to [download].
   *
   * The start is logged once here rather than on observed ENQUEUED states, because WorkManager
   * re-enqueues work it has to stop and that is still the same download.
   */
  private fun enqueueAndObserve(
    downloadWorkRequest: OneTimeWorkRequest,
    download: ObservedDownload,
    task: Task?,
    expectedTotalBytes: Long,
  ) {
    val workerId = downloadWorkRequest.id
    endReplacedDownloads(download.workName)
    workManager.enqueueUniqueWork(
      download.workName,
      ExistingWorkPolicy.REPLACE,
      downloadWorkRequest,
    )
    observedDownloads[workerId] = download
    downloadStartTimeSharedPreferences.edit { putLong(workerId.toString(), clock.millis()) }
    logAnalyticsEvent(
      GalleryEvent.MODEL_DOWNLOAD.id,
      downloadEventParams(EVENT_TYPE_START, download.model.name),
    )
    observeWorkerProgress(
      workerId = workerId,
      download = download,
      task = task,
      expectedTotalBytes = expectedTotalBytes,
    )
  }

  /**
   * Ends the observed downloads of [workName], which a new download is about to replace, and logs
   * how they ended.
   *
   * WorkManager deletes replaced work, possibly before its observer has seen it at all, so the
   * outcome is logged here rather than by the observer.
   */
  private fun endReplacedDownloads(workName: String) {
    for ((workerId, download) in observedDownloads) {
      if (download.workName != workName) {
        continue
      }
      endedWorkerIds.add(workerId)
      observedDownloads.remove(workerId)
      logCancelled(workerId, download)
    }
  }

  /** Logs that [download] was cancelled, by the user or by the app. */
  private fun logCancelled(workerId: UUID, download: ObservedDownload) {
    logDownloadOutcome(
      workerId = workerId,
      modelName = download.model.name,
      eventType =
        if (download.cancelledByUser) EVENT_TYPE_CANCELLED else EVENT_TYPE_CANCELLED_BY_APP,
    )
  }

  /**
   * Logs how a download ended, with its duration if its start time is known, and forgets its start
   * time.
   */
  private fun logDownloadOutcome(
    workerId: UUID,
    modelName: String,
    eventType: String,
    errorType: String? = null,
  ) {
    val startTimeKey = workerId.toString()
    val params = downloadEventParams(eventType, modelName)
    if (startTimeKey in downloadStartTimeSharedPreferences) {
      val startTime = downloadStartTimeSharedPreferences.getLong(startTimeKey, 0L)
      // The wall clock can move backward, e.g. when it's synced, so never log a negative duration.
      params.putLong("duration_ms", (clock.millis() - startTime).coerceAtLeast(0L))
    }
    if (errorType != null) {
      params.putString("error_type", errorType)
    }
    logAnalyticsEvent(GalleryEvent.MODEL_DOWNLOAD.id, params)
    downloadStartTimeSharedPreferences.edit { remove(startTimeKey) }
  }

  /** Returns the parameters every `model_download` analytics event has. */
  private fun downloadEventParams(eventType: String, modelName: String): Bundle =
    Bundle().apply {
      putString("event_type", eventType)
      putString("model_id", modelName)
    }

  override fun cancelDownloadExtraDataFiles(model: Model) {
    cancelByUser(workName(model.name, isExtraDataOnly = true))
  }

  override fun cancelDownloadModel(model: Model) {
    cancelByUser(workName(model.name, isExtraDataOnly = false))
    cancelByUser(workName(model.name, isExtraDataOnly = true))
  }

  /** Cancels the downloads of [workName], marking the observed ones as stopped by the user. */
  private fun cancelByUser(workName: String) {
    for (download in observedDownloads.values) {
      if (download.workName == workName) {
        download.cancelledByUser = true
      }
    }
    workManager.cancelAllWorkByTag(modelNameTag(workName))
  }

  override fun getPendingDownloads(onResult: (List<PendingDownload>) -> Unit) {
    val future =
      workManager.getWorkInfos(
        WorkQuery.fromStates(
          WorkInfo.State.ENQUEUED,
          WorkInfo.State.RUNNING,
          WorkInfo.State.BLOCKED,
        )
      )
    future.addListener(
      {
        val pendingDownloads =
          try {
            future.get().mapNotNull { PendingDownload.fromTags(it.id, it.tags) }
          } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while querying pending downloads", e)
            null
          } catch (e: Exception) {
            // ExecutionException, or CancellationException if the query was cancelled.
            Log.e(TAG, "Failed to query pending downloads", e)
            null
          }
        // Without the pending downloads, every start time could belong to a running download.
        if (pendingDownloads != null) {
          val keepWorkerIds = pendingDownloads.map { it.workerId }.toSet()
          Handler(Looper.getMainLooper()).post { pruneStartTimes(keepWorkerIds) }
        }
        onResult(pendingDownloads.orEmpty())
      },
      // Run on the thread that completes the query: onResult only hands the result over.
      Executor { it.run() },
    )
  }

  /**
   * Forgets the start times of downloads that are neither in [keepWorkerIds] nor observed. Their
   * outcome will never be logged, e.g. because they ended while no process observed them, so their
   * entries would otherwise stay forever. This also drops entries keyed by model name, from
   * versions of the app that keyed start times that way.
   *
   * Call on the main thread, where downloads start and end, so that none starts or ends midway.
   */
  private fun pruneStartTimes(keepWorkerIds: Set<UUID>) {
    val keep = (keepWorkerIds + observedDownloads.keys).map { it.toString() }.toSet()
    val stale = downloadStartTimeSharedPreferences.all.keys.filter { it !in keep }
    if (stale.isNotEmpty()) {
      downloadStartTimeSharedPreferences.edit {
        for (key in stale) {
          remove(key)
        }
      }
    }
  }

  override fun observePendingDownload(
    pendingDownload: PendingDownload,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val workerId = pendingDownload.workerId
    if (workerId in endedWorkerIds) {
      Log.d(TAG, "Worker $workerId has already ended. Replaying its final status.")
      endedDownloadStatuses[workerId]?.let { onStatusUpdated(model, it) }
      return
    }
    val download =
      ObservedDownload(
        model = model,
        isExtraDataOnly = pendingDownload.isExtraDataOnly,
        onStatusUpdated = onStatusUpdated,
      )
    if (
      observedDownloads.any { (id, other) -> id != workerId && other.workName == download.workName }
    ) {
      // A download this process started after the query replaced this one, which nothing observed,
      // so log how it ended here. The new download reports the model's status.
      Log.d(TAG, "Worker $workerId was replaced by a newer download.")
      endedWorkerIds.add(workerId)
      logCancelled(workerId, download)
      return
    }
    val observed = observedDownloads.putIfAbsent(workerId, download)
    if (observed != null) {
      Log.d(TAG, "Already observing worker $workerId. Updating its callback.")
      observed.model = model
      observed.onStatusUpdated = onStatusUpdated
      observed.lastStatus?.let { onStatusUpdated(model, it) }
      return
    }
    observeWorkerProgress(workerId = workerId, download = download, task = task)
  }

  override fun cancelPendingDownload(pendingDownload: PendingDownload) {
    val workerId = pendingDownload.workerId
    if (observedDownloads.containsKey(workerId) || workerId in endedWorkerIds) {
      Log.d(TAG, "Keeping worker $workerId, which this process observes or observed.")
      return
    }
    // Mark it ended first, so a later pending download list that still has it, before WorkManager
    // finishes cancelling it, doesn't cancel and log it again.
    endedWorkerIds.add(workerId)
    workManager.cancelWorkById(workerId)
    // No observer will log this download's outcome, so log it here to pair its start.
    logDownloadOutcome(
      workerId = workerId,
      modelName = pendingDownload.modelName,
      eventType = EVENT_TYPE_CANCELLED_BY_APP,
    )
  }

  private fun observeWorkerProgress(
    workerId: UUID,
    download: ObservedDownload,
    task: Task?,
    expectedTotalBytes: Long = 0L,
  ) {
    val isExtraDataOnly = download.isExtraDataOnly
    val liveData = workManager.getWorkInfoByIdLiveData(workerId)
    val observer =
      object : Observer<WorkInfo?> {

        override fun onChanged(value: WorkInfo?) {
          val workInfo = value
          if (workerId in endedWorkerIds) {
            // A new download replaced this one, and endReplacedDownloads logged how it ended.
            liveData.removeObserver(this)
            return
          }
          // Read the latest model: observePendingDownload may have replaced it.
          val model = download.model
          if (workInfo == null) {
            // WorkManager reports null only after it reported the work, once the work is deleted,
            // e.g. replaced by work enqueued without this repository. Whatever replaced it reports
            // the model's status, so only log how this download ended.
            Log.d(TAG, "worker $workerId was deleted")
            endObserving(finalStatus = null)
            logCancelled(workerId, download)
            return
          }

          when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
              val receivedBytes = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
              val downloadRate = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
              val remainingMs = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L)
              val startUnzipping = workInfo.progress.getBoolean(KEY_MODEL_START_UNZIPPING, false)

              if (!startUnzipping) {
                if (receivedBytes != 0L) {
                  val progressTotalBytes = workInfo.progress.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
                  val totalBytes =
                    when {
                      progressTotalBytes > 0L -> progressTotalBytes
                      expectedTotalBytes > 0L -> expectedTotalBytes
                      isExtraDataOnly ->
                        model.downloadInfo.extraDataFiles(task?.id).sumOf { it.sizeInBytes }
                      else -> model.downloadInfo.totalBytes
                    }
                  download.report(
                    ModelDownloadStatus(
                      status = ModelDownloadStatusType.IN_PROGRESS,
                      totalBytes = totalBytes,
                      receivedBytes = receivedBytes,
                      bytesPerSecond = downloadRate,
                      remainingMs = remainingMs,
                    )
                  )
                }
              } else {
                download.report(ModelDownloadStatus(status = ModelDownloadStatusType.UNZIPPING))
              }
            }

            WorkInfo.State.SUCCEEDED -> {
              val finalStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
              endObserving(finalStatus)
              Log.d(TAG, "worker $workerId success")
              download.report(finalStatus)
              if (isExtraDataOnly) {
                sendNotification(
                  title =
                    context.getString(R.string.download_extra_data_notification_title_success),
                  text =
                    context
                      .getString(R.string.download_extra_data_notification_content_success)
                      .format(
                        model.downloadInfo.optionalComponentsLabel(context, task?.id).ifEmpty {
                          model.name
                        }
                      ),
                  taskId = task?.id ?: DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID,
                  modelName = model.name,
                )
              } else {
                sendNotification(
                  title = context.getString(R.string.notification_title_success),
                  text =
                    context.getString(R.string.notification_content_success).format(model.name),
                  taskId = task?.id ?: DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID,
                  modelName = model.name,
                )
              }

              logDownloadOutcome(
                workerId = workerId,
                modelName = model.name,
                eventType = EVENT_TYPE_SUCCESS,
              )
            }

            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> {
              val errorMessage =
                workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: ""
              val authError =
                DownloadAuthError.fromName(
                  workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_AUTH_ERROR)
                )
              Log.d(TAG, "worker $workerId ${workInfo.state}: $errorMessage")
              val outcome =
                UnsuccessfulDownloadOutcome.from(
                  cancellation = DownloadCancellation.of(workInfo.state, download.cancelledByUser),
                  authError = authError,
                  errorType = workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_TYPE),
                )
              if (outcome.notifyFailure) {
                val (title, text) =
                  if (isExtraDataOnly) {
                    context.getString(R.string.download_extra_data_notification_title_fail) to
                      context
                        .getString(R.string.download_extra_data_notification_content_fail)
                        .format(
                          model.downloadInfo.optionalComponentsLabel(context, task?.id).ifEmpty {
                            model.name
                          }
                        )
                  } else {
                    context.getString(R.string.notification_title_fail) to
                      context.getString(R.string.notification_content_fail).format(model.name)
                  }
                sendNotification(title = title, text = text, taskId = "", modelName = "")
              }
              val finalStatus =
                ModelDownloadStatus(
                  status = outcome.status,
                  errorMessage = errorMessage,
                  authError = authError,
                )
              endObserving(finalStatus)
              download.report(finalStatus)
              logDownloadOutcome(
                workerId = workerId,
                modelName = model.name,
                eventType = outcome.analyticsEventType,
                errorType = outcome.analyticsErrorType,
              )
            }

            else -> {}
          }
        }

        /**
         * Stops observing the download, which has ended with [finalStatus], or null if it was
         * replaced.
         */
        private fun endObserving(finalStatus: ModelDownloadStatus?) {
          liveData.removeObserver(this)
          if (finalStatus != null) {
            endedDownloadStatuses[workerId] = finalStatus
          }
          endedWorkerIds.add(workerId)
          observedDownloads.remove(workerId)
        }
      }

    if (Looper.myLooper() == Looper.getMainLooper()) {
      liveData.observeForever(observer)
    } else {
      Handler(Looper.getMainLooper()).post { liveData.observeForever(observer) }
    }
  }

  @SuppressLint("PendingIntentMutability")
  private fun sendNotification(title: String, text: String, taskId: String, modelName: String) {
    // Don't send notification if app is in foreground.
    if (lifecycleProvider.isAppInForeground) {
      return
    }

    val channelId = "download_notification"
    val channelName = "AI Edge Gallery download notification"

    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    val importance = NotificationManager.IMPORTANCE_HIGH
    val channel = NotificationChannel(channelId, channelName, importance)
    val notificationManager: NotificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)

    val intent: Intent
    if (taskId.isEmpty()) {
      // If taskId is empty, it's a failed download. Just open the app's main screen.
      intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    }
    // Download from global model manager. Open the global model manager screen.
    else if (taskId == DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID) {
      intent =
        Intent(Intent.ACTION_VIEW, "com.google.ai.edge.gallery://global_model_manager".toUri())
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    } else {

      // Otherwise, create the deep link as before.
      intent =
        Intent(
            Intent.ACTION_VIEW,
            "com.google.ai.edge.gallery://model/$taskId/${modelName}".toUri(),
          )
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    }

    // Create a PendingIntent
    val pendingIntent: PendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, channelId)
        // TODO: replace icon.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

    with(NotificationManagerCompat.from(context)) {
      // notificationId is a unique int for each notification that you must define
      if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        // Permission not granted, return or handle accordingly. In real app, request permission.
        return
      }
      val notificationId =
        (modelName.hashCode() xor taskId.hashCode()).let { if (it == 0) 1 else it }
      notify(notificationId, builder.build())
    }
  }
}
