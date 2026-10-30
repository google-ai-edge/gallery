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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherInitStep
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playBilingualDialogue
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playTargetPhrase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.cancelActiveLessonTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.captureSessionBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.runLessonTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.CameraBottomAsrControls
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.CameraInitializingOverlay
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.InitialAsrTopGuidanceScrim
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.InitialThinkingBottomQuestionOverlay
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.SwitchToXrPill
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.TeacherSettingsButton
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.TopTeacherHudBubble
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.TopThinkingGlowOverlay
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.CameraAccessDeniedOverlay
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val CAMERA_FEED_PREFERRED_SIZE = 640

@Composable
internal fun XrLanguageTeacherCameraViewport(
  viewModel: XrLanguageTeacherViewModel,
  model: Model,
  uiState: XrLanguageTeacherUiState,
  mainLanguage: Language,
  learnLanguage: Language,
  selectedPersona: CharacterPersona,
  isModelReady: Boolean,
  isSubsystemWarming: Boolean,
  isLiveCameraFeedActive: Boolean,
  isInitialThinking: Boolean,
  initSteps: List<TeacherInitStep>,
  isCameraPermissionDenied: Boolean,
  isMicPermissionDenied: Boolean,
  onLiveCameraPermissionDeniedChanged: (Boolean) -> Unit,
  onShowCameraPermissionDeniedDialog: () -> Unit,
  onOpenPreferencesDialog: () -> Unit,
  onSettingsButtonBoundsChanged: (IntRect) -> Unit,
  cameraUsesText: Boolean,
  onCameraUsesTextChanged: (Boolean) -> Unit,
  onTakePhoto: () -> Unit,
  onInputRowBoundsChanged: (IntRect) -> Unit,
  latestLiveBitmapRef: AtomicReference<Bitmap?>,
  pendingInitialQuestionRef: AtomicReference<String?>,
  onRunningStateChanged: (Boolean) -> Unit,
  onStartMicRecordingWithPermission: () -> Unit,
  onStopAndSubmitRecording: () -> Unit,
  onSubmitSpokenQuestion: (String) -> Unit,
  onSwitchToXr: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val isCameraFrozen = uiState.isCameraFrozen
  val isInitialAsrRecording = !uiState.isSessionActive && uiState.isRecordingAsr
  val isThinkingGlowVisible =
    uiState.overlayState == TeacherOverlayState.IDENTIFYING ||
      uiState.overlayState == TeacherOverlayState.THINKING ||
      (uiState.isRunningInference && uiState.isWaitingForFirstToken)
  val frozenImageBitmap =
    remember(uiState.capturedBitmap) {
      uiState.capturedBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
  val hudDialogue =
    if (uiState.isSessionActive) uiState.teacherDialogue else uiState.sessionGreeting

  Box(modifier = modifier.fillMaxSize()) {
    if (isLiveCameraFeedActive) {
      DisposableEffect(Unit) { onDispose { latestLiveBitmapRef.set(null) } }
      LiveCameraView(
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        preferredSize = CAMERA_FEED_PREFERRED_SIZE,
        isPaused = false,
        fallBackToAvailableCamera = true,
        skipFramesWhilePaused = true,
        onBitmap = { bitmap, imageProxy ->
          latestLiveBitmapRef.set(bitmap)
          val pendingQuery = pendingInitialQuestionRef.getAndSet(null)
          if (pendingQuery != null) {
            val capturedCopy = bitmap.frozenCopy() ?: bitmap
            ContextCompat.getMainExecutor(context).execute {
              viewModel.runLessonTurn(
                model = model,
                spokenQuestion = pendingQuery,
                capturedBitmap = capturedCopy,
                onRunningStateChanged = onRunningStateChanged,
              )
            }
          }
          imageProxy.close()
        },
        modifier = Modifier.fillMaxSize(),
        onPermissionDeniedChanged = onLiveCameraPermissionDeniedChanged,
        permissionDeniedContent = {
          if (!isSubsystemWarming && !isCameraFrozen) {
            CameraAccessDeniedOverlay(modifier = Modifier.padding(bottom = 140.dp))
          }
        },
      )
    }

    val cameraScrimColor = MaterialTheme.colorScheme.scrim
    if (isCameraFrozen && frozenImageBitmap != null) {
      Image(
        bitmap = frozenImageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      // Subtle dark scrim over frozen camera frame during active sessions (Mockups c–f)
      if (uiState.isSessionActive) {
        Box(modifier = Modifier.fillMaxSize().background(cameraScrimColor.copy(alpha = 0.35f)))
      }
    }

    // Canonical full-surface loading view matching AI Chat and AR Translator while the model,
    // speech recognition and voice start. It has no timeout. A failed engine shows the speech
    // engine error popup on top while the others finish.
    if (isSubsystemWarming) {
      CameraInitializingOverlay(steps = initSteps)
    } else {
      // Subtle bottom contrast scrim for bright camera feeds in idle/recording states
      if (!uiState.isSessionActive && !isInitialThinking) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(220.dp)
              .align(Alignment.BottomCenter)
              .background(
                // Dark enough at the bottom for the action row to read well.
                brush =
                  Brush.verticalGradient(
                    colors =
                      listOf(
                        Color.Transparent,
                        cameraScrimColor.copy(alpha = 0.5f),
                        cameraScrimColor.copy(alpha = 0.85f),
                      )
                  )
              )
        )
      }

      // Mockup (a): Top dark gradient scrim with guidance text while recording initial
      // question
      AnimatedVisibility(
        visible = isInitialAsrRecording,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
        modifier = Modifier.align(Alignment.TopCenter),
      ) {
        InitialAsrTopGuidanceScrim()
      }

      // Mockups (b) & (e): Top atmospheric blue glow with "Thinking..." header
      AnimatedVisibility(
        visible = isThinkingGlowVisible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(180)),
        modifier = Modifier.align(Alignment.TopCenter),
      ) {
        TopThinkingGlowOverlay()
      }

      // Top section with the Teacher HUD. Below its bottom edge, the XR switch pill sits on
      // the left and the settings button on the right.
      Column(
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Mockups (c) & (f): Top Teacher HUD Bubble when dialogue is available (hidden during
        // follow-up ASR recording in Mockup d, and hidden when camera access is off before a
        // session starts). Before the first question it shows the session greeting.
        val isHudDialogueVisible =
          !isThinkingGlowVisible &&
            !uiState.isRecordingAsr &&
            hudDialogue.isNotBlank() &&
            (!isCameraPermissionDenied || uiState.isSessionActive)
        AnimatedVisibility(
          visible = isHudDialogueVisible,
          enter = fadeIn(animationSpec = tween(220)),
          exit = fadeOut(animationSpec = tween(160)),
        ) {
          TopTeacherHudBubble(
            dialogueText = hudDialogue,
            persona = selectedPersona,
            isSpeakingTts = uiState.isSpeakingDialogue,
            onToggleTts = {
              when {
                uiState.isSpeakingDialogue -> viewModel.stopTtsPlayback()
                uiState.isSessionActive ->
                  viewModel.playBilingualDialogue(
                    dialogueWithTargetTags = uiState.teacherDialogue,
                    hostLanguage = mainLanguage,
                    targetLanguage = learnLanguage,
                    persona = selectedPersona,
                  )
                else -> viewModel.playSessionGreeting()
              }
            },
            onPlayTargetPhrase = { phrase -> viewModel.playTargetPhrase(phrase, learnLanguage) },
            mainLanguage = mainLanguage,
            learnLanguage = learnLanguage,
            ttsEngine = uiState.ttsEngine,
            ttsReadiness = uiState.ttsReadiness,
            asrEngine = uiState.asrEngine,
            asrReadiness = uiState.asrReadiness,
          )
        }

        AnimatedVisibility(
          visible = !isThinkingGlowVisible,
          enter = fadeIn(animationSpec = tween(220)),
          exit = fadeOut(animationSpec = tween(160)),
        ) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (isHudDialogueVisible) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (!isInitialAsrRecording) {
              SwitchToXrPill(
                isGlassesConnected = uiState.isGlassesConnected,
                enabled = !uiState.isRunningInference,
                onClick = onSwitchToXr,
              )
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(
              visible = !uiState.isRecordingAsr,
              enter = fadeIn(animationSpec = tween(220)),
              exit = fadeOut(animationSpec = tween(160)),
            ) {
              TeacherSettingsButton(
                onClick = {
                  if (isCameraPermissionDenied) {
                    onShowCameraPermissionDeniedDialog()
                  } else {
                    onOpenPreferencesDialog()
                  }
                },
                enabled = !uiState.isRunningInference,
                appearDisabled = isCameraPermissionDenied,
                modifier =
                  Modifier.onGloballyPositioned { coordinates ->
                    onSettingsButtonBoundsChanged(coordinates.windowBounds())
                  },
              )
            }
          }
        }
      }
      // Mockup (b): User's transcribed question at bottom of frozen camera frame during Turn 1
      // thinking
      AnimatedVisibility(
        visible = isInitialThinking,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        InitialThinkingBottomQuestionOverlay(
          questionText = uiState.lastSpokenQuestion.orEmpty(),
          onStopThinking = { viewModel.cancelActiveLessonTurn(model) },
        )
      }

      // Idle camera input: the 1-tap action row (camera button, wide mic button and keyboard
      // button, or camera button, prompt box and mic button). Mockup (a) dark waveform pill
      // bar while recording.
      if (!uiState.isSessionActive && !isInitialThinking) {
        val canAsk = isModelReady && !uiState.isRunningInference
        // imePadding() only adds the part of the keyboard height not already covered by the
        // host's bottom padding, which XrLanguageTeacherTask consumes for its descendants.
        CameraBottomAsrControls(
          isRecordingAsr = uiState.isRecordingAsr,
          liveTranscript = uiState.liveAsrTranscript,
          confirmedTranscript = uiState.confirmedTranscript,
          audioLevel = uiState.audioLevel,
          enabled = canAsk,
          appearDisabled = isCameraPermissionDenied,
          micAppearDisabled = isCameraPermissionDenied || isMicPermissionDenied,
          onStartRecording = {
            if (isCameraPermissionDenied) {
              onShowCameraPermissionDeniedDialog()
            } else {
              onStartMicRecordingWithPermission()
            }
          },
          onCancelRecording = {
            viewModel.cancelAsrRecording()
            if (!uiState.isSessionActive) {
              viewModel.captureSessionBitmap(null)
            }
          },
          onSendRecording = {
            onStopAndSubmitRecording()
          },
          usesText = cameraUsesText,
          onUsesTextChange = { usesText ->
            if (isCameraPermissionDenied) {
              onShowCameraPermissionDeniedDialog()
            } else {
              onCameraUsesTextChanged(usesText)
            }
          },
          onSendText = { question ->
            if (isCameraPermissionDenied) {
              onShowCameraPermissionDeniedDialog()
            } else {
              onSubmitSpokenQuestion(question)
            }
          },
          onTakePhoto = onTakePhoto,
          onDisabledClick = onShowCameraPermissionDeniedDialog,
          inputRowModifier =
            Modifier.onGloballyPositioned { coordinates ->
              onInputRowBoundsChanged(coordinates.windowBounds())
            },
          modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
        )
      }
    }
  }
}

