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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationTtsPlayerTest {
  @Test
  fun playsEveryConfiguredLanguage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine = RecordingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = engine,
        systemFallback = systemFallback,
      )

    try {
      runBlocking {
        player.preload()
        TranslationLanguage.entries.forEach { language ->
          player.speak(text = TEST_TEXT, languageTag = language.ttsLanguageTag)
        }
      }
      assertEquals(
        TranslationLanguage.entries.map { TEST_TEXT to it.ttsLanguageTag },
        engine.synthesisRequests,
      )
      assertTrue(systemFallback.spokenLanguageTags.isEmpty())
      assertFalse(player.isSpeaking.value)
    } finally {
      player.release()
    }
  }

  @Test
  fun systemVoiceUsesOnlySystemFallback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        model = TranslationTtsModel.SYSTEM,
        systemFallback = systemFallback,
        sherpaEnabled = true,
      )

    try {
      runBlocking {
        player.preload()
        player.speak(text = TEST_TEXT, languageTag = testLanguageTag)
      }

      assertEquals(listOf(testLanguageTag), systemFallback.spokenLanguageTags)
    } finally {
      player.release()
    }
  }

  @Test
  fun fallsBackToSystemTtsWhenEngineFails() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val failingEngine = FailingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = failingEngine,
        systemFallback = systemFallback,
        sherpaEnabled = true,
      )

    try {
      runBlocking {
        player.speak(text = TEST_TEXT, languageTag = testLanguageTag)
      }

      assertEquals(1, failingEngine.synthesisCount)
      assertEquals(listOf(testLanguageTag), systemFallback.spokenLanguageTags)
      assertFalse(player.isSpeaking.value)
    } finally {
      player.release()
    }
  }

  @Test
  fun disabledEngineUsesOnlySystemTts() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val failingEngine = FailingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = failingEngine,
        systemFallback = systemFallback,
        sherpaEnabled = false,
      )

    try {
      runBlocking {
        player.preload()
        player.speak(text = TEST_TEXT, languageTag = testLanguageTag)
      }

      assertEquals(0, failingEngine.preloadCount)
      assertEquals(0, failingEngine.synthesisCount)
      assertEquals(listOf(testLanguageTag), systemFallback.spokenLanguageTags)
    } finally {
      player.release()
    }
  }

  @Test
  fun streamingSwitchesToSystemTtsAfterFirstEngineFailure() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val failingEngine = FailingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = failingEngine,
        systemFallback = systemFallback,
        sherpaEnabled = true,
      )

    try {
      runBlocking {
        val sessionId = player.startStreaming(languageTag = testLanguageTag)
        assertTrue(player.enqueueStreaming(sessionId, "First speech segment."))
        assertTrue(player.enqueueStreaming(sessionId, "Second speech segment."))

        val result = player.finishStreaming(sessionId)

        assertEquals(1, failingEngine.synthesisCount)
        assertEquals(listOf(testLanguageTag, testLanguageTag), systemFallback.spokenLanguageTags)
        assertEquals(2, result.playedChunkCount)
        assertNull(result.error)
      }
    } finally {
      player.release()
    }
  }

  @Test
  fun playsValidatedPcmWithoutCreatingAWav() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine = RecordingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = engine,
        systemFallback = systemFallback,
      )
    val wavFilesBefore =
      context.cacheDir.listFiles()?.filter { file -> file.extension == "wav" }?.map { it.name }
        ?.toSet().orEmpty()

    try {
      runBlocking {
        player.preload()
        player.speak(text = TEST_TEXT, languageTag = testLanguageTag)
      }
      assertEquals(listOf(TEST_TEXT to testLanguageTag), engine.synthesisRequests)
      assertTrue(systemFallback.spokenLanguageTags.isEmpty())
      assertFalse(player.isSpeaking.value)
      val wavFilesAfter =
        context.cacheDir.listFiles()?.filter { file -> file.extension == "wav" }?.map { it.name }
          ?.toSet().orEmpty()
      assertEquals(wavFilesBefore, wavFilesAfter)
    } finally {
      player.release()
    }
  }

  @Test
  fun streamsQueuedPcmChunksInOrder() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val engine = RecordingTranslationTtsEngine()
    val systemFallback = RecordingSystemTtsFallback()
    val player =
      TranslationTtsPlayer(
        context = context,
        engine = engine,
        systemFallback = systemFallback,
      )

    try {
      runBlocking {
        player.preload()
        val sessionId = player.startStreaming(languageTag = testLanguageTag)
        assertTrue(player.enqueueStreaming(sessionId, "First speech segment."))
        assertTrue(player.enqueueStreaming(sessionId, "Second speech segment."))

        val result = player.finishStreaming(sessionId)

        assertEquals(
          listOf(
            "First speech segment." to testLanguageTag,
            "Second speech segment." to testLanguageTag,
          ),
          engine.synthesisRequests,
        )
        assertTrue(systemFallback.spokenLanguageTags.isEmpty())
        assertEquals(2, result.queuedChunkCount)
        assertEquals(2, result.playedChunkCount)
        assertNull(result.error)
        assertFalse(result.cancelled)
      }
      assertFalse(player.isSpeaking.value)
    } finally {
      player.release()
    }
  }

  private class RecordingTranslationTtsEngine : TranslationTtsEngine {
    val synthesisRequests = mutableListOf<Pair<String, String>>()

    override suspend fun preload() = Unit

    override suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio {
      synthesisRequests += text to languageTag
      return SynthesizedAudio(
        samples = FloatArray(TEST_SAMPLE_COUNT) { 0.05f },
        sampleRate = TEST_SAMPLE_RATE,
      )
    }

    override fun release() = Unit
  }

  private class FailingTranslationTtsEngine : TranslationTtsEngine {
    var preloadCount = 0
    var synthesisCount = 0

    override suspend fun preload() {
      preloadCount++
    }

    override suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio {
      synthesisCount++
      throw TranslationTtsSynthesisException("Injected synthesis failure.")
    }

    override fun release() = Unit
  }

  private class RecordingSystemTtsFallback : TranslationSystemTtsFallback {
    val spokenLanguageTags = mutableListOf<String>()

    override suspend fun speak(text: String, languageTag: String) {
      spokenLanguageTags += languageTag
    }

    override fun stop() = Unit

    override fun release() = Unit
  }

  companion object {
    private const val TEST_TEXT = "This is a speech synthesis test."
    private const val TEST_SAMPLE_RATE = 24_000
    private const val TEST_SAMPLE_COUNT = TranslationTtsAudioValidator.MIN_SAMPLE_COUNT + 1
    private val testLanguageTag = TranslationLanguage.entries.first().ttsLanguageTag
  }
}
