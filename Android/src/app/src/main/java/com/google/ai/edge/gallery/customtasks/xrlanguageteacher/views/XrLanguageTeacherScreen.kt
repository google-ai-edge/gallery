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

@file:Suppress("DEPRECATION")

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherInitStepStatus
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageAdjustment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.saveCurrentVocabCard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.teacherInitSteps
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toNameResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.unsaveVocabCard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.unsupportedNativeLanguages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.clearAsrTranscript
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.isAsrRecordingOrPending
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.onNoSpeechHeard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playPersonaPreview
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playTargetPhrase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareTtsEngineIfEvicted
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.startAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopPersonaPreview
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.applyTeacherPreferences
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.cancelActiveLessonTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.captureSessionBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.checkMissingGlassesPermissions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.clearErrorMessage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.getPersonaPromptTemplate
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.handleGlassesTap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.registerModelForSessionPrewarm
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.resetLessonSession
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.runLessonTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.selectVocabCard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.setupXrTapListener
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.showGlassesDisconnectedMessage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.showGlassesPermissionRequiredMessage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.switchToPhone
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.switchToXr
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.ActiveLessonBottomSheet
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.TeacherInputMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.TeacherSettingsTooltip
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.XrGlassesModeScreen
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview.xrPipelineStatus
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.rememberGlassesPermissionRequester
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import com.google.ai.edge.gallery.ui.common.PermissionAccessKind
import com.google.ai.edge.gallery.ui.common.SpeechEngineErrorDialog
import com.google.ai.edge.gallery.ui.common.rememberOpenSpeechServicesStorePage
import com.google.ai.edge.gallery.ui.common.rememberPermissionAccessState
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * Height of the lesson sheet's header row (close button and its centered title) and the padding
 * around it.
 */
private val LESSON_SHEET_HEADER_PEEK_HEIGHT = 64.dp

