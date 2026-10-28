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

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.data.LanguagePackDownloader
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.SupplementarySpeechModelRepository
import com.google.ai.edge.gallery.services.asr.AsrDefaults
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.AsrServiceStatus
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.asr.SpeechProcessorAsrService
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsEngineType
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsModelMetadata
import com.google.ai.edge.gallery.services.tts.TtsModelRegistry
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.ai.edge.gallery.services.tts.TtsServiceStatus
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsUiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGModelManagerAudio"

/** Persistent promo ID for the one-time Audio Config onboarding rich tooltip. */
const val AUDIO_CONFIG_ONBOARDING_PROMO_ID = "audio_config_onboarding_tooltip"

internal object DummyPlatformTtsService : TtsService {
  override val status: StateFlow<TtsServiceStatus> = MutableStateFlow(TtsServiceStatus.Ready)
  override val availableModels: List<TtsModelMetadata> = emptyList()
  override val servedModelIds: Set<String> = setOf(TtsModelIdentifier.ANDROID_NATIVE)

  override suspend fun isSupportedOnDevice(): Boolean = true

  override suspend fun prepare(config: TtsConfig) {}

  override fun synthesize(text: String, config: TtsConfig?): TtsOutput =
    TtsOutput.AudioStream(emptyFlow())

  override fun pause() {}

  override fun resume() {}

  override fun stop() {}

  override fun cancel() {}

  override fun destroy() {}
}

internal fun createDefaultSupplementaryRepository(
  context: Context,
  asrServiceProvider: AsrServiceProvider,
  ioDispatcher: CoroutineDispatcher,
  repositoryScope: CoroutineScope = CoroutineScope(ioDispatcher),
): SupplementarySpeechModelRepository =
  SupplementarySpeechModelRepository(
    context = context,
    gates = emptyMap(),
    ttsServiceProvider =
      TtsServiceProvider(mapOf(TtsEngineType.ANDROID_NATIVE to DummyPlatformTtsService)),
    asrServiceProvider = asrServiceProvider,
    ioDispatcher = ioDispatcher,
    repositoryScope = repositoryScope,
  )

internal fun createDefaultSpeechSelectionUseCase(
  context: Context,
  ttsSettingsRepository: TtsSettingsRepository,
  asrSettingsRepository: AsrSettingsRepository,
  asrServiceProvider: AsrServiceProvider,
  dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  scope: CoroutineScope = CoroutineScope(dispatcher),
): SpeechSelectionUseCase {
  val repo =
    SupplementarySpeechModelRepository(
      context = context,
      ioDispatcher = dispatcher,
      gates = emptyMap(),
      ttsServiceProvider =
        TtsServiceProvider(mapOf(TtsEngineType.ANDROID_NATIVE to DummyPlatformTtsService)),
      asrServiceProvider = asrServiceProvider,
      repositoryScope = scope,
    )
  return SpeechSelectionUseCase(
    repository = repo,
    ttsSettingsRepository = ttsSettingsRepository,
    asrSettingsRepository = asrSettingsRepository,
    asrServiceProvider = asrServiceProvider,
    scope = scope,
  )
}

internal fun ModelManagerViewModel.observeTtsSettingsUiState() {
  viewModelScope.launch {
    combine(
        mutableUiState,
        ttsSettingsRepository.selectedTtsModelID,
        ttsSettingsRepository.selectedTtsVoice,
        ttsSettingsRepository.selectedLanguageCode,
        ttsSettingsRepository.autoPlayTts,
      ) { currentUiState, _, _, _, _ ->
        buildTtsSettingsUiState(currentUiState)
      }
      .collect { _ttsSettingsUiState.value = it }
  }
  viewModelScope.launch {
    combine(
        ttsSettingsRepository.warmingEngineIds,
        ttsSettingsRepository.selectedTtsModelID.flatMapLatest { engineId ->
          ttsSettingsRepository.serviceProviderOrNull()?.service(forModelId = engineId)?.status
            ?: flowOf(null)
        },
      ) { _, _ ->
        buildTtsSettingsUiState()
      }
      .collect { _ttsSettingsUiState.value = it }
  }
  // Which models can be picked, and whether the saved one can be used, follow their states.
  val repository = supplementarySpeechModelRepository
  val ttsModelStates =
    combine(repository.models, repository.modelStates) { models, states ->
        models
          .filter { it.modelType == ModelType.TTS }
          .map { model ->
            val state = states[model.name]
            model.name to (if (state is SpeechModelState.Downloading) Unit else state)
          }
      }
      .distinctUntilChanged()

  viewModelScope.launch {
    combine(ttsModelStates, repository.languageStates) { _, _ -> buildTtsSettingsUiState() }
      .collect { _ttsSettingsUiState.value = it }
  }
  refreshTtsSettingsUiState()
}

