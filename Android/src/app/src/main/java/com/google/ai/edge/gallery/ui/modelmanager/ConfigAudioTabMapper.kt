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

import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supplementary.DownloadProgress
import com.google.ai.edge.gallery.data.supplementary.LanguageState
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import com.google.ai.edge.gallery.services.asr.AsrDefaults
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrServiceStatus
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsUiState

/**
 * Builds the [ConfigAudioTabUiState] for [task] from the current ASR and TTS settings, model
 * availability states, and language pack states.
 */
internal fun buildConfigAudioTabUiState(
  task: Task,
  enableAsr: Boolean = true,
  enableTts: Boolean = true,
  hideSystemSpeechDefaults: Boolean = false,
  supplementaryModels: List<Model> = emptyList(),
  modelStates: Map<String, SpeechModelState> = emptyMap(),
  languageStates: Map<String, Map<String, LanguageState>> = emptyMap(),
  modelDownloadStatus: Map<String, ModelDownloadStatus> = emptyMap(),
  languagePackStatus: Map<String, Map<String, ModelDownloadStatus>> = emptyMap(),
  selectedAsrEngineType: AsrEngineType = AsrEngineType.SYSTEM_DEFAULT,
  selectedAsrModelId: String = AsrModelRegistry.modelId(selectedAsrEngineType),
  selectedAsrLanguageCode: String = AsrDefaults.LANGUAGE_CODE,
  selectedAsrServiceStatus: AsrServiceStatus = AsrServiceStatus.Ready,
  supportedAsrEngines: Set<AsrEngineType> = AsrEngineType.entries.toSet(),
  downloadedAsrModels: List<Model> = emptyList(),
  asrDownloadStatus: ModelDownloadStatus? = null,
  showInterimResults: Boolean = AsrDefaults.SHOW_INTERIM_RESULTS,
  ttsSettingsUiState: TtsSettingsUiState? = null,
  ttsDownloadStatus: ModelDownloadStatus? = null,
  voicePreviewUiState: VoicePreviewUiState = VoicePreviewUiState(),
): ConfigAudioTabUiState {
  val asrSection =
    if (task.supportsSpeechInput && enableAsr) {
      buildConfigAsrSection(
        task = task,
        hideSystemSpeechDefaults = hideSystemSpeechDefaults,
        supplementaryModels = supplementaryModels,
        modelStates = modelStates,
        languageStates = languageStates,
        modelDownloadStatus = modelDownloadStatus,
        languagePackStatus = languagePackStatus,
        selectedAsrEngineType = selectedAsrEngineType,
        selectedAsrModelId = selectedAsrModelId,
        selectedAsrLanguageCode = selectedAsrLanguageCode,
        selectedAsrServiceStatus = selectedAsrServiceStatus,
        supportedAsrEngines = supportedAsrEngines,
        downloadedAsrModels = downloadedAsrModels,
        asrDownloadStatus = asrDownloadStatus,
        showInterimResults = showInterimResults,
      )
    } else {
      null
    }

  val ttsSection =
    if (task.supportsReadAloud && enableTts && ttsSettingsUiState != null) {
      buildConfigTtsSection(
        task = task,
        hideSystemSpeechDefaults = hideSystemSpeechDefaults,
        supplementaryModels = supplementaryModels,
        modelStates = modelStates,
        languageStates = languageStates,
        modelDownloadStatus = modelDownloadStatus,
        languagePackStatus = languagePackStatus,
        ttsSettingsUiState = ttsSettingsUiState,
        ttsDownloadStatus = ttsDownloadStatus,
        voicePreviewUiState = voicePreviewUiState,
      )
    } else {
      null
    }

  return ConfigAudioTabUiState(asr = asrSection, tts = ttsSection)
}