/**
 * Returns whether any AR Language Teacher subsystem (Gemma model, ASR engine, or TTS engine) is
 * still initializing or warming up.
 */
internal fun isTeacherSubsystemWarming(
  isModelInitializing: Boolean,
  asrReadiness: SpeechEngineReadiness,
  ttsReadiness: SpeechEngineReadiness,
): Boolean =
  isModelInitializing ||
    asrReadiness == SpeechEngineReadiness.WARMING ||
    ttsReadiness == SpeechEngineReadiness.WARMING

/**
 * Whether the persona voice preview button can be tapped: the voice is ready, and the teacher model
 * isn't loading, so a preview doesn't compete with model loading.
 */
internal fun isPersonaPreviewAvailable(
  isModelInitializing: Boolean,
  ttsReadiness: SpeechEngineReadiness,
): Boolean = !isModelInitializing && ttsReadiness == SpeechEngineReadiness.READY

/**
 * Whether a lesson turn is still using the model: Pass 1 is generating the reply, or Pass 2 is
 * extracting the vocabulary card. Leaving the screen closes the model, so back navigation waits
 * until the turn ends. While Pass 1 is thinking the Stop button ends the turn in place; once the
 * reply streams there is no Stop button, and only "Ask about something else" or closing the lesson
 * sheet (both reset the session) end it early.
 */
