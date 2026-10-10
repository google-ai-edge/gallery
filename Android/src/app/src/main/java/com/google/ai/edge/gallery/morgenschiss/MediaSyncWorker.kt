/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.morgenschiss

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.mediagallery.MediaItem
import com.google.ai.edge.gallery.mediagallery.MediaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "MediaSyncWorker"
private const val CHANNEL_ID = "media_sync"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaSyncEntryPoint {
  fun api(): MediaSearchApi

  fun idStore(): MediaIdStore

  fun mediaRepository(): MediaRepository
}

/**
 * Brings the server index in line with the phone: fingerprints, then uploads of new and moved
 * files in batches of 16, one after the other, and removal of deleted files. Resumable: every
 * finished batch is recorded, a restart continues with what is still missing.
 */
class MediaSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val deps = EntryPointAccessors.fromApplication(context, MediaSyncEntryPoint::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)

  override suspend fun doWork(): Result {
    val api = deps.api()
    if (!api.client.isLoggedIn) return Result.success()
    val store = deps.idStore()
    val repo = deps.mediaRepository()
    repo.reload()
    val items = repo.library.value.items
    val byId = items.associateBy { it.id }
    val files = items.map { LocalFile(it.id, it.size, it.dateModifiedSec, it.folder, it.isVideo) }

    // 1. fingerprints (reads at most 12 MiB per file)
    var plan = SyncPlanner.plan(files, store.all(), null)
    plan.needFingerprint.forEachIndexed { i, f ->
      if (isStopped) return Result.retry()
      if (i % 25 == 0) report("Dateien prüfen", i, plan.needFingerprint.size)
      val item = byId.getValue(f.mediaId)
      val fp = runCatching { Fingerprint.ofUri(applicationContext.contentResolver, item.uri, item.size) }.getOrNull()
      if (fp != null) store.putFingerprint(f.mediaId, f.size, f.dateModifiedSec, fp)
    }

    // 2. compare with the server
    report("Abgleich mit morgenschiss", 0, 0)
    val serverIds =
      when (val r = api.ids()) {
        is ApiResult.Ok -> r.value.ids.toHashSet()
        else -> return outcome(r)
      }
    val rows = store.all()
    plan = SyncPlanner.plan(files, rows, serverIds)
    store.markIndexed(plan.alreadyIndexed.map { it.mediaId }) { byId.getValue(it).folder }
    if (plan.removeFromServer.isNotEmpty()) {
      for (chunk in plan.removeFromServer.chunked(2000)) {
        val r = api.remove(chunk)
        if (r !is ApiResult.Ok) return outcome(r)
      }
    }
    store.delete(plan.goneRows.map { it.mediaId })

    // 3. upload, one batch at a time (a parallel batch would get 429 index_busy)
    val batches = SyncPlanner.batches(plan.upload)
    var done = 0
    for (batch in batches) {
      if (isStopped) return Result.retry()
      report("Hochladen", done, plan.upload.size)
      val sent = batch.mapNotNull { f ->
        val item = byId.getValue(f.mediaId)
        val fp = rows[f.mediaId]?.fingerprint ?: return@mapNotNull null
        val frames = runCatching { FrameEncoder.framesFor(applicationContext.contentResolver, item) }.getOrElse { emptyList() }
        if (frames.isEmpty()) {
          store.markFailed(listOf(f.mediaId))
          return@mapNotNull null
        }
        f.mediaId to indexItem(item, fp, frames)
      }
      if (sent.isNotEmpty()) {
        val result = sendWithRetry(api, sent.map { it.second })
        when (result) {
          is ApiResult.Ok -> {
            val fpToMedia = sent.associate { it.second.id to it.first }
            store.markIndexed(result.value.indexed.mapNotNull { fpToMedia[it] }) { byId.getValue(it).folder }
            store.markFailed(result.value.failed.mapNotNull { fpToMedia[it.id] })
          }
          else -> return outcome(result)
        }
      }
      done += batch.size
    }
    report("Fertig", plan.upload.size, plan.upload.size)
    return Result.success(workDataOf(KEY_UPLOADED to done))
  }

  /** index_busy means our own previous batch is still running on the server: wait and resend. */
  private suspend fun sendWithRetry(api: MediaSearchApi, items: List<IndexItem>): ApiResult<IndexResponse> {
    repeat(6) {
      val r = api.index(items)
      if (r !is ApiResult.Busy) return r
      delay(15_000)
    }
    return ApiResult.Busy("index_busy")
  }

  private fun indexItem(item: MediaItem, fp: String, frames: List<String>) =
    IndexItem(
      id = fp,
      kind = if (item.isVideo) "video" else "image",
      mime = item.mime,
      size = item.size,
      takenAt = Instant.ofEpochMilli(item.takenAt).toString(),
      folder = item.folder,
      name = item.name,
      frames = frames,
    )

  /** Mac away or busy: try later; logged out or no access: stop until the user acts. */
  private fun outcome(r: ApiResult<*>): Result {
    Log.i(TAG, "sync stopped: $r")
    return when (r) {
      is ApiResult.Unavailable, is ApiResult.Busy -> Result.retry()
      else -> Result.failure(workDataOf(KEY_ERROR to r.toString()))
    }
  }

  private suspend fun report(phase: String, done: Int, total: Int) {
    setProgress(workDataOf(KEY_PHASE to phase, KEY_DONE to done, KEY_TOTAL to total))
    try {
      setForeground(foregroundInfo(phase, done, total))
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "no foreground service: ${e.message}")
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Abgleich", 0, 0)

  private fun foregroundInfo(phase: String, done: Int, total: Int): ForegroundInfo {
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Abgleich mit morgenschiss", NotificationManager.IMPORTANCE_LOW)
    )
    val text = if (total > 0) "$phase: $done von $total" else phase
    val n =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle("Galerie-Suche")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .setProgress(total, done, total == 0)
        .build()
    return ForegroundInfo(1701, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  companion object {
    const val KEY_PHASE = "phase"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    const val KEY_UPLOADED = "uploaded"
    const val UNIQUE_NOW = "media_sync_now"
    private const val UNIQUE_PERIODIC = "media_sync_periodic"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun runNow(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          UNIQUE_NOW,
          ExistingWorkPolicy.KEEP,
          OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build(),
        )
    }

    /** New photos reach the server a few times a day without opening the app. */
    fun schedulePeriodic(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
          UNIQUE_PERIODIC,
          ExistingPeriodicWorkPolicy.KEEP,
          PeriodicWorkRequestBuilder<MediaSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }

    fun cancelAll(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NOW)
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }
  }
}
