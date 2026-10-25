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

package com.google.ai.edge.gallery.data

enum class ModelDownloadStatusType {
  NOT_DOWNLOADED,
  PARTIALLY_DOWNLOADED,
  IN_PROGRESS,
  UNZIPPING,
  SUCCEEDED,
  FAILED,
  UNAVAILABLE,
}

/** Reason why a model is unavailable on the current device or environment. */
enum class ModelUnavailabilityReason {
  SPEECH_SERVICES_OUTDATED_OR_MISSING,
  AICORE_UNAVAILABLE,
  UNSUPPORTED_MODEL,
}

/** Structured unavailability metadata for a model with [ModelDownloadStatusType.UNAVAILABLE]. */
data class ModelUnavailability(val reason: ModelUnavailabilityReason, val guideUrl: String? = null)

/**
 * Why a download with [ModelDownloadStatusType.FAILED] stopped, when the UI treats it specially.
 */
enum class ModelDownloadFailureReason {
  /**
   * Nothing in the app is receiving the download's progress anymore: the app's download reported
   * nothing for a while, or the system service lists it as downloading while no download runs in
   * the app. The service may still finish it; the user can request it again.
   */
  STALLED
}

data class ModelDownloadStatus(
  val status: ModelDownloadStatusType,
  val totalBytes: Long = 0,
  val receivedBytes: Long = 0,
  val errorMessage: String = "",
  val bytesPerSecond: Long = 0,
  val remainingMs: Long = 0,
  val isUpdatable: Boolean = false,
  val installedModelFile: ModelFile? = null,
  val unavailability: ModelUnavailability? = null,
  /**
   * Set when the last download stopped because Hugging Face refused access, telling the UI to run
   * the sign-in or license flow before retrying.
   */
  val authError: DownloadAuthError? = null,
  /** Set when the last download failed for a reason the UI treats specially, such as stalling. */
  val failureReason: ModelDownloadFailureReason? = null,
)