internal fun ModelManagerViewModel.cleanupDeletedSpeechModel(model: Model) {
  if (model.modelType == ModelType.TTS) {
    ttsSettingsRepository.onModelDeleted(model)
    refreshTtsSettingsUiState()
    // Release the engine still holding the deleted allowlist model file (e.g. Kokoro 82M).
    if (model.backendSpec.runtimeType == RuntimeType.LITERT_LM) {
      val engineId = model.ttsProfile?.engineModelId?.ifEmpty { null } ?: model.name
      val engine = ttsSettingsRepository.serviceProviderOrNull()?.serviceOrNull(engineId)
      if (engine != null) {
        viewModelScope.launch(ioDispatcher) { engine.deleteModel(engineId) }
      }
    }
  }
  if (model.modelType == ModelType.ASR) {
    asrServiceProvider.onModelDeleted(model)
    val deletedEngineType =
      AsrModelRegistry.engineTypeForModelId(model.modelId)
        ?: AsrModelRegistry.resolveEngineType(model.name.ifEmpty { model.modelId })
    if (deletedEngineType == AsrEngineType.SPEECH_PROCESSOR) {
      viewModelScope.launch(ioDispatcher) {
        val asrCacheDir = File(context.cacheDir, SpeechProcessorAsrService.CACHE_DIRECTORY_NAME)
        runCatching { asrCacheDir.deleteRecursively() }
      }
    }
    asrSettingsRepository.onModelDeleted(model)
    asrServiceProvider.releaseAll(except = selectedAsrEngineType.value)
  }
  if (model.isSupplementary) {
    supplementarySpeechModelRepository.invalidateModel(model.name)
  }
}

/**
 * Tells the supplementary speech model repository about a download of [model], so the Supplementary
 * tab shows its progress and re-checks the model once the download ends.
 */
internal fun ModelManagerViewModel.publishSupplementaryDownloadStatus(
  model: Model,
  status: ModelDownloadStatus,
) {
  val supportsCancel = downloaderMap[model.backendSpec.runtimeType]?.supportsCancel == true
  when (val event = status.toSupplementaryDownloadEvent(model, supportsCancel)) {
    is SupplementaryDownloadEvent.Progress ->
      supplementarySpeechModelRepository.onModelDownloadProgress(model.name, event.state)
    is SupplementaryDownloadEvent.Queued ->
      supplementarySpeechModelRepository.onModelDownloadQueued(model.name, event.state)
    SupplementaryDownloadEvent.Completed ->
      supplementarySpeechModelRepository.onModelDownloadCompleted(model.name)
    SupplementaryDownloadEvent.Ended ->
      supplementarySpeechModelRepository.invalidateModel(model.name)
    null -> {}
  }
}

internal fun ModelManagerViewModel.shouldIncludeSupplementaryAllowlistModel(model: Model): Boolean {
  // Skip supplementary models whose runtime has no registered downloader in this build.
  if (downloaderMap[model.backendSpec.runtimeType] == null) {
    return false
  }
  return true
}

internal fun ModelManagerViewModel.registerAllowlistSpeechModels(
  allSupplementaryModels: List<Model>
) {
  AsrModelRegistry.registerAllowlistModels(allSupplementaryModels)
  // A saved allowlist ASR model only resolves to its engine once registered.
  if (asrSettingsRepository.refreshEngineType()) {
    prepareActiveAsrEngineInBackground()
  }
  supplementarySpeechModelRepository.registerModels(allSupplementaryModels.toList())
  TtsModelRegistry.registerAllowlistModels(allSupplementaryModels)
}

