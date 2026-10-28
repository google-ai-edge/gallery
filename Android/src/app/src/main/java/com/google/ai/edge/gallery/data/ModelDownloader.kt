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

import dagger.MapKey

/**
 * Dagger multibinding key annotation for [ModelDownloader] implementations keyed by [RuntimeType].
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class RuntimeTypeKey(val value: RuntimeType)

/**
 * Strategy interface for querying availability, downloading, cancelling, and deleting a [Model] for
 * a specific [RuntimeType].
 */
interface ModelDownloader {
  /** Queries the current availability and download status of [model]. */
  suspend fun queryStatus(model: Model): ModelDownloadStatus

  /** Starts downloading [model] and reports progress and completion via [onStatusUpdated]. */
  fun download(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (Model, ModelDownloadStatus) -> Unit,
  )

  /**
   * Whether [cancel] actually stops an in-flight download. When `false`, a download keeps running
   * once started, so callers must not report it as cancelled.
   */
  val supportsCancel: Boolean
    get() = true

  /** Cancels an in-flight download for [model], if supported by the runtime. */
  fun cancel(model: Model)

  /**
   * Deletes downloaded model files or cached resources for [model], if supported by the runtime.
   */
  suspend fun delete(model: Model)
}

/**
 * Optional capability of a [ModelDownloader] whose runtime installs each language as a separate
 * pack once the model itself is available (see [RuntimeType.installsLanguagePacks]).
 *
 * Language packs are identified by BCP-47 tags such as `"ja-JP"`.
 */
interface LanguagePackDownloader {
  /**
   * Whether [model] installs each language as a separate pack. When `false`, every language the
   * model supports is bundled with the model itself, so once the model is downloaded all of its
   * languages are installed and [queryLanguagePackStatus] and [downloadLanguagePack] don't apply.
   */
  fun installsLanguagesSeparately(model: Model): Boolean = true

  /** Queries whether the [languageCode] pack for [model] is installed. */
  suspend fun queryLanguagePackStatus(model: Model, languageCode: String): ModelDownloadStatus

  /**
   * Starts downloading the [languageCode] pack for [model] and reports progress and completion via
   * [onStatusUpdated].
   */
  fun downloadLanguagePack(
    model: Model,
    languageCode: String,
    onStatusUpdated: (ModelDownloadStatus) -> Unit,
  )
}

/**
 * Optional capability of a [ModelDownloader] whose runtime can serve a [Model] only on some
 * devices.
 *
 * Models the device cannot run are hidden from the model list, unlike models that only need setup
 * (an app update or a download), which stay visible and report it through
 * [ModelDownloader.queryStatus].
 */
interface DeviceSupportChecker {
  /**
   * Whether this device can run [model]. Called off the main thread while the allowlist loads.
   * Implementations must fail open: return `true` when support cannot be determined.
   */
  suspend fun isSupportedOnDevice(model: Model): Boolean
}
