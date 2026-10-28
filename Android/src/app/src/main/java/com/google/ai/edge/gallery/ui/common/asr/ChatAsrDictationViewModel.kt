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

package com.google.ai.edge.gallery.ui.common.asr

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceError
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.runDictationSession
import com.google.ai.edge.gallery.ui.common.tts.TtsPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGChatAsrDictationVM"

/** UI state of [ChatAsrDictationViewModel]. */
data class ChatAsrDictationUiState(
  /**
   * Whether a voice input session is capturing audio (listening or finishing). Stays `false` while
   * the ASR engine is still preparing so the live waveform only appears once the microphone is
   * open.
   */
  val isDictating: Boolean = false,
  /** Cumulative live transcript of the current session. */
  val transcript: String = "",
  /** Normalized microphone level in `[0, 1]`. */
  val audioLevel: Float = 0f,
)

/**
 * Drives voice input (speech-to-text) in the chat input bar using the user's selected ASR engine.
 *
 * A session starts with [start] and ends in one of three ways:
 * - [finish] stops listening and hands the final transcript to the caller (used by both "stop and
 *   edit" and "send").
 * - [cancel] stops listening and discards the transcript.
 * - The session ends on its own, for example when the engine fails. The transcript so far is then
 *   handed to the `onEndedBySession` callback passed to [start].
 */
@HiltViewModel
class ChatAsrDictationViewModel
@Inject
constructor(
  private val asrServiceProvider: AsrServiceProvider,
  private val ttsPlaybackController: TtsPlaybackController,
) : ViewModel() {
  private val _uiState = MutableStateFlow(ChatAsrDictationUiState())
  val uiState: StateFlow<ChatAsrDictationUiState> = _uiState.asStateFlow()

  private var sessionJob: Job? = null
  private var activeService: AsrService? = null
  private var onFinished: ((String) -> Unit)? = null
  private var onEndedBySession: ((String, Boolean) -> Unit)? = null
  private var finishRequested = false

  /**
   * Incremented whenever a session starts or is torn down. A session coroutine only touches shared
   * state while its id is still current, so a cancelled session that is still finishing engine
   * preparation can't take over or end a newer one.
   */
  private var sessionId = 0

  init {
    asrServiceProvider.preloadActiveService(viewModelScope)
    viewModelScope.launch { ttsPlaybackController.playbackStarts.collect { cancel() } }
  }

  /** Whether voice dictation is currently recording audio, or is still preparing the engine. */
  val isDictatingOrPending: Boolean
    get() = _uiState.value.isDictating || sessionJob?.isActive == true

  /**
   * Starts a voice input session. If called while [ChatAsrDictationUiState.isStarting] is `true`
   * (the engine is still preparing), cancels the in-flight start and returns to idle
   * (toggle-to-cancel); if already dictating, does nothing.
   *
   * @param onEndedBySession Called when the session ends without [finish] or [cancel], with the
   *   trimmed transcript recognized so far (possibly empty) and whether the session failed.
   */
  fun start(onEndedBySession: (transcript: String, failed: Boolean) -> Unit = { _, _ -> }) {
    if (isDictatingOrPending) {
      if (!_uiState.value.isDictating) {
        cancel()
      }
      return
    }
    ttsPlaybackController.stop()
    onFinished = null
    this.onEndedBySession = onEndedBySession
    finishRequested = false

    val id = ++sessionId
    sessionJob = viewModelScope.launch {
      var prepared = false
      var transcript = ""
      var failed = false
      try {
        asrServiceProvider.runDictationSession(
          shouldAbortAfterPrepare = {
            prepared = true
            id != sessionId || finishRequested
          },
          onServiceReady = {
            activeService = it
            _uiState.value = ChatAsrDictationUiState(isDictating = true)
          },
          onAmplitudeChanged = { level -> _uiState.update { it.copy(audioLevel = level) } },
          onTranscriptChanged = { text ->
            transcript = text
            _uiState.update { it.copy(transcript = text) }
          },
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (!prepared) {
          Log.w(TAG, "Failed to prepare ASR service", e)
          failed = true
        } else {
          Log.w(TAG, "ASR session failed", e)
          // Ending without speech is not a failure: nothing was said.
          failed = e !is AsrServiceError.NoSpeechDetected
        }
      }
      if (id == sessionId) {
        endSession(id, transcript = transcript, failed = failed)
      }
    }
  }

  /**
   * Stops listening and calls [onTranscript] with the final, trimmed transcript once the engine has
   * flushed it. [onTranscript] may receive an empty string if nothing was recognized.
   */
  fun finish(onTranscript: (String) -> Unit) {
    if (!isDictatingOrPending || finishRequested) {
      return
    }
    onFinished = onTranscript
    finishRequested = true
    val service = activeService ?: return // The session ends itself once preparation completes.
    viewModelScope.launch { service.stopRecording() }
  }

  /** Stops listening and discards the transcript. */
  fun cancel() {
    if (!isDictatingOrPending) {
      return
    }
    _uiState.value = ChatAsrDictationUiState()
    tearDownSession()
  }

  private fun endSession(id: Int, transcript: String, failed: Boolean) {
    if (id != sessionId) {
      return
    }
    val finishedCallback = onFinished
    val endedBySessionCallback = onEndedBySession
    onFinished = null
    onEndedBySession = null
    activeService = null
    sessionJob = null
    _uiState.value = ChatAsrDictationUiState()
    if (finishedCallback != null) {
      finishedCallback(transcript.trim())
    } else {
      endedBySessionCallback?.invoke(transcript.trim(), failed)
    }
  }

  /**
   * Invalidates the current session and cancels its coroutine. Cancelling the collection of the
   * recording flow also cancels the recording.
   */
  private fun tearDownSession() {
    sessionId++
    onFinished = null
    onEndedBySession = null
    activeService = null
    sessionJob?.cancel()
    sessionJob = null
  }

  override fun onCleared() {
    tearDownSession()
  }
}
