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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTtsAudioValidatorTest {
  @Test
  fun rejectsInvalidSampleRateWithStructuredReason() {
    val exception =
      assertThrows(TranslationTtsSynthesisException::class.java) {
        TranslationTtsAudioValidator.validate(
          SynthesizedAudio(samples = FloatArray(7000) { 0.1f }, sampleRate = 0)
        )
      }

    assertEquals(TranslationTtsAudioFailure.INVALID_SAMPLE_RATE, exception.audioFailure)
  }

  @Test
  fun rejectsOneSampleOutput() {
    val exception =
      assertThrows(TranslationTtsSynthesisException::class.java) {
        TranslationTtsAudioValidator.validate(
          SynthesizedAudio(samples = floatArrayOf(0.1f), sampleRate = 24000)
        )
      }

    assertEquals(TranslationTtsAudioFailure.SHORT_AUDIO, exception.audioFailure)
  }

  @Test
  fun rejectsAudioAtQuarterSecondBoundary() {
    val exception =
      assertThrows(TranslationTtsSynthesisException::class.java) {
        TranslationTtsAudioValidator.validate(
          SynthesizedAudio(samples = FloatArray(6000) { 0.1f }, sampleRate = 24000)
        )
      }

    assertEquals(TranslationTtsAudioFailure.SHORT_AUDIO, exception.audioFailure)
    assertEquals(0.25, exception.metrics?.durationSeconds ?: 0.0, 0.0001)
  }

  @Test
  fun rejectsNonFiniteOutput() {
    val samples = FloatArray(7000) { 0.1f }.apply { this[3500] = Float.NaN }

    val exception =
      assertThrows(TranslationTtsSynthesisException::class.java) {
        TranslationTtsAudioValidator.validate(
          SynthesizedAudio(samples = samples, sampleRate = 24000)
        )
      }

    assertEquals(TranslationTtsAudioFailure.NON_FINITE_AUDIO, exception.audioFailure)
  }

  @Test
  fun rejectsNearSilentOutput() {
    val exception =
      assertThrows(TranslationTtsSynthesisException::class.java) {
        TranslationTtsAudioValidator.validate(
          SynthesizedAudio(samples = FloatArray(7000), sampleRate = 24000)
        )
      }

    assertEquals(TranslationTtsAudioFailure.NEAR_SILENT_AUDIO, exception.audioFailure)
    assertEquals(0.0, exception.metrics?.rms ?: -1.0, 0.0)
  }

  @Test
  fun acceptsAudiblePcm() {
    val metrics =
      TranslationTtsAudioValidator.validate(
        SynthesizedAudio(samples = FloatArray(24000) { 0.05f }, sampleRate = 24000)
      )

    assertEquals(1.0, metrics.durationSeconds, 0.0001)
    assertTrue(metrics.rms >= TranslationTtsAudioValidator.MIN_RMS)
    assertTrue(metrics.peak >= TranslationTtsAudioValidator.MIN_PEAK)
  }
}
