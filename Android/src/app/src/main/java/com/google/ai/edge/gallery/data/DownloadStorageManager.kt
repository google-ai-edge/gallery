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
import android.os.StatFs
import android.util.Log
import com.google.ai.edge.gallery.common.getModelStorageDir
import java.io.File

private const val TAG = "AGDownloadStorageMgr"
private const val PLACEHOLDER_FILENAME = "placeholder"

/** The size of a repository download of a model's files. */
class RepositoryDownloadSize(
  /** The total size of the files the download writes. */
  val totalBytes: Long,
  /** Returns how many more bytes the download has to write, given the files already on disk. */
  val remainingBytes: () -> Long,
  /**
   * The room needed to unzip the download's zip files. A zip file is unzipped next to itself before
   * it's deleted, so it counts twice (download + unzipped files), taking its unzipped files to be
   * about as big as it.
   */
  val unzipBytes: Long,
) {
  /** Returns how much free storage the download still needs. */
  fun requiredStorageBytes(): Long = remainingBytes() + unzipBytes
}

/**
 * Manages on-disk files, partial `.tmp` files, unpacked extra-data markers, and storage sizing for
 * file-backed models in the app's models directory.
 */
class DownloadStorageManager(
  private val context: Context,
  private val modelsDir: File = getModelStorageDir(context),
) {
  companion object {
    /** Whether the download worker unzips a downloaded file named [fileName]. */
    fun isZipFileName(fileName: String): Boolean = fileName.endsWith(".zip", ignoreCase = true)
  }

  /**
   * Returns the free storage, in bytes, where models are downloaded, or `null` if it can't be read.
   */
  fun defaultAvailableStorageBytes(): Long? =
    try {
      StatFs(modelsDir.absolutePath).availableBytes
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Failed to read the free storage of ${modelsDir.absolutePath}", e)
      null
    }

  /**
   * Returns the size of [model]'s files that a repository download writes, with its extra data
   * files for [taskId] if [includeExtraDataFiles].
   */
  fun repositoryDownloadSize(
    model: Model,
    taskId: String?,
    includeExtraDataFiles: Boolean,
  ): RepositoryDownloadSize {
    val extraFiles =
      if (includeExtraDataFiles) model.downloadInfo.extraDataFiles(taskId) else emptyList()
    val fileNames =
      listOf(model.downloadInfo.downloadFileName) + extraFiles.map { it.downloadFileName }
    val totalBytes = model.downloadInfo.sizeInBytes + extraFiles.sumOf { it.sizeInBytes }
    val zipBytes =
      (if (model.downloadInfo.isZip || isZipFileName(model.downloadInfo.downloadFileName)) {
        model.downloadInfo.sizeInBytes
      } else {
        0L
      }) + extraFiles.filter { isZipFileName(it.downloadFileName) }.sumOf { it.sizeInBytes }
    return RepositoryDownloadSize(
      totalBytes = totalBytes,
      remainingBytes = { (totalBytes - bytesOnDisk(model, fileNames)).coerceAtLeast(0L) },
      unzipBytes = zipBytes,
    )
  }

  /**
   * Whether an earlier download of [model] left a partial `.tmp` file of its model file, or of its
   * extra data files for [taskId] if [includeExtraDataFiles], or finished at least one file of a
   * multi-file download while leaving another unfinished, which the next download resumes.
   */
  fun hasResumablePartialDownload(
    model: Model,
    taskId: String?,
    includeExtraDataFiles: Boolean,
  ): Boolean {
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      return false
    }
    val extraFiles =
      if (includeExtraDataFiles) model.downloadInfo.extraDataFiles(taskId) else emptyList()
    val fileNames =
      (listOf(model.downloadInfo.downloadFileName) + extraFiles.map { it.downloadFileName })
        .filter { it.isNotEmpty() }
    val hasTmpBytes = fileNames.any { fileName ->
      File(model.getPath(context = context, fileName = "$fileName.$TMP_FILE_EXT")).length() > 0L
    }
    if (hasTmpBytes) {
      return true
    }
    return fileNames.size > 1 &&
      fileNames.any { fileName -> isTargetFileDownloaded(model, fileName) } &&
      fileNames.any { fileName -> !isTargetFileDownloaded(model, fileName) }
  }

  private fun isTargetFileDownloaded(model: Model, fileName: String): Boolean {
    if (fileName.isEmpty()) return false
    val tmpFile = File(model.getPath(context = context, fileName = "$fileName.$TMP_FILE_EXT"))
    if (tmpFile.exists()) return false
    val originalFile = File(model.getPath(context = context, fileName = fileName))
    return originalFile.isFile ||
      isUnzippedMainFileOnDisk(model, fileName) ||
      isUnpackedExtraFileOnDisk(model, fileName)
  }

  /**
   * Whether [fileName] is [model]'s main zip file and was already unzipped, which deletes the zip
   * file.
   */
  private fun isUnzippedMainFileOnDisk(model: Model, fileName: String): Boolean =
    fileName == model.downloadInfo.downloadFileName &&
      model.downloadInfo.isZip &&
      model.downloadInfo.unzipDir.isNotEmpty() &&
      File(model.getPath(context = context, fileName = model.downloadInfo.unzipDir)).isDirectory

  private fun isUnpackedExtraFileOnDisk(model: Model, fileName: String): Boolean =
    fileName != model.downloadInfo.downloadFileName &&
      isZipFileName(fileName) &&
      File(model.getPath(context = context, fileName = "$fileName.$UNPACKED_FILE_EXT")).exists()

  /**
   * Returns how many bytes of [model]'s files named [fileNames] are already on disk: the partial
   * `.tmp` file of each file, the file itself if an earlier download finished it, or the declared
   * size of a zip file that was already unzipped, which deletes it.
   */
  fun bytesOnDisk(model: Model, fileNames: List<String>): Long = fileNames.sumOf { fileName ->
    if (fileName.isEmpty()) return@sumOf 0L
    val tmpFile = File(model.getPath(context = context, fileName = "$fileName.$TMP_FILE_EXT"))
    val originalFile = File(model.getPath(context = context, fileName = fileName))
    when {
      tmpFile.exists() -> tmpFile.length()
      originalFile.isFile -> originalFile.length()
      isUnzippedMainFileOnDisk(model, fileName) -> model.downloadInfo.sizeInBytes
      isUnpackedExtraFileOnDisk(model, fileName) ->
        model.downloadInfo.extraDataFiles
          .firstOrNull { it.downloadFileName == fileName }
          ?.sizeInBytes ?: 0L
      else -> 0L
    }
  }

  /**
   * Deletes stale files of [model]'s target version before starting a fresh download, preserving
   * any older installed versions (`updatableModelFiles`) and any extra data files that are already
   * downloaded or currently downloading when [cleanExtraDataFiles] is `false`.
   */
  fun cleanupStaleCurrentVersionFiles(model: Model, taskId: String?, cleanExtraDataFiles: Boolean) {
    if (model.downloadInfo.imported) {
      deleteFilesFromImportDir(model.downloadInfo.downloadFileName)
      return
    }
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      File("${model.downloadInfo.localModelFilePathOverride}.$TMP_FILE_EXT").delete()
    }
    val versionDir =
      File(
        modelsDir,
        listOf(model.normalizedName, model.downloadInfo.version).joinToString(File.separator),
      )
    if (!versionDir.exists()) {
      return
    }
    val downloadFileName = model.downloadInfo.downloadFileName
    if (downloadFileName.isNotEmpty()) {
      File(versionDir, downloadFileName).deleteRecursively()
      File(versionDir, "$downloadFileName.$TMP_FILE_EXT").delete()
      File(versionDir, "$downloadFileName.$UNPACKED_FILE_EXT").delete()
    }
    if (model.downloadInfo.isZip && model.downloadInfo.unzipDir.isNotEmpty()) {
      File(versionDir, model.downloadInfo.unzipDir).deleteRecursively()
    }
    if (cleanExtraDataFiles) {
      for (extraFile in model.downloadInfo.extraDataFiles(taskId)) {
        File(versionDir, extraFile.downloadFileName).deleteRecursively()
        File(versionDir, "${extraFile.downloadFileName}.$TMP_FILE_EXT").delete()
        File(versionDir, "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT").delete()
        File(versionDir, extraFile.downloadFileName.substringBeforeLast(".")).deleteRecursively()
        File(versionDir, extraFile.name).deleteRecursively()
      }
    }
    versionDir.delete()
  }

  /**
   * Deletes the partial `.tmp` files of [model]'s paused download, preserving other installed
   * versions and keeping extra data `.tmp` files while [isExtraDataDownloading] is true.
   */
  fun discardPartialFiles(model: Model, isExtraDataDownloading: Boolean) {
    val versionDir =
      if (model.downloadInfo.imported) {
        File(modelsDir, IMPORTS_DIR)
      } else {
        File(
          modelsDir,
          listOf(model.normalizedName, model.downloadInfo.version).joinToString(File.separator),
        )
      }
    val fileNames =
      listOf(model.downloadInfo.downloadFileName) +
        if (isExtraDataDownloading) emptyList()
        else model.downloadInfo.extraDataFiles.map { it.downloadFileName }
    for (fileName in fileNames.filter { it.isNotEmpty() }) {
      val tmpFile = File(versionDir, "$fileName.$TMP_FILE_EXT")
      if (tmpFile.exists() && !tmpFile.delete()) {
        Log.w(TAG, "Failed to delete the partial file '${tmpFile.name}'")
      }
    }
    // Remove the version's dir if nothing else is in it. File.delete() keeps a non-empty dir.
    if (!model.downloadInfo.imported) {
      versionDir.delete()
    }
  }

  fun isExtraDataPresentOnDisk(model: Model, taskId: String? = null): Boolean {
    val extraFiles = model.downloadInfo.extraDataFiles(taskId)
    if (extraFiles.isEmpty()) return false
    val modelDir = modelVersionDir(model)
    if (!modelDir.exists()) return false

    return extraFiles.any { extraFile ->
      File(modelDir, extraFile.downloadFileName).exists() ||
        File(modelDir, "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT").exists() ||
        File(modelDir, extraFile.name).exists() ||
        File(modelDir, extraFile.downloadFileName.substringBeforeLast(".")).exists()
    }
  }

  fun copyExtraDataFiles(sourceModel: Model, targetVariant: Model) {
    val srcModelDir = modelVersionDir(sourceModel)
    val destModelDir = modelVersionDir(targetVariant)
    if (!srcModelDir.exists() || srcModelDir.absolutePath == destModelDir.absolutePath) return
    if (!destModelDir.exists()) destModelDir.mkdirs()
    for (extraFile in sourceModel.downloadInfo.extraDataFiles) {
      val folderName = extraFile.downloadFileName.substringBeforeLast(".")
      val candidates =
        listOf(
          extraFile.downloadFileName,
          "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT",
          folderName,
          extraFile.name,
        )
      for (candidate in candidates) {
        val srcFile = File(srcModelDir, candidate)
        val destFile = File(destModelDir, candidate)
        if (srcFile.exists() && !destFile.exists()) {
          try {
            srcFile.copyRecursively(destFile, overwrite = false)
          } catch (e: Exception) {
            Log.w(TAG, "Failed to copy extra data file $candidate: ${e.message}")
          }
        }
      }
    }
  }

  fun cleanupCancelledExtraDataTmpFiles(model: Model) {
    val modelDir = modelVersionDir(model)
    if (!modelDir.exists()) return
    for (extraFile in model.downloadInfo.extraDataFiles) {
      val tmpFile = File(modelDir, "${extraFile.downloadFileName}.$TMP_FILE_EXT")
      if (tmpFile.exists()) tmpFile.delete()
    }
  }

  fun deleteExtraDataFilesOnDisk(model: Model, taskId: String?) {
    val modelDir = modelVersionDir(model)
    if (!modelDir.exists()) return
    for (extraFile in model.downloadInfo.extraDataFiles(taskId)) {
      File(modelDir, extraFile.downloadFileName).takeIf { it.exists() }?.deleteRecursively()
      File(modelDir, "${extraFile.downloadFileName}.$TMP_FILE_EXT").takeIf { it.exists() }?.delete()
      File(modelDir, "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT")
        .takeIf { it.exists() }
        ?.delete()
      File(modelDir, extraFile.downloadFileName.substringBeforeLast("."))
        .takeIf { it.exists() }
        ?.deleteRecursively()
      File(modelDir, extraFile.name).takeIf { it.exists() }?.deleteRecursively()
    }
  }

  private fun modelVersionDir(model: Model): File =
    File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
      ?: File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME))

  fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      return false
    }
    val tmpFilePath =
      model.getPath(
        context = context,
        fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT",
      )
    // An empty tmp file is left when a download stopped before receiving anything, which is the
    // same as not downloaded.
    return File(tmpFilePath).length() > 0L
  }

  /**
   * Retrieves the download status of [model] by checking if it's fully downloaded, partially
   * downloaded, or not downloaded at all.
   */
  fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.downloadInfo.localRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localRelativeDirPathOverride set. Set status to SUCCEEDED")
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
      isModelPartiallyDownloaded(model = model) -> {
        status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
        val tmpFilePath =
          model.getPath(
            context = context,
            fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT",
          )
        receivedBytes = File(tmpFilePath).length()
        totalBytes = model.downloadInfo.totalBytes
        Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
      }
      checkIfModelDownloaded(model, model.downloadInfo.version) -> {
        status = ModelDownloadStatusType.SUCCEEDED
        isUpdatable = false
        installedModelFile =
          ModelFile(
            fileName = model.downloadInfo.downloadFileName,
            commitHash = model.downloadInfo.version,
          )
        Log.d(TAG, "${model.name} has been downloaded.")
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
          Log.d(
            TAG,
            "${model.name} has been downloaded (updatable from ${updatableMatch.commitHash}).",
          )
        } else {
          Log.d(TAG, "${model.name} has not been downloaded.")
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

  fun isModelDownloaded(model: Model): Boolean {
    if (checkIfModelDownloaded(model, model.downloadInfo.version)) return true
    for (updatableFile in model.downloadInfo.updatableModelFiles) {
      if (updatableFile.commitHash.isEmpty()) continue
      if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
        return true
      }
    }
    return false
  }

  fun checkIfModelDownloaded(
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
          isFileInModelsDir(modelRelativePath)) ||
          (model.downloadInfo.localModelFilePathOverride.isNotEmpty() &&
            File(model.downloadInfo.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.downloadInfo.isZip &&
        model.downloadInfo.unzipDir.isNotEmpty() &&
        isFileInModelsDir(
          listOf(model.normalizedName, version, model.downloadInfo.unzipDir)
            .joinToString(File.separator)
        )

    return downloadedFileExists || unzippedDirectoryExists
  }

  fun isFileInModelsDir(fileName: String): Boolean = dirOrFileExists(File(modelsDir, fileName))

  private fun dirOrFileExists(file: File): Boolean = file.exists()

  fun deleteFileFromModelsDir(fileName: String) {
    if (fileName.isEmpty()) return
    val file = File(modelsDir, fileName)
    if (file.exists()) {
      file.delete()
    }
  }

  /**
   * Deletes files from the model imports directory whose absolute paths start with a given prefix.
   */
  fun deleteFilesFromImportDir(fileName: String) {
    val prefixAbsolutePath =
      "${modelsDir.absolutePath}${File.separator}$IMPORTS_DIR${File.separator}$fileName"
    val filesToDelete =
      File(modelsDir, IMPORTS_DIR)
        .listFiles { dirFile, name ->
          File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
        }
        .orEmpty()
    for (file in filesToDelete) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  fun deleteDirFromModelsDir(dir: String) {
    if (dir.isEmpty()) return
    val file = File(modelsDir, dir)
    if (file.exists()) {
      file.deleteRecursively()
    }
  }
}
