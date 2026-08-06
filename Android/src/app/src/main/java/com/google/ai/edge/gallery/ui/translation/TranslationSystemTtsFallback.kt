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

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGTranslationSystemTts"
private const val INITIALIZATION_PENDING = Int.MIN_VALUE

internal interface TranslationSystemTtsFallback {
  suspend fun speak(text: String, languageTag: String)

  fun stop()

  fun release()
}

internal class AndroidTranslationSystemTtsFallback(context: Context) :
  TranslationSystemTtsFallback {
  private val appContext = context.applicationContext
  private val lock = Any()
  private val pendingUtterances = ConcurrentHashMap<String, PendingUtterance>()
  private var textToSpeech: TextToSpeech? = null
  private var initialization: CompletableDeferred<TextToSpeech>? = null
  private var released = false

  override suspend fun speak(text: String, languageTag: String) {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty()) return

    val normalizedLanguageTag = SherpaKokoroVoiceSelector.normalize(languageTag)
    val engine = ensureInitialized()
    val utteranceId = UUID.randomUUID().toString()
    val completion = CompletableDeferred<Unit>()
    pendingUtterances[utteranceId] =
      PendingUtterance(languageTag = normalizedLanguageTag, completion = completion)

    try {
      val result =
        withContext(Dispatchers.Main.immediate) {
          val languageResult = engine.setLanguage(systemLocale(normalizedLanguageTag))
          if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
              languageResult == TextToSpeech.LANG_NOT_SUPPORTED
          ) {
            throw TranslationTtsSynthesisException(
              "Android system TTS does not support language: $normalizedLanguageTag"
            )
          }
          engine.speak(trimmedText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
      if (result == TextToSpeech.ERROR) {
        throw TranslationTtsSynthesisException("Android system TTS rejected the utterance.")
      }
      completion.await()
    } catch (exception: CancellationException) {
      pendingUtterances.remove(utteranceId)
      engine.stop()
      throw exception
    } catch (throwable: Throwable) {
      pendingUtterances.remove(utteranceId)
      logOutcome(normalizedLanguageTag, "playback_failed", alert = true)
      throw throwable
    }
  }

  override fun stop() {
    textToSpeech?.stop()
    val cancellation = CancellationException("Android system TTS playback was stopped.")
    pendingUtterances.values.forEach { pending -> pending.completion.cancel(cancellation) }
    pendingUtterances.clear()
  }

  override fun release() {
    val engine =
      synchronized(lock) {
        if (released) return
        released = true
        textToSpeech.also { textToSpeech = null }
      }
    stop()
    engine?.shutdown()
    initialization?.cancel(CancellationException("Android system TTS was released."))
  }

  private suspend fun ensureInitialized(): TextToSpeech {
    var createEngine = false
    val deferred =
      synchronized(lock) {
        if (released) {
          throw TranslationTtsSynthesisException("Android system TTS was released.")
        }
        textToSpeech?.takeIf { initialization?.isCompleted == true }?.let { return it }
        initialization
          ?: CompletableDeferred<TextToSpeech>().also {
            initialization = it
            createEngine = true
          }
      }
    if (createEngine) {
      withContext(Dispatchers.Main.immediate) { createEngine() }
    }
    return deferred.await()
  }

  private fun createEngine() {
    val initializationStatus = AtomicInteger(INITIALIZATION_PENDING)
    val created =
      TextToSpeech(appContext) { status ->
        initializationStatus.set(status)
        completeInitialization(status)
      }
    synchronized(lock) {
      if (released) {
        created.shutdown()
        return
      }
      textToSpeech = created
    }
    initializationStatus
      .get()
      .takeIf { it != INITIALIZATION_PENDING }
      ?.let(::completeInitialization)
  }

  private fun completeInitialization(status: Int) {
    val engine: TextToSpeech
    val deferred: CompletableDeferred<TextToSpeech>
    synchronized(lock) {
      engine = textToSpeech ?: return
      deferred = initialization ?: return
      if (deferred.isCompleted) return
    }
    if (status != TextToSpeech.SUCCESS) {
      deferred.completeExceptionally(
        TranslationTtsSynthesisException("Android system TTS initialization failed.")
      )
      return
    }
    engine.setAudioAttributes(
      AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()
    )
    engine.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
          pendingUtterances[utteranceId]?.let { pending ->
            logOutcome(pending.languageTag, "playback_started")
          }
        }

        override fun onDone(utteranceId: String) {
          pendingUtterances.remove(utteranceId)?.let { pending ->
            logOutcome(pending.languageTag, "playback_completed")
            pending.completion.complete(Unit)
          }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
          finishWithError(utteranceId)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
          finishWithError(utteranceId)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
          pendingUtterances.remove(utteranceId)?.let { pending ->
            logOutcome(pending.languageTag, "playback_cancelled")
            pending.completion.cancel(
              CancellationException("Android system TTS playback was interrupted.")
            )
          }
        }
      }
    )
    deferred.complete(engine)
  }

  private fun finishWithError(utteranceId: String) {
    pendingUtterances.remove(utteranceId)?.let { pending ->
      logOutcome(pending.languageTag, "playback_failed", alert = true)
      pending.completion.completeExceptionally(
        TranslationTtsSynthesisException("Android system TTS playback failed.")
      )
    }
  }

  private fun systemLocale(languageTag: String): Locale = Locale.forLanguageTag(languageTag)

  private fun logOutcome(languageTag: String, outcome: String, alert: Boolean = false) {
    val message =
      "backend=android-system revision=android-${Build.VERSION.SDK_INT} " +
        "language=$languageTag outcome=$outcome"
    if (alert) Log.e(TAG, "ALERT $message") else Log.i(TAG, message)
  }

  private data class PendingUtterance(
    val languageTag: String,
    val completion: CompletableDeferred<Unit>,
  )
}
