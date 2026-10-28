/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsPlaybackEvent
import com.google.ai.edge.gallery.services.tts.TtsQueueMode
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsUiState
import com.google.ai.edge.gallery.services.tts.TtsVoicePreviewPhrases
import com.google.ai.edge.gallery.services.tts.startOnFirstFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGVoicePreviewCtrl"
private const val DRAIN_POLL_INTERVAL_MS = 20L
private const val DRAIN_STALL_TIMEOUT_MS = 2000L
private const val BYTES_PER_PCM_FRAME = 2L

/** Manages TTS voice profile sample previews across all TTS engines for [ModelManagerViewModel]. */
internal class VoicePreviewController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  private val ttsSettingsRepository: TtsSettingsRepository,
  private val speechSelectionUseCase: SpeechSelectionUseCase,
  private val uiStateProvider: () -> ModelManagerUiState,
  private val ttsSettingsUiStateProvider: () -> TtsSettingsUiState,
  private val onRefreshTtsSettingsUiState: () -> Unit,
  private val onRefreshLanguagePackStatuses: (Model, List<String>) -> Unit,
  private val onRefreshSupplementaryLanguagePack: (Model, String) -> Unit,
) {
  private val _voicePreviewUiState = MutableStateFlow(VoicePreviewUiState())
  val uiState: StateFlow<VoicePreviewUiState> = _voicePreviewUiState.asStateFlow()

  private var voicePreviewJob: Job? = null
  private var voicePreviewSeq: Long = 0L
  private var registeredVoicePreviewStopProvider: TtsServiceProvider? = null
  private val onProviderStopAllListener: () -> Unit = { cancelLocalVoicePreview() }

  var playbackHeadProvider: (AudioTrack) -> Long = { track ->
    track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
  }

  /** Selects [voiceId] as the active TTS voice, and optionally plays a sample preview. */
  fun selectTtsVoice(voiceId: String, playPreview: Boolean = true) {
    val previousVoiceId = ttsSettingsUiStateProvider().selectedVoiceId
    ttsSettingsRepository.setSelectedTtsVoice(voiceId)
    onRefreshTtsSettingsUiState()
    when {
      playPreview -> {
        val effectiveSelection = speechSelectionUseCase.effectiveTtsSelection()
        val engineId =
          effectiveSelection.engineId.ifEmpty {
            ttsSettingsRepository.selectedTtsModelID.value.ifEmpty {
              TtsModelIdentifier.ANDROID_NATIVE
            }
          }
        val fallbackLanguageCode =
          effectiveSelection.languageCode
            .ifEmpty { ttsSettingsRepository.selectedLanguageCode.value }
            .ifEmpty { DEFAULT_TTS_LANGUAGE_CODE }
        val languageCode = resolveVoicePreviewLanguageCode(engineId, voiceId, fallbackLanguageCode)
        val model =
          uiStateProvider().supplementaryModels.firstOrNull {
            it.modelType == ModelType.TTS &&
              (it.ttsProfile?.engineModelId == engineId || it.name == engineId)
          }
        startVoicePreview(
          engineId = engineId,
          model = model,
          voiceId = voiceId,
          previewText = resolveVoicePreviewText(languageCode),
          languageCode = languageCode,
        )
      }
      previousVoiceId != voiceId -> stopVoicePreview()
    }
  }

  private fun resolveVoicePreviewLanguageCode(
    engineId: String,
    voiceId: String,
    fallbackLanguageCode: String,
  ): String {
    if (
      engineId == TtsModelIdentifier.ML_KIT ||
        TtsModelIdentifier.isSystemEngine(engineId) ||
        voiceId.isBlank() ||
        voiceId == TtsSettingsRepository.DEFAULT_SYSTEM_VOICE
    ) {
      return fallbackLanguageCode
    }
    val voiceLocale =
      ttsSettingsRepository
        .getAvailableVoicesForEngine(engineId, uiStateProvider().supplementaryModels)
        .firstOrNull { it.id == voiceId }
        ?.locale
        ?.takeIf(String::isNotBlank)
    return voiceLocale ?: fallbackLanguageCode
  }

  private fun resolveVoicePreviewText(languageCode: String): String {
    val localized = resolveLocalizedVoicePreviewText(context = context, languageCode = languageCode)
    val english =
      resolveLocalizedVoicePreviewText(context = context, languageCode = DEFAULT_TTS_LANGUAGE_CODE)
    return if (localized != english) {
      localized
    } else {
      TtsVoicePreviewPhrases.previewText(languageCode, english)
    }
  }

  /**
   * Starts or stops a sample audio preview for [voiceId] on the active TTS engine in the Config
   * side sheet's Audio tab.
   */
  fun toggleConfigVoicePreview(voiceId: String) {
    val effectiveSelection = speechSelectionUseCase.effectiveTtsSelection()
    val engineId =
      effectiveSelection.engineId.ifEmpty {
        ttsSettingsRepository.selectedTtsModelID.value.ifEmpty { TtsModelIdentifier.ANDROID_NATIVE }
      }
    val current = _voicePreviewUiState.value
    if (
      current.activeEngineId == engineId &&
        current.activeVoiceId == voiceId &&
        current.status != VoicePreviewStatus.IDLE
    ) {
      stopVoicePreview()
      return
    }
    val fallbackLanguageCode =
      effectiveSelection.languageCode
        .ifEmpty { ttsSettingsRepository.selectedLanguageCode.value }
        .ifEmpty { DEFAULT_TTS_LANGUAGE_CODE }
    val languageCode = resolveVoicePreviewLanguageCode(engineId, voiceId, fallbackLanguageCode)
    val model =
      uiStateProvider().supplementaryModels.firstOrNull {
        it.modelType == ModelType.TTS &&
          (it.ttsProfile?.engineModelId == engineId || it.name == engineId)
      }
    startVoicePreview(
      engineId = engineId,
      model = model,
      voiceId = voiceId,
      previewText = resolveVoicePreviewText(languageCode),
      languageCode = languageCode,
    )
  }

  /** Stops any currently loading or playing voice profile preview across all TTS engines. */
  fun stopVoicePreview() {
    cancelLocalVoicePreview()
    val provider = ttsSettingsRepository.serviceProviderOrNull() ?: return
    scope.launch(ioDispatcher) {
      try {
        provider.stopAll()
      } catch (e: Exception) {
        Log.w(TAG, "Error stopping TTS voice preview", e)
      }
    }
  }

  private fun cancelLocalVoicePreview() {
    voicePreviewSeq += 1L
    voicePreviewJob?.cancel()
    voicePreviewJob = null
    _voicePreviewUiState.value = VoicePreviewUiState()
  }

  private fun ensureVoicePreviewStopListenerRegistered(provider: TtsServiceProvider) {
    if (registeredVoicePreviewStopProvider !== provider) {
      registeredVoicePreviewStopProvider?.unregisterOnStopAllListener(onProviderStopAllListener)
      provider.registerOnStopAllListener(onProviderStopAllListener)
      registeredVoicePreviewStopProvider = provider
    }
  }

  /**
   * Stops a voice preview that is loading or playing, for example when the app leaves the
   * foreground. Unlike [stopVoicePreview], does nothing when no preview is active, so it never
   * creates the TTS engines just to stop them.
   */
  fun stopActiveVoicePreview() {
    if (_voicePreviewUiState.value.status != VoicePreviewStatus.IDLE) {
      stopVoicePreview()
    }
  }

  /**
   * Starts or stops a sample audio preview that speaks [previewText] in [languageCode] (a BCP-47
   * tag) with [voiceId] on [model], using any registered [TtsOutput.DirectPlayback] or
   * [TtsOutput.AudioStream] TTS engine.
   */
  fun toggleVoicePreview(
    model: Model,
    voiceId: String,
    previewText: String,
    languageCode: String = DEFAULT_TTS_LANGUAGE_CODE,
  ) {
    val engineId = model.ttsProfile?.engineModelId?.takeIf { it.isNotEmpty() } ?: return
    val current = _voicePreviewUiState.value
    if (
      current.activeEngineId == engineId &&
        current.activeVoiceId == voiceId &&
        current.status != VoicePreviewStatus.IDLE
    ) {
      stopVoicePreview()
      return
    }
    startVoicePreview(
      engineId = engineId,
      model = model,
      voiceId = voiceId,
      previewText = previewText,
      languageCode = languageCode,
    )
  }

  private fun startVoicePreview(
    engineId: String,
    model: Model?,
    voiceId: String,
    previewText: String,
    languageCode: String,
  ) {
    val previousJob = voicePreviewJob
    voicePreviewSeq += 1L
    previousJob?.cancel()
    voicePreviewJob = null
    val provider = ttsSettingsRepository.serviceProviderOrNull() ?: return
    ensureVoicePreviewStopListenerRegistered(provider)
    val seq = voicePreviewSeq
    _voicePreviewUiState.value =
      VoicePreviewUiState(
        activeEngineId = engineId,
        activeVoiceId = voiceId,
        status = VoicePreviewStatus.LOADING,
      )

    voicePreviewJob = scope.launch {
      try {
        val config =
          TtsConfig(
            modelId = engineId,
            languageCode = languageCode,
            voiceId = voiceId,
            queueMode = TtsQueueMode.FLUSH,
          )
        val service = provider.service(forConfig = config)
        withContext(ioDispatcher) {
          provider.unregisterOnStopAllListener(onProviderStopAllListener)
          try {
            provider.stopAllExcept(
              exceptEngine = service.takeIf { TtsModelIdentifier.ML_KIT in it.servedModelIds }
            )
          } finally {
            provider.registerOnStopAllListener(onProviderStopAllListener)
          }
        }
        previousJob?.join()
        if (voicePreviewSeq != seq) return@launch
        withContext(ioDispatcher) { service.prepare(config) }
        if (voicePreviewSeq != seq) return@launch
        if (model != null) {
          onRefreshLanguagePackStatuses(model, listOf(languageCode))
          onRefreshSupplementaryLanguagePack(model, languageCode)
        }

        when (val output = service.synthesize(previewText, config)) {
          is TtsOutput.DirectPlayback -> {
            output.statusFlow.collect { event ->
              if (voicePreviewSeq != seq) return@collect
              when (event) {
                TtsPlaybackEvent.PLAYBACK_STARTED -> {
                  _voicePreviewUiState.value =
                    VoicePreviewUiState(
                      activeEngineId = engineId,
                      activeVoiceId = voiceId,
                      status = VoicePreviewStatus.PLAYING,
                    )
                }
                TtsPlaybackEvent.PLAYBACK_COMPLETED -> {
                  _voicePreviewUiState.value = VoicePreviewUiState()
                }
              }
            }
          }
          is TtsOutput.AudioStream -> {
            withContext(ioDispatcher) {
              val minBufferSize =
                AudioTrack.getMinBufferSize(
                  24000,
                  AudioFormat.CHANNEL_OUT_MONO,
                  AudioFormat.ENCODING_PCM_16BIT,
                )
              val audioTrack =
                AudioTrack.Builder()
                  .setAudioAttributes(
                    AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build()
                  )
                  .setAudioFormat(
                    AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setSampleRate(24000)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build()
                  )
                  .setBufferSizeInBytes(minBufferSize.coerceAtLeast(4096 * 4))
                  .setTransferMode(AudioTrack.MODE_STREAM)
                  .build()
                  .startOnFirstFrame()
              try {
                audioTrack.play()
                var totalFramesWritten = 0L
                output.flow.collect { chunk ->
                  ensureActive()
                  if (chunk.isEmpty()) return@collect
                  if (_voicePreviewUiState.value.status != VoicePreviewStatus.PLAYING) {
                    _voicePreviewUiState.value =
                      VoicePreviewUiState(
                        activeEngineId = engineId,
                        activeVoiceId = voiceId,
                        status = VoicePreviewStatus.PLAYING,
                      )
                  }
                  val written = audioTrack.write(chunk, 0, chunk.size)
                  if (written > 0) {
                    totalFramesWritten += written / BYTES_PER_PCM_FRAME
                  }
                }
                if (
                  totalFramesWritten > 0L &&
                    voicePreviewSeq == seq &&
                    audioTrack.state == AudioTrack.STATE_INITIALIZED
                ) {
                  awaitAudioTrackDrain(
                    audioTrack = audioTrack,
                    targetFrames = totalFramesWritten,
                    isCancelled = { voicePreviewSeq != seq },
                    playbackHeadProvider = playbackHeadProvider,
                  )
                }
              } finally {
                try {
                  if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.pause()
                    audioTrack.flush()
                    audioTrack.stop()
                  }
                  audioTrack.release()
                } catch (e: Exception) {
                  Log.w(TAG, "Failed releasing preview AudioTrack", e)
                }
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Voice preview failed for '$voiceId' on '$engineId'", e)
      } finally {
        if (voicePreviewSeq == seq) {
          _voicePreviewUiState.value = VoicePreviewUiState()
        }
      }
    }
  }

  fun onCleared() {
    registeredVoicePreviewStopProvider?.unregisterOnStopAllListener(onProviderStopAllListener)
    registeredVoicePreviewStopProvider = null
    stopVoicePreview()
  }
}

/** Selects [voiceId] as the active TTS voice, and optionally plays a sample preview. */
fun ModelManagerViewModel.selectTtsVoice(voiceId: String, playPreview: Boolean = true) {
  voicePreviewController.selectTtsVoice(voiceId, playPreview)
}

/**
 * Starts or stops a sample audio preview for [voiceId] on the active TTS engine in the Config side
 * sheet's Audio tab.
 */
fun ModelManagerViewModel.toggleConfigVoicePreview(voiceId: String) {
  voicePreviewController.toggleConfigVoicePreview(voiceId)
}

/** Stops any currently loading or playing voice profile preview across all TTS engines. */
fun ModelManagerViewModel.stopVoicePreview() {
  voicePreviewController.stopVoicePreview()
}

/**
 * Stops a voice preview that is loading or playing, for example when the app leaves the foreground.
 * Unlike [stopVoicePreview], does nothing when no preview is active, so it never creates the TTS
 * engines just to stop them.
 */
fun ModelManagerViewModel.stopActiveVoicePreview() {
  voicePreviewController.stopActiveVoicePreview()
}

/**
 * Starts or stops a sample audio preview that speaks [previewText] in [languageCode] (a BCP-47 tag)
 * with [voiceId] on [model], using any registered [TtsOutput.DirectPlayback] or
 * [TtsOutput.AudioStream] TTS engine.
 */
fun ModelManagerViewModel.toggleVoicePreview(
  model: Model,
  voiceId: String,
  previewText: String,
  languageCode: String = DEFAULT_TTS_LANGUAGE_CODE,
) {
  voicePreviewController.toggleVoicePreview(model, voiceId, previewText, languageCode)
}

private suspend fun awaitAudioTrackDrain(
  audioTrack: AudioTrack,
  targetFrames: Long,
  isCancelled: () -> Boolean,
  playbackHeadProvider: (AudioTrack) -> Long,
) {
  var lastHead = playbackHeadProvider(audioTrack)
  var stalledMs = 0L
  while (lastHead < targetFrames && !isCancelled()) {
    delay(DRAIN_POLL_INTERVAL_MS)
    val head = playbackHeadProvider(audioTrack)
    stalledMs = if (head > lastHead) 0L else stalledMs + DRAIN_POLL_INTERVAL_MS
    lastHead = head
    if (stalledMs >= DRAIN_STALL_TIMEOUT_MS) break
  }
}
