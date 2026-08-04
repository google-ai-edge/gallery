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
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val SUPERTONIC_TAG = "AGSupertonicTtsEngine"
private const val SUPERTONIC_THREADS = 8
private const val SUPERTONIC_SPEED = 1.0f
private const val SUPERTONIC_NUM_STEPS = 8
private const val SUPERTONIC_SPEAKER_ID = 6
private const val SUPERTONIC_WARM_UP_TEXT = "Ready."

internal class SherpaSupertonicTtsEngine(
  context: Context,
  private val onDownloadProgress: (TranslationTtsDownloadProgress) -> Unit = {},
) : TranslationTtsEngine {
  private val appContext = context.applicationContext
  private val nativeLock = Any()
  private var tts: OfflineTts? = null
  private var loadedPackagePath: String? = null

  override suspend fun preload() {
    ensureLoaded()
  }

  override suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio =
    synthesizeInternal(text = text, languageTag = languageTag, onPcmChunk = null)

  override suspend fun synthesizeStreaming(
    text: String,
    languageTag: String,
    onPcmChunk: (SynthesizedAudio) -> Boolean,
  ): SynthesizedAudio =
    synthesizeInternal(text = text, languageTag = languageTag, onPcmChunk = onPcmChunk)

  private suspend fun synthesizeInternal(
    text: String,
    languageTag: String,
    onPcmChunk: ((SynthesizedAudio) -> Boolean)?,
  ): SynthesizedAudio {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty()) {
      throw TranslationTtsSynthesisException("Translation TTS cannot synthesize blank text.")
    }
    val normalizedLanguageTag = SherpaKokoroVoiceSelector.normalize(languageTag)
    val supertonicLanguage = selectLanguage(normalizedLanguageTag)
    ensureLoaded()

    return withContext(Dispatchers.IO) {
      val synthesisContext = currentCoroutineContext()
      synchronized(nativeLock) {
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
          val config = generationConfig(supertonicLanguage)
          val generatedAudio =
            if (onPcmChunk == null) {
              activeTts.generateWithConfig(text = trimmedText, config = config)
            } else {
              activeTts.generateWithConfigAndCallback(
                text = trimmedText,
                config = config,
                callback =
                  SherpaNativePcmCallback { samples ->
                    if (!synthesisContext.isActive) {
                      0
                    } else if (samples.isEmpty()) {
                      1
                    } else {
                      if (firstPcmNanos == 0L) firstPcmNanos = System.nanoTime()
                      val accepted =
                        onPcmChunk(
                          SynthesizedAudio(
                            samples = samples.copyOf(),
                            sampleRate = SUPERTONIC_SHERPA_SAMPLE_RATE,
                          )
                        )
                      if (accepted) 1
                      else {
                        consumerStopped = true
                        0
                      }
                    }
                  },
              )
            }
          synthesisContext.ensureActive()
          if (consumerStopped) throw CancellationException("Translation PCM consumer stopped.")
          audio =
            SynthesizedAudio(
              samples = generatedAudio.samples,
              sampleRate = generatedAudio.sampleRate,
            )
          val metrics = TranslationTtsAudioValidator.validate(audio)
          if (audio.sampleRate != SUPERTONIC_SHERPA_SAMPLE_RATE) {
            throw TranslationTtsSynthesisException(
              "Sherpa Supertonic returned ${audio.sampleRate} Hz; expected " +
                "$SUPERTONIC_SHERPA_SAMPLE_RATE Hz.",
              audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
              metrics = metrics,
            )
          }
          logOutcome(
            languageTag = normalizedLanguageTag,
            startedNanos = startedNanos,
            sampleCount = audio.samples.size,
            metrics = metrics,
            outcome = "success",
            firstPcmNanos = firstPcmNanos,
          )
          audio
        } catch (exception: CancellationException) {
          logOutcome(normalizedLanguageTag, startedNanos, 0, null, "cancelled", firstPcmNanos)
          throw exception
        } catch (exception: TranslationTtsSynthesisException) {
          logOutcome(
            languageTag = normalizedLanguageTag,
            startedNanos = startedNanos,
            sampleCount = audio?.samples?.size ?: 0,
            metrics = exception.metrics,
            outcome = "alert_${exception.audioFailure?.name?.lowercase() ?: "failed"}",
            firstPcmNanos = firstPcmNanos,
            alert = exception.audioFailure != null,
          )
          throw exception
        } catch (throwable: Throwable) {
          logOutcome(normalizedLanguageTag, startedNanos, 0, null, "failed", firstPcmNanos, true)
          throw throwable
        }
      }
    }
  }

  override fun release() {
    synchronized(nativeLock) {
      tts?.release()
      tts = null
      loadedPackagePath = null
    }
  }

  private suspend fun ensureLoaded(): SupertonicSherpaPackage {
    val installedPackage =
      SherpaSupertonicPackageInstaller.ensureInstalled(appContext, onDownloadProgress)
    withContext(Dispatchers.IO) {
      synchronized(nativeLock) {
        val packagePath = installedPackage.rootDirectory.canonicalPath
        if (tts != null && loadedPackagePath == packagePath) return@synchronized
        tts?.release()
        tts = null
        loadedPackagePath = null
        val newTts = createTts(installedPackage)
        val sampleRate = newTts.sampleRate()
        if (sampleRate != SUPERTONIC_SHERPA_SAMPLE_RATE) {
          newTts.release()
          throw TranslationTtsSynthesisException(
            "Sherpa Supertonic reports $sampleRate Hz; expected $SUPERTONIC_SHERPA_SAMPLE_RATE Hz.",
            audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
          )
        }
        tts = newTts
        loadedPackagePath = packagePath
        warmUp(newTts)
        Log.i(
          SUPERTONIC_TAG,
          "backend=$SUPERTONIC_SHERPA_BACKEND revision=$SUPERTONIC_SHERPA_PACKAGE_ID " +
            "threads=$SUPERTONIC_THREADS steps=$SUPERTONIC_NUM_STEPS outcome=initialized",
        )
      }
    }
    return installedPackage
  }

  private fun createTts(installedPackage: SupertonicSherpaPackage): OfflineTts =
    OfflineTts(
      config =
        OfflineTtsConfig(
          model =
            OfflineTtsModelConfig(
              supertonic =
                OfflineTtsSupertonicModelConfig(
                  durationPredictor = installedPackage.durationPredictor.absolutePath,
                  textEncoder = installedPackage.textEncoder.absolutePath,
                  vectorEstimator = installedPackage.vectorEstimator.absolutePath,
                  vocoder = installedPackage.vocoder.absolutePath,
                  ttsJson = installedPackage.ttsJson.absolutePath,
                  unicodeIndexer = installedPackage.unicodeIndexer.absolutePath,
                  voiceStyle = installedPackage.voiceStyle.absolutePath,
                ),
              numThreads = SUPERTONIC_THREADS,
              debug = false,
              provider = "cpu",
            ),
          maxNumSentences = 1,
        )
    )

  private fun warmUp(activeTts: OfflineTts) {
    runCatching {
        activeTts.generateWithConfig(
          text = SUPERTONIC_WARM_UP_TEXT,
          config = generationConfig("en"),
        )
      }
      .onFailure { throwable ->
        Log.w(
          SUPERTONIC_TAG,
          "backend=$SUPERTONIC_SHERPA_BACKEND revision=$SUPERTONIC_SHERPA_PACKAGE_ID " +
            "outcome=initialization_warmup_failed",
          throwable,
        )
      }
  }

  private fun generationConfig(language: String) =
    GenerationConfig(
      speed = SUPERTONIC_SPEED,
      sid = SUPERTONIC_SPEAKER_ID,
      numSteps = SUPERTONIC_NUM_STEPS,
      extra = mapOf("lang" to language),
    )

  private fun selectLanguage(normalizedLanguageTag: String): String =
    when (normalizedLanguageTag) {
      "en-us" -> "en"
      "es" -> "es"
      "fr-fr" -> "fr"
      "it" -> "it"
      else ->
        throw TranslationTtsSynthesisException(
          "Supertonic 3 does not support language tag: $normalizedLanguageTag"
        )
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
      "backend=$SUPERTONIC_SHERPA_BACKEND revision=$SUPERTONIC_SHERPA_PACKAGE_ID " +
        "language=$languageTag synthesis_ms=$synthesisMillis sample_count=$sampleCount " +
        "first_pcm_ms=$firstPcmMillis speed=$SUPERTONIC_SPEED steps=$SUPERTONIC_NUM_STEPS " +
        "duration=${metrics?.durationSeconds ?: 0.0} rms=${metrics?.rms ?: 0.0} " +
        "outcome=$outcome"
    if (alert) Log.e(SUPERTONIC_TAG, "ALERT $message") else Log.i(SUPERTONIC_TAG, message)
  }
}
