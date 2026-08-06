/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import android.content.Context

internal enum class TranslationTtsInstallStage {
  DOWNLOADING,
  VERIFYING,
  EXTRACTING,
  VALIDATING,
  FINALIZING,
}

internal data class TranslationTtsDownloadProgress(
  val currentFileName: String,
  val downloadedBytes: Long,
  val totalBytes: Long,
  val completedFiles: Int,
  val totalFiles: Int,
  val stage: TranslationTtsInstallStage = TranslationTtsInstallStage.DOWNLOADING,
) {
  val fraction: Float?
    get() = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes.toFloat() else null
}

internal object TranslationTtsModelRepository {
  fun isInstalled(context: Context, model: TranslationTtsModel): Boolean =
    when (model) {
      TranslationTtsModel.SYSTEM -> false
      TranslationTtsModel.KOKORO -> SherpaKokoroPackageInstaller.findInstalled(context) != null
      TranslationTtsModel.SUPERTONIC_3 ->
        SherpaSupertonicPackageInstaller.findInstalled(context) != null
    }

  suspend fun ensureInstalled(
    context: Context,
    model: TranslationTtsModel,
    onProgress: (TranslationTtsDownloadProgress) -> Unit = {},
  ) {
    when (model) {
      TranslationTtsModel.SYSTEM -> Unit
      TranslationTtsModel.KOKORO ->
        SherpaKokoroPackageInstaller.ensureInstalled(context, onProgress)
      TranslationTtsModel.SUPERTONIC_3 ->
        SherpaSupertonicPackageInstaller.ensureInstalled(context, onProgress)
    }
  }

  suspend fun deleteInstalled(context: Context, model: TranslationTtsModel): Boolean {
    TranslationTtsEngineStore.release(model)
    return when (model) {
      TranslationTtsModel.SYSTEM -> false
      TranslationTtsModel.KOKORO -> SherpaKokoroPackageInstaller.deleteInstalled(context)
      TranslationTtsModel.SUPERTONIC_3 ->
        SherpaSupertonicPackageInstaller.deleteInstalled(context)
    }
  }
}
