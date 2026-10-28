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
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.DialogueSegment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MultilingualTtsSegment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TtsPlaybackSource
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toLocale
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsPlaybackEvent
import com.google.ai.edge.gallery.services.tts.TtsQueueMode
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceError
import com.google.ai.edge.gallery.services.tts.TtsTextNormalizer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaks the vocabulary [phrase] (a word or example sentence) in [targetLanguage], and publishes
 * its [PhrasePlaybackState] so that its speaker button shows a spinner until the audio starts.
 * Tapping the same phrase while it loads or plays stops it instead of starting it again.
 */
fun XrLanguageTeacherViewModel.playTargetPhrase(
  phrase: String,
  targetLanguage: Language = learnLanguage.value,
) {
  val cleanPhrase =
    TtsTextNormalizer.stripEmojis(XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(phrase))
  if (cleanPhrase.isBlank()) return
  val current = uiState.value.phrasePlaybackState
  if (
    (current is PhrasePlaybackState.Loading && current.phrase == phrase) ||
      (current is PhrasePlaybackState.Playing && current.phrase == phrase)
  ) {
    stopTtsPlayback()
    return
  }
  val segments =
    listOf(
      MultilingualTtsSegment(
        text = cleanPhrase,
        languageCode = targetLanguage.toLocale().toLanguageTag(),
        isTargetLanguage = true,
      )
    )
  speakSegments(segments, selectedPersona.value, source = TtsPlaybackSource.VOCAB_PHRASE)
  // After speakSegments(), which resets the playback state of the previous playback.
  updateUiState { it.copy(phrasePlaybackState = PhrasePlaybackState.Loading(phrase)) }
}

/** Marks the loading vocabulary phrase as playing, once its audio starts. */
private fun XrLanguageTeacherViewModel.markPhrasePlaybackStarted() {
  updateUiState { state ->
    val loading =
      state.phrasePlaybackState as? PhrasePlaybackState.Loading ?: return@updateUiState state
    state.copy(phrasePlaybackState = PhrasePlaybackState.Playing(loading.phrase))
  }
}

fun XrLanguageTeacherViewModel.playBilingualDialogue(
  dialogueWithTargetTags: String,
  hostLanguage: Language = mainLanguage.value,
  targetLanguage: Language = learnLanguage.value,
  persona: CharacterPersona = selectedPersona.value,
) {
  val segments =
    XrLanguageTeacherDialogueFormatter.parseMultilingualSegments(
      text = dialogueWithTargetTags,
      hostLocaleCode = hostLanguage.toLocale().toLanguageTag(),
      targetLocaleCode = targetLanguage.toLocale().toLanguageTag(),
    )
  if (segments.isEmpty()) return
  speakSegments(segments, persona, source = TtsPlaybackSource.DIALOGUE)
}

internal fun XrLanguageTeacherViewModel.playBilingualDialogueSegments(
  segments: List<DialogueSegment>,
  hostLanguage: Language,
  targetLanguage: Language,
  persona: CharacterPersona = selectedPersona.value,
) {
  val ttsSegments =
    XrLanguageTeacherDialogueFormatter.dialogueSegmentsToMultilingualTtsSegments(
      segments = segments,
      hostLocaleCode = hostLanguage.toLocale().toLanguageTag(),
      targetLocaleCode = targetLanguage.toLocale().toLanguageTag(),
    )
  if (ttsSegments.isEmpty()) return
  speakSegments(ttsSegments, persona, source = TtsPlaybackSource.DIALOGUE)
}

/** Starts a streaming TTS session where sentences can be enqueued progressively. */
internal fun XrLanguageTeacherViewModel.startStreamingTts(
  persona: CharacterPersona = selectedPersona.value
) {
  val channel = Channel<MultilingualTtsSegment>(capacity = 64)
  launchTtsPlayback(persona, channel, source = TtsPlaybackSource.DIALOGUE) {
    streamPipelinedSegments(channel.receiveAsFlow(), persona)
  }
}

