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

import android.util.Log
import com.google.ai.edge.gallery.services.semanticretrieval.AUDIO_SAMPLE_RATE_HZ
import com.google.ai.edge.gallery.services.semanticretrieval.EmbedPart
import kotlin.math.ceil

private const val TAG = "AGAudioInterleaving"

/**
 * Audio tokens the streaming audio encoder emits per second of 16 kHz mono audio.
 *
 * Derived rather than measured: the mel frontend produces 100 frames per second (16 kHz with a 160
 * sample hop) and the encoder shrinks them by a factor of 16. Only used to keep a window inside the
 * model's context, so a modest error is harmless.
 */
internal const val AUDIO_TOKENS_PER_SECOND = 6.25f

/**
 * Tokens budgeted for one timestamp string plus the modality markers wrapping the part it labels.
 *
 * A deliberate overestimate of `"00:03"` plus `<SOI>`/`<EOI>` (or `<SOA>`/`<EOA>`), so the budget
 * check below errs towards dropping audio rather than overflowing the context.
 */
internal const val TIMESTAMP_AND_MARKER_TOKENS = 8

/** Tokens spent on the sequence's own start and end markers. */
internal const val SEQUENCE_MARKER_TOKENS = 2

/**
 * Builds the ordered embedding parts for one time window.
 *
 * With audio, parts follow the model's TAV layout, pairing each frame with the audio recorded
 * around it:
 * ```
 * ["00:00"] <SOA> audio_1 <EOA> ["00:00"] <SOI> frame_1 <EOI> ["00:02"] <SOA> audio_2 <EOA> ...
 * ```
 *
 * The markers are inserted by the embedding engine, so only the timestamps, audio, and frames are
 * listed here. Windows without any audio fall back to frames alone and omit the timestamps: the
 * frame-only task was trained without time markers, so adding them there would be an unvalidated
 * change of input distribution.
 *
 * [audioSlices] is positional: entry `i` is the audio for frame `i`, and may be null or empty when
 * that moment has no decodable audio. It may also be empty to request a frame-only window.
 */
internal fun buildWindowEmbedParts(
  frames: List<ByteArray>,
  frameTimestampsMs: List<Long>,
  audioSlices: List<ShortArray?> = emptyList(),
): List<EmbedPart> {
  require(frames.size == frameTimestampsMs.size) {
    "frames (${frames.size}) and frameTimestampsMs (${frameTimestampsMs.size}) must be aligned"
  }
  require(audioSlices.isEmpty() || audioSlices.size == frames.size) {
    "audioSlices (${audioSlices.size}) must be empty or aligned with frames (${frames.size})"
  }

  val hasAudio = audioSlices.any { it != null && it.isNotEmpty() }
  if (!hasAudio) {
    return frames.map { EmbedPart.Image(it) }
  }

  Log.d(TAG, "Building window with ${frames.size} frames and ${audioSlices.size} audio slices")
  return buildList {
    for (index in frames.indices) {
      val timestamp = formatTime(frameTimestampsMs[index])
      val audio = audioSlices[index]
      if (audio != null && audio.isNotEmpty()) {
        add(EmbedPart.Text(timestamp))
        add(EmbedPart.Audio(audio))
      }
      add(EmbedPart.Text(timestamp))
      add(EmbedPart.Image(frames[index]))
    }
  }
}

/**
 * Estimates the tokens one window costs, to check it against the model's max input length.
 *
 * Deliberately an upper bound: overshooting only turns audio off for an extreme configuration,
 * while undershooting overflows the context and fails the whole indexing pass.
 */
internal fun estimateWindowTokenCount(
  frameCount: Int,
  visionTokensPerFrame: Int,
  audioSliceCount: Int,
  audioSliceDurationSec: Float,
): Int {
  val visionTokens = frameCount * visionTokensPerFrame
  val audioTokens =
    ceil(audioSliceCount * audioSliceDurationSec.coerceAtLeast(0f) * AUDIO_TOKENS_PER_SECOND)
      .toInt()
  val markerTokens = (frameCount + audioSliceCount) * TIMESTAMP_AND_MARKER_TOKENS
  return visionTokens + audioTokens + markerTokens + SEQUENCE_MARKER_TOKENS
}

/**
 * Splits `[startTimeMs], [endTimeMs])` into [sliceCount] contiguous, equal ranges.
 *
 * Each range is the audio paired with the frame at the same index, so together they cover the whole
 * window with no gaps or overlap.
 */
internal fun audioSliceBoundaries(
  startTimeMs: Long,
  endTimeMs: Long,
  sliceCount: Int,
): List<Pair<Long, Long>> {
  if (sliceCount <= 0 || endTimeMs <= startTimeMs) return emptyList()
  val durationMs = endTimeMs - startTimeMs
  return (0 until sliceCount).map { index ->
    val sliceStartMs = startTimeMs + (index * durationMs) / sliceCount
    val sliceEndMs = startTimeMs + ((index + 1) * durationMs) / sliceCount
    sliceStartMs to sliceEndMs
  }
}

/**
 * Splits [windowPcm], the 16 kHz mono audio of `[startTimeMs], [endTimeMs])`, into one slice per
 * frame.
 *
 * The whole window is decoded in one read and divided here rather than read a slice at a time,
 * because [VideoAudioExtractor] decodes in a single forward pass and releases the samples behind
 * each request: asking for one slice at a time steps backwards at the start of every overlapping
 * window and silently drops the audio for its leading frames.
 *
 * Offsets come from each slice's own timestamps rather than from a proportional split of the
 * buffer, so a window whose audio is cut short by the end of the track keeps the surviving slices
 * aligned with their frames and reports the missing tail as null, instead of stretching what is
 * left across every frame.
 */
internal fun splitWindowPcm(
  windowPcm: ShortArray,
  startTimeMs: Long,
  endTimeMs: Long,
  sliceCount: Int,
): List<ShortArray?> {
  if (windowPcm.isEmpty()) return emptyList()
  val boundaries = audioSliceBoundaries(startTimeMs, endTimeMs, sliceCount)
  return boundaries.map { (sliceStartMs, sliceEndMs) ->
    val from = sampleOffsetAt(sliceStartMs - startTimeMs).coerceIn(0, windowPcm.size)
    val to = sampleOffsetAt(sliceEndMs - startTimeMs).coerceIn(from, windowPcm.size)
    if (to > from) windowPcm.copyOfRange(from, to) else null
  }
}

/** Offset, in 16 kHz mono samples, of the sample [offsetMs] into a window. */
private fun sampleOffsetAt(offsetMs: Long): Int =
  (offsetMs.coerceAtLeast(0L) * AUDIO_SAMPLE_RATE_HZ / 1000L).toInt()
