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

import androidx.work.WorkInfo
import com.google.ai.edge.gallery.R
import java.util.UUID

internal const val MODEL_NAME_TAG = "modelName"
internal const val TASK_ID_TAG = "taskId"
internal const val VERSION_TAG = "version"

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

/** The notification posted when a download ends while the app is in the background. */
internal enum class DownloadEndNotification {
  /** No notification: the user stopped the download, or has to act in the app anyway. */
  NONE,
  /** The download failed. */
  FAILED,
  /** The download paused and can be resumed from the app. */
  PAUSED,
}

/** How a download that ended without succeeding is reported. */
internal data class UnsuccessfulDownloadOutcome(
  /** The model's download status afterwards. */
  val status: ModelDownloadStatusType,
  /** The notification that tells the user how the download ended. */
  val notification: DownloadEndNotification,
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
     * @param partialBytes The bytes the download left on disk to resume, 0 if none.
     */
    fun from(
      cancellation: DownloadCancellation?,
      authError: DownloadAuthError?,
      errorType: String? = null,
      partialBytes: Long = 0L,
    ): UnsuccessfulDownloadOutcome =
      when {
        // Cancellations aren't failures. Logging them separately keeps the failure rate accurate.
        cancellation == DownloadCancellation.USER ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notification = DownloadEndNotification.NONE,
            analyticsEventType = EVENT_TYPE_CANCELLED,
          )
        cancellation == DownloadCancellation.APP ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notification = DownloadEndNotification.NONE,
            analyticsEventType = EVENT_TYPE_CANCELLED_BY_APP,
          )
        authError != null ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.NOT_DOWNLOADED,
            notification = DownloadEndNotification.NONE,
            analyticsEventType = EVENT_TYPE_AUTH_REQUIRED,
          )
        // A network problem that left bytes to resume pauses the download: the user can resume it
        // once the network is back, so the notification says it paused rather than failed. It's
        // still logged as a failure, to keep the failure rate comparable.
        isTransientDownloadFailure(errorType) && partialBytes > 0L ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
            notification = DownloadEndNotification.PAUSED,
            analyticsEventType = EVENT_TYPE_FAILURE,
            analyticsErrorType = errorType,
          )
        else ->
          UnsuccessfulDownloadOutcome(
            status = ModelDownloadStatusType.FAILED,
            notification = DownloadEndNotification.FAILED,
            analyticsEventType = EVENT_TYPE_FAILURE,
            analyticsErrorType = errorType?.ifEmpty { null } ?: ERROR_TYPE_UNKNOWN,
          )
      }
  }
}

/**
 * The task ID with which a download notification opens the global model manager, for downloads
 * started outside a task.
 */
internal const val DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID = "___"

/** What the notification for a download that ended shows, and what tapping it opens. */
internal data class DownloadEndNotificationContent(
  /** The string resource of the notification's title. */
  val titleResId: Int,
  /** The string resource of the notification's text, which takes [displayName]. */
  val textResId: Int,
  /** The name of what was downloaded: the model, or its extra data files. */
  val displayName: String,
  /**
   * The task whose model page tapping the notification opens:
   * [DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID] for the global model manager, or empty for the
   * app's main screen.
   */
  val taskId: String,
  /** The model whose page tapping the notification opens, empty for the app's main screen. */
  val modelName: String,
)

/**
 * Returns what the [notification] for a download that ended shows and opens, or null for
 * [DownloadEndNotification.NONE].
 *
 * A failure notification opens the app's main screen. A pause notification opens the model, as the
 * notification for a finished download does, so that the user can resume the download there.
 *
 * @param modelName The name of the downloaded model.
 * @param isExtraDataOnly Whether only the model's extra data files were downloaded.
 * @param extraDataLabel The label of the extra data files, shown instead of [modelName] for an
 *   extra data download unless it's empty.
 * @param taskId The ID of the task the download was started from, or null if it was started from
 *   the global model manager.
 */
internal fun downloadEndNotificationContent(
  notification: DownloadEndNotification,
  modelName: String,
  isExtraDataOnly: Boolean,
  extraDataLabel: String,
  taskId: String?,
): DownloadEndNotificationContent? {
  val displayName = if (isExtraDataOnly) extraDataLabel.ifEmpty { modelName } else modelName
  return when (notification) {
    DownloadEndNotification.NONE -> null
    DownloadEndNotification.FAILED ->
      DownloadEndNotificationContent(
        titleResId =
          if (isExtraDataOnly) R.string.download_extra_data_notification_title_fail
          else R.string.notification_title_fail,
        textResId =
          if (isExtraDataOnly) R.string.download_extra_data_notification_content_fail
          else R.string.notification_content_fail,
        displayName = displayName,
        taskId = "",
        modelName = "",
      )
    DownloadEndNotification.PAUSED ->
      DownloadEndNotificationContent(
        titleResId =
          if (isExtraDataOnly) R.string.download_extra_data_notification_title_paused
          else R.string.notification_title_paused,
        textResId =
          if (isExtraDataOnly) R.string.download_extra_data_notification_content_paused
          else R.string.notification_content_paused,
        displayName = displayName,
        taskId = taskId ?: DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID,
        modelName = modelName,
      )
  }
}

