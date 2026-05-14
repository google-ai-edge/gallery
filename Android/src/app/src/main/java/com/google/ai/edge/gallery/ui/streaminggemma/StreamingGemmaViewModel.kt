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
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import com.google.ai.edge.litertlm.Contents
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

private const val SYSTEM_PROMPT =
  """You are a voice assistant. Your responses are being processed by a Text-to-Speech (TTS) engine and spoken directly to the user. The user cannot reply to your response, so you must provide a complete, standalone answer in a single turn.

You must strictly obey the following formatting and behavioral rules:

1. BE CONCISE AND CONVERSATIONAL: Speak in natural, casual prose. Keep responses short and punchy—aim for 1 to 3 sentences max. Because the user cannot reply, NEVER ask follow-up questions or prompt the user for more information at the end of your response.
2. NO MARKDOWN OR LISTS: Absolutely no bullet points, numbered lists, bolding, italics, or asterisks. Do not output any structural formatting. Write everything as continuous, spoken paragraphs.
3. NO LATEX OR SYMBOLIC MATH: Never output LaTeX commands (like \frac, \sqrt, \times), markdown math, or complex mathematical symbols (no $, \, _, or ^). You must spell out all equations and math exactly as they are spoken out loud. For example, output "A equals pi R squared" instead of A=πr², "the square root of nine" instead of \sqrt{9}, and "three fifths" instead of \frac{3}{5}.
4. TTS-FRIENDLY PRONUNCIATION: Spell out symbols, acronyms, and numbers the way a human would say them if they could cause TTS issues.
5. STANDALONE ANSWERS: Provide the most important information immediately. Do not over-explain, but ensure the core answer is fully resolved since there is no back-and-forth conversation."""

data class StreamingGemmaUiState(
  val isListening: Boolean = false,
  val isProcessing: Boolean = false,
  val recognizedText: String = "",
  val statusText: String = "Waiting to start...",
  val responseText: String = "",
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
  private var activeModel: Model? = null

  init {
    recognizerIntent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      }
  }

  fun initializeSession(model: Model) {
    activeModel = model
    viewModelScope.launch {
      while (model.instance == null) {
        delay(100)
      }
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = true,
        systemInstruction = Contents(text = SYSTEM_PROMPT),
      )
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
        it.copy(
          isListening = true,
          recognizedText = "",
          responseText = "",
          statusText = "Listening...",
        )
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
      runInference(text)
    } else {
      // Nothing was said, start listening again
      startListening()
    }
  }

  private fun runInference(input: String) {
    val model = activeModel ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isProcessing = true, statusText = "Responding...") }
      var fullResponse = ""

      model.runtimeHelper.runInference(
        model = model,
        input = input,
        resultListener = { partialResult, done, _ ->
          if (!partialResult.startsWith("<ctrl")) {
            fullResponse += partialResult
            _uiState.update { it.copy(responseText = fullResponse) }
            if (done) {
              _uiState.update { it.copy(isProcessing = false, statusText = "Done") }
              startListening() // Automatically restart listening after response
            }
          }
        },
        cleanUpListener = { _uiState.update { it.copy(isProcessing = false) } },
        onError = { errorMsg ->
          Log.e(TAG, "Inference error: $errorMsg")
          _uiState.update { it.copy(isProcessing = false, statusText = "Error: $errorMsg") }
          startListening() // Restart listening on error
        },
        coroutineScope = viewModelScope,
      )
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
