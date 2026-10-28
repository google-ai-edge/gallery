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

package com.google.ai.edge.gallery.services.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.RemoteException
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.VoiceInfo
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGMlKitTtsEngine"

internal val speechAudioAttributes: AudioAttributes =
  AudioAttributes.Builder()
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .build()

/** The outcome of one warm-up utterance; see [warmUpVoice]. */
private enum class WarmUpResult {
  DONE,
  VOICE_NOT_LISTED,
  INCOMPLETE,
}

/**
 * Speaks [MlKitTtsEngine.VOICE_WARM_UP_TEXT] with [voice] once per [locale] and voice, so that the
 * TTS engine has loaded the voice before the first real utterance.
 *
 * If the warm-up fails with `ERROR_NOT_INSTALLED_YET`, it retries after each of
 * [MlKitTtsEngine.VOICE_WARM_UP_RETRY_DELAYS_MS]. If it still fails after the last retry, the
 * warm-up only logs a warning and doesn't throw; the real utterance then reports
 * [TtsServiceError.VoiceNotInstalledYet] if the voice is still missing. Other failures are only
 * logged too. In every case except success the voice isn't marked as warmed up, so the next
 * [MlKitTtsEngine.prepare] warms it up again.
 *
 * Skipped while an utterance plays, because the engine is running then and a queued warm-up would
 * only delay the next utterance.
 */
internal suspend fun MlKitTtsEngine.warmUpVoice(locale: Locale, voice: VoiceInfo) {
  val key = MlKitTtsEngine.WarmedVoice(locale, voice.name)
  if (key in warmedUpVoices) return
  val synthClient = acquireSynthesizer(locale)
  val startGeneration = lifecycleGeneration.get()
  fun checkGeneration() {
    if (lifecycleGeneration.get() != startGeneration) {
      throw CancellationException("Engine was destroyed during voice warm-up")
    }
  }

  voiceWarmUpMutex.withLock {
    if (key in warmedUpVoices) return
    var retry = 0
    while (activeCompletions.isEmpty()) {
      when (speakWarmUpUtterance(synthClient, voice)) {
        WarmUpResult.DONE -> {
          checkGeneration()
          warmedUpVoices += key
          return
        }
        WarmUpResult.VOICE_NOT_LISTED -> {
          val delayMs = MlKitTtsEngine.VOICE_WARM_UP_RETRY_DELAYS_MS.getOrNull(retry)
          if (delayMs == null) {
            Log.w(
              TAG,
              "Warm-up of voice ${voice.name} for ${locale.toLanguageTag()} did not complete" +
                " after ${MlKitTtsEngine.VOICE_WARM_UP_RETRY_DELAYS_MS.size} retries",
            )
            return
          }
          retry += 1
          Log.w(
            TAG,
            "Retrying warm-up of voice ${voice.name} for ${locale.toLanguageTag()} in" +
              " $delayMs ms ($retry/${MlKitTtsEngine.VOICE_WARM_UP_RETRY_DELAYS_MS.size})",
          )
          checkGeneration()
          delay(delayMs)
          checkGeneration()
        }
        WarmUpResult.INCOMPLETE -> return
      }
    }
  }
}

/**
 * Speaks one warm-up utterance with [voice] and returns how it ended. Runs on
 * [MlKitTtsEngine.synthesisDispatcher] with [SpeechSynthesizerRequest.QueueMode.QUEUE_ADD], like
 * [MlKitTtsEngine.synthesize], so it never reorders or flushes queued utterances.
 */
private suspend fun MlKitTtsEngine.speakWarmUpUtterance(
  synthClient: MlKitSpeechSynthesizer,
  voice: VoiceInfo,
): WarmUpResult =
  try {
    withContext(synthesisDispatcher) {
      withTimeoutOrNull(MlKitTtsEngine.VOICE_WARM_UP_TIMEOUT_MS) {
        var result: WarmUpResult? = null
        val request =
          SpeechSynthesizerRequest(
            input = SpeechSynthesizerRequest.Input.Text(MlKitTtsEngine.VOICE_WARM_UP_TEXT, voice),
            audioAttributes = speechAudioAttributes,
            queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_ADD,
          )
        synthClient.synthesize(request).collect { response ->
          if (result != null) return@collect
          result =
            when (response) {
              is SpeechSynthesizerResponse.StatusUpdate ->
                when (response.status) {
                  SpeechSynthesizerResponse.Status.DONE -> WarmUpResult.DONE
                  SpeechSynthesizerResponse.Status.STOP -> WarmUpResult.INCOMPLETE
                  else -> null
                }
              is SpeechSynthesizerResponse.Error ->
                if (response.errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                  WarmUpResult.VOICE_NOT_LISTED
                } else {
                  Log.w(TAG, "Warm-up of voice ${voice.name} failed: ${response.errorCode}")
                  WarmUpResult.INCOMPLETE
                }
              is SpeechSynthesizerResponse.AudioAvailable -> null
            }
        }
        result ?: WarmUpResult.INCOMPLETE
      }
    } ?: WarmUpResult.INCOMPLETE
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Warm-up of voice ${voice.name} failed", e)
    WarmUpResult.INCOMPLETE
  }