private fun buildConfigAsrSection(
  task: Task,
  hideSystemSpeechDefaults: Boolean,
  supplementaryModels: List<Model>,
  modelStates: Map<String, SpeechModelState>,
  languageStates: Map<String, Map<String, LanguageState>>,
  modelDownloadStatus: Map<String, ModelDownloadStatus>,
  languagePackStatus: Map<String, Map<String, ModelDownloadStatus>>,
  selectedAsrEngineType: AsrEngineType,
  selectedAsrModelId: String,
  selectedAsrLanguageCode: String,
  selectedAsrServiceStatus: AsrServiceStatus,
  supportedAsrEngines: Set<AsrEngineType>,
  downloadedAsrModels: List<Model>,
  asrDownloadStatus: ModelDownloadStatus?,
  showInterimResults: Boolean,
): ConfigAsrSectionUiState {
  val allAsrModels =
    (supplementaryModels.filter { it.modelType == ModelType.ASR } + downloadedAsrModels)
      .distinctBy { it.name }

  val selectedAsrModel = allAsrModels.firstOrNull { model ->
    isAsrModelSelected(model, selectedAsrEngineType, selectedAsrModelId)
  }

  val isSystemDefaultAsr =
    selectedAsrModel == null &&
      AsrModelRegistry.isSystemEngine(selectedAsrEngineType) &&
      (AsrModelIdentifier.isSystemEngine(selectedAsrModelId) || selectedAsrModelId.isEmpty())

  val selectedModelLabel =
    selectedAsrModel?.let { ConfigAudioText.Literal(it.displayName.ifEmpty { it.name }) }
      ?: if (hideSystemSpeechDefaults && isSystemDefaultAsr) {
        ConfigAudioText.Resource(R.string.config_dialog_speech_model_none_selected)
      } else {
        ConfigAudioText.Resource(selectedAsrEngineType.labelRes())
      }

  val modelItems =
    buildAsrModelDropdownItems(
      hideSystemSpeechDefaults = hideSystemSpeechDefaults,
      allAsrModels = allAsrModels,
      modelStates = modelStates,
      languageStates = languageStates,
      modelDownloadStatus = modelDownloadStatus,
      languagePackStatus = languagePackStatus,
      selectedAsrEngineType = selectedAsrEngineType,
      selectedAsrModelId = selectedAsrModelId,
      isSystemDefaultAsr = isSystemDefaultAsr,
      supportedAsrEngines = supportedAsrEngines,
    )

  val selectedModelState = selectedAsrModel?.let { model ->
    resolveEffectiveModelState(
      model = model,
      modelStates = modelStates,
      modelDownloadStatus = modelDownloadStatus,
      fallbackDownloadStatus = asrDownloadStatus,
    )
  }
  val selectedModelInstallsLanguagesSeparately =
    selectedAsrModel?.let { installsLanguagesSeparately(it) }
      ?: AsrModelRegistry.metadataFor(selectedAsrEngineType).installsLanguagePacks
  val selectedModelHasInstalledLanguage =
    when {
      !selectedModelInstallsLanguagesSeparately -> true
      selectedAsrModel != null ->
        hasAnyInstalledLanguage(
          model = selectedAsrModel,
          state = selectedModelState,
          languageStates = languageStates[selectedAsrModel.name].orEmpty(),
          languagePackStatus = languagePackStatus[selectedAsrModel.name].orEmpty(),
        )
      else -> selectedAsrEngineType in supportedAsrEngines
    }

  val statusRow =
    buildAsrStatusRow(
      isSystemDefaultAsr = isSystemDefaultAsr,
      hideSystemSpeechDefaults = hideSystemSpeechDefaults,
      selectedModelLabel = selectedModelLabel,
      selectedAsrEngineType = selectedAsrEngineType,
      selectedAsrServiceStatus = selectedAsrServiceStatus,
      selectedModelState = selectedModelState,
      selectedModelInstallsLanguagesSeparately = selectedModelInstallsLanguagesSeparately,
      selectedModelHasInstalledLanguage = selectedModelHasInstalledLanguage,
      supportedAsrEngines = supportedAsrEngines,
      asrDownloadStatus = asrDownloadStatus,
    )

  val languageDropdown =
    if (task.showsAudioLanguageSelectors) {
      buildAsrLanguageDropdown(
        selectedAsrEngineType = selectedAsrEngineType,
        selectedAsrModelId = selectedAsrModelId,
        selectedAsrModel = selectedAsrModel,
        isSystemDefaultAsr = isSystemDefaultAsr,
        selectedAsrLanguageCode = selectedAsrLanguageCode,
        languageStates = selectedAsrModel?.let { languageStates[it.name] }.orEmpty(),
        languagePackStatus = selectedAsrModel?.let { languagePackStatus[it.name] }.orEmpty(),
      )
    } else {
      null
    }

  return ConfigAsrSectionUiState(
    selectedModelLabel = selectedModelLabel,
    modelItems = modelItems,
    statusRow = statusRow,
    languageDropdown = languageDropdown,
    showLiveTranscriptionToggle = task.supportsLiveTranscriptionToggle,
    liveTranscriptionEnabled = showInterimResults,
  )
}

private fun buildAsrModelDropdownItems(
  hideSystemSpeechDefaults: Boolean,
  allAsrModels: List<Model>,
  modelStates: Map<String, SpeechModelState>,
  languageStates: Map<String, Map<String, LanguageState>>,
  modelDownloadStatus: Map<String, ModelDownloadStatus>,
  languagePackStatus: Map<String, Map<String, ModelDownloadStatus>>,
  selectedAsrEngineType: AsrEngineType,
  selectedAsrModelId: String,
  isSystemDefaultAsr: Boolean,
  supportedAsrEngines: Set<AsrEngineType>,
): List<ConfigAudioModelDropdownItemUiState> = buildList {
  if (!hideSystemSpeechDefaults) {
    add(
      ConfigAudioModelDropdownItemUiState(
        key = AsrModelIdentifier.SYSTEM_DEFAULT,
        label = ConfigAudioText.Resource(R.string.asr_engine_system_default),
        target = ConfigAudioModelSelectionTarget.SystemAsr,
        isSelected = isSystemDefaultAsr,
        status = ConfigAudioModelItemStatus.Ready,
      )
    )
  }

  // Repository-backed ASR engines come from the allowlist in production; in unit tests or offline
  // setups without allowlist entries, synthesize fallback dropdown items from their static
  // metadata. When allowlist ASR models are present, any omitted engine was filtered out by the
  // allowlist (for example by deviceRequirement) and must stay hidden.
  if (allAsrModels.isEmpty()) {
    for (metadata in AsrEngineType.entries.map { AsrModelRegistry.metadataFor(it) }) {
      if (AsrModelIdentifier.isSystemEngine(metadata.modelId)) continue
      if (!metadata.requiresRepositoryModel) continue

      val isSupported = metadata.engineType in supportedAsrEngines
      if (hideSystemSpeechDefaults && !isSupported) {
        continue
      }
      val engineModelId = metadata.modelId
      val isSelected =
        selectedAsrEngineType == metadata.engineType &&
          (selectedAsrModelId == engineModelId || selectedAsrModelId.isEmpty())
      add(
        ConfigAudioModelDropdownItemUiState(
          key = engineModelId,
          label = ConfigAudioText.Resource(metadata.engineType.labelRes()),
          target = ConfigAudioModelSelectionTarget.AsrModel(engineName = metadata.engineType.name),
          isSelected = isSelected,
          status =
            if (isSupported) {
              ConfigAudioModelItemStatus.Ready
            } else {
              ConfigAudioModelItemStatus.Unavailable(R.string.model_unavailable_status)
            },
        )
      )
    }
  }

  for (model in allAsrModels) {
    val state =
      resolveEffectiveModelState(
        model = model,
        modelStates = modelStates,
        modelDownloadStatus = modelDownloadStatus,
        fallbackDownloadStatus = null,
        supportedAsrEngines = supportedAsrEngines,
      )
    if (state is SpeechModelState.Hidden) {
      continue
    }
    val itemStatus =
      toModelItemStatus(
        model = model,
        state = state,
        languageStates = languageStates[model.name].orEmpty(),
        languagePackStatus = languagePackStatus[model.name].orEmpty(),
      )
    val builtInEngine = AsrModelRegistry.engineTypeForModelId(model.modelId)
    val target =
      ConfigAudioModelSelectionTarget.AsrModel(
        engineName = (builtInEngine ?: AsrEngineType.SPEECH_PROCESSOR).name,
        model = model,
      )
    add(
      ConfigAudioModelDropdownItemUiState(
        key = model.name,
        label = ConfigAudioText.Literal(model.displayName.ifEmpty { model.name }),
        target = target,
        isSelected = isAsrModelSelected(model, selectedAsrEngineType, selectedAsrModelId),
        status = itemStatus,
      )
    )
  }
}

