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

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SelectedSpeechEngineLocales
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageOptions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageResolution
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.isXrLanguageTeacherTtsModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toEnglishName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelFillerPresynthesis
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.getSupportedLocaleTagsForAsrModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.getSupportedLocaleTagsForTtsModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.isAsrRecordingOrPending
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareAsrEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareTtsEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.releaseTtsAudioAfterStop
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.resolveSelectedAsrService
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SpeechEngineKind
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun XrLanguageTeacherViewModel.createSelectedSpeechEngineLocalesFlow():
  StateFlow<SelectedSpeechEngineLocales> =
  combine(
      selectedAsrModelID,
      selectedTtsModelID,
      combine(
        speechSelectionUseCase.hasRegisteredModelsFlow(SpeechModality.ASR),
        speechSelectionUseCase.hasRegisteredModelsFlow(SpeechModality.TTS),
      ) { a, b ->
        a && b
      },
      _uiState.map { it.asrReadiness }.distinctUntilChanged(),
      _uiState.map { it.ttsReadiness }.distinctUntilChanged(),
    ) { asrModelId, ttsModelId, _, _, _ ->
      try {
        SelectedSpeechEngineLocales(
          asrModelId = asrModelId,
          asrLocaleTags = getSupportedLocaleTagsForAsrModel(asrModelId),
          ttsModelId = ttsModelId,
          ttsLocaleTags = getSupportedLocaleTagsForTtsModel(ttsModelId),
          reachableTtsLocaleTags = getReachableTtsLocaleTags(),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read the selected speech engines' locales", e)
        SelectedSpeechEngineLocales(asrModelId = asrModelId, ttsModelId = ttsModelId)
      }
    }
    .distinctUntilChanged()
    .flowOn(ioDispatcher)
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue =
        SelectedSpeechEngineLocales(
          asrModelId = selectedAsrModelID.value,
          asrLocaleTags = getSupportedLocaleTagsForAsrModel(selectedAsrModelID.value),
          ttsModelId = selectedTtsModelID.value,
          ttsLocaleTags = getSupportedLocaleTagsForTtsModel(selectedTtsModelID.value),
          reachableTtsLocaleTags = getReachableTtsLocaleTags(),
        ),
    )

/**
 * The languages any text-to-speech model the teacher's model selection offers can speak, whether or
 * not it's downloaded, or `null` when one of them doesn't restrict its languages.
 */
private fun XrLanguageTeacherViewModel.getReachableTtsLocaleTags(): List<String>? {
  val tags = mutableListOf<String>()
  for (model in speechSelectionUseCase.registeredModels(SpeechModality.TTS)) {
    if (!isXrLanguageTeacherTtsModel(model)) continue
    val languages = speechSelectionUseCase.supportedLanguageCodes(model)
    if (languages.isEmpty()) return null
    tags += languages
  }
  return tags
}

internal fun XrLanguageTeacherViewModel.createLanguageOptionsFlow(
  localesFlow: StateFlow<SelectedSpeechEngineLocales>
): StateFlow<TeacherLanguageOptions> =
  localesFlow
    .map {
      TeacherLanguageOptions.fromLocaleTags(
        asrLocaleTags = it.asrLocaleTags,
        ttsLocaleTags = it.ttsLocaleTags,
      )
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue =
        TeacherLanguageOptions.fromLocaleTags(
          asrLocaleTags = getSupportedLocaleTagsForAsrModel(selectedAsrModelID.value),
          ttsLocaleTags = getSupportedLocaleTagsForTtsModel(selectedTtsModelID.value),
        ),
    )