/** Updates whether partial recognition hypotheses are emitted during live dictation. */
fun ModelManagerViewModel.setShowInterimResults(show: Boolean) {
  asrSettingsRepository.setShowInterimResults(show)
}

/** Re-checks which [AsrEngineType] backends can be selected now ([supportedAsrEngines]). */
fun ModelManagerViewModel.checkAsrEngineSupport() {
  viewModelScope.launch(ioDispatcher) {
    _supportedAsrEngines.value = speechSelectionUseCase.selectableAsrEngineTypes()
    // Refreshes activeAsrEngineType.
    val unused = asrServiceProvider.resolveActiveService()
  }
}

/** Returns the reactive [AsrServiceStatus] flow for [engineType]. */
fun ModelManagerViewModel.getAsrServiceStatus(
  engineType: AsrEngineType
): StateFlow<AsrServiceStatus> = asrServiceProvider.service(engineType).status

/**
 * Returns the download status of the ASR supplementary model served by [engineType], or `null` if
 * there is no such model.
 */
fun ModelManagerViewModel.getAsrDownloadStatus(engineType: AsrEngineType): ModelDownloadStatus? {
  val asrModel =
    mutableUiState.value.supplementaryModels.firstOrNull {
      it.modelType == ModelType.ASR &&
        AsrModelRegistry.engineTypeForModelId(it.modelId) == engineType
    }
  if (asrModel != null) {
    return mutableUiState.value.modelDownloadStatus[asrModel.name]
  }
  val service = asrServiceProvider.serviceOrNull(engineType)
  if (service?.managesOwnDownloads == true) {
    val serviceStatus = service.status.value
    val isDownloaded =
      when (serviceStatus) {
        is AsrServiceStatus.Downloaded,
        is AsrServiceStatus.Ready,
        is AsrServiceStatus.Initializing,
        is AsrServiceStatus.Recognizing,
        is AsrServiceStatus.Processing -> true
        is AsrServiceStatus.Downloading,
        is AsrServiceStatus.NotDownloaded,
        is AsrServiceStatus.Unavailable,
        is AsrServiceStatus.Failed -> false
        is AsrServiceStatus.Unknown -> {
          checkAsrEngineSupport()
          false
        }
      }
    return ModelDownloadStatus(
      status =
        if (isDownloaded) {
          ModelDownloadStatusType.SUCCEEDED
        } else {
          ModelDownloadStatusType.NOT_DOWNLOADED
        }
    )
  }
  return null
}

/**
 * Returns the download status of the ASR supplementary model identified by [modelId], or `null` if
 * there is no such model.
 */
fun ModelManagerViewModel.getAsrDownloadStatus(modelId: String): ModelDownloadStatus? {
  val engineType = AsrModelRegistry.engineTypeForModelId(modelId)
  if (engineType != null) {
    return getAsrDownloadStatus(engineType)
  }
  val asrModel =
    mutableUiState.value.supplementaryModels.firstOrNull {
      it.modelType == ModelType.ASR && (it.name == modelId || it.modelId == modelId)
    }
  return asrModel?.let { mutableUiState.value.modelDownloadStatus[it.name] }
}

/**
 * Returns the downloaded supplementary ASR models that do not map to a built-in [AsrEngineType]
 * (such as `.litertlm` ASR models).
 */
fun ModelManagerViewModel.getDownloadedAsrModels(): List<Model> =
  mutableUiState.value.supplementaryModels.filter {
    it.modelType == ModelType.ASR &&
      AsrModelRegistry.engineTypeForModelId(it.modelId) == null &&
      mutableUiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
  }

/** Selects [engineType] as the ASR engine and prepares it in the background. */
fun ModelManagerViewModel.saveAsrEngineType(engineType: AsrEngineType) {
  asrSettingsRepository.setSelectedEngineType(engineType)
  asrServiceProvider.releaseAll(except = engineType)
  prepareActiveAsrEngineInBackground()
}

/** Selects [model] as the ASR model and prepares the active ASR engine in the background. */
fun ModelManagerViewModel.saveAsrModel(model: Model) {
  asrSettingsRepository.setSelectedModel(model)
  asrServiceProvider.releaseAll(except = selectedAsrEngineType.value)
  prepareActiveAsrEngineInBackground()
}

