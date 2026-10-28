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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.clearAsrTranscript
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.isAsrRecordingOrPending
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.onNoSpeechHeard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.startAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.GlassesGesture
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.GlassesPermissionRequest
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.XrEarconType
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.flow.update

internal fun XrLanguageTeacherViewModel.checkMissingGlassesPermissions(
  permissions: List<String>
): GlassesPermissionRequest? = xrGlassesManager.checkMissingGlassesPermissions(permissions)

/**
 * Switches to XR Glasses mode if the feature flag is enabled and glasses are connected. The screen
 * registers the glasses tap listener once [XrLanguageTeacherUiState.isXrMode] is true.
 */
fun XrLanguageTeacherViewModel.switchToXr() {
  val enableXr = true
  Log.i(
    TAG,
    "switchToXr: enableXr=$enableXr, connected=${xrGlassesManager.isGlassesConnected.value}",
  )
  if (!enableXr) return
  if (!xrGlassesManager.isGlassesConnected.value) {
    showGlassesDisconnectedMessage()
    return
  }
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  }
  _uiState.update { it.copy(isXrMode = true) }
  xrGlassesManager.startGlassesCameraStreaming()
  xrGlassesManager.playEarcon(XrEarconType.APP_OPEN)
}

/** Switches back to Phone camera mode and releases the glasses camera, mic, and tap listener. */
fun XrLanguageTeacherViewModel.switchToPhone(isDisconnect: Boolean = false) {
  if (!_uiState.value.isXrMode) return
  isSubmittingXrTurn = false
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  }
  stopTtsPlayback()
  xrGlassesManager.stopGlassesCameraStreaming()
  xrGlassesManager.unregisterGlassesTapListener()
  _uiState.update {
    it.copy(
      isXrMode = false,
      errorMessage =
        if (isDisconnect) {
          storageRepository.getString(R.string.xrteacher_xr_disconnected)
        } else {
          it.errorMessage
        },
    )
  }
}

/** Displays a snackbar notification when the user taps the disabled XR pill while disconnected. */
fun XrLanguageTeacherViewModel.showGlassesDisconnectedMessage() {
  _uiState.update {
    it.copy(errorMessage = storageRepository.getString(R.string.xrteacher_xr_no_glasses))
  }
}

/** Displays a snackbar notification when required glasses virtual-device permissions are denied. */
fun XrLanguageTeacherViewModel.showGlassesPermissionRequiredMessage() {
  _uiState.update {
    it.copy(errorMessage = storageRepository.getString(R.string.xrteacher_xr_permission_required))
  }
}

/**
 * Routes glasses touchpad gestures to [handleGlassesGesture] for [model]. Safe to call again (for
 * example when the model changes): the new listener replaces the old one, and the glasses-side
 * activity is only launched if it isn't already running.
 */
fun XrLanguageTeacherViewModel.setupXrTapListener(
  model: Model,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  xrGlassesManager.registerGlassesGestureListener { gesture ->
    handleGlassesGesture(
      gesture = gesture,
      model = model,
      onRunningStateChanged = onRunningStateChanged,
    )
  }
}

/** Convenience helper delegating a single touchpad tap to [handleGlassesGesture]. */
fun XrLanguageTeacherViewModel.handleGlassesTap(
  model: Model,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  handleGlassesGesture(
    gesture = GlassesGesture.TAP,
    model = model,
    onRunningStateChanged = onRunningStateChanged,
  )
}

/**
 * Handles a physical touchpad gesture from the glasses.
 *
 * Gesture state machine:
 * - **Thinking (inference running or a turn being submitted)**: busy earcon, nothing else.
 * - **Preparing (ASR requested but the engine is not ready yet)**: single tap plays stop-sent
 *   earcon and cancels the pending start before the microphone opens; forward swipe is ignored.
 * - **Recording**: single tap ([GlassesGesture.TAP]) plays stop-sent earcon, finishes ASR, and
 *   submits the transcript (with the captured glasses camera frame for a new session); forward
 *   swipe ([GlassesGesture.SWIPE_FORWARD]) is ignored while recording.
 * - **Idle or teacher speaking**:
 *     - [GlassesGesture.TAP]: interrupts TTS if speaking, resets active session if one is active,
 *       captures the latest glasses camera frame, plays start earcon, and starts ASR for a new
 *       object scan.
 *     - [GlassesGesture.SWIPE_FORWARD]: requires an active session (plays error earcon otherwise),
 *       interrupts TTS if speaking, keeps active session context, plays start earcon, and starts
 *       ASR for a follow-up question.
 */