internal fun XrLanguageTeacherViewModel.observeLanguageAndSpeechInputs() {
  // ASR and TTS are prepared separately so that switching one engine doesn't also reload the
  // other; both can be large on-device models.
  viewModelScope.launch {
    combine(selectedAsrModelID, mainLanguage) { asrId, mainLang -> asrId to mainLang }
      .distinctUntilChanged()
      .collect {
        markAsrWarming()
        prepareAsrEngine()
      }
  }
  viewModelScope.launch {
    combine(selectedTtsModelID, mainLanguage, learnLanguage, selectedPersona, ::TtsInputs)
      .distinctUntilChanged()
      .collect {
        markTtsWarming()
        prepareTtsEngine()
      }
  }
  viewModelScope.launch {
    var previous: TeacherLanguageResolution? = null
    teacherLanguages.collect { current ->
      val prev = previous
      previous = current
      if (prev != null && prev.saved == current.saved && prev.used != current.used) {
        stopTtsPlayback()
        cancelAsrRecording()
        isConversationInitialized = false
        clearSystemPromptOverride()
        resetLessonSessionInternal()
      }
    }
  }
}

internal fun XrLanguageTeacherViewModel.performOnCleared() {
  // The model instance is shared across tasks, so only stop it while this ViewModel's turn runs.
  val wasInferring = isTurnInferring()
  val unused =
    cancelTurnJobs(reason = "cleared", modelToStop = activeInferenceModel.takeIf { wasInferring })
  asrSessionEpoch.incrementAndGet()
  isStoppingAsr = true
  prepareSpeechJob?.cancel()
  prepareSpeechJob = null
  prepareTtsJob?.cancel()
  prepareTtsJob = null
  val wasRecordingAsr = isAsrRecordingOrPending
  asrRecordingJob?.cancel()
  asrRecordingJob = null
  asrAmplitudeJob?.cancel()
  asrAmplitudeJob = null
  latestKnownModel = null
  activeInferenceModel = null
  cancelFillerPresynthesis()
  releaseStickerResources()
  stopTtsPlayback()
  releaseTtsAudioAfterStop()
  // XrGlassesManager is an app-wide singleton; only release this session's resources.
  xrGlassesManager.endSession()
  if (isMonitoringGlassesConnection) {
    xrGlassesManager.stopMonitoringConnection()
  }
  val service = resolveSelectedAsrService()
  _uiState.update { it.copy(capturedBitmap = null, isRecordingAsr = false, audioLevel = 0f) }
  if (service == null) return
  // viewModelScope is already cancelled by the time onCleared() runs, so use the injected
  // applicationScope for non-cancellable ASR teardown.
  val cleanupScope = if (viewModelScope.isActive) viewModelScope else applicationScope
  cleanupScope.launch(ioDispatcher) {
    withContext(NonCancellable) {
      try {
        if (
          wasRecordingAsr ||
            service.status.value is AsrServiceStatus.Recognizing ||
            service.status.value is AsrServiceStatus.Processing
        ) {
          service.cancelRecording()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Error cancelling ASR recording on clear", e)
      }
    }
  }
}

/** Sets the main language. Choosing a different language starts a new lesson session. */
fun XrLanguageTeacherViewModel.setMainLanguage(language: Language) {
  applyMainLanguageInternal(language = language, prewarm = true)
}

internal fun XrLanguageTeacherViewModel.applyMainLanguageInternal(
  language: Language,
  prewarm: Boolean,
) {
  if (language != mainLanguage.value) {
    markAsrWarming()
    markTtsWarming()
    resetLessonSessionInternal(prewarmSession = false)
    hudController.resetGreetingLatch()
    clearSystemPromptOverride()
  }
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  saveMainLanguage(language)
  if (prewarm) {
    prewarmConversationSession(mainLang = language)
  }
}

internal fun XrLanguageTeacherViewModel.saveMainLanguage(language: Language) {
  viewModelScope.launch {
    dictionaryDataStore.updateData { currentDictionary ->
      currentDictionary.toBuilder().setMainLanguage(language).build()
    }
  }
}

/** Sets the learn language. Choosing a different language starts a new lesson session. */
fun XrLanguageTeacherViewModel.setLearnLanguage(language: Language) {
  applyLearnLanguageInternal(language = language, prewarm = true)
}

internal fun XrLanguageTeacherViewModel.applyLearnLanguageInternal(
  language: Language,
  prewarm: Boolean,
) {
  if (language != learnLanguage.value) {
    markTtsWarming()
    resetLessonSessionInternal(prewarmSession = false)
    hudController.resetGreetingLatch()
    clearSystemPromptOverride()
  }
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  saveLearnLanguage(language)
  if (prewarm) {
    prewarmConversationSession(learnLang = language)
  }
}

internal fun XrLanguageTeacherViewModel.saveLearnLanguage(language: Language) {
  viewModelScope.launch {
    dictionaryDataStore.updateData { currentDictionary ->
      currentDictionary.toBuilder().setLearnLanguage(language).build()
    }
  }
}

/** Sets the teacher persona. Choosing a different persona starts a new lesson session. */
fun XrLanguageTeacherViewModel.setSelectedPersona(persona: CharacterPersona) {
  applySelectedPersonaInternal(persona = persona, prewarm = true)
}

internal fun XrLanguageTeacherViewModel.applySelectedPersonaInternal(
  persona: CharacterPersona,
  prewarm: Boolean,
) {
  if (persona != selectedPersona.value) {
    markTtsWarming()
    resetLessonSessionInternal(prewarmSession = false)
    hudController.resetGreetingLatch()
    clearSystemPromptOverride()
  }
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  stopTtsPlayback()
  viewModelScope.launch {
    dictionaryDataStore.updateData { currentDictionary ->
      currentDictionary.toBuilder().setSelectedPersona(persona.id).build()
    }
  }
  if (prewarm) {
    prewarmConversationSession(persona = persona)
  }
}

/**
 * Applies the languages, persona and persona prompt edits chosen in the preferences dialogs. Only
 * values that differ from the current ones are written, so applying unchanged preferences keeps the
 * current lesson. Languages the teacher uses instead of unsupported saved ones
 * ([XrLanguageTeacherViewModel.languageAdjustment]) are saved too, which confirms them. A prompt
 * edit that is blank or equals the persona's default template removes the customization.
 *
 * A failed speech engine is tried again, because the user may have downloaded a missing language in
 * the dialog.
 */
fun XrLanguageTeacherViewModel.applyTeacherPreferences(
  preferences: TeacherPreferencesDraft,
  model: Model? = null,
) {
  if (model != null) {
    latestKnownModel = model
  }
  // Engines whose inputs change are prepared again by the collectors in init; the others are
  // retried below if they failed.
  val asrInputsUnchanged = preferences.mainLanguage == mainLanguage.value
  val ttsInputsUnchanged =
    asrInputsUnchanged &&
      preferences.learnLanguage == learnLanguage.value &&
      preferences.persona == selectedPersona.value
  val stateBefore = _uiState.value
  if (asrInputsUnchanged && stateBefore.asrReadiness == SpeechEngineReadiness.ERROR) {
    retrySpeechEngine(SpeechEngineKind.SPEECH_RECOGNITION)
  }
  if (ttsInputsUnchanged && stateBefore.ttsReadiness == SpeechEngineReadiness.ERROR) {
    retrySpeechEngine(SpeechEngineKind.VOICE)
  }
  var sessionInvalidated = false
  var sessionReset = false
  when {
    preferences.mainLanguage != mainLanguage.value -> {
      applyMainLanguageInternal(preferences.mainLanguage, prewarm = false)
      sessionInvalidated = true
      sessionReset = true
    }
    preferences.mainLanguage != savedMainLanguage.value -> {
      // Confirms the language the teacher already uses instead of an unsupported saved one.
      saveMainLanguage(preferences.mainLanguage)
    }
  }
  when {
    preferences.learnLanguage != learnLanguage.value -> {
      applyLearnLanguageInternal(preferences.learnLanguage, prewarm = false)
      sessionInvalidated = true
      sessionReset = true
    }
    preferences.learnLanguage != savedLearnLanguage.value -> {
      saveLearnLanguage(preferences.learnLanguage)
    }
  }
  if (preferences.persona != selectedPersona.value) {
    applySelectedPersonaInternal(preferences.persona, prewarm = false)
    sessionInvalidated = true
    sessionReset = true
  }
  for ((persona, template) in preferences.promptTemplateEdits) {
    val isDefault = template.isBlank() || template.trim() == persona.defaultPromptTemplate.trim()
    when {
      isDefault -> {
        if (storageRepository.hasCustomPersonaPromptTemplate(persona)) {
          resetPersonaPromptTemplate(persona)
          sessionInvalidated = true
        }
      }
      template != getPersonaPromptTemplate(persona) -> {
        savePersonaPromptTemplate(persona, template)
        sessionInvalidated = true
      }
    }
  }
  if (sessionInvalidated) {
    if (!sessionReset) {
      resetLessonSessionInternal(model = model, prewarmSession = false)
    }
    hudController.requestGreeting()
    greetNewSessionIfNeeded()
    prewarmConversationSession(
      model = model ?: latestKnownModel,
      mainLang = preferences.mainLanguage,
      learnLang = preferences.learnLanguage,
      persona = preferences.persona,
    )
  }
}

fun XrLanguageTeacherViewModel.getPersonaPromptTemplate(persona: CharacterPersona): String {
  return storageRepository.getPersonaPromptTemplate(persona)
}

fun XrLanguageTeacherViewModel.savePersonaPromptTemplate(
  persona: CharacterPersona,
  template: String,
) {
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  storageRepository.savePersonaPromptTemplate(persona, template)
  _personaPromptTemplateVersion.update { it + 1 }
  clearSystemPromptOverride()
}

fun XrLanguageTeacherViewModel.resetPersonaPromptTemplate(persona: CharacterPersona) {
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  storageRepository.resetPersonaPromptTemplate(persona)
  _personaPromptTemplateVersion.update { it + 1 }
  clearSystemPromptOverride()
}

/**
 * Builds the default system instruction sent to the model for [persona] and the given languages:
 * the persona prompt template (or its customized version) with the languages substituted, followed
 * by the Pass 1 reply-only output schema.
 */
fun XrLanguageTeacherViewModel.buildDefaultSystemPrompt(
  persona: CharacterPersona,
  mainLanguage: Language,
  learnLanguage: Language,
): String {
  return persona.buildSystemPrompt(
    mainLangName = mainLanguage.toEnglishName(fallback = "English"),
    learnLangName = learnLanguage.toEnglishName(fallback = "Spanish"),
    customTemplate = storageRepository.getCustomPersonaPromptTemplate(persona),
  )
}

/**
 * Replaces the whole system instruction with [prompt], as edited in the model config dialog.
 *
 * A blank prompt, or one that equals the current default, removes the override so the default
 * system prompt is used again.
 */
fun XrLanguageTeacherViewModel.saveSystemPromptOverride(prompt: String) {
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  val isDefaultPrompt =
    prompt.trim() ==
      buildDefaultSystemPrompt(selectedPersona.value, mainLanguage.value, learnLanguage.value)
        .trim()
  if (prompt.isBlank() || isDefaultPrompt) {
    clearSystemPromptOverride()
    return
  }
  storageRepository.saveSystemPromptOverride(prompt)
  _systemPromptOverride.value = prompt
}

internal fun XrLanguageTeacherViewModel.clearSystemPromptOverride() {
  if (_systemPromptOverride.value == null) return
  hasPendingPreferencePrewarm = true
  isConversationInitialized = false
  storageRepository.clearSystemPromptOverride()
  _systemPromptOverride.value = null
}

fun XrLanguageTeacherViewModel.setTeacherDialogue(dialogue: String) {
  _uiState.update { it.copy(teacherDialogue = dialogue) }
}

fun XrLanguageTeacherViewModel.setOverlayState(state: TeacherOverlayState) {
  _uiState.update { it.copy(overlayState = state) }
}

fun XrLanguageTeacherViewModel.clearErrorMessage() {
  _uiState.update { it.copy(errorMessage = null) }
}
