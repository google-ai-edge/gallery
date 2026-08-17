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
  fun rejectsInvalidPcm() {
    val invalidAudio =
      mapOf(
        TranslationTtsAudioFailure.INVALID_SAMPLE_RATE to audibleAudio(sampleRate = 0),
        TranslationTtsAudioFailure.SHORT_AUDIO to
          audibleAudio(sampleCount = TranslationTtsAudioValidator.MIN_SAMPLE_COUNT),
        TranslationTtsAudioFailure.NON_FINITE_AUDIO to
          audibleAudio().copy(samples = audibleAudio().samples.apply { this[0] = Float.NaN }),
        TranslationTtsAudioFailure.NEAR_SILENT_AUDIO to
          audibleAudio().copy(samples = FloatArray(VALID_SAMPLE_COUNT)),
      )

    invalidAudio.forEach { (expectedFailure, audio) ->
      val exception =
        assertThrows(TranslationTtsSynthesisException::class.java) {
          TranslationTtsAudioValidator.validate(audio)
        }
      assertEquals(expectedFailure, exception.audioFailure)
    }
  }

  @Test
  fun acceptsAudiblePcm() {
    val metrics = TranslationTtsAudioValidator.validate(audibleAudio())

    assertEquals(1.0, metrics.durationSeconds, 0.0001)
    assertTrue(metrics.rms >= TranslationTtsAudioValidator.MIN_RMS)
    assertTrue(metrics.peak >= TranslationTtsAudioValidator.MIN_PEAK)
  }

  private fun audibleAudio(
    sampleCount: Int = VALID_SAMPLE_COUNT,
    sampleRate: Int = VALID_SAMPLE_RATE,
  ) = SynthesizedAudio(samples = FloatArray(sampleCount) { 0.05f }, sampleRate = sampleRate)

  companion object {
    private const val VALID_SAMPLE_RATE = 24_000
    private const val VALID_SAMPLE_COUNT = 24_000
  }
}
