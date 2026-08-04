/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

internal data class SynthesizedAudio(
  val samples: FloatArray,
  val sampleRate: Int,
)

internal interface TranslationTtsEngine {
  suspend fun preload()

  suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio

  suspend fun synthesizeStreaming(
    text: String,
    languageTag: String,
    onPcmChunk: (SynthesizedAudio) -> Boolean,
  ): SynthesizedAudio {
    val audio = synthesize(text = text, languageTag = languageTag)
    if (!onPcmChunk(audio)) {
      throw CancellationException("Translation PCM consumer stopped.")
    }
    return audio
  }

  fun release()
}

internal enum class TranslationTtsAudioFailure {
  INVALID_SAMPLE_RATE,
  UNEXPECTED_SAMPLE_RATE,
  SHORT_AUDIO,
  NON_FINITE_AUDIO,
  NEAR_SILENT_AUDIO,
}

internal class TranslationTtsSynthesisException(
  message: String,
  val audioFailure: TranslationTtsAudioFailure? = null,
  val metrics: TranslationTtsAudioMetrics? = null,
) : RuntimeException(message)

internal data class TranslationTtsAudioMetrics(
  val durationSeconds: Double,
  val rms: Double,
  val peak: Double,
)

internal object TranslationTtsAudioValidator {
  const val MIN_SAMPLE_COUNT = 6000
  const val MIN_DURATION_SECONDS = 0.25
  const val MIN_RMS = 0.0001
  const val MIN_PEAK = 0.001

  fun validate(audio: SynthesizedAudio): TranslationTtsAudioMetrics {
    if (audio.sampleRate <= 0) {
      throw TranslationTtsSynthesisException(
        "TTS returned an invalid sample rate: ${audio.sampleRate}.",
        audioFailure = TranslationTtsAudioFailure.INVALID_SAMPLE_RATE,
      )
    }

    var sumOfSquares = 0.0
    var peak = 0.0
    audio.samples.forEach { sample ->
      if (!sample.isFinite()) {
        throw TranslationTtsSynthesisException(
          "TTS returned non-finite PCM samples.",
          audioFailure = TranslationTtsAudioFailure.NON_FINITE_AUDIO,
        )
      }
      val value = sample.toDouble()
      sumOfSquares += value * value
      peak = maxOf(peak, abs(value))
    }
    val rms = sqrt(sumOfSquares / audio.samples.size)
    val metrics =
      TranslationTtsAudioMetrics(
        durationSeconds = audio.samples.size.toDouble() / audio.sampleRate,
        rms = rms,
        peak = peak,
      )
    if (
      audio.samples.size <= MIN_SAMPLE_COUNT ||
        metrics.durationSeconds <= MIN_DURATION_SECONDS
    ) {
      throw TranslationTtsSynthesisException(
        "TTS output is ${metrics.durationSeconds}s; expected more than " +
          "$MIN_DURATION_SECONDS seconds.",
        audioFailure = TranslationTtsAudioFailure.SHORT_AUDIO,
        metrics = metrics,
      )
    }
    if (rms < MIN_RMS || peak < MIN_PEAK) {
      throw TranslationTtsSynthesisException(
        "TTS output is near-silent: rms=$rms, peak=$peak.",
        audioFailure = TranslationTtsAudioFailure.NEAR_SILENT_AUDIO,
        metrics = metrics,
      )
    }

    return metrics
  }
}
