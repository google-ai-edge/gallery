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

package com.google.ai.edge.gallery.services.asr

/** Represents the lifecycle and availability status of an [AsrService]. */
sealed interface AsrServiceStatus {
  data object Unknown : AsrServiceStatus

  data object NotDownloaded : AsrServiceStatus

  data class Downloading(
    val progress: Float,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
  ) : AsrServiceStatus

  data object Downloaded : AsrServiceStatus

  data object Initializing : AsrServiceStatus

  data object Ready : AsrServiceStatus

  data object Recognizing : AsrServiceStatus

  data object Processing : AsrServiceStatus

  /** Feature or model is not supported on the current device hardware or setup. */
  data object Unavailable : AsrServiceStatus

  /** Engine encountered a terminal setup or recognition failure. */
  data object Failed : AsrServiceStatus
}

/** Categorizes why an [AsrService] reported [AsrServiceStatus.Unavailable]. */
enum class AsrUnavailabilityReason {
  /** Google Speech Services (`com.google.android.tts`) is missing or older than required. */
  SPEECH_SERVICES_OUTDATED_OR_MISSING,
  /** AICore (`com.google.android.aicore`) is missing or older than required. */
  AICORE_OUTDATED_OR_MISSING,
  /** The AICore speech model is not yet opted in or provisioned on this device. */
  AICORE_UNAVAILABLE,
  /** The device hardware or OS does not support this ASR engine. */
  UNSUPPORTED_DEVICE,
}
