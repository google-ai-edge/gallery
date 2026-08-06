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
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "AGTranslationTtsEngine"
private const val WARM_UP_TEXT = "Ready."

internal abstract class SherpaTtsEngine<Package>(
  context: Context,
  private val onDownloadProgress: (TranslationTtsDownloadProgress) -> Unit,
) : TranslationTtsEngine {
  protected abstract val backend: String
  protected abstract val revision: String
  protected abstract val displayName: String
  protected abstract val sampleRate: Int
  protected abstract val logSettings: String

  private val appContext = context.applicationContext
  private val nativeLock = Any()
  private var tts: OfflineTts? = null
  private var loadedPackagePath: String? = null
  private var loadedConfigKey: String? = null

  protected abstract suspend fun installPackage(
    context: Context,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ): Package

  protected abstract fun packageRoot(installedPackage: Package): File

  protected abstract fun createTts(installedPackage: Package, language: String): OfflineTts

  protected abstract fun generationConfig(
    installedPackage: Package,
    language: String,
  ): GenerationConfig

  protected open fun sherpaLanguage(normalizedLanguageTag: String): String = normalizedLanguageTag

  protected open fun configKey(language: String): String = ""

  final override suspend fun preload() {
    ensureLoaded(sherpaLanguage("en-us"))
  }

  final override suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio =
    synthesizeInternal(text, languageTag, null)

  final override suspend fun synthesizeStreaming(
    text: String,
    languageTag: String,
    onPcmChunk: (SynthesizedAudio) -> Boolean,
  ): SynthesizedAudio = synthesizeInternal(text, languageTag, onPcmChunk)

  private suspend fun synthesizeInternal(
    text: String,
    languageTag: String,
    onPcmChunk: ((SynthesizedAudio) -> Boolean)?,
  ): SynthesizedAudio {
    val textToSpeak = text.trim()
    if (textToSpeak.isEmpty()) {
      throw TranslationTtsSynthesisException("Translation TTS cannot synthesize blank text.")
    }
    val normalizedLanguage = SherpaKokoroVoiceSelector.normalize(languageTag)
    val modelLanguage = sherpaLanguage(normalizedLanguage)
    val installedPackage = installPackage(appContext, onDownloadProgress)

    return withContext(Dispatchers.IO) {
      val coroutineContext = currentCoroutineContext()
      synchronized(nativeLock) {
        ensureLoaded(installedPackage, modelLanguage)
        val activeTts =
          tts
            ?: throw TranslationTtsSynthesisException(
              "Translation TTS was released before synthesis started."
            )
        val startedNanos = System.nanoTime()
        var firstPcmNanos = 0L
        var consumerStopped = false
        var audio: SynthesizedAudio? = null
        try {
          val config = generationConfig(installedPackage, modelLanguage)
          val generatedAudio =
            if (onPcmChunk == null) {
              activeTts.generateWithConfig(textToSpeak, config)
            } else {
              activeTts.generateWithConfigAndCallback(
                text = textToSpeak,
                config = config,
                callback =
                  SherpaNativePcmCallback { samples ->
                    when {
                      !coroutineContext.isActive -> 0
                      samples.isEmpty() -> 1
                      else -> {
                        if (firstPcmNanos == 0L) firstPcmNanos = System.nanoTime()
                        if (onPcmChunk(SynthesizedAudio(samples.copyOf(), sampleRate))) {
                          1
                        } else {
                          consumerStopped = true
                          0
                        }
                      }
                    }
                  },
              )
            }
          coroutineContext.ensureActive()
          if (consumerStopped) throw CancellationException("Translation PCM consumer stopped.")

          audio = SynthesizedAudio(generatedAudio.samples, generatedAudio.sampleRate)
          val metrics = TranslationTtsAudioValidator.validate(audio)
          if (audio.sampleRate != sampleRate) {
            throw TranslationTtsSynthesisException(
              "$displayName returned ${audio.sampleRate} Hz; expected $sampleRate Hz.",
              audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
              metrics = metrics,
            )
          }
          logOutcome(
            normalizedLanguage,
            startedNanos,
            audio.samples.size,
            metrics,
            "success",
            firstPcmNanos,
          )
          audio
        } catch (exception: CancellationException) {
          logOutcome(
            normalizedLanguage,
            startedNanos,
            audio?.samples?.size ?: 0,
            null,
            "cancelled",
            firstPcmNanos,
          )
          throw exception
        } catch (exception: TranslationTtsSynthesisException) {
          logOutcome(
            languageTag = normalizedLanguage,
            startedNanos = startedNanos,
            sampleCount = audio?.samples?.size ?: 0,
            metrics = exception.metrics,
            outcome = "alert_${exception.audioFailure?.name?.lowercase() ?: "failed"}",
            firstPcmNanos = firstPcmNanos,
            alert = exception.audioFailure != null,
          )
          throw exception
        } catch (throwable: Throwable) {
          logOutcome(normalizedLanguage, startedNanos, 0, null, "failed", firstPcmNanos, true)
          throw throwable
        }
      }
    }
  }

  final override fun release() {
    synchronized(nativeLock) {
      tts?.release()
      tts = null
      loadedPackagePath = null
      loadedConfigKey = null
    }
  }

  private suspend fun ensureLoaded(language: String): Package {
    val installedPackage = installPackage(appContext, onDownloadProgress)
    withContext(Dispatchers.IO) {
      synchronized(nativeLock) { ensureLoaded(installedPackage, language) }
    }
    return installedPackage
  }

  private fun ensureLoaded(installedPackage: Package, language: String) {
    val packagePath = packageRoot(installedPackage).canonicalPath
    val newConfigKey = configKey(language)
    if (
      tts != null && loadedPackagePath == packagePath && loadedConfigKey == newConfigKey
    ) {
      return
    }

    tts?.release()
    tts = null
    loadedPackagePath = null
    loadedConfigKey = null
    val newTts = createTts(installedPackage, language)
    val reportedSampleRate = newTts.sampleRate()
    if (reportedSampleRate != sampleRate) {
      newTts.release()
      throw TranslationTtsSynthesisException(
        "$displayName reports $reportedSampleRate Hz; expected $sampleRate Hz.",
        audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
      )
    }
    tts = newTts
    loadedPackagePath = packagePath
    loadedConfigKey = newConfigKey
    warmUp(newTts, installedPackage, language)
    Log.i(TAG, "backend=$backend revision=$revision $logSettings outcome=initialized")
  }

  private fun warmUp(activeTts: OfflineTts, installedPackage: Package, language: String) {
    runCatching {
        activeTts.generateWithConfig(
          WARM_UP_TEXT,
          generationConfig(installedPackage, language),
        )
      }
      .onFailure { throwable ->
        Log.w(
          TAG,
          "backend=$backend revision=$revision outcome=initialization_warmup_failed",
          throwable,
        )
      }
  }

  private fun logOutcome(
    languageTag: String,
    startedNanos: Long,
    sampleCount: Int,
    metrics: TranslationTtsAudioMetrics?,
    outcome: String,
    firstPcmNanos: Long,
    alert: Boolean = false,
  ) {
    val synthesisMillis = (System.nanoTime() - startedNanos) / 1_000_000.0
    val firstPcmMillis =
      if (firstPcmNanos == 0L) 0.0 else (firstPcmNanos - startedNanos) / 1_000_000.0
    val message =
      "backend=$backend revision=$revision language=$languageTag " +
        "synthesis_ms=$synthesisMillis sample_count=$sampleCount first_pcm_ms=$firstPcmMillis " +
        "$logSettings duration=${metrics?.durationSeconds ?: 0.0} " +
        "rms=${metrics?.rms ?: 0.0} outcome=$outcome"
    if (alert) Log.e(TAG, "ALERT $message") else Log.i(TAG, message)
  }
}
