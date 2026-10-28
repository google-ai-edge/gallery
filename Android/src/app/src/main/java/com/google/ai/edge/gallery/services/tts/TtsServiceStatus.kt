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

package com.google.ai.edge.gallery.services.tts

/**
 * Lifecycle state of a [TtsService].
 *
 * See the [TtsService] KDoc for the transitions each lifecycle call is expected to produce.
 */
sealed class TtsServiceStatus {
  /** The backing model is not present on device and must be downloaded before use. */
  data object NotDownloaded : TtsServiceStatus()

  /** The backing model is being downloaded. */
  data object Downloading : TtsServiceStatus()

  /** The backing model is present on device but the engine is not initialized yet. */
  data object Downloaded : TtsServiceStatus()

  /** [TtsService.prepare] is running. */
  data object Initializing : TtsServiceStatus()

  /** The engine is idle and able to accept a [TtsService.synthesize] call. */
  data object Ready : TtsServiceStatus()

  /** An utterance is being synthesized or played back. */
  data object Synthesizing : TtsServiceStatus()

  /** The engine failed and cannot be used until [TtsService.prepare] succeeds again. */
  data class Failed(val error: Throwable) : TtsServiceStatus()

  /** Whether the engine has been prepared and its native model weights remain loaded. */
  val isEngineLoaded: Boolean
    get() = this is Ready || this is Synthesizing
}

/** Typed reason why a TTS engine model is unavailable on the current device. */
enum class TtsUnavailabilityReason {
  /**
   * Speech Services by Google (`com.google.android.tts`) is missing or below the minimum version.
   */
  SPEECH_SERVICES_OUTDATED_OR_MISSING,

  /** AICore / ML Kit GenAI feature reported unavailability on this device. */
  AICORE_UNAVAILABLE,

  /** The backing TTS model is unsupported or temporarily unavailable on this device. */
  UNSUPPORTED_MODEL,
}

/** Availability state of a TTS backing model on the current device. */
sealed interface TtsModelAvailability {
  /** Model assets are present on device and ready for preparation or synthesis. */
  data object Downloaded : TtsModelAvailability

  /** Model is supported on this device and can be downloaded. */
  data object NotDownloaded : TtsModelAvailability

  /**
   * Model cannot be used on this device for [reason], with an optional setup/upgrade [guideUrl].
   */
  data class Unavailable(val reason: TtsUnavailabilityReason, val guideUrl: String? = null) :
    TtsModelAvailability
}

/** Byte-level progress snapshot emitted by [TtsService.downloadModel]. */
data class TtsDownloadProgress(val downloadedBytes: Long, val totalBytes: Long)
