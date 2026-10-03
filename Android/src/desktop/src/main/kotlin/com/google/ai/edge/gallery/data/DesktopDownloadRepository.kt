package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.AppLifecycleProvider
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "DesktopDownloadRepo"

data class AGWorkInfo(val taskId: String, val modelName: String, val workId: String)

interface DownloadRepository {
  fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean = true,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  fun cancelAll(onComplete: () -> Unit)

  fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadExtraDataFiles(model: Model)
}

class DesktopDownloadRepository(
  private val context: Context,
  private val lifecycleProvider: AppLifecycleProvider? = null,
) : DownloadRepository {

  private val activeJobs = ConcurrentHashMap<String, Job>()
  private val downloadScope = CoroutineScope(Dispatchers.IO)
  private val httpClient =
    HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .connectTimeout(Duration.ofSeconds(30))
      .build()

  override fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val downloadUrl = model.downloadInfo.url.ifEmpty { model.downloadInfo.downloadFileName }
    if (downloadUrl.isEmpty()) {
      Log.e(TAG, "Empty download URL for model: ${model.name}")
      onStatusUpdated(
        model,
        ModelDownloadStatus(
          status = ModelDownloadStatusType.FAILED,
          errorMessage = "No download URL available",
        ),
      )
      return
    }

    cancelDownloadModel(model)

    val job = downloadScope.launch {
      try {
        onStatusUpdated(
          model,
          ModelDownloadStatus(
            status = ModelDownloadStatusType.IN_PROGRESS,
            totalBytes = 0,
            receivedBytes = 0,
          ),
        )

        val targetFile = File(model.getPath(context))
        val targetDir = targetFile.parentFile ?: File(context.filesDir, "models")
        targetDir.mkdirs()
        val tempFile = File(targetDir, "${targetFile.name}.downloading")

        Log.d(TAG, "Starting download from $downloadUrl to ${tempFile.absolutePath}")

        val request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
          throw RuntimeException("HTTP download failed with status ${response.statusCode()}")
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloadedBytes = 0L

        response.body().use { input ->
          FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var lastUpdateMs = System.currentTimeMillis()

            while (input.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead

              val now = System.currentTimeMillis()
              if (now - lastUpdateMs > 300) {
                lastUpdateMs = now
                onStatusUpdated(
                  model,
                  ModelDownloadStatus(
                    status = ModelDownloadStatusType.IN_PROGRESS,
                    totalBytes = totalBytes,
                    receivedBytes = downloadedBytes,
                  ),
                )
              }
            }
          }
        }

        if (targetFile.exists()) {
          targetFile.delete()
        }
        tempFile.renameTo(targetFile)

        Log.d(TAG, "Download finished successfully: ${targetFile.absolutePath}")
        onStatusUpdated(
          model,
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            totalBytes = targetFile.length(),
            receivedBytes = targetFile.length(),
          ),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Download error for ${model.name}: ${e.message}", e)
        onStatusUpdated(
          model,
          ModelDownloadStatus(
            status = ModelDownloadStatusType.FAILED,
            errorMessage = e.message ?: "Download failed",
          ),
        )
      } finally {
        activeJobs.remove(model.name)
      }
    }

    activeJobs[model.name] = job
  }

  override fun cancelDownloadModel(model: Model) {
    activeJobs.remove(model.name)?.cancel()
  }

  override fun cancelAll(onComplete: () -> Unit) {
    activeJobs.values.forEach { it.cancel() }
    activeJobs.clear()
    onComplete()
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}

  override fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}

  override fun cancelDownloadExtraDataFiles(model: Model) {}
}