/**
 * Camera view of AR Language Teacher.
 *
 * @param canGreet Whether the teacher may greet the user. False while the first-time introduction
 *   dialog is shown so the greeting uses the persona and languages chosen there.
 * @param canShowSpeechEngineError Whether the speech engine error popup may show. False while the
 *   first-time introduction dialog is shown, where the user picks and downloads languages.
 * @param showSettingsTooltip Whether to show the one-time tooltip on the language and teacher
 *   settings button once the teacher is ready.
 * @param onSettingsTooltipDismissed Called when the user dismisses the tooltip.
 * @param onLessonSheetShownChanged Called with whether the lesson bottom sheet is on screen,
 *   including while it animates in or out.
 * @param onInitializingChanged Called with whether the teacher model, speech recognition or voice
 *   is still starting, so the host can disable navigation meanwhile.
 * @param onChooseAnotherModel Called with the model selection section to open when the user chooses
 *   another speech model from the speech engine error popup.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun XrLanguageTeacherScreen(
  viewModel: XrLanguageTeacherViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  onRunningStateChanged: (Boolean) -> Unit = {},
  canGreet: Boolean = true,
  canShowSpeechEngineError: Boolean = true,
  showSettingsTooltip: Boolean = false,
  onSettingsTooltipDismissed: () -> Unit = {},
  onLessonSheetShownChanged: (Boolean) -> Unit = {},
  onInitializingChanged: (Boolean) -> Unit = {},
  onChooseAnotherModel: (ModelSelectionTarget) -> Unit = {},
) {
  val haptic = LocalHapticFeedback.current

  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val model = modelManagerUiState.selectedModel

  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val mainLanguage by viewModel.mainLanguage.collectAsStateWithLifecycle()
  val learnLanguage by viewModel.learnLanguage.collectAsStateWithLifecycle()
  val selectedPersona by viewModel.selectedPersona.collectAsStateWithLifecycle()
  val availableLanguages by viewModel.availableLanguages.collectAsStateWithLifecycle()
  val availableNativeLanguages by viewModel.availableNativeLanguages.collectAsStateWithLifecycle()
  val speechEngineLocales by viewModel.selectedSpeechEngineLocales.collectAsStateWithLifecycle()
  val dictionaryEntries by viewModel.dictionaryEntries.collectAsStateWithLifecycle()
  val glassesCameraBitmap by viewModel.glassesCameraBitmap.collectAsStateWithLifecycle()

  val initStatus by model.initStatusFlow.collectAsStateWithLifecycle()
  val isInitializing = initStatus is Model.InitializationStatus.Initializing
  val isModelReady = initStatus is Model.InitializationStatus.Initialized

  var showPreferencesDialog by rememberSaveable { mutableStateOf(false) }
  // Window bounds of the language and teacher settings button, for its one-time tooltip.
  var settingsButtonBounds by remember { mutableStateOf<IntRect?>(null) }
  // How the user asks about what the camera sees; Audio (speech recognition) by default.
  var inputMode by rememberSaveable { mutableStateOf(TeacherInputMode.AUDIO) }
  // Whether follow-up questions are typed rather than spoken. It follows the way the user just
  // asked, and the switch button in the lesson sheet changes it.
  var followUpUsesText by rememberSaveable { mutableStateOf(false) }
  val latestLiveBitmapRef = remember { AtomicReference<Bitmap?>(null) }
  val pendingInitialQuestionRef = remember { AtomicReference<String?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }

  val fallbackErrorText = stringResource(R.string.xrteacher_inference_error_fallback)

  LaunchedEffect(uiState.errorMessage) {
    val error = uiState.errorMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(error.ifBlank { fallbackErrorText })
    viewModel.clearErrorMessage()
  }

  val isSubsystemWarming =
    isTeacherSubsystemWarming(
      isModelInitializing = isInitializing,
      asrReadiness = uiState.asrReadiness,
      ttsReadiness = uiState.ttsReadiness,
    )

  // When the saved languages don't work with newly selected speech models, Teacher settings opens
  // with a note explaining why once initialization finishes, so the student picks again before
  // using the camera and the persona voice preview is ready.
  val languageNotice by viewModel.languageNotice.collectAsStateWithLifecycle()
  var pendingLanguageNotice by remember { mutableStateOf<TeacherLanguageAdjustment?>(null) }
  LaunchedEffect(languageNotice, canShowSpeechEngineError, isSubsystemWarming) {
    val notice = languageNotice ?: return@LaunchedEffect
    // Not while the first-time introduction, where languages are picked anyway, is shown, or while
    // the teacher model and speech engines are still initializing.
    if (!canShowSpeechEngineError || isSubsystemWarming) return@LaunchedEffect
    pendingLanguageNotice = notice
    showPreferencesDialog = true
  }
  val activeLanguageNotice = pendingLanguageNotice ?: languageNotice
  val hasLanguageNoticeToPresent = canShowSpeechEngineError && activeLanguageNotice != null
  val nativeLanguageNotice =
    activeLanguageNotice?.unsupportedMain?.let { language ->
      stringResource(
        R.string.xrteacher_rechoose_native_language,
        stringResource(language.toNameResId()),
      )
    }
  val learnLanguageNotice =
    activeLanguageNotice?.unsupportedLearn?.let { language ->
      stringResource(
        R.string.xrteacher_rechoose_learn_language,
        stringResource(language.toNameResId()),
      )
    }

  fun submitSpokenQuestion(rawTranscript: String) {
    // A blank transcript is sent as-is (normalized to "no question" by runLessonTurn) so the first
    // turn falls back to the prompt's identify-the-object directive instead of a hardcoded
    // English question.
    val textToSend = rawTranscript.trim()
    viewModel.clearAsrTranscript()

    if (uiState.isSessionActive) {
      // Nothing was heard, so there is no follow-up question to answer.
      if (textToSend.isEmpty()) {
        viewModel.onNoSpeechHeard()
        return
      }
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      viewModel.runLessonTurn(
        model = model,
        spokenQuestion = textToSend,
        onRunningStateChanged = onRunningStateChanged,
      )
    } else {
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      followUpUsesText = inputMode == TeacherInputMode.TEXT
      val frozenOrLiveBitmap =
        uiState.capturedBitmap?.takeUnless { it.isRecycled }
          ?: latestLiveBitmapRef.get().frozenCopy()
      if (frozenOrLiveBitmap != null && !frozenOrLiveBitmap.isRecycled) {
        viewModel.runLessonTurn(
          model = model,
          spokenQuestion = textToSend,
          capturedBitmap = frozenOrLiveBitmap,
          onRunningStateChanged = onRunningStateChanged,
        )
      } else {
        pendingInitialQuestionRef.set(textToSend)
      }
    }
  }

  val micPermissionState = rememberPermissionAccessState(PermissionAccessKind.MICROPHONE)
  val cameraPermissionState = rememberPermissionAccessState(PermissionAccessKind.CAMERA)
  var isLiveCameraPermissionDenied by remember { mutableStateOf(false) }
  val isCameraPermissionDenied = isLiveCameraPermissionDenied || cameraPermissionState.isDenied
  val isCameraPermissionGranted = cameraPermissionState.isGranted && !isCameraPermissionDenied
  val isMicPermissionDenied = micPermissionState.isDenied

  fun startMicRecordingWithPermission() {
    if (micPermissionState.isDenied) {
      micPermissionState.showDeniedDialog()
      return
    }
    if (uiState.hasSpeechEngineError) {
      // The lesson is paused until the failed speech engine works again.
      viewModel.reshowSpeechEngineError()
      return
    }
    micPermissionState.requestOrRun {
      if (!uiState.isSessionActive) {
        viewModel.captureSessionBitmap(latestLiveBitmapRef.get().frozenCopy(), model = model)
      }
      viewModel.startAsrRecording()
    }
  }

  val requestGlassesPermissionsOrSwitch =
    rememberGlassesPermissionRequester(
      checkMissingPermissions = viewModel::checkMissingGlassesPermissions,
      onPermissionsGranted = {
        viewModel.setupXrTapListener(model, onRunningStateChanged)
        viewModel.switchToXr()
      },
      onPermissionsDenied = viewModel::showGlassesPermissionRequiredMessage,
    )

  fun handleSwitchToXr() {
    if (isCameraPermissionDenied) {
      cameraPermissionState.showDeniedDialog()
      return
    }
    if (!uiState.isGlassesConnected) {
      viewModel.showGlassesDisconnectedMessage()
      return
    }
    requestGlassesPermissionsOrSwitch()
  }

  LaunchedEffect(uiState.isXrMode, model) {
    if (uiState.isXrMode) {
      viewModel.setupXrTapListener(model, onRunningStateChanged)
    }
  }

  fun stopAndSubmitRecording() {
    val fallbackTranscript = uiState.liveAsrTranscript.trim()
    viewModel.stopAsrRecording { transcript ->
      submitSpokenQuestion(transcript.ifBlank { fallbackTranscript })
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      latestLiveBitmapRef.set(null)
      pendingInitialQuestionRef.set(null)
      if (viewModel.isAsrRecordingOrPending) {
        viewModel.cancelAsrRecording()
      }
      viewModel.stopTtsPlayback()
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.prepareTtsEngineIfEvicted() }
  OnAppBackgroundedEffect { viewModel.onAppBackgrounded() }

  val hasPriorAssistantTurn = uiState.vocabCardData != null || uiState.chatTurns.any { !it.isUser }
  val isInitialThinking =
    !hasPriorAssistantTurn &&
      (uiState.overlayState == TeacherOverlayState.IDENTIFYING ||
        uiState.overlayState == TeacherOverlayState.THINKING ||
        (uiState.isRunningInference && uiState.isWaitingForFirstToken))
  val isFollowUpThinking =
    hasPriorAssistantTurn &&
      (uiState.overlayState == TeacherOverlayState.THINKING ||
        (uiState.isRunningInference && uiState.isWaitingForFirstToken))

  val isLiveCameraFeedActive =
    uiState.isLiveCameraFeedActive(
      isModelInitializing = isInitializing,
      isOnboardingVisible = !canGreet,
    )
  val latestOnInitializingChanged by rememberUpdatedState(onInitializingChanged)
  LaunchedEffect(isSubsystemWarming) { latestOnInitializingChanged(isSubsystemWarming) }
  DisposableEffect(Unit) { onDispose { latestOnInitializingChanged(false) } }
  val initSteps =
    teacherInitSteps(
      teacherModelStatus =
        when (initStatus) {
          is Model.InitializationStatus.Initialized -> TeacherInitStepStatus.READY
          is Model.InitializationStatus.Failed -> TeacherInitStepStatus.FAILED
          else -> TeacherInitStepStatus.IN_PROGRESS
        },
      asrReadiness = uiState.asrReadiness,
      ttsReadiness = uiState.ttsReadiness,
    )

  // Greets the user once when the feature is first opened and Gemma, ASR, TTS, and Camera are
  // ready, after any Teacher settings dialog closes.
  viewModel.setCameraAccessDenied(!isCameraPermissionGranted)
  val needsGreeting = uiState.sessionGreeting.isBlank()
  LaunchedEffect(
    isModelReady,
    isSubsystemWarming,
    needsGreeting,
    canGreet,
    isCameraPermissionGranted,
    showPreferencesDialog,
    hasLanguageNoticeToPresent,
  ) {
    if (
      isModelReady &&
        !isSubsystemWarming &&
        needsGreeting &&
        canGreet &&
        isCameraPermissionGranted &&
        !showPreferencesDialog &&
        !hasLanguageNoticeToPresent
    ) {
      viewModel.greetNewSessionIfNeeded()
    }
  }
  LaunchedEffect(
    isCameraPermissionDenied,
    isCameraPermissionGranted,
    uiState.isSessionActive,
    uiState.isSpeakingDialogue,
    uiState.isSpeakingTts,
  ) {
    viewModel.setCameraAccessDenied(!isCameraPermissionGranted)
    if (isCameraPermissionDenied) {
      showPreferencesDialog = false
    }
    if (
      isCameraPermissionDenied &&
        !uiState.isSessionActive &&
        (uiState.isSpeakingDialogue || uiState.isSpeakingTts)
    ) {
      viewModel.stopTtsPlayback()
    }
  }

  LaunchedEffect(model, isModelReady) {
    if (isModelReady) {
      viewModel.registerModelForSessionPrewarm(model)
    }
  }

  fun scanNewObject() {
    viewModel.resetLessonSession(model = model, onRunningStateChanged = onRunningStateChanged)
  }

  // Mockups (c), (d), (e), (f): the vocab card and follow-up controls live in a standard
  // (non-modal)
  // Material 3 bottom sheet, so the HUD and buttons behind it stay interactive.
  val isLessonSheetRequested = isActiveLessonSheetVisible(uiState)
  val lessonSheetState =
    rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
  val lessonScaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = lessonSheetState)
  LaunchedEffect(isLessonSheetRequested, uiState.vocabCardData != null) {
    if (isLessonSheetRequested) {
      // A child job, so a user drag that cancels the animation does not stop the observer below.
      launch { lessonSheetState.expand() }
      // Swiping the sheet all the way down ends the lesson, like its close button. The sheet
      // counts as swiped away once it has started showing and then settles hidden, even if the
      // swipe interrupted the expand animation.
      var hasStartedShowing = false
      snapshotFlow { lessonSheetState.currentValue to lessonSheetState.targetValue }
        .collect { (currentValue, targetValue) ->
          when {
            currentValue != SheetValue.Hidden || targetValue != SheetValue.Hidden -> {
              hasStartedShowing = true
            }
            hasStartedShowing -> {
              hasStartedShowing = false
              scanNewObject()
            }
          }
        }
    } else {
      lessonSheetState.hide()
    }
  }
  val isLessonSheetShown =
    !isInitializing &&
      (lessonSheetState.currentValue != SheetValue.Hidden ||
        lessonSheetState.targetValue != SheetValue.Hidden)
  val latestOnLessonSheetShownChanged by rememberUpdatedState(onLessonSheetShownChanged)
  LaunchedEffect(isLessonSheetShown) { latestOnLessonSheetShownChanged(isLessonSheetShown) }
  DisposableEffect(Unit) { onDispose { latestOnLessonSheetShownChanged(false) } }
  // When collapsed, the sheet keeps its header row above the system navigation bar.
  val lessonSheetPeekHeight =
    LESSON_SHEET_HEADER_PEEK_HEIGHT +
      WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  Box(modifier = modifier.fillMaxSize()) {
    if (uiState.isXrMode) {
      XrGlassesModeScreen(
        onSwitchToPhone = { viewModel.switchToPhone() },
        onTapScreen = { viewModel.handleGlassesTap(model, onRunningStateChanged) },
        glassesCameraBitmap = glassesCameraBitmap,
        isRecording = uiState.isRecordingAsr,
        liveTranscript = uiState.liveAsrTranscript,
        isRunningInference = uiState.isRunningInference,
        isSpeakingTts = uiState.isSpeakingTts,
        teacherDialogue =
          if (uiState.isRunningInference) {
            uiState.teacherDialogue
          } else {
            uiState.teacherDialogue.ifBlank { uiState.sessionGreeting }
          },
        userQuestion = uiState.lastSpokenQuestion.orEmpty(),
        status =
          xrPipelineStatus(
            isRecording = uiState.isRecordingAsr,
            isSubmittingToModel = uiState.isSubmittingToModel,
            isRunningInference = uiState.isRunningInference,
            isWaitingForFirstToken = uiState.isWaitingForFirstToken,
            isSpeakingTts = uiState.isSpeakingTts,
            lastAsrError = uiState.lastAsrError,
          ),
      )
    } else {
      BottomSheetScaffold(
        scaffoldState = lessonScaffoldState,
        sheetPeekHeight = lessonSheetPeekHeight,
        sheetMaxWidth = Dp.Unspecified,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // ActiveLessonBottomSheet draws its own surface and header.
        sheetContainerColor = Color.Transparent,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 16.dp,
        sheetDragHandle = null,
        // Once the lesson ends the sheet only animates away, so it can't be dragged back up.
        sheetSwipeEnabled = isLessonSheetRequested,
        containerColor = Color.Transparent,
        sheetContent = {
          ActiveLessonBottomSheet(
            // While hidden, the sheet stays composed off screen; keep it out of accessibility.
            modifier = if (isLessonSheetShown) Modifier else Modifier.clearAndSetSemantics {},
            vocabCardData = uiState.vocabCardData,
            isGeneratingCard =
              uiState.isExtractingCard || (!hasPriorAssistantTurn && uiState.isRunningInference),
            capturedBitmap = uiState.capturedBitmap,
            isCurrentCardSaved = uiState.isCurrentCardSaved,
            isRecordingAsr = uiState.isRecordingAsr,
            isFollowUpThinking = isFollowUpThinking,
            liveTranscript = uiState.liveAsrTranscript,
            confirmedTranscript = uiState.confirmedTranscript,
            lastSpokenQuestion = uiState.lastSpokenQuestion.orEmpty(),
            audioLevel = uiState.audioLevel,
            enabled = !uiState.isRunningInference && !isSubsystemWarming,
            onPlayTargetPhrase = { phrase -> viewModel.playTargetPhrase(phrase, learnLanguage) },
            phrasePlaybackState = uiState.phrasePlaybackState,
            onSaveToVocab = { viewModel.saveCurrentVocabCard() },
            onUnsaveCardFromVocab = { card -> viewModel.unsaveVocabCard(card) },
            onScanNewObject = {
              scanNewObject()
            },
            onStartFollowUpRecording = {
              startMicRecordingWithPermission()
            },
            onCancelFollowUpRecording = {
              viewModel.cancelAsrRecording()
            },
            onSendFollowUpRecording = {
              stopAndSubmitRecording()
            },
            // The follow-up input starts the way the user just asked: a text box after a typed
            // question, the mic after a spoken one or a photo.
            followUpUsesText = followUpUsesText,
            onFollowUpUsesTextChange = { followUpUsesText = it },
            onSendFollowUpText = { question ->
              submitSpokenQuestion(question)
            },
            onStopThinking = { viewModel.cancelActiveLessonTurn(model) },
            isMicPermissionDenied = isMicPermissionDenied,
            vocabCards = uiState.vocabCards,
            currentVocabCardIndex = uiState.currentVocabCardIndex,
            onSelectVocabCard = { index -> viewModel.selectVocabCard(index) },
            isCardSaved = { card ->
              (card == uiState.vocabCardData && uiState.isCurrentCardSaved) ||
                dictionaryEntries.any {
                  it.learnLanguage == learnLanguage &&
                    (it.word.equals(card.word, ignoreCase = true) ||
                      XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(it.word, card.word))
                }
            },
            onSaveCardToVocab = { card -> viewModel.saveCurrentVocabCard(card) },
          )
        },
      ) { _ ->
        XrLanguageTeacherCameraViewport(
          viewModel = viewModel,
          model = model,
          uiState = uiState,
          mainLanguage = mainLanguage,
          learnLanguage = learnLanguage,
          selectedPersona = selectedPersona,
          isModelReady = isModelReady,
          isSubsystemWarming = isSubsystemWarming,
          isLiveCameraFeedActive = isLiveCameraFeedActive,
          isInitialThinking = isInitialThinking,
          initSteps = initSteps,
          isCameraPermissionDenied = isCameraPermissionDenied,
          isMicPermissionDenied = isMicPermissionDenied,
          onLiveCameraPermissionDeniedChanged = { isLiveCameraPermissionDenied = it },
          onShowCameraPermissionDeniedDialog = { cameraPermissionState.showDeniedDialog() },
          onOpenPreferencesDialog = { showPreferencesDialog = true },
          onSettingsButtonBoundsChanged = { settingsButtonBounds = it },
          inputMode = inputMode,
          onInputModeChanged = { inputMode = it },
          latestLiveBitmapRef = latestLiveBitmapRef,
          pendingInitialQuestionRef = pendingInitialQuestionRef,
          onRunningStateChanged = onRunningStateChanged,
          onStartMicRecordingWithPermission = ::startMicRecordingWithPermission,
          onStopAndSubmitRecording = ::stopAndSubmitRecording,
          onSubmitSpokenQuestion = ::submitSpokenQuestion,
          onSwitchToXr = ::handleSwitchToXr,
        )
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
    )
  }

  // One-time tooltip on the language and teacher settings button once the teacher is ready.
  val settingsAnchorBounds = settingsButtonBounds
  if (
    showSettingsTooltip &&
      settingsAnchorBounds != null &&
      isModelReady &&
      !isSubsystemWarming &&
      !isCameraPermissionDenied &&
      !uiState.isRecordingAsr &&
      !uiState.isRunningInference &&
      !showPreferencesDialog &&
      !hasLanguageNoticeToPresent &&
      uiState.speechEngineError == null &&
      !WindowInsets.isImeVisible
  ) {
    TeacherSettingsTooltip(
      anchorBounds = settingsAnchorBounds,
      onGotIt = onSettingsTooltipDismissed,
    )
  }

  if (showPreferencesDialog) {
    val languagePacks =
      rememberTeacherLanguagePacks(
        modelManagerViewModel = modelManagerViewModel,
        speechEngineLocales = speechEngineLocales,
        nativeLanguages = availableNativeLanguages,
        learnLanguages = availableLanguages,
      )
    ConfigureTeacherPreferencesDialog(
      initialPreferences =
        TeacherPreferencesDraft(
          mainLanguage = mainLanguage,
          learnLanguage = learnLanguage,
          persona = selectedPersona,
        ),
      availableLanguages = availableLanguages,
      availableNativeLanguages = availableNativeLanguages,
      getPersonaPromptTemplate = viewModel::getPersonaPromptTemplate,
      onPlayPersonaPreview = viewModel::playPersonaPreview,
      onApply = { preferences ->
        showPreferencesDialog = false
        activeLanguageNotice?.let(viewModel::acknowledgeLanguageNotice)
        pendingLanguageNotice = null
        viewModel.applyTeacherPreferences(preferences, model = model)
      },
      onDismiss = {
        showPreferencesDialog = false
        activeLanguageNotice?.let(viewModel::acknowledgeLanguageNotice)
        pendingLanguageNotice = null
      },
      personaPreviewState = uiState.personaPreviewState,
      isPersonaPreviewAvailable =
        isPersonaPreviewAvailable(
          isModelInitializing = isInitializing,
          ttsReadiness = uiState.ttsReadiness,
        ),
      onStopPersonaPreview = viewModel::stopPersonaPreview,
      languagePacks = languagePacks.uiState,
      onDownloadLanguagePack = languagePacks.onDownloadLanguagePack,
      onChangeModels = {
        showPreferencesDialog = false
        activeLanguageNotice?.let(viewModel::acknowledgeLanguageNotice)
        pendingLanguageNotice = null
        onChooseAnotherModel(ModelSelectionTarget.ASR_MODEL)
      },
      unsupportedNativeLanguages =
        remember(speechEngineLocales, availableNativeLanguages) {
          unsupportedNativeLanguages(speechEngineLocales, availableNativeLanguages)
        },
      nativeLanguageNotice = nativeLanguageNotice,
      learnLanguageNotice = learnLanguageNotice,
    )
  }

  val speechEngineError = uiState.speechEngineError
  if (speechEngineError != null && canShowSpeechEngineError && !showPreferencesDialog) {
    val openSpeechServicesStorePage = rememberOpenSpeechServicesStorePage()
    SpeechEngineErrorDialog(
      error = speechEngineError,
      onDismiss = viewModel::dismissSpeechEngineError,
      // The popup stays up, so the user can tap Retry after updating.
      onUpdate = openSpeechServicesStorePage,
      onRetry = { viewModel.retrySpeechEngine(speechEngineError.kind) },
      onChooseLanguage = {
        viewModel.dismissSpeechEngineError()
        showPreferencesDialog = true
      },
      onChooseAnotherModel = {
        viewModel.dismissSpeechEngineError()
        onChooseAnotherModel(speechEngineError.kind.selectionTarget)
      },
    )
  }
}
