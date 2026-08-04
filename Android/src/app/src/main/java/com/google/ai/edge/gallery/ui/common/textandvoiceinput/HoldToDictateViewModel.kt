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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGHTD"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

/** The UI state of the HoldToDictateViewModel. */
data class HoldToDictateUiState(
  val recognizing: Boolean = false,
  val transcribing: Boolean = false,
  val recognizedText: String = "",
  val errorMessage: String = "",
  val offerTextInputFallback: Boolean = false,
)

private enum class SpeechRecognitionRoute {
  NETWORK_CAPABLE,
  ON_DEVICE,
}

@HiltViewModel
class HoldToDictateViewModel @Inject constructor(@ApplicationContext private val context: Context) :
  ViewModel(), RecognitionListener {
  protected val _uiState = MutableStateFlow(HoldToDictateUiState())
  val uiState = _uiState.asStateFlow()

  private val networkSpeechRecognizer: SpeechRecognizer?
  private val onDeviceSpeechRecognizer: SpeechRecognizer?
  private val networkRecognizerIntent: Intent
  private val onDeviceRecognizerIntent: Intent
  private var activeSpeechRecognizer: SpeechRecognizer? = null
  private var activeSpeechRecognitionRoute: SpeechRecognitionRoute? = null
  private var onRecognitionDone: ((String) -> Unit)? = null
  private var onAmplitudeChanged: ((Int) -> Unit)? = null

  init {
    val networkRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    val onDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    Log.i(
      TAG,
      "Speech recognition available: network_capable=$networkRecognitionAvailable " +
        "on_device=$onDeviceRecognitionAvailable",
    )
    networkSpeechRecognizer =
      if (networkRecognitionAvailable) {
        runCatching {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
              setRecognitionListener(this@HoldToDictateViewModel)
            }
          }
          .onFailure { exception ->
            Log.w(TAG, "Unable to create network-capable speech recognizer", exception)
          }
          .getOrNull()
      } else {
        null
      }
    onDeviceSpeechRecognizer =
      if (onDeviceRecognitionAvailable) {
        runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
              setRecognitionListener(this@HoldToDictateViewModel)
            }
          }
          .onFailure { exception ->
            Log.w(TAG, "Unable to create on-device speech recognizer", exception)
          }
          .getOrNull()
      } else {
        null
      }

    networkRecognizerIntent = createRecognizerIntent(preferOffline = false)
    onDeviceRecognizerIntent = createRecognizerIntent(preferOffline = true)
    prepareOnDeviceRecognition()
  }

  fun startSpeechRecognition(
    onDone: (String) -> Unit,
    onAmplitudeChanged: (Int) -> Unit,
  ) {
    if (uiState.value.recognizing || uiState.value.transcribing) {
      Log.i(TAG, "Ignoring speech recognition start while a session is still active.")
      return
    }
    onRecognitionDone = onDone
    this.onAmplitudeChanged = onAmplitudeChanged
    val hasInternetConnection = hasValidatedInternetConnection()
    val route =
      if (hasInternetConnection && networkSpeechRecognizer != null) {
        SpeechRecognitionRoute.NETWORK_CAPABLE
      } else {
        SpeechRecognitionRoute.ON_DEVICE
      }
    val recognizer =
      when (route) {
        SpeechRecognitionRoute.NETWORK_CAPABLE -> networkSpeechRecognizer
        SpeechRecognitionRoute.ON_DEVICE -> onDeviceSpeechRecognizer
      }
    val recognizerIntent =
      when (route) {
        SpeechRecognitionRoute.NETWORK_CAPABLE -> networkRecognizerIntent
        SpeechRecognitionRoute.ON_DEVICE -> onDeviceRecognizerIntent
      }

    if (recognizer == null) {
      val message =
        if (hasInternetConnection) {
          "Android speech recognition is not available on this device."
        } else {
          "Offline speech recognition is not available on this device."
        }
      Log.w(TAG, message)
      setRecognizedText(text = "")
      setErrorMessage(message)
      setTextInputFallbackOffered(true)
      setRecognizing(recognizing = false)
      setTranscribing(transcribing = false)
      return
    }

    activeSpeechRecognizer = recognizer
    activeSpeechRecognitionRoute = route
    Log.i(TAG, "Starting Android speech recognition: route=${route.name.lowercase()}")
    setRecognizedText(text = "")
    setErrorMessage(message = "")
    setTextInputFallbackOffered(false)
    setRecognizing(recognizing = true)
    try {
      recognizer.startListening(recognizerIntent)
    } catch (e: RuntimeException) {
      val message =
        "Failed to start speech recognition: ${e.message ?: e.javaClass.simpleName}"
      Log.e(TAG, message, e)
      activeSpeechRecognizer = null
      activeSpeechRecognitionRoute = null
      setErrorMessage(message)
      setTextInputFallbackOffered(true)
      setRecognizing(recognizing = false)
      setTranscribing(transcribing = false)
    }
  }

  fun stopSpeechRecognition() {
    val recognizer = activeSpeechRecognizer
    if (recognizer == null) {
      setRecognizing(recognizing = false)
      setTranscribing(transcribing = false)
      return
    }
    setRecognizing(recognizing = false)
    setTranscribing(transcribing = true)
    viewModelScope.launch {
      delay(500)
      if (!uiState.value.transcribing) {
        Log.i(TAG, "Skipping stale Android speech recognition stop")
        return@launch
      }
      Log.i(TAG, "Stopping speech recognition")
      recognizer.stopListening()
    }
  }

  fun cancelSpeechRecognition() {
    if (uiState.value.transcribing) {
      Log.i(TAG, "Ignoring speech recognition cancellation while finalizing transcription.")
      return
    }
    Log.i(TAG, "Cancelling speech recognition")
    activeSpeechRecognizer?.cancel()
    activeSpeechRecognizer = null
    activeSpeechRecognitionRoute = null
    setRecognizing(recognizing = false)
    setTranscribing(transcribing = false)
  }

  fun dismissTextInputFallbackOffer() {
    setTextInputFallbackOffered(false)
  }

  fun setRecognizing(recognizing: Boolean) {
    _uiState.update { uiState.value.copy(recognizing = recognizing) }
  }

  private fun setTranscribing(transcribing: Boolean) {
    _uiState.update { uiState.value.copy(transcribing = transcribing) }
  }

  fun setRecognizedText(text: String) {
    _uiState.update { uiState.value.copy(recognizedText = text) }
  }

  private fun setErrorMessage(message: String) {
    _uiState.update { uiState.value.copy(errorMessage = message) }
  }

  private fun setTextInputFallbackOffered(offered: Boolean) {
    _uiState.update { uiState.value.copy(offerTextInputFallback = offered) }
  }

  override fun onReadyForSpeech(params: Bundle?) {
    Log.i(TAG, "Ready for speech")
  }

  override fun onBeginningOfSpeech() {
    Log.i(TAG, "Beginning of speech")
  }

  override fun onRmsChanged(rmsdB: Float) {
    onAmplitudeChanged?.invoke(convertRmsDbToAmplitude(rmsdB = rmsdB))
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {
    Log.i(TAG, "End of speech")
    setRecognizing(recognizing = false)
    setTranscribing(transcribing = true)
  }

  override fun onError(error: Int) {
    val canDownloadOfflineModel = hasValidatedInternetConnection()
    if (
      activeSpeechRecognitionRoute == SpeechRecognitionRoute.ON_DEVICE &&
        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
          error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    ) {
      if (canDownloadOfflineModel) {
        Log.w(TAG, "Offline speech language unavailable ($error); requesting model download")
        requestAndroidSpeechModelDownload()
      }
      setErrorMessage(
        message =
          if (canDownloadOfflineModel) {
            "The offline speech model is not ready. Its download has been requested."
          } else {
            "The offline speech model is not installed. Connect to the internet to download it."
          }
      )
      setTextInputFallbackOffered(true)
      setRecognizing(recognizing = false)
      setTranscribing(transcribing = false)
      activeSpeechRecognizer = null
      activeSpeechRecognitionRoute = null
      return
    }

    val message = speechRecognizerErrorToMessage(error = error)
    Log.w(TAG, "Speech recognition error: $message ($error)")
    setErrorMessage(message = message)
    setTextInputFallbackOffered(
      error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    )
    setRecognizing(recognizing = false)
    setTranscribing(transcribing = false)
    activeSpeechRecognizer = null
    activeSpeechRecognitionRoute = null
  }

  override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val livePartialText = uiState.value.recognizedText.trim()
    val recognizedText =
      matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { livePartialText }
    setRecognizedText(recognizedText)

    if (recognizedText.isNotEmpty()) {
      Log.i(TAG, "Delivering Android speech result: length=${recognizedText.length}")
      onRecognitionDone?.invoke(recognizedText)
    } else {
      Log.i(TAG, "Ignoring empty final speech recognition result")
    }

    Log.i(
      TAG,
      "Speech recognition results: candidate_lengths=${matches?.map { it.length }.orEmpty()} " +
        "live_partial_length=${livePartialText.length} delivered_length=${recognizedText.length}",
    )
    setErrorMessage(message = "")
    setTextInputFallbackOffered(false)
    setRecognizing(recognizing = false)
    setTranscribing(transcribing = false)
    activeSpeechRecognizer = null
    activeSpeechRecognitionRoute = null
  }

  override fun onPartialResults(partialResults: Bundle?) {
    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val partialText = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (partialText.isNotEmpty()) {
      setRecognizedText(partialText)
      Log.i(TAG, "Speech recognition partial result: length=${partialText.length}")
    } else {
      Log.i(TAG, "Ignoring empty speech recognition partial result")
    }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}

  override fun onCleared() {
    networkSpeechRecognizer?.destroy()
    onDeviceSpeechRecognizer?.destroy()
    activeSpeechRecognizer = null
    activeSpeechRecognitionRoute = null
    super.onCleared()
  }

  private fun requestAndroidSpeechModelDownload() {
    val recognizer = onDeviceSpeechRecognizer ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        recognizer.triggerModelDownload(
          onDeviceRecognizerIntent,
          context.mainExecutor,
          object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
              Log.i(TAG, "Android speech model download: $completedPercent%")
            }

            override fun onSuccess() {
              Log.i(TAG, "Android speech model download completed")
            }

            override fun onScheduled() {
              Log.i(TAG, "Android speech model download scheduled")
            }

            override fun onError(error: Int) {
              Log.w(TAG, "Android speech model download failed ($error)")
            }
          },
        )
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION") recognizer.triggerModelDownload(onDeviceRecognizerIntent)
      }
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to request Android speech model download", e)
    }
  }

  private fun prepareOnDeviceRecognition() {
    val recognizer = onDeviceSpeechRecognizer ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return
    }
    recognizer.checkRecognitionSupport(
      onDeviceRecognizerIntent,
      context.mainExecutor,
      object : RecognitionSupportCallback {
        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
          val requestedLanguage = Locale.getDefault()
          val installed =
            recognitionSupport.installedOnDeviceLanguages.any { languageTag ->
              Locale.forLanguageTag(languageTag).language == requestedLanguage.language
            }
          val downloadable =
            recognitionSupport.supportedOnDeviceLanguages.any { languageTag ->
              Locale.forLanguageTag(languageTag).language == requestedLanguage.language
            }
          if (!installed && downloadable && hasValidatedInternetConnection()) {
            Log.i(TAG, "Requesting offline speech model for ${requestedLanguage.toLanguageTag()}")
            requestAndroidSpeechModelDownload()
          }
        }

        override fun onError(error: Int) {
          Log.w(TAG, "Unable to check offline speech recognition support ($error)")
        }
      },
    )
  }

  private fun createRecognizerIntent(preferOffline: Boolean): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        putExtra(
          RecognizerIntent.EXTRA_ENABLE_FORMATTING,
          RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY,
        )
      }
    }

  private fun hasValidatedInternetConnection(): Boolean {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }
}

private fun speechRecognizerErrorToMessage(error: Int): String =
  when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "Speech recognition audio recording failed."
    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client failed."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
      "Microphone permission is required for speech recognition."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
      "The selected speech recognition language is not supported."
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
      "The selected speech recognition language is not installed."
    SpeechRecognizer.ERROR_NETWORK -> "Speech recognition network error."
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout."
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
    SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognition server disconnected."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
    else -> "Speech recognition failed with error code $error."
  }

private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
  // Clamp the input value to the defined range
  var clampedRmsdB = Math.max(rmsdB, AUDIO_METER_MIN_DB)
  clampedRmsdB = Math.min(clampedRmsdB, AUDIO_METER_MAX_DB)

  // Linear scaling to a 0-65535 range
  return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
    .toInt()
}