internal fun XrLanguageTeacherViewModel.isStreamingTtsActive(): Boolean =
  ttsChannel != null && ttsPlaybackJob?.isActive == true

/** Enqueues a sentence into the active streaming TTS session. */
internal fun XrLanguageTeacherViewModel.enqueueTtsSentence(
  sentenceText: String,
  hostLanguage: Language,
  targetLanguage: Language,
  targetWord: String? = null,
) {
  val sanitized = XrLanguageTeacherDialogueFormatter.normalizeAndSanitizeTargetTags(sentenceText)
  val normalizedSentence =
    if (!targetWord.isNullOrBlank()) {
      XrLanguageTeacherDialogueFormatter.wrapPhraseWithTargetInDialogue(
          dialogue = sanitized,
          phrase = targetWord,
          onlyIfUntagged = true,
        )
        .first
    } else {
      sanitized
    }
  val segments =
    XrLanguageTeacherDialogueFormatter.parseMultilingualSegments(
      text = normalizedSentence,
      hostLocaleCode = hostLanguage.toLocale().toLanguageTag(),
      targetLocaleCode = targetLanguage.toLocale().toLanguageTag(),
    )
  for (seg in segments) {
    if (seg.text.isNotBlank()) {
      ttsChannel?.trySend(seg)
    }
  }
}

/** Closes the active streaming TTS session when all sentences have been enqueued. */
internal fun XrLanguageTeacherViewModel.finishStreamingTts() {
  ttsChannel?.close()
}

internal fun XrLanguageTeacherViewModel.speakSegments(
  segments: List<MultilingualTtsSegment>,
  persona: CharacterPersona,
  source: TtsPlaybackSource = TtsPlaybackSource.DIALOGUE,
) {
  launchTtsPlayback(persona, source = source) {
    streamPipelinedSegments(segments.asFlow(), persona)
  }
}

internal fun XrLanguageTeacherViewModel.launchTtsPlayback(
  persona: CharacterPersona,
  channel: Channel<MultilingualTtsSegment>? = null,
  source: TtsPlaybackSource = TtsPlaybackSource.DIALOGUE,
  block: suspend () -> Unit,
) {
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  }
  val previousPlaybackJob = ttsPlaybackJob
  resetTtsPlaybackState()
  val playbackEpoch = ttsPlaybackEpoch.incrementAndGet()
  val pendingStopJob = ttsStopJob
  cancelFillerPresynthesis()
  ttsChannel = channel
  ttsPlaybackJob =
    viewModelScope.launch(ioDispatcher) {
      // Tear down the previous turn, and wait for any pending stop, before this one speaks, so an
      // engine stop() cannot land after (and flush) this turn's first queued utterance.
      previousPlaybackJob?.cancelAndJoin()
      pendingStopJob?.join()
      stopTtsEnginesAndFlushAudio()
      updateUiState {
        it.copy(
          isSpeakingTts = true,
          isSpeakingDialogue = source == TtsPlaybackSource.DIALOGUE,
          isSpeakingVocabPhrase = source == TtsPlaybackSource.VOCAB_PHRASE,
        )
      }
      try {
        prepareTtsJob?.join()
        if (uiState.value.ttsReadiness == SpeechEngineReadiness.ERROR) {
          // The selected engine failed and there is no fallback, so stay silent until it works.
          Log.d(TAG, "Skipping TTS playback because the TTS engine failed")
          return@launch
        }
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "TTS playback failed for ${persona.displayName}", e)
        if (ttsPlaybackEpoch.get() == playbackEpoch) {
          reportTtsEngineFailure(SpeechEngineErrorCause.OTHER)
        }
      } finally {
        if (ttsPlaybackEpoch.get() == playbackEpoch) {
          withContext(NonCancellable) {
            pcmAudioStreamPlayer.stopAndFlush()
            updateUiState {
              it.copy(
                isSpeakingTts = false,
                isSpeakingDialogue = false,
                isSpeakingVocabPhrase = false,
                activeSpokenText = "",
                phrasePlaybackState = PhrasePlaybackState.Idle,
              )
            }
          }
        }
      }
    }
  // Synthesize the thinking fillers once the teacher stops speaking.
  ttsPlaybackJob?.invokeOnCompletion { schedulePresynthesizeFillers() }
}