private fun buildAsrStatusRow(
  isSystemDefaultAsr: Boolean,
  hideSystemSpeechDefaults: Boolean,
  selectedModelLabel: ConfigAudioText,
  selectedAsrEngineType: AsrEngineType,
  selectedAsrServiceStatus: AsrServiceStatus,
  selectedModelState: SpeechModelState?,
  selectedModelInstallsLanguagesSeparately: Boolean,
  selectedModelHasInstalledLanguage: Boolean,
  supportedAsrEngines: Set<AsrEngineType>,
  asrDownloadStatus: ModelDownloadStatus?,
): ConfigAudioStatusRowUiState? {
  if (isSystemDefaultAsr) {
    if (hideSystemSpeechDefaults) {
      return null
    }
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.READY,
      text = ConfigAudioText.Resource(R.string.model_status_ready_builtin),
    )
  }

  // While the selected ASR engine is preparing, show Initializing… and never the unavailable
  // fallback.
  if (selectedAsrServiceStatus is AsrServiceStatus.Initializing) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.PROGRESS,
      text = ConfigAudioText.Resource(R.string.model_status_initializing),
    )
  }

  if (selectedAsrServiceStatus is AsrServiceStatus.Downloading) {
    return downloadingStatusRow(
      receivedBytes = selectedAsrServiceStatus.bytesDownloaded,
      totalBytes = selectedAsrServiceStatus.totalBytes,
      continuesInBackground = false,
    )
  }

  if (selectedModelState != null) {
    when (selectedModelState) {
      is SpeechModelState.Downloading ->
        return downloadingStatusRow(
          receivedBytes = selectedModelState.progress.bytesDownloaded,
          totalBytes = selectedModelState.progress.totalBytes,
          continuesInBackground =
            selectedModelState.continuesInBackground && !selectedModelState.cancellable,
        )
      is SpeechModelState.Queued ->
        return ConfigAudioStatusRowUiState(
          tone = ConfigAudioStatusTone.PROGRESS,
          text = ConfigAudioText.Resource(R.string.supplementary_status_queued),
        )
      SpeechModelState.Checking ->
        return ConfigAudioStatusRowUiState(
          tone = ConfigAudioStatusTone.PROGRESS,
          text = ConfigAudioText.Resource(R.string.supplementary_status_checking),
        )
      is SpeechModelState.NeedsLanguagePack ->
        return ConfigAudioStatusRowUiState(
          tone = ConfigAudioStatusTone.ERROR,
          text = ConfigAudioText.Resource(R.string.supplementary_status_needs_language),
        )
      is SpeechModelState.NeedsUpdate,
      is SpeechModelState.NeedsOptIn,
      is SpeechModelState.Unavailable,
      is SpeechModelState.CheckFailed,
      is SpeechModelState.EngineError,
      is SpeechModelState.Hidden ->
        return ConfigAudioStatusRowUiState(
          tone = ConfigAudioStatusTone.ERROR,
          text =
            ConfigAudioText.Formatted(
              R.string.model_status_unavailable_using,
              selectedModelLabel,
              ConfigAudioText.Resource(R.string.asr_engine_system_default),
            ),
        )
      is SpeechModelState.Downloadable ->
        return if (asrDownloadStatus?.status == ModelDownloadStatusType.FAILED) {
          ConfigAudioStatusRowUiState(
            tone = ConfigAudioStatusTone.ERROR,
            text = ConfigAudioText.Resource(R.string.tts_language_pack_download_failed),
          )
        } else {
          ConfigAudioStatusRowUiState(
            tone = ConfigAudioStatusTone.NEUTRAL,
            text = ConfigAudioText.Resource(R.string.model_status_not_downloaded),
          )
        }
      is SpeechModelState.Ready -> {
        if (selectedModelInstallsLanguagesSeparately && !selectedModelHasInstalledLanguage) {
          return ConfigAudioStatusRowUiState(
            tone = ConfigAudioStatusTone.ERROR,
            text = ConfigAudioText.Resource(R.string.supplementary_status_needs_language),
          )
        }
      }
    }
  }

  if (
    selectedAsrServiceStatus is AsrServiceStatus.Failed ||
      selectedAsrServiceStatus is AsrServiceStatus.Unavailable ||
      (selectedModelState == null && selectedAsrEngineType !in supportedAsrEngines)
  ) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.ERROR,
      text =
        ConfigAudioText.Formatted(
          R.string.model_status_unavailable_using,
          selectedModelLabel,
          ConfigAudioText.Resource(R.string.asr_engine_system_default),
        ),
    )
  }

  if (asrDownloadStatus?.status == ModelDownloadStatusType.FAILED) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.ERROR,
      text = ConfigAudioText.Resource(R.string.tts_language_pack_download_failed),
    )
  }

  return ConfigAudioStatusRowUiState(
    tone = ConfigAudioStatusTone.READY,
    text = ConfigAudioText.Resource(R.string.model_status_ready),
  )
}

