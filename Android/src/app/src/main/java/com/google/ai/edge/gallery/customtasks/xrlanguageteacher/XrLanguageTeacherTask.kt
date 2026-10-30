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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher

import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.ALL_SUPPORTED_LANGUAGES
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SelectedSpeechEngineLocales
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageRole
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XR_LANGUAGE_TEACHER_MODEL_SELECTION_CONFIG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XR_LANGUAGE_TEACHER_ONBOARDING_FEATURE_ID
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XR_LANGUAGE_TEACHER_SETTINGS_TOOLTIP_FEATURE_ID
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDictionary
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherPreferencesStore
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherScreenName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.cleanupXrLanguageTeacherTopK
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.deleteDictionaryEntry
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.isLanguageSupportedByEngineLocales
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.loadSavedVocabImageBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.orDefaultLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.orDefaultMainLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.saveVocabCardFromHistory
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.setupXrLanguageTeacherTopK
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toEnglishName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.unsaveVocabCardForLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.unsupportedNativeLanguages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playPersonaPreview
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playTargetPhrase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopPersonaPreview
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.applyTeacherPreferences
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.buildDefaultSystemPrompt
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.buildDisplayLessonSessions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.deleteAllLessonSessions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.deleteLessonSession
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.getPersonaPromptTemplate
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.loadSessionImageBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.logChatHistoryAction
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.saveSystemPromptOverride
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.SessionHistorySideSheet
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.XrLanguageTeacherOnboardingOverlay
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.XrLanguageTeacherScreen
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.isActiveLessonSheetVisible
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.isPersonaPreviewAvailable
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.isTeacherSubsystemWarming
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.rememberTeacherLanguagePacks
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.rememberTurnGate
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.studyview.StudyScreen
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.vocabview.DictionaryScreen
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelSelectionConfig
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.Dictionary
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingViewModel
import com.google.ai.edge.gallery.ui.modelmanager.LanguagePackUiStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class XrLanguageTeacherTask
constructor(
  private val context: Context,
  @XrLanguageTeacherDictionary private val dictionaryDataStore: DataStore<Dictionary>? = null,
  private val storageRepository: XrLanguageTeacherPreferencesStore? = null,
  private val initDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CustomTask {
  @Inject
  constructor(
    context: Context,
    @XrLanguageTeacherDictionary dictionaryDataStore: DataStore<Dictionary>,
    storageRepository: XrLanguageTeacherPreferencesStore,
  ) : this(context, dictionaryDataStore, storageRepository, Dispatchers.Default)

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER,
      label = context.getString(R.string.xrteacher_xr_task_title),
      category = Category.LLM,
      icon = Icons.Outlined.School,
      description = context.getString(R.string.xrteacher_xr_task_description),
      shortDescription = context.getString(R.string.xrteacher_task_short_description),
      docUrl =
        "https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/xrlanguageteacher",
      models = mutableListOf(),
      modelNames =
        listOf(
          "Gemma 4 E2B (Gemini Nano via AICore)",
          "Gemma 4 E4B (Gemini Nano via AICore)",
          "Gemma-4-E2B-it TPU",
          "Gemma-4-E2B-it",
          "Gemma-4-E4B-it",
        ),
      defaultSystemPrompt = CharacterPersona.BUDDY.defaultPromptTemplate,
    )

  override val modelSelectionConfig: ModelSelectionConfig =
    XR_LANGUAGE_TEACHER_MODEL_SELECTION_CONFIG

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(initDispatcher) {
      val dictionary = dictionaryDataStore?.data?.firstOrNull() ?: Dictionary.getDefaultInstance()
      val persona = CharacterPersona.fromId(dictionary.selectedPersona)
      val mainLang = dictionary.mainLanguage.orDefaultMainLanguage()
      val learnLang = dictionary.learnLanguage.orDefaultLearnLanguage(mainLang)
      val repo = storageRepository ?: XrLanguageTeacherPreferencesStore(context)
      val incomingInstructionText = systemInstruction?.toString()?.trim()
      val customPromptOverride = incomingInstructionText?.takeIf {
        it.isNotEmpty() && it != task.defaultSystemPrompt
      }
      val overridePrompt = repo.getSystemPromptOverride()
      val customTemplate = repo.getCustomPersonaPromptTemplate(persona)
      val systemPromptText =
        customPromptOverride
          ?: overridePrompt
          ?: persona.buildSystemPrompt(
            mainLangName = mainLang.toEnglishName(fallback = "English"),
            learnLangName = learnLang.toEnglishName(fallback = "Spanish"),
            customTemplate = customTemplate,
          )
      val resolvedSystemInstruction = Contents.of(systemPromptText)

      model.runtimeHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = true,
        supportAudio = false,
        onDone = onDone,
        systemInstruction = resolvedSystemInstruction,
        tools = emptyList(),
        enableConversationConstrainedDecoding = false,
        // Lesson turns stream a plain `<reply>`; the vocabulary card is extracted by a separate
        // constrained pass on its own ephemeral conversation.
        enableResponseFormat = false,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    coroutineScope.launch(initDispatcher) {
      model.runtimeHelper.cleanUp(model = model, onDone = onDone)
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    val modelManagerViewModel: ModelManagerViewModel = myData.modelManagerViewModel

    val viewModel: XrLanguageTeacherViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entries by viewModel.dictionaryEntries.collectAsStateWithLifecycle()
    val mainLanguage by viewModel.mainLanguage.collectAsStateWithLifecycle()
    val learnLanguage by viewModel.learnLanguage.collectAsStateWithLifecycle()
    val availableLanguages by viewModel.availableLanguages.collectAsStateWithLifecycle()
    val availableNativeLanguages by viewModel.availableNativeLanguages.collectAsStateWithLifecycle()
    val speechEngineLocales by viewModel.selectedSpeechEngineLocales.collectAsStateWithLifecycle()
    val selectedPersona by viewModel.selectedPersona.collectAsStateWithLifecycle()
    val historySessions by viewModel.historySessions.collectAsStateWithLifecycle()

    var isReviewing by rememberSaveable { mutableStateOf(false) }
    var showHistorySheet by rememberSaveable { mutableStateOf(false) }

    // uiState updates at a high rate (e.g. audio level), so only build the list while it is shown.
    val displaySessions =
      remember(showHistorySheet, uiState, historySessions) {
        if (showHistorySheet) {
          viewModel.buildDisplayLessonSessions(
            uiState = uiState,
            persistedSessions = historySessions,
          )
        } else {
          emptyList()
        }
      }

    LaunchedEffect(isReviewing) { myData.setTopBarVisible(!isReviewing) }

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
    val selectedModel = modelManagerUiState.selectedModel
    val initStatus by selectedModel.initStatusFlow.collectAsStateWithLifecycle()
    val isSubsystemWarming =
      isTeacherSubsystemWarming(
        isModelInitializing = initStatus is Model.InitializationStatus.Initializing,
        asrReadiness = uiState.asrReadiness,
        ttsReadiness = uiState.ttsReadiness,
      )
    val navButtonsEnabled =
      rememberTurnGate(uiState, isSubsystemWarming, myData.setAppBarControlsDisabled)

    // While the teacher model, speech recognition and voice start, only the Camera tab (which shows
    // the progress) and the back button stay usable.
    var isTeacherInitializing by remember { mutableStateOf(false) }
    LaunchedEffect(isTeacherInitializing) { myData.setAppBarActionsDisabled(isTeacherInitializing) }
    DisposableEffect(myData) { onDispose { myData.setAppBarActionsDisabled(false) } }
    val otherTabsEnabled = navButtonsEnabled && !isTeacherInitializing

    DisposableEffect(myData, viewModel, modelManagerViewModel) {
      myData.setHistoryClickHandler {
        viewModel.logChatHistoryAction(
          action = "open_history",
          model = modelManagerViewModel.uiState.value.selectedModel,
        )
        showHistorySheet = true
      }
      onDispose { myData.setHistoryClickHandler(null) }
    }

    // The config dialog's "System prompt" tab shows the exact system instruction sent to the
    // model. Its default is rebuilt from the current persona and languages.
    val systemPromptOverride by viewModel.systemPromptOverride.collectAsStateWithLifecycle()
    val personaPromptTemplateVersion by
      viewModel.personaPromptTemplateVersion.collectAsStateWithLifecycle()
    DisposableEffect(selectedModel.name, task.id) {
      if (selectedModel.setupXrLanguageTeacherTopK()) {
        modelManagerViewModel.updateConfigValuesUpdateTrigger()
      }
      onDispose {
        if (selectedModel.cleanupXrLanguageTeacherTopK()) {
          modelManagerViewModel.updateConfigValuesUpdateTrigger()
        }
      }
    }
    val defaultSystemPrompt =
      remember(selectedPersona, mainLanguage, learnLanguage, personaPromptTemplateVersion) {
        viewModel.buildDefaultSystemPrompt(selectedPersona, mainLanguage, learnLanguage)
      }
    val effectiveSystemPrompt = systemPromptOverride ?: defaultSystemPrompt
    LaunchedEffect(effectiveSystemPrompt) {
      myData.setSystemPromptProvider(
        { effectiveSystemPrompt },
        { newPrompt -> viewModel.saveSystemPromptOverride(newPrompt) },
      )
    }
    DisposableEffect(myData, defaultSystemPrompt) {
      myData.setDefaultSystemPromptProvider { defaultSystemPrompt }
      onDispose { myData.setDefaultSystemPromptProvider(null) }
    }

    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingVisitedFlow =
      remember(onboardingViewModel) {
        onboardingViewModel.isVisitedFlow(XR_LANGUAGE_TEACHER_ONBOARDING_FEATURE_ID)
      }
    val isOnboardingVisited by
      onboardingVisitedFlow.collectAsStateWithLifecycle(initialValue = null)
    var completedOnboarding by rememberSaveable { mutableStateOf(false) }
    // The settings button tooltip shows once, on the camera screen after the introduction.
    val settingsTooltipVisitedFlow =
      remember(onboardingViewModel) {
        onboardingViewModel.isVisitedFlow(XR_LANGUAGE_TEACHER_SETTINGS_TOOLTIP_FEATURE_ID)
      }
    val isSettingsTooltipVisited by
      settingsTooltipVisitedFlow.collectAsStateWithLifecycle(initialValue = null)
    var dismissedSettingsTooltip by rememberSaveable { mutableStateOf(false) }
    val screenBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    // The navigation bar hides as soon as the lesson sheet is requested, so the layout doesn't
    // resize while the sheet animates in, and only returns once the sheet has fully slid away.
    var isLessonSheetShown by remember { mutableStateOf(false) }
    val isLessonSheetCoveringNav =
      uiState.currentScreen == XrLanguageTeacherScreenName.CAMERA.value &&
        (isActiveLessonSheetVisible(uiState) || isLessonSheetShown)

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      bottomBar = {
        // The lesson sheet extends to the bottom edge, so the navigation bar is hidden while it is
        // open.
        if (!isReviewing && !isLessonSheetCoveringNav) {
          NavigationBar {
            NavigationBarItem(
              icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
              label = { Text(stringResource(R.string.xrteacher_tab_camera)) },
              selected = uiState.currentScreen == XrLanguageTeacherScreenName.CAMERA.value,
              onClick = {
                viewModel.setScreen(XrLanguageTeacherScreenName.CAMERA.value)
              },
              enabled = navButtonsEnabled,
            )
            NavigationBarItem(
              icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
              label = { Text(stringResource(R.string.xrteacher_tab_vocabulary)) },
              selected = uiState.currentScreen == XrLanguageTeacherScreenName.VOCABULARY.value,
              onClick = {
                viewModel.setScreen(XrLanguageTeacherScreenName.VOCABULARY.value)
              },
              enabled = otherTabsEnabled,
            )
            NavigationBarItem(
              icon = { Icon(Icons.Outlined.School, contentDescription = null) },
              label = { Text(stringResource(R.string.xrteacher_tab_study)) },
              selected = uiState.currentScreen == XrLanguageTeacherScreenName.STUDY.value,
              onClick = {
                viewModel.setScreen(XrLanguageTeacherScreenName.STUDY.value)
              },
              enabled = otherTabsEnabled,
            )
          }
        }
      },
    ) { innerPadding ->
      // The lesson sheet pads itself above the system navigation bar.
      val contentPadding =
        PaddingValues(
          bottom = if (isLessonSheetCoveringNav) 0.dp else innerPadding.calculateBottomPadding()
        )
      Box(
        // Consuming the bottom padding lets imePadding() in the screens lift their inputs only by
        // the part of the keyboard that overlaps them, not by the full keyboard height.
        modifier =
          Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
      ) {
        when (uiState.currentScreen) {
          XrLanguageTeacherScreenName.CAMERA.value -> {
            XrLanguageTeacherScreen(
              viewModel = viewModel,
              modelManagerViewModel = modelManagerViewModel,
              canGreet = isOnboardingVisited == true,
              canShowSpeechEngineError = isOnboardingVisited == true,
              showSettingsTooltip =
                (isOnboardingVisited == true || completedOnboarding) &&
                  isSettingsTooltipVisited == false &&
                  !dismissedSettingsTooltip,
              onSettingsTooltipDismissed = {
                dismissedSettingsTooltip = true
                onboardingViewModel.markVisited(XR_LANGUAGE_TEACHER_SETTINGS_TOOLTIP_FEATURE_ID)
              },
              onLessonSheetShownChanged = { isLessonSheetShown = it },
              onInitializingChanged = { isTeacherInitializing = it },
              onChooseAnotherModel = myData.navigateToModelSelection,
            )
          }
          XrLanguageTeacherScreenName.VOCABULARY.value -> {
            val languagePacks =
              rememberTeacherLanguagePacks(
                modelManagerViewModel = modelManagerViewModel,
                speechEngineLocales = speechEngineLocales,
                nativeLanguages = availableNativeLanguages,
                learnLanguages = ALL_SUPPORTED_LANGUAGES,
              )
            DictionaryScreen(
              entries = entries,
              onDelete = { entry -> viewModel.deleteDictionaryEntry(entry) },
              onPlayPhrase = { phrase, lang -> viewModel.playTargetPhrase(phrase, lang) },
              loadVocabImageBitmap = viewModel::loadSavedVocabImageBitmap,
              isTtsLanguageSupported =
                rememberIsTtsLanguageSupported(speechEngineLocales, languagePacks.uiState),
              phrasePlaybackState = uiState.phrasePlaybackState,
              onChangeVoiceModel = {
                myData.navigateToModelSelection(ModelSelectionTarget.TTS_MODEL)
              },
            )
          }
          XrLanguageTeacherScreenName.STUDY.value -> {
            val languagePacks =
              rememberTeacherLanguagePacks(
                modelManagerViewModel = modelManagerViewModel,
                speechEngineLocales = speechEngineLocales,
                nativeLanguages = availableNativeLanguages,
                learnLanguages = ALL_SUPPORTED_LANGUAGES,
              )
            StudyScreen(
              entries = entries,
              onReviewModeChanged = { isReviewing = it },
              onPlayPhrase = { phrase, lang -> viewModel.playTargetPhrase(phrase, lang) },
              phrasePlaybackState = uiState.phrasePlaybackState,
              loadVocabImageBitmap = viewModel::loadSavedVocabImageBitmap,
              isTtsLanguageSupported =
                rememberIsTtsLanguageSupported(speechEngineLocales, languagePacks.uiState),
              isAsrLanguageSupported =
                rememberIsAsrLanguageSupported(speechEngineLocales, languagePacks.uiState),
              onOpenAudioSettings = {
                myData.navigateToModelSelection(ModelSelectionTarget.TTS_MODEL)
              },
              onOpenAsrSettings = {
                myData.navigateToModelSelection(ModelSelectionTarget.ASR_MODEL)
              },
            )
          }
        }
      }
    }

    if (isOnboardingVisited == false && !completedOnboarding) {
      val languagePacks =
        rememberTeacherLanguagePacks(
          modelManagerViewModel = modelManagerViewModel,
          speechEngineLocales = speechEngineLocales,
          nativeLanguages = availableNativeLanguages,
          learnLanguages = availableLanguages,
        )
      XrLanguageTeacherOnboardingOverlay(
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
        onGetStarted = { preferences ->
          completedOnboarding = true
          viewModel.applyTeacherPreferences(preferences)
          onboardingViewModel.markVisited(XR_LANGUAGE_TEACHER_ONBOARDING_FEATURE_ID)
        },
        // The dialog window has its own back dispatcher, so forward to the screen's dispatcher to
        // leave the feature without completing the introduction.
        onExit = { screenBackDispatcher?.onBackPressed() },
        personaPreviewState = uiState.personaPreviewState,
        isPersonaPreviewAvailable =
          isPersonaPreviewAvailable(
            isModelInitializing = initStatus is Model.InitializationStatus.Initializing,
            ttsReadiness = uiState.ttsReadiness,
          ),
        onStopPersonaPreview = viewModel::stopPersonaPreview,
        languagePacks = languagePacks.uiState,
        onDownloadLanguagePack = languagePacks.onDownloadLanguagePack,
        onChangeModels = { myData.navigateToModelSelection(ModelSelectionTarget.ASR_MODEL) },
        unsupportedNativeLanguages =
          remember(speechEngineLocales, availableNativeLanguages) {
            unsupportedNativeLanguages(speechEngineLocales, availableNativeLanguages)
          },
      )
    }

    if (showHistorySheet) {
      SessionHistorySideSheet(
        sessions = displaySessions,
        persona = selectedPersona,
        savedWords = entries,
        loadSessionImageBitmap = viewModel::loadSessionImageBitmap,
        onExpandSession = {
          viewModel.logChatHistoryAction(
            action = "expand_session",
            model = modelManagerViewModel.uiState.value.selectedModel,
          )
        },
        onDeleteSession = { sessionId ->
          val currentModel = modelManagerViewModel.uiState.value.selectedModel
          viewModel.deleteLessonSession(sessionId = sessionId, model = currentModel)
        },
        onDeleteAllSessions = {
          viewModel.deleteAllLessonSessions(
            model = modelManagerViewModel.uiState.value.selectedModel
          )
        },
        onSaveVocabCard = { session, card -> viewModel.saveVocabCardFromHistory(session, card) },
        onUnsaveVocabCard = { card, language ->
          viewModel.unsaveVocabCardForLanguage(card, language)
        },
        onDismissRequest = { showHistorySheet = false },
      )
    }
  }
}

@Composable
private fun rememberIsTtsLanguageSupported(
  speechEngineLocales: SelectedSpeechEngineLocales,
  languagePacksUiState: TeacherLanguagePackUiState,
): (Language) -> Boolean =
  remember(speechEngineLocales.ttsLocaleTags, languagePacksUiState) {
    { language ->
      isLanguageSupportedByEngineLocales(language, speechEngineLocales.ttsLocaleTags) &&
        languagePacksUiState.statusOf(TeacherLanguageRole.LEARN, language) !=
          LanguagePackUiStatus.Unavailable
    }
  }

@Composable
private fun rememberIsAsrLanguageSupported(
  speechEngineLocales: SelectedSpeechEngineLocales,
  languagePacksUiState: TeacherLanguagePackUiState,
): (Language) -> Boolean =
  remember(speechEngineLocales.asrLocaleTags, languagePacksUiState) {
    { language ->
      isLanguageSupportedByEngineLocales(language, speechEngineLocales.asrLocaleTags) &&
        languagePacksUiState.statusOf(TeacherLanguageRole.NATIVE, language) !=
          LanguagePackUiStatus.Unavailable
    }
  }
