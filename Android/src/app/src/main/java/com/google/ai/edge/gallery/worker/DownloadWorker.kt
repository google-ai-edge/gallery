/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.ERROR_TYPE_HTTP_PREFIX
import com.google.ai.edge.gallery.data.ERROR_TYPE_MISSING_URL
import com.google.ai.edge.gallery.data.ERROR_TYPE_OUT_OF_STORAGE
import com.google.ai.edge.gallery.data.HuggingFaceUrls
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_AUTH_ERROR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_TYPE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_ONLY
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_IMPORTED
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

/** How a download response is written into the file's partial `.tmp` file. */
enum class ResumeAction {
  /** The response carries the rest of the file: append it to the partial file. */
  APPEND,
  /** The response carries the whole file: replace the partial file, if any. */
  OVERWRITE,
  /** The partial file can't be resumed: delete it and request the whole file again. */
  RESTART,
  /** The partial file already holds the whole file: keep it without downloading anything. */
  COMPLETE,
}

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val modelsDir = getModelStorageDir(context)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Unique notification id.
  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  @SuppressLint("ZipName")
  override suspend fun doWork(): Result {
    val isExtraDataOnly = inputData.getBoolean(KEY_MODEL_EXTRA_DATA_ONLY, false)
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = requireNotNull(inputData.getString(KEY_MODEL_COMMIT_HASH)) { "Missing version" }
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir =
      requireNotNull(inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)) { "Missing model dir" }
    val isModelImported = inputData.getBoolean(KEY_MODEL_IS_IMPORTED, false)
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    return withContext(Dispatchers.IO) {
      if (!isExtraDataOnly && (fileUrl == null || fileName == null)) {
        Result.failure(
          Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_TYPE, ERROR_TYPE_MISSING_URL).build()
        )
      } else {
        // The partial file being written, deleted if the device runs out of storage.
        var currentTmpFile: File? = null
        return@withContext try {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          if (!isExtraDataOnly && fileUrl != null && fileName != null) {
            allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          }
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            // Prepare output file's dir.
            val outputDir =
              if (isModelImported) {
                File(modelsDir, modelDir)
              } else {
                File(modelsDir, listOf(modelDir, version).joinToString(separator = File.separator))
              }
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              if (isModelImported) {
                File(
                  modelsDir,
                  listOf(modelDir, "${file.fileName}.$TMP_FILE_EXT")
                    .joinToString(separator = File.separator),
                )
              } else {
                File(
                  modelsDir,
                  listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                    .joinToString(separator = File.separator),
                )
              }
            currentTmpFile = outputTmpFile
            val originalFile = File(outputTmpFile.absolutePath.removeSuffix(".$TMP_FILE_EXT"))
            val shouldUnzip =
              (file.fileName == fileName && isZip) ||
                file.fileName.endsWith(".zip", ignoreCase = true)

            // WorkManager reruns a stopped download from its first file, so keep the files that an
            // earlier run already finished rather than downloading them again.
            if (
              isFileAlreadyDownloaded(
                originalFile = originalFile,
                tmpFile = outputTmpFile,
                unzippedDir =
                  if (shouldUnzip) {
                    unzipTargetDir(
                      outputDir = outputDir,
                      fileName = file.fileName,
                      mainFileName = fileName,
                      unzippedDir = unzippedDir,
                    )
                  } else {
                    null
                  },
              )
            ) {
              Log.d(TAG, "File '${originalFile.name}' was already downloaded. Skipping it")
              downloadedBytes += originalFile.length()
            } else {
              val authorization =
                authorizationHeaderValue(accessToken = accessToken, url = file.url)
              var partialBytes = outputTmpFile.length()
              if (partialBytes > 0) {
                Log.d(
                  TAG,
                  "File '${outputTmpFile.name}' partial size: $partialBytes. Trying to resume download",
                )
              }
              var connection =
                openDownloadConnection(
                  url = file.url,
                  authorization = authorization,
                  rangeStart = partialBytes,
                )
              Log.d(TAG, "response code: ${connection.responseCode}")
              var resumeAction =
                resumeAction(
                  responseCode = connection.responseCode,
                  contentRange = connection.getHeaderField("Content-Range"),
                  partialBytes = partialBytes,
                )
              when (resumeAction) {
                ResumeAction.COMPLETE -> {
                  Log.d(TAG, "File '${outputTmpFile.name}' was already fully downloaded")
                  connection.disconnect()
                  downloadedBytes += partialBytes
                }
                ResumeAction.RESTART -> {
                  // The partial file can't be resumed (e.g. the server sent a different range, or
                  // the partial file doesn't match the file's size): download the file again from
                  // its first byte.
                  Log.w(
                    TAG,
                    "Can't resume '${outputTmpFile.name}' (HTTP ${connection.responseCode}). " +
                      "Restarting it",
                  )
                  connection.disconnect()
                  outputTmpFile.delete()
                  partialBytes = 0L
                  connection =
                    openDownloadConnection(
                      url = file.url,
                      authorization = authorization,
                      rangeStart = 0L,
                    )
                  resumeAction = ResumeAction.OVERWRITE
                }
                ResumeAction.APPEND,
                ResumeAction.OVERWRITE -> {}
              }

              if (resumeAction != ResumeAction.COMPLETE) {
                try {
                  if (
                    connection.responseCode != HttpURLConnection.HTTP_OK &&
                      connection.responseCode != HttpURLConnection.HTTP_PARTIAL
                  ) {
                    return@withContext httpErrorResult(
                      url = file.url,
                      httpCode = connection.responseCode,
                      sentAccessToken = authorization != null,
                    )
                  }

                  // A server can ignore the range request and send the whole file with HTTP 200, so
                  // only append to the partial file when it sent exactly the requested range.
                  val append = resumeAction == ResumeAction.APPEND
                  if (append) {
                    downloadedBytes += partialBytes
                  } else {
                    Log.d(TAG, "Download starts from beginning.")
                  }

                  connection.inputStream.use { inputStream ->
                    FileOutputStream(outputTmpFile, append).use { outputStream ->
                      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                      var bytesRead: Int
                      var lastSetProgressTs: Long = 0
                      var deltaBytes = 0L
                      while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        deltaBytes += bytesRead

                        // Report progress every 200 ms.
                        val curTs = System.currentTimeMillis()
                        if (curTs - lastSetProgressTs > 200) {
                          // Calculate download rate.
                          var bytesPerMs = 0f
                          if (lastSetProgressTs != 0L) {
                            if (bytesReadSizeBuffer.size == 5) {
                              bytesReadSizeBuffer.removeAt(0)
                            }
                            bytesReadSizeBuffer.add(deltaBytes)
                            if (bytesReadLatencyBuffer.size == 5) {
                              bytesReadLatencyBuffer.removeAt(0)
                            }
                            bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                            deltaBytes = 0L
                            bytesPerMs =
                              bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                          }

                          setProgress(
                            Data.Builder()
                              .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                              .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                              .putLong(
                                KEY_MODEL_DOWNLOAD_REMAINING_MS,
                                remainingMs(
                                  totalBytes = totalBytes,
                                  downloadedBytes = downloadedBytes,
                                  bytesPerMs = bytesPerMs,
                                ),
                              )
                              // Lets an observer that attaches mid-download show the right total.
                              .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
                              .build()
                          )
                          setForeground(
                            createForegroundInfo(
                              progress =
                                progressPercent(
                                  downloadedBytes = downloadedBytes,
                                  totalBytes = totalBytes,
                                ),
                              modelName = modelName,
                            )
                          )
                          Log.d(TAG, "downloadedBytes: $downloadedBytes")
                          lastSetProgressTs = curTs
                        }
                      }
                    }
                  }
                } finally {
                  connection.disconnect()
                }
              }

              // Rename the tmp file to the original file name by removing the tmp file ext.
              if (originalFile.exists()) {
                originalFile.delete()
              }
              if (!outputTmpFile.renameTo(originalFile)) {
                throw IOException(
                  "Failed to rename '${outputTmpFile.name}' to '${originalFile.name}'"
                )
              }
              Log.d(TAG, "Download done")
            }

            // Unzip if the downloaded file is a zip.
            if (shouldUnzip && originalFile.exists()) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                when {
                  file.fileName == fileName && !unzippedDir.isNullOrEmpty() ->
                    File(outputDir, unzippedDir)
                  file.fileName != fileName -> {
                    val folderName = file.fileName.substringBeforeLast(".")
                    var hasPrefix = false
                    try {
                      ZipInputStream(BufferedInputStream(FileInputStream(originalFile))).use {
                        checkZipIn ->
                        var checkEntry = checkZipIn.nextEntry
                        while (checkEntry != null) {
                          if (checkEntry.name.startsWith("$folderName/")) {
                            hasPrefix = true
                            break
                          }
                          checkEntry = checkZipIn.nextEntry
                        }
                      }
                    } catch (e: Exception) {
                      Log.w(TAG, "Failed to inspect zip entries for prefix: ${e.message}")
                    }
                    if (hasPrefix) outputDir else File(outputDir, folderName)
                  }
                  else -> outputDir
                }
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(8192)
              ZipInputStream(BufferedInputStream(FileInputStream(originalFile))).use { zipIn ->
                var zipEntry: ZipEntry? = zipIn.nextEntry
                while (zipEntry != null) {
                  coroutineContext.ensureActive()
                  val outFile = File(destDir, zipEntry.name)
                  // Guard against Zip Slip.
                  if (
                    outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) ||
                      outFile.canonicalPath == destDir.canonicalPath
                  ) {
                    // Extract files.
                    if (!zipEntry.isDirectory) {
                      outFile.parentFile?.mkdirs()
                      FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                          coroutineContext.ensureActive()
                          fos.write(unzipBuffer, 0, len)
                        }
                      }
                    } else {
                      outFile.mkdirs()
                    }
                  } else {
                    throw SecurityException(
                      "Zip entry is outside of the target dir: ${zipEntry.name}"
                    )
                  }

                  zipIn.closeEntry()
                  zipEntry = zipIn.nextEntry
                }
              }

              // Delete the original zip file.
              originalFile.delete()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          if (isOutOfStorage(e)) {
            // Give the space the partial file holds back to the device, which is otherwise left
            // with no free storage at all, and stop the next app launch from resuming a download
            // that can't fit.
            currentTmpFile?.delete()
          }
          exceptionResult(e)
        } catch (e: CancellationException) {
          // The work was stopped. Let WorkManager handle it rather than reporting a failure.
          throw e
        } catch (e: Exception) {
          // Any other error fails the download as well (WorkManager would mark the work failed).
          // Catch it only to report its type, e.g. a foreground service that couldn't start.
          Log.e(TAG, e.message, e)
          exceptionResult(e)
        }
      }
    }
  }

  companion object {
    /** The start of a `Content-Range` header with a byte range, matched ignoring case. */
    private const val CONTENT_RANGE_BYTES_PREFIX = "bytes "

    /** The start of the `Content-Range` header of an HTTP 416 response, matched ignoring case. */
    private const val CONTENT_RANGE_UNSATISFIED_PREFIX = "bytes */"

    /**
     * Returns how to write the response to a download request into the file's partial `.tmp` file.
     *
     * @param responseCode The HTTP status code of the response.
     * @param contentRange The response's `Content-Range` header, if any.
     * @param partialBytes The size of the partial file, which is the first byte that was requested
     *   (no range is requested when it is 0).
     */
    @VisibleForTesting
    fun resumeAction(responseCode: Int, contentRange: String?, partialBytes: Long): ResumeAction {
      if (partialBytes <= 0L) {
        return ResumeAction.OVERWRITE
      }
      return when (responseCode) {
        HttpURLConnection.HTTP_PARTIAL ->
          if (contentRangeStart(contentRange) == partialBytes) ResumeAction.APPEND
          else ResumeAction.RESTART
        // An earlier run can stop after writing the last byte but before renaming the file.
        HTTP_RANGE_NOT_SATISFIABLE ->
          if (contentRangeTotal(contentRange) == partialBytes) ResumeAction.COMPLETE
          else ResumeAction.RESTART
        // HTTP 200 carries the whole file, and error responses are reported as failures.
        else -> ResumeAction.OVERWRITE
      }
    }

    /**
     * Returns the first byte of a `Content-Range: bytes <start>-<end>/<total>` header, or `null` if
     * there's no header or it can't be parsed.
     */
    private fun contentRangeStart(contentRange: String?): Long? =
      contentRange
        ?.trim()
        // The range unit is case-insensitive (RFC 9110, section 14.1).
        ?.takeIf { it.startsWith(CONTENT_RANGE_BYTES_PREFIX, ignoreCase = true) }
        ?.drop(CONTENT_RANGE_BYTES_PREFIX.length)
        ?.substringBefore("-")
        ?.trim()
        ?.toLongOrNull()

    /**
     * Returns the total file size in the `Content-Range` header of an HTTP 416 response, which has
     * no byte range, only an asterisk followed by the total size (e.g. `bytes *` + `/5000`).
     * Returns `null` if there's no header or it can't be parsed.
     */
    private fun contentRangeTotal(contentRange: String?): Long? =
      contentRange
        ?.trim()
        ?.takeIf { it.startsWith(CONTENT_RANGE_UNSATISFIED_PREFIX, ignoreCase = true) }
        ?.drop(CONTENT_RANGE_UNSATISFIED_PREFIX.length)
        ?.trim()
        ?.toLongOrNull()

    /**
     * Returns the `Authorization` header value for downloading [url], or `null` if no header should
     * be sent.
     *
     * The Hugging Face access token is only sent to Hugging Face URLs, so that it never leaks to
     * other hosts, e.g. the extra data files of a Hugging Face model that are hosted elsewhere.
     */
    @VisibleForTesting
    fun authorizationHeaderValue(accessToken: String?, url: String): String? {
      if (accessToken.isNullOrEmpty() || !HuggingFaceUrls.isHuggingFaceUrl(url)) {
        return null
      }
      return "Bearer $accessToken"
    }

    /** Whether [e] means the device ran out of storage while writing a file. */
    @VisibleForTesting
    fun isOutOfStorage(e: IOException): Boolean =
      // Writes to a full disk fail with "write failed: ENOSPC (No space left on device)".
      e.message?.contains("ENOSPC") == true

    /**
     * Whether an earlier run of the download already finished [originalFile]: no partial [tmpFile]
     * is left to resume, and the file exists or, for a zip file that is deleted once unzipped, the
     * [unzippedDir] it was unzipped to isn't empty.
     */
    @VisibleForTesting
    fun isFileAlreadyDownloaded(
      originalFile: File,
      tmpFile: File,
      unzippedDir: File? = null,
    ): Boolean =
      !tmpFile.exists() && (originalFile.exists() || unzippedDir?.list()?.isNotEmpty() == true)

    /**
     * Returns the dir in [outputDir] that the zip file [fileName] is unzipped to, or `null` if it
     * is unzipped right into [outputDir], which doesn't tell whether it was unzipped.
     *
     * @param mainFileName The name of the model's main file, as opposed to its extra data files.
     * @param unzippedDir The dir that the main file is unzipped to, if any.
     */
    @VisibleForTesting
    fun unzipTargetDir(
      outputDir: File,
      fileName: String,
      mainFileName: String?,
      unzippedDir: String?,
    ): File? =
      when {
        // An extra data zip file is unzipped to a dir named after it, or holds that dir itself.
        fileName != mainFileName -> File(outputDir, fileName.substringBeforeLast("."))
        !unzippedDir.isNullOrEmpty() -> File(outputDir, unzippedDir)
        else -> null
      }

    /**
     * Returns how long the rest of a download of [totalBytes] takes at [bytesPerMs] once
     * [downloadedBytes] are downloaded, or 0 if it can't tell. The files can be bigger than
     * [totalBytes], which is never negative.
     */
    @VisibleForTesting
    fun remainingMs(totalBytes: Long, downloadedBytes: Long, bytesPerMs: Float): Long =
      if (bytesPerMs > 0f && totalBytes > 0L) {
        ((totalBytes - downloadedBytes).coerceAtLeast(0L) / bytesPerMs).toLong()
      } else {
        0L
      }

    /**
     * Returns the progress, from 0 to 100, of a download of [totalBytes] once [downloadedBytes] are
     * downloaded, or 0 if [totalBytes] isn't known. The files can be bigger than [totalBytes].
     */
    @VisibleForTesting
    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int =
      if (totalBytes > 0L) (downloadedBytes * 100 / totalBytes).coerceIn(0L, 100L).toInt() else 0

    /** Returns the failed worker result for an exception, recording the exception's type. */
    @VisibleForTesting
    fun exceptionResult(e: Exception): ListenableWorker.Result {
      val errorType =
        if (e is IOException && isOutOfStorage(e)) {
          ERROR_TYPE_OUT_OF_STORAGE
        } else {
          e.javaClass.simpleName.ifEmpty { "Exception" }
        }
      return ListenableWorker.Result.failure(
        Data.Builder()
          .putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message)
          .putString(KEY_MODEL_DOWNLOAD_ERROR_TYPE, errorType)
          .build()
      )
    }

    /**
     * Returns the worker result for a download request that got a non-2xx response.
     *
     * Hugging Face access errors carry a [DownloadAuthError] under [KEY_MODEL_DOWNLOAD_AUTH_ERROR],
     * so the UI can ask the user to sign in or accept the model's license. Other errors are plain
     * failures.
     *
     * @param url The requested URL.
     * @param httpCode The HTTP status code of the response.
     * @param sentAccessToken Whether the request carried a Hugging Face access token.
     */
    fun httpErrorResult(
      url: String,
      httpCode: Int,
      sentAccessToken: Boolean,
    ): ListenableWorker.Result {
      val errorMessage = "HTTP error code: $httpCode"
      val outputData =
        Data.Builder()
          .putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, errorMessage)
          .putString(KEY_MODEL_DOWNLOAD_ERROR_TYPE, "$ERROR_TYPE_HTTP_PREFIX$httpCode")
      val authError = DownloadAuthError.fromResponse(url, httpCode, sentAccessToken)
      if (authError != null) {
        Log.w(TAG, "Download needs Hugging Face access ($authError): $errorMessage")
        outputData.putString(KEY_MODEL_DOWNLOAD_AUTH_ERROR, authError.name)
      } else {
        Log.e(TAG, errorMessage)
      }
      return ListenableWorker.Result.failure(outputData.build())
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  /**
   * Opens a connection that downloads [url], requesting the bytes from [rangeStart] on when it is
   * greater than 0.
   *
   * @param authorization The `Authorization` header value, if any.
   */
  private fun openDownloadConnection(
    url: String,
    authorization: String?,
    rangeStart: Long,
  ): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    // URLConnection waits forever by default, so a stalled connection would hang the download.
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    if (authorization != null) {
      connection.setRequestProperty("Authorization", authorization)
    }

    // Force the server to send non-compressed data on every request, so that the partial file holds
    // the bytes of the file itself and resuming it at its size requests the right range.
    // HttpURLConnection would otherwise ask for gzip and unzip the response on the fly.
    connection.setRequestProperty("Accept-Encoding", "identity")
    if (rangeStart > 0L) {
      connection.setRequestProperty("Range", "bytes=$rangeStart-")
    }
    connection.connect()
    return connection
  }

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  @SuppressLint("PendingIntentMutability")
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    // Name the activity rather than load its class, which the worker module doesn't depend on.
    val intent =
      Intent().setClassName(applicationContext, "com.google.ai.edge.gallery.MainActivity").apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