/**
 * Retries [this] utterance up to [MlKitTtsEngine.SYNTHESIS_ERROR_MAX_RETRIES] times when it fails
 * with [TextToSpeech.ERROR_SYNTHESIS] before [TtsPlaybackEvent.PLAYBACK_STARTED].
 */
internal fun Flow<TtsPlaybackEvent>.retrySynthesisErrorBeforeStart(
  engine: MlKitTtsEngine,
  text: String,
  config: TtsConfig?,
): Flow<TtsPlaybackEvent> = flow {
  val stopsAtStart = engine.stopCount.get()
  var currentFlow = this@retrySynthesisErrorBeforeStart
  var retries = 0
  while (true) {
    var started = false
    var failure: TtsServiceError? = null
    try {
      currentFlow.collect { event ->
        if (event == TtsPlaybackEvent.PLAYBACK_STARTED) {
          started = true
        }
        emit(event)
      }
      return@flow
    } catch (e: TtsServiceError.SynthesisFailed) {
      if (
        !started &&
          e.errorCode == TextToSpeech.ERROR_SYNTHESIS &&
          retries < MlKitTtsEngine.SYNTHESIS_ERROR_MAX_RETRIES
      ) {
        failure = e
      } else {
        throw e
      }
    }
    val generationBeforeWait = engine.activeFlowGeneration.get()
    retries += 1
    Log.w(
      TAG,
      "ML Kit synthesis failed before audio started; retrying in" +
        " ${MlKitTtsEngine.SYNTHESIS_ERROR_RETRY_DELAY_MS} ms" +
        " ($retries/${MlKitTtsEngine.SYNTHESIS_ERROR_MAX_RETRIES})",
      failure,
    )
    delay(MlKitTtsEngine.SYNTHESIS_ERROR_RETRY_DELAY_MS)
    if (
      engine.stopCount.get() != stopsAtStart ||
        engine.activeFlowGeneration.get() != generationBeforeWait
    ) {
      return@flow
    }
    currentFlow = engine.synthesisAttempt(text, config)
  }
}