private fun buildAsrLanguageDropdown(
  selectedAsrEngineType: AsrEngineType,
  selectedAsrModelId: String,
  selectedAsrModel: Model?,
  isSystemDefaultAsr: Boolean,
  selectedAsrLanguageCode: String,
  languageStates: Map<String, LanguageState>,
  languagePackStatus: Map<String, ModelDownloadStatus>,
): ConfigAudioLanguageDropdownUiState {
  val builtInEngine =
    selectedAsrModel?.let { AsrModelRegistry.engineTypeForModelId(it.modelId) }
      ?: selectedAsrEngineType
  val metadata = AsrModelRegistry.metadataFor(builtInEngine)

  val isAutoDetectModel = !isSystemDefaultAsr && metadata.isLanguageAutoDetected

  val languageCodes: List<String> =
    when {
      isSystemDefaultAsr || AsrModelIdentifier.isSystemEngine(metadata.modelId) ->
        (listOf(AsrDefaults.LANGUAGE_CODE) +
            AsrModelRegistry.MLKIT_ADVANCED_SUPPORTED_LOCALES +
            listOfNotNull(selectedAsrLanguageCode.takeIf { it.isNotEmpty() }))
          .distinct()
      metadata.hasFixedLanguageList ->
        selectedAsrModel?.let { AsrModelRegistry.supportedLanguages(it) }?.ifEmpty { null }
          ?: metadata.supportedLanguages
      selectedAsrModel != null ->
        AsrModelRegistry.supportedLanguages(selectedAsrModel).ifEmpty {
          listOf(AsrDefaults.LANGUAGE_CODE)
        }
      else ->
        AsrModelRegistry.supportedLanguagesForModelName(selectedAsrModelId).ifEmpty {
          listOf(AsrDefaults.LANGUAGE_CODE)
        }
    }.filterNot(::isPseudoLocale)

  val supportsPerLanguageDownload = metadata.installsLanguagePacks
  val isSelectionEnabled = !isAutoDetectModel
  val descriptionRes =
    when {
      isSystemDefaultAsr || AsrModelIdentifier.isSystemEngine(metadata.modelId) -> null
      isAutoDetectModel -> R.string.asr_config_language_note_auto_detect
      else -> R.string.asr_config_language_note_single
    }

  val effectiveLanguageCode =
    selectedAsrLanguageCode.takeIf { it.isNotEmpty() && it in languageCodes }
      ?: languageCodes.firstOrNull()
      ?: AsrDefaults.LANGUAGE_CODE

  val items = languageCodes.mapNotNull { code ->
    val packStatus =
      if (supportsPerLanguageDownload) {
        resolveLanguagePackUiStatus(code, languageStates, languagePackStatus)
      } else {
        LanguagePackUiStatus.Installed
      }
    if (packStatus == LanguagePackUiStatus.Unavailable) {
      return@mapNotNull null
    }
    val itemSelectable = isSelectionEnabled && packStatus == LanguagePackUiStatus.Installed
    ConfigAudioLanguageItemUiState(
      languageCode = code,
      isSelected = code == effectiveLanguageCode,
      isSelectable = itemSelectable,
      packStatus = packStatus,
    )
  }

  val resolvedSelectedLanguageCode =
    effectiveLanguageCode.takeIf { selected -> items.any { it.languageCode == selected } }
      ?: items.firstOrNull()?.languageCode
      ?: effectiveLanguageCode

  return ConfigAudioLanguageDropdownUiState(
    selectedLanguageCode = resolvedSelectedLanguageCode,
    items = items.map { it.copy(isSelected = it.languageCode == resolvedSelectedLanguageCode) },
    isSelectionEnabled = isSelectionEnabled,
    supportsPerLanguageDownload = supportsPerLanguageDownload,
    model = selectedAsrModel,
    descriptionRes = descriptionRes,
    showInfoTooltip =
      supportsPerLanguageDownload ||
        builtInEngine == AsrEngineType.MLKIT_BASIC ||
        builtInEngine == AsrEngineType.MLKIT_ADVANCED ||
        selectedAsrModel?.backendSpec?.runtimeType == RuntimeType.MLKIT_ASR,
  )
}