private sealed interface PreparedTtsSegment {
  val rawText: String
  val modelId: String?
  /** Whether this is the thinking filler, whose audio doesn't count as the reply speaking. */
  val isFiller: Boolean

  data class Stream(
    override val rawText: String,
    override val modelId: String?,
    override val isFiller: Boolean,
    val pcmChannel: Channel<ByteArray>,
    val playbackDone: CompletableDeferred<Unit> = CompletableDeferred(),
  ) : PreparedTtsSegment

  data class Direct(
    override val rawText: String,
    override val modelId: String?,
    override val isFiller: Boolean,
    val completionJob: Job,
  ) : PreparedTtsSegment
}

/**
 * Collects [output]'s playback events and calls [onStarted] when playback starts. Failures are
 * rethrown; the caller retries a segment once if it fails before playback starts.
 */
internal suspend fun collectDirectPlayback(
  output: TtsOutput.DirectPlayback,
  onStarted: () -> Unit,
) {
  output.statusFlow.collect { event ->
    if (event == TtsPlaybackEvent.PLAYBACK_STARTED) {
      onStarted()
    }
  }
}

/**
 * Executes a 2-stage producer-consumer synthesis/playback pipeline across [segmentsFlow]:
 * - **Stage 1 (Background Lookahead Producer)**: Resolves each segment's locale/voice config and
 *   starts synthesizing immediately in FIFO order via `CoroutineStart.UNDISPATCHED` without waiting
 *   for earlier segments' audio hardware playback to drain whenever consecutive segments share the
 *   same backing engine:
 *     - For [TtsOutput.AudioStream], pre-buffers synthesized PCM chunks into an in-memory channel
 *       while the active segment plays out through the speaker.
 *     - For [TtsOutput.DirectPlayback], enqueues consecutive same-engine segments (across both
 *       same-language and cross-language segments) with [TtsQueueMode.ADD] as soon as the previous
 *       segment starts playing, so voice loading and synthesis overlap with the active utterance,
 *       while draining any prior active utterance first only on cross-engine transitions.
 * - **Stage 2 (Single Ordered Consumer)**: Streams buffered PCM into a single uninterrupted
 *   [PcmAudioStreamPlayer] track or awaits direct-playback completion in strict FIFO order.
 */
