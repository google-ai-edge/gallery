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

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrRecordingOptions
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGTeacherGameSpeech"

/** Microphone and speech recognition state of the `Vocab Popper` and `Vocab Runner` games. */
enum class XrLanguageTeacherGameMicState {
  /** The recognizer is being prepared. */
  STARTING,
  /** The microphone is listening for the target-language words. */
  LISTENING,
  /** The microphone permission is not granted. */
  PERMISSION_DENIED,
  /** Speech recognition failed repeatedly or is not available on the device. */
  UNAVAILABLE,
}

/** Receives the events of a [XrLanguageTeacherGameSpeechSession], on the session's scope. */
interface XrLanguageTeacherGameSpeechListener {
  /** A new recognition segment started; its transcripts start from scratch. */
  fun onNewSegment()

  /**
   * The player paused for [XrLanguageTeacherGameSpeechSession.UTTERANCE_PAUSE_MS] after speaking.
   */
  fun onUtterancePause()

  /** The trimmed cumulative [transcript] of the current segment changed. */
  fun onTranscript(transcript: String)

  /** The microphone state changed to [state]. */
  fun onMicStateChanged(state: XrLanguageTeacherGameMicState)
}

/** Continuous speech recognition of a study game, started on entry and stopped on exit. */
interface XrLanguageTeacherGameSpeechRecognizer {
  /** Starts (or restarts) listening. */
  fun start()

  /**
   * Stops listening and releases the microphone. Returns the job that completes once the recognizer
   * is torn down, so the shared ASR engine can be prepared for something else after it.
   */
  fun stop(): Job
}

/** The ASR engine and the preparation that recognize a game's learn language. */
data class XrLanguageTeacherGameAsrTarget(
  val service: AsrService,
  /**
   * Prepares [service] for the learn language; called before every recognition segment, so the
   * learn language is applied again if something prepared the shared engine for another language.
   */
  val prepare: suspend () -> Unit,
)

/**
 * When a game's recognition segment rolls over to a fresh one, which keeps transcripts short over a
 * long round but loses the audio until the engine listens again.
 *
 * @property pauseRolloverMinAgeMs Minimum age of a segment before a speaking pause also rolls it
 *   over, or `null` to never roll over at a pause.
 * @property maxSegmentDurationMs Age at which a segment rolls over regardless.
 */
data class XrLanguageTeacherGameSegmentTiming(
  val pauseRolloverMinAgeMs: Long?,
  val maxSegmentDurationMs: Long,
) {
  companion object {
    /**
     * Returns the timing for [engineType]:
     * - The system recognizer keeps a session with a cumulative transcript and restarts itself
     *   across its own segments and silences (up to a limit, after which the session ends and the
     *   game starts a new one), while cancelling it drops the pending words and the audio until it
     *   is ready again. So it never rolls over at a pause, and only after
     *   [XrLanguageTeacherGameSpeechSession.SYSTEM_MAX_SEGMENT_DURATION_MS], longer than any round,
     *   which normally keeps one session per round.
     * - ML Kit and the speech processor restart quickly; they roll over at a pause after
     *   [XrLanguageTeacherGameSpeechSession.SEGMENT_ROLLOVER_MIN_AGE_MS], or after
     *   [XrLanguageTeacherGameSpeechSession.MAX_SEGMENT_DURATION_MS], which bounds their transcript
     *   and the engine's work on long audio.
     */
    fun forEngine(engineType: AsrEngineType): XrLanguageTeacherGameSegmentTiming =
      when (engineType) {
        AsrEngineType.SYSTEM_DEFAULT ->
          XrLanguageTeacherGameSegmentTiming(
            pauseRolloverMinAgeMs = null,
            maxSegmentDurationMs = XrLanguageTeacherGameSpeechSession.SYSTEM_MAX_SEGMENT_DURATION_MS,
          )
        AsrEngineType.MLKIT_BASIC,
        AsrEngineType.MLKIT_ADVANCED,
        AsrEngineType.SPEECH_PROCESSOR ->
          XrLanguageTeacherGameSegmentTiming(
            pauseRolloverMinAgeMs = XrLanguageTeacherGameSpeechSession.SEGMENT_ROLLOVER_MIN_AGE_MS,
            maxSegmentDurationMs = XrLanguageTeacherGameSpeechSession.MAX_SEGMENT_DURATION_MS,
          )
      }
  }
}

