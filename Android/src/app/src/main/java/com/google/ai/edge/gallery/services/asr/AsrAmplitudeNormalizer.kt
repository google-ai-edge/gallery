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

package com.google.ai.edge.gallery.services.asr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Calibrates raw audio level metrics across all ASR engines onto a unified `[0f, 1f]` perceptual
 * scale so waveform animations respond consistently regardless of the active speech engine.
 */
object AsrAmplitudeNormalizer {
  /** Maximum 16-bit signed PCM amplitude scale expected by UI waveform indicators. */
  const val MAX_AMPLITUDE = 65535

  private const val MLKIT_NOISE_BASELINE = 15f
  private const val MLKIT_ACTIVE_RANGE = 75f

  private const val SYSTEM_SPEECH_MIN_RMS_DB = -2f
  private const val SYSTEM_SPEECH_DYNAMIC_RANGE_DB = 12f

  /**
   * Level window, in dBFS, for raw microphone PCM captured by [PcmAudioRecorder]. The
   * `VOICE_RECOGNITION` source applies no automatic gain, so a quiet room sits below the floor
   * (about -60 dBFS) and conversational speech near -30 dBFS.
   */
  private const val PCM_MIN_DBFS = -55f
  private const val PCM_MAX_DBFS = -25f
  private const val PCM_RMS_WEIGHT = 0.7f
  private const val PCM_PEAK_WEIGHT = 0.3f
  private const val PCM_SILENCE_LEVEL = 1e-4f

  /** Normalizes ML Kit GenAI Speech's `0..100` integer audio level onto `[0f, 1f]`. */
  fun normalizeMlKitLevel(audioLevel: Int): Float =
    ((audioLevel - MLKIT_NOISE_BASELINE) / MLKIT_ACTIVE_RANGE).coerceIn(0f, 1f)

  /** Normalizes Android `SpeechRecognizer.onRmsChanged(rmsdB)` (`~[-2f, 10f]`) onto `[0f, 1f]`. */
  fun normalizeSystemSpeechRmsDb(rmsdB: Float): Float =
    ((rmsdB - SYSTEM_SPEECH_MIN_RMS_DB) / SYSTEM_SPEECH_DYNAMIC_RANGE_DB).coerceIn(0f, 1f)

  /**
   * Normalizes a chunk of raw microphone float PCM samples in `[-1f, 1f]` (on-device speech
   * processor engines) onto `[0f, 1f]`, from a mix of RMS and peak level in dBFS.
   */
  fun normalizePcmSamples(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sumSquares = 0.0
    var peak = 0f
    for (sample in samples) {
      val magnitude = abs(sample)
      if (magnitude > peak) peak = magnitude
      sumSquares += (sample * sample).toDouble()
    }
    val rms = sqrt(sumSquares / samples.size).toFloat()
    val level = (rms * PCM_RMS_WEIGHT + peak * PCM_PEAK_WEIGHT).coerceAtLeast(PCM_SILENCE_LEVEL)
    val dbfs = 20f * log10(level)
    return ((dbfs - PCM_MIN_DBFS) / (PCM_MAX_DBFS - PCM_MIN_DBFS)).coerceIn(0f, 1f)
  }

  /**
   * Scales a normalized `[0f, 1f]` amplitude into the `0..65535` integer range used by UI waves.
   */
  fun toPcmAmplitude(normalizedVolume: Float): Int =
    (normalizedVolume.coerceIn(0f, 1f) * MAX_AMPLITUDE).toInt()
}