/** Synthesizes and plays [text] once; see [MlKitTtsEngine.synthesize]. */
internal fun MlKitTtsEngine.synthesisAttempt(
  text: String,
  config: TtsConfig?,
): Flow<TtsPlaybackEvent> {
  val targetLocale =
    config?.languageCode?.let(::resolveSupportedLocale)
      ?: MlKitTtsModels.normalizeLocale(currentLocale)
  val warmed =
    warmedLocales[targetLocale]
      ?: return callbackFlow {
        close(
          TtsServiceError.EngineUnavailable(
            "ML Kit TTS (${TtsModelIdentifier.ML_KIT}) not prepared for locale" +
              " ${targetLocale.toLanguageTag()}; call prepare() before synthesize()"
          )
        )
      }

  val synthClient = acquireSynthesizer(targetLocale)
  var voice = warmed.voice

  if (config?.voiceId != null && warmed.supportedVoices.isNotEmpty()) {
    findVoice(warmed.supportedVoices, config.voiceId)?.let { resolvedVoice ->
      voice = resolvedVoice
      warmed.voice = resolvedVoice
    }
  }

  val requestedQueueMode = config?.queueMode ?: TtsQueueMode.FLUSH
  val mlKitQueueMode =
    when (requestedQueueMode) {
      TtsQueueMode.FLUSH -> SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH
      TtsQueueMode.ADD -> SpeechSynthesizerRequest.QueueMode.QUEUE_ADD
    }
  val clauses =
    TtsTextNormalizer.splitIntoClauses(text).let { split ->
      if (split.size <= 1) listOf(text) else split
    }

  val flow = callbackFlow {
    val myFlowGeneration = activeFlowGeneration.incrementAndGet()
    if (requestedQueueMode == TtsQueueMode.FLUSH) {
      for ((gen, completion) in activeCompletions) {
        if (gen < myFlowGeneration && activeCompletions.remove(gen, completion)) {
          completion.invoke()
        }
      }
    }
    setEngineStatus(TtsServiceStatus.Synthesizing)
    currentText = text
    isPaused = false

    var synthJob: Job? = null
    var pcmBuffer = ByteArrayOutputStream()
    val clausesWithAudio = MutableList(clauses.size) { false }
    var segmentStartTimeMs = 0L
    var playedBytesOffset = 0
    val completion: () -> Unit = { close() }
    activeCompletions[myFlowGeneration] = completion

    onPauseCallback = {
      if (segmentStartTimeMs > 0L) {
        val elapsedMs = (clockMsProvider() - segmentStartTimeMs).coerceAtLeast(0L)
        val additionalBytes =
          (((elapsedMs * MlKitTtsEngine.PCM_SAMPLE_RATE_HZ) / 1000L) *
              MlKitTtsEngine.PCM_BYTES_PER_SAMPLE)
            .toInt()
        playedBytesOffset += (additionalBytes and 1.inv())
        segmentStartTimeMs = 0L
      }
    }

    fun buildRequest(
      clauseText: String,
      queueMode: Int = mlKitQueueMode,
    ): SpeechSynthesizerRequest =
      SpeechSynthesizerRequest(
        input = SpeechSynthesizerRequest.Input.Text(clauseText, voice),
        audioAttributes = speechAudioAttributes,
        queueMode = queueMode,
      )

    fun releaseBufferedPcm() {
      pcmBuffer.reset()
      pcmBuffer = ByteArrayOutputStream()
      clausesWithAudio.fill(false)
    }

    fun cleanUpGeneration() {
      activeCompletions.remove(myFlowGeneration)
      if (!isPaused) {
        releaseBufferedPcm()
      }
      if (warmedLocales.isNotEmpty() && activeCompletions.isEmpty()) {
        setEngineStatus(TtsServiceStatus.Ready)
      }
    }

    fun startSynthesizing(
      queueMode: Int = mlKitQueueMode,
      startClauseIndex: Int = 0,
      emitPlaybackStarted: Boolean = true,
    ) {
      synthJob?.cancel()
      if (startClauseIndex == 0) {
        releaseBufferedPcm()
        playedBytesOffset = 0
      }
      segmentStartTimeMs = 0L
      val remainingClauseCount = clauses.size - startClauseIndex
      synthJob =
        launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            var hasStartedPlayback = !emitPlaybackStarted
            var completedClauses = 0
            val clauseStartedGates = List(clauses.size) { CompletableDeferred<Unit>() }
            coroutineScope {
              for (clauseIndex in startClauseIndex until clauses.size) {
                val clauseText = clauses[clauseIndex]
                val clauseQueueMode =
                  if (clauseIndex == startClauseIndex) queueMode
                  else SpeechSynthesizerRequest.QueueMode.QUEUE_ADD
                launch(start = CoroutineStart.UNDISPATCHED) {
                  if (clauseIndex > startClauseIndex) {
                    clauseStartedGates[clauseIndex - 1].await()
                  }
                  synthClient.synthesize(buildRequest(clauseText, clauseQueueMode)).collect {
                    response ->
                    when (response) {
                      is SpeechSynthesizerResponse.StatusUpdate -> {
                        when (response.status) {
                          SpeechSynthesizerResponse.Status.START -> {
                            clauseStartedGates[clauseIndex].complete(Unit)
                            if (segmentStartTimeMs == 0L) {
                              segmentStartTimeMs = clockMsProvider()
                            }
                            if (!hasStartedPlayback) {
                              hasStartedPlayback = true
                              val unused = trySend(TtsPlaybackEvent.PLAYBACK_STARTED)
                            }
                          }
                          SpeechSynthesizerResponse.Status.DONE -> {
                            clauseStartedGates[clauseIndex].complete(Unit)
                            if (!isPaused && ++completedClauses == remainingClauseCount) {
                              val unused = trySend(TtsPlaybackEvent.PLAYBACK_COMPLETED)
                              cleanUpGeneration()
                              close()
                            }
                          }
                          SpeechSynthesizerResponse.Status.STOP -> {
                            if (!isPaused) {
                              cleanUpGeneration()
                              close()
                              synthJob?.cancel()
                            }
                          }
                        }
                      }
                      is SpeechSynthesizerResponse.Error -> {
                        if (!isPaused) {
                          // On ERROR_NOT_INSTALLED_YET (-9) before START the voice is marked
                          // cold again, so the next prepare() warms it up.
                          if (response.errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                            warmedUpVoices -= MlKitTtsEngine.WarmedVoice(targetLocale, voice.name)
                          }
                          Log.w(
                            TAG,
                            "ML Kit synthesis error ${response.errorCode}" +
                              " (locale=${targetLocale.toLanguageTag()}, voice=${voice.name}," +
                              " queueMode=$requestedQueueMode, started=${segmentStartTimeMs > 0L}," +
                              " msSinceClientCreated=${clockMsProvider() - synthesizerCreatedAtMs})",
                          )
                          val hadEnqueuedTrailingClause =
                            remainingClauseCount > 1 &&
                              clauseStartedGates[startClauseIndex].isCompleted
                          cleanUpGeneration()
                          if (hadEnqueuedTrailingClause && activeCompletions.isEmpty()) {
                            synthClient.stop()
                          }
                          close(synthesisErrorFor(response.errorCode, targetLocale, voice))
                          synthJob?.cancel()
                        }
                      }
                      is SpeechSynthesizerResponse.AudioAvailable -> {
                        clausesWithAudio[clauseIndex] = true
                        pcmBuffer.write(response.audio)
                      }
                    }
                  }
                }
              }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            if (!isPaused) {
              cleanUpGeneration()
              if (remainingClauseCount > 1 && activeCompletions.isEmpty()) {
                synthClient.stop()
              }
              close(synthesisFailureFor(e))
            }
          }
        }
    }

    resumeSynthesis = {
      val bufferedPcm = pcmBuffer.toByteArray()
      val alignedOffset = (playedBytesOffset.coerceAtLeast(0) and 1.inv())
      val firstMissingClauseIndex = clausesWithAudio.indexOfFirst { !it }
      if (bufferedPcm.isNotEmpty() && alignedOffset < bufferedPcm.size) {
        synthJob?.cancel()
        synthJob = launch {
          try {
            segmentStartTimeMs = clockMsProvider()
            val unusedStart = trySend(TtsPlaybackEvent.PLAYBACK_STARTED)
            val player = pcmPlayer ?: ::playRemainingPcmWithAudioTrack
            player(bufferedPcm, alignedOffset) { isPaused }
            if (!isPaused) {
              playedBytesOffset = (bufferedPcm.size and 1.inv())
              if (firstMissingClauseIndex < 0) {
                val unusedDone = trySend(TtsPlaybackEvent.PLAYBACK_COMPLETED)
                cleanUpGeneration()
                close()
              } else {
                startSynthesizing(
                  queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH,
                  startClauseIndex = firstMissingClauseIndex,
                  emitPlaybackStarted = false,
                )
              }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            if (!isPaused) {
              cleanUpGeneration()
              close(e)
            }
          }
        }
      } else {
        val resumeClauseIndex = if (firstMissingClauseIndex > 0) firstMissingClauseIndex else 0
        startSynthesizing(
          queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH,
          startClauseIndex = resumeClauseIndex,
        )
      }
    }

    startSynthesizing()

    awaitClose {
      synthJob?.cancel()
      if (!isPaused) {
        releaseBufferedPcm()
      }
      activeCompletions.remove(myFlowGeneration)
      if (activeFlowGeneration.get() == myFlowGeneration) {
        onPauseCallback = null
        resumeSynthesis = null
        isPaused = false
        currentText = null
      }
      cleanUpGeneration()
    }
  }

  // See the class KDoc: a single serial dispatcher keeps `speak()` order equal to collect order.
  return flow.flowOn(synthesisDispatcher)
}

