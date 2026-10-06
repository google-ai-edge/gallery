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

package com.google.ai.edge.gallery.customtasks.smartalbum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "SampleAlbumStorageMgr"
private const val ASSETS_SAMPLE_DIR = "smartalbum_samples/${SampleAlbumStorageManager.IMAGES_DIR}"
private const val PREFS_NAME = "smart_album_prefs"
private const val KEY_SAMPLES_REMOVED = "sample_photos_removed"
private const val KEY_REMOVED_SAMPLE_IDS = "removed_sample_ids"

/**
 * Manages sample photo dataset detection, loading, bitmap rendering, and deletion from downloaded
 * model data directory, SDCard storage, or APK assets.
 */
class SampleAlbumStorageManager(
  private val context: Context,
  private val model: Model? = null,
  private val modelPath: String? = null,
) {
  companion object {
    const val SAMPLES_DIR = "samples"
    const val IMAGES_DIR = "images"
    const val DATABASE_NAME = DefaultSemanticRetrievalServiceProvider.SAMPLE_DATABASE_NAME
    val DB_EXTENSIONS = listOf("", "-wal", "-shm", "-journal")
  }

  @Volatile private var cachedAssets: List<PhotoAsset>? = null

  fun invalidateCachedAssets() {
    cachedAssets = null
  }

  fun getBaseModelDirectory(): File {
    if (modelPath != null) {
      val modelFile = File(modelPath)
      val modelDir = if (modelFile.isDirectory) modelFile else modelFile.parentFile
      if (modelDir != null) {
        return modelDir
      }
    }
    if (model != null) {
      val modelStorageDir = getModelStorageDir(context)
      val dir =
        File(
          modelStorageDir,
          "${model.normalizedName}${File.separator}${model.downloadInfo.version}",
        )
      if (dir.exists()) {
        return dir
      }
      val extDir =
        File(
          context.getExternalFilesDir(null) ?: context.filesDir,
          "${model.normalizedName}${File.separator}${model.downloadInfo.version}",
        )
      if (extDir.exists()) {
        return extDir
      }
      return dir
    }
    return getModelStorageDir(context)
  }

  val isSampleAlbumAvailable: Boolean
    get() {
      val items = fetchSampleAssets()
      return items.isNotEmpty()
    }

  fun isSamplesRemoved(): Boolean {
    return context
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_SAMPLES_REMOVED, false)
  }

  fun getRemovedSampleIds(): Set<String> {
    return context
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getStringSet(KEY_REMOVED_SAMPLE_IDS, emptySet())
      ?.toSet() ?: emptySet()
  }

  fun removeSampleAssets(ids: Set<String>) {
    cachedAssets = null
    val samplesDir = getSamplesDirectory()
    if (samplesDir != null && samplesDir.exists()) {
      try {
        val files = samplesDir.listFiles() ?: emptyArray()
        for (file in files) {
          val assetId = "sample_${file.name}"
          if (assetId in ids) {
            file.delete()
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to remove sample assets from disk: ${e.message}", e)
      }
    }

    val currentRemoved = getRemovedSampleIds()
    val updatedRemoved = HashSet(currentRemoved) + ids
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
      putStringSet(KEY_REMOVED_SAMPLE_IDS, updatedRemoved)
    }

    cachedAssets = null
  }

  fun resetRemovedStatus() {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
      remove(KEY_SAMPLES_REMOVED)
      remove(KEY_REMOVED_SAMPLE_IDS)
    }
    cachedAssets = null
  }

  fun invalidateCache() {
    cachedAssets = null
  }

  fun reload(): List<PhotoAsset> {
    cachedAssets = null
    return fetchSampleAssets()
  }

  fun fetchAsset(id: String): PhotoAsset? = fetchSampleAssets().find { it.id == id }

  fun getSamplesDownloadUrl(): String? {
    return model
      ?.downloadInfo
      ?.extraDataFiles
      ?.find { it.downloadFileName.endsWith(".zip", ignoreCase = true) }
      ?.url ?: model?.downloadInfo?.extraDataFiles?.firstOrNull()?.url
  }

  fun getSamplesTotalBytes(): Long {
    return model
      ?.downloadInfo
      ?.extraDataFiles
      ?.find { it.downloadFileName.endsWith(".zip", ignoreCase = true) }
      ?.sizeInBytes ?: model?.downloadInfo?.extraDataFiles?.firstOrNull()?.sizeInBytes ?: 0L
  }

  suspend fun downloadAndExtractSamples(
    url: String? = getSamplesDownloadUrl(),
    expectedTotalBytes: Long = getSamplesTotalBytes(),
    onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
  ): Boolean =
    withContext(Dispatchers.IO) {
      if (url.isNullOrEmpty()) {
        Log.e(TAG, "No sample download URL available")
        return@withContext false
      }
      val baseModelDir = getBaseModelDirectory()

      val tempZipFile = File.createTempFile("temp_samples_", ".zip", context.cacheDir)
      var connection: HttpURLConnection? = null
      try {
        val downloadUrl = URL(url)
        connection = downloadUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.requestMethod = "GET"
        connection.connect()

        val responseCode = connection.responseCode
        if (
          responseCode != HttpURLConnection.HTTP_OK &&
            responseCode != HttpURLConnection.HTTP_PARTIAL
        ) {
          Log.e(TAG, "Download failed with HTTP response code: $responseCode")
          return@withContext false
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) contentLength else expectedTotalBytes

        var receivedBytes = 0L
        val buffer = ByteArray(8192)

        connection.inputStream.use { input ->
          FileOutputStream(tempZipFile).use { output ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
              ensureActive()
              output.write(buffer, 0, bytesRead)
              receivedBytes += bytesRead
              onProgress(receivedBytes, totalBytes)
            }
            output.flush()
          }
        }

        // Unzip samples.
        var hasSamplesPrefix = false
        try {
          ZipInputStream(BufferedInputStream(FileInputStream(tempZipFile))).use { checkZipIn ->
            var checkEntry = checkZipIn.nextEntry
            while (checkEntry != null) {
              val entryName = checkEntry.name
              if (entryName.startsWith("$SAMPLES_DIR/")) {
                hasSamplesPrefix = true
                break
              }
              checkEntry = checkZipIn.nextEntry
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to inspect zip entries: ${e.message}", e)
        }

        val destDir = if (hasSamplesPrefix) baseModelDir else File(baseModelDir, SAMPLES_DIR)
        if (!destDir.exists()) {
          destDir.mkdirs()
        }

        val unzipBuffer = ByteArray(8192)
        ZipInputStream(BufferedInputStream(FileInputStream(tempZipFile))).use { zipIn ->
          var zipEntry = zipIn.nextEntry
          while (zipEntry != null) {
            ensureActive()
            val outFile = File(destDir, zipEntry.name)
            // Guard against Zip Slip.
            if (
              !outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                outFile.canonicalPath != destDir.canonicalPath
            ) {
              throw SecurityException("Zip entry is outside of the target dir: ${zipEntry.name}")
            }
            if (zipEntry.isDirectory) {
              outFile.mkdirs()
            } else {
              outFile.parentFile?.mkdirs()
              FileOutputStream(outFile).use { fos ->
                var len: Int
                while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                  fos.write(unzipBuffer, 0, len)
                }
              }
            }
            zipIn.closeEntry()
            zipEntry = zipIn.nextEntry
          }
        }

        resetRemovedStatus()
        cachedAssets = null
        Log.d(TAG, "Successfully downloaded and extracted samples to ${destDir.absolutePath}")
        true
      } catch (e: CancellationException) {
        Log.d(TAG, "Sample download was cancelled")
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Error downloading or extracting sample photos: ${e.message}", e)
        false
      } finally {
        connection?.disconnect()
        if (tempZipFile.exists()) {
          tempZipFile.delete()
        }
      }
    }

  private fun getSamplesDirectory(): File? {
    val supportedExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    fun hasImages(dir: File): Boolean {
      if (!dir.exists() || !dir.isDirectory) return false
      val files = dir.listFiles() ?: return false
      return files.any { it.isFile && supportedExts.contains(it.extension.lowercase(Locale.ROOT)) }
    }

    val baseModelDir = getBaseModelDirectory()
    if (baseModelDir.exists()) {
      val samplesImagesSubdir = File(baseModelDir, "$SAMPLES_DIR/$IMAGES_DIR")
      if (hasImages(samplesImagesSubdir)) return samplesImagesSubdir
      val imagesSubdir = File(baseModelDir, IMAGES_DIR)
      if (hasImages(imagesSubdir)) return imagesSubdir
      val samplesSubdir = File(baseModelDir, SAMPLES_DIR)
      if (hasImages(samplesSubdir)) return samplesSubdir
      if (hasImages(baseModelDir)) return baseModelDir
    }

    // 1. Check model-specific extracted samples directory:
    // e.g. <modelStorageDir>/Embedding_Gemma_V2/20260817/samples/
    if (model != null) {
      val modelStorageDir = getModelStorageDir(context)
      val candidateDirs =
        mutableListOf(
          File(
            modelStorageDir,
            "${model.normalizedName}${File.separator}${model.downloadInfo.version}${File.separator}$SAMPLES_DIR",
          ),
          File(
            modelStorageDir,
            "${model.normalizedName}${File.separator}${model.downloadInfo.version}",
          ),
          File(
            context.getExternalFilesDir(null),
            "${model.normalizedName}${File.separator}${model.downloadInfo.version}${File.separator}$SAMPLES_DIR",
          ),
          File(
            context.getExternalFilesDir(null),
            "${model.normalizedName.lowercase(Locale.ROOT)}${File.separator}${model.downloadInfo.version}${File.separator}$SAMPLES_DIR",
          ),
        )
      // Also check parent or sibling model variant directories (e.g. EmbeddingGemma 2 GPU vs TPU).
      val rootNormalizedName =
        model.hierarchy.parentModelName?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: model.normalizedName
      modelStorageDir.listFiles()?.forEach { dir ->
        if (
          dir.isDirectory &&
            (dir.name == rootNormalizedName || dir.name.startsWith("${rootNormalizedName}_"))
        ) {
          dir.listFiles()?.forEach { versionDir ->
            if (versionDir.isDirectory) {
              candidateDirs.add(File(versionDir, SAMPLES_DIR))
              candidateDirs.add(versionDir)
            }
          }
        }
      }
      for (modelSamplesDir in candidateDirs) {
        if (modelSamplesDir.exists() && modelSamplesDir.isDirectory) {
          val samplesImagesSubdir = File(modelSamplesDir, "$SAMPLES_DIR/$IMAGES_DIR")
          if (hasImages(samplesImagesSubdir)) {
            return samplesImagesSubdir
          }
          val imagesSubdir = File(modelSamplesDir, IMAGES_DIR)
          if (hasImages(imagesSubdir)) {
            return imagesSubdir
          }
          if (hasImages(modelSamplesDir)) {
            return modelSamplesDir
          }
        }
      }
    }

    // 2. Check general external files dir: <externalFilesDir>/samples/
    val externalSamplesDir = File(context.getExternalFilesDir(null), SAMPLES_DIR)
    if (externalSamplesDir.exists() && externalSamplesDir.isDirectory) {
      val imagesSubdir = File(externalSamplesDir, IMAGES_DIR)
      if (hasImages(imagesSubdir)) {
        return imagesSubdir
      }
      if (hasImages(externalSamplesDir)) {
        return externalSamplesDir
      }
    }

    return null
  }

  fun fetchSampleAssets(): List<PhotoAsset> {
    cachedAssets?.let {
      return it
    }

    val removedIds = getRemovedSampleIds()
    val supportedExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    // Check disk directory first.
    val samplesDir = getSamplesDirectory()
    if (samplesDir != null && samplesDir.exists()) {
      try {
        val files = samplesDir.listFiles() ?: emptyArray()
        val diskAssets =
          files
            .filter { file ->
              file.isFile && supportedExts.contains(file.extension.lowercase(Locale.ROOT))
            }
            .sortedBy { it.name }
            .filterNot { file -> "sample_${file.name}" in removedIds }
            .map { file ->
              val filename = file.name
              val displayName =
                filename.substringBeforeLast('.').replace('_', ' ').replaceFirstChar {
                  if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }

              PhotoAsset(
                id = "sample_$filename",
                contentUri = file.toUri(),
                dateTaken = file.lastModified(),
                sizeBytes = file.length(),
                displayName = displayName,
                locationName = null,
                latitude = null,
                longitude = null,
                deviceModel = "Demo Sample Photo",
              )
            }

        if (diskAssets.isNotEmpty()) {
          if (isSamplesRemoved()) {
            resetRemovedStatus()
          }
          cachedAssets = diskAssets
          return diskAssets
        } else if (files.isNotEmpty() && removedIds.isNotEmpty()) {
          cachedAssets = diskAssets
          return diskAssets
        }
      } catch (e: Exception) {
        Log.e(
          TAG,
          "Failed to list sample images from disk directory ${samplesDir.path}: ${e.message}",
          e,
        )
      }
    }

    if (isSamplesRemoved()) {
      cachedAssets = emptyList()
      return emptyList()
    }

    // Fall back to APK assets.
    val assetList =
      try {
        val files = context.assets.list(ASSETS_SAMPLE_DIR) ?: emptyArray()
        files
          .filter { file ->
            val ext = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
            supportedExts.contains(ext)
          }
          .sorted()
          .filterNot { filename -> "sample_$filename" in removedIds }
          .map { filename ->
            val assetPath = "$ASSETS_SAMPLE_DIR/$filename"
            val displayName =
              filename.substringBeforeLast('.').replace('_', ' ').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
              }

            PhotoAsset(
              id = "sample_$filename",
              contentUri = "asset:///$assetPath".toUri(),
              dateTaken = 0L,
              displayName = displayName,
              locationName = null,
              latitude = null,
              longitude = null,
              deviceModel = "Demo Sample Photo",
            )
          }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to list sample images from assets: ${e.message}", e)
        emptyList()
      }

    cachedAssets = assetList
    return assetList
  }

  fun deleteSamplePhotos() {
    val baseModelDir = getBaseModelDirectory()
    val baseSamplesDir = File(baseModelDir, SAMPLES_DIR)

    // 1. Close active services and cancel any pending indexing FIRST to release SQLite locks.
    if (model != null) {
      try {
        GemmaEmbeddingModelStore.closeServices(model.getPath(context))
      } catch (e: Exception) {
        Log.w(TAG, "Error closing services for ${model.name}: ${e.message}")
      }
    } else if (modelPath != null) {
      try {
        GemmaEmbeddingModelStore.closeServices(modelPath)
      } catch (e: Exception) {
        Log.w(TAG, "Error closing services for $modelPath: ${e.message}")
      }
    }
    try {
      SmartAlbumIndexingWorker.cancel(context, SmartAlbumSource.SAMPLE_ALBUM)
    } catch (e: Exception) {
      Log.w(TAG, "Error cancelling indexing worker: ${e.message}")
    }

    // 2. Delete any SQLite sample database files and WAL/SHM/journal files.
    val dbNames = DB_EXTENSIONS.map { "$DATABASE_NAME$it" }
    for (dbName in dbNames) {
      val dbInModelDir = File(baseModelDir, dbName)
      if (dbInModelDir.exists()) dbInModelDir.delete()
      val dbInSamplesDir = File(baseSamplesDir, dbName)
      if (dbInSamplesDir.exists()) dbInSamplesDir.delete()
      val appDb = context.getDatabasePath(dbName)
      if (appDb.exists()) appDb.delete()
    }

    // 3. Delete extracted sample files.
    val samplesDir = getSamplesDirectory()
    if (samplesDir != null && samplesDir.exists()) {
      try {
        samplesDir.deleteRecursively()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to delete sample directory: ${e.message}", e)
      }
    }

    val externalSamplesDir = File(context.getExternalFilesDir(null), SAMPLES_DIR)
    if (externalSamplesDir.exists()) {
      try {
        externalSamplesDir.deleteRecursively()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to delete external $SAMPLES_DIR directory: ${e.message}", e)
      }
    }

    if (baseSamplesDir.exists()) {
      try {
        baseSamplesDir.deleteRecursively()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to delete base samples directory: ${e.message}", e)
      }
    }

    if (model != null) {
      val lowercaseDir =
        File(
          context.getExternalFilesDir(null),
          "${model.normalizedName.lowercase(Locale.ROOT)}${File.separator}${model.downloadInfo.version}${File.separator}$SAMPLES_DIR",
        )
      if (lowercaseDir.exists()) {
        try {
          lowercaseDir.deleteRecursively()
        } catch (e: Exception) {
          Log.e(TAG, "Failed to delete model samples directory: ${e.message}", e)
        }
      }

      val modelStorageDir = getModelStorageDir(context)
      val rootNormalizedName =
        model.hierarchy.parentModelName?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: model.normalizedName
      modelStorageDir.listFiles()?.forEach { dir ->
        if (
          dir.isDirectory &&
            (dir.name == rootNormalizedName || dir.name.startsWith("${rootNormalizedName}_"))
        ) {
          dir.listFiles()?.forEach { versionDir ->
            if (versionDir.isDirectory) {
              try {
                GemmaEmbeddingModelStore.closeServices(versionDir.absolutePath)
              } catch (e: Exception) {
                Log.w(TAG, "Error closing services for ${versionDir.absolutePath}: ${e.message}")
              }
              val variantSamplesDir = File(versionDir, SAMPLES_DIR)
              if (variantSamplesDir.exists()) {
                try {
                  variantSamplesDir.deleteRecursively()
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to delete variant samples directory: ${e.message}", e)
                }
              }
              for (dbName in dbNames) {
                val dbInVariantDir = File(versionDir, dbName)
                if (dbInVariantDir.exists()) dbInVariantDir.delete()
              }
            }
          }
        }
      }
    }

    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
      putBoolean(KEY_SAMPLES_REMOVED, true)
    }

    cachedAssets = null
  }

  suspend fun loadBitmap(asset: PhotoAsset, targetDimension: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
      if (asset.contentUri.scheme == "file" || asset.contentUri.toString().startsWith("file://")) {
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
          Log.e(TAG, "Error loading sample file bitmap $filePath: ${e.message}")
          null
        }
      }

      val path =
        if (asset.contentUri.scheme == "asset") {
          asset.contentUri.path?.removePrefix("/")
            ?: asset.contentUri.schemeSpecificPart.trimStart('/')
        } else {
          val idName = asset.id.removePrefix("sample_")
          "$ASSETS_SAMPLE_DIR/$idName"
        }

      try {
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
        Log.e(TAG, "Error loading sample bitmap $path: ${e.message}")
        null
      }
    }
}