private fun buildConfigTtsSection(
  task: Task,
  hideSystemSpeechDefaults: Boolean,
  supplementaryModels: List<Model>,
  modelStates: Map<String, SpeechModelState>,
  languageStates: Map<String, Map<String, LanguageState>>,
  modelDownloadStatus: Map<String, ModelDownloadStatus>,
  languagePackStatus: Map<String, Map<String, ModelDownloadStatus>>,
  ttsSettingsUiState: TtsSettingsUiState,
  ttsDownloadStatus: ModelDownloadStatus?,
  voicePreviewUiState: VoicePreviewUiState,
): ConfigTtsSectionUiState {
  val allTtsModels =
    (supplementaryModels.filter { it.modelType == ModelType.TTS } +
        ttsSettingsUiState.downloadedModels)
      .distinctBy { it.name }

  val selectedModelId = ttsSettingsUiState.selectedModelId
  val selectedTtsModel = allTtsModels.firstOrNull { model ->
    SpeechSelectionUseCase.ttsEngineIdOf(model) == selectedModelId || model.name == selectedModelId
  }

  val isEffectiveSystemEngine =
    TtsModelIdentifier.isSystemEngine(selectedModelId) || selectedTtsModel == null

  val selectedModelLabel =
    if (isEffectiveSystemEngine) {
      if (hideSystemSpeechDefaults) {
        ConfigAudioText.Resource(R.string.config_dialog_speech_model_none_selected)
      } else {
        ConfigAudioText.Resource(R.string.tts_android_system_builtin_name)
      }
    } else {
      ConfigAudioText.Literal(selectedTtsModel.displayName.ifEmpty { selectedTtsModel.name })
    }

  val modelItems = buildList {
    if (!hideSystemSpeechDefaults) {
      add(
        ConfigAudioModelDropdownItemUiState(
          key = TtsModelIdentifier.ANDROID_NATIVE,
          label = ConfigAudioText.Resource(R.string.tts_android_system_builtin_name),
          target = ConfigAudioModelSelectionTarget.SystemTts,
          isSelected = isEffectiveSystemEngine,
          status = ConfigAudioModelItemStatus.Ready,
        )
      )
    }
    for (model in allTtsModels) {
      val state =
        resolveEffectiveModelState(
          model = model,
          modelStates = modelStates,
          modelDownloadStatus = modelDownloadStatus,
          fallbackDownloadStatus =
            if (model.name == selectedTtsModel?.name) ttsDownloadStatus else null,
          downloadedModels = ttsSettingsUiState.downloadedModels,
        )
      if (state is SpeechModelState.Hidden) {
        continue
      }
      val engineId = SpeechSelectionUseCase.ttsEngineIdOf(model)
      val itemStatus =
        toModelItemStatus(
          model = model,
          state = state,
          languageStates = languageStates[model.name].orEmpty(),
          languagePackStatus = languagePackStatus[model.name].orEmpty(),
        )
      add(
        ConfigAudioModelDropdownItemUiState(
          key = model.name,
          label = ConfigAudioText.Literal(model.displayName.ifEmpty { model.name }),
          target = ConfigAudioModelSelectionTarget.TtsModel(engineId = engineId, model = model),
          isSelected = !isEffectiveSystemEngine && model.name == selectedTtsModel.name,
          status = itemStatus,
        )
      )
    }
  }

  val selectedModelState = selectedTtsModel?.let { model ->
    resolveEffectiveModelState(
      model = model,
      modelStates = modelStates,
      modelDownloadStatus = modelDownloadStatus,
      fallbackDownloadStatus = ttsDownloadStatus,
      downloadedModels = ttsSettingsUiState.downloadedModels,
    )
  }

  val statusRow =
    buildTtsStatusRow(
      isEffectiveSystemEngine = isEffectiveSystemEngine,
      hideSystemSpeechDefaults = hideSystemSpeechDefaults,
      isVoiceListLoading = ttsSettingsUiState.isVoiceListLoading,
      unavailableModelName = ttsSettingsUiState.unavailableModelName,
      selectedModelState = selectedModelState,
      ttsDownloadStatus = ttsDownloadStatus,
    )

  val supportedLanguages =
    resolveTtsSupportedLanguages(
      selectedTtsModel = selectedTtsModel,
      isEffectiveSystemEngine = isEffectiveSystemEngine,
      ttsSettingsUiState = ttsSettingsUiState,
    )
  val effectiveLanguageCode =
    ttsSettingsUiState.selectedLanguageCode.takeIf {
      it.isNotEmpty() && (it in supportedLanguages || isEffectiveSystemEngine)
    }
      ?: DEFAULT_TTS_LANGUAGE_CODE.takeIf { it in supportedLanguages }
      ?: supportedLanguages.firstOrNull().orEmpty()

  val languageDropdown =
    if (task.showsAudioLanguageSelectors) {
      val supportsPerLanguageDownload =
        selectedTtsModel?.let { installsLanguagesSeparately(it) } == true
      val modelLanguageStates = selectedTtsModel?.let { languageStates[it.name] }.orEmpty()
      val modelLanguagePackStatus = selectedTtsModel?.let { languagePackStatus[it.name] }.orEmpty()
      val items = supportedLanguages.mapNotNull { code ->
        val packStatus =
          if (supportsPerLanguageDownload) {
            resolveLanguagePackUiStatus(code, modelLanguageStates, modelLanguagePackStatus)
          } else {
            LanguagePackUiStatus.Installed
          }
        if (packStatus == LanguagePackUiStatus.Unavailable) {
          return@mapNotNull null
        }
        ConfigAudioLanguageItemUiState(
          languageCode = code,
          isSelected = code == effectiveLanguageCode,
          isSelectable = packStatus == LanguagePackUiStatus.Installed,
          packStatus = packStatus,
        )
      }
      val resolvedSelectedLanguageCode =
        effectiveLanguageCode.takeIf { selected -> items.any { it.languageCode == selected } }
          ?: items.firstOrNull()?.languageCode
          ?: effectiveLanguageCode
      ConfigAudioLanguageDropdownUiState(
        selectedLanguageCode = resolvedSelectedLanguageCode,
        items = items.map { it.copy(isSelected = it.languageCode == resolvedSelectedLanguageCode) },
        isSelectionEnabled = true,
        supportsPerLanguageDownload = supportsPerLanguageDownload,
        model = selectedTtsModel,
        descriptionRes =
          if (isEffectiveSystemEngine) null else R.string.tts_config_language_note_single,
      )
    } else {
      null
    }

  val voiceDropdown =
    buildTtsVoiceDropdown(
      selectedTtsModel = selectedTtsModel,
      isEffectiveSystemEngine = isEffectiveSystemEngine,
      effectiveLanguageCode = effectiveLanguageCode,
      ttsSettingsUiState = ttsSettingsUiState,
      voicePreviewUiState = voicePreviewUiState,
    )

  return ConfigTtsSectionUiState(
    selectedModelLabel = selectedModelLabel,
    modelItems = modelItems,
    statusRow = statusRow,
    languageDropdown = languageDropdown,
    voiceDropdown = voiceDropdown,
    showAutoPlayToggle = task.supportsAutoPlayTts,
    autoPlayEnabled = ttsSettingsUiState.autoPlayTts,
  )
}