/**
 * Fails with [TtsServiceError.DownloadStalled] when this download reports nothing for
 * [MlKitTtsEngine.DOWNLOAD_STALL_TIMEOUT_MS].
 *
 * Failing stops collecting the ML Kit download flow, which only cancels this app's progress stream:
 * Speech Services logs the cancelled call and keeps running the download.
 */
internal fun Flow<TtsDownloadProgress>.failWhenStalled(locale: Locale): Flow<TtsDownloadProgress> =
  channelFlow {
    fun launchStallWatchdog() = launch {
      delay(MlKitTtsEngine.DOWNLOAD_STALL_TIMEOUT_MS)
      Log.i(TAG, "The ML Kit voice download for $locale reported nothing; no longer waiting")
      throw TtsServiceError.DownloadStalled(locale.toLanguageTag())
    }

    var watchdog = launchStallWatchdog()
    this@failWhenStalled.collect { progress ->
      watchdog.cancel()
      send(progress)
      watchdog = launchStallWatchdog()
    }
    watchdog.cancel()
  }

private const val PCM_WRITE_CHUNK_BYTES = 4800

/**
 * Maps a [SpeechSynthesizerResponse.Error] code to a [TtsServiceError].
 *
 * Every code other than `ERROR_NOT_INSTALLED_YET` (-9) is a [TtsServiceError.SynthesisFailed] that
 * carries the code in [TtsServiceError.SynthesisFailed.errorCode], so callers can recover from
 * specific codes. Lost connections surface as exceptions instead; see [synthesisFailureFor].
 */
