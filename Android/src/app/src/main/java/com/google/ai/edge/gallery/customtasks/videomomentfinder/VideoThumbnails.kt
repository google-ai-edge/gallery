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

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log

private const val TAG = "AGVideoThumbnails"

/**
 * Timestamps, relative to the start of the video, probed when looking for a cover frame.
 *
 * The first entry must stay at 0: videos that open on a usable frame — the overwhelming majority —
 * then match on the very first probe, and the whole search costs one small decode.
 *
 * The spacing widens because the two things that produce a black opening frame have very different
 * durations: a camera exposure ramp lasts a few frames, while an edited fade-in from black runs one
 * to two seconds. The last probe is deliberately past any reasonable fade.
 */
private val PROBE_OFFSETS_MS = longArrayOf(0L, 200L, 500L, 1_000L, 2_000L, 4_000L)

/**
 * Edge length of the downscaled bitmap used for the brightness test.
 *
 * The probe only has to answer "is there anything visible here", so it decodes into a thumbnail far
 * smaller than the frame that is eventually saved. Reading pixels out of a full 1080p bitmap costs
 * roughly five hundred times as many samples for an answer that does not change.
 */
private const val PROBE_DIMENSION = 64

/**
 * A frame is treated as black when its average luma is below this and nothing in it is bright
 * enough to carry a thumbnail on its own.
 *
 * Kept low on purpose. The goal is to reject black and near-black frames, not to reject dark
 * footage: a genuine night scene should still be allowed to represent its own video.
 */
private const val MIN_MEAN_LUMA = 12

/**
 * A frame survives the black test if any part of it is at least this bright, even when its average
 * is very low.
 *
 * This is what saves the candle-in-a-dark-room shot, which is mostly black by area but is a
 * perfectly recognizable thumbnail.
 */
private const val MIN_PEAK_LUMA = 64

/** How bright one probed frame turned out to be, on the 0..255 luma scale. */
internal data class FrameBrightness(val meanLuma: Int, val peakLuma: Int) {
  /** True when the frame is black, or so close to black that it would read as black in a grid. */
  val isEffectivelyBlack: Boolean
    get() = meanLuma < MIN_MEAN_LUMA && peakLuma < MIN_PEAK_LUMA
}

/**
 * Measures the brightness of already-decoded ARGB [pixels].
 *
 * Luma uses the integer Rec.601 weights rather than [android.graphics.Color.luminance] because the
 * decision here is a coarse threshold, and the gamma-correct version would pay a float conversion
 * per channel per pixel to move the answer by less than the threshold's own margin.
 */
internal fun measureBrightness(pixels: IntArray): FrameBrightness {
  if (pixels.isEmpty()) return FrameBrightness(meanLuma = 0, peakLuma = 0)
  var total = 0L
  var peak = 0
  for (pixel in pixels) {
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    val luma = (r * 77 + g * 151 + b * 28) shr 8
    total += luma
    if (luma > peak) peak = luma
  }
  return FrameBrightness(meanLuma = (total / pixels.size).toInt(), peakLuma = peak)
}

/** Reads [bitmap] into an int array and measures it. */
private fun measureBrightness(bitmap: Bitmap): FrameBrightness {
  val pixels = IntArray(bitmap.width * bitmap.height)
  bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
  return measureBrightness(pixels)
}

/**
 * Picks which offsets in [PROBE_OFFSETS_MS] are worth probing for a video of [durationMs].
 *
 * Offsets at or past the end are dropped, and 0 is always probed so that a video shorter than the
 * first spacing still gets looked at.
 */
internal fun probeOffsetsForDuration(durationMs: Long): List<Long> = PROBE_OFFSETS_MS.filter {
  it == 0L || it < durationMs
}

/**
 * Returns the presentation time, in microseconds, of the first frame near the start of the video
 * that is not black, for use as the project's cover thumbnail.
 *
 * Returns 0 when every probe is black or when no frame can be decoded at all, so the caller always
 * has a timestamp to fall back on and this can never make the thumbnail worse than the frame at 0.
 *
 * [retriever] must already have its data source set. Probes use `OPTION_CLOSEST` rather than
 * `OPTION_CLOSEST_SYNC`: the frames being skipped over are usually inside the opening group of
 * pictures, and snapping to the nearest keyframe would hand back the same black frame every time.
 *
 * The returned timestamp is only meaningful when decoded with `OPTION_CLOSEST` for the same reason.
 */
internal fun findFirstNonBlackFrameTimeUs(
  retriever: MediaMetadataRetriever,
  durationMs: Long,
): Long {
  var brightestTimeUs = 0L
  var brightestMeanLuma = -1

  for (offsetMs in probeOffsetsForDuration(durationMs)) {
    val timeUs = offsetMs * 1_000L
    val bitmap =
      try {
        retriever.getScaledFrameAtTime(
          timeUs,
          MediaMetadataRetriever.OPTION_CLOSEST,
          PROBE_DIMENSION,
          PROBE_DIMENSION,
        )
      } catch (e: Exception) {
        Log.w(TAG, "Failed to probe frame at ${offsetMs}ms", e)
        null
      } ?: continue

    val brightness =
      try {
        measureBrightness(bitmap)
      } finally {
        bitmap.recycle()
      }

    if (!brightness.isEffectivelyBlack) {
      if (offsetMs > 0L) {
        Log.i(TAG, "Opening frame was black; using the frame at ${offsetMs}ms instead")
      }
      return timeUs
    }
    if (brightness.meanLuma > brightestMeanLuma) {
      brightestMeanLuma = brightness.meanLuma
      brightestTimeUs = timeUs
    }
  }

  // Every probe was black. Fall back to the least black of them rather than giving up, which for a
  // video that really is black throughout is the same frame the caller would have picked anyway.
  Log.i(TAG, "No non-black frame found in the opening seconds; using ${brightestTimeUs / 1000}ms")
  return brightestTimeUs
}
