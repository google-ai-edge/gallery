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

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting

private const val TAG = "AGVideoDecoderSupport"

/** The properties of a video track that decide whether this device has a decoder for it. */
data class VideoTrackFormat(val mimeType: String, val width: Int, val height: Int)

/** Whether this device can decode a video, as determined by [checkVideoDecoderSupport]. */
sealed interface VideoDecoderSupport {
  /** At least one decoder on this device accepts the video's codec at its resolution. */
  data object Supported : VideoDecoderSupport

  /**
   * No decoder on this device accepts the video's codec at its resolution, typically an 8K video on
   * hardware whose decoders top out at 4K.
   */
  data class Unsupported(val format: VideoTrackFormat) : VideoDecoderSupport

  /**
   * The video could not be inspected, for example because the file is corrupt or has no video
   * track. Callers should let the import proceed: the later stages already report an unreadable
   * video, and blocking here would turn an inspection hiccup into a false rejection.
   */
  data object Unknown : VideoDecoderSupport
}

/**
 * Checks whether this device has a decoder for the video at [uri].
 *
 * Only the codec and resolution are checked, not the frame rate: indexing and thumbnails pull
 * individual frames through `MediaMetadataRetriever` rather than decoding in real time, so a
 * decoder that cannot sustain the video's frame rate can still process it.
 *
 * Performs file I/O, so it must not be called on the main thread.
 */
internal fun checkVideoDecoderSupport(context: Context, uri: Uri): VideoDecoderSupport {
  val format = readVideoTrackFormat(context, uri) ?: return VideoDecoderSupport.Unknown
  return checkVideoDecoderSupport(format)
}

/**
 * Checks [format] against the decoders available through [findDecoder], which returns the name of a
 * decoder that accepts the given format, or null if there is none.
 */
@VisibleForTesting
internal fun checkVideoDecoderSupport(
  format: VideoTrackFormat,
  findDecoder: (MediaFormat) -> String? = { mediaFormat ->
    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mediaFormat)
  },
): VideoDecoderSupport {
  val mediaFormat = MediaFormat.createVideoFormat(format.mimeType, format.width, format.height)
  val decoderName =
    try {
      findDecoder(mediaFormat)
    } catch (e: RuntimeException) {
      // Thrown for a malformed format (IllegalArgumentException) or when the platform media service
      // is unavailable (e.g. IllegalStateException). Neither says anything about this device's
      // decoders, so it must not be reported as unsupported.
      Log.w(TAG, "Could not look up a decoder for $format", e)
      return VideoDecoderSupport.Unknown
    }
  return if (decoderName != null) {
    Log.d(TAG, "Decoder $decoderName supports $format")
    VideoDecoderSupport.Supported
  } else {
    Log.w(TAG, "No decoder on this device supports $format")
    VideoDecoderSupport.Unsupported(format)
  }
}

/** Reads the codec and resolution of the first video track at [uri], or null if there is none. */
private fun readVideoTrackFormat(context: Context, uri: Uri): VideoTrackFormat? {
  val extractor = MediaExtractor()
  return try {
    extractor.setDataSource(context, uri, null)
    (0 until extractor.trackCount)
      .asSequence()
      .map { extractor.getTrackFormat(it) }
      .firstNotNullOfOrNull { trackFormat ->
        val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
        if (
          mimeType == null ||
            !mimeType.startsWith("video/") ||
            !trackFormat.containsKey(MediaFormat.KEY_WIDTH) ||
            !trackFormat.containsKey(MediaFormat.KEY_HEIGHT)
        ) {
          null
        } else {
          VideoTrackFormat(
            mimeType = mimeType,
            width = trackFormat.getInteger(MediaFormat.KEY_WIDTH),
            height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT),
          )
        }
      }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to read the video track format of $uri", e)
    null
  } finally {
    extractor.release()
  }
}
