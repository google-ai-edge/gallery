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
package com.google.ai.edge.gallery.ui.common.textandvoiceinput

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGHoldToDictateVM"
private const val RECORDING_DONE_DELAY = 500L

/** The UI state of the HoldToDictateViewModel. */
data class HoldToDictateUiState(val recognizing: Boolean = false, val recognizedText: String = "")

@HiltViewModel
class HoldToDictateViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  protected val _uiState = MutableStateFlow(HoldToDictateUiState())
  val uiState = _uiState.asStateFlow()

  private var speechRecognizer: SpeechRecognizer? = null
  private lateinit var recognizerIntent: Intent
  private var recognitionListener: RecognitionListener? = null
  private var currentOnDone: ((String) -> Unit)? = null
  private var currentOnAmplitudeChanged: ((Int) -> Unit)? = null

  fun startSpeechRecognition(onDone: (String) -> Unit, onAmplitudeChanged: (Int) -> Unit) {
    startSpeechRecognition(
      context = appContext,
      onDone = onDone,
      onAmplitudeChanged = onAmplitudeChanged,
    )
  }

  private fun startSpeechRecognition(
    context: Context,
    onDone: (String) -> Unit,
    onAmplitudeChanged: (Int) -> Unit,
  ) {
    currentOnDone = onDone
    currentOnAmplitudeChanged = onAmplitudeChanged

    if (speechRecognizer == null) {
      recognitionListener =
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {}

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {
            currentOnAmplitudeChanged?.invoke(convertRmsDbToAmplitude(rmsdB = rmsdB))
          }

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {}

          override fun onError(error: Int) {
            Log.d(TAG, "onError: $error")
          }

          override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.size > 0) {
              val text = matches[0] ?: ""
              _uiState.update { uiState.value.copy(recognizedText = text) }
              currentOnDone?.invoke(text)
            }
            setRecognizing(recognizing = false)
          }

          override fun onPartialResults(partialResults: Bundle?) {
            val matches =
partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.size > 0) {
              _uiState.update { uiState.value.copy(recognizedText = matches[0] ?: "") }
            }
          }

          override fun onEvent(eventType: Int, params: Bundle?) {}
        }

      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(recognitionListener)
        }

      recognizerIntent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
          )
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    _uiState.update { uiState.value.copy(recognizedText = "") }
    speechRecognizer?.startListening(recognizerIntent)
    setRecognizing(recognizing = true)
  }

  fun stopSpeechRecognition() {
    viewModelScope.launch(Dispatchers.Default) {
      delay(RECORDING_DONE_DELAY)
      viewModelScope.launch(Dispatchers.Main) {
        speechRecognizer?.stopListening()
        setRecognizing(recognizing = false)
      }
    }
  }

  fun cancelSpeechRecognition() {
    speechRecognizer?.cancel()
    setRecognizing(recognizing = false)
  }

  fun setRecognizing(recognizing: Boolean) {
    _uiState.update { uiState.value.copy(recognizing = recognizing) }
  }

  fun clearRecognizedText() {
    _uiState.update { uiState.value.copy(recognizedText = "") }
  }
}

private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
  // Clamp the input value to a reasonable range
  var clampedRmsdB = Math.max(rmsdB, -2.0f)
  clampedRmsdB = Math.min(clampedRmsdB, 10.0f)

  // Linearly scale to a 0-65535 range
  return ((clampedRmsdB + 2f) * 65535f / 12f).toInt()
}
