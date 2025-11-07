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

package com.neuralforge.mobile.data

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
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neuralforge.mobile.AppLifecycleProvider
import com.neuralforge.mobile.R
import com.neuralforge.mobile.firebaseAnalytics
import com.neuralforge.mobile.worker.DownloadWorker
import java.util.UUID
import java.util.concurrent.Executors

private const val TAG = "AGDownloadRepository"
private const val MODEL_NAME_TAG = "modelName"
private const val TASK_ID_TAG = "taskId"

data class AGWorkInfo(val taskId: String, val modelName: String, val workId: String)

interface DownloadRepository {
  fun downloadModel(
    task: Task,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  fun cancelAll(onComplete: () -> Unit)

  fun observerWorkerProgress(
    workerId: UUID,
    task: Task,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )
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
  /**
   * Stores the start time of a model download.
   *
   * We use SharedPreferences to persist the download start times. This ensures that the data is
   * still available after the app restarts. The key is the model name and the value is the download
   * start time in milliseconds.
   */
  private val downloadStartTimeSharedPreferences =
    context.getSharedPreferences("download_start_time_ms", Context.MODE_PRIVATE)

  override fun downloadModel(
    task: Task,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    // Create input data.
    val builder = Data.Builder()
    val totalBytes = model.totalBytes + model.extraDataFiles.sumOf { it.sizeInBytes }
    val inputDataBuilder =
      builder
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.version)
        .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.normalizedName)
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)

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
        .addTag("$TASK_ID_TAG:${task.id}")
        .build()

    val workerId = downloadWorkRequest.id

    // Start!
    workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, downloadWorkRequest)

    // Observe progress.
    observerWorkerProgress(
      workerId = workerId,
      task = task,
      model = model,
      onStatusUpdated = onStatusUpdated,
    )
  }

  override fun cancelDownloadModel(model: Model) {
    workManager.cancelAllWorkByTag("$MODEL_NAME_TAG:${model.name}")
  }

  override fun cancelAll(onComplete: () -> Unit) {
    workManager
      .cancelAllWork()
      .result
      .addListener({ onComplete() }, Executors.newSingleThreadExecutor())
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    task: Task,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    workManager.getWorkInfoByIdLiveData(workerId).observeForever { workInfo ->
      if (workInfo != null) {
        when (workInfo.state) {
          WorkInfo.State.ENQUEUED -> {
            downloadStartTimeSharedPreferences.edit {
              putLong(model.name, System.currentTimeMillis())
            }
            firebaseAnalytics?.logEvent(
              "model_download",
              bundleOf("event_type" to "start", "model_id" to model.name),
            )
          }

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
              taskId = task.id,
              modelName = model.name,
            )

            val startTime = downloadStartTimeSharedPreferences.getLong(model.name, 0L)
            val duration = System.currentTimeMillis() - startTime
            firebaseAnalytics?.logEvent(
              "model_download",
              bundleOf(
                "event_type" to "success",
                "model_id" to model.name,
                "duration_ms" to duration,
              ),
            )
            downloadStartTimeSharedPreferences.edit { remove(model.name) }
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
                taskId = "",
                modelName = "",
              )
            }
            onStatusUpdated(
              model,
              ModelDownloadStatus(status = status, errorMessage = errorMessage),
            )

            val startTime = downloadStartTimeSharedPreferences.getLong(model.name, 0L)
            val duration = System.currentTimeMillis() - startTime
            // TODO: Add failure reasons
            firebaseAnalytics?.logEvent(
              "model_download",
              bundleOf(
                "event_type" to "failure",
                "model_id" to model.name,
                "duration_ms" to duration,
              ),
            )
            downloadStartTimeSharedPreferences.edit { remove(model.name) }
          }

          else -> {}
        }
      }
    }
  }

  private fun sendNotification(title: String, text: String, taskId: String, modelName: String) {
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

    val intent: Intent
    if (taskId.isEmpty()) {
      // If taskId is empty, it's a failed download. Just open the app's main screen.
      intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    } else {
      // Otherwise, create the deep link as before.
      intent =
        Intent(
            Intent.ACTION_VIEW,
            "com.google.ai.edge.gallery://model/$taskId/${modelName}".toUri(),
          )
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
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