internal fun isLessonTurnInferring(uiState: XrLanguageTeacherUiState): Boolean =
  uiState.isRunningInference || uiState.isExtractingCard

/**
 * Keeps the app bar controls ([setAppBarControlsDisabled]) disabled while a lesson turn runs, the
 * speech engines warm up, or the mic records, and returns whether they (and the bottom tabs) are
 * enabled. [uiState] alone drives the app bar.
 *
 * The app bar's back button is disabled with the other controls, and while a lesson turn runs the
 * system back gesture is held too (with no feedback), since leaving closes the model while the turn
 * is still using it. Unlike the app bar, the gesture still leaves while the speech engines warm up
 * or the mic records, which don't use the model.
 */
@Composable
internal fun rememberTurnGate(
  uiState: XrLanguageTeacherUiState,
  isSubsystemWarming: Boolean,
  setAppBarControlsDisabled: (Boolean) -> Unit,
): Boolean {
  val isTurnInferring = isLessonTurnInferring(uiState)
  val navButtonsEnabled = !isTurnInferring && !uiState.isRecordingAsr && !isSubsystemWarming
  val latestSetAppBarControlsDisabled by rememberUpdatedState(setAppBarControlsDisabled)
  LaunchedEffect(navButtonsEnabled) { latestSetAppBarControlsDisabled(!navButtonsEnabled) }
  BackHandler(enabled = isTurnInferring) {}
  return navButtonsEnabled
}

