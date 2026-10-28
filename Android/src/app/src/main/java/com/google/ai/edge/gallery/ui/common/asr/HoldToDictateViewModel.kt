/*
 * Copyright 2025 Google LLC
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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.services.asr.AsrAmplitudeNormalizer
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.runDictationSession
import com.google.ai.edge.gallery.ui.common.tts.TtsPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val TAG = "AGHTD"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

/** The UI state of the HoldToDictateViewModel. */
data class HoldToDictateUiState(val recognizing: Boolean = false, val recognizedText: String = "")

@HiltViewModel
class HoldToDictateViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val asrServiceProvider: AsrServiceProvider,
  private val ttsPlaybackController: TtsPlaybackController,
) : ViewModel(), RecognitionListener {
  protected val _uiState: MutableStateFlow<HoldToDictateUiState> =
    MutableStateFlow(HoldToDictateUiState())
  val uiState: StateFlow<HoldToDictateUiState> = _uiState.asStateFlow()

  private val speechRecognizer: SpeechRecognizer
  private val recognizerIntent: Intent
  private var onRecognitionDone: ((String) -> Unit)? = null
  private var onAmplitudeChanged: ((Int) -> Unit)? = null

  private var activeAsrService: AsrService? = null
  private var asrRecordingJob: Job? = null
  private var stopRequested: Boolean = false

  init {
    // Initialize SpeechRecognizer
    speechRecognizer =
      SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(this@HoldToDictateViewModel)
      }

    // Initialize Intent (used for language/model settings)
    recognizerIntent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      }

    asrServiceProvider.preloadActiveService(viewModelScope)
    viewModelScope.launch {
      ttsPlaybackController.playbackStarts.collect {
        if (_uiState.value.recognizing || asrRecordingJob?.isActive == true) {
          cancelSpeechRecognition()
        }
      }
    }
  }

  override fun onCleared() {
    speechRecognizer.destroy()
  }

  fun startSpeechRecognition(onDone: (String) -> Unit, onAmplitudeChanged: (Int) -> Unit) {
    startAsrRecognition(onDone = onDone, onAmplitudeChanged = onAmplitudeChanged)
  }

  fun stopSpeechRecognition() {
    viewModelScope.launch {
      delay(500)
      stopRequested = true
      val service = activeAsrService
      if (service != null) {
        service.stopRecording()
      } else {
        // Still preparing, so there is no recording to finish.
        asrRecordingJob?.cancel()
      }
      setRecognizing(recognizing = false)
    }
  }

  fun cancelSpeechRecognition() {
    // Cancelling the collection of the recording flow also cancels the recording.
    stopRequested = false
    asrRecordingJob?.cancel()
    asrRecordingJob = null
    activeAsrService = null
    speechRecognizer.cancel()
    setRecognizedText(text = "")
    setRecognizing(recognizing = false)
  }

  fun setRecognizing(recognizing: Boolean) {
    _uiState.update { it.copy(recognizing = recognizing) }
  }

  fun setRecognizedText(text: String) {
    _uiState.update { it.copy(recognizedText = text) }
  }

  /**
   * Records with the [AsrService] resolved by [AsrServiceProvider], showing partial results in
   * [uiState] and passing the final text to [onDone] once the recording ends normally.
   */
  private fun startAsrRecognition(onDone: (String) -> Unit, onAmplitudeChanged: (Int) -> Unit) {
    ttsPlaybackController.stop()
    stopRequested = false
    setRecognizedText(text = "")

    asrRecordingJob?.cancel()
    asrRecordingJob = viewModelScope.launch {
      try {
        asrServiceProvider.runDictationSession(
          shouldAbortAfterPrepare = { stopRequested },
          onServiceReady = { service ->
            activeAsrService = service
            setRecognizing(recognizing = true)
          },
          onAmplitudeChanged = { amplitude ->
            onAmplitudeChanged(AsrAmplitudeNormalizer.toPcmAmplitude(amplitude))
          },
          onTranscriptChanged = { text -> setRecognizedText(text = text) },
          onRecordingCompleted = { onDone(uiState.value.recognizedText) },
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Like the platform recognizer's `onError` above, end the session without a result.
        Log.w(TAG, "Speech recognition failed", e)
      } finally {
        // A cancelled session must not reset the state of the session that replaced it.
        if (asrRecordingJob === coroutineContext.job) {
          activeAsrService = null
          setRecognizing(recognizing = false)
        }
      }
    }
  }

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {}

  override fun onRmsChanged(rmsdB: Float) {
    onAmplitudeChanged?.invoke(convertRmsDbToAmplitude(rmsdB = rmsdB))
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onError(error: Int) {}

  override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (matches != null && matches.size > 0) {
      setRecognizedText(matches[0] ?: "")
    } else {
      setRecognizedText("")
    }

    val curOnRecognitionDone = onRecognitionDone
    if (curOnRecognitionDone != null) {
      curOnRecognitionDone(uiState.value.recognizedText)
    }

    setRecognizing(recognizing = false)
  }

  override fun onPartialResults(partialResults: Bundle?) {
    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (matches != null && matches.size > 0) {
      setRecognizedText(matches[0] ?: "")
    } else {
      setRecognizedText("")
    }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}
}

private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
  // Clamp the input value to the defined range
  val clampedRmsdB = rmsdB.coerceIn(AUDIO_METER_MIN_DB, AUDIO_METER_MAX_DB)

  // Linear scaling to a 0-65535 range
  return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
    .toInt()
}
