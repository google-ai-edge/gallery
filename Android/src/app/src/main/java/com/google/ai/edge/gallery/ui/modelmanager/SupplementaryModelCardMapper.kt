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

package com.google.ai.edge.gallery.ui.modelmanager

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.TtsVoice
import com.google.ai.edge.gallery.data.supplementary.LanguageState
import com.google.ai.edge.gallery.data.supplementary.SpeechDefaultChoice
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.UpdatableComponent
import com.google.ai.edge.gallery.proto.SupplementaryModelFilterProto
import com.google.ai.edge.gallery.services.asr.AsrDefaults
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsVoicePreviewPhrases
import com.google.ai.edge.gallery.services.tts.isPseudoLocale
import java.util.Locale

/** `bestForTaskTypes` key that marks the best TTS model in the allowlist. */
internal const val BEST_FOR_TTS_KEY = "tts"

/** `bestForTaskTypes` key that marks the best ASR model in the allowlist. */
internal const val BEST_FOR_ASR_KEY = "asr"

/**
 * Builds the card for [model], or returns `null` when [state] hides it.
 *
 * @param state The model's state from the repository; `null` before its first check.
 * @param languageStates The model's language pack states by BCP-47 tag; a missing tag is still
 *   being checked.
 * @param languageCodes The languages the model supports, in display order.
 * @param installsLanguagesSeparately Whether each language is a separate pack. When `false`, all
 *   languages come with the model.
 * @param defaultChoice How the model relates to the default engine of its modality, from
 *   [SpeechSelectionUseCase.defaultChoiceFor], or `null` when it can't be a default.
 * @param voices The model's voice profiles (TTS only).
 */
internal fun buildSupplementaryCard(
  model: Model,
  state: SpeechModelState?,
  languageStates: Map<String, LanguageState>,
  languageCodes: List<String>,
  installsLanguagesSeparately: Boolean,
  defaultChoice: SpeechDefaultChoice?,
  voices: List<TtsVoice>,
): SupplementaryModelCardUiState? {
  if (state is SpeechModelState.Hidden) {
    return null
  }
  val card =
    SupplementaryModelCardUiState(
      model = model,
      isBestOverall = isBestOverallForModality(model),
      usesTopLevelDownload = model.supportsTopLevelDownload,
    )
  val readyContent = {
    readyCard(
      card,
      model,
      languageStates,
      languageCodes,
      installsLanguagesSeparately,
      defaultChoice,
      voices,
    )
  }
  val readOnlyLanguages = {
    languageRows(
      model = model,
      languageStates = languageStates,
      languageCodes = languageCodes,
      installsLanguagesSeparately = installsLanguagesSeparately,
      isModelReady = false,
      canSelect = false,
      selectedLanguageCode = "",
    )
  }
  if (card.usesTopLevelDownload) {
    // The card's regular download button and progress cover everything but these states.
    return when (state) {
      is SpeechModelState.Ready -> readyContent()
      is SpeechModelState.CheckFailed ->
        card.copy(notice = checkFailedNotice(), languages = readOnlyLanguages())
      is SpeechModelState.EngineError ->
        card.copy(notice = engineErrorNotice(state.recoverable), languages = readOnlyLanguages())
      else -> card.copy(languages = readOnlyLanguages())
    }
  }
  return when (state) {
    null,
    SpeechModelState.Checking -> card.copy(statusLine = SupplementaryStatusLine.Checking)
    is SpeechModelState.NeedsUpdate ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.supplementary_status_update_required),
        notice =
          SupplementaryCardNotice(
            messageRes =
              when (state.component) {
                UpdatableComponent.SPEECH_SERVICES ->
                  R.string.model_unavailable_speech_services_outdated
                UpdatableComponent.AICORE -> R.string.supplementary_notice_aicore_outdated
              },
            action = SupplementaryCardAction.OpenUrl(R.string.update, state.storeUrl),
          ),
      )
    is SpeechModelState.NeedsOptIn ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.supplementary_status_access_required),
        notice =
          SupplementaryCardNotice(
            messageRes = R.string.aicore_access_panel_title,
            action =
              SupplementaryCardAction.OpenUrl(R.string.aicore_access_panel_button, state.guideUrl),
          ),
      )
    is SpeechModelState.Unavailable ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.model_unavailable_status),
        notice =
          SupplementaryCardNotice(
            messageRes = R.string.supplementary_notice_unavailable_right_now,
            action = SupplementaryCardAction.Retry,
          ),
      )
    is SpeechModelState.CheckFailed ->
      card.copy(
        statusLine =
          SupplementaryStatusLine.Message(
            R.string.supplementary_status_check_failed,
            isError = true,
          ),
        notice = checkFailedNotice(),
      )
    is SpeechModelState.Downloadable ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.model_status_not_downloaded),
        notice =
          SupplementaryCardNotice(
            messageRes = R.string.supplementary_notice_downloadable,
            action = SupplementaryCardAction.Download,
          ),
        languages = readOnlyLanguages(),
      )
    is SpeechModelState.NeedsLanguagePack ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.supplementary_status_needs_language),
        languages = readOnlyLanguages(),
      )
    is SpeechModelState.Downloading ->
      card.copy(
        statusLine =
          SupplementaryStatusLine.Downloading(
            receivedBytes = state.progress.bytesDownloaded,
            totalBytes = state.progress.totalBytes,
            continuesInBackground = state.continuesInBackground && !state.cancellable,
          ),
        languages = readOnlyLanguages(),
      )
    is SpeechModelState.Queued ->
      card.copy(
        statusLine = SupplementaryStatusLine.Message(R.string.supplementary_status_queued),
        languages = readOnlyLanguages(),
      )
    is SpeechModelState.Ready -> readyContent()
    is SpeechModelState.EngineError ->
      card.copy(
        statusLine =
          SupplementaryStatusLine.Message(
            R.string.supplementary_status_engine_error,
            isError = true,
          ),
        notice = engineErrorNotice(state.recoverable),
        languages = readOnlyLanguages(),
      )
    is SpeechModelState.Hidden -> null
  }
}

