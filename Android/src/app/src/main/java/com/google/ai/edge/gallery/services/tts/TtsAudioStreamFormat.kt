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

package com.google.ai.edge.gallery.services.tts

import android.media.AudioTrack
import android.os.Build

/** Audio format metadata for raw linear PCM data chunks emitted by [TtsOutput.AudioStream]. */
data class TtsAudioStreamFormat(
  val sampleRate: Double = 24000.0,
  val channelCount: Int = 1,
  val bitDepth: Int = 16,
) {
  companion object {
    /** Standard 24,000 Hz, 1-channel mono, 16-bit signed integer linear PCM. */
    val STANDARD_24KHZ_MONO = TtsAudioStreamFormat(24000.0, 1, 16)
  }
}

/**
 * Configures this [AudioTrack] to start playback as soon as the first frame is written.
 *
 * On Android 12+ (API 31+), streaming [AudioTrack] instances default `startThresholdInFrames` to
 * the full buffer capacity. For short utterances or after buffer underruns, the track stalls in
 * AudioFlinger `FS_FILLING` state until the buffer completely fills. Setting the threshold to 1
 * frame allows playback to begin immediately on the first written audio chunk.
 */
fun AudioTrack.startOnFirstFrame(): AudioTrack {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && state == AudioTrack.STATE_INITIALIZED) {
    setStartThresholdInFrames(1)
  }
  return this
}