/** Selects [languageCode] for the active ASR engine and prepares it in the background. */
fun ModelManagerViewModel.selectAsrLanguage(languageCode: String) {
  asrSettingsRepository.setSelectedLanguageCode(languageCode)
  prepareActiveAsrEngineInBackground()
}

internal fun ModelManagerViewModel.prepareActiveAsrEngineInBackground() {
  viewModelScope.launch(ioDispatcher) {
    try {
      val unused = asrServiceProvider.prepareActiveService()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prepare the ${selectedAsrEngineType.value} ASR engine", e)
    }
  }
}

/** Queries and updates the download/availability status of all loaded supplementary models. */
fun ModelManagerViewModel.refreshSupplementaryStatuses() {
  viewModelScope.launch {
    // Query all statuses concurrently, then apply them in allowlist order.
    val pendingStatuses = mutableSupplementaryModels.mapNotNull { model ->
      val downloader = downloaderMap[model.backendSpec.runtimeType] ?: return@mapNotNull null
      model to
        async {
          try {
            downloader.queryStatus(model)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Failed to query status for supplementary model '${model.name}'", e)
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              errorMessage = e.localizedMessage.orEmpty(),
            )
          }
        }
    }
    for ((model, pendingStatus) in pendingStatuses) {
      val status = pendingStatus.await()
      // A download in flight reports its own status. The files on disk don't show it, e.g. when
      // processPendingDownloads has just attached to a download left by an earlier activity.
      val curStatus = uiState.value.modelDownloadStatus[model.name]?.status
      if (
        model.downloadsViaRepository &&
          (curStatus == ModelDownloadStatusType.IN_PROGRESS ||
            curStatus == ModelDownloadStatusType.UNZIPPING)
      ) {
        continue
      }
      setDownloadStatus(curModel = model, status = status)
    }
    // The read-aloud default is only ever picked among ready models.
    speechSelectionUseCase.autoPickDefaultTts { refreshTtsSettingsUiState() }
    refreshTtsSettingsUiState()
  }
}

/** Selects [engineId] (optionally backed by [model]) as the active TTS engine. */
fun ModelManagerViewModel.selectTtsEngine(engineId: String, model: Model? = null) {
  stopVoicePreview()
  ttsSettingsRepository.setSelectedTtsModelID(
    id = engineId,
    isExplicitUserSelection = true,
    model = model,
  )
  ttsSettingsRepository.warmEngineIfVoicesMissing(
    engineId = engineId,
    model = model,
    supplementaryModels = mutableUiState.value.supplementaryModels,
  ) {
    refreshTtsSettingsUiState()
  }
  refreshTtsSettingsUiState()
}

/** Enables or disables automatic TTS playback of assistant responses. */
fun ModelManagerViewModel.setAutoPlayTts(enabled: Boolean) {
  ttsSettingsRepository.setAutoPlayTts(enabled)
  refreshTtsSettingsUiState()
}

/** Selects [languageCode] for the active TTS engine. */
fun ModelManagerViewModel.selectTtsLanguage(languageCode: String) {
  ttsSettingsRepository.setSelectedLanguageCode(
    languageCode = languageCode,
    supplementaryModels = mutableUiState.value.supplementaryModels,
  )
  refreshTtsSettingsUiState()
}

/**
 * Whether [model] installs each language as a separate pack that the user downloads and picks from.
 * `false` when the model bundles all of its languages or its runtime has no language packs.
 */
fun ModelManagerViewModel.installsLanguagesSeparately(model: Model): Boolean =
  supplementarySpeechModelRepository.installsLanguagePacksSeparately(model)

/**
 * Queries the install status of each of [languageCodes] for [model]'s language packs.
 *
 * When [model] bundles its languages ([installsLanguagesSeparately] is `false`), every code is
 * reported installed without a query: the language list is only shown for downloaded models.
 */
