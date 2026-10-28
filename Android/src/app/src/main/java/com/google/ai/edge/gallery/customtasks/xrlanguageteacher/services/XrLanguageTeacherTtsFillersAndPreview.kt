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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.FillerVoiceKey
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MultilingualTtsSegment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PresynthesizedFiller
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.selectLiveFiller
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.selectPresynthesizedFiller
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.spokenphrases.ResolvedSpokenPhrases
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.spokenphrases.XrLanguageTeacherSpokenPhrases
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsQueueMode
import com.google.ai.edge.gallery.services.tts.TtsServiceError
import com.google.ai.edge.gallery.services.tts.TtsTextNormalizer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stops any in-flight or active persona preview and resets [PersonaPreviewState] to
 * [PersonaPreviewState.Idle].
 */
fun XrLanguageTeacherViewModel.stopPersonaPreview() {
  if (uiState.value.personaPreviewState !is PersonaPreviewState.Idle) {
    stopTtsPlayback()
  }
}

/**
 * Speaks [persona]'s sample line in [draftMainLanguage] (or in English when the selected TTS engine
 * has no voice for [draftMainLanguage]) so the user can hear its voice.
 *
 * Tapping the same persona while it is [PersonaPreviewState.Loading] or
 * [PersonaPreviewState.Playing] stops playback and resets to [PersonaPreviewState.Idle].
 */
fun XrLanguageTeacherViewModel.playPersonaPreview(
  persona: CharacterPersona,
  draftMainLanguage: Language = mainLanguage.value,
) {
  val currentPreview = uiState.value.personaPreviewState
  if (
    (currentPreview is PersonaPreviewState.Loading && currentPreview.persona == persona) ||
      (currentPreview is PersonaPreviewState.Playing && currentPreview.persona == persona)
  ) {
    stopPersonaPreview()
    return
  }
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  }
  val previousPlaybackJob = ttsPlaybackJob
  resetTtsPlaybackState()
  val spokenPhrases = resolveNativeSpokenPhrases(draftMainLanguage)
  val previewLine =
    XrLanguageTeacherSpokenPhrases.personaGreeting(spokenPhrases.languageTag, persona)
  val cleanSpoken = TtsTextNormalizer.cleanMarkdownForSpeech(previewLine, stripEmojis = true)
  if (cleanSpoken.isBlank()) {
    return
  }
  setPersonaPreviewState(PersonaPreviewState.Loading(persona))
  val playbackEpoch = ttsPlaybackEpoch.incrementAndGet()
  val pendingStopJob = ttsStopJob
  cancelFillerPresynthesis()
  ttsPlaybackJob =
    viewModelScope.launch(ioDispatcher) {
      val mlKitEngine = ttsServiceProvider.serviceOrNull(TtsModelIdentifier.ML_KIT)
      stopTtsEnginesAndFlushAudio(exceptEngine = mlKitEngine)
      previousPlaybackJob?.cancelAndJoin()
      pendingStopJob?.join()
      pcmAudioStreamPlayer.stopAndFlush()
      updateUiState {
        it.copy(isSpeakingTts = true, isSpeakingDialogue = false, isSpeakingVocabPhrase = false)
      }
      try {
        prepareTtsJob?.join()
        if (uiState.value.ttsReadiness == SpeechEngineReadiness.ERROR) {
          Log.d(TAG, "Skipping persona preview because the TTS engine failed")
          return@launch
        }
        val config =
          resolveSegmentTtsConfig(
            requestedLanguageCode = spokenPhrases.languageTag,
            persona = persona,
            queueMode = TtsQueueMode.FLUSH,
          )
        val service = prepareTtsServiceForConfig(config)
        if (service !== mlKitEngine) {
          mlKitEngine?.stop()
        }
        when (val output = service.synthesize(cleanSpoken, config)) {
          is TtsOutput.DirectPlayback -> {
            var directOutput: TtsOutput.DirectPlayback = output
            var retriedBeforeStart = false
            while (true) {
              var started = false
              try {
                collectDirectPlayback(
                  output = directOutput,
                  onStarted = {
                    started = true
                    if (ttsPlaybackEpoch.get() == playbackEpoch) {
                      setPersonaPreviewState(PersonaPreviewState.Playing(persona))
                      updateUiState { it.copy(activeSpokenText = previewLine) }
                    }
                  },
                )
                break
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (
                  started ||
                    retriedBeforeStart ||
                    ttsPlaybackEpoch.get() != playbackEpoch ||
                    e is TtsServiceError.VoiceNotInstalledYet
                ) {
                  throw e
                }
                retriedBeforeStart = true
                directOutput =
                  service.synthesize(cleanSpoken, config) as? TtsOutput.DirectPlayback ?: throw e
              }
            }
            publishActiveTtsEngine(config.modelId)
          }
          is TtsOutput.AudioStream -> {
            val pcmChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
            val streamJob =
              launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                  var emittedFirstChunk = false
                  output.flow.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                      if (!emittedFirstChunk) {
                        emittedFirstChunk = true
                        if (ttsPlaybackEpoch.get() == playbackEpoch) {
                          setPersonaPreviewState(PersonaPreviewState.Playing(persona))
                          updateUiState { it.copy(activeSpokenText = previewLine) }
                        }
                      }
                      val unused = pcmChannel.trySend(chunk)
                    }
                  }
                  pcmChannel.close()
                } catch (e: CancellationException) {
                  pcmChannel.close(e)
                  throw e
                } catch (e: Exception) {
                  pcmChannel.close(e)
                }
              }
            val wroteAudio = pcmAudioStreamPlayer.playStream(pcmFlow = pcmChannel.receiveAsFlow())
            streamJob.join()
            if (wroteAudio) {
              publishActiveTtsEngine(config.modelId)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A preview that fails only resets the preview button. It doesn't mark the engine as failed
        // or pause the lesson: the engine's own retries already ran out, and the next preview, the
        // greeting, and the lesson try the engine again.
        Log.w(TAG, "TTS persona preview failed for ${persona.displayName}", e)
      } finally {
        if (ttsPlaybackEpoch.get() == playbackEpoch) {
          withContext(NonCancellable) {
            pcmAudioStreamPlayer.stopAndFlush()
            setPersonaPreviewState(PersonaPreviewState.Idle)
            updateUiState {
              it.copy(
                isSpeakingTts = false,
                isSpeakingDialogue = false,
                isSpeakingVocabPhrase = false,
                activeSpokenText = "",
              )
            }
          }
        }
      }
    }
  ttsPlaybackJob?.invokeOnCompletion { schedulePresynthesizeFillers() }
}

