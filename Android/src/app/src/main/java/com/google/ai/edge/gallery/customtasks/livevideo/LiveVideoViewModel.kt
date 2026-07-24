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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveVideoUiState(
  val mode: VideoMode = VideoMode.CONVERSATION,
  val isProcessing: Boolean = false,
  val lastResponse: String = "",
  val fps: Float = 0f,
  val effectiveFps: Float = 0f,
  val frameCount: Int = 0,
  val cellHighlights: List<Pair<Float, Float>> = emptyList(),
  val allCellScores: List<Float> = emptyList(),
  val autoCaptionEnabled: Boolean = false,
  val chatHistory: List<ChatEntry> = emptyList(),
  val smartCameraSubMode: SmartCameraSubMode = SmartCameraSubMode.AUTO_MODE,
  val lastAction: String = "",
  val showGazeOverlay: Boolean = true,
  val gazeEnabled: Boolean = true,
  val inferenceLatencyMs: Long = 0,
  val gazeLatencyMs: Long = 0,
  val gazePreprocessMs: Long = 0,
  val framesUsed: Int = 0,
  val captureTriggered: Boolean = false,
  val captureId: Long = 0,
  val captureReason: String = "",
  val detectedMode: String? = null,
  val cameraState: CameraState = CameraState(),
  // Voice I/O state
  val voiceEnabled: Boolean = false,
  val isListening: Boolean = false,
  val recognizedText: String = "",
  val isSpeaking: Boolean = false,
  val continuousMode: Boolean = false,
)

data class ChatEntry(
  val query: String,
  val response: String,
  val timestampMs: Long = System.currentTimeMillis(),
  val isUser: Boolean = false,
)

enum class SmartCameraSubMode(val label: String, val description: String) {
  AUTO_MODE("Auto Mode", "Auto-detect portrait, night, macro, action"),
  AUTO_CAPTURE("Auto Capture", "Capture when a condition is met"),
  AUTO_ZOOM("Auto Zoom", "Track and zoom to the subject"),
}

class LiveVideoViewModel : ViewModel() {
  companion object {
    private const val TAG = "LiveVideoVM"
    private const val MAX_FRAMES_FOR_INFERENCE = 8
    private const val MAX_CHAT_HISTORY = 50
    private const val SMART_CAMERA_INTERVAL_MS = 3000L
    private const val GAZE_UPDATE_INTERVAL_MS = 500L
    private const val CONTINUOUS_LISTEN_DELAY_MS = 800L
  }

  private val _uiState = MutableStateFlow(LiveVideoUiState())
  val uiState: StateFlow<LiveVideoUiState> = _uiState.asStateFlow()

  val frameBuffer = FrameBuffer(maxFrames = 64)
  var cameraController: CameraController? = null
    private set

  private var autoCaptionJob: Job? = null
  private var smartCameraJob: Job? = null
  private var gazeTrackingJob: Job? = null
  private val frameCountForFps = AtomicInteger(0)
  private val fpsStartTimeMs = AtomicLong(System.currentTimeMillis())
  private val effectiveFrameCount = AtomicInteger(0)
  private val effectiveFpsStartMs = AtomicLong(System.currentTimeMillis())
  @Volatile private var lastFrameAcceptedMs = 0L

  // Speech recognition
  private var speechRecognizer: SpeechRecognizer? = null
  private var recognizerIntent: Intent? = null
  private var continuousListenJob: Job? = null

  // TTS
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private val ttsBuffer = AtomicReference("")
  private var ttsUtteranceCount = 0

  // Model reference for auto-inference
  private var currentModel: Model? = null

  fun initCameraController(context: Context) {
    if (cameraController == null) {
      cameraController = CameraController(context.applicationContext)
    }
  }