/**
 * Continuous learn-language speech recognition for the `Vocab Popper` and `Vocab Runner` games.
 *
 * Every loop resolves the [XrLanguageTeacherGameAsrTarget] returned by [resolveTarget], then runs
 * back-to-back recognition segments with interim results, preparing the target before each one, and
 * forwards each new, trimmed transcript to [listener]. A segment ends when the engine finalizes it
 * or reports that no speech was heard, or rolls over to a fresh one by the
 * [XrLanguageTeacherGameSegmentTiming] of the engine: at a pause of [UTTERANCE_PAUSE_MS] once the
 * segment is old enough, or once it reaches its maximum duration. Errors restart the loop after
 * [RETRY_BACKOFF_MS]; [MAX_CONSECUTIVE_FAILURES] errors in a row without a transcript mark
 * recognition [XrLanguageTeacherGameMicState.UNAVAILABLE].
 *
 * Not thread-safe: call [start] and [stop] from the thread of [scope].
 *
 * @param hasMicPermission whether the microphone permission is granted; checked on [start].
 * @param resolveTarget returns the engine to recognize with, or `null` when none is available.
 * @param elapsedRealtimeMillis monotonic clock that measures segment age.
 */
class XrLanguageTeacherGameSpeechSession(
  private val scope: CoroutineScope,
  private val hasMicPermission: () -> Boolean,
  private val resolveTarget: suspend () -> XrLanguageTeacherGameAsrTarget?,
  private val listener: XrLanguageTeacherGameSpeechListener,
  private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : XrLanguageTeacherGameSpeechRecognizer {
  private var loopJob: Job? = null
  private var teardownJob: Job? = null
  private var activeService: AsrService? = null

  override fun start() {
    loopJob?.cancel()
    listener.onMicStateChanged(XrLanguageTeacherGameMicState.STARTING)
    if (!hasMicPermission()) {
      listener.onMicStateChanged(XrLanguageTeacherGameMicState.PERMISSION_DENIED)
      return
    }
    val previousJob = loopJob
    val previousTeardown = teardownJob
    loopJob = scope.launch {
      // The engine is shared; let the previous loop release it first.
      previousJob?.join()
      previousTeardown?.join()
      runContinuousLoop()
    }
  }

  override fun stop(): Job {
    val job = loopJob
    loopJob = null
    job?.cancel()
    val service = activeService
    activeService = null
    val previousTeardown = teardownJob
    return scope
      .launch {
        previousTeardown?.join()
        // The cancelled loop cancels its recording on the way out; wait for it.
        job?.join()
        if (service != null) cancelRecordingQuietly(service)
      }
      .also { teardownJob = it }
  }

  private suspend fun runContinuousLoop() {
    var consecutiveFailures = 0
    while (currentCoroutineContext().isActive) {
      val target =
        try {
          resolveTarget()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to resolve the game ASR engine", e)
          null
        }
      if (target == null) {
        listener.onMicStateChanged(XrLanguageTeacherGameMicState.UNAVAILABLE)
        return
      }
      activeService = target.service
      val timing = XrLanguageTeacherGameSegmentTiming.forEngine(target.service.engineType)
      try {
        var isListening = false
        while (currentCoroutineContext().isActive) {
          // Applies the learn language again before every segment, in case something prepared the
          // shared engine for another language meanwhile.
          target.prepare()
          if (!isListening) {
            isListening = true
            listener.onMicStateChanged(XrLanguageTeacherGameMicState.LISTENING)
          }
          listener.onNewSegment()
          val shouldContinueRightAway = runSegment(target.service, timing)
          consecutiveFailures = 0
          if (!shouldContinueRightAway) {
            // Keeps an engine that ends silent segments right away from spinning.
            delay(RETRY_BACKOFF_MS)
          }
        }
      } catch (e: CancellationException) {
        withContext(NonCancellable) { cancelRecordingQuietly(target.service) }
        throw e
      } catch (e: AsrServiceError.AudioPermissionDenied) {
        Log.w(TAG, "Game speech recognition lost the microphone permission", e)
        cancelRecordingQuietly(target.service)
        listener.onMicStateChanged(XrLanguageTeacherGameMicState.PERMISSION_DENIED)
        return
      } catch (e: Exception) {
        cancelRecordingQuietly(target.service)
        consecutiveFailures++
        Log.w(TAG, "Game speech recognition error ($consecutiveFailures)", e)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          listener.onMicStateChanged(XrLanguageTeacherGameMicState.UNAVAILABLE)
          return
        }
      }
      delay(RETRY_BACKOFF_MS)
    }
  }

  /**
   * Runs one recognition segment on [service] until the engine ends it or it rolls over by
   * [timing]. Returns whether the next segment should start right away (the segment produced a
   * transcript or rolled over), and throws engine errors other than
   * [AsrServiceError.NoSpeechDetected].
   */
  private suspend fun runSegment(
    service: AsrService,
    timing: XrLanguageTeacherGameSegmentTiming,
  ): Boolean = coroutineScope {
    val segmentScope = this
    val segmentStartMs = elapsedRealtimeMillis()
    var lastEmittedTranscript = ""
    // Whether a transcript arrived after the last utterance pause was reported.
    var hasPendingPause = false
    var didRollOver = false
    var failure: Exception? = null
    var pauseJob: Job? = null
    var collectJob: Job? = null
    collectJob = launch {
      try {
        service.startRecording(AsrRecordingOptions(showInterimResults = true)).collect { partial ->
          val trimmed = partial.trim()
          if (trimmed.isEmpty() || trimmed == lastEmittedTranscript) return@collect
          lastEmittedTranscript = trimmed
          listener.onTranscript(trimmed)
          hasPendingPause = true
          pauseJob?.cancel()
          pauseJob = segmentScope.launch {
            delay(UTTERANCE_PAUSE_MS)
            hasPendingPause = false
            listener.onUtterancePause()
            val rolloverMinAgeMs = timing.pauseRolloverMinAgeMs
            if (
              rolloverMinAgeMs != null &&
                elapsedRealtimeMillis() - segmentStartMs >= rolloverMinAgeMs
            ) {
              didRollOver = true
              collectJob?.cancel()
            }
          }
        }
      } catch (e: AsrServiceError.NoSpeechDetected) {
        // A normal silence timeout; the next segment starts right away.
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failure = e
      }
    }
    val maxDurationJob = launch {
      delay(timing.maxSegmentDurationMs)
      didRollOver = true
      collectJob.cancel()
    }
    collectJob.join()
    pauseJob?.cancel()
    maxDurationJob.cancel()
    // The segment ended (by the engine or a rollover) before the pause after the last words: they
    // won't be revised anymore, so report the pause now.
    if (hasPendingPause && failure == null) {
      listener.onUtterancePause()
    }
    if (didRollOver) {
      // Cancelling the collection already ends recording; make sure the engine is released.
      cancelRecordingQuietly(service)
    }
    failure?.let { throw it }
    didRollOver || lastEmittedTranscript.isNotEmpty()
  }

  private suspend fun cancelRecordingQuietly(service: AsrService) {
    try {
      service.cancelRecording()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to cancel game speech recognition", e)
    }
  }

  companion object {
    /** Silence after speech that marks the current utterance as consumed. */
    const val UTTERANCE_PAUSE_MS = 950L

    /**
     * Minimum age of an ML Kit or speech processor recognition segment before a pause also rolls
     * over to a fresh segment, bounding transcript growth over a 60-90 s round without restarting
     * on every word.
     */
    const val SEGMENT_ROLLOVER_MIN_AGE_MS = 15_000L

    /** Maximum duration of an ML Kit or speech processor recognition segment. */
    const val MAX_SEGMENT_DURATION_MS = 24_000L

    /**
     * Maximum duration of a system recognizer segment: longer than the longest (90 s) round, so the
     * game normally keeps one session per round, while still bounding a session that keeps running.
     */
    const val SYSTEM_MAX_SEGMENT_DURATION_MS = 120_000L

    /** Delay before the recognition loop restarts after an error. */
    const val RETRY_BACKOFF_MS = 180L

    /** Errors in a row, without a transcript in between, after which recognition gives up. */
    const val MAX_CONSECUTIVE_FAILURES = 3
  }
}
