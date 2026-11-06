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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.graphics.Bitmap

data class TimestampedFrame(val bitmap: Bitmap, val timestampMs: Long)

class FrameBuffer(private val maxFrames: Int = 32) {
  private val buffer = ArrayDeque<TimestampedFrame>()

  @Synchronized
  fun addFrame(bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()) {
    if (buffer.size >= maxFrames) {
      buffer.removeFirst().bitmap.recycle()
    }
    buffer.addLast(TimestampedFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false), timestampMs))
  }

  @Synchronized
  fun sample(n: Int): List<TimestampedFrame> {
    if (buffer.isEmpty()) return emptyList()

    val frames =
      if (buffer.size <= n) {
        buffer.toList()
      } else {
        val recentCount = (n * 0.25f).toInt().coerceAtLeast(1)
        val uniformCount = n - recentCount
        val all = buffer.toList()
        val recent = all.takeLast(recentCount)
        val older = all.dropLast(recentCount)
        val step = older.size.toFloat() / uniformCount
        val uniform =
          (0 until uniformCount).map { i ->
            older[(i * step).toInt().coerceAtMost(older.lastIndex)]
          }
        (uniform + recent).distinctBy { it.timestampMs }.sortedBy { it.timestampMs }.take(n)
      }

    // Return copies so recycling in addFrame() doesn't invalidate returned bitmaps
    return frames.map { frame ->
      TimestampedFrame(
        bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false),
        timestampMs = frame.timestampMs,
      )
    }
  }

  @Synchronized
  fun sampleLatest(): TimestampedFrame? {
    val last = buffer.lastOrNull() ?: return null
    return TimestampedFrame(
      bitmap = last.bitmap.copy(Bitmap.Config.ARGB_8888, false),
      timestampMs = last.timestampMs,
    )
  }

  @Synchronized fun frameCount(): Int = buffer.size

  @Synchronized
  fun clear() {
    buffer.forEach { it.bitmap.recycle() }
    buffer.clear()
  }
}