fun XrLanguageTeacherViewModel.handleGlassesGesture(
  gesture: GlassesGesture,
  model: Model,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  val state = _uiState.value
  Log.i(
    TAG,
    "handleGlassesGesture($gesture): isXrMode=${state.isXrMode}, sessionActive=${state.isSessionActive}, " +
      "recordingAsr=${state.isRecordingAsr}, runningInference=${state.isRunningInference}, " +
      "submittingTurn=$isSubmittingXrTurn, speakingTts=${state.isSpeakingTts}",
  )
  if (!state.isXrMode) return

  // 1. Thinking / Busy if inference is running or a finished utterance is being submitted.
  if (state.isRunningInference || isSubmittingXrTurn) {
    xrGlassesManager.playEarcon(XrEarconType.BUSY)
    return
  }

  // 2a. While the ASR engine is still preparing a requested turn: single tap cancels the pending
  // start before the microphone opens; swipe forward is ignored.
  if (!state.isRecordingAsr && asrRecordingJob?.isActive == true) {
    if (gesture != GlassesGesture.TAP) return
    xrGlassesManager.playEarcon(XrEarconType.STOP_RECORDING_SENT)
    cancelAsrRecording()
    return
  }

  // 2b. While recording: single tap stops recording and submits; swipe forward is ignored.
  if (state.isRecordingAsr) {
    if (gesture != GlassesGesture.TAP) return
    xrGlassesManager.playEarcon(XrEarconType.STOP_RECORDING_SENT)
    val fallbackTranscript = state.liveAsrTranscript.trim()
    isSubmittingXrTurn = true
    stopAsrRecording { transcript ->
      isSubmittingXrTurn = false
      val spokenQuestion = transcript.ifBlank { fallbackTranscript }
      clearAsrTranscript()
      if (spokenQuestion.isBlank() && _uiState.value.isSessionActive) {
        // Nothing was heard, so there is no follow-up to answer. Swipe forward to retry.
        onNoSpeechHeard()
        return@stopAsrRecording
      }
      // A blank opening turn runs without a question, like the phone, so the model identifies the
      // focal object.
      val capturedBitmap =
        if (_uiState.value.isSessionActive) {
          null
        } else {
          _uiState.value.capturedBitmap
            ?: xrGlassesManager.glassesCameraBitmap.value?.let { bmp ->
              if (!bmp.isRecycled) bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) else null
            }
        }
      runLessonTurn(
        model = model,
        spokenQuestion = spokenQuestion,
        capturedBitmap = capturedBitmap,
        onRunningStateChanged = onRunningStateChanged,
      )
    }
    return
  }

  // 3. Idle or TTS speaking: distinguish single tap (new scan) vs swipe forward (follow-up).
  when (gesture) {
    GlassesGesture.TAP -> {
      if (state.isSpeakingTts) {
        stopTtsPlayback()
      }
      if (state.isSessionActive) {
        val preservedGreeting = state.sessionGreetingLine
        resetLessonSession(model = model, onRunningStateChanged = onRunningStateChanged)
        if (preservedGreeting != null) {
          _uiState.update { it.copy(sessionGreetingLine = preservedGreeting) }
        }
      }
      xrGlassesManager.glassesCameraBitmap.value?.let { bmp ->
        if (!bmp.isRecycled) {
          captureSessionBitmap(
            bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false),
            model = model,
          )
        }
      }
      Log.i(
        TAG,
        "Starting ASR from glasses TAP (hasCapturedBitmap=${_uiState.value.capturedBitmap != null})",
      )
      xrGlassesManager.playEarcon(XrEarconType.START_RECORDING)
      startAsrRecording()
    }
    GlassesGesture.SWIPE_FORWARD -> {
      if (!state.isSessionActive) {
        xrGlassesManager.playEarcon(XrEarconType.ERROR)
        return
      }
      if (state.isSpeakingTts) {
        stopTtsPlayback()
      }
      Log.i(TAG, "Starting follow-up ASR from glasses SWIPE_FORWARD")
      xrGlassesManager.playEarcon(XrEarconType.START_RECORDING)
      startAsrRecording()
    }
  }
}