// MARK: - Thinking fillers

internal data class ResolvedFillerVoice(
  val spokenPhrases: ResolvedSpokenPhrases,
  val config: TtsConfig,
)

/** The phrases and the TTS config the thinking filler speaks with for [nativeLanguage]. */
internal fun XrLanguageTeacherViewModel.resolveFillerVoice(
  nativeLanguage: Language,
  persona: CharacterPersona,
): ResolvedFillerVoice {
  val spokenPhrases = resolveNativeSpokenPhrases(nativeLanguage)
  val config = resolveSegmentTtsConfig(spokenPhrases.languageTag, persona, TtsQueueMode.FLUSH)
  return ResolvedFillerVoice(spokenPhrases = spokenPhrases, config = config)
}

internal fun TtsConfig.toFillerVoiceKey() =
  FillerVoiceKey(ttsModelId = modelId.orEmpty(), languageTag = languageCode, voiceId = voiceId)

/**
 * Returns the thinking filler segment for a lesson turn: a filler synthesized ahead of time when
 * there is one for the current voice ([selectPresynthesizedFiller]), else a phrase to synthesize
 * live ([selectLiveFiller]). Never the same as the previous turn's filler when there is a choice.
 */
internal fun XrLanguageTeacherViewModel.buildThinkingFillerSegment(
  nativeLanguage: Language,
  persona: CharacterPersona,
): MultilingualTtsSegment? {
  val (spokenPhrases, config) = resolveFillerVoice(nativeLanguage, persona)
  val presynthesized =
    selectPresynthesizedFiller(
      presynthesizedFillers.get(config.toFillerVoiceKey()),
      previousText = lastFillerText,
    )
  val text =
    presynthesized?.text
      ?: selectLiveFiller(spokenPhrases.phrases.fillers, previousText = lastFillerText)
      ?: return null
  lastFillerText = text
  return MultilingualTtsSegment(
    text = text,
    languageCode = spokenPhrases.languageTag,
    isTargetLanguage = false,
    isFiller = true,
    presynthesizedFiller = presynthesized,
  )
}

/**
 * Whether the teacher is idle enough to synthesize thinking fillers in the background: the model is
 * initialized and not inferring, the TTS engine is ready and not speaking, and nothing records.
 */