/**
 * Whether the active lesson bottom sheet (vocab card + follow-up controls) is shown: a lesson is in
 * progress, the first reply is no longer pending, and the teacher has answered at least once (or is
 * not generating).
 */
internal fun isActiveLessonSheetVisible(uiState: XrLanguageTeacherUiState): Boolean {
  val hasPriorAssistantTurn = uiState.vocabCardData != null || uiState.chatTurns.any { !it.isUser }
  val hasStreamedReply = !uiState.isWaitingForFirstToken && uiState.teacherDialogue.isNotBlank()
  val hasCompletedOrStreamingTurn =
    hasPriorAssistantTurn || hasStreamedReply || !uiState.isRunningInference
  val isInitialThinking =
    !hasPriorAssistantTurn &&
      (uiState.overlayState == TeacherOverlayState.IDENTIFYING ||
        uiState.overlayState == TeacherOverlayState.THINKING ||
        (uiState.isRunningInference && uiState.isWaitingForFirstToken))
  return uiState.isSessionActive && !isInitialThinking && hasCompletedOrStreamingTurn
}

internal fun Bitmap?.frozenCopy(): Bitmap? =
  this?.takeUnless { it.isRecycled }?.copy(config ?: Bitmap.Config.ARGB_8888, false)

/** Returns this layout's bounds in window coordinates. */
private fun LayoutCoordinates.windowBounds(): IntRect {
  val position = positionInWindow()
  return IntRect(offset = IntOffset(position.x.roundToInt(), position.y.roundToInt()), size = size)
}
