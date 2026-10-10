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

package com.google.ai.edge.gallery.mediagallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaRepository"

/** One photo or video from MediaStore. */
data class MediaItem(
  val id: Long,
  val uri: Uri,
  val isVideo: Boolean,
  val mime: String,
  val size: Long,
  /** Capture time in ms; falls back to the modification time. */
  val takenAt: Long,
  val dateModifiedSec: Long,
  val name: String,
  val durationMs: Long,
  val bucketId: Long,
  val bucketName: String,
  /** e.g. "DCIM/Camera/"; what the server calls the folder. */
  val relativePath: String,
  val favorite: Boolean = false,
  val width: Int = 0,
  val height: Int = 0,
) {
  /** Folder as sent to morgenschiss: relative path without the trailing slash. */
  val folder: String
    get() = relativePath.trimEnd('/')
}

/** A flat folder (MediaStore bucket) with its newest item as cover. */
data class MediaFolder(
  val bucketId: Long,
  val name: String,
  val count: Int,
  val cover: MediaItem,
  val relativePath: String,
)

data class MediaLibrary(
  val items: List<MediaItem> = emptyList(),
  val folders: List<MediaFolder> = emptyList(),
  val loaded: Boolean = false,
  /** Every photo and video was readable; only then may the sync treat a missing file as deleted. */
  val complete: Boolean = false,
) {
  private val byId: Map<Long, MediaItem> by lazy { items.associateBy { it.id } }

  fun item(id: Long): MediaItem? = byId[id]

  fun itemsIn(bucketId: Long?): List<MediaItem> =
    when (bucketId) {
      null -> items
      FAVORITES -> items.filter { it.favorite }
      else -> items.filter { it.bucketId == bucketId }
    }

  companion object {
    /** Pseudo bucket id for the favourites tile. */
    const val FAVORITES = -3L
  }
}

/** Reads all photos and videos once and again whenever MediaStore changes. */
@Singleton
class MediaRepository @Inject constructor(@ApplicationContext private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _library = MutableStateFlow(MediaLibrary())
  val library: StateFlow<MediaLibrary> = _library.asStateFlow()
  private var observerRegistered = false
  private var pendingReload: Job? = null

  private val observer =
    object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        // a camera burst fires many changes; reload once it settles
        pendingReload?.cancel()
        pendingReload =
          scope.launch {
            delay(800)
            reload()
          }
      }
    }

  /** Call after the media permission was granted. */
  fun start() {
    if (!observerRegistered) {
      context.contentResolver.registerContentObserver(FILES_URI, true, observer)
      observerRegistered = true
    }
    scope.launch { reload() }
  }

  suspend fun reload() {
    val items = withContext(Dispatchers.IO) { query() }
    _library.value =
      MediaLibrary(
        items = items.orEmpty(),
        folders = foldersOf(items.orEmpty()),
        loaded = true,
        complete = items != null && hasFullAccess(context),
      )
  }

  /** null when MediaStore refused the query. */
  private fun query(): List<MediaItem>? {
    val out = ArrayList<MediaItem>(9000)
    val projection =
      arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_FAVORITE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
      )
    val selection =
      "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
    val args =
      arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
      )
    try {
      context.contentResolver
        .query(FILES_URI, projection, selection, args, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
        ?.use { c ->
          val iId = c.getColumnIndexOrThrow(projection[0])
          val iType = c.getColumnIndexOrThrow(projection[1])
          val iMime = c.getColumnIndexOrThrow(projection[2])
          val iSize = c.getColumnIndexOrThrow(projection[3])
          val iTaken = c.getColumnIndexOrThrow(projection[4])
          val iMod = c.getColumnIndexOrThrow(projection[5])
          val iName = c.getColumnIndexOrThrow(projection[6])
          val iDur = c.getColumnIndexOrThrow(projection[7])
          val iBucket = c.getColumnIndexOrThrow(projection[8])
          val iBucketName = c.getColumnIndexOrThrow(projection[9])
          val iPath = c.getColumnIndexOrThrow(projection[10])
          val iFav = c.getColumnIndexOrThrow(projection[11])
          val iW = c.getColumnIndexOrThrow(projection[12])
          val iH = c.getColumnIndexOrThrow(projection[13])
          while (c.moveToNext()) {
            val id = c.getLong(iId)
            val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base =
              if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
              else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val mod = c.getLong(iMod)
            val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (mod * 1000)
            out +=
              MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(base, id),
                isVideo = isVideo,
                mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                size = c.getLong(iSize),
                takenAt = taken,
                dateModifiedSec = mod,
                name = c.getString(iName) ?: "",
                durationMs = c.getLong(iDur),
                bucketId = c.getLong(iBucket),
                bucketName = c.getString(iBucketName) ?: "Ohne Ordner",
                relativePath = c.getString(iPath) ?: "",
                favorite = c.getInt(iFav) == 1,
                width = c.getInt(iW),
                height = c.getInt(iH),
              )
          }
        }
    } catch (e: SecurityException) {
      Log.w(TAG, "No media permission", e)
      return null
    }
    // DATE_TAKEN can be empty; the fallback above needs a final sort
    out.sortByDescending { it.takenAt }
    return out
  }

  companion object {
    /** Full access to images and videos, not just a user-picked selection (Android 14+). */
    fun hasFullAccess(context: Context): Boolean {
      val perms =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
          listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
      return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    val FILES_URI: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun foldersOf(items: List<MediaItem>): List<MediaFolder> =
      items
        .groupBy { it.bucketId }
        .map { (bucketId, list) ->
          // items are sorted newest first
          MediaFolder(
            bucketId = bucketId,
            name = list.first().bucketName,
            count = list.size,
            cover = list.first(),
            relativePath = list.first().relativePath,
          )
        }
        .sortedByDescending { it.cover.takenAt }
  }
}