internal fun XrLanguageTeacherViewModel.isIdleForFillerPresynthesis(): Boolean {
  val state = uiState.value
  return state.ttsReadiness == SpeechEngineReadiness.READY &&
    !state.isRunningInference &&
    !state.isSubmittingToModel &&
    !state.isExtractingCard &&
    !state.isSpeakingTts &&
    !state.isRecordingAsr &&
    !hudController.pendingGreeting &&
    // Pass 2 keeps decoding after its job returns, and after isExtractingCard clears on a null
    // card.
    !isPass2InFlight &&
    // These jobs only cover the inference set-up; the flags above cover native generation.
    activeInferenceJob?.isActive != true &&
    activePass2Job?.isActive != true &&
    activePrewarmJob?.isActive != true &&
    ttsPlaybackJob?.isActive != true &&
    latestKnownModel?.instance != null
}

/**
 * Cancels the thinking filler synthesis in progress, and stops the engine's synthesis too, since
 * cancelling the job alone only takes effect between native calls. Called when the teacher stops
 * being idle: a turn, a playback, a prewarm or a recording starts.
 */
internal fun XrLanguageTeacherViewModel.cancelFillerPresynthesis() {
  val job = fillerPresynthesisJob ?: return
  // The engine the job synthesizes with, which is not the selected one after a TTS model change.
  val service = fillerPresynthesisService
  fillerPresynthesisJob = null
  fillerPresynthesisService = null
  if (!job.isActive) return
  job.cancel()
  try {
    service?.stop()
  } catch (e: Exception) {
    Log.w(TAG, "Error stopping the thinking filler synthesis", e)
  }
}

/**
 * Tries [presynthesizeFillersIfIdle] soon, for example once a job that kept the teacher busy ends.
 */
internal fun XrLanguageTeacherViewModel.schedulePresynthesizeFillers() {
  viewModelScope.launch { presynthesizeFillersIfIdle() }
}

/**
 * Synthesizes the thinking fillers of the current TTS model, native language and persona into
 * memory while the teacher is idle ([isIdleForFillerPresynthesis]), so that a lesson turn can play
 * one the moment it is submitted.
 *
 * Uses only the engine already prepared and warmed for the filler's voice, and never prepares it.
 * Stops, keeping nothing, as soon as the teacher is no longer idle or the voice changes. If a
 * phrase fails, the phrases synthesized so far are kept. Engines that play audio themselves
 * ([TtsOutput.DirectPlayback]) can't be synthesized ahead of time; that is remembered until the
 * voice changes.
 */
internal fun XrLanguageTeacherViewModel.presynthesizeFillersIfIdle() {
  if (fillerPresynthesisJob?.isActive == true || !isIdleForFillerPresynthesis()) return
  val persona = selectedPersona.value
  val nativeLanguage = mainLanguage.value
  val (spokenPhrases, config) = resolveFillerVoice(nativeLanguage, persona)
  val key = config.toFillerVoiceKey()
  if (presynthesizedFillers.get(key).isNotEmpty() || presynthesizedFillers.isUnsupported(key)) {
    return
  }
  val service = ttsServiceProvider.serviceOrNull(forModelId = key.ttsModelId) ?: return
  if (!preparedTtsKeys.isPrepared(config, service.status.value.isEngineLoaded)) return
  fillerPresynthesisService = service
  fillerPresynthesisJob =
    viewModelScope.launch(ioDispatcher) {
      fun isStillWanted(): Boolean =
        isIdleForFillerPresynthesis() &&
          resolveFillerVoice(mainLanguage.value, selectedPersona.value).config.toFillerVoiceKey() ==
            key
      val fillers = mutableListOf<PresynthesizedFiller>()
      for (phrase in spokenPhrases.phrases.fillers) {
        if (!isStillWanted()) return@launch
        val cleanSpoken = TtsTextNormalizer.cleanMarkdownForSpeech(phrase, stripEmojis = true)
        if (cleanSpoken.isBlank()) continue
        val pcm = ByteArrayOutputStream()
        try {
          val stream = service.synthesize(cleanSpoken, config) as? TtsOutput.AudioStream
          if (stream == null) {
            Log.d(TAG, "The TTS engine '${key.ttsModelId}' can't synthesize fillers ahead of time")
            presynthesizedFillers.markUnsupported(key)
            return@launch
          }
          stream.flow.collect { chunk -> pcm.write(chunk) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Synthesizing the thinking filler '$phrase' ahead of time failed", e)
          break
        }
        if (pcm.size() > 0) {
          fillers += PresynthesizedFiller(text = phrase, pcm = pcm.toByteArray())
        }
      }
      if (fillers.isNotEmpty() && isStillWanted()) {
        presynthesizedFillers.put(key, fillers)
        Log.d(
          TAG,
          "Synthesized ${fillers.size} thinking fillers for $key ahead of time, durations (ms): " +
            fillers.joinToString { it.durationMs.toString() },
        )
      }
    }
}