fun ModelManagerViewModel.refreshLanguagePackStatuses(model: Model, languageCodes: List<String>) {
  val downloader = downloaderMap[model.backendSpec.runtimeType] as? LanguagePackDownloader
  if (downloader == null || !downloader.installsLanguagesSeparately(model)) {
    val installedStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
    setLanguagePackStatuses(model, languageCodes.associateWith { installedStatus })
    return
  }
  viewModelScope.launch {
    val pendingStatuses = languageCodes.map { code ->
      code to
        async {
          try {
            downloader.queryLanguagePackStatus(model, code)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Failed to query language pack '$code' for '${model.name}'", e)
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              errorMessage = e.localizedMessage.orEmpty(),
            )
          }
        }
    }
    for ((code, pendingStatus) in pendingStatuses) {
      val status = pendingStatus.await()
      // Don't let a stale query overwrite a download that started meanwhile.
      val current = mutableUiState.value.languagePackStatus[model.name]?.get(code)?.status
      if (current != ModelDownloadStatusType.IN_PROGRESS) {
        setLanguagePackStatus(model, code, status)
      }
    }
  }
}

/**
 * Re-checks the [languageCode] pack of [model] in the supplementary speech model repository in the
 * background, for example after preparing a voice may have installed it.
 */
internal fun ModelManagerViewModel.refreshSupplementaryLanguagePack(
  model: Model,
  languageCode: String,
) {
  if (!supplementarySpeechModelRepository.installsLanguagePacksSeparately(model)) {
    return
  }
  viewModelScope.launch {
    try {
      supplementarySpeechModelRepository.refreshLanguagePacks(
        model,
        listOf(languageCode),
        forceRefresh = true,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to re-check language pack '$languageCode' for '${model.name}'", e)
    }
  }
}

/** Starts downloading the [languageCode] pack for [model]. */
fun ModelManagerViewModel.downloadLanguagePack(model: Model, languageCode: String) {
  val downloader = downloaderMap[model.backendSpec.runtimeType] as? LanguagePackDownloader ?: return
  downloader.downloadLanguagePack(model, languageCode) { status ->
    setLanguagePackStatus(model, languageCode, status)
    supplementarySpeechModelRepository.onLanguageDownloadProgress(
      model.name,
      status.toLanguageState(languageCode),
    )
  }
}

internal fun ModelManagerViewModel.setLanguagePackStatus(
  model: Model,
  languageCode: String,
  status: ModelDownloadStatus,
) {
  setLanguagePackStatuses(model, mapOf(languageCode to status))
}

/** Merges [statuses], keyed by language code, into [model]'s language pack statuses. */
internal fun ModelManagerViewModel.setLanguagePackStatuses(
  model: Model,
  statuses: Map<String, ModelDownloadStatus>,
) {
  mutableUiState.update { currentState ->
    val modelStatuses = currentState.languagePackStatus[model.name].orEmpty()
    currentState.copy(
      languagePackStatus =
        currentState.languagePackStatus + (model.name to (modelStatuses + statuses))
    )
  }
  refreshTtsSettingsUiState()
}

internal fun ModelManagerViewModel.refreshTtsSettingsUiState() {
  _ttsSettingsUiState.value = buildTtsSettingsUiState()
}

internal fun ModelManagerViewModel.buildTtsSettingsUiState(
  currentUiState: ModelManagerUiState = mutableUiState.value
): TtsSettingsUiState {
  val ttsModels = currentUiState.supplementaryModels.filter { it.modelType == ModelType.TTS }
  val selectableTtsModels = speechSelectionUseCase.selectableModels(SpeechModality.TTS)
  // The saved model is never replaced. When it can't be used, the notice names it and the system
  // engine, with its own voices, speaks instead.
  val effectiveSelection = speechSelectionUseCase.effectiveTtsSelection()
  val savedModelId = ttsSettingsRepository.selectedTtsModelID.value
  val speakingModelId = effectiveSelection.engineId
  return TtsSettingsUiState(
    unavailableModelName = effectiveSelection.notice?.modelName,
    selectedModelId = savedModelId,
    selectedVoiceId = effectiveSelection.voiceId,
    selectedLanguageCode = ttsSettingsRepository.selectedLanguageCode.value,
    autoPlayTts = ttsSettingsRepository.autoPlayTts.value,
    downloadedModels = selectableTtsModels,
    availableVoices =
      ttsSettingsRepository.getAvailableVoicesForEngine(speakingModelId, selectableTtsModels),
    voicesByModelName =
      ttsModels.associate { model -> model.name to ttsSettingsRepository.resolveVoices(model) },
    languagesByModelName =
      ttsModels.associate { model -> model.name to ttsSettingsRepository.resolveLanguages(model) },
    isVoiceListLoading = ttsSettingsRepository.isEngineInitializing(speakingModelId),
  )
}

/**
 * Returns whether [task] is eligible for the Audio Config onboarding tooltip based on task ID and
 * active speech feature flags (`ENABLE_ASR` for speech input or `ENABLE_TTS` for read-aloud).
 */
fun ModelManagerViewModel.isTaskEligibleForAudioConfigOnboarding(task: Task): Boolean {
  if (!task.supportsAudioConfigOnboarding) return false
  return task.supportsSpeechInput || task.supportsReadAloud
}

/**
 * Loads whether the user has previously viewed the Audio Config onboarding promo from
 * [DataStoreRepository] on [ioDispatcher] and updates [audioConfigOnboardingPromoViewed].
 */
suspend fun ModelManagerViewModel.refreshAudioConfigOnboardingState(): Boolean {
  val viewed =
    withContext(ioDispatcher) {
      dataStoreRepository.hasViewedPromo(AUDIO_CONFIG_ONBOARDING_PROMO_ID)
    }
  _audioConfigOnboardingPromoViewed.value = viewed
  return viewed
}

/**
 * Returns whether the Audio Config onboarding tooltip should be shown for [task].
 *
 * Shown when [task] is one of the eligible speech-enabled tasks, the corresponding ASR or TTS
 * feature flag is enabled, the user has not dismissed the tooltip during the current app session,
 * and the user has not permanently dismissed it in a previous session.
 */
fun ModelManagerViewModel.shouldShowAudioConfigOnboarding(task: Task): Boolean {
  if (!isTaskEligibleForAudioConfigOnboarding(task)) return false
  if (_audioConfigOnboardingDismissedInSession.value) return false
  if (_audioConfigOnboardingPromoViewed.value == true) return false
  return !dataStoreRepository.hasViewedPromo(AUDIO_CONFIG_ONBOARDING_PROMO_ID)
}

/**
 * Dismisses the Audio Config onboarding tooltip for the remainder of the current app session across
 * all tasks without persisting to [DataStoreRepository].
 */
fun ModelManagerViewModel.dismissAudioConfigOnboardingForSession() {
  _audioConfigOnboardingDismissedInSession.value = true
}

/**
 * Dismisses the Audio Config onboarding tooltip for the current session and permanently records it
 * in [DataStoreRepository] on [ioDispatcher] so it never appears again on future app launches.
 */
fun ModelManagerViewModel.dismissAudioConfigOnboardingPermanently() {
  _audioConfigOnboardingDismissedInSession.value = true
  _audioConfigOnboardingPromoViewed.value = true
  viewModelScope.launch(ioDispatcher) {
    dataStoreRepository.addViewedPromoId(AUDIO_CONFIG_ONBOARDING_PROMO_ID)
  }
}

/** Refreshes speech engine and language pack states when the Config side sheet opens for [task]. */
fun ModelManagerViewModel.onAudioConfigOpened(task: Task) {
  if (!task.supportsAudioConfig) return
  if (task.supportsSpeechInput) {
    checkAsrEngineSupport()
  }
  supplementarySpeechModelRepository.prewarm()
  for (model in mutableUiState.value.supplementaryModels) {
    if (installsLanguagesSeparately(model)) {
      onAudioLanguageDropdownExpanded(model)
    }
  }
}

/** Refreshes the language pack statuses for [model] when its language dropdown opens. */
fun ModelManagerViewModel.onAudioLanguageDropdownExpanded(model: Model) {
  val codes =
    when (model.modelType) {
      ModelType.ASR -> AsrModelRegistry.supportedLanguages(model)
      ModelType.TTS -> ttsSettingsRepository.resolveLanguages(model)
      else -> emptyList()
    }
  if (codes.isEmpty()) return
  val repository = supplementarySpeechModelRepository
  if (!repository.installsLanguagePacksSeparately(model)) return
  val knownCodes = repository.languageStates.value[model.name].orEmpty().keys
  val uncheckedCodes = codes.filterNot { it in knownCodes }
  if (uncheckedCodes.isEmpty()) return
  viewModelScope.launch(ioDispatcher) {
    try {
      repository.refreshLanguagePacks(model, uncheckedCodes)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to refresh language packs for '${model.name}'", e)
    }
  }
}

/** Applies a model selection from the Config side sheet's Audio tab. */
fun ModelManagerViewModel.selectAudioModel(target: ConfigAudioModelSelectionTarget) {
  when (target) {
    ConfigAudioModelSelectionTarget.SystemAsr -> saveAsrEngineType(AsrEngineType.SYSTEM_DEFAULT)
    is ConfigAudioModelSelectionTarget.AsrModel -> {
      val model = target.model
      if (model != null) {
        saveAsrModel(model)
      } else {
        AsrEngineType.entries.firstOrNull { it.name == target.engineName }?.let(::saveAsrEngineType)
      }
    }
    ConfigAudioModelSelectionTarget.SystemTts ->
      selectTtsEngine(TtsModelIdentifier.ANDROID_NATIVE, null)
    is ConfigAudioModelSelectionTarget.TtsModel -> selectTtsEngine(target.engineId, target.model)
  }
}

/** Restores the Audio tab's ASR and TTS settings for [task] to their defaults. */
fun ModelManagerViewModel.restoreAudioDefaults(task: Task) {
  val hideSystemSpeechDefaults =
    getCustomTaskByTaskId(task.id)?.modelSelectionConfig?.hideSystemSpeechDefaults == true
  if (task.supportsSpeechInput) {
    if (!hideSystemSpeechDefaults) {
      saveAsrEngineType(AsrEngineType.SYSTEM_DEFAULT)
    }
    if (task.showsAudioLanguageSelectors) {
      selectAsrLanguage(AsrDefaults.LANGUAGE_CODE)
    }
    if (task.supportsLiveTranscriptionToggle) {
      setShowInterimResults(AsrDefaults.SHOW_INTERIM_RESULTS)
    }
  }
  if (task.supportsReadAloud) {
    if (!hideSystemSpeechDefaults) {
      selectTtsEngine(TtsModelIdentifier.ANDROID_NATIVE, null)
    }
    if (task.showsAudioLanguageSelectors) {
      selectTtsLanguage(DEFAULT_TTS_LANGUAGE_CODE)
    }
    selectTtsVoice(TtsSettingsRepository.DEFAULT_SYSTEM_VOICE, playPreview = false)
    if (task.supportsAutoPlayTts) {
      setAutoPlayTts(false)
    }
  }
}

/** Builds the current [ConfigAudioTabUiState] snapshot for [task]. */
fun ModelManagerViewModel.buildAudioTabUiState(
  task: Task,
  currentUiState: ModelManagerUiState = mutableUiState.value,
  currentTtsUiState: TtsSettingsUiState = _ttsSettingsUiState.value,
  currentAsrEngineType: AsrEngineType = selectedAsrEngineType.value,
  currentAsrModelId: String = selectedAsrModelId.value,
  currentAsrLanguageCode: String = selectedAsrLanguageCode.value,
  currentAsrServiceStatus: AsrServiceStatus = getAsrServiceStatus(currentAsrEngineType).value,
  currentSupportedAsrEngines: Set<AsrEngineType> = supportedAsrEngines.value,
  currentShowInterimResults: Boolean = showInterimResults.value,
  currentVoicePreviewUiState: VoicePreviewUiState = voicePreviewUiState.value,
): ConfigAudioTabUiState {
  val hideSystemSpeechDefaults =
    getCustomTaskByTaskId(task.id)?.modelSelectionConfig?.hideSystemSpeechDefaults == true
  val repository = supplementarySpeechModelRepository
  val selectedTtsModel =
    currentUiState.supplementaryModels.firstOrNull {
      it.modelType == ModelType.TTS &&
        (it.ttsProfile?.engineModelId == currentTtsUiState.selectedModelId ||
          it.name == currentTtsUiState.selectedModelId)
    }
  return buildConfigAudioTabUiState(
    task = task,
    enableAsr = true,
    enableTts = true,
    hideSystemSpeechDefaults = hideSystemSpeechDefaults,
    supplementaryModels = currentUiState.supplementaryModels,
    modelStates = repository.modelStates.value,
    languageStates = repository.languageStates.value,
    modelDownloadStatus = currentUiState.modelDownloadStatus,
    languagePackStatus = currentUiState.languagePackStatus,
    selectedAsrEngineType = currentAsrEngineType,
    selectedAsrModelId = currentAsrModelId,
    selectedAsrLanguageCode = currentAsrLanguageCode,
    selectedAsrServiceStatus = currentAsrServiceStatus,
    supportedAsrEngines = currentSupportedAsrEngines,
    downloadedAsrModels = getDownloadedAsrModels(),
    asrDownloadStatus = getAsrDownloadStatus(currentAsrModelId),
    showInterimResults = currentShowInterimResults,
    ttsSettingsUiState = currentTtsUiState,
    ttsDownloadStatus = selectedTtsModel?.let { currentUiState.modelDownloadStatus[it.name] },
    voicePreviewUiState = currentVoicePreviewUiState,
  )
}

/** Returns a reactive [Flow] of [ConfigAudioTabUiState] for [task]. */
fun ModelManagerViewModel.observeAudioTabUiState(task: Task): Flow<ConfigAudioTabUiState> {
  val asrStatusFlow = selectedAsrEngineType.flatMapLatest { engineType ->
    getAsrServiceStatus(engineType)
  }
  val repoModelStates = supplementarySpeechModelRepository.modelStates
  val repoLanguageStates = supplementarySpeechModelRepository.languageStates
  return combine(
    mutableUiState,
    _ttsSettingsUiState,
    selectedAsrEngineType,
    selectedAsrModelId,
    selectedAsrLanguageCode,
    asrStatusFlow,
    supportedAsrEngines,
    showInterimResults,
    repoModelStates,
    repoLanguageStates,
    voicePreviewUiState,
  ) { _ ->
    buildAudioTabUiState(task = task)
  }
}

/** Creates the [ConfigAudioTabActions] wired to this ViewModel for [task]. */
fun ModelManagerViewModel.createAudioTabActions(task: Task): ConfigAudioTabActions =
  ConfigAudioTabActions(
    onSelectModel = { target ->
      selectAudioModel(target)
      when (target) {
        ConfigAudioModelSelectionTarget.SystemAsr,
        is ConfigAudioModelSelectionTarget.AsrModel -> {
          val asrId = selectedAsrModelId.value
          Log.d(
            TAG,
            "Analytics: button_clicked, event_type=select_asr_model, button_id=$asrId, capability_name=${task.id}",
          )
          logButtonClick("select_asr_model", buttonId = asrId, capabilityName = task.id)
        }
        ConfigAudioModelSelectionTarget.SystemTts,
        is ConfigAudioModelSelectionTarget.TtsModel -> {
          val engineId = _ttsSettingsUiState.value.selectedModelId
          Log.d(
            TAG,
            "Analytics: button_clicked, event_type=select_tts_model, button_id=$engineId, capability_name=${task.id}",
          )
          logButtonClick("select_tts_model", buttonId = engineId, capabilityName = task.id)
        }
      }
    },
    onDownloadModel = { model -> downloadModel(task = null, model = model) },
    onSelectAsrLanguage = ::selectAsrLanguage,
    onSelectTtsLanguage = ::selectTtsLanguage,
    onDownloadLanguagePack = ::downloadLanguagePack,
    onLanguageDropdownExpanded = ::onAudioLanguageDropdownExpanded,
    onLiveTranscriptionChanged = ::setShowInterimResults,
    onSelectTtsVoice = { voiceId -> selectTtsVoice(voiceId, playPreview = false) },
    onToggleVoicePreview = ::toggleConfigVoicePreview,
    onStopVoicePreview = ::stopVoicePreview,
    onAutoPlayTtsChanged = { enabled ->
      Log.d(
        TAG,
        "Analytics: button_clicked, event_type=toggle_auto_play_tts, enabled=$enabled, capability_name=${task.id}",
      )
      logButtonClick("toggle_auto_play_tts", capabilityName = task.id) {
        putBoolean("enabled", enabled)
      }
      setAutoPlayTts(enabled)
    },
    onRestoreDefaults = { restoreAudioDefaults(task) },
  )
