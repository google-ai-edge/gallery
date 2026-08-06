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

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaKokoroTtsEngineTest {
  @Test
  fun arm64SynthesizesValidatedPcmForEveryConfiguredVoice() {
    assertTrue(
      "Kokoro device tests require arm64; ABIs=" +
        Build.SUPPORTED_ABIS.joinToString(),
      Build.SUPPORTED_ABIS.contains("arm64-v8a"),
    )
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine: TranslationTtsEngine = SherpaKokoroTtsEngine(context)

    try {
      runBlocking {
        engine.preload()
        KOKORO_SHERPA_VOICE_CONFIGS.forEach { voice ->
          val audio =
            engine.synthesize(
              text = TEST_TEXT_BY_LANGUAGE.getValue(voice.languageTag),
              languageTag = voice.languageTag,
            )
          assertEquals(KOKORO_SHERPA_SAMPLE_RATE, audio.sampleRate)
          assertTrue(audio.samples.size > TranslationTtsAudioValidator.MIN_SAMPLE_COUNT)
          assertTrue(audio.samples.all(Float::isFinite))
          val metrics = TranslationTtsAudioValidator.validate(audio)
          assertTrue(metrics.durationSeconds > TranslationTtsAudioValidator.MIN_DURATION_SECONDS)
          assertTrue(metrics.rms >= TranslationTtsAudioValidator.MIN_RMS)
        }
      }
    } finally {
      engine.release()
    }
  }

  @Test
  fun rejectsBlankTextBeforeSynthesis() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine: TranslationTtsEngine = SherpaKokoroTtsEngine(context)

    try {
      assertThrows(TranslationTtsSynthesisException::class.java) {
        runBlocking {
          engine.synthesize(
            text = "   ",
            languageTag = KOKORO_SHERPA_VOICE_CONFIGS.first().languageTag,
          )
        }
      }
    } finally {
      engine.release()
    }
  }

  @Test
  fun arm64StreamsValidatedPcm() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine: TranslationTtsEngine = SherpaKokoroTtsEngine(context)

    try {
      var callbackCount = 0
      var callbackSampleCount = 0L
      val audio =
        runBlocking {
          engine.preload()
          engine.synthesizeStreaming(
            text = TEST_TEXT_BY_LANGUAGE.getValue(KOKORO_SHERPA_VOICE_CONFIGS.first().languageTag),
            languageTag = KOKORO_SHERPA_VOICE_CONFIGS.first().languageTag,
            onPcmChunk = { chunk ->
              callbackCount++
              callbackSampleCount += chunk.samples.size
              assertEquals(KOKORO_SHERPA_SAMPLE_RATE, chunk.sampleRate)
              assertTrue(chunk.samples.isNotEmpty())
              assertTrue(chunk.samples.all(Float::isFinite))
              true
            },
          )
        }

      assertTrue(callbackCount > 0)
      assertEquals(audio.samples.size.toLong(), callbackSampleCount)
      TranslationTtsAudioValidator.validate(audio)
    } finally {
      engine.release()
    }
  }

  companion object {
    private val TEST_TEXT_BY_LANGUAGE =
      mapOf(
        "en-us" to "This is a speech synthesis test.",
        "es" to "Esta es una prueba de síntesis de voz.",
        "fr-fr" to "Ceci est un test de synthèse vocale.",
        "hi" to "यह वाक् संश्लेषण का परीक्षण है।",
        "it" to "Questo è un test di sintesi vocale.",
        "pt-br" to "Este é um teste de síntese de voz.",
      )
  }
}
