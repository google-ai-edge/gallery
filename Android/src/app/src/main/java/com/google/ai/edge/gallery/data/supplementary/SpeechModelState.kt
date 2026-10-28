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

package com.google.ai.edge.gallery.data.supplementary

/**
 * Unified state contract for supplementary speech models (TTS and ASR).
 *
 * Combines availability gating, download progress, and runtime engine health.
 */
sealed interface SpeechModelState {
  /** Model is hidden from UI on this device. */
  data class Hidden(val reason: HiddenReason) : SpeechModelState

  /** Initial availability check is in progress. Visible with a loader; not selectable. */
  data object Checking : SpeechModelState

  /** Required system component (e.g. Speech Services) is outdated and requires an update. */
  data class NeedsUpdate(val component: UpdatableComponent, val storeUrl: String) : SpeechModelState

  /** Feature requires user opt-in or enrollment in an early access program. */
  data class NeedsOptIn(val guideUrl: String) : SpeechModelState

  /** Eligible device hardware, but the service is currently not serving this model. */
  data class Unavailable(val reason: SpeechUnavailabilityReason, val learnMoreUrl: String? = null) :
    SpeechModelState

  /**
   * Checking status failed due to a transient error (e.g. timeout or Binder failure).
   *
   * Note: [message] is for diagnostic logging only; UI components must render localized strings.
   */
  data class CheckFailed(val message: String, val retryable: Boolean = true) : SpeechModelState

  /** Full model asset is supported and ready to download (e.g. ASR Advanced, LiteRT models). */
  data class Downloadable(val totalBytes: Long? = null) : SpeechModelState

  /** Model is usable, but requires at least one language pack to be installed before use. */
  data class NeedsLanguagePack(val languages: List<LanguageState> = emptyList()) : SpeechModelState

  /** Asset download is actively running. */
  data class Downloading(
    val progress: DownloadProgress = DownloadProgress.INDETERMINATE,
    val cancellable: Boolean = false,
    val continuesInBackground: Boolean = true,
  ) : SpeechModelState

  /** Download is queued or waiting for network/scheduling conditions. */
  data class Queued(val reason: QueueReason = QueueReason.SCHEDULED_BY_SYSTEM) : SpeechModelState

  /**
   * Model is fully primed and ready for synthesis or recognition.
   *
   * Only models in [Ready] state are selectable as the default engine.
   */
  data class Ready(
    val languages: List<LanguageState> = emptyList(),
    val voices: VoiceList = VoiceList.Empty,
    val engine: EnginePhase = EnginePhase.IDLE,
  ) : SpeechModelState

  /** Engine encountered a runtime failure during synthesis, recognition, or warm-up. */
  data class EngineError(val reason: EngineErrorReason, val recoverable: Boolean = true) :
    SpeechModelState
}

/** Reasons why a speech model is hidden from the UI. */
enum class HiddenReason {
  DEVICE_NOT_COMPATIBLE,
  FEATURE_FLAG_OFF,
  NO_ENGINE_IN_BUILD,
}

/** Identifies an external system component that can be updated via the app store. */
enum class UpdatableComponent {
  SPEECH_SERVICES,
  AICORE,
}

/** Reason why a download is queued. */
enum class QueueReason {
  WAITING_FOR_WIFI,
  SCHEDULED_BY_SYSTEM,
}

/** Current runtime activity phase of an engine. */
enum class EnginePhase {
  IDLE,
  WARMING,
  BUSY,
}

/** Root cause of a runtime engine failure. */
enum class EngineErrorReason {
  NOT_INSTALLED_AT_RUNTIME,
  INITIALIZATION_FAILED,
  SYNTHESIS_FAILED,
  RECOGNITION_FAILED,
  SERVICE_DISCONNECTED,
}

/** Byte-level download progress. */
data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) {
  val fraction: Float
    get() =
      if (totalBytes > 0L) {
        (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
      } else {
        0f
      }

  companion object {
    val INDETERMINATE = DownloadProgress(0L, 0L)
  }
}

/** Represents voice options available for a TTS model. */
sealed interface VoiceList {
  data class Loaded(val voices: List<String>, val voiceLocales: Map<String, String> = emptyMap()) :
    VoiceList

  data object Empty : VoiceList

  data class NotLoaded(val retryable: Boolean = true) : VoiceList
}

/** Single canonical selectability rule: a model is selectable as default iff it is [Ready]. */
val SpeechModelState.isSelectableAsDefault: Boolean
  get() = this is SpeechModelState.Ready
