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

package com.google.ai.edge.gallery.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.readLaunchInfo
import com.google.ai.edge.gallery.worker.DownloadWorker
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.UUID

private const val TAG = "AGDownloadRepository"
private const val MODEL_NAME_TAG = "modelName"

data class AGWorkInfo(val modelName: String, val workId: String)

interface DownloadRepository {
  fun downloadModel(
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  fun cancelAll(models: List<Model>, onComplete: () -> Unit)

  fun observerWorkerProgress(
    workerId: UUID,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun getEnqueuedOrRunningWorkInfos(): List<AGWorkInfo>
}

/**
 * Repository for managing model downloads using WorkManager.
 *
 * This class provides methods to initiate model downloads, cancel downloads, observe download
 * progress, and retrieve information about enqueued or running download tasks. It utilizes
 * WorkManager to handle background download operations.
 */
class DefaultDownloadRepository(
  private val context: Context,
  private val lifecycleProvider: AppLifecycleProvider,
) : DownloadRepository {
  private val workManager = WorkManager.getInstance(context)

  override fun downloadModel(
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val appTs = readLaunchInfo(context = context)?.ts ?: 0

    // Create input data.
    val builder = Data.Builder()
    val totalBytes = model.totalBytes + model.extraDataFiles.sumOf { it.sizeInBytes }
    val inputDataBuilder =
      builder
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_VERSION, model.version)
        .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.normalizedName)
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
        .putLong(KEY_MODEL_DOWNLOAD_APP_TS, appTs)

    if (model.extraDataFiles.isNotEmpty()) {
      inputDataBuilder
        .putString(KEY_MODEL_EXTRA_DATA_URLS, model.extraDataFiles.joinToString(",") { it.url })
        .putString(
          KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES,
          model.extraDataFiles.joinToString(",") { it.downloadFileName },
        )
    }
    if (model.accessToken != null) {
      inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, model.accessToken)
    }
    val inputData = inputDataBuilder.build()

    // Create worker request.
    val downloadWorkRequest =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputData)
        .addTag("$MODEL_NAME_TAG:${model.name}")
        .build()

    val workerId = downloadWorkRequest.id

    // Start!
    workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, downloadWorkRequest)

    // Observe progress.
    observerWorkerProgress(workerId = workerId, model = model, onStatusUpdated = onStatusUpdated)
  }

  override fun cancelDownloadModel(model: Model) {
    workManager.cancelAllWorkByTag("$MODEL_NAME_TAG:${model.name}")
  }

  override fun cancelAll(models: List<Model>, onComplete: () -> Unit) {
    if (models.isEmpty()) {
      onComplete()
      return
    }

    val futures = mutableListOf<ListenableFuture<Operation.State.SUCCESS>>()
    for (tag in models.map { "$MODEL_NAME_TAG:${it.name}" }) {
      futures.add(workManager.cancelAllWorkByTag(tag).result)
    }
    val combinedFuture: ListenableFuture<List<Operation.State.SUCCESS>> = Futures.allAsList(futures)
    Futures.addCallback(
      combinedFuture,
      object : FutureCallback<List<Operation.State.SUCCESS>> {
        override fun onSuccess(result: List<Operation.State.SUCCESS>?) {
          // All cancellations are complete
          onComplete()
        }

        override fun onFailure(t: Throwable) {
          // At least one cancellation failed
          t.printStackTrace()
          onComplete()
        }
      },
      MoreExecutors.directExecutor(),
    )
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    workManager.getWorkInfoByIdLiveData(workerId).observeForever { workInfo ->
      if (workInfo != null) {
        when (workInfo.state) {
          WorkInfo.State.RUNNING -> {
            val receivedBytes = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
            val downloadRate = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
            val remainingSeconds = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L)
            val startUnzipping = workInfo.progress.getBoolean(KEY_MODEL_START_UNZIPPING, false)

            if (!startUnzipping) {
              if (receivedBytes != 0L) {
                onStatusUpdated(
                  model,
                  ModelDownloadStatus(
                    status = ModelDownloadStatusType.IN_PROGRESS,
                    totalBytes = model.totalBytes,
                    receivedBytes = receivedBytes,
                    bytesPerSecond = downloadRate,
                    remainingMs = remainingSeconds,
                  ),
                )
              }
            } else {
              onStatusUpdated(
                model,
                ModelDownloadStatus(status = ModelDownloadStatusType.UNZIPPING),
              )
            }
          }

          WorkInfo.State.SUCCEEDED -> {
            Log.d("repo", "worker %s success".format(workerId.toString()))
            onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED))
            sendNotification(
              title = context.getString(R.string.notification_title_success),
              text = context.getString(R.string.notification_content_success).format(model.name),
              modelName = model.name,
            )
          }

          WorkInfo.State.FAILED,
          WorkInfo.State.CANCELLED -> {
            var status = ModelDownloadStatusType.FAILED
            val errorMessage = workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: ""
            Log.d(
              "repo",
              "worker %s FAILED or CANCELLED: %s".format(workerId.toString(), errorMessage),
            )
            if (workInfo.state == WorkInfo.State.CANCELLED) {
              status = ModelDownloadStatusType.NOT_DOWNLOADED
            } else {
              sendNotification(
                title = context.getString(R.string.notification_title_fail),
                text = context.getString(R.string.notification_content_success).format(model.name),
                modelName = "",
              )
            }
            onStatusUpdated(
              model,
              ModelDownloadStatus(status = status, errorMessage = errorMessage),
            )
          }

          else -> {}
        }
      }
    }
  }

  /**
   * Retrieves a list of AGWorkInfo objects representing WorkManager work items that are either
   * enqueued or currently running.
   */
  override fun getEnqueuedOrRunningWorkInfos(): List<AGWorkInfo> {
    val workQuery =
      WorkQuery.Builder.fromStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)).build()

    return workManager.getWorkInfos(workQuery).get().map { info ->
      val tags = info.tags
      var modelName = ""
      Log.d(TAG, "work: ${info.id}, tags: $tags")
      for (tag in tags) {
        if (tag.startsWith("$MODEL_NAME_TAG:")) {
          val index = tag.indexOf(':')
          if (index >= 0) {
            modelName = tag.substring(index + 1)
            break
          }
        }
      }
      return@map AGWorkInfo(modelName = modelName, workId = info.id.toString())
    }
  }

  private fun sendNotification(title: String, text: String, modelName: String) {
    // Don't send notification if app is in foreground.
    if (lifecycleProvider.isAppInForeground) {
      return
    }

    val channelId = "download_notification"
    val channelName = "AI Edge Gallery download notification"

    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    val importance = NotificationManager.IMPORTANCE_HIGH
    val channel = NotificationChannel(channelId, channelName, importance)
    val notificationManager: NotificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)

    // Create an Intent to open your app with a deep link.
    val intent =
      Intent(Intent.ACTION_VIEW, "com.google.ai.edge.gallery://model/${modelName}".toUri()).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }

    // Create a PendingIntent
    val pendingIntent: PendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, channelId)
        // TODO: replace icon.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

    with(NotificationManagerCompat.from(context)) {
      // notificationId is a unique int for each notification that you must define
      if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        // Permission not granted, return or handle accordingly. In real app, request permission.
        return
      }
      notify(1, builder.build())
    }
  }
}
