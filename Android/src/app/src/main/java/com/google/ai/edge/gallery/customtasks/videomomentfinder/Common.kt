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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.ai.edge.gallery.common.openSafeOutputStream
import com.google.ai.edge.gallery.proto.VideoMomentProject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "AGVideoClipExporter"

/** Formats a duration in milliseconds to MM:SS or HH:MM:SS format. */
fun formatTime(ms: Long): String {
  val totalSeconds = ms.coerceAtLeast(0L) / 1000
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%02d:%02d", minutes, seconds)
  }
}

/**
 * Trims and extracts a video clip defined by [startTimeMs] and [endTimeMs] from the project source
 * video and writes the resulting MP4 file to [outputFile].
 *
 * The cut is frame accurate. Copying samples straight across would be cheaper, but it can only
 * start on a keyframe, and a video whose keyframes are seconds apart then drags in everything back
 * to the preceding one: asking a 4K camera clip with keyframes at 0 s and 8.3 s for the three
 * seconds from 7 s exports all ten. [Transformer] instead transcodes the leading group of pictures
 * and transmuxes the remainder, so only the handful of frames that have to be re-encoded are, and
 * it falls back to a full transcode on its own when the two halves cannot be stitched together.
 */
suspend fun extractVideoClipToFile(
  context: Context,
  project: VideoMomentProject,
  startTimeMs: Long,
  endTimeMs: Long,
  outputFile: File,
): Boolean {
  if (startTimeMs < 0L || endTimeMs <= startTimeMs) {
    Log.e(TAG, "Invalid time range: startTimeMs=$startTimeMs, endTimeMs=$endTimeMs")
    return false
  }

  val sourceVideoFile = File(context.getExternalFilesDir(null), project.relativeVideoPath)
  val sourceIsReadable =
    withContext(Dispatchers.IO) {
      if (sourceVideoFile.isFile && sourceVideoFile.length() > 0L) {
        outputFile.parentFile?.mkdirs()
        true
      } else {
        false
      }
    }
  if (!sourceIsReadable) {
    Log.e(TAG, "Source video file not found or empty: ${sourceVideoFile.absolutePath}")
    return false
  }

  val clip =
    MediaItem.Builder()
      .setUri(Uri.fromFile(sourceVideoFile))
      .setClippingConfiguration(
        ClippingConfiguration.Builder()
          .setStartPositionMs(startTimeMs)
          .setEndPositionMs(endTimeMs)
          .build()
      )
      .build()

  var exported = false
  try {
    // Transformer reports its result on, and must be driven from, the looper it was built on.
    exported =
      withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
          val transformer =
            Transformer.Builder(context)
              .experimentalSetTrimOptimizationEnabled(true)
              .addListener(
                object : Transformer.Listener {
                  override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.d(
                      TAG,
                      "Exported clip $startTimeMs-$endTimeMs ms," +
                        " optimization result ${exportResult.optimizationResult}",
                    )
                    continuation.resume(true)
                  }

                  override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                  ) {
                    Log.e(TAG, "Failed to export clip $startTimeMs-$endTimeMs ms", exportException)
                    continuation.resume(false)
                  }
                }
              )
              .build()
          // Cancellation reaches us on whichever thread cancelled, so it has to hop back.
          val mainHandler = Handler(Looper.getMainLooper())
          continuation.invokeOnCancellation { mainHandler.post { transformer.cancel() } }
          transformer.start(EditedMediaItem.Builder(clip).build(), outputFile.absolutePath)
        }
      }
    return exported
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // Transformer rejects a source it cannot open from `start()` rather than through the listener,
    // so the callers' `Boolean` contract only holds if that is caught here too.
    Log.e(TAG, "Failed to start the export of clip $startTimeMs-$endTimeMs ms", e)
    return false
  } finally {
    // Transformer leaves the partial output behind on failure, and so does a cancelled export.
    if (!exported) {
      withContext(Dispatchers.IO + NonCancellable) { outputFile.delete() }
    }
  }
}

/**
 * Trims and exports a video clip defined by [startTimeMs] and [endTimeMs] to the device's external
 * MediaStore photo/video album.
 */
suspend fun exportVideoClipToMediaStore(
  context: Context,
  project: VideoMomentProject,
  startTimeMs: Long,
  endTimeMs: Long,
): Boolean =
  withContext(Dispatchers.IO) {
    if (startTimeMs < 0L || endTimeMs <= startTimeMs) {
      Log.e(TAG, "Invalid time range for export: startTimeMs=$startTimeMs, endTimeMs=$endTimeMs")
      return@withContext false
    }

    val tempOutputFile = File.createTempFile("clip_export_", ".mp4", context.cacheDir)
    var mediaStoreUri: Uri? = null
    var copySuccess = false

    try {
      val extracted =
        extractVideoClipToFile(
          context = context,
          project = project,
          startTimeMs = startTimeMs,
          endTimeMs = endTimeMs,
          outputFile = tempOutputFile,
        )
      if (!extracted) {
        return@withContext false
      }

      val resolver = context.contentResolver
      val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

      val clipName = "clip_${project.id.take(8)}_${System.currentTimeMillis()}.mp4"
      val contentValues =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, clipName)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
          put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
          put(MediaStore.Video.Media.IS_PENDING, 1)
          put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIEdgeGallery")
        }

      val uri = resolver.insert(videoCollection, contentValues) ?: return@withContext false
      mediaStoreUri = uri

      val outputStream = openSafeOutputStream(context, uri)
      copySuccess =
        outputStream?.use { outStream ->
          tempOutputFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
          true
        } ?: false

      if (copySuccess) {
        val updateValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, updateValues, null, null)
      } else {
        try {
          resolver.delete(uri, null, null)
        } catch (_: Exception) {}
        mediaStoreUri = null
      }

      copySuccess
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error exporting video clip to MediaStore", e)
      false
    } finally {
      withContext(NonCancellable) {
        if (tempOutputFile.exists()) {
          tempOutputFile.delete()
        }
        if (!copySuccess && mediaStoreUri != null) {
          try {
            context.contentResolver.delete(mediaStoreUri, null, null)
          } catch (_: Exception) {}
        }
      }
    }
  }

/** Retrieves the video duration in milliseconds from the given [uri]. */
fun getVideoDurationMs(context: Context, uri: Uri): Long {
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(context, uri)
    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    durationStr?.toLongOrNull() ?: 0L
  } catch (e: Exception) {
    Log.w(TAG, "Failed to retrieve video duration for uri: $uri", e)
    0L
  } finally {
    try {
      retriever.release()
    } catch (_: Exception) {}
  }
}
