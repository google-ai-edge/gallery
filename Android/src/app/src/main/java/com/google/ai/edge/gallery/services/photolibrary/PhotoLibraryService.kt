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

package com.google.ai.edge.gallery.services.photolibrary

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.common.openSafeInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PhotoLibraryService"
private const val PREFS_NAME = "photo_library_prefs"
private const val KEY_REMOVED_ASSET_IDS = "removed_asset_ids"
private const val KEY_CUSTOM_ASSET_URIS = "custom_asset_uris"
private const val KEY_FULL_LIBRARY_ACCESS_ENABLED = "full_library_access_enabled"
private const val KEY_MAX_MEDIASTORE_ASSET_COUNT = "max_mediastore_asset_count"

data class PhotoAsset(
  val id: String,
  val contentUri: Uri,
  val dateTaken: Long,
  val sizeBytes: Long = 0L,
  val displayName: String = "",
  val locationName: String? = null,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val deviceModel: String? = null,
  val isVideo: Boolean = false,
  val durationMs: Long? = null,
) {
  fun getLocationString(): String {
    if (!locationName.isNullOrBlank()) return locationName
    if (latitude != null && longitude != null) {
      val latDir = if (latitude >= 0) "N" else "S"
      val lngDir = if (longitude >= 0) "E" else "W"
      return String.format(
        Locale.US,
        "%.4f° %s, %.4f° %s",
        Math.abs(latitude),
        latDir,
        Math.abs(longitude),
        lngDir,
      )
    }
    return "Location unavailable"
  }
}

/** Service interface for accessing photos, videos, and metadata from the device photo library. */
interface PhotoLibraryService {
  suspend fun fetchAllAssetIdentifiers(): List<String>

  suspend fun fetchAsset(id: String): PhotoAsset?

  suspend fun fetchAssets(ids: List<String>): List<PhotoAsset>

  suspend fun fetchAllAssets(): List<PhotoAsset>

  suspend fun fetchDetailedAsset(asset: PhotoAsset): PhotoAsset

  suspend fun loadBitmap(asset: PhotoAsset, targetDimension: Int = 512): Bitmap?

  /** Returns a cached in-memory [Bitmap] thumbnail for [asset], or `null` if not cached. */
  fun getCachedBitmap(asset: PhotoAsset, targetDimension: Int = 512): Bitmap? = null

  /**
   * Loads representative keyframes from the given media asset for vector embedding generation.
   *
   * For photo assets, returns a single bitmap. For video assets, extracts and returns the first and
   * last keyframes.
   */
  suspend fun loadKeyframes(asset: PhotoAsset, targetDimension: Int = 1024): List<Bitmap> =
    loadBitmap(asset, targetDimension)?.let { listOf(it) } ?: emptyList()

  /**
   * Marks the specified photo asset IDs as removed (hidden/excluded) within the application.
   *
   * **Note:** This does **not** delete or remove the actual media files from the user's device
   * photo library or MediaStore. Instead, it persists the excluded asset IDs in local app
   * preferences so they are filtered out and ignored in subsequent queries
   * ([fetchAllAssetIdentifiers], [fetchAllAssets], [fetchAsset]) within the app.
   *
   * @param ids The set of asset IDs to exclude from this service.
   */
  suspend fun removeAssets(ids: Set<String>) {}

  /** Adds custom assets created from photo picker or URIs to the library. */
  suspend fun addCustomAssets(assets: List<PhotoAsset>) {}

  /**
   * Returns the set of photo asset IDs that have been marked as removed (excluded) within the app.
   *
   * These assets are hidden from queries in this service, but the original media files remain
   * untouched in the device's photo library.
   *
   * @return A set of excluded asset ID strings.
   */
  suspend fun getRemovedAssetIdentifiers(): Set<String> = emptySet()

  /** Clears all removed asset IDs so that all library assets are included again. */
  suspend fun clearRemovedAssets() {}

  /** Returns whether full photo library access (all MediaStore assets) is enabled. */
  fun isFullLibraryAccessEnabled(): Boolean = true

  /** Sets whether full photo library access (all MediaStore assets) is enabled. */
  suspend fun setFullLibraryAccessEnabled(enabled: Boolean) {}

  /** Configures the library to include the most recent [count] assets from the device library. */
  suspend fun selectRecentAssets(count: Int) {
    setFullLibraryAccessEnabled(true)
    clearRemovedAssets()
    val allIds = fetchAllAssetIdentifiers()
    if (count in 0 until allIds.size) {
      removeAssets(allIds.drop(count).toSet())
    }
  }

  /** Fetches the total count of MediaStore assets on the device, ignoring removed asset filters. */
  suspend fun fetchMediaStoreAssetCount(): Int

  /**
   * Returns the total count of permitted MediaStore assets on the device if there are any permitted
   * MediaStore assets not currently included in the collection, or `0` if all permitted MediaStore
   * assets are already included.
   */
  suspend fun fetchPermittedMediaStoreCountIfUnadded(): Int

