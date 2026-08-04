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
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "AGTranslationTtsEngine"
private const val SHERPA_TTS_THREADS = 8
private const val SHERPA_TTS_SPEED = 1.2f
private const val SHERPA_TTS_WARM_UP_TEXT = "Ready."

internal class SherpaKokoroTtsEngine(
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

  override suspend fun synthesize(text: String, languageTag: String): SynthesizedAudio {
    return synthesizeInternal(text = text, languageTag = languageTag, onPcmChunk = null)
  }

  override suspend fun synthesizeStreaming(
    text: String,
    languageTag: String,
    onPcmChunk: (SynthesizedAudio) -> Boolean,
  ): SynthesizedAudio {
    return synthesizeInternal(
      text = text,
      languageTag = languageTag,
      onPcmChunk = onPcmChunk,
    )
  }

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
    val installedPackage = ensureLoaded()
    val voiceConfig =
      SherpaKokoroVoiceSelector.select(
        languageTag = normalizedLanguageTag,
        voiceConfigs = installedPackage.voiceConfigs,
      )
        ?: throw TranslationTtsSynthesisException(
          "Translation TTS does not support language tag: $languageTag"
        )

    return withContext(Dispatchers.IO) {
      val synthesisContext = currentCoroutineContext()
      synchronized(nativeLock) {
        val activeTts =
          tts
            ?: throw TranslationTtsSynthesisException(
              "Translation TTS was released before synthesis started."
            )
        val synthesisStartedNanos = System.nanoTime()
        var firstPcmNanos = 0L
        var consumerStopped = false
        var audio: SynthesizedAudio? = null
        try {
          val generationConfig =
            GenerationConfig(
              speed = SHERPA_TTS_SPEED,
              sid = voiceConfig.speakerId,
              extra = mapOf("lang" to voiceConfig.espeakVoice),
            )
          val generatedAudio =
            if (onPcmChunk == null) {
              activeTts.generateWithConfig(text = trimmedText, config = generationConfig)
            } else {
              val nativeCallback =
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
                          sampleRate = KOKORO_SHERPA_SAMPLE_RATE,
                        )
                      )
                    if (accepted) {
                      1
                    } else {
                      consumerStopped = true
                      0
                    }
                  }
                }
              activeTts.generateWithConfigAndCallback(
                text = trimmedText,
                config = generationConfig,
                callback = nativeCallback,
              )
            }
          synthesisContext.ensureActive()
          if (consumerStopped) {
            throw CancellationException("Translation PCM consumer stopped.")
          }
          audio =
            SynthesizedAudio(
              samples = generatedAudio.samples,
              sampleRate = generatedAudio.sampleRate,
            )
          val metrics = TranslationTtsAudioValidator.validate(audio)
          if (audio.sampleRate != KOKORO_SHERPA_SAMPLE_RATE) {
            throw TranslationTtsSynthesisException(
              "Sherpa Kokoro returned ${audio.sampleRate} Hz; expected " +
                "$KOKORO_SHERPA_SAMPLE_RATE Hz.",
              audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
              metrics = metrics,
            )
          }
          logSynthesisOutcome(
            languageTag = normalizedLanguageTag,
            synthesisStartedNanos = synthesisStartedNanos,
            sampleCount = audio.samples.size,
            metrics = metrics,
            outcome = "success",
            firstPcmNanos = firstPcmNanos,
          )
          audio
        } catch (exception: CancellationException) {
          logSynthesisOutcome(
            languageTag = normalizedLanguageTag,
            synthesisStartedNanos = synthesisStartedNanos,
            sampleCount = audio?.samples?.size ?: 0,
            metrics = null,
            outcome = "cancelled",
            firstPcmNanos = firstPcmNanos,
          )
          throw exception
        } catch (exception: TranslationTtsSynthesisException) {
          logSynthesisOutcome(
            languageTag = normalizedLanguageTag,
            synthesisStartedNanos = synthesisStartedNanos,
            sampleCount = audio?.samples?.size ?: 0,
            metrics = exception.metrics,
            outcome = "alert_${exception.audioFailure?.name?.lowercase() ?: "failed"}",
            alert = exception.audioFailure != null,
            firstPcmNanos = firstPcmNanos,
          )
          throw exception
        } catch (throwable: Throwable) {
          logSynthesisOutcome(
            languageTag = normalizedLanguageTag,
            synthesisStartedNanos = synthesisStartedNanos,
            sampleCount = audio?.samples?.size ?: 0,
            metrics = null,
            outcome = "failed",
            alert = true,
            firstPcmNanos = firstPcmNanos,
          )
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

  private suspend fun ensureLoaded(): KokoroSherpaPackage {
    val installedPackage =
      TranslationTtsModelRepository.ensureInstalled(
        context = appContext,
        onProgress = onDownloadProgress,
      )
    withContext(Dispatchers.IO) {
      synchronized(nativeLock) {
        val packagePath = installedPackage.rootDirectory.canonicalPath
        if (tts != null && loadedPackagePath == packagePath) return@synchronized

        tts?.release()
        tts = null
        loadedPackagePath = null
        val newTts = createTts(installedPackage)
        val sampleRate = newTts.sampleRate()
        if (sampleRate != KOKORO_SHERPA_SAMPLE_RATE) {
          newTts.release()
          throw TranslationTtsSynthesisException(
            "Sherpa Kokoro reports $sampleRate Hz; expected $KOKORO_SHERPA_SAMPLE_RATE Hz.",
            audioFailure = TranslationTtsAudioFailure.UNEXPECTED_SAMPLE_RATE,
          )
        }
        tts = newTts
        loadedPackagePath = packagePath
        warmUpInitializedModel(activeTts = newTts, installedPackage = installedPackage)
        Log.i(
          TAG,
          "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
            "threads=$SHERPA_TTS_THREADS speed=$SHERPA_TTS_SPEED outcome=initialized",
        )
      }
    }
    return installedPackage
  }

  private fun warmUpInitializedModel(
    activeTts: OfflineTts,
    installedPackage: KokoroSherpaPackage,
  ) {
    val voiceConfig =
      SherpaKokoroVoiceSelector.select(
        languageTag = "en-us",
        voiceConfigs = installedPackage.voiceConfigs,
      ) ?: return
    val warmUpStartedNanos = System.nanoTime()
    try {
      activeTts.generateWithConfig(
        text = SHERPA_TTS_WARM_UP_TEXT,
        config =
          GenerationConfig(
            speed = SHERPA_TTS_SPEED,
            sid = voiceConfig.speakerId,
            extra = mapOf("lang" to voiceConfig.espeakVoice),
          ),
      )
      val warmUpMillis = (System.nanoTime() - warmUpStartedNanos) / 1_000_000.0
      Log.i(
        TAG,
        "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
          "threads=$SHERPA_TTS_THREADS speed=$SHERPA_TTS_SPEED warmup_ms=$warmUpMillis " +
          "outcome=initialization_warmup_completed",
      )
    } catch (throwable: Throwable) {
      Log.w(
        TAG,
        "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
          "threads=$SHERPA_TTS_THREADS speed=$SHERPA_TTS_SPEED " +
          "outcome=initialization_warmup_failed",
        throwable,
      )
    }
  }

  private fun createTts(installedPackage: KokoroSherpaPackage): OfflineTts {
    val config =
      OfflineTtsConfig(
        model =
          OfflineTtsModelConfig(
            kokoro =
              OfflineTtsKokoroModelConfig(
                model = installedPackage.modelFile.absolutePath,
                voices = installedPackage.voicesFile.absolutePath,
                tokens = installedPackage.tokensFile.absolutePath,
                dataDir = installedPackage.espeakDataDirectory.absolutePath,
                lang = "en-us",
              ),
            numThreads = SHERPA_TTS_THREADS,
            debug = false,
            provider = "cpu",
          ),
        maxNumSentences = 1,
      )
    return OfflineTts(config = config)
  }

  private fun logSynthesisOutcome(
    languageTag: String,
    synthesisStartedNanos: Long,
    sampleCount: Int,
    metrics: TranslationTtsAudioMetrics?,
    outcome: String,
    alert: Boolean = false,
    firstPcmNanos: Long = 0L,
  ) {
    val synthesisMillis = (System.nanoTime() - synthesisStartedNanos) / 1_000_000.0
    val message =
      "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
      "language=$languageTag synthesis_ms=$synthesisMillis sample_count=$sampleCount " +
        "first_pcm_ms=" +
        (if (firstPcmNanos == 0L) 0.0 else (firstPcmNanos - synthesisStartedNanos) / 1_000_000.0) +
        " speed=$SHERPA_TTS_SPEED " +
        "duration=${metrics?.durationSeconds ?: 0.0} rms=${metrics?.rms ?: 0.0} " +
        "outcome=$outcome"
    if (alert) {
      Log.e(TAG, "ALERT $message")
    } else {
      Log.i(TAG, message)
    }
  }
}