private fun buildTtsStatusRow(
  isEffectiveSystemEngine: Boolean,
  hideSystemSpeechDefaults: Boolean,
  isVoiceListLoading: Boolean,
  unavailableModelName: String?,
  selectedModelState: SpeechModelState?,
  ttsDownloadStatus: ModelDownloadStatus?,
): ConfigAudioStatusRowUiState? {
  if (isVoiceListLoading) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.PROGRESS,
      text = ConfigAudioText.Resource(R.string.model_status_initializing),
    )
  }

  if (unavailableModelName != null) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.ERROR,
      text =
        ConfigAudioText.Formatted(
          R.string.model_status_unavailable_using,
          ConfigAudioText.Literal(unavailableModelName),
          ConfigAudioText.Resource(R.string.tts_android_system_builtin_name),
        ),
    )
  }

  if (isEffectiveSystemEngine) {
    if (hideSystemSpeechDefaults) {
      return null
    }
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.READY,
      text = ConfigAudioText.Resource(R.string.model_status_ready_builtin),
    )
  }

  when (selectedModelState) {
    is SpeechModelState.Downloading ->
      return downloadingStatusRow(
        receivedBytes = selectedModelState.progress.bytesDownloaded,
        totalBytes = selectedModelState.progress.totalBytes,
        continuesInBackground =
          selectedModelState.continuesInBackground && !selectedModelState.cancellable,
      )
    is SpeechModelState.Queued ->
      return ConfigAudioStatusRowUiState(
        tone = ConfigAudioStatusTone.PROGRESS,
        text = ConfigAudioText.Resource(R.string.supplementary_status_queued),
      )
    SpeechModelState.Checking ->
      return ConfigAudioStatusRowUiState(
        tone = ConfigAudioStatusTone.PROGRESS,
        text = ConfigAudioText.Resource(R.string.supplementary_status_checking),
      )
    is SpeechModelState.NeedsLanguagePack ->
      return ConfigAudioStatusRowUiState(
        tone = ConfigAudioStatusTone.ERROR,
        text = ConfigAudioText.Resource(R.string.supplementary_status_needs_language),
      )
    is SpeechModelState.Ready ->
      return ConfigAudioStatusRowUiState(
        tone = ConfigAudioStatusTone.READY,
        text = ConfigAudioText.Resource(R.string.model_status_ready),
      )
    else -> {}
  }

  if (ttsDownloadStatus?.status == ModelDownloadStatusType.FAILED) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.ERROR,
      text = ConfigAudioText.Resource(R.string.tts_language_pack_download_failed),
    )
  }

  if (ttsDownloadStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED) {
    return ConfigAudioStatusRowUiState(
      tone = ConfigAudioStatusTone.NEUTRAL,
      text = ConfigAudioText.Resource(R.string.model_status_not_downloaded),
    )
  }

  return ConfigAudioStatusRowUiState(
    tone = ConfigAudioStatusTone.READY,
    text = ConfigAudioText.Resource(R.string.model_status_ready),
  )
}

private fun resolveTtsSupportedLanguages(
  selectedTtsModel: Model?,
  isEffectiveSystemEngine: Boolean,
  ttsSettingsUiState: TtsSettingsUiState,
): List<String> {
  if (!isEffectiveSystemEngine && selectedTtsModel != null) {
    val fromMap =
      ttsSettingsUiState.languagesByModelName[selectedTtsModel.name]
        .orEmpty()
        .filterNot(::isPseudoLocale)
    if (fromMap.isNotEmpty()) {
      return fromMap
    }
    val fromProfile = selectedTtsModel.ttsProfile?.languages.orEmpty().filterNot(::isPseudoLocale)
    if (fromProfile.isNotEmpty()) {
      return fromProfile
    }
    return listOf(DEFAULT_TTS_LANGUAGE_CODE)
  }

  val voiceLocales =
    ttsSettingsUiState.availableVoices
      .mapNotNull { it.locale.ifEmpty { null } }
      .filterNot(::isPseudoLocale)
      .distinct()
  val baseLanguages =
    if (voiceLocales.isNotEmpty()) {
      (listOf(DEFAULT_TTS_LANGUAGE_CODE) + voiceLocales).distinct()
    } else {
      (listOf(DEFAULT_TTS_LANGUAGE_CODE) +
          AsrModelRegistry.MLKIT_ADVANCED_SUPPORTED_LOCALES.filterNot(::isPseudoLocale))
        .distinct()
    }
  val selectedCode = ttsSettingsUiState.selectedLanguageCode
  return if (
    selectedCode.isNotEmpty() && !isPseudoLocale(selectedCode) && selectedCode !in baseLanguages
  ) {
    (listOf(selectedCode) + baseLanguages).distinct()
  } else {
    baseLanguages
  }
}

private fun isPseudoLocale(languageCode: String): Boolean {
  val normalized = languageCode.trim().replace('_', '-').uppercase()
  return normalized.endsWith("-XA") || normalized.endsWith("-XB") || normalized.endsWith("-XC")
}

private fun buildTtsVoiceDropdown(
  selectedTtsModel: Model?,
  isEffectiveSystemEngine: Boolean,
  effectiveLanguageCode: String,
  ttsSettingsUiState: TtsSettingsUiState,
  voicePreviewUiState: VoicePreviewUiState,
): ConfigTtsVoiceDropdownUiState {
  val allVoices =
    selectedTtsModel?.let {
      ttsSettingsUiState.voicesByModelName[it.name]?.takeIf { list -> list.isNotEmpty() }
    } ?: ttsSettingsUiState.availableVoices

  val filteredVoices =
    TtsSettingsRepository.filterVoicesForLanguage(allVoices, effectiveLanguageCode).filter {
      it.id.isNotEmpty() && it.id != TtsSettingsRepository.DEFAULT_SYSTEM_VOICE
    }

  val voiceItems = filteredVoices.map { voice ->
    // TtsSettingsRepository already resolved the label; fall back to the ID only if it is blank.
    ConfigTtsVoiceItemUiState(id = voice.id, displayName = voice.displayName.ifBlank { voice.id })
  }

  val defaultItem =
    ConfigTtsVoiceItemUiState(
      id = TtsSettingsRepository.DEFAULT_SYSTEM_VOICE,
      isDefaultForLanguage = true,
      languageCode = effectiveLanguageCode,
    )

  val items =
    if (!isEffectiveSystemEngine && voiceItems.isNotEmpty()) {
      voiceItems
    } else {
      listOf(defaultItem) + voiceItems
    }

  val selectedVoiceId = ttsSettingsUiState.selectedVoiceId
  val selectedItem =
    when {
      selectedVoiceId.isNotEmpty() &&
        selectedVoiceId != TtsSettingsRepository.DEFAULT_SYSTEM_VOICE ->
        voiceItems.firstOrNull { it.id == selectedVoiceId }
          ?: voiceItems.firstOrNull()
          ?: defaultItem
      !isEffectiveSystemEngine && voiceItems.isNotEmpty() -> voiceItems.first()
      else -> defaultItem
    }

  val engineId =
    if (isEffectiveSystemEngine || selectedTtsModel == null) {
      TtsModelIdentifier.ANDROID_NATIVE
    } else {
      SpeechSelectionUseCase.ttsEngineIdOf(selectedTtsModel)
    }

  return ConfigTtsVoiceDropdownUiState(
    isLoading = ttsSettingsUiState.isVoiceListLoading,
    selectedItem = selectedItem,
    items = items,
    engineId = engineId,
    voicePreviewUiState = voicePreviewUiState,
  )
}