  /**
   * Returns true if there are any MediaStore assets that the app has permission to read which are
   * not currently included in the collection.
   */
  suspend fun hasUnaddedPermittedMediaStoreAssets(): Boolean =
    fetchPermittedMediaStoreCountIfUnadded() > 0

  /** Creates a [PhotoAsset] from an arbitrary media [Uri], detecting whether it is a video. */
  suspend fun createAssetFromUri(uri: Uri): PhotoAsset {
    val uriString = uri.toString()
    val isPickerUri = uriString.contains("picker")
    if (!isPickerUri) {
      val numericId = uri.lastPathSegment?.toLongOrNull()
      val fetched = numericId?.let { fetchAsset(it.toString()) }
      if (fetched != null) {
        return fetched.copy(id = uri.toString(), contentUri = uri)
      }
    }
    val isVideo =
      uriString.contains("/video/") ||
        listOf(".mp4", ".mov", ".mkv", ".webm", ".3gp").any {
          uriString.endsWith(it, ignoreCase = true)
        }
    return PhotoAsset(
      id = uri.toString(),
      contentUri = uri,
      dateTaken = System.currentTimeMillis(),
      displayName = uri.lastPathSegment ?: if (isVideo) "Video" else "Photo",
      isVideo = isVideo,
    )
  }
}