/**
 * Builds the card of a ready model: the default checkbox, voices, and languages. The checkbox only
 * shows while [defaultChoice] says the model is selectable, which for a model that installs
 * languages separately requires at least one installed language.
 */
private fun readyCard(
  card: SupplementaryModelCardUiState,
  model: Model,
  languageStates: Map<String, LanguageState>,
  languageCodes: List<String>,
  installsLanguagesSeparately: Boolean,
  defaultChoice: SpeechDefaultChoice?,
  voices: List<TtsVoice>,
): SupplementaryModelCardUiState {
  val visibleLanguageCodes = languageCodes.filterNot { code ->
    code.isPseudoLocale() ||
      (installsLanguagesSeparately && languageStates[code] is LanguageState.NotOffered)
  }
  val installedCodes =
    if (installsLanguagesSeparately) {
      visibleLanguageCodes.filter { languageStates[it] is LanguageState.Installed }
    } else {
      visibleLanguageCodes
    }
  val usage = defaultChoice?.modality?.toDefaultUsage()
  val canBeDefault = defaultChoice?.isSelectable == true
  val isDefault = canBeDefault && defaultChoice.isDefault
  val statusLine =
    when {
      !installsLanguagesSeparately -> SupplementaryStatusLine.Message(R.string.model_status_ready)
      installedCodes.isNotEmpty() ->
        SupplementaryStatusLine.LanguagesInstalled(installedCodes.size, visibleLanguageCodes.size)
      visibleLanguageCodes.any {
        languageStates[it] == null || languageStates[it] is LanguageState.Checking
      } -> SupplementaryStatusLine.Checking
      else -> SupplementaryStatusLine.Message(R.string.supplementary_status_needs_language)
    }
  val selectedLanguageCode = defaultChoice?.selectedLanguageCode.orEmpty()
  val voicesUiState =
    if (model.modelType == ModelType.TTS && canBeDefault && voices.isNotEmpty()) {
      val engineId = SpeechSelectionUseCase.ttsEngineIdOf(model)
      val distinctVoiceLocales =
        voices.mapNotNull { it.locale.takeIf(String::isNotBlank) }.distinct()
      val appliesToAllLanguages =
        engineId == TtsModelIdentifier.ML_KIT ||
          (distinctVoiceLocales.size <= 1 && visibleLanguageCodes.size > 1)
      SupplementaryVoicesUiState(
        engineId = engineId,
        voices = voices,
        selectedVoiceId =
          defaultChoice.selectedVoiceId.takeIf { isDefault && it.isNotEmpty() }
            ?: model.ttsProfile?.defaultVoiceId?.ifEmpty { null }
            ?: voices.first().id,
        canSelect = isDefault,
        speakableLanguageCodes = installedCodes,
        appliesToAllLanguages = appliesToAllLanguages,
      )
    } else {
      null
    }
  return card.copy(
    statusLine = if (card.usesTopLevelDownload) null else statusLine,
    defaultToggle =
      usage?.takeIf { canBeDefault }?.let { SupplementaryDefaultToggle(it, isDefault) },
    voices = voicesUiState,
    languages =
      languageRows(
        model = model,
        languageStates = languageStates,
        languageCodes = visibleLanguageCodes,
        installsLanguagesSeparately = installsLanguagesSeparately,
        isModelReady = true,
        canSelect = isDefault && (model.modelType == ModelType.TTS || installsLanguagesSeparately),
        selectedLanguageCode = selectedLanguageCode,
      ),
  )
}