private fun isAsrModelSelected(
  model: Model,
  selectedAsrEngineType: AsrEngineType,
  selectedAsrModelId: String,
): Boolean {
  val builtInEngine = AsrModelRegistry.engineTypeForModelId(model.modelId)
  return if (builtInEngine != null) {
    selectedAsrEngineType == builtInEngine &&
      (selectedAsrModelId == AsrModelRegistry.modelId(builtInEngine) ||
        selectedAsrModelId == model.modelId ||
        selectedAsrModelId == model.name)
  } else {
    selectedAsrModelId == model.name ||
      (model.modelId.isNotEmpty() && selectedAsrModelId == model.modelId)
  }
}

private fun resolveEffectiveModelState(
  model: Model,
  modelStates: Map<String, SpeechModelState>,
  modelDownloadStatus: Map<String, ModelDownloadStatus>,
  fallbackDownloadStatus: ModelDownloadStatus?,
  supportedAsrEngines: Set<AsrEngineType> = AsrEngineType.entries.toSet(),
  downloadedModels: List<Model> = emptyList(),
): SpeechModelState {
  val downloadStatus = modelDownloadStatus[model.name] ?: fallbackDownloadStatus
  modelStates[model.name]?.let { repositoryState ->
    return downloadStatus?.let { newerDownloadState(model, repositoryState, it) } ?: repositoryState
  }
  if (downloadStatus != null) {
    return when (downloadStatus.status) {
      ModelDownloadStatusType.SUCCEEDED -> SpeechModelState.Ready()
      ModelDownloadStatusType.IN_PROGRESS,
      ModelDownloadStatusType.UNZIPPING -> downloadingState(model, downloadStatus)
      ModelDownloadStatusType.UNAVAILABLE ->
        SpeechModelState.Unavailable(SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW)
      ModelDownloadStatusType.NOT_DOWNLOADED,
      ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
      ModelDownloadStatusType.FAILED ->
        SpeechModelState.Downloadable(totalBytes = model.downloadInfo.totalBytes.takeIf { it > 0L })
    }
  }
  if (downloadedModels.any { it.name == model.name }) {
    return SpeechModelState.Ready()
  }
  val builtInEngine = AsrModelRegistry.engineTypeForModelId(model.modelId)
  if (builtInEngine != null) {
    return if (builtInEngine in supportedAsrEngines) {
      SpeechModelState.Ready()
    } else {
      SpeechModelState.Unavailable(SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW)
    }
  }
  return SpeechModelState.Downloadable(
    totalBytes = model.downloadInfo.totalBytes.takeIf { it > 0L }
  )
}

/**
 * Returns the state [downloadStatus] reports for [model] when it is newer than the repository's
 * [repositoryState], or `null` to keep [repositoryState].
 *
 * The repository keeps answering [SpeechModelState.Downloadable] for a model that is downloading
 * until it re-checks the model after the download: it is never told the progress of models with a
 * top-level download, and the re-check after any download runs in the background. Meanwhile:
 * - A download in progress shows as [SpeechModelState.Downloading].
 * - A finished top-level download shows as [SpeechModelState.Ready]. Other models wait for the
 *   re-check, which may still ask for a language pack.
 */
private fun newerDownloadState(
  model: Model,
  repositoryState: SpeechModelState,
  downloadStatus: ModelDownloadStatus,
): SpeechModelState? {
  if (repositoryState !is SpeechModelState.Downloadable) {
    return null
  }
  return when (downloadStatus.status) {
    ModelDownloadStatusType.IN_PROGRESS,
    ModelDownloadStatusType.UNZIPPING -> downloadingState(model, downloadStatus)
    ModelDownloadStatusType.SUCCEEDED ->
      if (model.supportsTopLevelDownload) SpeechModelState.Ready() else null
    ModelDownloadStatusType.NOT_DOWNLOADED,
    ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
    ModelDownloadStatusType.FAILED,
    ModelDownloadStatusType.UNAVAILABLE -> null
  }
}

/** Maps the in-progress [downloadStatus] of [model] to a [SpeechModelState.Downloading]. */
private fun downloadingState(
  model: Model,
  downloadStatus: ModelDownloadStatus,
): SpeechModelState.Downloading =
  SpeechModelState.Downloading(
    progress =
      DownloadProgress(
        bytesDownloaded = downloadStatus.receivedBytes,
        totalBytes = downloadStatus.totalBytes,
      ),
    cancellable = model.supportsTopLevelDownload,
    continuesInBackground = !model.supportsTopLevelDownload,
  )

