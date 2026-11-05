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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.media.AudioTrack
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BackendSpec
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DefaultNetworkStateProvider
import com.google.ai.edge.gallery.data.DeviceIdentity
import com.google.ai.edge.gallery.data.DeviceRequirementRegistry
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.DownloadStorageManager
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.ImportOrigin
import com.google.ai.edge.gallery.data.LiteRtLmModelDownloader
import com.google.ai.edge.gallery.data.LlmProfile
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.ModelUtils
import com.google.ai.edge.gallery.data.NetworkStateProvider
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.OssModelImportLogger
import com.google.ai.edge.gallery.data.PendingDownload
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.data.getTargetTaskIdsForImportedModel
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.SupplementarySpeechModelRepository
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelDownloader
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.asr.SystemSpeechAsrService
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsUiState
import com.google.ai.edge.gallery.ui.common.isMemoryLow
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlin.collections.sortedWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

/**
 * Location of an optional local model allowlist used for testing.
 *
 * When this file exists, it takes precedence over the allowlist fetched from the network, e.g.:
 * ```
 * adb push dev.json /data/local/tmp/model_allowlist_test.json
 * ```
 *
 * Delete the file to go back to the regular allowlist.
 */
const val MODEL_ALLOWLIST_TEST_FILE_PATH = "/data/local/tmp/model_allowlist_test.json"

enum class TokenStatus {
  NOT_STORED,
  EXPIRED,
  NOT_EXPIRED,
}

enum class TokenRequestResultType {
  FAILED,
  SUCCEEDED,
  USER_CANCELLED,
}

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)

data class TokenRequestResult(val status: TokenRequestResultType, val errorMessage: String? = null)

/** What clicking a model's download button does. */
sealed interface DownloadClickAction {
  /** Download the model, sending [accessToken] if it isn't `null`. */
  data class Download(val accessToken: String?) : DownloadClickAction

  /** Ask the user for the access the last download attempt was refused for. */
  data class ResolveAuth(val authError: DownloadAuthError) : DownloadClickAction
}

/**
 * Whether replacing a model's [previous] download status with [status] only reports the progress of
 * an ongoing download. Such updates arrive several times a second on the main thread and change
 * nothing but the download progress.
 */
internal fun isDownloadProgressUpdate(
  previous: ModelDownloadStatus?,
  status: ModelDownloadStatus,
): Boolean =
  status.status == ModelDownloadStatusType.IN_PROGRESS &&
    previous?.status == ModelDownloadStatusType.IN_PROGRESS

/**
 * Returns this status, read from the files on disk, with the reason [previous] gave for pausing the
 * same download, e.g. "Connection lost". The files on disk only tell that the download is paused.
 */
internal fun ModelDownloadStatus.keepingPauseReason(
  previous: ModelDownloadStatus?
): ModelDownloadStatus =
  if (
    status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED &&
      previous?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED &&
      errorMessage.isEmpty()
  ) {
    copy(errorMessage = previous.errorMessage)
  } else {
    this
  }

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /**
   * Whether to show a warning that the model allowlist was loaded from the local test file at
   * [MODEL_ALLOWLIST_TEST_FILE_PATH] instead of the regular source.
   */
  val showLocalTestAllowlistDialog: Boolean = false,

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,

  /**
   * A map that tracks whether optional components are enabled for download for each model, indexed
   * by model name.
   */
  val downloadOptionalComponents: Map<String, Boolean> = mapOf(),

  /** A map that tracks the download status of optional extra data files, indexed by model name. */
  val extraDataDownloadStatus: Map<String, ModelDownloadStatus> = mapOf(),

  /** Supplementary models that are not bound to a specific task. */
  val supplementaryModels: List<Model> = emptyList(),

  /**
   * The name of the main model last launched from the model selection screen of each task that has
   * one, by task id.
   */
  val lastMainModelByTaskId: Map<String, String> = mapOf(),
  /**
   * Download status of each language pack, indexed by model name and then by BCP-47 language tag.
   * Only populated for models whose runtime installs language packs separately.
   */
  val languagePackStatus: Map<String, Map<String, ModelDownloadStatus>> = mapOf(),
) {
  fun isDownloadOptionalComponentsEnabled(modelName: String): Boolean {
    return downloadOptionalComponents[modelName] ?: true
  }

  fun getExtraDataDownloadStatus(modelName: String): ModelDownloadStatus? {
    return extraDataDownloadStatus[modelName]
  }
}

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )
private val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.SMART_ALBUM,
    BuiltInTaskId.VIDEO_MOMENT_FINDER,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
    BuiltInTaskId.MP_SCRAPBOOK,
  )

/**
 * A download waiting for the user to agree to use mobile data (see
 * [ModelManagerViewModel.confirmMobileDataDownload]).
 *
 * @property downloadBytes The remaining size of everything that will be downloaded.
 */