class DefaultPhotoLibraryService(
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhotoLibraryService {

  companion object {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxOf(1024 * 16, maxMemory / 8)
    private val bitmapLruCache =
      object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
          return bitmap.byteCount / 1024
        }
      }
    private val FALLBACK_DIMENSIONS = listOf(1024, 512, 256)

    // Shared across service instances so background workers and UI view models share
    // in-memory asset metadata without redundant disk/MediaStore queries.
    private val assetCache = ConcurrentHashMap<String, PhotoAsset>()
    private val customAssets = ConcurrentHashMap<String, PhotoAsset>()

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun putBitmap(key: String, bitmap: Bitmap) {
      bitmapLruCache.put(key, bitmap)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun getBitmap(key: String): Bitmap? = bitmapLruCache.get(key)

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun clearCache() {
      bitmapLruCache.evictAll()
      assetCache.clear()
      customAssets.clear()
    }
  }

  override fun getCachedBitmap(asset: PhotoAsset, targetDimension: Int): Bitmap? {
    bitmapLruCache.get("${asset.id}_$targetDimension")?.let {
      return it
    }
    if (targetDimension > 0) {
      for (dim in FALLBACK_DIMENSIONS) {
        if (dim >= targetDimension) {
          bitmapLruCache.get("${asset.id}_$dim")?.let {
            return it
          }
        }
      }
    }
    return null
  }

  private fun extractMediaStoreId(uri: Uri): String? {
    val lastSegment = uri.lastPathSegment ?: return null
    val numericPart = lastSegment.substringAfterLast(':')
    return numericPart.toLongOrNull()?.toString()
  }

  private fun shouldIncludeAsset(
    asset: PhotoAsset,
    existingIds: Set<String>,
    removedIds: Set<String>,
  ): Boolean {
    val mediaStoreId = extractMediaStoreId(asset.contentUri)
    return asset.id !in existingIds &&
      (mediaStoreId == null || mediaStoreId !in existingIds) &&
      asset.id !in removedIds &&
      (mediaStoreId == null || mediaStoreId !in removedIds)
  }

  override suspend fun addCustomAssets(assets: List<PhotoAsset>) =
    withContext(ioDispatcher) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val current = prefs.getStringSet(KEY_CUSTOM_ASSET_URIS, emptySet()) ?: emptySet()
      val newUris = assets.map { it.contentUri.toString() }
      val updated = HashSet(current) + newUris

      val removedCurrent = prefs.getStringSet(KEY_REMOVED_ASSET_IDS, emptySet()) ?: emptySet()
      val idsToUnremove = mutableSetOf<String>()
      for (asset in assets) {
        try {
          context.contentResolver.takePersistableUriPermission(
            asset.contentUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        } catch (e: Exception) {
          Log.w(TAG, "Failed to take persistable URI permission for ${asset.contentUri}", e)
        }
        idsToUnremove.add(asset.id)
        idsToUnremove.add(asset.contentUri.toString())
        extractMediaStoreId(asset.contentUri)?.let { idsToUnremove.add(it) }
      }
      val removedUpdated = removedCurrent - idsToUnremove

      prefs.edit(commit = true) {
        putStringSet(KEY_CUSTOM_ASSET_URIS, updated)
        if (removedUpdated.size != removedCurrent.size) {
          putStringSet(KEY_REMOVED_ASSET_IDS, removedUpdated)
        }
      }
      for (asset in assets) {
        customAssets[asset.id] = asset
        customAssets[asset.contentUri.toString()] = asset
        assetCache[asset.id] = asset
      }
    }

  private suspend fun loadStoredCustomAssets(): List<PhotoAsset> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val storedUris = prefs.getStringSet(KEY_CUSTOM_ASSET_URIS, emptySet()) ?: emptySet()
    val removedIds = getRemovedAssetIdentifiers()
    val result = mutableListOf<PhotoAsset>()
    for (uriString in storedUris) {
      try {
        val uri = uriString.toUri()
        val asset = customAssets[uriString] ?: createAssetFromUri(uri)
        if (shouldIncludeAsset(asset, emptySet(), removedIds)) {
          customAssets[asset.id] = asset
          customAssets[uriString] = asset
          assetCache[asset.id] = asset
          result.add(asset)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to restore custom asset from URI: $uriString", e)
      }
    }
    return result
  }

  override suspend fun removeAssets(ids: Set<String>) =
    withContext(ioDispatcher) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val current = prefs.getStringSet(KEY_REMOVED_ASSET_IDS, emptySet()) ?: emptySet()
      val allIdsToRemove = HashSet(ids)
      for (id in ids) {
        if (id.startsWith("content://") || id.startsWith("file://") || id.startsWith("/")) {
          try {
            extractMediaStoreId(id.toUri())?.let { allIdsToRemove.add(it) }
          } catch (e: Exception) {
            // ignore
          }
        }
      }
      val customCurrent = prefs.getStringSet(KEY_CUSTOM_ASSET_URIS, emptySet()) ?: emptySet()
      val customUpdated =
        customCurrent
          .filterNot { uriStr ->
            uriStr in allIdsToRemove ||
              customAssets[uriStr]?.id in allIdsToRemove ||
              extractMediaStoreId(uriStr.toUri()) in allIdsToRemove
          }
          .toSet()
      val prunedCustomUris = customCurrent - customUpdated
      allIdsToRemove.addAll(prunedCustomUris)
      for (uriStr in prunedCustomUris) {
        customAssets[uriStr]?.id?.let { allIdsToRemove.add(it) }
      }
      val updated = HashSet(current) + allIdsToRemove
      prefs.edit(commit = true) {
        putStringSet(KEY_REMOVED_ASSET_IDS, updated)
        putStringSet(KEY_CUSTOM_ASSET_URIS, customUpdated)
      }
      for (id in allIdsToRemove) {
        customAssets.remove(id)
        assetCache.remove(id)
        bitmapLruCache.remove("${id}_128")
        bitmapLruCache.remove("${id}_256")
        bitmapLruCache.remove("${id}_512")
        bitmapLruCache.remove("${id}_1024")
      }
    }

  override suspend fun getRemovedAssetIdentifiers(): Set<String> =
    withContext(ioDispatcher) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.getStringSet(KEY_REMOVED_ASSET_IDS, emptySet())?.toSet() ?: emptySet()
    }

  override suspend fun clearRemovedAssets() =
    withContext(ioDispatcher) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit(commit = true) {
        remove(KEY_REMOVED_ASSET_IDS)
        remove(KEY_MAX_MEDIASTORE_ASSET_COUNT)
      }
    }

  private fun getMaxMediaStoreAssetCount(): Int? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return if (prefs.contains(KEY_MAX_MEDIASTORE_ASSET_COUNT)) {
      prefs.getInt(KEY_MAX_MEDIASTORE_ASSET_COUNT, -1).takeIf { it >= 0 }
    } else {
      null
    }
  }

  override fun isFullLibraryAccessEnabled(): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_FULL_LIBRARY_ACCESS_ENABLED, true)
  }

  override suspend fun setFullLibraryAccessEnabled(enabled: Boolean) =
    withContext(ioDispatcher) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit(commit = true) { putBoolean(KEY_FULL_LIBRARY_ACCESS_ENABLED, enabled) }
    }

  override suspend fun selectRecentAssets(count: Int) =
    withContext(ioDispatcher) {
      val totalCount = fetchMediaStoreAssetCount()
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit(commit = true) {
        putBoolean(KEY_FULL_LIBRARY_ACCESS_ENABLED, true)
        remove(KEY_REMOVED_ASSET_IDS)
        if (count in 0 until totalCount) {
          putInt(KEY_MAX_MEDIASTORE_ASSET_COUNT, count)
        } else {
          remove(KEY_MAX_MEDIASTORE_ASSET_COUNT)
        }
      }
    }

  override suspend fun fetchMediaStoreAssetCount(): Int =
    withContext(ioDispatcher) { queryMediaStoreAssetIds(emptySet()).size }

  override suspend fun fetchPermittedMediaStoreCountIfUnadded(): Int =
    withContext(ioDispatcher) {
      val allPermittedIds = queryMediaStoreAssetIds(emptySet())
      if (allPermittedIds.isEmpty()) return@withContext 0
      val removedIds = getRemovedAssetIdentifiers()
      val maxCount = getMaxMediaStoreAssetCount()
      val hasUnadded =
        if (isFullLibraryAccessEnabled()) {
          (maxCount != null && maxCount < allPermittedIds.size) ||
            allPermittedIds.any { it in removedIds }
        } else {
          val customList = loadStoredCustomAssets()
          val includedMediaStoreIds = mutableSetOf<String>()
          for (asset in customList) {
            if (shouldIncludeAsset(asset, includedMediaStoreIds, removedIds)) {
              includedMediaStoreIds.add(asset.id)
              extractMediaStoreId(asset.contentUri)?.let { includedMediaStoreIds.add(it) }
            }
          }
          allPermittedIds.any { it !in includedMediaStoreIds }
        }
      if (hasUnadded) allPermittedIds.size else 0
    }

  override suspend fun hasUnaddedPermittedMediaStoreAssets(): Boolean =
    fetchPermittedMediaStoreCountIfUnadded() > 0

  private fun queryMediaStoreAssetIds(
    removedIds: Set<String>,
    maxCount: Int? = null,
  ): List<String> {
    if (maxCount != null && maxCount <= 0) return emptyList()
    val ids = mutableListOf<String>()
    val projection =
      arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
      )
    val selection =
      "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
    val selectionArgs =
      arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
      )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
    val resolver: ContentResolver = context.contentResolver
    var scannedCount = 0
    try {
      resolver
        .query(
          MediaStore.Files.getContentUri("external"),
          projection,
          selection,
          selectionArgs,
          sortOrder,
        )
        ?.use { cursor ->
          val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
          while (cursor.moveToNext()) {
            if (maxCount != null && scannedCount >= maxCount) break
            scannedCount++
            val id = cursor.getLong(idColumn).toString()
            if (id !in removedIds) {
              ids.add(id)
            }
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore.Files query failed: ${e.message}")
    }
    if (ids.isEmpty() && scannedCount == 0) {
      val imageVideoIds = mutableListOf<Pair<Long, Long>>()
      try {
        resolver
          .query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            null,
            null,
            sortOrder,
          )
          ?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
              val id = cursor.getLong(idColumn)
              val dateTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
              imageVideoIds.add(id to dateTaken)
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "MediaStore.Images query fallback failed: ${e.message}")
      }
      try {
        resolver
          .query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_TAKEN),
            null,
            null,
            sortOrder,
          )
          ?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
              val id = cursor.getLong(idColumn)
              val dateTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
              imageVideoIds.add(id to dateTaken)
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "MediaStore.Video query fallback failed: ${e.message}")
      }
      imageVideoIds.sortByDescending { it.second }
      val distinctIds = imageVideoIds.map { it.first.toString() }.distinct()
      val limitedIds = if (maxCount != null) distinctIds.take(maxCount) else distinctIds
      ids.addAll(limitedIds.filter { it !in removedIds })
    }
    return ids
  }

  override suspend fun fetchAllAssetIdentifiers(): List<String> =
    withContext(ioDispatcher) {
      val removedIds = getRemovedAssetIdentifiers()
      val ids = mutableListOf<String>()
      if (isFullLibraryAccessEnabled()) {
        ids.addAll(queryMediaStoreAssetIds(removedIds, getMaxMediaStoreAssetCount()))
      }
      val customList = loadStoredCustomAssets()
      val existingIds = ids.toHashSet()
      for (asset in customList) {
        if (shouldIncludeAsset(asset, existingIds, removedIds)) {
          ids.add(0, asset.id)
          existingIds.add(asset.id)
          extractMediaStoreId(asset.contentUri)?.let { existingIds.add(it) }
        }
      }
      ids
    }

  override suspend fun fetchAsset(id: String): PhotoAsset? =
    withContext(ioDispatcher) {
      val removedIds = getRemovedAssetIdentifiers()
      if (id in removedIds) return@withContext null
      if (id.startsWith("content://") || id.startsWith("file://") || id.startsWith("/")) {
        val uri = if (id.startsWith("/")) Uri.fromFile(File(id)) else id.toUri()
        extractMediaStoreId(uri)?.let { if (it in removedIds) return@withContext null }
      }

      val cached = assetCache[id] ?: customAssets[id]
      if (cached != null) return@withContext cached

      if (id.startsWith("content://") || id.startsWith("file://") || id.startsWith("/")) {
        val customList = loadStoredCustomAssets()
        val custom = customList.find { it.id == id }
        if (custom != null) return@withContext custom

        val uri = if (id.startsWith("/")) Uri.fromFile(File(id)) else id.toUri()
        val asset = createAssetFromUri(uri)
        assetCache[id] = asset
        return@withContext asset
      }

      val numericId = id.toLongOrNull() ?: return@withContext null
      var isVideo = false
      var durationMs: Long? = null
      var dateTaken = 0L
      var sizeBytes = 0L
      var displayName = "Media $id"

      val projection =
        arrayOf(
          MediaStore.Files.FileColumns._ID,
          MediaStore.Files.FileColumns.MEDIA_TYPE,
          MediaStore.Files.FileColumns.DATE_TAKEN,
          MediaStore.Files.FileColumns.SIZE,
          MediaStore.Files.FileColumns.DISPLAY_NAME,
          MediaStore.Files.FileColumns.DURATION,
        )
      val selection =
        "${MediaStore.Files.FileColumns._ID} = ? AND (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
      val selectionArgs =
        arrayOf(
          id,
          MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
          MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
      val resolver: ContentResolver = context.contentResolver
      var foundInFiles = false
      try {
        resolver
          .query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null,
          )
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              foundInFiles = true
              val typeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
              val dateIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
              val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
              val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
              val durIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)

              if (typeIdx >= 0) {
                isVideo = cursor.getInt(typeIdx) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
              }
              if (dateIdx >= 0) dateTaken = cursor.getLong(dateIdx)
              if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
              if (nameIdx >= 0) {
                displayName =
                  cursor.getString(nameIdx) ?: (if (isVideo) "Video $id" else "Photo $id")
              }
              if (durIdx >= 0 && !cursor.isNull(durIdx)) durationMs = cursor.getLong(durIdx)
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "MediaStore.Files query error: ${e.message}")
      }

      if (!foundInFiles) {
        try {
          val imgUri =
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId)
          resolver
            .query(
              imgUri,
              arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DISPLAY_NAME,
              ),
              null,
              null,
              null,
            )
            ?.use { cursor ->
              if (cursor.moveToFirst()) {
                foundInFiles = true
                isVideo = false
                val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (dateIdx >= 0) dateTaken = cursor.getLong(dateIdx)
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: "Photo $id"
              }
            }
        } catch (e: Exception) {
          // Fallback ignored
        }
      }

      if (!foundInFiles) {
        try {
          val vidUri =
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, numericId)
          resolver
            .query(
              vidUri,
              arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
              ),
              null,
              null,
              null,
            )
            ?.use { cursor ->
              if (cursor.moveToFirst()) {
                foundInFiles = true
                isVideo = true
                val dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                if (dateIdx >= 0) dateTaken = cursor.getLong(dateIdx)
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: "Video $id"
                if (durIdx >= 0 && !cursor.isNull(durIdx)) durationMs = cursor.getLong(durIdx)
              }
            }
        } catch (e: Exception) {
          // Fallback ignored
        }
      }

      if (!foundInFiles) return@withContext null

      val contentUri =
        if (isVideo) {
          ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, numericId)
        } else {
          ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId)
        }

      val asset =
        PhotoAsset(
          id = id,
          contentUri = contentUri,
          dateTaken = if (dateTaken > 0) dateTaken else System.currentTimeMillis(),
          sizeBytes = sizeBytes,
          displayName = displayName,
          locationName = null,
          latitude = null,
          longitude = null,
          deviceModel =
            "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} ${Build.MODEL}",
          isVideo = isVideo,
          durationMs = durationMs,
        )
      assetCache[id] = asset
      asset
    }

  override suspend fun fetchAssets(ids: List<String>): List<PhotoAsset> =
    withContext(ioDispatcher) { ids.mapNotNull { fetchAsset(it) } }

  override suspend fun fetchAllAssets(): List<PhotoAsset> =
    withContext(ioDispatcher) {
      val removedIds = getRemovedAssetIdentifiers()
      val maxCount = getMaxMediaStoreAssetCount()
      val assets = mutableListOf<PhotoAsset>()
      if (isFullLibraryAccessEnabled() && (maxCount == null || maxCount > 0)) {
        val projection =
          arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
          )
        val selection =
          "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs =
          arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
          )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
        val resolver: ContentResolver = context.contentResolver
        var scannedCount = 0
        try {
          resolver
            .query(
              MediaStore.Files.getContentUri("external"),
              projection,
              selection,
              selectionArgs,
              sortOrder,
            )
            ?.use { cursor ->
              val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
              val typeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
              val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
              val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
              val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
              val durCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)

              while (cursor.moveToNext()) {
                if (maxCount != null && scannedCount >= maxCount) break
                scannedCount++
                val id = cursor.getLong(idCol).toString()
                if (id in removedIds) continue
                val numericId = cursor.getLong(idCol)
                val isVideo =
                  if (typeCol >= 0) {
                    cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                  } else false
                val contentUri =
                  if (isVideo) {
                    ContentUris.withAppendedId(
                      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                      numericId,
                    )
                  } else {
                    ContentUris.withAppendedId(
                      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                      numericId,
                    )
                  }
                val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val displayName =
                  if (nameCol >= 0) {
                    cursor.getString(nameCol) ?: (if (isVideo) "Video $id" else "Photo $id")
                  } else (if (isVideo) "Video $id" else "Photo $id")
                val durationMs =
                  if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else null

                val asset =
                  PhotoAsset(
                    id = id,
                    contentUri = contentUri,
                    dateTaken = if (dateTaken > 0) dateTaken else System.currentTimeMillis(),
                    sizeBytes = sizeBytes,
                    displayName = displayName,
                    locationName = null,
                    latitude = null,
                    longitude = null,
                    deviceModel =
                      "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} ${Build.MODEL}",
                    isVideo = isVideo,
                    durationMs = durationMs,
                  )
                assetCache[id] = asset
                assets.add(asset)
              }
            }
        } catch (e: Exception) {
          Log.e(TAG, "MediaStore batch query error: ${e.message}")
        }
        if (assets.isEmpty() && scannedCount == 0) {
          val fallbackAssets = mutableListOf<PhotoAsset>()
          try {
            resolver
              .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                  MediaStore.Images.Media._ID,
                  MediaStore.Images.Media.DATE_TAKEN,
                  MediaStore.Images.Media.SIZE,
                  MediaStore.Images.Media.DISPLAY_NAME,
                ),
                null,
                null,
                sortOrder,
              )
              ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                  val numericId = cursor.getLong(idCol)
                  val id = numericId.toString()
                  val contentUri =
                    ContentUris.withAppendedId(
                      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                      numericId,
                    )
                  val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                  val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                  val displayName =
                    if (nameCol >= 0) cursor.getString(nameCol) ?: "Photo $id" else "Photo $id"
                  val asset =
                    PhotoAsset(
                      id = id,
                      contentUri = contentUri,
                      dateTaken = if (dateTaken > 0) dateTaken else System.currentTimeMillis(),
                      sizeBytes = sizeBytes,
                      displayName = displayName,
                      locationName = null,
                      latitude = null,
                      longitude = null,
                      deviceModel =
                        "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} ${Build.MODEL}",
                      isVideo = false,
                      durationMs = null,
                    )
                  assetCache[id] = asset
                  fallbackAssets.add(asset)
                }
              }
          } catch (e: Exception) {
            Log.e(TAG, "MediaStore.Images fetchAllAssets fallback failed: ${e.message}")
          }
          try {
            resolver
              .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                  MediaStore.Video.Media._ID,
                  MediaStore.Video.Media.DATE_TAKEN,
                  MediaStore.Video.Media.SIZE,
                  MediaStore.Video.Media.DISPLAY_NAME,
                  MediaStore.Video.Media.DURATION,
                ),
                null,
                null,
                sortOrder,
              )
              ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                  val numericId = cursor.getLong(idCol)
                  val id = numericId.toString()
                  val contentUri =
                    ContentUris.withAppendedId(
                      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                      numericId,
                    )
                  val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                  val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                  val displayName =
                    if (nameCol >= 0) cursor.getString(nameCol) ?: "Video $id" else "Video $id"
                  val durationMs =
                    if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else null
                  val asset =
                    PhotoAsset(
                      id = id,
                      contentUri = contentUri,
                      dateTaken = if (dateTaken > 0) dateTaken else System.currentTimeMillis(),
                      sizeBytes = sizeBytes,
                      displayName = displayName,
                      locationName = null,
                      latitude = null,
                      longitude = null,
                      deviceModel =
                        "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} ${Build.MODEL}",
                      isVideo = true,
                      durationMs = durationMs,
                    )
                  assetCache[id] = asset
                  fallbackAssets.add(asset)
                }
              }
          } catch (e: Exception) {
            Log.e(TAG, "MediaStore.Video fetchAllAssets fallback failed: ${e.message}")
          }
          fallbackAssets.sortByDescending { it.dateTaken }
          val limitedFallback =
            if (maxCount != null) fallbackAssets.take(maxCount) else fallbackAssets
          assets.addAll(limitedFallback.filterNot { it.id in removedIds })
        }
      }

      val customList = loadStoredCustomAssets()
      val existingIds = assets.mapTo(HashSet()) { it.id }
      for (asset in customList) {
        if (shouldIncludeAsset(asset, existingIds, removedIds)) {
          assets.add(0, asset)
          existingIds.add(asset.id)
          extractMediaStoreId(asset.contentUri)?.let { existingIds.add(it) }
        }
      }
      assets
    }

  @Suppress("DEPRECATION")
  override suspend fun fetchDetailedAsset(asset: PhotoAsset): PhotoAsset =
    withContext(ioDispatcher) {
      if (asset.latitude != null && asset.longitude != null && asset.locationName != null) {
        return@withContext asset
      }
      assetCache[asset.id]?.let { cached ->
        if (cached.latitude != null && cached.longitude != null && cached.locationName != null) {
          return@withContext cached
        }
      }

      var lat: Double? = null
      var lng: Double? = null

      // 1. Try MediaStore columns
      try {
        val latCol =
          if (asset.isVideo) MediaStore.Video.Media.LATITUDE else MediaStore.Images.Media.LATITUDE
        val lngCol =
          if (asset.isVideo) MediaStore.Video.Media.LONGITUDE else MediaStore.Images.Media.LONGITUDE
        val projection = arrayOf(latCol, lngCol)
        context.contentResolver.query(asset.contentUri, projection, null, null, null)?.use { cursor
          ->
          if (cursor.moveToFirst()) {
            val latIdx = cursor.getColumnIndex(latCol)
            val lngIdx = cursor.getColumnIndex(lngCol)
            if (latIdx >= 0 && !cursor.isNull(latIdx)) {
              val v = cursor.getDouble(latIdx)
              if (v != 0.0) lat = v
            }
            if (lngIdx >= 0 && !cursor.isNull(lngIdx)) {
              val v = cursor.getDouble(lngIdx)
              if (v != 0.0) lng = v
            }
          }
        }
      } catch (e: Exception) {
        // MediaStore location query error ignored
      }

      // 2. Try EXIF with unredacted MediaStore URI (for images)
      if (!asset.isVideo && (lat == null || lng == null)) {
        try {
          val targetUri =
            try {
              MediaStore.setRequireOriginal(asset.contentUri)
            } catch (e: Exception) {
              asset.contentUri
            }
          val inputStream = openSafeInputStream(context, targetUri)
          inputStream?.use { stream ->
            val exif = ExifInterface(stream)
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
              if (latLong[0] != 0f || latLong[1] != 0f) {
                lat = latLong[0].toDouble()
                lng = latLong[1].toDouble()
              }
            }
          }
        } catch (e: Exception) {
          // EXIF extraction error ignored
        }
      }

      // 3. Geocoder lookup for city/state
      var locationName: String? = null
      if (lat != null && lng != null) {
        locationName = getCityAndState(context, lat!!, lng!!)
      }

      val detailed = asset.copy(locationName = locationName, latitude = lat, longitude = lng)
      assetCache[asset.id] = detailed
      detailed
    }

  override suspend fun loadKeyframes(asset: PhotoAsset, targetDimension: Int): List<Bitmap> =
    withContext(ioDispatcher) {
      if (asset.isVideo) {
        extractVideoKeyframes(asset, targetDimension)
      } else {
        loadBitmap(asset, targetDimension)?.let { listOf(it) } ?: emptyList()
      }
    }

  private suspend fun extractVideoKeyframes(
    asset: PhotoAsset,
    targetDimension: Int = 1024,
  ): List<Bitmap> =
    withContext(ioDispatcher) {
      val retriever = MediaMetadataRetriever()
      try {
        if (
          asset.contentUri.scheme == "file" || asset.contentUri.toString().startsWith("file://")
        ) {
          val filePath = asset.contentUri.path ?: asset.contentUri.schemeSpecificPart
          retriever.setDataSource(filePath)
        } else if (
          asset.contentUri.scheme == "asset" || asset.contentUri.toString().startsWith("asset://")
        ) {
          val path =
            asset.contentUri.path?.removePrefix("/")
              ?: asset.contentUri.schemeSpecificPart.trimStart('/')
          context.assets.openFd(path).use { afd ->
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
          }
        } else {
          retriever.setDataSource(context, asset.contentUri)
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: (asset.durationMs ?: 0L)
        val durationUs = durationMs * 1000L

        // First keyframe at the beginning of the video (timeUs = 0)
        val firstKeyframe: Bitmap? =
          if (targetDimension > 0) {
            retriever.getScaledFrameAtTime(
              0L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              targetDimension,
              targetDimension,
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          }

        // Last keyframe near the end of the video
        val lastKeyframe: Bitmap? =
          if (durationUs > 1_000_000L) {
            val targetLastUs = maxOf(0L, durationUs - 500_000L)
            if (targetDimension > 0) {
              retriever.getScaledFrameAtTime(
                targetLastUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetDimension,
                targetDimension,
              )
                ?: retriever.getFrameAtTime(
                  targetLastUs,
                  MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } else {
              retriever.getFrameAtTime(targetLastUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
          } else {
            null
          }

        val keyframes = mutableListOf<Bitmap>()
        if (firstKeyframe != null) {
          keyframes.add(firstKeyframe)
        }
        if (
          lastKeyframe != null && (firstKeyframe == null || !firstKeyframe.sameAs(lastKeyframe))
        ) {
          keyframes.add(lastKeyframe)
        }
        if (firstKeyframe != null && targetDimension > 0) {
          bitmapLruCache.put("${asset.id}_$targetDimension", firstKeyframe)
        }
        keyframes
      } catch (e: Exception) {
        Log.e(TAG, "Error extracting keyframes from video ${asset.id}: ${e.message}")
        loadBitmap(asset, targetDimension)?.let { listOf(it) } ?: emptyList()
      } finally {
        try {
          retriever.release()
        } catch (e: Exception) {
          // Ignored
        }
      }
    }

  override suspend fun loadBitmap(asset: PhotoAsset, targetDimension: Int): Bitmap? {
    val cacheKey = "${asset.id}_$targetDimension"
    bitmapLruCache.get(cacheKey)?.let {
      return it
    }
    if (targetDimension > 0) {
      for (dim in FALLBACK_DIMENSIONS) {
        if (dim >= targetDimension) {
          val fallbackCached = bitmapLruCache.get("${asset.id}_$dim")
          if (fallbackCached != null) {
            return fallbackCached
          }
        }
      }
    }

    val bitmap =
      withContext(ioDispatcher) {
        if (asset.isVideo) {
          if (asset.contentUri.scheme == "content") {
            try {
              val thumb =
                context.contentResolver.loadThumbnail(
                  asset.contentUri,
                  Size(targetDimension, targetDimension),
                  null,
                )
              return@withContext thumb
            } catch (e: Exception) {
              // Fall back to MediaMetadataRetriever
            }
          }
          val retriever = MediaMetadataRetriever()
          return@withContext try {
            if (
              asset.contentUri.scheme == "file" || asset.contentUri.toString().startsWith("file://")
            ) {
              val filePath = asset.contentUri.path ?: asset.contentUri.schemeSpecificPart
              retriever.setDataSource(filePath)
            } else if (
              asset.contentUri.scheme == "asset" ||
                asset.contentUri.toString().startsWith("asset://")
            ) {
              val path =
                asset.contentUri.path?.removePrefix("/")
                  ?: asset.contentUri.schemeSpecificPart.trimStart('/')
              context.assets.openFd(path).use { afd ->
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
              }
            } else {
              retriever.setDataSource(context, asset.contentUri)
            }
            if (targetDimension > 0) {
              retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetDimension,
                targetDimension,
              ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
              retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error loading video thumbnail: ${asset.id}, ${e.message}")
            null
          } finally {
            try {
              retriever.release()
            } catch (e: Exception) {
              // Ignored
            }
          }
        }

        if (
          asset.contentUri.scheme == "file" || asset.contentUri.toString().startsWith("file://")
        ) {
          val filePath = asset.contentUri.path ?: asset.contentUri.schemeSpecificPart
          return@withContext try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            if (maxDim > targetDimension && targetDimension > 0) {
              sampleSize = maxOf(1, maxDim / targetDimension)
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(filePath, decodeOptions)
          } catch (e: Exception) {
            Log.e(TAG, "Error loading file bitmap: $filePath, ${e.message}")
            null
          }
        }

        if (
          asset.contentUri.scheme == "asset" || asset.contentUri.toString().startsWith("asset://")
        ) {
          val path =
            asset.contentUri.path?.removePrefix("/")
              ?: asset.contentUri.schemeSpecificPart.trimStart('/')
          return@withContext try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { stream ->
              BitmapFactory.decodeStream(stream, null, options)
            }
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            if (maxDim > targetDimension && targetDimension > 0) {
              sampleSize = maxOf(1, maxDim / targetDimension)
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.assets.open(path).use { decodeStream ->
              BitmapFactory.decodeStream(decodeStream, null, decodeOptions)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error loading asset bitmap: $path, ${e.message}")
            null
          }
        }

        try {
          val source = ImageDecoder.createSource(context.contentResolver, asset.contentUri)
          ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            val maxDim = maxOf(size.width, size.height)
            if (maxDim > targetDimension) {
              val scale = targetDimension.toFloat() / maxDim
              decoder.setTargetSize(
                maxOf(1, (size.width * scale).toInt()),
                maxOf(1, (size.height * scale).toInt()),
              )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          }
        } catch (e: Exception) {
          try {
            context.contentResolver.loadThumbnail(
              asset.contentUri,
              Size(targetDimension, targetDimension),
              null,
            )
          } catch (e2: Exception) {
            null
          }
        }
      }
    if (bitmap != null) {
      bitmapLruCache.put(cacheKey, bitmap)
    }
    return bitmap
  }

  override suspend fun createAssetFromUri(uri: Uri): PhotoAsset =
    withContext(ioDispatcher) {
      val uriString = uri.toString()
      val fetched = extractMediaStoreId(uri)?.let { fetchAsset(it) }
      if (fetched != null) {
        return@withContext fetched.copy(
          id = uriString,
          contentUri = if ("picker" in uriString) fetched.contentUri else uri,
        )
      }
      var isVideo = false
      var durationMs: Long? = null
      var displayName = uri.lastPathSegment ?: "Media"

      try {
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
          isVideo = mimeType.startsWith("video/")
        }
      } catch (e: Exception) {
        // Fallback to URI analysis
      }

      if (!isVideo) {
        isVideo =
          uriString.contains("/video/") ||
            listOf(".mp4", ".mov", ".mkv", ".webm", ".3gp").any {
              uriString.endsWith(it, ignoreCase = true)
            }
      }

      try {
        context.contentResolver
          .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                cursor.getString(nameIdx)?.let { displayName = it }
              }
            }
          }
      } catch (e: Exception) {
        // Fallback
      }

      if (isVideo) {
        try {
          val retriever = MediaMetadataRetriever()
          try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull()
          } finally {
            retriever.release()
          }
        } catch (e: Exception) {
          // Ignored
        }
      }

      PhotoAsset(
        id = uri.toString(),
        contentUri = uri,
        dateTaken = System.currentTimeMillis(),
        displayName = displayName,
        isVideo = isVideo,
        durationMs = durationMs,
      )
    }

  private fun getCityAndState(context: Context, lat: Double, lng: Double): String? {
    return try {
      @Suppress("DEPRECATION") val geocoder = Geocoder(context, Locale.getDefault())
      @Suppress("DEPRECATION") val addresses = geocoder.getFromLocation(lat, lng, 1)
      if (!addresses.isNullOrEmpty()) {
        val addr = addresses[0]
        val city = addr.locality ?: addr.subAdminArea
        val region = addr.adminArea ?: addr.countryName
        if (city != null && region != null) "$city, $region" else city ?: region
      } else null
    } catch (e: Exception) {
      null
    }
  }
}
