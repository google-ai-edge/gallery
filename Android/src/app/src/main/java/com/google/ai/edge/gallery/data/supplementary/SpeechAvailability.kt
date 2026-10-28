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
 * Raw availability facts reported by speech engine services.
 *
 * Decoupled from engine runtime lifecycle status (e.g. Initializing, Recognizing, Synthesizing).
 */
sealed interface SpeechAvailability {
  /** Model or language pack assets are installed on device and ready for immediate use. */
  data object Available : SpeechAvailability

  /** Model or language pack is supported on this device but must be downloaded first. */
  data class NeedsDownload(val totalBytes: Long? = null) : SpeechAvailability

  /** Download of required model or language pack assets is currently in progress. */
  data class Downloading(val receivedBytes: Long, val totalBytes: Long) : SpeechAvailability

  /** Model or language pack cannot be used on this device for the specified [reason]. */
  data class Unavailable(val reason: SpeechUnavailabilityReason) : SpeechAvailability

  /**
   * The status probe itself failed (e.g. Binder failure, gRPC exception, or timeout).
   *
   * Must never be cached as a permanent answer or treated as unsupported hardware.
   */
  data class ProbeFailed(val cause: Throwable) : SpeechAvailability
}

/** Specific reason why a speech engine reported [SpeechAvailability.Unavailable]. */
enum class SpeechUnavailabilityReason {
  /** Speech Services by Google (`com.google.android.tts`) is missing or below minimum version. */
  SPEECH_SERVICES_OUTDATED,

  /** AICore package on device is missing or below minimum supported version. */
  AICORE_OUTDATED,

  /** Device hardware or OEM build is not served by the backend or allowlist. */
  DEVICE_NOT_SERVED,

  /** User or device has not completed the required opt-in or enrollment. */
  NOT_OPTED_IN,

  /** Backend service is temporarily unavailable or not serving this device right now. */
  NOT_SERVED_RIGHT_NOW,

  /** Requested language/locale is not supported or offered by the backend engine. */
  LOCALE_NOT_OFFERED,

  /** Allowlist model is registered but no backing engine is present in this build. */
  NO_ENGINE_IN_BUILD,
}

/** Shared contract for speech engines capable of probing model and language availability. */
interface SpeechAvailabilityProbe {
  /** Probes the availability of [modelId] on the current device. */
  suspend fun checkAvailability(modelId: String): SpeechAvailability

  /** Probes whether the language pack for [languageCode] is available on this device. */
  suspend fun checkLanguageAvailability(modelId: String, languageCode: String): SpeechAvailability
}
