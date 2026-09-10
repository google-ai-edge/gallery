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

import android.content.Context

/** Download and storage metadata for model files. */
data class ModelDownloadInfo(
  /**
   * The URL to download the model from.
   *
   * If the url is from HuggingFace, we will automatically prompt users to fetch access token if the
   * model is gated.
   */
  val url: String = "",

  /**
   * The size of the model file in bytes. This DOES NOT include the size of extra data files.
   *
   * This will be used to calculate download progress.
   */
  val sizeInBytes: Long = 0L,

  /**
   * The name of the downloaded model file.
   *
   * It will be used to define the file path on local device to store the downloaded model.
   * {context.getExternalFilesDir}/{normalizedName}/{version}/{downloadFileName}
   */
  val downloadFileName: String = "_",

  /**
   * (optional)
   *
   * The version of the model.
   *
   * It will be used to define the file path on local device to store the downloaded model.
   * {context.getExternalFilesDir}/{normalizedName}/{version}/{downloadFileName}
   */
  val version: String = "_",

  /**
   * (optional, experimental)
   *
   * A list of additional data files required by the model.
   */
  val extraDataFiles: List<ModelDataFile> = emptyList(),

  /** Indicates whether the model is a zip file. */
  val isZip: Boolean = false,

  /** The name of the directory to unzip the model to (if it's a zip file). */
  val unzipDir: String = "",

  /**
   * Set this to a relative path pointing to a dir (e.g., my_model/local_dir/) if you want to
   * manually manage model files instead of downloading them. This dir is relative to the app's
   * "External Files Directory", which is: /storage/emulated/0/Android/data/<app_id>/files/.
   *
   * The <app_id> depends on how the app was built:
   * - `com.google.aiedge.gallery` for builds from the GitHub source.
   * - `com.google.ai.edge.gallery` for other builds (Play store, internal, etc).
   *
   * For example, if this field is set to "my_model/local_dir/", then the location you should push
   * files to is (assuming non-github builds):
   *
   * /storage/emulated/0/Android/data/com.google.ai.edge.gallery/files/my_model/local_dir/
   *
   * You can get the full path to a specific file within your code using `Model.getPath(Context,
   * fileNameToGet)`.
   *
   * Using this field is recommended when:
   * - Your model files are not publicly accessible on the internet (e.g. private models).
   * - Your "model" or experience requires multiple files. Manually pushing these files to the
   *   device and using Model.getPath() for each one is often simpler than downloading them,
   *   especially for demos.
   */
  val localRelativeDirPathOverride: String = "",

  /**
   * When set, the app will try to use this path to find the model file.
   *
   * For testing purpose only.
   */
  val localModelFilePathOverride: String = "",

  /** Whether the model is imported or not. */
  val imported: Boolean = false,

  /**
   * The model files that this model can be upgraded from.
   *
   * If a model with the same name is already downloaded, and its information matches one of the
   * [ModelFile] entries in this list, the UI will show users some extra UI elements (e.g., an
   * update button or update info) for them to choose to update.
   */
  val updatableModelFiles: List<ModelFile> = emptyList(),

  /**
   * The information about the model update.
   *
   * If set, the UI will show users this information when they tap on the update info.
   */
  val updateInfo: String = "",
) {
  /**
   * Transient access token for downloading gated models.
   *
   * Declared in the class body so it is excluded from [equals] and [hashCode].
   */
  var accessToken: String? = null

  /**
   * The total size in bytes for this model.
   *
   * It is calculated as `sizeInBytes + sumOf(extraDataFiles.sizeInBytes)`.
   */
  val totalBytes: Long
    get() = sizeInBytes + extraDataFiles.sumOf { it.sizeInBytes }

  fun getExtraDataFile(name: String): ModelDataFile? {
    return extraDataFiles.find { it.name == name }
  }

  fun extraDataFiles(taskId: String? = null): List<ModelDataFile> {
    if (taskId != null) {
      return extraDataFiles.filter { it.isTargeted(taskId) }
    }
    return extraDataFiles
  }

  fun hasOptionalComponents(taskId: String? = null): Boolean {
    if (taskId != null) {
      return extraDataFiles.any { it.isTargeted(taskId) }
    }
    return extraDataFiles.isNotEmpty()
  }

  fun optionalComponentsDownloadLabel(context: Context, taskId: String? = null): String {
    return extraDataFiles(taskId).firstOrNull()?.downloadLabel(context) ?: ""
  }

  fun optionalComponentsLabel(context: Context? = null, taskId: String? = null): String {
    return extraDataFiles(taskId).firstOrNull()?.componentLabel(context) ?: ""
  }
}
