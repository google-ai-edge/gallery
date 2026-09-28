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
import android.util.Log
import com.google.ai.edge.gallery.common.getModelStorageDir
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGLiteRtLmDownloader"

/**
 * [ModelDownloader] implementation for file-backed models (`RuntimeType.LITERT_LM` and
 * `RuntimeType.UNKNOWN`) downloaded via [DownloadRepository] into the app's models directory.
 */
class LiteRtLmModelDownloader(
  private val context: Context,
  private val downloadRepository: DownloadRepository,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloader {

  private val modelsDir: File = getModelStorageDir(context)

  override suspend fun queryStatus(model: Model): ModelDownloadStatus =
    withContext(ioDispatcher) { queryStatusOnDisk(model) }

  private fun queryStatusOnDisk(model: Model): ModelDownloadStatus {
    if (model.downloadInfo.localRelativeDirPathOverride.isNotEmpty()) {
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L
    var isUpdatable = false
    var installedModelFile: ModelFile? = null

    when {
      isModelPartiallyDownloaded(model) -> {
        status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
        val tmpFilePath =
          model.getPath(
            context = context,
            fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT",
          )
        val tmpFile = File(tmpFilePath)
        receivedBytes = tmpFile.length()
        totalBytes = model.downloadInfo.totalBytes
      }
      checkIfModelDownloaded(model, model.downloadInfo.version) -> {
        status = ModelDownloadStatusType.SUCCEEDED
        isUpdatable = false
        installedModelFile =
          ModelFile(
            fileName = model.downloadInfo.downloadFileName,
            commitHash = model.downloadInfo.version,
          )
      }
      else -> {
        val updatableMatch =
          model.downloadInfo.updatableModelFiles.firstOrNull { updatableFile ->
            updatableFile.commitHash.isNotEmpty() &&
              checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)
          }
        if (updatableMatch != null) {
          status = ModelDownloadStatusType.SUCCEEDED
          isUpdatable = true
          installedModelFile = updatableMatch
        }
      }
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
      isUpdatable = isUpdatable,
      installedModelFile = installedModelFile,
    )
  }

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
        deleteFilesFromImportDir(model.downloadInfo.downloadFileName)
      } else {
        deleteDirFromModelsDir(model.normalizedName)
      }
    }
  }

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      return false
    }
    val tmpFilePath =
      model.getPath(
        context = context,
        fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT",
      )
    return File(tmpFilePath).exists()
  }

  private fun checkIfModelDownloaded(
    model: Model,
    version: String,
    fileName: String = model.downloadInfo.downloadFileName,
  ): Boolean {
    val modelRelativePath =
      if (model.downloadInfo.imported) {
        listOf(IMPORTS_DIR, fileName).joinToString(File.separator)
      } else {
        listOf(model.normalizedName, version, fileName).joinToString(File.separator)
      }
    val downloadedFileExists =
      fileName.isNotEmpty() &&
        ((model.downloadInfo.localModelFilePathOverride.isEmpty() &&
          File(modelsDir, modelRelativePath).exists()) ||
          (model.downloadInfo.localModelFilePathOverride.isNotEmpty() &&
            File(model.downloadInfo.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.downloadInfo.isZip &&
        model.downloadInfo.unzipDir.isNotEmpty() &&
        File(
            modelsDir,
            listOf(model.normalizedName, version, model.downloadInfo.unzipDir)
              .joinToString(File.separator),
          )
          .exists()

    return downloadedFileExists || unzippedDirectoryExists
  }

  private fun deleteFilesFromImportDir(fileName: String) {
    val prefixAbsolutePath =
      "${modelsDir.absolutePath}${File.separator}$IMPORTS_DIR${File.separator}$fileName"
    val filesToDelete =
      File(modelsDir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
      } ?: arrayOf()
    for (file in filesToDelete) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromModelsDir(dir: String) {
    if (dir.isEmpty()) return
    val file = File(modelsDir, dir)
    if (file.exists()) {
      file.deleteRecursively()
    }
  }
}