internal suspend fun XrLanguageTeacherViewModel.streamPipelinedSegments(
  segmentsFlow: Flow<MultilingualTtsSegment>,
  persona: CharacterPersona,
) = coroutineScope {
  val preparedQueue = Channel<PreparedTtsSegment>(capacity = Channel.UNLIMITED)
  // Whether a reply segment (not the filler) played any audio, and the first reply segment failure.
  // If no reply segment played and one failed, the engine is likely broken, so it is reported.
  val replyPlayedAudio = AtomicBoolean(false)
  val firstReplyFailure = AtomicReference<Exception?>(null)
  val producerJob =
    launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        var previousSegmentModelId: String? = null
        var previousDirectModelId: String? = null
        // Completes once the previous DirectPlayback segment starts playing or ends.
        var previousDirectStarted: Deferred<Unit>? = null
        val activeEngineJobs = CopyOnWriteArrayList<Job>()
        segmentsFlow.collect { segment ->
          val cleanSpoken =
            TtsTextNormalizer.cleanMarkdownForSpeech(segment.text, stripEmojis = true)
          if (cleanSpoken.isBlank()) return@collect
          fun recordFailure(e: Exception) {
            if (!segment.isFiller) {
              firstReplyFailure.compareAndSet(null, e)
            }
          }
          val presynthesizedFiller = segment.presynthesizedFiller
          val baseConfig =
            resolveSegmentTtsConfig(segment.languageCode, persona, TtsQueueMode.FLUSH)
          val canPipelineOnSameDirectEngine =
            previousDirectModelId != null && previousDirectModelId == baseConfig.modelId
          val config =
            if (canPipelineOnSameDirectEngine) {
              baseConfig.copy(queueMode = TtsQueueMode.ADD)
            } else {
              baseConfig
            }
          val switchedModel =
            previousSegmentModelId != null && previousSegmentModelId != config.modelId
          if (presynthesizedFiller != null) {
            // The filler's audio is ready, so it plays without preparing or synthesizing.
            previousDirectModelId = null
            previousDirectStarted = null
            previousSegmentModelId = config.modelId
            if (activeEngineJobs.isEmpty()) {
              updateUiState { it.copy(activeSpokenText = segment.text) }
            }
            // Written in small slices, like a live stream, so a stop doesn't wait behind one long
            // blocking write.
            val pcmChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
            for (slice in presynthesizedFiller.pcmSlices()) {
              val unusedSend = pcmChannel.trySend(slice)
            }
            pcmChannel.close()
            val playbackDone = CompletableDeferred<Unit>()
            activeEngineJobs.add(playbackDone)
            playbackDone.invokeOnCompletion { activeEngineJobs.remove(playbackDone) }
            val unused =
              preparedQueue.trySend(
                PreparedTtsSegment.Stream(
                  rawText = segment.text,
                  modelId = config.modelId,
                  isFiller = true,
                  pcmChannel = pcmChannel,
                  playbackDone = playbackDone,
                )
              )
            return@collect
          }
          // Pre-warm the upcoming segment's locale and voice while prior utterances drain, so that
          // language switches skip the cold lookup. This doesn't change the utterance that is
          // speaking.
          val service =
            try {
              try {
                prepareTtsServiceForConfig(baseConfig)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                // An unknown model won't appear by preparing again.
                if (e is TtsServiceError.ModelNotFound) throw e
                Log.w(
                  TAG,
                  "TTS segment preparation failed (model=${baseConfig.modelId}); preparing again",
                  e,
                )
                prepareTtsServiceForConfig(baseConfig, forceReprepare = true)
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              // Skip only this segment so that the rest of the turn still plays. The failure is
              // reported at the end if no reply segment of the turn played.
              Log.w(
                TAG,
                "TTS segment preparation failed (model=${baseConfig.modelId}," +
                  " lang=${baseConfig.languageCode}, voice=${baseConfig.voiceId}); skipping it",
                e,
              )
              recordFailure(e)
              return@collect
            }
          when {
            switchedModel && activeEngineJobs.isNotEmpty() -> {
              for (priorJob in activeEngineJobs.toList()) {
                priorJob.join()
              }
              activeEngineJobs.clear()
            }
            canPipelineOnSameDirectEngine -> {
              // Queue behind the previous segment only once it has started, so that this ADD
              // can't reach the engine before the previous segment's request.
              previousDirectStarted?.await()
            }
          }
          if (activeEngineJobs.isEmpty()) {
            updateUiState { it.copy(activeSpokenText = segment.text) }
          }
          previousSegmentModelId = config.modelId
          val retries = SegmentRetries()
          val initialOutput =
            try {
              service.synthesize(cleanSpoken, config)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              val retry = retries.takeRetryOfFirstAttempt(service, e)
              val retried =
                if (retry != null) {
                  retrySegmentSynthesis(service, baseConfig, config, cleanSpoken, e, retry)
                } else {
                  null
                }
              if (retried == null) recordFailure(e)
              retried
            }
          if (initialOutput == null) return@collect
          when (initialOutput) {
            is TtsOutput.AudioStream -> {
              previousDirectModelId = null
              previousDirectStarted = null
              val pcmChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
              val playbackDone = CompletableDeferred<Unit>()
              val unused =
                preparedQueue.trySend(
                  PreparedTtsSegment.Stream(
                    rawText = segment.text,
                    modelId = config.modelId,
                    isFiller = segment.isFiller,
                    pcmChannel = pcmChannel,
                    playbackDone = playbackDone,
                  )
                )
              val streamJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                  var output: TtsOutput = initialOutput
                  try {
                    while (true) {
                      val stream = output as? TtsOutput.AudioStream ?: break
                      var emittedAudio = false
                      try {
                        stream.flow.collect { chunk ->
                          if (chunk.isNotEmpty()) {
                            emittedAudio = true
                            val unusedSend = pcmChannel.trySend(chunk)
                          }
                        }
                        break
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        val retry = if (emittedAudio) null else retries.takeRetry(service, e)
                        if (retry == null) {
                          // End only this segment: it already played audio, or has no retry left.
                          Log.w(
                            TAG,
                            "TTS segment failed (model=${config.modelId}," +
                              " lang=${config.languageCode}, chars=${cleanSpoken.length})," +
                              " midStream=$emittedAudio; ending it",
                            e,
                          )
                          recordFailure(e)
                          break
                        }
                        val retried =
                          retrySegmentSynthesis(service, baseConfig, config, cleanSpoken, e, retry)
                        if (retried == null) {
                          recordFailure(e)
                          break
                        }
                        output = retried
                      }
                    }
                    pcmChannel.close()
                  } catch (e: CancellationException) {
                    pcmChannel.close(e)
                    throw e
                  }
                }
              activeEngineJobs.add(streamJob)
              activeEngineJobs.add(playbackDone)
              streamJob.invokeOnCompletion { activeEngineJobs.remove(streamJob) }
              playbackDone.invokeOnCompletion { activeEngineJobs.remove(playbackDone) }
            }
            is TtsOutput.DirectPlayback -> {
              previousDirectModelId = config.modelId
              val started = CompletableDeferred<Unit>()
              previousDirectStarted = started
              val completionJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                  var output: TtsOutput = initialOutput
                  try {
                    while (true) {
                      val direct = output as? TtsOutput.DirectPlayback ?: break
                      var playbackStarted = false
                      try {
                        collectDirectPlayback(
                          output = direct,
                          onStarted = {
                            playbackStarted = true
                            started.complete(Unit)
                            updateUiState { it.copy(activeSpokenText = segment.text) }
                            markPhrasePlaybackStarted()
                          },
                        )
                        // The engine played the utterance to its end.
                        if (!segment.isFiller) {
                          replyPlayedAudio.set(true)
                        }
                        break
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        val retry = if (playbackStarted) null else retries.takeRetry(service, e)
                        if (retry == null) {
                          // Contain the failure to this segment. Letting it escape would cancel
                          // this scope, and the cancelled sibling flows would stop the engine,
                          // dropping every segment still queued in this turn.
                          Log.w(
                            TAG,
                            "TTS segment failed (model=${config.modelId}," +
                              " lang=${config.languageCode}, voice=${config.voiceId}," +
                              " chars=${cleanSpoken.length}); skipping it",
                            e,
                          )
                          if (playbackStarted && !segment.isFiller) {
                            replyPlayedAudio.set(true)
                          }
                          recordFailure(e)
                          break
                        }
                        val retried =
                          retrySegmentSynthesis(service, baseConfig, config, cleanSpoken, e, retry)
                        if (retried == null) {
                          recordFailure(e)
                          break
                        }
                        output = retried
                      }
                    }
                  } finally {
                    started.complete(Unit)
                  }
                }
              activeEngineJobs.add(completionJob)
              completionJob.invokeOnCompletion { activeEngineJobs.remove(completionJob) }
              val unused =
                preparedQueue.trySend(
                  PreparedTtsSegment.Direct(
                    rawText = segment.text,
                    modelId = config.modelId,
                    isFiller = segment.isFiller,
                    completionJob = completionJob,
                  )
                )
            }
          }
        }
      } finally {
        preparedQueue.close()
      }
    }

  var usedStreamingTrack = false
  for (prepared in preparedQueue) {
    when (prepared) {
      is PreparedTtsSegment.Stream -> {
        updateUiState { it.copy(activeSpokenText = prepared.rawText) }
        markPhrasePlaybackStarted()
        try {
          val wroteAudio =
            pcmAudioStreamPlayer.playStream(pcmFlow = prepared.pcmChannel.receiveAsFlow())
          if (wroteAudio) {
            usedStreamingTrack = true
            publishActiveTtsEngine(prepared.modelId)
            if (!prepared.isFiller) {
              replyPlayedAudio.set(true)
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Contain an audio output failure to this segment so the rest of the turn still plays.
          // The player dropped a broken track, so the next segment gets a new one.
          Log.w(TAG, "Playing a TTS segment failed; skipping it", e)
          // Nobody reads this segment's audio anymore, so stop collecting it.
          prepared.pcmChannel.cancel()
          if (!prepared.isFiller) {
            firstReplyFailure.compareAndSet(null, e)
          }
        } finally {
          prepared.playbackDone.complete(Unit)
        }
      }
      is PreparedTtsSegment.Direct -> {
        if (usedStreamingTrack) {
          pcmAudioStreamPlayer.finishStream()
          usedStreamingTrack = false
        }
        pcmAudioStreamPlayer.stopAndFlush()
        publishActiveTtsEngine(prepared.modelId)
        prepared.completionJob.join()
      }
    }
  }
  producerJob.join()
  if (usedStreamingTrack) {
    pcmAudioStreamPlayer.finishStream()
  }
  // No reply segment played, so the engine itself is likely broken: report it.
  if (!replyPlayedAudio.get()) {
    firstReplyFailure.get()?.let { throw it }
  }
}

/** Records the engine that spoke, for the HUD. */
internal fun XrLanguageTeacherViewModel.publishActiveTtsEngine(modelID: String?) {
  val engine = ttsSpeechEngineFor(modelID) ?: return
  updateUiState { it.copy(ttsEngine = engine) }
}

/**
 * Stops TTS playback. The engine stop runs in the background as [ttsStopJob], which the next
 * playback waits for before it speaks.
 */
fun XrLanguageTeacherViewModel.stopTtsPlayback() {
  val previousPlaybackJob = ttsPlaybackJob
  val previousStopJob = ttsStopJob
  resetTtsPlaybackState()
  // Abort native synthesis right away: cancelling the playback job only takes effect between native
  // calls. The stop job below stops the engines again once that job has returned.
  try {
    ttsServiceProvider.stopAll()
  } catch (e: Exception) {
    Log.w(TAG, "Error stopping TtsService", e)
  }
  ttsStopJob =
    applicationScope.launch(ioDispatcher) {
      previousPlaybackJob?.cancelAndJoin()
      previousStopJob?.join()
      stopTtsEnginesAndFlushAudio()
    }
}

/** Releases the audio track once the pending [stopTtsPlayback] finishes, when the screen closes. */
internal fun XrLanguageTeacherViewModel.releaseTtsAudioAfterStop() {
  val pendingStopJob = ttsStopJob
  applicationScope.launch(ioDispatcher) {
    pendingStopJob?.join()
    pcmAudioStreamPlayer.release()
  }
}

internal fun XrLanguageTeacherViewModel.resetTtsPlaybackState() {
  ttsPlaybackEpoch.incrementAndGet()
  ttsChannel?.close()
  ttsChannel = null
  ttsPlaybackJob?.cancel()
  ttsPlaybackJob = null
  setPersonaPreviewState(PersonaPreviewState.Idle)
  updateUiState {
    it.copy(
      isSpeakingTts = false,
      isSpeakingDialogue = false,
      isSpeakingVocabPhrase = false,
      activeSpokenText = "",
      phrasePlaybackState = PhrasePlaybackState.Idle,
    )
  }
}

internal suspend fun XrLanguageTeacherViewModel.stopTtsEnginesAndFlushAudio(
  exceptEngine: TtsService? = null
) {
  try {
    ttsServiceProvider.stopAllExcept(exceptEngine)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Error stopping TtsService", e)
  }
  pcmAudioStreamPlayer.stopAndFlush()
}