private fun toModelItemStatus(
  model: Model,
  state: SpeechModelState,
  languageStates: Map<String, LanguageState>,
  languagePackStatus: Map<String, ModelDownloadStatus>,
): ConfigAudioModelItemStatus =
  when (state) {
    SpeechModelState.Checking -> ConfigAudioModelItemStatus.Checking
    is SpeechModelState.Downloadable -> ConfigAudioModelItemStatus.Downloadable(model)
    is SpeechModelState.Downloading ->
      ConfigAudioModelItemStatus.Downloading(
        receivedBytes = state.progress.bytesDownloaded,
        totalBytes = state.progress.totalBytes,
        continuesInBackground = state.continuesInBackground && !state.cancellable,
      )
    is SpeechModelState.Queued -> ConfigAudioModelItemStatus.Queued
    is SpeechModelState.NeedsLanguagePack ->
      ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_needs_language)
    is SpeechModelState.NeedsUpdate ->
      ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_update_required)
    is SpeechModelState.NeedsOptIn ->
      ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_access_required)
    is SpeechModelState.Unavailable ->
      ConfigAudioModelItemStatus.Unavailable(R.string.model_unavailable_status)
    is SpeechModelState.CheckFailed ->
      ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_check_failed)
    is SpeechModelState.EngineError ->
      ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_engine_error)
    is SpeechModelState.Hidden ->
      ConfigAudioModelItemStatus.Unavailable(R.string.model_unavailable_status)
    is SpeechModelState.Ready ->
      when {
        !installsLanguagesSeparately(model) -> ConfigAudioModelItemStatus.Ready
        hasAnyInstalledLanguage(
          model = model,
          state = state,
          languageStates = languageStates,
          languagePackStatus = languagePackStatus,
        ) -> ConfigAudioModelItemStatus.Ready
        isAnyLanguageChecking(
          model = model,
          languageStates = languageStates,
          languagePackStatus = languagePackStatus,
        ) -> ConfigAudioModelItemStatus.Checking
        else -> ConfigAudioModelItemStatus.Unavailable(R.string.supplementary_status_needs_language)
      }
  }

private fun installsLanguagesSeparately(model: Model): Boolean =
  when (model.modelType) {
    ModelType.TTS -> model.backendSpec.runtimeType.installsLanguagePacks
    ModelType.ASR -> AsrModelRegistry.installsLanguagePacks(model.modelId)
    else -> false
  }

private fun hasAnyInstalledLanguage(
  model: Model,
  state: SpeechModelState?,
  languageStates: Map<String, LanguageState>,
  languagePackStatus: Map<String, ModelDownloadStatus>,
): Boolean {
  if (languageStates.values.any { it is LanguageState.Installed }) {
    return true
  }
  if (state is SpeechModelState.Ready && state.languages.any { it is LanguageState.Installed }) {
    return true
  }
  if (languagePackStatus.values.any { it.status == ModelDownloadStatusType.SUCCEEDED }) {
    return true
  }
  // When no language pack states have been populated yet (e.g. in tests that only set Ready()),
  // treat Ready() with empty language maps as having an installed language.
  return languageStates.isEmpty() &&
    languagePackStatus.isEmpty() &&
    (state as? SpeechModelState.Ready)?.languages.isNullOrEmpty()
}

private fun isAnyLanguageChecking(
  model: Model,
  languageStates: Map<String, LanguageState>,
  languagePackStatus: Map<String, ModelDownloadStatus>,
): Boolean {
  val codes =
    when (model.modelType) {
      ModelType.TTS ->
        model.ttsProfile?.languages?.ifEmpty { null } ?: listOf(DEFAULT_TTS_LANGUAGE_CODE)
      ModelType.ASR -> AsrModelRegistry.supportedLanguages(model)
      else -> emptyList()
    }
  return codes.any { code ->
    val state = languageStates[code]
    val packStatus = languagePackStatus[code]
    state is LanguageState.Checking || (state == null && packStatus == null)
  }
}

private fun resolveLanguagePackUiStatus(
  code: String,
  languageStates: Map<String, LanguageState>,
  languagePackStatus: Map<String, ModelDownloadStatus>,
): LanguagePackUiStatus {
  val state = languageStates[code]
  if (state != null) {
    return when (state) {
      is LanguageState.Checking -> LanguagePackUiStatus.Checking
      is LanguageState.Installed -> LanguagePackUiStatus.Installed
      is LanguageState.Downloadable -> LanguagePackUiStatus.Downloadable
      is LanguageState.Downloading ->
        LanguagePackUiStatus.Downloading(
          receivedBytes = state.progress.bytesDownloaded,
          totalBytes = state.progress.totalBytes,
          stalled = state.stalled,
        )
      is LanguageState.Queued -> LanguagePackUiStatus.Downloading(0L, 0L)
      is LanguageState.Failed,
      is LanguageState.CheckFailed -> LanguagePackUiStatus.Failed
      is LanguageState.NotOffered -> LanguagePackUiStatus.Unavailable
    }
  }
  return languagePackStatus[code].toLanguagePackUiStatus()
}

private fun downloadingStatusRow(
  receivedBytes: Long,
  totalBytes: Long,
  continuesInBackground: Boolean,
): ConfigAudioStatusRowUiState {
  val text =
    when {
      totalBytes > 0L -> {
        val percent = ((receivedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        if (continuesInBackground) {
          ConfigAudioText.Formatted(
            R.string.supplementary_status_downloading_background_percent,
            percent,
          )
        } else {
          ConfigAudioText.Formatted(R.string.model_status_downloading_percent, percent)
        }
      }
      continuesInBackground ->
        ConfigAudioText.Resource(R.string.supplementary_status_downloading_background)
      else -> ConfigAudioText.Resource(R.string.tts_downloading)
    }
  return ConfigAudioStatusRowUiState(tone = ConfigAudioStatusTone.PROGRESS, text = text)
}

private fun AsrEngineType.labelRes(): Int =
  when (this) {
    AsrEngineType.SYSTEM_DEFAULT -> R.string.asr_engine_system_default
    AsrEngineType.MLKIT_BASIC -> R.string.asr_engine_mlkit_basic
    AsrEngineType.MLKIT_ADVANCED -> R.string.asr_engine_mlkit_advanced
    AsrEngineType.SPEECH_PROCESSOR -> R.string.asr_engine_speech_processor
  }
