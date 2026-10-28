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

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadFailureReason
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.supplementary.DownloadProgress
import com.google.ai.edge.gallery.data.supplementary.LanguageState
import com.google.ai.edge.gallery.data.supplementary.QueueReason
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState

/**
 * What the supplementary speech model repository should learn from an update of a supplementary
 * model's download, which `ModelManagerViewModel` still runs.
 */
internal sealed interface SupplementaryDownloadEvent {
  /** The download is in progress; show [state] on the card. */
  data class Progress(val state: SpeechModelState.Downloading) : SupplementaryDownloadEvent

  /** Speech Services queued the download to finish later; show [state] on the card. */
  data class Queued(val state: SpeechModelState.Queued) : SupplementaryDownloadEvent

  /** The download succeeded; re-check the model. */
  data object Completed : SupplementaryDownloadEvent

  /** The download failed or was cancelled; re-check the model. */
  data object Ended : SupplementaryDownloadEvent
}

/**
 * Maps a download update of [model] to the event the repository needs, or `null` when there is
 * nothing to tell it.
 *
 * Progress is only reported for models without a top-level download, since the others show it on
 * the card's regular download button. A failure that only means Speech Services queued the download
 * ([isScheduledDownloadFailure]) is reported as [SupplementaryDownloadEvent.Queued].
 *
 * @param supportsCancel Whether the model's downloader can stop the download.
 */
internal fun ModelDownloadStatus.toSupplementaryDownloadEvent(
  model: Model,
  supportsCancel: Boolean,
): SupplementaryDownloadEvent? =
  when (status) {
    ModelDownloadStatusType.IN_PROGRESS,
    ModelDownloadStatusType.UNZIPPING ->
      if (model.supportsTopLevelDownload) {
        null
      } else {
        SupplementaryDownloadEvent.Progress(
          SpeechModelState.Downloading(
            progress = DownloadProgress(bytesDownloaded = receivedBytes, totalBytes = totalBytes),
            cancellable = supportsCancel,
            continuesInBackground = !supportsCancel,
          )
        )
      }
    ModelDownloadStatusType.SUCCEEDED -> SupplementaryDownloadEvent.Completed
    ModelDownloadStatusType.FAILED ->
      if (isScheduledDownloadFailure()) {
        SupplementaryDownloadEvent.Queued(SpeechModelState.Queued(QueueReason.SCHEDULED_BY_SYSTEM))
      } else {
        SupplementaryDownloadEvent.Ended
      }
    ModelDownloadStatusType.NOT_DOWNLOADED -> SupplementaryDownloadEvent.Ended
    ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
    ModelDownloadStatusType.UNAVAILABLE -> null
  }

/**
 * Maps a language pack download update to the [LanguageState] of [languageCode]. A failure that
 * only means Speech Services queued the download ([isScheduledDownloadFailure]) is
 * [LanguageState.Queued], and a stalled one ([ModelDownloadFailureReason.STALLED]) is a stalled
 * [LanguageState.Downloading], not [LanguageState.Failed].
 */
internal fun ModelDownloadStatus.toLanguageState(languageCode: String): LanguageState =
  when (status) {
    ModelDownloadStatusType.SUCCEEDED -> LanguageState.Installed(languageCode)
    ModelDownloadStatusType.IN_PROGRESS,
    ModelDownloadStatusType.UNZIPPING ->
      LanguageState.Downloading(
        languageCode,
        DownloadProgress(bytesDownloaded = receivedBytes, totalBytes = totalBytes),
      )
    ModelDownloadStatusType.NOT_DOWNLOADED,
    ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> LanguageState.Downloadable(languageCode)
    ModelDownloadStatusType.FAILED ->
      when {
        isScheduledDownloadFailure() ->
          LanguageState.Queued(languageCode, QueueReason.SCHEDULED_BY_SYSTEM)
        failureReason == ModelDownloadFailureReason.STALLED ->
          LanguageState.Downloading(
            languageCode,
            DownloadProgress(bytesDownloaded = receivedBytes, totalBytes = totalBytes),
            stalled = true,
          )
        else -> LanguageState.Failed(languageCode, errorMessage)
      }
    ModelDownloadStatusType.UNAVAILABLE -> LanguageState.NotOffered(languageCode)
  }

/** Whether this failed download means the system scheduled the download to finish later. */
internal fun ModelDownloadStatus.isScheduledDownloadFailure(): Boolean =
  status == ModelDownloadStatusType.FAILED &&
    SCHEDULED_DOWNLOAD_MESSAGES.any { errorMessage.contains(it, ignoreCase = true) }

private val SCHEDULED_DOWNLOAD_MESSAGES = listOf("download is scheduled", "retried automatically")
