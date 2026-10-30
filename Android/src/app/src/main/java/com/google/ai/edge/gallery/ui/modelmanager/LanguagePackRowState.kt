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

import com.google.ai.edge.gallery.data.ModelDownloadFailureReason
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType

/** What a row in a speech model card's "Supported languages" list shows for one language. */
sealed interface LanguagePackUiStatus {
  /** The install status hasn't been reported yet. */
  data object Checking : LanguagePackUiStatus

  /** The language is installed and ready to use. */
  data object Installed : LanguagePackUiStatus

  /** The language can be downloaded. */
  data object Downloadable : LanguagePackUiStatus

  /**
   * The language is downloading; [totalBytes] is `0` when the size is unknown.
   *
   * @property stalled Whether nothing in the app is receiving the download's progress, so the user
   *   can request it again.
   */
  data class Downloading(
    val receivedBytes: Long,
    val totalBytes: Long,
    val stalled: Boolean = false,
  ) : LanguagePackUiStatus

  /** The last download or status query failed; the user can retry the download. */
  data object Failed : LanguagePackUiStatus

  /** The language can't be installed on this device, so its row is hidden. */
  data object Unavailable : LanguagePackUiStatus
}

/** One visible row of the "Supported languages" list. */
data class LanguagePackRowState(val languageCode: String, val status: LanguagePackUiStatus)

/** Maps a language pack's download status, or `null` if not reported yet, to its row status. */
fun ModelDownloadStatus?.toLanguagePackUiStatus(): LanguagePackUiStatus {
  val downloadStatus = this ?: return LanguagePackUiStatus.Checking
  return when (downloadStatus.status) {
    ModelDownloadStatusType.SUCCEEDED -> LanguagePackUiStatus.Installed
    ModelDownloadStatusType.NOT_DOWNLOADED,
    ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> LanguagePackUiStatus.Downloadable
    ModelDownloadStatusType.IN_PROGRESS,
    ModelDownloadStatusType.UNZIPPING ->
      LanguagePackUiStatus.Downloading(
        receivedBytes = downloadStatus.receivedBytes,
        totalBytes = downloadStatus.totalBytes,
      )
    ModelDownloadStatusType.FAILED ->
      when (downloadStatus.failureReason) {
        ModelDownloadFailureReason.STALLED ->
          LanguagePackUiStatus.Downloading(
            receivedBytes = downloadStatus.receivedBytes,
            totalBytes = downloadStatus.totalBytes,
            stalled = true,
          )
        null -> LanguagePackUiStatus.Failed
      }
    ModelDownloadStatusType.UNAVAILABLE -> LanguagePackUiStatus.Unavailable
  }
}

/**
 * Builds the rows of the "Supported languages" list in [languageCodes] order, dropping languages
 * that are [LanguagePackUiStatus.Unavailable] on this device.
 *
 * @param statuses Download status by language code; a missing code shows as
 *   [LanguagePackUiStatus.Checking].
 */
internal fun buildLanguagePackRows(
  languageCodes: List<String>,
  statuses: Map<String, ModelDownloadStatus>,
): List<LanguagePackRowState> =
  languageCodes
    .map { code -> LanguagePackRowState(code, statuses[code].toLanguagePackUiStatus()) }
    .filterNot { it.status == LanguagePackUiStatus.Unavailable }

/** Playback status of a TTS voice profile sample preview. */
enum class VoicePreviewStatus {
  IDLE,
  LOADING,
  PLAYING,
}

/** UI state of the active TTS voice profile sample preview across engines. */
data class VoicePreviewUiState(
  val activeEngineId: String? = null,
  val activeVoiceId: String? = null,
  val status: VoicePreviewStatus = VoicePreviewStatus.IDLE,
)

/**
 * The "Supported languages" list of a speech model, shown by `LanguagePacksPanel` on Model Manager
 * cards and in the Config side sheet's Audio tab.
 *
 * @property showInfoTooltip Whether the header shows the info tooltip explaining how to uninstall
 *   ML Kit language data in system settings.
 */
data class SupplementaryLanguagesUiState(
  val rows: List<SupplementaryLanguageRowUiState>,
  val showInfoTooltip: Boolean = false,
)

/**
 * One language in [SupplementaryLanguagesUiState].
 *
 * @property isSelectable Whether the row is a radio button that picks this installed language.
 */
data class SupplementaryLanguageRowUiState(
  val languageCode: String,
  val status: SupplementaryLanguageStatus,
  val isSelectable: Boolean = false,
  val isSelected: Boolean = false,
)

/** What a row of the "Supported languages" list shows for one language. */
sealed interface SupplementaryLanguageStatus {
  /** The language's status is being checked. */
  data object Checking : SupplementaryLanguageStatus

  /** The language is installed. */
  data object Installed : SupplementaryLanguageStatus

  /** The language comes bundled with a model that hasn't been downloaded yet. */
  data object Bundled : SupplementaryLanguageStatus

  /** The language can be downloaded. */
  data object Downloadable : SupplementaryLanguageStatus

  /**
   * The language is downloading; [totalBytes] is `0` when the size is unknown.
   *
   * @property stalled Whether nothing in the app is receiving the download's progress, so the user
   *   can request it again.
   */
  data class Downloading(
    val receivedBytes: Long,
    val totalBytes: Long,
    val stalled: Boolean = false,
  ) : SupplementaryLanguageStatus

  /** The download is queued by the system, for example until the device is on Wi-Fi. */
  data object Queued : SupplementaryLanguageStatus

  /** The last download failed; the user can download it again. */
  data object Failed : SupplementaryLanguageStatus

  /** The status couldn't be checked; the user can retry. */
  data object CheckFailed : SupplementaryLanguageStatus

  /** The engine doesn't offer the language on this device; shown greyed out with Retry. */
  data object NotOffered : SupplementaryLanguageStatus
}

/** Maps a language's status in the Config side sheet to its row status in the language list. */
fun LanguagePackUiStatus.toSupplementaryLanguageStatus(): SupplementaryLanguageStatus =
  when (this) {
    LanguagePackUiStatus.Checking -> SupplementaryLanguageStatus.Checking
    LanguagePackUiStatus.Installed -> SupplementaryLanguageStatus.Installed
    LanguagePackUiStatus.Downloadable -> SupplementaryLanguageStatus.Downloadable
    is LanguagePackUiStatus.Downloading ->
      SupplementaryLanguageStatus.Downloading(receivedBytes, totalBytes, stalled)
    LanguagePackUiStatus.Failed -> SupplementaryLanguageStatus.Failed
    LanguagePackUiStatus.Unavailable -> SupplementaryLanguageStatus.NotOffered
  }
