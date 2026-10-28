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

/** Represents the availability and installation state of an individual language pack. */
sealed interface LanguageState {
  /** BCP-47 language tag identifying the language (e.g. "en-US", "es-ES"). */
  val code: String

  /** Probing language status asynchronously. */
  data class Checking(override val code: String) : LanguageState

  /** Language assets are installed on device and ready for synthesis or recognition. */
  data class Installed(override val code: String) : LanguageState

  /** Language is offered on this device and available to download. */
  data class Downloadable(override val code: String, val totalBytes: Long? = null) : LanguageState

  /**
   * Download of language pack assets is currently in progress.
   *
   * @property stalled Whether nothing in the app is receiving the download's progress: the app's
   *   download reported nothing for a while, or the engine reports the pack downloading while no
   *   download runs in the app. The user can request the download again.
   */
  data class Downloading(
    override val code: String,
    val progress: DownloadProgress = DownloadProgress.INDETERMINATE,
    val stalled: Boolean = false,
  ) : LanguageState

  /** Language pack download is scheduled or queued by the backend system. */
  data class Queued(
    override val code: String,
    val reason: QueueReason = QueueReason.SCHEDULED_BY_SYSTEM,
  ) : LanguageState

  /** Language pack download failed. */
  data class Failed(override val code: String, val message: String) : LanguageState

  /**
   * Querying status for this language failed due to a transient probe error.
   *
   * Note: [message] is for diagnostic logging only; UI components must render localized strings.
   */
  data class CheckFailed(override val code: String, val message: String) : LanguageState

  /** Language is not offered by the engine for this device. */
  data class NotOffered(override val code: String) : LanguageState
}
