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

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.google.ai.edge.gallery.mediagallery.MediaItem
import java.io.ByteArrayOutputStream

/** Frames as the Mac expects them: photos 1536 px long side, video frames 512 px, JPEG. */
object FrameEncoder {
  const val IMAGE_SIDE = 1536
  const val VIDEO_SIDE = 512
  private const val MAX_FRAME_BYTES = 2_000_000

  fun framesFor(resolver: ContentResolver, item: MediaItem): List<String> =
    if (item.isVideo) videoFrames(resolver, item) else listOfNotNull(imageFrame(resolver, item))

  private fun imageFrame(resolver: ContentResolver, item: MediaItem): String? {
    val bitmap =
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1f, IMAGE_SIDE.toFloat() / maxOf(w, h))
        decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        // software bitmap: hardware ones cannot be compressed
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    return try { encode(bitmap) } finally { bitmap.recycle() }
  }

  /** Like the original app: first frame and duration - 0.5 s, together one vector. */
  private fun videoFrames(resolver: ContentResolver, item: MediaItem): List<String> {
    val retriever = MediaMetadataRetriever()
    return try {
      resolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
        retriever.setDataSource(pfd.fileDescriptor)
        val durUs = item.durationMs * 1000
        val times = listOf(0L, maxOf(0L, durUs - 500_000)).distinct()
        times.mapNotNull { t ->
          val frame =
            retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, VIDEO_SIDE, VIDEO_SIDE)
              ?: return@mapNotNull null
          try { encode(frame) } finally { frame.recycle() }
        }
      } ?: emptyList()
    } finally {
      retriever.release()
    }
  }

  /** The server takes at most 2 MiB per decoded frame: lower the quality, then the size. */
  private fun encode(bitmap: Bitmap): String {
    var current = bitmap
    try {
      while (true) {
        for (quality in intArrayOf(80, 70, 60)) {
          val out = ByteArrayOutputStream()
          current.compress(Bitmap.CompressFormat.JPEG, quality, out)
          if (out.size() <= MAX_FRAME_BYTES) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val scaled = Bitmap.createScaledBitmap(current, maxOf(1, current.width * 3 / 4), maxOf(1, current.height * 3 / 4), true)
        if (current !== bitmap) current.recycle()
        current = scaled
      }
    } finally {
      if (current !== bitmap) current.recycle()
    }
  }
}