/**
 * Builds the language list, or `null` when the model has no languages to list on this device.
 * Languages that are [LanguageState.NotOffered] on this device or Android testing pseudo-locales
 * (`-XA`, `-XB`, `-XC`) are omitted.
 */
private fun languageRows(
  model: Model,
  languageStates: Map<String, LanguageState>,
  languageCodes: List<String>,
  installsLanguagesSeparately: Boolean,
  isModelReady: Boolean,
  canSelect: Boolean,
  selectedLanguageCode: String,
): SupplementaryLanguagesUiState? {
  val visibleCodes = languageCodes.filterNot { code ->
    code.isPseudoLocale() ||
      (installsLanguagesSeparately && languageStates[code] is LanguageState.NotOffered)
  }
  if (visibleCodes.isEmpty()) {
    return null
  }
  val effectiveLanguageCode = selectedLanguageCode.ifEmpty {
    if (model.modelType == ModelType.ASR) AsrDefaults.LANGUAGE_CODE else DEFAULT_TTS_LANGUAGE_CODE
  }
  val rows = visibleCodes.map { code ->
    val status =
      when {
        installsLanguagesSeparately -> languageStates[code].toLanguageStatus()
        isModelReady -> SupplementaryLanguageStatus.Installed
        else -> SupplementaryLanguageStatus.Bundled
      }
    val isSelectable = canSelect && status == SupplementaryLanguageStatus.Installed
    SupplementaryLanguageRowUiState(
      languageCode = code,
      status = status,
      isSelectable = isSelectable,
      isSelected = isSelectable && code == effectiveLanguageCode,
    )
  }
  return SupplementaryLanguagesUiState(rows = rows, showInfoTooltip = model.isMlKitSpeechModel())
}

/** Whether [this] model is backed by an ML Kit speech runtime (`mlkit_speech` or `mlkit_asr`). */
internal fun Model.isMlKitSpeechModel(): Boolean =
  backendSpec.runtimeType == RuntimeType.MLKIT_SPEECH ||
    backendSpec.runtimeType == RuntimeType.MLKIT_ASR ||
    ttsProfile?.engineModelId == TtsModelIdentifier.ML_KIT ||
    modelId == AsrModelIdentifier.MLKIT_BASIC ||
    modelId == AsrModelIdentifier.MLKIT_ADVANCED

/** Maps a language pack's state, or `null` before its first check, to its row status. */
internal fun LanguageState?.toLanguageStatus(): SupplementaryLanguageStatus =
  when (this) {
    null,
    is LanguageState.Checking -> SupplementaryLanguageStatus.Checking
    is LanguageState.Installed -> SupplementaryLanguageStatus.Installed
    is LanguageState.Downloadable -> SupplementaryLanguageStatus.Downloadable
    is LanguageState.Downloading ->
      SupplementaryLanguageStatus.Downloading(
        receivedBytes = progress.bytesDownloaded,
        totalBytes = progress.totalBytes,
        stalled = stalled,
      )
    is LanguageState.Queued -> SupplementaryLanguageStatus.Queued
    is LanguageState.Failed -> SupplementaryLanguageStatus.Failed
    is LanguageState.CheckFailed -> SupplementaryLanguageStatus.CheckFailed
    is LanguageState.NotOffered -> SupplementaryLanguageStatus.NotOffered
  }

private fun checkFailedNotice() =
  SupplementaryCardNotice(
    messageRes = R.string.supplementary_notice_check_failed,
    action = SupplementaryCardAction.Retry,
  )

private fun engineErrorNotice(recoverable: Boolean) =
  SupplementaryCardNotice(
    messageRes = R.string.supplementary_notice_engine_error,
    action = if (recoverable) SupplementaryCardAction.Retry else null,
  )

