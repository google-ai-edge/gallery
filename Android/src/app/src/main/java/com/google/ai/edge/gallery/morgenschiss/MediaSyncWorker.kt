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
import kotlinx.coroutines.sync.Mutex

private const val TAG = "MediaSyncWorker"
private const val CHANNEL_ID = "media_sync"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaSyncEntryPoint {
  fun api(): MediaSearchApi

  fun idStore(): MediaIdStore

  fun mediaRepository(): MediaRepository

  fun albums(): com.google.ai.edge.gallery.mediagallery.AlbumRepository

  fun vectors(): VectorStore
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
    // the periodic and the manual job share one lock: two parallel runs only fight over index_busy
    if (!running.tryLock()) return Result.success()
    try {
      return sync()
    } finally {
      running.unlock()
    }
  }

  private suspend fun sync(): Result {
    val api = deps.api()
    if (!api.client.isLoggedIn) return Result.success()
    val store = deps.idStore()
    val repo = deps.mediaRepository()
    repo.reload()
    // with partial access ("selected photos") or a failed query every unseen file would look
    // deleted and be removed from the server
    if (!repo.library.value.complete) return Result.success()
    val items = repo.library.value.items
    val byId = items.associateBy { it.id }
    val files = items.map { LocalFile(it.id, it.size, it.dateModifiedSec, it.folder, it.isVideo) }

    // 1. fingerprints (reads at most 12 MiB per file)
    var plan = SyncPlanner.plan(files, store.all(), null)
    // fingerprints of edited files: their old server entry goes once no copy uses it
    val replaced = HashSet<String>()
    plan.needFingerprint.forEachIndexed { i, f ->
      if (isStopped) return Result.retry()
      if (i % 25 == 0) report("Dateien prüfen", i, plan.needFingerprint.size)
      val item = byId.getValue(f.mediaId)
      val fp = runCatching { Fingerprint.ofUri(applicationContext.contentResolver, item.uri, item.size) }.getOrNull()
      if (fp != null) store.putFingerprint(f.mediaId, f.size, f.dateModifiedSec, fp)?.let { replaced += it }
    }

    // 2. compare with the server
    report("Abgleich mit morgenschiss", 0, 0)
    val idsResponse =
      when (val r = api.ids()) {
        is ApiResult.Ok -> r.value
        else -> return outcome(r)
      }
    val serverIds = idsResponse.ids.toHashSet()
    val rows = store.all()
    plan = SyncPlanner.plan(files, rows, serverIds)
    store.markIndexed(plan.alreadyIndexed.map { it.mediaId }) { byId.getValue(it).folder }
    val liveFps = rows.values.map { it.fingerprint }.toSet()
    val toRemove = (plan.removeFromServer + replaced.filter { it in serverIds && it !in liveFps }).distinct()
    // a sudden mass disappearance (card removed, storage hiccup) is more likely an error than a cleanup
    val massDelete = plan.goneRows.size > maxOf(50, rows.size / 10)
    if (massDelete) {
      Log.w(TAG, "${plan.goneRows.size} of ${rows.size} files gone at once, not removing anything")
    } else if (toRemove.isNotEmpty()) {
      for (chunk in toRemove.chunked(2000)) {
        val r = api.remove(chunk)
        if (r !is ApiResult.Ok) return outcome(r)
      }
    }
    if (!massDelete) store.delete(plan.goneRows.map { it.mediaId })

    // the phone searches in its own copy of the vectors: fetch what is already there first,
    // so search works before a long upload ends
    val vectors = deps.vectors()
    report("Suchdaten laden", 0, 0)
    VectorSync.pull(applicationContext, api, vectors)?.let { Log.w(TAG, "vector download failed: $it") }
    if (!massDelete) {
      vectors.keepOnly(store.all().values.map { it.fingerprint }.toSet())
      // a file that came back (SD card, trash) is not new on the server, so a delta never brings
      // its vectors again; fetch everything once in that case
      val missing = store.all().values.map { it.fingerprint }.filter { it in serverIds }.toSet() - vectors.fingerprints()
      if (missing.isNotEmpty()) {
        Log.i(TAG, "${missing.size} indexed files without a vector on the phone, full download")
        VectorSync.pull(applicationContext, api, vectors, full = true)?.let { Log.w(TAG, "vector download failed: $it") }
      }
    }

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
      for (chunk in byBodySize(sent)) {
        val stop = send(api, store, chunk, byId)
        if (stop != null) return stop
      }
      done += batch.size
    }
    // new files that belong to a folder album wait for the user to move them (Android asks)
    runCatching { sortIntoFolderAlbums(api, byId) }

    // 4. scenes along videos, so a moment inside a long video can be found
    val sceneVideos = SyncPlanner.needScenes(files, store.all())
    sceneVideos.forEachIndexed { i, f ->
      if (isStopped) return Result.retry()
      report("Szenen aus Videos", i, sceneVideos.size)
      val item = byId.getValue(f.mediaId)
      val fp = store.all()[f.mediaId]?.fingerprint ?: return@forEachIndexed
      val scenes = runCatching { FrameEncoder.sceneFrames(applicationContext.contentResolver, item) }.getOrElse { emptyList() }
      val items = scenes.map { (t, frame) -> IndexItem(id = fp, kind = "video", mime = item.mime, size = item.size, frames = listOf(frame), scene = SceneTime(t)) }
      for (chunk in byBodySize(items.map { f.mediaId to it }).flatMap { it.chunked(SyncPlanner.BATCH_SIZE) }) {
        val stop = sendScenes(api, chunk.map { it.second })
        if (stop != null) return stop
      }
      // copies share the fingerprint and therefore the scenes
      store.markScenes(store.all().values.filter { it.fingerprint == fp }.map { it.mediaId }, MediaIdStore.SCENE_VERSION)
    }

    // Videos are not transcribed in bulk (hundreds of GB would take hours and much storage);
    // a transcript is made on demand from the viewer, which also stores it for the search.

    report("Suchdaten laden", 0, 0)
    VectorSync.pull(applicationContext, api, vectors)?.let { return outcome(it) }

    report("Fertig", plan.upload.size, plan.upload.size)
    return Result.success(workDataOf(KEY_UPLOADED to done))
  }

  /** Keeps a request under the server's 30 MB body limit (frames travel as base64). */
  private fun byBodySize(sent: List<Pair<Long, IndexItem>>): List<List<Pair<Long, IndexItem>>> {
    val out = ArrayList<List<Pair<Long, IndexItem>>>()
    var cur = ArrayList<Pair<Long, IndexItem>>()
    var bytes = 0L
    for (p in sent) {
      val size = p.second.frames.sumOf { it.length.toLong() }
      if (cur.isNotEmpty() && bytes + size > MAX_BODY_CHARS) {
        out += cur
        cur = ArrayList()
        bytes = 0
      }
      cur += p
      bytes += size
    }
    if (cur.isNotEmpty()) out += cur
    return out
  }

  /**
   * One request. The server rejects a whole batch for one bad frame (400) or an oversized body
   * (413), so those are split until the culprit is alone and marked failed. Returns a [Result]
   * when the sync has to stop.
   */
  private suspend fun send(
    api: MediaSearchApi,
    store: MediaIdStore,
    chunk: List<Pair<Long, IndexItem>>,
    byId: Map<Long, MediaItem>,
  ): Result? {
    val result = sendWithRetry(api, chunk.map { it.second })
    return when {
      result is ApiResult.Ok -> {
        val fpToMedia = chunk.associate { it.second.id to it.first }
        store.markIndexed(result.value.indexed.mapNotNull { fpToMedia[it] }) { byId.getValue(it).folder }
        store.markFailed(result.value.failed.mapNotNull { fpToMedia[it.id] })
        null
      }
      result is ApiResult.Failed && (result.status == 400 || result.status == 413) ->
        if (chunk.size == 1) {
          store.markFailed(listOf(chunk[0].first))
          null
        } else {
          val half = chunk.size / 2
          send(api, store, chunk.subList(0, half), byId) ?: send(api, store, chunk.subList(half, chunk.size), byId)
        }
      else -> outcome(result)
    }
  }

  /**
   * Every file is checked once after its upload (the mark is stored, so a retry loses nothing).
   * Only files new on the phone are offered: a file the user moved keeps its mark.
   */
  private suspend fun sortIntoFolderAlbums(api: MediaSearchApi, byId: Map<Long, MediaItem>) {
    val store = deps.idStore()
    val rows = store.all()
    val fresh = rows.values.filter { !it.matched && it.serverFolder != null && byId.containsKey(it.mediaId) }
    if (fresh.isEmpty()) return
    val albums = deps.albums()
    albums.refresh()
    if (albums.albums.value.none { it.isFolder }) {
      store.markMatched(fresh.map { it.mediaId })
      return
    }
    val fpToIds = fresh.groupBy({ it.fingerprint }, { it.mediaId })
    for (chunk in fpToIds.keys.chunked(2000)) {
      val r = api.matchAlbums(chunk) as? ApiResult.Ok ?: return
      val moves = HashMap<Long, com.google.ai.edge.gallery.mediagallery.PendingMove>()
      for ((fp, m) in r.value.matches) {
        if (m.mode != "folder") continue
        val path = com.google.ai.edge.gallery.mediagallery.MediaActions.newFolderPath(m.name)
        for (id in fpToIds[fp].orEmpty()) {
          val item = byId[id] ?: continue
          if (item.relativePath != path) moves[id] = com.google.ai.edge.gallery.mediagallery.PendingMove(path, fp, item.relativePath)
        }
      }
      albums.addPending(moves)
      store.markMatched(chunk.flatMap { fpToIds[it].orEmpty() })
    }
  }

  /** Like [send], for scenes: a bad scene is skipped, never the whole video. */
  private suspend fun sendScenes(api: MediaSearchApi, chunk: List<IndexItem>): Result? {
    val result = sendWithRetry(api, chunk)
    return when {
      result is ApiResult.Ok -> null
      result is ApiResult.Failed && (result.status == 400 || result.status == 413) ->
        if (chunk.size == 1) null
        else sendScenes(api, chunk.subList(0, chunk.size / 2)) ?: sendScenes(api, chunk.subList(chunk.size / 2, chunk.size))
      else -> outcome(result)
    }
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
    private val running = Mutex()
    private const val MAX_BODY_CHARS = 24L * 1024 * 1024
    const val KEY_PHASE = "phase"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    const val KEY_UPLOADED = "uploaded"
    const val UNIQUE_NOW = "media_sync_now"
    private const val UNIQUE_PERIODIC = "media_sync_periodic"

    // the first sync sends several GB of previews: Wi-Fi (unmetered) only, never mobile data
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

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