  fun initVoice(context: Context) {
    if (speechRecognizer == null) {
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(recognitionListener)
        }
      recognizerIntent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    if (tts == null) {
      tts =
        TextToSpeech(context) { status ->
          if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(ttsProgressListener)
            ttsReady = true
            Log.d(TAG, "TTS initialized")
          }
        }
    }
  }

  fun setCurrentModel(model: Model) {
    currentModel = model
  }

  fun toggleVoice() {
    val newEnabled = !_uiState.value.voiceEnabled
    _uiState.update { it.copy(voiceEnabled = newEnabled) }
    if (!newEnabled) {
      stopListening()
      stopSpeaking()
    }
  }

  fun toggleContinuousMode() {
    val newContinuous = !_uiState.value.continuousMode
    _uiState.update { it.copy(continuousMode = newContinuous) }
    if (!newContinuous) {
      stopListening()
    }
  }

  fun startListening() {
    val recognizer = speechRecognizer ?: return
    val intent = recognizerIntent ?: return
    stopSpeaking()
    _uiState.update { it.copy(isListening = true, recognizedText = "") }
    try {
      recognizer.startListening(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start speech recognition", e)
      _uiState.update { it.copy(isListening = false) }
    }
  }

  fun stopListening() {
    continuousListenJob?.cancel()
    continuousListenJob = null
    speechRecognizer?.stopListening()
    _uiState.update { it.copy(isListening = false) }
  }

  private fun stopSpeaking() {
    tts?.stop()
    ttsBuffer.set("")
    _uiState.update { it.copy(isSpeaking = false) }
  }

  private fun speakText(text: String) {
    if (!ttsReady || text.isBlank()) return
    val uttId = "lv_utt_${ttsUtteranceCount++}"
    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, uttId)
    _uiState.update { it.copy(isSpeaking = true) }
  }

  private fun handleStreamingToken(token: String) {
    if (!_uiState.value.voiceEnabled) return
    ttsBuffer.set(ttsBuffer.get() + token)
    processTtsBuffer()
  }

  private fun processTtsBuffer() {
    val buffer = ttsBuffer.get()
    if (buffer.isBlank()) return

    val terminators = listOf(".", "!", "?", "\n")
    var lastIdx = -1
    for (t in terminators) {
      val idx = buffer.lastIndexOf(t)
      if (idx > lastIdx) lastIdx = idx
    }

    if (lastIdx != -1) {
      val toSpeak = buffer.substring(0, lastIdx + 1).trim()
      val remaining = buffer.substring(lastIdx + 1)
      if (toSpeak.isNotEmpty()) speakText(toSpeak)
      ttsBuffer.set(remaining)
    }
  }

  private fun flushTtsBuffer() {
    val remaining = ttsBuffer.getAndSet("")
    if (remaining.isNotBlank()) speakText(remaining.trim())
  }

  private fun onSpeechResult(text: String) {
    _uiState.update { it.copy(isListening = false, recognizedText = text) }

    if (text.isBlank()) {
      maybeRestartListening()
      return
    }

    _uiState.update {
      val history =
        (it.chatHistory + ChatEntry(query = text, response = "", isUser = true)).takeLast(
          MAX_CHAT_HISTORY
        )
      it.copy(chatHistory = history)
    }

    val model = currentModel
    if (model != null) {
      runInference(model, text)
    }
  }

  private fun maybeRestartListening() {
    val state = _uiState.value
    if (
      state.voiceEnabled &&
        state.continuousMode &&
        !state.isProcessing &&
        !state.isSpeaking &&
        !state.isListening
    ) {
      continuousListenJob?.cancel()
      continuousListenJob = viewModelScope.launch {
        delay(CONTINUOUS_LISTEN_DELAY_MS)
        val s = _uiState.value
        if (
          s.voiceEnabled && s.continuousMode && !s.isProcessing && !s.isSpeaking && !s.isListening
        ) {
          Log.d(TAG, "Auto-restarting listening")
          startListening()
        }
      }
    }
  }

  private val recognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {}

      override fun onError(error: Int) {
        Log.w(TAG, "Speech recognition error: $error")
        _uiState.update { it.copy(isListening = false) }
        // Don't retry on permission errors (9) or busy (8) — would cause infinite loop
        if (
          error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
            error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        ) {
          maybeRestartListening()
        }
      }

      override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        onSpeechResult(text)
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        if (text.isNotEmpty()) {
          _uiState.update { it.copy(recognizedText = text) }
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }

  private val ttsProgressListener =
    object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {}

      override fun onDone(utteranceId: String?) {
        _uiState.update { it.copy(isSpeaking = false) }
        viewModelScope.launch { maybeRestartListening() }
      }

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        _uiState.update { it.copy(isSpeaking = false) }
        viewModelScope.launch { maybeRestartListening() }
      }
    }

  private fun getTargetFrameIntervalMs(): Long {
    return if (_uiState.value.gazeEnabled) 100L else 500L
  }

  private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
  val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

  fun onCameraFrame(bitmap: Bitmap) {
    val now = System.currentTimeMillis()
    val interval = getTargetFrameIntervalMs()
    if (now - lastFrameAcceptedMs < interval) {
      return
    }
    lastFrameAcceptedMs = now

    _previewBitmap.value = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    frameBuffer.addFrame(bitmap)

    val count = frameCountForFps.incrementAndGet()
    val effCount = effectiveFrameCount.incrementAndGet()
    val start = fpsStartTimeMs.get()
    if (now - start > 1000) {
      val fps = count * 1000f / (now - start)
      val effStart = effectiveFpsStartMs.get()
      val effFps = effCount * 1000f / (now - effStart)
      frameCountForFps.set(0)
      fpsStartTimeMs.set(now)
      effectiveFrameCount.set(0)
      effectiveFpsStartMs.set(now)
      _uiState.update {
        it.copy(fps = fps, effectiveFps = effFps, frameCount = frameBuffer.frameCount())
      }
    }
  }

  fun setMode(mode: VideoMode) {
    stopAllBackgroundJobs()
    if (!mode.hasAudioIO) {
      stopListening()
      stopSpeaking()
    }
    _uiState.update {
      it.copy(
        mode = mode,
        voiceEnabled = mode.hasAudioIO,
        continuousMode = false,
        autoCaptionEnabled = false,
      )
    }
  }

  fun setSmartCameraSubMode(subMode: SmartCameraSubMode) {
    _uiState.update { it.copy(smartCameraSubMode = subMode) }
    cameraController?.setAutoZoom(subMode == SmartCameraSubMode.AUTO_ZOOM)
  }

  fun toggleGazeOverlay() {
    _uiState.update { it.copy(showGazeOverlay = !it.showGazeOverlay) }
  }

  fun toggleGazeEnabled() {
    val newEnabled = !_uiState.value.gazeEnabled
    _uiState.update { it.copy(gazeEnabled = newEnabled, showGazeOverlay = newEnabled) }
  }

  fun toggleAutoCaption(model: Model?) {
    _uiState.update { it.copy(autoCaptionEnabled = !it.autoCaptionEnabled) }
    if (_uiState.value.autoCaptionEnabled && model != null) {
      startAutoCaption(model)
    } else {
      stopAutoCaption()
    }
  }

  // --- GemmaGaze-only tracking (no LLM, runs continuously for auto-zoom) ---

  fun startGazeTracking(model: Model) {
    stopGazeTracking()
    gazeTrackingJob =
      viewModelScope.launch(Dispatchers.Default) {
        while (true) {
          delay(GAZE_UPDATE_INTERVAL_MS)
          val gemmaGaze = LiveVideoGazeHolder.gemmaGaze ?: continue
          if (!gemmaGaze.isLoaded()) continue

          val frames = frameBuffer.sample(MAX_FRAMES_FOR_INFERENCE)
          if (frames.isEmpty()) continue

          try {
            val t0 = System.currentTimeMillis()
            val scores = gemmaGaze.scoreFrames(frames.map { it.bitmap })
            val gazeMs = System.currentTimeMillis() - t0

            frames.forEach { it.bitmap.recycle() }

            if (scores.isNotEmpty()) {
              val topCells = gemmaGaze.selectTopCells(scores, k = 8)
              val firstTopCells = topCells.firstOrNull()
              val firstScores = scores.firstOrNull()
              if (firstTopCells != null && firstScores != null) {
                val highlights = firstTopCells.map { idx ->
                  val cx = idx % GemmaGazeInterpreter.CELLS_W
                  val cy = idx / GemmaGazeInterpreter.CELLS_W
                  (cx + 0.5f) / GemmaGazeInterpreter.CELLS_W to
                    (cy + 0.5f) / GemmaGazeInterpreter.CELLS_H
                }
                _uiState.update {
                  it.copy(
                    cellHighlights = highlights,
                    allCellScores = firstScores.toList(),
                    gazeLatencyMs = gazeMs,
                  )
                }
                cameraController?.updateAutoZoomFromGaze(highlights)
                cameraController?.state?.value?.let { cs ->
                  _uiState.update { it.copy(cameraState = cs) }
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Gaze tracking error: ${e.message}")
            frames.forEach { runCatching { it.bitmap.recycle() } }
          }
        }
      }
  }

  fun stopGazeTracking() {
    gazeTrackingJob?.cancel()
    gazeTrackingJob = null
  }

  // --- Smart camera loop (uses LLM for decisions) ---

  fun startSmartCamera(model: Model, condition: String = "") {
    stopSmartCamera()
    val subMode = _uiState.value.smartCameraSubMode

    val prompt =
      when (subMode) {
        SmartCameraSubMode.AUTO_MODE ->
          """Look at these camera frames and determine the best camera mode.
Respond with ONLY a JSON object: {"action": "mode", "mode": "portrait"|"landscape"|"night"|"macro"|"action"}
Choose based on: lighting conditions, subject type, and scene composition."""

        SmartCameraSubMode.AUTO_CAPTURE -> {
          val cond = condition.ifEmpty { "something interesting happens" }
          cameraController?.setAutoCapture(true, cond)
          """Monitor these camera frames. Capture when: $cond
If the condition is met, respond: {"action": "capture", "reason": "brief explanation"}
If not met, respond: {"action": "wait"}
Respond with ONLY a JSON object."""
        }

        SmartCameraSubMode.AUTO_ZOOM -> return
      }

    smartCameraJob =
      viewModelScope.launch(Dispatchers.Default) {
        while (true) {
          delay(SMART_CAMERA_INTERVAL_MS)
          if (_uiState.value.isProcessing) continue
          runSmartCameraInference(model, prompt)
        }
      }
  }

  fun stopSmartCamera() {
    smartCameraJob?.cancel()
    smartCameraJob = null
    cameraController?.setAutoCapture(false)
  }

  private suspend fun runSmartCameraInference(model: Model, prompt: String) {
    _uiState.update { it.copy(isProcessing = true) }
    val frames = frameBuffer.sample(4)
    if (frames.isEmpty()) {
      _uiState.update { it.copy(isProcessing = false) }
      return
    }

    val t0 = System.currentTimeMillis()
    val responseBuilder = StringBuilder()
    val frameBitmaps = frames.map { it.bitmap }

    model.runtimeHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { token, done, _ ->
        if (!done) {
          responseBuilder.append(token)
        } else {
          val response = responseBuilder.toString()
          val latency = System.currentTimeMillis() - t0

          val action = cameraController?.parseAndExecute(response)
          val actionLabel =
            when (action) {
              is CameraAction.SetMode -> "Mode: ${action.mode}"
              is CameraAction.Capture -> "CAPTURED: ${action.reason}"
              is CameraAction.Frame -> "Zoom: %.1fx".format(action.zoom)
              null -> if (response.contains("wait")) "Monitoring..." else "—"
            }

          when (action) {
            is CameraAction.Capture -> {
              val captureFrame = frameBuffer.sample(1).firstOrNull()
              captureFrame?.let {
                val unused = cameraController?.saveFrameToGallery(it.bitmap, action.reason)
                it.bitmap.recycle()
              }
              _uiState.update {
                it.copy(
                  captureTriggered = true,
                  captureId = System.currentTimeMillis(),
                  captureReason = action.reason,
                )
              }
              viewModelScope.launch {
                delay(200)
                _uiState.update { it.copy(captureTriggered = false) }
              }
            }
            is CameraAction.SetMode -> {
              _uiState.update { it.copy(detectedMode = action.mode) }
            }
            else -> {}
          }

          _uiState.update {
            it.copy(
              isProcessing = false,
              lastAction = actionLabel,
              lastResponse = response,
              inferenceLatencyMs = latency,
            )
          }

          // Recycle sampled frames
          frameBitmaps.forEach { bmp -> runCatching { bmp.recycle() } }
        }
      },
      cleanUpListener = {},
      onError = { error ->
        _uiState.update { it.copy(isProcessing = false, lastResponse = "Error: $error") }
        frameBitmaps.forEach { bmp -> runCatching { bmp.recycle() } }
      },
      images = frameBitmaps,
      coroutineScope = viewModelScope,
    )
  }

  // --- Standard inference (Q&A, caption, translate) ---

  fun runInference(model: Model, query: String = "") {
    if (_uiState.value.isProcessing) return
    _uiState.update { it.copy(isProcessing = true) }

    viewModelScope.launch(Dispatchers.Default) {
      val mode = _uiState.value.mode
      val frameCount = mode.maxFrames.coerceAtMost(MAX_FRAMES_FOR_INFERENCE)

      val allFrames =
        if (frameCount <= 1) {
          frameBuffer.sampleLatest()?.let { listOf(it) } ?: emptyList()
        } else {
          frameBuffer.sample(frameCount)
        }

      if (allFrames.isEmpty()) {
        _uiState.update { it.copy(isProcessing = false, lastResponse = "No frames captured yet.") }
        return@launch
      }

      val gazeEnabled = _uiState.value.gazeEnabled && mode.useGaze
      val gemmaGaze = LiveVideoGazeHolder.gemmaGaze
      var gazePreprocessMs = 0L

      val frames =
        if (gazeEnabled && gemmaGaze != null && gemmaGaze.isLoaded() && allFrames.size > 1) {
          val gazeT0 = System.currentTimeMillis()
          try {
            val scores = gemmaGaze.scoreFrames(allFrames.map { it.bitmap })
            gazePreprocessMs = System.currentTimeMillis() - gazeT0

            val frameScores = scores.mapIndexed { i, cellScores -> i to cellScores.max() }
            val bestIdx = frameScores.maxByOrNull { it.second }?.first ?: (allFrames.size - 1)

            allFrames.forEachIndexed { i, frame ->
              if (i != bestIdx) runCatching { frame.bitmap.recycle() }
            }
            listOf(allFrames[bestIdx])
          } catch (e: Exception) {
            Log.w(TAG, "GemmaGaze scoring failed, using latest frame", e)
            gazePreprocessMs = System.currentTimeMillis() - gazeT0
            val latest = allFrames.last()
            allFrames.dropLast(1).forEach { runCatching { it.bitmap.recycle() } }
            listOf(latest)
          }
        } else {
          val latest = allFrames.last()
          allFrames.dropLast(1).forEach { runCatching { it.bitmap.recycle() } }
          listOf(latest)
        }
      val prompt =
        if (mode == VideoMode.CONVERSATION && query.isNotEmpty()) {
          "${mode.prompt}\n\nUser: $query"
        } else {
          mode.prompt
        }

      // Stateless modes: clear AICore chat history before each call to prevent
      // context contamination (previous answers biasing new ones) and prompt bloat.
      if (!mode.hasAudioIO) {
        model.runtimeHelper.resetConversation(model, supportImage = true)
      }

      val configMap = model.configValues.toMutableMap()
      configMap[ConfigKeys.TEMPERATURE.label] = mode.temperature.toString()
      model.configValues = configMap

      val t0 = System.currentTimeMillis()
      val responseBuilder = StringBuilder()
      val frameBitmaps = frames.map { f ->
        val bmp = f.bitmap
        if (gazeEnabled) {
          val longerSide = maxOf(bmp.width, bmp.height)
          val targetPx = mode.imageMaxPx
          if (longerSide > targetPx) {
            val scale = targetPx.toFloat() / longerSide
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            bmp.recycle()
            scaled
          } else {
            bmp
          }
        } else {
          bmp
        }
      }
      val framesUsed = frameBitmaps.size
      var tokenCount = 0

      ttsBuffer.set("")
      try {
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { token, done, _ ->
            if (!done) {
              responseBuilder.append(token)
              tokenCount++
              handleStreamingToken(token)
              if (tokenCount % 3 == 0) {
                _uiState.update { it.copy(lastResponse = responseBuilder.toString()) }
              }
            } else {
              val finalResponse = responseBuilder.toString()
              val latency = System.currentTimeMillis() - t0
              flushTtsBuffer()
              _uiState.update {
                val history =
                  (it.chatHistory +
                      ChatEntry(
                        query = if (query.isNotEmpty()) query else mode.label,
                        response = finalResponse,
                      ))
                    .takeLast(MAX_CHAT_HISTORY)
                it.copy(
                  isProcessing = false,
                  lastResponse = finalResponse,
                  chatHistory = history,
                  inferenceLatencyMs = latency,
                  gazePreprocessMs = gazePreprocessMs,
                  framesUsed = framesUsed,
                )
              }
              frameBitmaps.forEach { bmp -> runCatching { bmp.recycle() } }
              maybeRestartListening()
            }
          },
          cleanUpListener = {},
          onError = { error ->
            _uiState.update { it.copy(isProcessing = false, lastResponse = "Error: $error") }
            frameBitmaps.forEach { bmp -> runCatching { bmp.recycle() } }
            maybeRestartListening()
          },
          images = frameBitmaps,
          coroutineScope = viewModelScope,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Inference failed", e)
        _uiState.update { it.copy(isProcessing = false, lastResponse = "Error: ${e.message}") }
        frameBitmaps.forEach { bmp -> runCatching { bmp.recycle() } }
      }
    }
  }

  private fun startAutoCaption(model: Model?) {
    stopAutoCaption()
    if (model == null) return
    autoCaptionJob = viewModelScope.launch {
      while (_uiState.value.autoCaptionEnabled) {
        if (!_uiState.value.isProcessing) {
          runInference(model)
        }
        delay(_uiState.value.mode.autoIntervalMs)
      }
    }
  }

  private fun stopAutoCaption() {
    autoCaptionJob?.cancel()
    autoCaptionJob = null
  }

  private fun stopAllBackgroundJobs() {
    stopAutoCaption()
    stopSmartCamera()
  }

  override fun onCleared() {
    super.onCleared()
    stopAllBackgroundJobs()
    stopGazeTracking()
    continuousListenJob?.cancel()
    speechRecognizer?.destroy()
    speechRecognizer = null
    tts?.shutdown()
    tts = null
    frameBuffer.clear()
  }
}