/**
 * Whether [model] is marked best for its modality in the allowlist (`bestForTaskTypes` of `"tts"`
 * or `"asr"`), since supplementary cards aren't shown for a task.
 */
internal fun isBestOverallForModality(model: Model): Boolean =
  when (model.modelType) {
    ModelType.TTS -> BEST_FOR_TTS_KEY in model.bestForTaskIds
    ModelType.ASR -> BEST_FOR_ASR_KEY in model.bestForTaskIds
    else -> false
  }

/** Whether [model] passes this filter. */
internal fun SupplementaryModelFilter.matches(model: Model): Boolean =
  when (this) {
    SupplementaryModelFilter.ALL -> true
    SupplementaryModelFilter.TTS -> model.modelType == ModelType.TTS
    SupplementaryModelFilter.ASR -> model.modelType == ModelType.ASR
  }

/** The checkbox label that makes a model of this modality the default. */
private fun SpeechModality.toDefaultUsage(): SupplementaryDefaultUsage =
  when (this) {
    SpeechModality.TTS -> SupplementaryDefaultUsage.READ_ALOUD
    SpeechModality.ASR -> SupplementaryDefaultUsage.SPEECH_INPUT
  }

/** Maps the persisted filter to the filter chip it selects. */
internal fun SupplementaryModelFilterProto.toFilter(): SupplementaryModelFilter =
  when (this) {
    SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_TTS -> SupplementaryModelFilter.TTS
    SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_ASR -> SupplementaryModelFilter.ASR
    SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_ALL,
    SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_UNSPECIFIED,
    SupplementaryModelFilterProto.UNRECOGNIZED -> SupplementaryModelFilter.ALL
  }

/** Maps a filter chip to its persisted value. */
internal fun SupplementaryModelFilter.toProto(): SupplementaryModelFilterProto =
  when (this) {
    SupplementaryModelFilter.ALL -> SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_ALL
    SupplementaryModelFilter.TTS -> SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_TTS
    SupplementaryModelFilter.ASR -> SupplementaryModelFilterProto.SUPPLEMENTARY_MODEL_FILTER_ASR
  }

/**
 * Returns the language to speak the voice preview sentence in, or `null` to fall back to the
 * English sentence spoken in [DEFAULT_TTS_LANGUAGE_CODE].
 *
 * The preview follows the app language ([uiLocale]) only when the sentence is actually translated
 * into it ([isPreviewTranslated]; English needs no translation) and the engine can speak that
 * language right away ([speakableLanguageCodes], BCP-47 tags). An exact tag match is preferred over
 * a match on the language alone, so for example `en-GB` picks an `en-GB` voice over `en-US`.
 */
internal fun voicePreviewLanguageCode(
  uiLocale: Locale,
  isPreviewTranslated: Boolean,
  speakableLanguageCodes: List<String>,
): String? {
  if (!isPreviewTranslated && uiLocale.language != Locale.ENGLISH.language) return null
  val uiTag = uiLocale.toLanguageTag()
  return speakableLanguageCodes.firstOrNull { it.equals(uiTag, ignoreCase = true) }
    ?: speakableLanguageCodes.firstOrNull {
      Locale.forLanguageTag(it).language == uiLocale.language
    }
}

/**
 * Resolves `R.string.tts_voice_preview_text` for [languageCode] using a locale-configured
 * [Context], falling back to the English preview sentence when [languageCode] has no translation.
 */
@SuppressLint("AppBundleLocaleChanges")
internal fun resolveLocalizedVoicePreviewText(
  context: Context,
  languageCode: String,
  configuration: Configuration = context.resources.configuration,
): String {
  val targetLocale = Locale.forLanguageTag(languageCode)
  val targetConfiguration = Configuration(configuration).apply { setLocale(targetLocale) }
  val localizedPreviewSentence =
    context
      .createConfigurationContext(targetConfiguration)
      .getString(R.string.tts_voice_preview_text)
  if (targetLocale.language == Locale.ENGLISH.language) {
    return localizedPreviewSentence
  }
  val englishConfiguration = Configuration(configuration).apply { setLocale(Locale.ENGLISH) }
  val englishPreviewSentence =
    context
      .createConfigurationContext(englishConfiguration)
      .getString(R.string.tts_voice_preview_text)
  return if (localizedPreviewSentence != englishPreviewSentence) {
    localizedPreviewSentence
  } else {
    TtsVoicePreviewPhrases.previewText(languageCode, englishPreviewSentence)
  }
}