/**
 * Returns the string resource that explains a failed download to the user, or null to show the
 * worker's own error message.
 *
 * @param errorType The worker's error category (see [KEY_MODEL_DOWNLOAD_ERROR_TYPE]): the class
 *   name of the exception that failed the download.
 * @param errorMessage The worker's error message (see [KEY_MODEL_DOWNLOAD_ERROR_MESSAGE]).
 */
internal fun downloadFailureMessageRes(errorType: String?, errorMessage: String?): Int? =
  when {
    isOutOfStorageFailure(errorMessage, errorType) ->
      R.string.model_download_error_not_enough_storage
    errorType in NO_INTERNET_ERROR_TYPES -> R.string.model_download_error_no_internet
    errorType == TIMEOUT_ERROR_TYPE -> R.string.model_download_error_timed_out
    errorType in CONNECTION_LOST_ERROR_TYPES -> R.string.model_download_error_connection_lost
    isTransientHttpError(errorType) -> R.string.model_download_error_server_unavailable
    else -> null
  }

/**
 * Whether a download failed because the device ran out of storage, given its error message and
 * error type.
 */
internal fun isOutOfStorageFailure(errorMessage: String?, errorType: String? = null): Boolean =
  errorType == ERROR_TYPE_OUT_OF_STORAGE ||
    // Writes to a full disk fail with "write failed: ENOSPC (No space left on device)".
    errorMessage?.contains("ENOSPC") == true

/**
 * Whether a download that failed with [errorType] (see [KEY_MODEL_DOWNLOAD_ERROR_TYPE]) may succeed
 * when it's simply run again: the network or the server had a temporary problem. HTTP 4xx errors
 * other than 429 (e.g. 404 or an auth error), running out of storage, and corrupt files aren't
 * transient.
 */
internal fun isTransientDownloadFailure(errorType: String?): Boolean =
  errorType in NO_INTERNET_ERROR_TYPES ||
    errorType == TIMEOUT_ERROR_TYPE ||
    errorType in CONNECTION_LOST_ERROR_TYPES ||
    isTransientHttpError(errorType)

/** Whether [errorType] is an HTTP error that the server may not return next time: 5xx or 429. */
private fun isTransientHttpError(errorType: String?): Boolean {
  if (errorType == null || !errorType.startsWith(ERROR_TYPE_HTTP_PREFIX)) {
    return false
  }
  val httpCode = errorType.removePrefix(ERROR_TYPE_HTTP_PREFIX).toIntOrNull() ?: return false
  return httpCode in 500..599 || httpCode == HTTP_TOO_MANY_REQUESTS
}

private const val HTTP_TOO_MANY_REQUESTS = 429

/** The exception that means the server stopped responding. */
private const val TIMEOUT_ERROR_TYPE = "SocketTimeoutException"

/** The exceptions that mean the download server couldn't be reached at all. */
private val NO_INTERNET_ERROR_TYPES =
  setOf("UnknownHostException", "ConnectException", "NoRouteToHostException")

/** The exceptions that mean the connection broke while the file was being downloaded. */
private val CONNECTION_LOST_ERROR_TYPES =
  setOf("SocketException", "ProtocolException", "SSLException", "EOFException")

// The `event_type` values of the `model_download` analytics event.
internal const val EVENT_TYPE_START = "start"
internal const val EVENT_TYPE_SUCCESS = "success"
internal const val EVENT_TYPE_FAILURE = "failure"
internal const val EVENT_TYPE_CANCELLED = "cancelled"
internal const val EVENT_TYPE_CANCELLED_BY_APP = "cancelled_by_app"
internal const val EVENT_TYPE_AUTH_REQUIRED = "auth_required"

internal const val EXTRA_DATA_SUFFIX = "_extra_data"

/** Returns the unique WorkManager work name of a model's download or extra data download. */
internal fun workName(modelName: String, isExtraDataOnly: Boolean): String =
  if (isExtraDataOnly) "$modelName$EXTRA_DATA_SUFFIX" else modelName

/** Returns the tag that marks the work requests of [workName]. */
internal fun modelNameTag(workName: String): String = "$MODEL_NAME_TAG:$workName"
