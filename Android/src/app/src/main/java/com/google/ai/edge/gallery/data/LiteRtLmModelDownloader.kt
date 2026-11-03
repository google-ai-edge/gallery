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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModelDownloader] implementation for file-backed models (`RuntimeType.LITERT_LM` and
 * `RuntimeType.UNKNOWN`) downloaded via [DownloadRepository] into the app's models directory.
 */
class LiteRtLmModelDownloader(
  context: Context,
  private val downloadRepository: DownloadRepository,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloader {

  private val storageManager = DownloadStorageManager(context)

  override suspend fun queryStatus(model: Model): ModelDownloadStatus =
    withContext(ioDispatcher) { storageManager.getModelDownloadStatus(model) }

  override fun download(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (Model, ModelDownloadStatus) -> Unit,
  ) {
    downloadRepository.downloadModel(
      task = task,
      model = model,
      includeExtraDataFiles = includeExtraDataFiles,
      onStatusUpdated = onStatusUpdated,
    )
  }

  override fun cancel(model: Model) {
    downloadRepository.cancelDownloadModel(model)
  }

  override suspend fun delete(model: Model) {
    withContext(ioDispatcher) {
      if (model.downloadInfo.imported) {
        storageManager.deleteFilesFromImportDir(model.downloadInfo.downloadFileName)
      } else {
        storageManager.deleteDirFromModelsDir(model.normalizedName)
      }
    }
  }
}