internal fun synthesisErrorFor(errorCode: Int, locale: Locale, voice: VoiceInfo): TtsServiceError =
  when (errorCode) {
    TextToSpeech.ERROR_NOT_INSTALLED_YET ->
      TtsServiceError.VoiceNotInstalledYet(locale.toLanguageTag())
    else ->
      TtsServiceError.SynthesisFailed(
        "ML Kit synthesis error: $errorCode" +
          " (locale=${locale.toLanguageTag()}, voice=${voice.name})",
        errorCode = errorCode,
      )
  }

/** Maps an exception thrown by the ML Kit synthesis flow to a [TtsServiceError]. */
internal fun synthesisFailureFor(e: Exception): TtsServiceError =
  when {
    e is TtsServiceError -> e
    e is RemoteException ->
      TtsServiceError.ServiceDisconnected(
        "Speech service binder died: ${e.javaClass.simpleName}",
        e,
      )
    else -> TtsServiceError.SynthesisFailed(e.message ?: e.javaClass.simpleName, e)
  }

internal suspend fun playRemainingPcmWithAudioTrack(
  pcmBytes: ByteArray,
  startOffset: Int,
  shouldStop: () -> Boolean,
) =
  withContext(Dispatchers.IO) {
    val alignedOffset = (startOffset.coerceAtLeast(0) and 1.inv())
    if (alignedOffset >= pcmBytes.size) return@withContext
    val minBufferSize =
      AudioTrack.getMinBufferSize(
          MlKitTtsEngine.PCM_SAMPLE_RATE_HZ,
          AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
        .coerceAtLeast(PCM_WRITE_CHUNK_BYTES)
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(speechAudioAttributes)
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(MlKitTtsEngine.PCM_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(minBufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .startOnFirstFrame()
    try {
      track.play()
      var offset = alignedOffset
      while (offset < pcmBytes.size && currentCoroutineContext().isActive && !shouldStop()) {
        val toWrite = minOf(PCM_WRITE_CHUNK_BYTES, pcmBytes.size - offset)
        val written = track.write(pcmBytes, offset, toWrite)
        if (written <= 0) break
        offset += written
      }
      val totalFramesWritten = (offset - alignedOffset) / MlKitTtsEngine.PCM_BYTES_PER_SAMPLE
      if (totalFramesWritten > 0 && currentCoroutineContext().isActive && !shouldStop()) {
        val expectedDurationMs =
          ((totalFramesWritten * 1000L) / MlKitTtsEngine.PCM_SAMPLE_RATE_HZ) + 250L
        val drainDeadlineMs = SystemClock.elapsedRealtime() + expectedDurationMs
        while (
          currentCoroutineContext().isActive &&
            !shouldStop() &&
            track.playbackHeadPosition in 0 until totalFramesWritten &&
            SystemClock.elapsedRealtime() < drainDeadlineMs
        ) {
          delay(15L)
        }
      }
    } finally {
      runCatching { track.stop() }
      runCatching { track.release() }
    }
  }

internal fun Flow<DownloadStatus>.toDownloadProgress(
  expectedTotalBytes: Long = 0L
): Flow<TtsDownloadProgress> {
  var totalBytes = expectedTotalBytes.coerceAtLeast(0L)
  return transform { downloadStatus ->
    when (downloadStatus) {
      is DownloadStatus.DownloadStarted -> {
        if (downloadStatus.bytesToDownload > 0L) {
          totalBytes = downloadStatus.bytesToDownload
        }
        emit(TtsDownloadProgress(downloadedBytes = 0L, totalBytes = totalBytes))
      }
      is DownloadStatus.DownloadProgress -> {
        val downloaded = downloadStatus.totalBytesDownloaded.coerceAtLeast(0L)
        if (downloaded > totalBytes) {
          totalBytes = downloaded
        }
        emit(TtsDownloadProgress(downloadedBytes = downloaded, totalBytes = totalBytes))
      }
      is DownloadStatus.DownloadCompleted -> {
        emit(TtsDownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes))
      }
      is DownloadStatus.DownloadFailed -> {
        throw downloadStatus.e
      }
    }
  }
}
