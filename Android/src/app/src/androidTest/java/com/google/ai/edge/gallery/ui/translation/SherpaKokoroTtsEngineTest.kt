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
  fun arm64SynthesizesValidatedPcmForAllTranslationLanguages() {
    assertTrue(
      "This regression test must run on an arm64 emulator/device; ABIs=" +
        Build.SUPPORTED_ABIS.joinToString(),
      Build.SUPPORTED_ABIS.contains("arm64-v8a"),
    )
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine: TranslationTtsEngine = SherpaKokoroTtsEngine(context)

    try {
      runBlocking {
        engine.preload()
        val phrases =
          mapOf(
            "en-us" to "Hello, this is a test.",
            "es" to "Hola, esto es una prueba.",
            "fr-fr" to "Bonjour, ceci est un test.",
            "it" to "Ciao, questo è un test.",
          )

        phrases.forEach { (languageTag, phrase) ->
          val audio = engine.synthesize(text = phrase, languageTag = languageTag)
          assertEquals(24000, audio.sampleRate)
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
        runBlocking { engine.synthesize(text = "   ", languageTag = "fr-fr") }
      }
    } finally {
      engine.release()
    }
  }

  @Test
  fun arm64StreamsNativePcmBeforeReturningValidatedAudio() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine: TranslationTtsEngine = SherpaKokoroTtsEngine(context)

    try {
      var callbackCount = 0
      var callbackSampleCount = 0L
      val audio =
        runBlocking {
          engine.preload()
          engine.synthesizeStreaming(
            text = "Hola, esta es una prueba de audio transmitido.",
            languageTag = "es",
            onPcmChunk = { chunk ->
              callbackCount++
              callbackSampleCount += chunk.samples.size
              assertEquals(24000, chunk.sampleRate)
              assertTrue(chunk.samples.isNotEmpty())
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
}