class MobileDataDownloadConfirmation
internal constructor(
  val downloadBytes: Long,
  internal val onConfirmed: () -> Unit,
  internal val onCancelled: () -> Unit,
)

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  private val systemPromptRepository: SystemPromptRepository,
  val huggingFaceApiClient: HuggingFaceApiClient,
  @ApplicationContext internal val context: Context,
  protected val downloaders: Map<RuntimeType, @JvmSuppressWildcards ModelDownloader>,
  private val ossModelImportLogger: OssModelImportLogger,
  internal val ttsSettingsRepository: TtsSettingsRepository,
  internal val asrSettingsRepository: AsrSettingsRepository,
  internal val asrServiceProvider: AsrServiceProvider,
  @IoDispatcher internal val ioDispatcher: CoroutineDispatcher,
  /** Availability and language pack states of supplementary speech models. */
  internal val supplementarySpeechModelRepository: SupplementarySpeechModelRepository,
  /**
   * Selection rules for supplementary speech models (only ready models are selectable, and a saved
   * default that can't be used is never replaced).
   */
  internal val speechSelectionUseCase: SpeechSelectionUseCase =
    createDefaultSpeechSelectionUseCase(
      context,
      ttsSettingsRepository,
      asrSettingsRepository,
      asrServiceProvider,
    ),
  /** Whether the device is connected, and on mobile data, which downloads ask about first. */
  private val networkStateProvider: NetworkStateProvider = DefaultNetworkStateProvider(context),
) :
  ViewModel()
{
  constructor(
    downloadRepository: DownloadRepository,
    dataStoreRepository: DataStoreRepository,
    lifecycleProvider: AppLifecycleProvider,
    customTasks: Set<@JvmSuppressWildcards CustomTask>,
    systemPromptRepository: SystemPromptRepository,
    huggingFaceApiClient: HuggingFaceApiClient,
    context: Context,
    downloaders: Map<RuntimeType, @JvmSuppressWildcards ModelDownloader>,
    ossModelImportLogger: OssModelImportLogger = OssModelImportLogger(),
    networkStateProvider: NetworkStateProvider = DefaultNetworkStateProvider(context),
  ) : this(
    downloadRepository = downloadRepository,
    dataStoreRepository = dataStoreRepository,
    lifecycleProvider = lifecycleProvider,
    customTasks = customTasks,
    systemPromptRepository = systemPromptRepository,
    huggingFaceApiClient = huggingFaceApiClient,
    context = context,
    downloaders = downloaders,
    ossModelImportLogger = ossModelImportLogger,
    asrSettingsRepository =
      AsrSettingsRepository(
        dataStoreRepository = dataStoreRepository,
        scope = CoroutineScope(Dispatchers.Unconfined),
      ),
    networkStateProvider = networkStateProvider,
  )

  private constructor(
    downloadRepository: DownloadRepository,
    dataStoreRepository: DataStoreRepository,
    lifecycleProvider: AppLifecycleProvider,
    customTasks: Set<@JvmSuppressWildcards CustomTask>,
    systemPromptRepository: SystemPromptRepository,
    huggingFaceApiClient: HuggingFaceApiClient,
    context: Context,
    downloaders: Map<RuntimeType, @JvmSuppressWildcards ModelDownloader>,
    ossModelImportLogger: OssModelImportLogger = OssModelImportLogger(),
    asrSettingsRepository: AsrSettingsRepository,
    networkStateProvider: NetworkStateProvider,
  ) : this(
    downloadRepository = downloadRepository,
    dataStoreRepository = dataStoreRepository,
    lifecycleProvider = lifecycleProvider,
    customTasks = customTasks,
    systemPromptRepository = systemPromptRepository,
    huggingFaceApiClient = huggingFaceApiClient,
    context = context,
    downloaders = downloaders,
    ossModelImportLogger = ossModelImportLogger,
    ttsSettingsRepository =
      TtsSettingsRepository(
        dataStoreRepository = dataStoreRepository,
        ttsServiceProviderLazy = Optional.empty(),
        ioDispatcher = Dispatchers.Unconfined,
      ),
    asrSettingsRepository = asrSettingsRepository,
    asrServiceProvider =
      AsrServiceProvider(
        engines = mapOf(AsrEngineType.SYSTEM_DEFAULT to SystemSpeechAsrService(context)),
        asrSettingsRepository = asrSettingsRepository,
      ),
    ioDispatcher = Dispatchers.Unconfined,
    networkStateProvider = networkStateProvider,
  )

  internal constructor(
    downloadRepository: DownloadRepository,
    dataStoreRepository: DataStoreRepository,
    lifecycleProvider: AppLifecycleProvider,
    customTasks: Set<@JvmSuppressWildcards CustomTask>,
    systemPromptRepository: SystemPromptRepository,
    huggingFaceApiClient: HuggingFaceApiClient,
    context: Context,
    downloaders: Map<RuntimeType, @JvmSuppressWildcards ModelDownloader>,
    ossModelImportLogger: OssModelImportLogger = OssModelImportLogger(),
    ttsSettingsRepository: TtsSettingsRepository,
    asrSettingsRepository: AsrSettingsRepository,
    asrServiceProvider: AsrServiceProvider,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    networkStateProvider: NetworkStateProvider = DefaultNetworkStateProvider(context),
  ) : this(
    downloadRepository = downloadRepository,
    dataStoreRepository = dataStoreRepository,
    lifecycleProvider = lifecycleProvider,
    customTasks = customTasks,
    systemPromptRepository = systemPromptRepository,
    huggingFaceApiClient = huggingFaceApiClient,
    context = context,
    downloaders = downloaders,
    ossModelImportLogger = ossModelImportLogger,
    ttsSettingsRepository = ttsSettingsRepository,
    asrSettingsRepository = asrSettingsRepository,
    asrServiceProvider = asrServiceProvider,
    ioDispatcher = ioDispatcher,
    supplementarySpeechModelRepository =
      createDefaultSupplementaryRepository(
        context = context,
        asrServiceProvider = asrServiceProvider,
        ioDispatcher = ioDispatcher,
      ),
    networkStateProvider = networkStateProvider,
  )

  constructor(
    downloadRepository: DownloadRepository,
    dataStoreRepository: DataStoreRepository,
    lifecycleProvider: AppLifecycleProvider,
    customTasks: Set<@JvmSuppressWildcards CustomTask>,
    systemPromptRepository: SystemPromptRepository,
    huggingFaceApiClient: HuggingFaceApiClient,
    context: Context,
    ossModelImportLogger: OssModelImportLogger = OssModelImportLogger(),
    networkStateProvider: NetworkStateProvider = DefaultNetworkStateProvider(context),
  ) : this(
    downloadRepository = downloadRepository,
    dataStoreRepository = dataStoreRepository,
    lifecycleProvider = lifecycleProvider,
    customTasks = customTasks,
    systemPromptRepository = systemPromptRepository,
    huggingFaceApiClient = huggingFaceApiClient,
    context = context,
    downloaders =
      mapOf(
        RuntimeType.UNKNOWN to LiteRtLmModelDownloader(context, downloadRepository),
        RuntimeType.LITERT_LM to LiteRtLmModelDownloader(context, downloadRepository),
        RuntimeType.AICORE to AICoreModelDownloader(context),
      ),
    ossModelImportLogger = ossModelImportLogger,
    networkStateProvider = networkStateProvider,
  )

  private val modelsDir = getModelStorageDir(context)
  private val storageManager = DownloadStorageManager(context, modelsDir)

  /** Local test allowlist; see [MODEL_ALLOWLIST_TEST_FILE_PATH]. Overridden in tests. */
  @VisibleForTesting internal var localTestAllowlistFile = File(MODEL_ALLOWLIST_TEST_FILE_PATH)

  /** Dispatcher that [loadModelAllowlist] runs on. Overridden in tests to load synchronously. */
  @VisibleForTesting internal var allowlistLoadingDispatcher: CoroutineDispatcher = Dispatchers.IO

  /**
   * The provider of device identity matched against device-requirement sets. Overridden in tests.
   */
  @VisibleForTesting
  internal var deviceProvider: () -> DeviceIdentity = { DeviceIdentity.current() }

  /**
   * Returns the free storage, in bytes, where models are downloaded, or `null` if it can't be read.
   * Overridden in tests.
   */
  @VisibleForTesting
  internal var availableStorageBytesProvider: () -> Long? =
    storageManager::defaultAvailableStorageBytes

  private val _mobileDataDownloadConfirmation =
    MutableStateFlow<MobileDataDownloadConfirmation?>(null)

  /**
   * The download waiting for the user to agree to use mobile data, if any, shown by the app's
   * mobile data dialog. See [confirmMobileDataDownload].
   */
  val mobileDataDownloadConfirmation: StateFlow<MobileDataDownloadConfirmation?> =
    _mobileDataDownloadConfirmation.asStateFlow()

  protected val _uiState: MutableStateFlow<ModelManagerUiState> =
    MutableStateFlow(createEmptyUiState())
  internal val mutableUiState: MutableStateFlow<ModelManagerUiState>
    get() = _uiState

  open val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

  private val downloadPlanner =
    ModelDownloadPlanner(
      context = context,
      storageManager = storageManager,
      networkStateProvider = networkStateProvider,
      uiStateProvider = { _uiState.value },
      availableStorageBytesProvider = { availableStorageBytesProvider() },
    )

  internal val _audioConfigOnboardingDismissedInSession = MutableStateFlow(false)
  /** Whether the Audio Config onboarding tooltip has been dismissed during this app session. */
  val audioConfigOnboardingDismissedInSession: StateFlow<Boolean> =
    _audioConfigOnboardingDismissedInSession.asStateFlow()

  internal val _audioConfigOnboardingPromoViewed = MutableStateFlow<Boolean?>(null)
  /**
   * Whether the persistent Audio Config onboarding promo ID has been marked as viewed in
   * [DataStoreRepository], or `null` while the initial read on [ioDispatcher] is pending.
   */
  val audioConfigOnboardingPromoViewed: StateFlow<Boolean?> =
    _audioConfigOnboardingPromoViewed.asStateFlow()

  internal val _ttsSettingsUiState = MutableStateFlow(TtsSettingsUiState())
  open val ttsSettingsUiState: StateFlow<TtsSettingsUiState> = _ttsSettingsUiState.asStateFlow()

  internal val voicePreviewController =
    VoicePreviewController(
      context = context,
      scope = viewModelScope,
      ioDispatcher = ioDispatcher,
      ttsSettingsRepository = ttsSettingsRepository,
      speechSelectionUseCase = speechSelectionUseCase,
      uiStateProvider = { _uiState.value },
      ttsSettingsUiStateProvider = { ttsSettingsUiState.value },
      onRefreshTtsSettingsUiState = ::refreshTtsSettingsUiState,
      onRefreshLanguagePackStatuses = ::refreshLanguagePackStatuses,
      onRefreshSupplementaryLanguagePack = ::refreshSupplementaryLanguagePack,
    )
  open val voicePreviewUiState: StateFlow<VoicePreviewUiState>
    get() = voicePreviewController.uiState

  @VisibleForTesting
  internal var voicePreviewPlaybackHeadProvider: (AudioTrack) -> Long
    get() = voicePreviewController.playbackHeadProvider
    set(value) {
      voicePreviewController.playbackHeadProvider = value
    }

  init {
    observeTtsSettingsUiState()
  }

  open fun fetchModelDetails(modelId: String, onResult: (HfModelItemProto?) -> Unit) {
    viewModelScope.launch {
      try {
        val token = getTokenStatusAndData().data?.accessToken
        val details = huggingFaceApiClient.getModelDetails(modelId, accessToken = token)
        onResult(details)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch model details for $modelId", e)
        onResult(null)
      }
    }
  }

  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  protected val _supplementaryModels: MutableList<Model> = CopyOnWriteArrayList()
  internal val mutableSupplementaryModels: MutableList<Model>
    get() = _supplementaryModels

  internal val downloaderMap: Map<RuntimeType, @JvmSuppressWildcards ModelDownloader>
    get() = downloaders

  // Tracks the initialized backends for each model by model name.
  private val initializedBackends = ConcurrentHashMap<String, MutableSet<Accelerator>>()

  // Models whose download the user started from a download button, mapped to whether that download
  // retries right after a Hugging Face sign-in. Kept here rather than in the button, which can be
  // recomposed or replaced (e.g. by AnimatedContent) while the download runs.
  private val userStartedDownloads = ConcurrentHashMap<String, Boolean>()

  /** Work on a model's files that runs on [ioDispatcher] before other work on them can start. */
  private class PendingFileWork(
    val job: Job,
    /**
     * Whether the work deletes the files of a cancelled download, which later work waits for, or
     * else starts a download, which later work cancels.
     */
    val isDeletion: Boolean,
  )

  // The work on each model's files that hasn't ended, by model name (see [launchFileWork]). Only
  // used on the main thread.
  private val pendingFileWork = mutableMapOf<String, PendingFileWork>()

  fun isFirstInitialization(model: Model): Boolean {
    val backend = getModelCurrentAccelerator(model)
    return initializedBackends[model.name]?.contains(backend) != true
  }

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  open fun isDownloadOptionalComponentsEnabled(modelName: String): Boolean {
    return _uiState.value.isDownloadOptionalComponentsEnabled(modelName)
  }

  open fun setDownloadOptionalComponents(modelName: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val newMap = currentState.downloadOptionalComponents.toMutableMap()
      newMap[modelName] = enabled
      currentState.copy(downloadOptionalComponents = newMap)
    }
  }

  public override fun onCleared() {
    voicePreviewController.onCleared()
    authService.dispose()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { it.id in ids }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  /** Whether the current device's memory is lower than [model]'s minimum required memory. */
  open fun isModelMemoryLow(model: Model): Boolean {
    return isMemoryLow(context = context, model = model)
  }

  open fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return uiState.value.supplementaryModels.find { it.name == name }
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  open fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() {
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      val mainModelFilter =
        customTask.modelSelectionConfig
          ?.sections
          ?.firstOrNull { it.target == ModelSelectionTarget.MAIN_MODEL }
          ?.modelFilter
      if (mainModelFilter != null) {
        task.models.retainAll { it.downloadInfo.imported || mainModelFilter(it) }
      }
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { task.id in it.bestForTaskIds }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  /**
   * Remembers [model] as the main model last launched from the model selection screen of [task], so
   * that the screen preselects it next time.
   */
  fun saveLastMainModel(task: Task, model: Model) {
    _uiState.update {
      it.copy(lastMainModelByTaskId = it.lastMainModelByTaskId + (task.id to model.name))
    }
    viewModelScope.launch {
      try {
        dataStoreRepository.saveLastMainModel(taskId = task.id, modelName = model.name)
      } catch (e: IOException) {
        Log.w(TAG, "Failed to save the last main model of '${task.id}'", e)
      }
    }
  }

  /**
   * Reads the main model last launched for each task from the settings, or none if the read fails,
   * so that a settings error doesn't fail loading the model list.
   */
  private suspend fun readSavedLastMainModels(): Map<String, String> {
    // `null` if the read fails, in which case no model is restored.
    val saved: Map<String, String>? =
      try {
        dataStoreRepository.readLastMainModels()
      } catch (e: IOException) {
        Log.w(TAG, "Failed to read the last main models", e)
        null
      }
    return saved ?: emptyMap()
  }

  @MainThread
  open fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean = isDownloadOptionalComponentsEnabled(model.name),
  ) {
    // An earlier download of this model may still be deleting its stale files, or a cancelled one
    // its files. This download picks its files once that ended, as that reads the files on disk.
    if (model.name in pendingFileWork) {
      val waitingStatus = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS)
      setDownloadStatus(curModel = model, status = waitingStatus)
      if (model.isSupplementary) {
        publishSupplementaryDownloadStatus(model, waitingStatus)
      }
      launchFileWork(model.name, isDeletion = false) {
        pendingFileWork.remove(model.name)
        downloadModel(task = task, model = model, includeExtraDataFiles = includeExtraDataFiles)
      }
      return
    }

    val plan = downloadPlanner.modelDownloadPlan(task, model, includeExtraDataFiles)
    val resumesPartialDownload = plan.resumesPartialDownload
    val actualIncludeExtraDataFiles = plan.includeExtraDataFiles
    val downloadSize = plan.downloadSize
    val requiredStorageBytes = downloadSize::requiredStorageBytes
    val initialRequiredBytes = plan.initialRequiredBytes()

    // Fail right away, rather than queue a download that can't start, without a connection. A
    // download with bytes on disk stays paused, so the user can resume it once connected. When the
    // files don't fit either, the connection error shows: it's the one to fix first.
    if (model.downloadsViaRepository && !networkStateProvider.isConnected()) {
      Log.w(TAG, "Not downloading '${model.name}': no internet connection")
      val noConnectionStatus =
        downloadPlanner.noConnectionStatus(
          totalBytes = downloadSize.totalBytes,
          // Files on disk that the next download wouldn't resume don't count as progress.
          remainingBytes = plan.remainingBytes(),
        )
      setDownloadStatus(curModel = model, status = noConnectionStatus)
      if (model.isSupplementary) {
        publishSupplementaryDownloadStatus(model, noConnectionStatus)
      }
      return
    }

    // Fail right away, rather than partway through, if the files don't fit on the device.
    if (model.downloadsViaRepository) {
      val shortfallStatus =
        downloadPlanner.storageShortfallStatus(
          requiredBytes = initialRequiredBytes,
          excludedModelNames = setOf(model.name),
        )
      if (shortfallStatus != null) {
        setDownloadStatus(curModel = model, status = shortfallStatus)
        if (model.isSupplementary) {
          publishSupplementaryDownloadStatus(model, shortfallStatus)
        }
        return
      }
    }

    // A download that doesn't resume deletes the stale files of the model's version first.
    val cleansUpStaleFiles = model.downloadsViaRepository && !resumesPartialDownload

    // Update status. The sizes let the storage check of another download leave room for this one.
    val inProgressStatus =
      if (model.downloadsViaRepository) {
        ModelDownloadStatus(
          status = ModelDownloadStatusType.IN_PROGRESS,
          totalBytes = downloadSize.totalBytes,
          // The files of a download that doesn't resume are deleted before it starts.
          receivedBytes =
            if (cleansUpStaleFiles) 0L else downloadSize.totalBytes - downloadSize.remainingBytes(),
        )
      } else {
        ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS)
      }
    setDownloadStatus(curModel = model, status = inProgressStatus)
    if (model.isSupplementary) {
      publishSupplementaryDownloadStatus(model, inProgressStatus)
    }

    if (!cleansUpStaleFiles) {
      startDownload(
        task = task,
        model = model,
        includeExtraDataFiles = actualIncludeExtraDataFiles,
        requiredStorageBytes = requiredStorageBytes,
      )
      return
    }
    // Deleting the files of a big model takes a while, so it runs off the main thread. The download
    // writes the same files, so it starts only once they're deleted, unless it's cancelled before.
    launchFileWork(model.name, isDeletion = false) {
      withContext(ioDispatcher) {
        storageManager.cleanupStaleCurrentVersionFiles(
          model = model,
          taskId = task?.id,
          cleanExtraDataFiles = actualIncludeExtraDataFiles,
        )
      }
      startDownload(
        task = task,
        model = model,
        includeExtraDataFiles = actualIncludeExtraDataFiles,
        requiredStorageBytes = requiredStorageBytes,
      )
    }
  }

  /**
   * Runs [work] on the main thread, as the pending work on [modelName]'s files (see
   * [pendingFileWork]) until it ends, or a later call cancels it, unless [isDeletion].
   *
   * [work] only starts once the earlier pending work of [modelName], if any, ended, so that the two
   * never touch the model's files at the same time: an earlier download waiting to start is
   * cancelled, while an earlier deletion is waited for, as a deletion running on [ioDispatcher]
   * can't be stopped halfway. Even when cancelled, the work ends only once the earlier one ended,
   * so that the work queued after it never overlaps the earlier one either.
   */
  @MainThread
  private fun launchFileWork(
    modelName: String,
    isDeletion: Boolean,
    work: suspend CoroutineScope.() -> Unit,
  ) {
    val previous = pendingFileWork.remove(modelName)
    val job =
      viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
          if (previous != null) {
            if (!previous.isDeletion) {
              previous.job.cancel()
            }
            withContext(NonCancellable) { previous.job.join() }
          }
          ensureActive()
          work()
        } finally {
          if (pendingFileWork[modelName]?.job === coroutineContext.job) {
            pendingFileWork.remove(modelName)
          }
        }
      }
    pendingFileWork[modelName] = PendingFileWork(job = job, isDeletion = isDeletion)
    job.start()
  }

  /**
   * Starts the download of [model] for [task] with [model]'s downloader, publishing the statuses it
   * reports.
   *
   * @param requiredStorageBytes Returns how much free storage the download still needs.
   */
  @MainThread
  private fun startDownload(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    requiredStorageBytes: () -> Long,
  ) {
    val downloader =
      downloaders[model.backendSpec.runtimeType] ?: downloaders[RuntimeType.LITERT_LM]
    downloader?.download(
      task = task,
      model = model,
      includeExtraDataFiles = includeExtraDataFiles,
      onStatusUpdated = { curModel, reportedStatus ->
        val status =
          downloadPlanner.withStorageShortfall(
            reportedStatus,
            requiredBytes = requiredStorageBytes,
            excludedModelNames = setOf(model.name),
          )
        setDownloadStatus(curModel = curModel, status = status)
        if (curModel.isSupplementary) {
          publishSupplementaryDownloadStatus(curModel, status)
        }
      },
    )
  }

  /**
   * Returns the message to show when downloads can't start because the device isn't connected to
   * the internet, or `null` if it is.
   */
  @MainThread open fun noConnectionMessage(): String? = downloadPlanner.noConnectionMessage()

  open fun cancelDownloadModel(model: Model) {
    val downloader =
      downloaders[model.backendSpec.runtimeType] ?: downloaders[RuntimeType.LITERT_LM]
    // The download keeps running, so leave its status alone rather than report it cancelled.
    if (downloader?.supportsCancel == false) {
      return
    }
    downloader?.cancel(model)
    // The status set below forgets that the user started the download.
    if (model.downloadsViaRepository) {
      discardCancelledDownload(model)
    } else {
      setDownloadStatus(
        curModel = model,
        status = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
      )
    }
  }

  /**
   * Deletes the files that the cancelled download of [model] wrote, on [ioDispatcher], and then
   * updates its status from the files left on disk. A download of [model] that waits to start (see
   * [launchFileWork]) never starts, and a later one waits for the files to be deleted.
   *
   * Only the files of the version being downloaded are deleted: other installed versions of the
   * model, e.g. the one being updated, and their extra data files stay. The model's extra data
   * files stay as well while an extra data download of its family writes them, or if they were
   * downloaded before, in which case the download didn't include them.
   */
  @MainThread
  private fun discardCancelledDownload(model: Model) {
    val keepsExtraDataFiles = downloadPlanner.keepsExtraDataFilesOnCancel(model)
    // The download no longer runs. Its status is read from disk once its files are deleted.
    publishDownloadStatus(
      model,
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
    )
    launchFileWork(model.name, isDeletion = true) {
      withContext(ioDispatcher) {
        storageManager.cleanupStaleCurrentVersionFiles(
          model = model,
          taskId = null,
          cleanExtraDataFiles = !keepsExtraDataFiles,
        )
      }
      publishDownloadStatus(model, getModelDownloadStatus(model))
    }
  }

  /** Sets the download status of [model], and tells the supplementary models about it if needed. */
  @MainThread
  private fun publishDownloadStatus(model: Model, status: ModelDownloadStatus) {
    setDownloadStatus(curModel = model, status = status)
    if (model.isSupplementary) {
      publishSupplementaryDownloadStatus(model, status)
    }
  }

  /**
   * Deletes the partial files that the paused download of [model] left, so that its next download
   * starts over, and updates its status from the files left on disk.
   *
   * Only the partial `.tmp` files of the version being downloaded are deleted: the files of other
   * installed versions of the model stay. The partial files of the model's extra data files are
   * kept while an extra data download of its family writes them. Does nothing unless the model's
   * download is paused.
   */
  @MainThread
  open fun discardPartialDownload(model: Model) {
    val status = uiState.value.modelDownloadStatus[model.name]?.status
    if (!model.downloadsViaRepository || status != ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
      Log.w(TAG, "Not discarding '${model.name}', whose download isn't paused: $status")
      return
    }
    downloadRepository.cancelDownloadModel(model)
    storageManager.discardPartialFiles(
      model = model,
      isExtraDataDownloading = downloadPlanner.isExtraDataDownloading(model),
    )
    val newStatus = getModelDownloadStatus(model)
    setDownloadStatus(curModel = model, status = newStatus)
    if (model.isSupplementary) {
      publishSupplementaryDownloadStatus(model, newStatus)
    }
  }

  /**
   * Asks [model]'s downloader for its current status, publishes it in [uiState], and returns it.
   *
   * Returns `null`, leaving the status unchanged, when no downloader is registered for the model's
   * runtime.
   */
  open suspend fun refreshModelStatus(model: Model): ModelDownloadStatus? {
    val downloader = downloaders[model.backendSpec.runtimeType] ?: return null
    val status =
      try {
          downloader.queryStatus(model)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to query the status of model '${model.name}'", e)
          ModelDownloadStatus(
            status = ModelDownloadStatusType.FAILED,
            errorMessage = e.localizedMessage.orEmpty(),
          )
        }
        .keepingPauseReason(previous = uiState.value.modelDownloadStatus[model.name])
    setDownloadStatus(curModel = model, status = status)
    return status
  }

  /**
   * Resumes the download of [model] that an earlier app process left partially downloaded, unless
   * the rest of its files doesn't fit in the free storage.
   *
   * Unlike [downloadModel], it keeps all the model's files: the download continues from them.
   */
  private fun resumePartialDownload(task: Task?, model: Model, includeExtraDataFiles: Boolean) {
    val downloadSize =
      storageManager.repositoryDownloadSize(
        model,
        task?.id,
        includeExtraDataFiles = includeExtraDataFiles,
      )
    val requiredStorageBytes = downloadSize::requiredStorageBytes
    val publishStatus = { status: ModelDownloadStatus ->
      setDownloadStatus(curModel = model, status = status)
      if (model.isSupplementary) {
        publishSupplementaryDownloadStatus(model, status)
      }
    }
    val shortfallStatus =
      downloadPlanner.storageShortfallStatus(
        requiredBytes = requiredStorageBytes(),
        excludedModelNames = setOf(model.name),
      )
    if (shortfallStatus != null) {
      // The partial files stay, so the user can resume the download after freeing up storage.
      publishStatus(shortfallStatus)
      return
    }
    publishStatus(
      ModelDownloadStatus(
        status = ModelDownloadStatusType.IN_PROGRESS,
        totalBytes = downloadSize.totalBytes,
        receivedBytes = downloadSize.totalBytes - downloadSize.remainingBytes(),
      )
    )
    downloadRepository.downloadModel(
      task = task,
      model = model,
      includeExtraDataFiles = includeExtraDataFiles,
      onStatusUpdated = { _, status ->
        publishStatus(
          downloadPlanner.withStorageShortfall(
            status,
            requiredBytes = requiredStorageBytes,
            excludedModelNames = setOf(model.name),
          )
        )
      },
    )
  }

  /**
   * Returns the message to show if the models in [downloads], as (task, model) pairs, don't all fit
   * in the free storage, or `null` if they fit or the free storage is unknown. Each model is sized
   * as [downloadModel] would download it, with the extra data files the user enabled, and the bytes
   * that other running downloads still have to write are kept free for them.
   *
   * Only models downloaded by the app are counted, and only batches of at least two of them are
   * checked: [downloadModel] checks a single model itself and shows the shortfall on its card.
   */
  @MainThread
  fun batchDownloadStorageShortfallMessage(downloads: List<Pair<Task?, Model>>): String? =
    downloadPlanner.batchDownloadStorageShortfallMessage(downloads)

  /**
   * Returns how many bytes of [downloads], as (task, model) pairs, the user must agree to download
   * over mobile data first, or `null` if no confirmation is needed: the device isn't on mobile
   * data, nothing is left to download, or the device isn't connected or the files don't fit in the
   * free storage, in which case downloading shows that error instead without downloading anything.
   *
   * Each model is sized as [downloadModel] downloads it, without the bytes already on disk, and
   * checked for storage as [downloadModel] checks it, so that a download never starts over mobile
   * data without asking. Several models are checked together, as
   * [batchDownloadStorageShortfallMessage] checks them. Only models downloaded by the app count:
   * system services download the other ones themselves.
   */
  @MainThread
  open fun mobileDataDownloadBytes(downloads: List<Pair<Task?, Model>>): Long? =
    downloadPlanner.mobileDataDownloadBytes(downloads)

  /**
   * Calls [onConfirmed] right away if [downloadBytes] is `null`. Otherwise asks the user to agree
   * to download [downloadBytes] over mobile data (see [mobileDataDownloadConfirmation]), and calls
   * [onConfirmed] if they do or [onCancelled] if they don't. A newer request, even one that needs
   * no confirmation, replaces a pending one, which is cancelled.
   */
  @MainThread
  open fun confirmMobileDataDownload(
    downloadBytes: Long?,
    onConfirmed: () -> Unit,
    onCancelled: () -> Unit = {},
  ) {
    val pending = _mobileDataDownloadConfirmation.value
    if (downloadBytes == null) {
      _mobileDataDownloadConfirmation.value = null
      pending?.onCancelled?.invoke()
      onConfirmed()
      return
    }
    _mobileDataDownloadConfirmation.value =
      MobileDataDownloadConfirmation(
        downloadBytes = downloadBytes,
        onConfirmed = onConfirmed,
        onCancelled = onCancelled,
      )
    pending?.onCancelled?.invoke()
  }

  /** Called when the user agrees to download over mobile data. */
  @MainThread
  fun onMobileDataDownloadConfirmed() {
    val confirmation = _mobileDataDownloadConfirmation.value ?: return
    _mobileDataDownloadConfirmation.value = null
    confirmation.onConfirmed()
  }

  /** Called when the user doesn't agree to download over mobile data. */
  @MainThread
  fun onMobileDataDownloadCancelled() {
    val confirmation = _mobileDataDownloadConfirmation.value ?: return
    _mobileDataDownloadConfirmation.value = null
    confirmation.onCancelled()
  }

  /**
   * Downloads [model] for [task] (see [downloadModel]), once the user agrees to use mobile data if
   * the device is on it, and then calls [onStarted]. If the user doesn't agree, nothing starts, the
   * download is no longer recorded as started by the user (see [forgetUserStartedDownload]), and
   * [onCancelled] is called. [includeExtraDataFiles] is passed on to [downloadModel].
   */
  @MainThread
  open fun downloadModelAfterMobileDataCheck(
    task: Task?,
    model: Model,
    onStarted: () -> Unit = {},
    onCancelled: () -> Unit = {},
    includeExtraDataFiles: Boolean = isDownloadOptionalComponentsEnabled(model.name),
  ) {
    val downloads = mutableListOf<Pair<Task?, Model>>(task to model)
    confirmMobileDataDownload(
      downloadBytes = mobileDataDownloadBytes(downloads),
      onConfirmed = {
        downloadModel(task = task, model = model, includeExtraDataFiles = includeExtraDataFiles)
        onStarted()
      },
      onCancelled = {
        forgetUserStartedDownload(model)
        onCancelled()
      },
    )
  }

  /**
   * Downloads the extra data files of [model]'s family (see [downloadExtraDataFiles]), once the
   * user agrees to use mobile data if the device is on it.
   */
  @MainThread
  open fun downloadExtraDataFilesAfterMobileDataCheck(
    task: Task?,
    model: Model,
    modelVariants: List<Model> = emptyList(),
  ) {
    confirmMobileDataDownload(
      downloadBytes = downloadPlanner.extraDataMobileDataDownloadBytes(task, model, modelVariants),
      onConfirmed = {
        downloadExtraDataFiles(task = task, model = model, modelVariants = modelVariants)
      },
    )
  }

  fun getModelFamily(model: Model, modelVariants: List<Model> = emptyList()): List<Model> =
    downloadPlanner.getModelFamily(model, modelVariants)

  fun syncExtraDataAcrossFamily(model: Model, modelVariants: List<Model> = emptyList()) {
    val family = getModelFamily(model, modelVariants)
    val sourceModel = family.firstOrNull { storageManager.isExtraDataPresentOnDisk(it) } ?: return
    for (targetVariant in family) {
      if (
        targetVariant.name != sourceModel.name &&
          (uiState.value.modelDownloadStatus[targetVariant.name]?.status ==
            ModelDownloadStatusType.SUCCEEDED || targetVariant.name == model.name)
      ) {
        storageManager.copyExtraDataFiles(sourceModel = sourceModel, targetVariant = targetVariant)
      }
    }
  }

  fun setExtraDataDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    _uiState.update { currentState ->
      val curStatus = currentState.extraDataDownloadStatus.toMutableMap()
      curStatus[curModel.name] = status
      currentState.copy(extraDataDownloadStatus = curStatus)
    }
  }

  @MainThread
  open fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    modelVariants: List<Model> = emptyList(),
  ) {
    for (m in getModelFamily(model, modelVariants)) {
      setDownloadOptionalComponents(m.name, true)
    }
    val plan = downloadPlanner.extraDataDownloadPlan(task, model, modelVariants) ?: return
    val family = plan.family
    val targetModel = plan.targetModel
    val totalBytes = plan.totalBytes
    val requiredBytes = plan.requiredBytes
    val familyNames = plan.familyNames
    // Fail right away without a connection, like [downloadModel].
    if (!networkStateProvider.isConnected()) {
      Log.w(TAG, "Not downloading the extra data of '${model.name}': no internet connection")
      val noConnectionStatus =
        downloadPlanner.noConnectionStatus(
          totalBytes = totalBytes,
          remainingBytes = plan.remainingBytes(),
        )
      for (m in family) {
        setExtraDataDownloadStatus(curModel = m, status = noConnectionStatus)
      }
      return
    }
    val shortfallStatus =
      downloadPlanner.storageShortfallStatus(
        requiredBytes = requiredBytes(),
        excludedExtraDataModelNames = familyNames,
      )
    if (shortfallStatus != null) {
      for (m in family) {
        setExtraDataDownloadStatus(curModel = m, status = shortfallStatus)
      }
      return
    }
    val initialStatus =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.IN_PROGRESS,
        receivedBytes = 0L,
        totalBytes = totalBytes,
      )
    for (m in family) {
      setExtraDataDownloadStatus(curModel = m, status = initialStatus)
    }
    val updateExtraDataStatus = extraDataStatusUpdater(targetModel = targetModel, family = family)
    downloadRepository.downloadExtraDataFiles(
      task = task,
      model = targetModel,
      onStatusUpdated = { curModel, status ->
        updateExtraDataStatus(
          curModel,
          downloadPlanner.withStorageShortfall(
            status,
            requiredBytes = requiredBytes,
            excludedExtraDataModelNames = familyNames,
          ),
        )
      },
    )
  }

  /**
   * Returns the callback for the extra data download of [targetModel], which applies its status to
   * the whole model [family].
   */
  private fun extraDataStatusUpdater(
    targetModel: Model,
    family: List<Model>,
  ): (Model, ModelDownloadStatus) -> Unit = { _, status ->
    if (status.status == ModelDownloadStatusType.SUCCEEDED) {
      syncExtraDataAcrossFamily(targetModel, family)
    }
    for (m in family) {
      setExtraDataDownloadStatus(m, status)
    }
  }

  fun cancelDownloadExtraDataFiles(model: Model, modelVariants: List<Model> = emptyList()) {
    val family = getModelFamily(model, modelVariants)
    for (m in family) {
      downloadRepository.cancelDownloadExtraDataFiles(m)
      storageManager.cleanupCancelledExtraDataTmpFiles(m)
      setExtraDataDownloadStatus(
        curModel = m,
        status = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
      )
    }
  }

  open fun deleteExtraDataFiles(
    model: Model,
    task: Task? = null,
    modelVariants: List<Model> = emptyList(),
    onComplete: (() -> Unit)? = null,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      deleteExtraDataFilesInternal(model, task, modelVariants)
      withContext(Dispatchers.Main) { onComplete?.invoke() }
    }
  }

  internal fun deleteExtraDataFilesInternal(
    model: Model,
    task: Task? = null,
    modelVariants: List<Model> = emptyList(),
  ) {
    val family = getModelFamily(model, modelVariants)
    for (m in family) {
      for (curTask in uiState.value.tasks) {
        if (task == null || curTask.id == task.id) {
          if (curTask.models.any { it.name == m.name }) {
            val customTask = getCustomTaskByTaskId(id = curTask.id)
            customTask?.onDeleteExtraDataFn(context = context, model = m)
          }
        }
      }

      storageManager.deleteExtraDataFilesOnDisk(m, task?.id)

      setDownloadOptionalComponents(m.name, false)
      setExtraDataDownloadStatus(
        curModel = m,
        status = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
      )
    }
  }

  fun deleteModel(
    model: Model,
    removeImportedFromModelList: Boolean = true,
    preserveOptionalComponentsState: Boolean = false,
  ) {
    for (curTask in uiState.value.tasks) {
      if (curTask.models.any { it.name == model.name }) {
        val customTask = getCustomTaskByTaskId(id = curTask.id)
        customTask?.onDeleteModelFn(context = context, model = model)
      }
    }

    val family = getModelFamily(model)
    val remainingDownloadedVariants = family.filter {
      it.name != model.name &&
        uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }

    if (remainingDownloadedVariants.isNotEmpty() && !model.downloadInfo.imported) {
      storageManager.copyExtraDataFiles(
        sourceModel = model,
        targetVariant = remainingDownloadedVariants.first(),
      )
    }

    if (model.downloadsViaRepository) {
      if (model.downloadInfo.imported) {
        storageManager.deleteFilesFromImportDir(model.downloadInfo.downloadFileName)
      } else {
        storageManager.deleteDirFromModelsDir(model.normalizedName)
      }
    } else {
      val downloader = downloaders[model.backendSpec.runtimeType]
      if (downloader != null) {
        viewModelScope.launch {
          downloader.delete(model)
          setDownloadStatus(curModel = model, status = downloader.queryStatus(model))
        }
      }
    }

    initializedBackends.remove(model.name)

    // Delete model from the list if model is imported as a local model and
    // removeImportedFromModelList is
    // true.
    if (model.downloadInfo.imported && removeImportedFromModelList) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }

      // Update data store.
      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
      if (importedModelIndex >= 0) {
        importedModels.removeAt(importedModelIndex)
      }
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
    _uiState.update { currentState ->
      val curModelDownloadStatus = currentState.modelDownloadStatus.toMutableMap()
      if (model.downloadInfo.imported && removeImportedFromModelList) {
        curModelDownloadStatus.remove(model.name)
      } else {
        curModelDownloadStatus[model.name] =
          ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
      }
      val updatedDownloadOptionalComponents = currentState.downloadOptionalComponents.toMutableMap()
      val curExtraDownloadStatus = currentState.extraDataDownloadStatus.toMutableMap()
      if (!preserveOptionalComponentsState) {
        if (remainingDownloadedVariants.isEmpty()) {
          for (m in family) {
            updatedDownloadOptionalComponents.remove(m.name)
            curExtraDownloadStatus.remove(m.name)
          }
        } else {
          updatedDownloadOptionalComponents.remove(model.name)
          curExtraDownloadStatus.remove(model.name)
        }
      }
      currentState.copy(
        modelDownloadStatus = curModelDownloadStatus,
        downloadOptionalComponents = updatedDownloadOptionalComponents,
        extraDataDownloadStatus = curExtraDownloadStatus,
        tasks = currentState.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
    cleanupDeletedSpeechModel(model)
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    viewModelScope.launch {
      // Skip if initialized already.
      if (!force && model.initStatusFlow.value is Model.InitializationStatus.Initialized) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        onDone()
        return@launch
      }

      // Skip if initialization is in progress.
      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      // Clean up.
      cleanupModel(context = context, task = task, model = model)

      // Start initialization.
      Log.d(TAG, "Initializing model '${model.name}'...")
      model.markInitializationStarted()

      val onDoneFn: (error: String) -> Unit = { error ->
        if (model.instance != null) {
          Log.d(TAG, "Model '${model.name}' initialized successfully")
          val backend = getModelCurrentAccelerator(model)
          initializedBackends.getOrPut(model.name) { ConcurrentHashMap.newKeySet() }.add(backend)

          if (model.cleanUpAfterInit) {
            model.markInitializationFailed(
              IllegalStateException("Model cleaned up after initialization")
            )
            Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          } else {
            model.markInitialized()
          }
          onDone()
        } else if (error.isNotEmpty()) {
          model.markInitializationFailed(error)
          Log.d(TAG, "Model '${model.name}' failed to initialize")
          onError(error)
        } else {
          model.markInitialized(null)
        }
      }

      // Call the model initialization function.
      val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      withContext(Dispatchers.IO) {
        getCustomTaskByTaskId(id = task.id)
          ?.initializeModelFn(
            context = context,
            coroutineScope = viewModelScope,
            model = model,
            systemInstruction = Contents.of(systemPrompt),
            onDone = onDoneFn,
          )
      }
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val onDoneFn: () -> Unit = {
        model.resetInitialization()
        Log.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      getCustomTaskByTaskId(id = task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDoneFn,
        )
    } else {
      // When model is being initialized and we are trying to clean it up at same time, we mark it
      // to clean up and it will be cleaned up after initialization is done.
      if (model.initializing) {
        Log.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
      onDone()
    }
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // A download that ended without an auth error leaves nothing to ask the user about.
    if (
      status.authError == null &&
        (status.status == ModelDownloadStatusType.SUCCEEDED ||
          status.status == ModelDownloadStatusType.FAILED ||
          status.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
          status.status == ModelDownloadStatusType.NOT_DOWNLOADED)
    ) {
      userStartedDownloads.remove(curModel.name)
    }

    // Delete downloaded file if status is failed or not_downloaded.
    if (
      curModel.downloadsViaRepository &&
        (status.status == ModelDownloadStatusType.FAILED ||
          status.status == ModelDownloadStatusType.NOT_DOWNLOADED)
    ) {
      storageManager.deleteFileFromModelsDir(curModel.downloadInfo.downloadFileName)
    }

    if (curModel.downloadsViaRepository && status.status == ModelDownloadStatusType.SUCCEEDED) {
      syncExtraDataAcrossFamily(curModel)
      val family = getModelFamily(curModel)
      if (
        (isDownloadOptionalComponentsEnabled(curModel.name) &&
          curModel.downloadInfo.extraDataFiles.isNotEmpty()) ||
          family.any { storageManager.isExtraDataPresentOnDisk(it) }
      ) {
        val succeededStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
        for (m in family) {
          setExtraDataDownloadStatus(curModel = m, status = succeededStatus)
        }
      }
    }

    var isProgressUpdate = false
    _uiState.update { currentState ->
      isProgressUpdate =
        isDownloadProgressUpdate(
          previous = currentState.modelDownloadStatus[curModel.name],
          status = status,
        )
      val curModelDownloadStatus = currentState.modelDownloadStatus.toMutableMap()
      curModelDownloadStatus[curModel.name] = status
      currentState.copy(modelDownloadStatus = curModelDownloadStatus)
    }
    // The progress of an ongoing download, reported several times a second on the main thread,
    // doesn't change the TTS settings.
    if (!isProgressUpdate) {
      refreshTtsSettingsUiState()
    }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { it.copy(textInputHistory = mutableListOf()) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  /**
   * Retrieves whether Firebase Analytics collection is currently enabled.
   *
   * @return `true` if enabled or not explicitly disabled in settings; `false` if disabled.
   */
  fun readFirebaseAnalytics(): Boolean {
    return dataStoreRepository.readFirebaseAnalytics()
  }

  /**
   * Updates the user preference for Firebase Analytics data collection right away.
   *
   * Persists the setting to on-disk DataStore
   * (`dataStoreRepository.saveFirebaseAnalytics(enabled)`) and dynamically updates the live
   * Firebase SDK state via `firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)`.
   *
   * @param enabled `true` to enable diagnostic/analytics gathering; `false` to disable.
   */
  fun saveFirebaseAnalytics(enabled: Boolean) {
    dataStoreRepository.saveFirebaseAnalytics(enabled = enabled)
    firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)
  }

  internal val _supportedAsrEngines =
    MutableStateFlow<Set<AsrEngineType>>(AsrEngineType.entries.toSet())
  /**
   * Set of [AsrEngineType] backends that can be selected now: the system recognizer and the engines
   * whose model is ready (see [SpeechSelectionUseCase.selectableAsrEngineTypes]).
   */
  val supportedAsrEngines: StateFlow<Set<AsrEngineType>> = _supportedAsrEngines.asStateFlow()

  /** ASR engine currently selected in Settings. */
  val selectedAsrEngineType: StateFlow<AsrEngineType> = asrSettingsRepository.selectedEngineType

  /** Identifier of the ASR model currently selected in Settings. */
  val selectedAsrModelId: StateFlow<String> = asrSettingsRepository.selectedModelId

  /**
   * ASR engine that actually serves the selection in Settings, or `null` before it is resolved.
   * Differs from [selectedAsrEngineType] when the selected engine can't be used and another engine
   * is used instead.
   */
  val activeAsrEngineType: StateFlow<AsrEngineType?> = asrServiceProvider.activeEngineType

  /** BCP-47 code of the ASR language currently selected in Settings. */
  val selectedAsrLanguageCode: StateFlow<String> = asrSettingsRepository.selectedLanguageCode

  /** Whether partial recognition hypotheses are emitted during live dictation. */
  val showInterimResults: StateFlow<Boolean> = asrSettingsRepository.showInterimResults

  fun addImportedLlmModel(info: ImportedModel, origin: ImportOrigin? = null) {
    Log.d(TAG, "adding imported llm model: $info")

    val importsDir = File(modelsDir, IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    // Create model.
    val model = createModelFromImportedModelInfo(info = info)

    // Remove duplicated imported model if existed across all tasks.
    for (task in uiState.value.tasks) {
      val modelIndex =
        task.models.indexOfFirst { info.fileName == it.name && it.downloadInfo.imported }
      if (modelIndex >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
        task.updateTrigger.value = System.currentTimeMillis()
      }
    }

    for (task in getTasksByIds(ids = model.getTargetTaskIdsForImportedModel())) {
      task.models.add(model)
      task.updateTrigger.value = System.currentTimeMillis()
    }

    // Add initial status and states.
    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    if (model.downloadInfo.url.isNotEmpty()) {
      modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
    } else {
      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = info.fileSize,
          totalBytes = info.fileSize,
        )
    }

    // Update ui state.
    _uiState.update {
      it.copy(
        tasks = it.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    // Add to data store.
    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)

    if (origin != null) {
      ossModelImportLogger.logImportSuccess(source = origin.source, modelId = origin.modelId)
    }
  }

  /** Logs that a model import from [origin] failed while copying into app storage. */
  fun onModelImportFailed(origin: ImportOrigin) {
    ossModelImportLogger.logImportFailure(
      source = origin.source,
      errorType = OssModelImportLogger.ErrorType.IMPORT_FAILED,
      modelId = origin.modelId,
    )
  }

  fun getTokenStatusAndData(): TokenStatusAndData {
    // Try to load token data from DataStore.
    var tokenStatus = TokenStatus.NOT_STORED
    Log.d(TAG, "Reading token data from data store...")
    val tokenData = dataStoreRepository.readAccessTokenData()

    // Token exists.
    if (tokenData != null && tokenData.accessToken.isNotEmpty()) {
      Log.d(TAG, "Token exists and loaded.")

      // Check expiration (with 5-minute buffer).
      val curTs = System.currentTimeMillis()
      val expirationTs = tokenData.expiresAtMs - 5 * 60
      Log.d(
        TAG,
        "Checking whether token has expired or not. Current ts: $curTs, expires at: $expirationTs",
      )
      if (curTs >= expirationTs) {
        Log.d(TAG, "Token expired!")
        tokenStatus = TokenStatus.EXPIRED
      } else {
        Log.d(TAG, "Token not expired.")
        tokenStatus = TokenStatus.NOT_EXPIRED
        curAccessToken = tokenData.accessToken
      }
    } else {
      Log.d(TAG, "Token doesn't exists.")
    }

    return TokenStatusAndData(status = tokenStatus, data = tokenData)
  }

  /** Returns the stored Hugging Face access token, or `null` if there's none or it has expired. */
  fun validStoredAccessToken(): String? {
    val tokenStatusAndData = getTokenStatusAndData()
    return tokenStatusAndData.data?.accessToken?.takeIf {
      tokenStatusAndData.status == TokenStatus.NOT_EXPIRED && it.isNotEmpty()
    }
  }

  /**
   * Records that the user started downloading [model] from a download button, so that if Hugging
   * Face refuses this download, the user is asked for the missing access (see
   * [takeAuthErrorToPrompt]).
   *
   * @param afterSignIn Whether this download retries right after a Hugging Face sign-in.
   */
  fun onUserStartedDownload(model: Model, afterSignIn: Boolean) {
    userStartedDownloads[model.name] = afterSignIn
  }

  /**
   * Forgets that the user started downloading [model] (see [onUserStartedDownload]), when the
   * download didn't start after all, e.g. because the user declined to use mobile data. Otherwise
   * an earlier refusal still in the model's status would ask for access later on its own.
   */
  fun forgetUserStartedDownload(model: Model) {
    userStartedDownloads.remove(model.name)
  }

  /**
   * Returns the access error to ask the user about for [model]'s refused download, or `null` if
   * there's nothing to ask.
   *
   * Only downloads recorded with [onUserStartedDownload] are asked about, and each refusal at most
   * once, even if several buttons show the model. A sign-in refusal right after signing in isn't
   * asked about again: the OAuth flow can complete without user input, so it could loop.
   */
  fun takeAuthErrorToPrompt(model: Model): DownloadAuthError? {
    val authError = uiState.value.modelDownloadStatus[model.name]?.authError ?: return null
    val afterSignIn = userStartedDownloads.remove(model.name) ?: return null
    if (authError == DownloadAuthError.NEEDS_SIGN_IN && afterSignIn) {
      Log.w(TAG, "Model '${model.name}' is still refused after signing in. Not signing in again.")
      return null
    }
    return authError
  }

  /**
   * Decides what clicking [model]'s download button does: download, with the stored Hugging Face
   * token if the model is on Hugging Face, or first ask for the access the last attempt was refused
   * for.
   *
   * The URL isn't probed before downloading: Hugging Face counts every request to a model file,
   * including HEAD, as a download. Missing access shows up as an auth error on the download itself.
   */
  fun downloadClickAction(model: Model): DownloadClickAction {
    if (!HuggingFaceApiClient.isHuggingFaceUrl(model.downloadInfo.url)) {
      return DownloadClickAction.Download(accessToken = null)
    }
    val storedToken = validStoredAccessToken()
    val authError = uiState.value.modelDownloadStatus[model.name]?.authError
    return when {
      authError == null -> DownloadClickAction.Download(accessToken = storedToken)
      // A token stored since the refused attempt (e.g. the user signed in while downloading another
      // model) may have access, so try it before asking the user to sign in again.
      authError == DownloadAuthError.NEEDS_SIGN_IN &&
        storedToken != null &&
        storedToken != model.downloadInfo.accessToken ->
        DownloadClickAction.Download(accessToken = storedToken)
      else -> DownloadClickAction.ResolveAuth(authError = authError)
    }
  }

  fun getAuthorizationRequest(): AuthorizationRequest {
    return AuthorizationRequest.Builder(
        ProjectConfig.authServiceConfig,
        ProjectConfig.clientId,
        ResponseTypeValues.CODE,
        ProjectConfig.redirectUri.toUri(),
      )
      .setScope("read-repos")
      .build()
  }

  fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) {
    val dataIntent = result.data
    if (dataIntent == null) {
      onTokenRequested(
        TokenRequestResult(
          status = TokenRequestResultType.FAILED,
          errorMessage = "Empty auth result",
        )
      )
      return
    }

    val response = AuthorizationResponse.fromIntent(dataIntent)
    val exception = AuthorizationException.fromIntent(dataIntent)

    when {
      response?.authorizationCode != null -> {
        // Authorization successful, exchange the code for tokens
        var errorMessage: String? = null
        authService.performTokenRequest(response.createTokenExchangeRequest()) {
          tokenResponse,
          tokenEx ->
          if (tokenResponse != null) {
            if (tokenResponse.accessToken == null) {
              errorMessage = "Empty access token"
            } else if (tokenResponse.refreshToken == null) {
              errorMessage = "Empty refresh token"
            } else if (tokenResponse.accessTokenExpirationTime == null) {
              errorMessage = "Empty expiration time"
            } else {
              // Token exchange successful. Store the tokens securely
              Log.d(TAG, "Token exchange successful. Storing tokens...")
              saveAccessToken(
                accessToken = tokenResponse.accessToken!!,
                refreshToken = tokenResponse.refreshToken!!,
                expiresAt = tokenResponse.accessTokenExpirationTime!!,
              )
              curAccessToken = tokenResponse.accessToken!!
              Log.d(TAG, "Token successfully saved.")
            }
          } else if (tokenEx != null) {
            errorMessage = "Token exchange failed: ${tokenEx.message}"
          } else {
            errorMessage = "Token exchange failed"
          }
          if (errorMessage == null) {
            onTokenRequested(TokenRequestResult(status = TokenRequestResultType.SUCCEEDED))
          } else {
            onTokenRequested(
              TokenRequestResult(
                status = TokenRequestResultType.FAILED,
                errorMessage = errorMessage,
              )
            )
          }
        }
      }

      exception != null -> {
        onTokenRequested(
          TokenRequestResult(
            status =
              if (exception.message == "User cancelled flow") TokenRequestResultType.USER_CANCELLED
              else TokenRequestResultType.FAILED,
            errorMessage = exception.message,
          )
        )
      }

      else -> {
        onTokenRequested(TokenRequestResult(status = TokenRequestResultType.USER_CANCELLED))
      }
    }
  }

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    dataStoreRepository.saveAccessTokenData(
      accessToken = accessToken,
      refreshToken = refreshToken,
      expiresAt = expiresAt,
    )
  }

  fun clearAccessToken() {
    dataStoreRepository.clearAccessTokenData()
  }

  private fun startAutoDownloadModels() {
    viewModelScope.launch {
      val autoDownloadModels =
        uiState.value.tasks
          .flatMap { it.models }
          .filter { it.autoDownloadsOnStartup }
          .distinctBy { it.name }

      // Proactively attempt auto-download model initialization/download upon app startup.
      for (model in autoDownloadModels) {
        downloadModel(task = null, model = model)
      }
    }
  }

  /**
   * Picks up the downloads left over from an earlier activity or app process.
   *
   * Downloads that WorkManager still has queued or running keep going, and this ViewModel only
   * attaches to their progress, so a recreated activity logs no cancel and no second start for
   * them. Partially downloaded models of tasks with no pending download are restarted, resuming
   * from their partial file. Supplementary models are only attached to.
   */
  private fun processPendingDownloads() {
    downloadRepository.getPendingDownloads { pendingDownloads ->
      viewModelScope.launch(Dispatchers.Main) {
        val tokenStatusAndData = getTokenStatusAndData()
        val plan =
          downloadPlanner.planPendingDownloads(
            pendingDownloads = pendingDownloads,
            supplementaryModels = _supplementaryModels,
          )

        // Attach to all the pending downloads first, so that they show as running, with the bytes
        // they still have to write, before any download below is resumed: the storage check of a
        // resumed download keeps room for them.
        for (observed in plan.toObserve) {
          Log.d(TAG, "Observing the pending download of '${observed.model.name}'")
          observePendingDownload(
            pendingDownload = observed.pendingDownload,
            task = observed.task,
            model = observed.model,
          )
        }

        for (resumable in
          downloadPlanner.autoResumablePartialModels(
            pendingDownloads = pendingDownloads,
            supplementaryModels = _supplementaryModels,
          )) {
          val task = resumable.task
          val model = resumable.model
          if (
            tokenStatusAndData.status == TokenStatus.NOT_EXPIRED && tokenStatusAndData.data != null
          ) {
            model.downloadInfo.accessToken = tokenStatusAndData.data.accessToken
          }
          if (task != null) {
            Log.d(TAG, "Sending a new download request for '${model.name}'")
          } else {
            Log.d(TAG, "Resuming the download of supplementary model '${model.name}'")
          }
          resumePartialDownload(
            task = task,
            model = model,
            includeExtraDataFiles = resumable.includeExtraDataFiles,
          )
        }

        // Cancel downloads of models, or versions of models, that are no longer in the allowlist.
        for (pendingDownload in plan.staleToCancel) {
          Log.d(TAG, "Cancelling the download of unknown model '${pendingDownload.modelName}'")
          downloadRepository.cancelPendingDownload(pendingDownload)
        }
      }
    }
  }

  /**
   * Marks the model or its extra data as downloading and reports the progress of [pendingDownload]
   * to this ViewModel.
   */
  private fun observePendingDownload(pendingDownload: PendingDownload, task: Task?, model: Model) {
    if (pendingDownload.isExtraDataOnly) {
      val family = getModelFamily(model)
      val inProgress =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.IN_PROGRESS,
          totalBytes = model.downloadInfo.extraDataFiles(task?.id).sumOf { it.sizeInBytes },
        )
      for (m in family) {
        setExtraDataDownloadStatus(curModel = m, status = inProgress)
      }
      downloadRepository.observePendingDownload(
        pendingDownload = pendingDownload,
        task = task,
        model = model,
        onStatusUpdated = extraDataStatusUpdater(targetModel = model, family = family),
      )
    } else {
      // Keep the bytes already downloaded until the download reports its progress.
      val inProgress =
        uiState.value.modelDownloadStatus[model.name]?.copy(
          status = ModelDownloadStatusType.IN_PROGRESS
        ) ?: ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS)
      setDownloadStatus(model, inProgress)
      downloadRepository.observePendingDownload(
        pendingDownload = pendingDownload,
        task = task,
        model = model,
        onStatusUpdated = { curModel, status ->
          setDownloadStatus(curModel = curModel, status = status)
          if (task == null) {
            publishSupplementaryDownloadStatus(curModel, status)
          }
        },
      )
    }
  }

  fun loadModelAllowlist() {
    _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }

    viewModelScope.launch(allowlistLoadingDispatcher) {
      try {
        // Clear existing allowlist models.
        _allowlistModels.clear()
        _supplementaryModels.clear()

        // Load model allowlist json.
        // Try to read the local test allowlist first.
        Log.d(TAG, "Loading test model allowlist from ${localTestAllowlistFile.absolutePath}.")
        var modelAllowlist = readModelAllowlistFromDisk(file = localTestAllowlistFile)
        val loadedFromTestAllowlist = modelAllowlist != null
        if (loadedFromTestAllowlist) {
          Log.w(TAG, "Using local test model allowlist: ${localTestAllowlistFile.absolutePath}")
        }

        if (modelAllowlist == null) {
          // Load from github.
          var version = BuildConfig.VERSION_NAME.replace(".", "_")
          val url = getAllowlistUrl(version)
          Log.d(TAG, "Loading model allowlist from internet. Url: $url")
          val data = getJsonResponse<ModelAllowlist>(url = url)
          modelAllowlist = data?.jsonObj

          if (modelAllowlist == null) {
            Log.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
            modelAllowlist = readModelAllowlistFromDisk()
          } else {
            Log.d(TAG, "Done: loading model allowlist from internet")
            saveModelAllowlistToDisk(modelAllowlistContent = data?.textContent ?: "{}")
          }
        }

        if (modelAllowlist == null) {
          _uiState.update { it.copy(loadingModelAllowlistError = "Failed to load model list") }
          return@launch
        }

        Log.d(TAG, "Allowlist: $modelAllowlist")

        val deviceRequirements = DeviceRequirementRegistry.from(modelAllowlist)
        val device = deviceProvider()

        // Convert models in the allowlist.
        Log.d(
          TAG,
          "loadModelAllowlist: Converting models. Total models: ${modelAllowlist.models.size}",
        )
        val curTasks = getActiveCustomTasks().map { it.task }
        val nameToModel = mutableMapOf<String, Model>()
        for (allowedModel in modelAllowlist.models) {
          if (allowedModel.disabled == true) {
            continue
          }

          // Hide models whose device-requirement set doesn't include this device.
          val requiredSet = allowedModel.requiredDeviceRequirementSet
          if (requiredSet != null && !deviceRequirements.isSatisfied(requiredSet, device)) {
            Log.d(
              TAG,
              "Ignoring model '${allowedModel.name}' because device '${device.model}' (SOC" +
                " '${device.soc}') is not in device-requirement set '$requiredSet'",
            )
            continue
          }

          // Ignore the allowedModel if its accelerator is only npu and this device's soc is not in
          // its socToModelFiles.
          val accelerators = allowedModel.defaultConfig?.accelerators ?: ""
          val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
          if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
            val socToModelFiles = allowedModel.socToModelFiles
            if (socToModelFiles != null && SOC !in socToModelFiles) {
              Log.d(
                TAG,
                "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
              )
              continue
            }
          }

          val model = allowedModel.toModel() ?: continue

          if (model.isSupplementary) {
            if (!shouldIncludeSupplementaryAllowlistModel(model)) {
              continue
            }
            // Whether the device can use the model is decided by its gate in the supplementary
            // speech model repository, which hides it or shows why it can't be used.
            _allowlistModels.add(model)
            _supplementaryModels.add(model)
            // Supplementary models are not bound to tasks, so skip the task registration below.
            continue
          }

          _allowlistModels.add(model)
          nameToModel.put(model.name, model)
          for (taskType in allowedModel.taskTypes.orEmpty()) {
            if (taskType == BuiltInTaskId.LLM_TEST) continue
            val task = curTasks.find { it.id == taskType }
            task?.models?.add(model)

            if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
              val newConfigs = model.configs.toMutableList()
              newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
              model.configs = newConfigs
            }
          }
        }
        registerAllowlistSpeechModels(_supplementaryModels)

        // Find models from allowlist if a task's `modelNames` field is not empty.
        Log.d(TAG, "loadModelAllowlist: Processing task modelNames")
        for (task in curTasks) {
          if (task.modelNames.isNotEmpty()) {
            for (modelName in task.modelNames) {
              val model = nameToModel[modelName]
              if (model == null) {
                Log.w(TAG, "Model '$modelName' in task '${task.label}' not found in allowlist.")
                continue
              }
              Log.d(TAG, "Adding model '$modelName' to task '${task.label}' from modelNames.")
              if (model !in task.models) {
                task.models.add(model)
              }
            }
          }
        }

        // Process all tasks.
        Log.d(TAG, "loadModelAllowlist: Processing tasks")
        processTasks()

        val savedLastMainModels = readSavedLastMainModels()

        // Update UI state.
        Log.d(TAG, "loadModelAllowlist: Updating UI state")
        _uiState.update {
          createUiState(savedLastMainModels = savedLastMainModels)
            .copy(
              loadingModelAllowlist = false,
              tasks = curTasks,
              tasksByCategory = groupTasksByCategory(),
              showLocalTestAllowlistDialog = loadedFromTestAllowlist,
            )
        }

        // Process pending downloads.
        Log.d(TAG, "loadModelAllowlist: Processing pending downloads")
        processPendingDownloads()

        // Start auto-downloads for models configured with autoDownloadsOnStartup.
        Log.d(TAG, "loadModelAllowlist: Starting auto-download models")
        startAutoDownloadModels()
        refreshSupplementaryStatuses()
        Log.d(TAG, "loadModelAllowlist: Done")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load model allowlist", e)
      }
    }
  }

  fun clearLoadModelAllowlistError() {
    val curTasks = getActiveCustomTasks().map { it.task }
    processTasks()
    _uiState.update {
      createUiState()
        .copy(
          loadingModelAllowlist = false,
          tasks = curTasks,
          loadingModelAllowlistError = "",
          tasksByCategory = groupTasksByCategory(),
        )
    }
  }

  /** Dismisses the warning shown when the model allowlist is loaded from the local test file. */
  fun dismissLocalTestAllowlistDialog() {
    _uiState.update { it.copy(showLocalTestAllowlistDialog = false) }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
    try {
      Log.d(TAG, "Saving model allowlist to disk...")
      val file = File(modelsDir, MODEL_ALLOWLIST_FILENAME)
      file.writeText(modelAllowlistContent)
      Log.d(TAG, "Done: saving model allowlist to disk.")
    } catch (e: Exception) {
      Log.e(TAG, "failed to write model allowlist to disk", e)
    }
  }

  private fun readModelAllowlistFromDisk(
    file: File = File(modelsDir, MODEL_ALLOWLIST_FILENAME)
  ): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from disk: ${file.absolutePath}")
      if (file.exists()) {
        val content = file.readText()
        Log.d(TAG, "Model allowlist content from local file: $content")

        val gson = Gson()
        return gson.fromJson(content, ModelAllowlist::class.java)
      }
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from disk", e)
      return null
    }

    return null
  }

  private fun createEmptyUiState(): ModelManagerUiState {
    return ModelManagerUiState(
      tasks = listOf(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = mapOf(),
    )
  }

  /**
   * Builds the UI state from the loaded tasks and models.
   *
   * @param savedLastMainModels The last main models read from the settings, by task id. They are
   *   merged with the ones already in the UI state.
   */
  private fun createUiState(
    savedLastMainModels: Map<String, String> = emptyMap()
  ): ModelManagerUiState {
    val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
    val extraDataDownloadStatus = _uiState.value.extraDataDownloadStatus.toMutableMap()
    val tasks: MutableMap<String, Task> = mutableMapOf()
    val checkedModelNames = mutableSetOf<String>()
    val checkedModels = mutableListOf<Model>()
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      tasks[task.id] = task
      for (model in task.models) {
        if (model.name in checkedModelNames) {
          continue
        }
        modelDownloadStatus[model.name] =
          getModelDownloadStatus(model = model)
            .keepingPauseReason(previous = _uiState.value.modelDownloadStatus[model.name])
        checkedModelNames.add(model.name)
        checkedModels.add(model)
      }
    }

    for (model in checkedModels) {
      if (
        extraDataDownloadStatus[model.name] == null &&
          getModelFamily(model).any { storageManager.isExtraDataPresentOnDisk(it) }
      ) {
        extraDataDownloadStatus[model.name] =
          ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
      }
    }

    for (suppModel in _supplementaryModels) {
      modelDownloadStatus[suppModel.name] =
        _uiState.value.modelDownloadStatus[suppModel.name]
          ?: if (suppModel.downloadsViaRepository) {
            getModelDownloadStatus(model = suppModel)
          } else {
            ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
          }
    }

    // Load imported models.
    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")

      // Create model.
      val model = createModelFromImportedModelInfo(info = importedModel)

      // Add to task.
      for (taskId in model.getTargetTaskIdsForImportedModel()) {
        tasks[taskId]?.models?.add(model)
      }

      // Update status.
      if (model.downloadInfo.url.isNotEmpty()) {
        modelDownloadStatus[model.name] =
          getModelDownloadStatus(model = model)
            .keepingPauseReason(previous = _uiState.value.modelDownloadStatus[model.name])
      } else {
        modelDownloadStatus[model.name] =
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = importedModel.fileSize,
            totalBytes = importedModel.fileSize,
          )
      }
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    Log.d(TAG, "text input history: $textInputHistory")

    // Choices saved in this session take precedence, as their writes may still be pending.
    val lastMainModelByTaskId = savedLastMainModels + _uiState.value.lastMainModelByTaskId

    Log.d(TAG, "model download status: $modelDownloadStatus")
    return ModelManagerUiState(
      tasks = getActiveCustomTasks().map { it.task }.toList(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = modelDownloadStatus,
      textInputHistory = textInputHistory,
      downloadOptionalComponents = _uiState.value.downloadOptionalComponents,
      extraDataDownloadStatus = extraDataDownloadStatus,
      supplementaryModels = _supplementaryModels.toList(),
      lastMainModelByTaskId = lastMainModelByTaskId,
    )
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU

            else -> null // Ignore unknown accelerator labels
          }
        }
        .ifEmpty { listOf(Accelerator.CPU) }
        .toMutableList()
    val llmMaxToken = info.llmConfig.defaultMaxTokens.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN
    val llmSupportImage = info.llmConfig.supportImage
    val llmSupportAudio = info.llmConfig.supportAudio
    val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
    val llmSupportMobileActions = info.llmConfig.supportMobileActions
    val llmSupportThinking = info.llmConfig.supportThinking
    val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
    val isForTestOnly = ModelUtils.isImportedUrlForTestOnly(info.url)
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = llmMaxToken,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
          supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        )
        .toMutableList()
    if (llmSupportTinyGarden && !isForTestOnly) {
      configs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
    }
    val capabilityToTaskTypes =
      ModelUtils.buildImportedModelCapabilityToTaskTypes(
        supportThinking = llmSupportThinking,
        supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        isForTestOnly = isForTestOnly,
      )
    val downloadInfo =
      ModelDownloadInfo(
        url = info.url,
        sizeInBytes = info.fileSize,
        downloadFileName = info.fileName,
        imported = true,
      )
    // We assume all imported models are LLM for now.
    val llmProfile =
      LlmProfile(
        supportTinyGarden = llmSupportTinyGarden,
        supportMobileActions = llmSupportMobileActions,
        maxTokens = llmMaxToken,
      )
    val model =
      Model(
        name = info.fileName,
        configs = configs,
        showRunAgainButton = false,
        downloadInfo = downloadInfo,
        llmProfile = llmProfile,
        supportImage = llmSupportImage,
        supportAudio = llmSupportAudio,
        capabilities = capabilityToTaskTypes.keys.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes,
        backendSpec = BackendSpec(runtimeType = RuntimeType.LITERT_LM, accelerators = accelerators),
      )
    model.preProcess()

    return model
  }

  private fun groupTasksByCategory(): Map<String, List<Task>> {
    val tasks = getActiveCustomTasks().map { it.task }.filter { it.id != BuiltInTaskId.LLM_TEST }

    val categoryMap: Map<String, CategoryInfo> =
      tasks.associateBy { it.category.id }.mapValues { it.value.category }

    val groupedTasks = tasks.groupBy { it.category.id }
    val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
    // Sort the tasks in categories by pre-defined order. Sort other tasks by label.
    for (categoryId in groupedTasks.keys) {
      val sortedTasks =
        groupedTasks[categoryId]!!.sortedWith { a, b ->
          if (categoryId == Category.LLM.id) {
            val order: List<String> =
              when (categoryId) {
                Category.LLM.id -> PREDEFINED_LLM_TASK_ORDER
                else -> listOf()
              }
            val indexA = order.indexOf(a.id)
            val indexB = order.indexOf(b.id)
            if (indexA != -1 && indexB != -1) {
              indexA.compareTo(indexB)
            } else if (indexA != -1) {
              -1
            } else if (indexB != -1) {
              1
            } else {
              val ca = categoryMap[a.id]!!
              val cb = categoryMap[b.id]!!
              val caLabel = getCategoryLabel(context = context, category = ca)
              val cbLabel = getCategoryLabel(context = context, category = cb)
              caLabel.compareTo(cbLabel)
            }
          } else {
            a.label.compareTo(b.label)
          }
        }
      for ((index, task) in sortedTasks.withIndex()) {
        task.index = index
      }
      groupedSortedTasks[categoryId] = sortedTasks
    }

    return groupedSortedTasks
  }

  private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
    val stringRes = category.labelStringRes
    val label = category.label
    if (stringRes != null) {
      return context.getString(stringRes)
    } else if (label != null) {
      return label
    }
    return context.getString(R.string.category_unlabeled)
  }

  /**
   * Retrieves the download status of a model by checking if it's fully downloaded, partially
   * downloaded, or not downloaded at all.
   */
  fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    storageManager.getModelDownloadStatus(model)

  fun isModelDownloaded(model: Model): Boolean = storageManager.isModelDownloaded(model)
}

private fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}

/**
 * Returns the current accelerator for the given model.
 *
 * If the model does not have a current accelerator, returns the default accelerator for the model's
 * backend spec, and if that is not available, returns GPU.
 */
private fun getModelCurrentAccelerator(model: Model): Accelerator {
  return model.currentAccelerator ?: Accelerator.GPU
}
