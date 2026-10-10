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
  const val SCENE_SIDE = 768
  /** At most this many scenes per video; longer videos sample less densely. */
  const val MAX_SCENES = 200
  private const val SCENE_STEP_SEC = 2.5
  /** dHash distance below which two frames count as the same shot. */
  private const val SAME_SHOT_BITS = 6
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
  /**
   * Scenes along a video: one frame every 2.5 s (sparser for long videos, at most [MAX_SCENES]),
   * skipping frames that look like the previous kept one, so a static shot is sent once.
   * Returns (seconds, base64 JPEG).
   */
  fun sceneFrames(resolver: ContentResolver, item: MediaItem): List<Pair<Double, String>> {
    val durSec = item.durationMs / 1000.0
    if (durSec <= 0) return emptyList()
    val step = maxOf(SCENE_STEP_SEC, durSec / MAX_SCENES)
    val retriever = MediaMetadataRetriever()
    return try {
      resolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
        retriever.setDataSource(pfd.fileDescriptor)
        val out = ArrayList<Pair<Double, String>>()
        var lastHash: Long? = null
        var t = 0.0
        while (t < durSec) {
          val frame =
            retriever.getScaledFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SCENE_SIDE, SCENE_SIDE)
          if (frame != null) {
            try {
              val hash = dHash(frame)
              if (lastHash == null || java.lang.Long.bitCount(hash xor lastHash) > SAME_SHOT_BITS) {
                out += t to encode(frame)
                lastHash = hash
              }
            } finally {
              frame.recycle()
            }
          }
          t += step
        }
        out
      } ?: emptyList()
    } finally {
      retriever.release()
    }
  }

  /** 64-bit difference hash: brightness gradients of a 9x8 thumbnail. */
  fun dHash(bitmap: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    try {
      var hash = 0L
      var bit = 0
      for (y in 0 until 8) {
        for (x in 0 until 8) {
          if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
          bit++
        }
      }
      return hash
    } finally {
      if (small !== bitmap) small.recycle()
    }
  }

  private fun luma(c: Int): Int = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000

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
