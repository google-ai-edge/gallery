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
package com.google.ai.edge.gallery.ui.streaminggemma

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGStreamingGemmaVM"
private const val SILENCE_TIMEOUT_MS = 1500L

data class StreamingGemmaUiState(
  val isListening: Boolean = false,
  val isProcessing: Boolean = false,
  val recognizedText: String = "",
  val statusText: String = "Waiting to start...",
)

@HiltViewModel
class StreamingGemmaViewModel
@Inject
constructor(@ApplicationContext private val context: Context) :
  LlmChatViewModelBase(), RecognitionListener {

  private val _uiState = MutableStateFlow(StreamingGemmaUiState())
  val uiState = _uiState.asStateFlow()

  private var speechRecognizer: SpeechRecognizer? = null
  private val recognizerIntent: Intent

  private var silenceTimerJob: Job? = null
  var onSpeechFinished: ((String) -> Unit)? = null

  init {
    recognizerIntent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      }
  }

  fun startListening() {
    if (speechRecognizer == null) {
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(this@StreamingGemmaViewModel)
        }
    }

    viewModelScope.launch {
      _uiState.update {
        it.copy(isListening = true, recognizedText = "", statusText = "Listening...")
      }
      speechRecognizer?.startListening(recognizerIntent)
    }
  }

  fun stopListening() {
    viewModelScope.launch {
      silenceTimerJob?.cancel()
      speechRecognizer?.stopListening()
      _uiState.update { it.copy(isListening = false, statusText = "Processing...") }
    }
  }

  fun cancelListening() {
    viewModelScope.launch {
      silenceTimerJob?.cancel()
      speechRecognizer?.cancel()
      _uiState.update {
        it.copy(isListening = false, recognizedText = "", statusText = "Cancelled")
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    speechRecognizer?.destroy()
    speechRecognizer = null
  }

  private fun restartSilenceTimer() {
    silenceTimerJob?.cancel()
    silenceTimerJob = viewModelScope.launch {
      delay(SILENCE_TIMEOUT_MS)
      handleSilenceDetected()
    }
  }

  private fun handleSilenceDetected() {
    Log.d(TAG, "Silence detected")
    stopListening()
    val text = _uiState.value.recognizedText
    if (text.isNotBlank()) {
      onSpeechFinished?.invoke(text)
    } else {
      // Nothing was said, start listening again
      startListening()
    }
  }

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {}

  override fun onRmsChanged(rmsdB: Float) {}

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onError(error: Int) {
    Log.e(TAG, "Speech recognition error: ${error}")
    // Restart listening on error (e.g. no match)
    if (uiState.value.isListening) {
      startListening()
    }
  }

  override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (!matches.isNullOrEmpty()) {
      val text = matches[0] ?: ""
      _uiState.update { it.copy(recognizedText = text) }
    }
    // If not processing manually via silence, we could handle it here.
    // We'll let silence timer handle the flow, or trigger it here if it's a hard stop.
    handleSilenceDetected()
  }

  override fun onPartialResults(partialResults: Bundle?) {
    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    if (!matches.isNullOrEmpty()) {
      val text = matches[0] ?: ""
      _uiState.update { it.copy(recognizedText = text) }
      if (text.isNotBlank()) {
        restartSilenceTimer()
      }
    }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}
}
