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

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelSelectionConfig
import com.google.ai.edge.gallery.data.ModelSelectionSection
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.ModelUnavailabilityReason
import com.google.ai.edge.gallery.data.SpeechEngineError
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.data.SpeechEngineKind
import com.google.ai.edge.gallery.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGTaskModelSelectionVM"
private const val KEY_INITIALIZED_TASK_ID = "initialized_task_id"
private const val KEY_SELECTION_PREFIX = "selection_"
private const val KEY_EXPANDED_SECTIONS = "expanded_sections"
private const val KEY_COLLAPSED_MODELS = "collapsed_models"
private const val KEY_SUMMARY_EXPANDED = "summary_expanded"
// How long uiState keeps its upstream after the screen stops collecting, so that configuration
// changes don't restart it.
private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * What the model selection screen needs from the app-wide model manager. [asModelSelectionHost]
 * adapts [ModelManagerViewModel]; tests use fakes.
 */
interface ModelSelectionHost {
  /** The model manager state: tasks, supplementary models and download statuses. */
  val uiState: StateFlow<ModelManagerUiState>

  /** Starts downloading [model]. See [ModelManagerViewModel.downloadModel]. */
  fun downloadModel(task: Task?, model: Model)

  /** Cancels [model]'s download, if its runtime supports it. */
  fun cancelDownloadModel(model: Model)

  /** Queries [model]'s current status from its downloader and returns it. */
  suspend fun refreshModelStatus(model: Model): ModelDownloadStatus?

  /** Whether [model] is the choice currently saved in the app settings for [target]. */
  fun isSavedSelection(target: ModelSelectionTarget, model: Model): Boolean

  /**
   * Saves [model] as the app-wide choice for [target]. No-op for [ModelSelectionTarget.MAIN_MODEL],
   * which is saved per task with [saveMainModel].
   */
  fun saveSelection(target: ModelSelectionTarget, model: Model)

  /**
   * Saves [model] as the main model launched for [task]. It is read back from
   * [ModelManagerUiState.lastMainModelByTaskId].
   */
  fun saveMainModel(task: Task, model: Model)

  /** Whether the current device's memory is lower than [model]'s minimum required memory. */
  fun isMemoryLow(model: Model): Boolean = false
}

/** Adapts this [ModelManagerViewModel] to [ModelSelectionHost]. */
fun ModelManagerViewModel.asModelSelectionHost(): ModelSelectionHost =
  ModelManagerSelectionHost(this)

private class ModelManagerSelectionHost(private val viewModel: ModelManagerViewModel) :
  ModelSelectionHost {
  override val uiState: StateFlow<ModelManagerUiState>
    get() = viewModel.uiState

  override fun downloadModel(task: Task?, model: Model) {
    viewModel.downloadModel(task = task, model = model)
  }

  override fun cancelDownloadModel(model: Model) {
    viewModel.cancelDownloadModel(model = model)
  }

  override suspend fun refreshModelStatus(model: Model): ModelDownloadStatus? =
    viewModel.refreshModelStatus(model)

  override fun isSavedSelection(target: ModelSelectionTarget, model: Model): Boolean {
    return false
  }

  override fun saveSelection(target: ModelSelectionTarget, model: Model) {
  }

  override fun saveMainModel(task: Task, model: Model) {
    viewModel.saveLastMainModel(task = task, model = model)
  }

  override fun isMemoryLow(model: Model): Boolean = viewModel.isModelMemoryLow(model)
}

/**
 * Returns the supported BCP-47 language tags for a speech candidate [model] in [target], excluding
 * Android testing pseudo-locales (`-XA`, `-XB`, `-XC` such as `ar-XA`).
 */
internal fun supportedLanguagesForCandidate(
  target: ModelSelectionTarget,
  model: Model,
): List<String> {
  return emptyList()
}

private fun isPseudoLocaleTag(languageCode: String): Boolean {
  val normalized = languageCode.trim().replace('_', '-').uppercase()
  return normalized.endsWith("-XA") || normalized.endsWith("-XB") || normalized.endsWith("-XC")
}

/** Returns the models [section] offers, in display order. */
internal fun candidatesFor(
  section: ModelSelectionSection,
  task: Task,
  modelManagerUiState: ModelManagerUiState,
): List<Model> =
  when (section.target) {
    ModelSelectionTarget.MAIN_MODEL ->
      task.models.filter { !it.downloadInfo.imported && section.modelFilter(it) }
    ModelSelectionTarget.ASR_MODEL,
    ModelSelectionTarget.TTS_MODEL ->
      modelManagerUiState.supplementaryModels.filter(section.modelFilter)
  }

/**
 * Whether [model] can be picked given its [status] and [isMemoryLow]. Models that can't run on this
 * device, models whose minimum memory requirement exceeds the device's memory, and models the
 * system downloads on startup (AICore) that failed, can't be fixed from the selection screen.
 */
internal fun isModelSelectable(
  model: Model,
  status: ModelDownloadStatus?,
  isMemoryLow: Boolean = false,
): Boolean =
  when {
    isMemoryLow -> false
    status?.status == ModelDownloadStatusType.UNAVAILABLE -> false
    status?.status == ModelDownloadStatusType.FAILED -> !model.autoDownloadsOnStartup
    else -> true
  }

/**
 * Whether the app-wide choices saved in this host for [task] make a complete selection with
 * [mainModel], so the task can start without the selection screen: [mainModel] and the saved choice
 * of every other section are offered by their sections and downloaded.
 */
fun ModelSelectionHost.isSavedModelSelectionComplete(
  task: Task,
  config: ModelSelectionConfig,
  mainModel: Model,
): Boolean {
  val modelManagerUiState = uiState.value
  fun isDownloaded(model: Model) =
    modelManagerUiState.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
  return config.sections.all { section ->
    val candidates = candidatesFor(section, task, modelManagerUiState)
    when (section.target) {
      ModelSelectionTarget.MAIN_MODEL ->
        candidates.any { it.name == mainModel.name } &&
          isDownloaded(mainModel) &&
          !isMemoryLow(mainModel)
      ModelSelectionTarget.ASR_MODEL,
      ModelSelectionTarget.TTS_MODEL ->
        candidates.any {
          isSavedSelection(section.target, it) && isDownloaded(it) && !isMemoryLow(it)
        }
    }
  }
}

/**
 * The speech engine error for a speech model of a [target] section whose status check before launch
 * reported [status], or `null` for main models.
 */
internal fun speechEngineErrorFor(
  target: ModelSelectionTarget,
  status: ModelDownloadStatus,
): SpeechEngineError? {
  val kind =
    when (target) {
      ModelSelectionTarget.MAIN_MODEL -> return null
      ModelSelectionTarget.ASR_MODEL -> SpeechEngineKind.SPEECH_RECOGNITION
      ModelSelectionTarget.TTS_MODEL -> SpeechEngineKind.VOICE
    }
  val cause =
    when (status.unavailability?.reason) {
      ModelUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING ->
        SpeechEngineErrorCause.SPEECH_SERVICES_OUTDATED
      else -> SpeechEngineErrorCause.OTHER
    }
  return SpeechEngineError(kind = kind, cause = cause)
}

/**
 * A selected speech model that wasn't ready when the task was about to launch.
 *
 * @property model The model that wasn't ready.
 * @property sectionId The section that offers [model].
 * @property error What the error popup shows.
 */
data class ModelNotReadyUiState(
  val model: Model,
  val sectionId: String,
  val error: SpeechEngineError,
)

/** A model offered by a section of the selection screen. */
data class ModelSelectionCandidateUiState(
  val model: Model,
  val downloadStatus: ModelDownloadStatus?,
  val isSelected: Boolean,
  val isSelectable: Boolean,
  val isExpanded: Boolean,
  val isMemoryLow: Boolean = false,
  val supportedLanguages: List<String> = emptyList(),
)

/** A section of the selection screen. */
data class ModelSelectionSectionUiState(
  val section: ModelSelectionSection,
  val candidates: List<ModelSelectionCandidateUiState>,
  /** The selected model, or `null` if none is selected or the selected one can't be used. */
  val selectedModel: Model?,
  val isExpanded: Boolean,
) {
  /** The number of selected models, shown in the summary sheet. */
  val selectedCount: Int
    get() = if (selectedModel == null) 0 else 1
}

/** What happens after the user taps Try it. */
enum class ModelSelectionLaunchPhase {
  /** Nothing is launching. */
  IDLE,

  /** The download checks (memory, access, notification permission) are running. */
  GATING,

  /** The selected models are downloading. */
  DOWNLOADING,

  /** The selected models are downloaded and their status is being checked before launch. */
  CHECKING,
}

/**
 * Asks the screen to run the download checks before Try it starts downloads.
 *
 * @property id Distinguishes successive requests.
 * @property memoryCheckModel The model to show the low-memory warning for, if any.
 * @property modelsToDownload The models that need downloading.
 */
data class ModelSelectionGateRequest(
  val id: Long,
  val memoryCheckModel: Model?,
  val modelsToDownload: List<Model>,
)

/** UI state of the sectioned model selection screen. */
data class TaskModelSelectionUiState(
  val sections: List<ModelSelectionSectionUiState> = emptyList(),
  val isSummaryExpanded: Boolean = false,
  /**
   * Whether Try it can be tapped: every section has its required selection and nothing launches.
   */
  val canTryIt: Boolean = false,
  val launchPhase: ModelSelectionLaunchPhase = ModelSelectionLaunchPhase.IDLE,
  /** Overall download progress in [0, 1] while downloading, or `null` when unknown. */
  val downloadProgress: Float? = null,
  val gateRequest: ModelSelectionGateRequest? = null,
  /** The section whose header the list should scroll to the top, consumed with [onScrolled]. */
  val scrollToSectionId: String? = null,
  /** The main model to launch the task with, consumed with [onLaunchHandled]. */
  val launchModel: Model? = null,
  /** The speech model that wasn't ready when the task was about to launch, if any. */
  val notReady: ModelNotReadyUiState? = null,
)

private data class SelectionState(
  /** The selected model name of each section, by section id. */
  val selections: Map<String, String> = emptyMap(),
  val expandedSectionIds: Set<String> = emptySet(),
  /** Names of the model cards the user collapsed. Every other card is expanded. */
  val collapsedModelNames: Set<String> = emptySet(),
  val isSummaryExpanded: Boolean = false,
  val launchPhase: ModelSelectionLaunchPhase = ModelSelectionLaunchPhase.IDLE,
  val gateRequest: ModelSelectionGateRequest? = null,
  /** Names of the models whose download was started by the current launch. */
  val startedDownloads: Set<String> = emptySet(),
  val scrollToSectionId: String? = null,
  val launchModel: Model? = null,
  val notReady: ModelNotReadyUiState? = null,
)

private data class Binding(
  val host: ModelSelectionHost,
  val task: Task,
  val config: ModelSelectionConfig,
)

/**
 * ViewModel of the sectioned model selection screen shown for tasks with a
 * [com.google.ai.edge.gallery.customtasks.common.CustomTask.modelSelectionConfig].
 *
 * It holds the pending selection of each section, which sections and cards are expanded, and the
 * Try it flow: download checks, concurrent downloads, a status check, then launch. Choices are
 * saved only at launch: the speech choices to the app settings, because starting or cancelling a
 * download resets the saved speech choice of that model, and the main model for the task. The
 * pending selection is kept in the [SavedStateHandle].
 *
 * Call [bind] before using it.
 */
@HiltViewModel
class TaskModelSelectionViewModel
@Inject
constructor(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val binding = MutableStateFlow<Binding?>(null)
  private val state = MutableStateFlow(restoreState())
  private var observeJob: Job? = null
  private var launchJob: Job? = null
  private var nextGateRequestId = 0L

  val uiState: StateFlow<TaskModelSelectionUiState> =
    binding
      .flatMapLatest { currentBinding ->
        if (currentBinding == null) {
          flowOf(TaskModelSelectionUiState())
        } else {
          combine(currentBinding.host.uiState, state) { modelManagerUiState, selectionState ->
            buildUiState(currentBinding, modelManagerUiState, selectionState)
          }
        }
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        TaskModelSelectionUiState(),
      )

  /**
   * Binds the screen to [task] and its [config]. On first entry it preselects the main model last
   * launched for [task] (or the "Best overall" one if that can no longer be picked) and the speech
   * models saved in the app settings (never the System defaults), and expands every section and
   * every model card.
   */
  fun bind(host: ModelSelectionHost, task: Task, config: ModelSelectionConfig) {
    val current = binding.value
    if (current != null && current.host === host && current.task.id == task.id) {
      return
    }
    val newBinding = Binding(host = host, task = task, config = config)
    val initializedTaskId: String? = savedStateHandle[KEY_INITIALIZED_TASK_ID]
    if (initializedTaskId != task.id) {
      state.value = initialState(newBinding)
      savedStateHandle[KEY_INITIALIZED_TASK_ID] = task.id
      persist(state.value)
    }
    binding.value = newBinding
    observeJob?.cancel()
    observeJob = viewModelScope.launch {
      // The selection state only triggers a check here; continueLaunch reads its live value.
      combine(host.uiState, state) { modelManagerUiState, _ -> modelManagerUiState }
        .collect { modelManagerUiState -> continueLaunch(newBinding, modelManagerUiState) }
    }
  }

  /**
   * Selects [model] in the section [sectionId], keeps that section open, and expands and scrolls to
   * the next section without a selection, or expands the summary sheet once every section has one.
   * Selecting another model while a launch is downloading cancels the replaced model's download and
   * stops the launch.
   */
  fun selectModel(sectionId: String, model: Model) {
    val currentBinding = binding.value ?: return
    val modelManagerUiState = currentBinding.host.uiState.value
    val section = currentBinding.config.sections.firstOrNull { it.id == sectionId } ?: return
    val candidates = candidatesFor(section, currentBinding.task, modelManagerUiState)
    if (
      candidates.none { it.name == model.name } ||
        !isModelSelectable(
          model = model,
          status = modelManagerUiState.modelDownloadStatus[model.name],
          isMemoryLow = currentBinding.host.isMemoryLow(model),
        )
    ) {
      return
    }
    val current = state.value
    val previousName = current.selections[sectionId]
    var startedDownloads = current.startedDownloads
    var launchPhase = current.launchPhase
    if (previousName != null && previousName != model.name) {
      if (previousName in startedDownloads) {
        val previousModel = candidates.firstOrNull { it.name == previousName }
        val previousStatus = modelManagerUiState.modelDownloadStatus[previousName]?.status
        if (previousModel != null && previousStatus != ModelDownloadStatusType.SUCCEEDED) {
          Log.d(TAG, "Cancelling the download of replaced model '$previousName'")
          currentBinding.host.cancelDownloadModel(previousModel)
        }
        startedDownloads = startedDownloads - previousName
      }
      if (launchPhase != ModelSelectionLaunchPhase.IDLE) {
        launchJob?.cancel()
        launchPhase = ModelSelectionLaunchPhase.IDLE
      }
    }
    val selections = current.selections + (sectionId to model.name)
    val sectionIds = currentBinding.config.sections.map { it.id }
    val index = sectionIds.indexOf(sectionId)
    // Look for the next section without a selection, wrapping around.
    val nextSectionId =
      (sectionIds.drop(index + 1) + sectionIds.take(index)).firstOrNull { it !in selections }
    // The answered section stays open, so the user can still compare its models.
    val expandedSectionIds = current.expandedSectionIds + listOfNotNull(nextSectionId)
    update(
      current.copy(
        selections = selections,
        expandedSectionIds = expandedSectionIds,
        isSummaryExpanded = current.isSummaryExpanded || nextSectionId == null,
        launchPhase = launchPhase,
        gateRequest =
          if (launchPhase == ModelSelectionLaunchPhase.IDLE) null else current.gateRequest,
        startedDownloads =
          if (launchPhase == ModelSelectionLaunchPhase.IDLE) emptySet() else startedDownloads,
        scrollToSectionId = nextSectionId,
      )
    )
  }

  /** Expands or collapses the section [sectionId]. */
  fun toggleSection(sectionId: String) {
    val current = state.value
    val expanded =
      if (sectionId in current.expandedSectionIds) {
        current.expandedSectionIds - sectionId
      } else {
        current.expandedSectionIds + sectionId
      }
    update(current.copy(expandedSectionIds = expanded))
  }

  /** Shows or hides the description of the card for [modelName]. */
  fun setModelExpanded(modelName: String, expanded: Boolean) {
    val current = state.value
    val names =
      if (expanded) current.collapsedModelNames - modelName
      else current.collapsedModelNames + modelName
    update(current.copy(collapsedModelNames = names))
  }

  /** Expands or collapses the summary sheet. */
  fun toggleSummaryExpanded() {
    val current = state.value
    update(current.copy(isSummaryExpanded = !current.isSummaryExpanded))
  }

  /**
   * Starts the Try it flow when every section has its selection: asks the screen to run the
   * download checks through [TaskModelSelectionUiState.gateRequest]. The low-memory warning only
   * covers the main model.
   */
  fun onTryItClicked() {
    val currentBinding = binding.value ?: return
    val uiState = buildUiState(currentBinding, currentBinding.host.uiState.value, state.value)
    if (!uiState.canTryIt) {
      return
    }
    val selectedModels = uiState.sections.mapNotNull { it.selectedModel }
    val statuses = currentBinding.host.uiState.value.modelDownloadStatus
    val mainModel =
      uiState.sections
        .firstOrNull { it.section.target == ModelSelectionTarget.MAIN_MODEL }
        ?.selectedModel
    val request =
      ModelSelectionGateRequest(
        id = nextGateRequestId++,
        memoryCheckModel = mainModel,
        modelsToDownload = selectedModels.filter { needsDownload(it, statuses[it.name]) },
      )
    update(
      state.value.copy(
        launchPhase = ModelSelectionLaunchPhase.GATING,
        gateRequest = request,
        notReady = null,
      )
    )
  }

  /**
   * Called when the download checks of [request] passed: starts the missing downloads concurrently,
   * expands the sections whose model downloads, and launches once every model is ready.
   */
  fun onGatesPassed(request: ModelSelectionGateRequest) {
    val currentBinding = binding.value ?: return
    val current = state.value
    if (current.gateRequest?.id != request.id) {
      return
    }
    val sectionByModelName =
      currentBinding.config.sections
        .mapNotNull { section -> current.selections[section.id]?.let { it to section } }
        .toMap()
    for (model in request.modelsToDownload) {
      val section = sectionByModelName[model.name]
      val task =
        if (section?.target == ModelSelectionTarget.MAIN_MODEL) currentBinding.task else null
      Log.d(TAG, "Downloading model '${model.name}'")
      currentBinding.host.downloadModel(task = task, model = model)
    }
    val downloadingSectionIds =
      request.modelsToDownload.mapNotNull { sectionByModelName[it.name]?.id }.toSet()
    update(
      state.value.copy(
        launchPhase = ModelSelectionLaunchPhase.DOWNLOADING,
        gateRequest = null,
        startedDownloads = request.modelsToDownload.map { it.name }.toSet(),
        expandedSectionIds = state.value.expandedSectionIds + downloadingSectionIds,
      )
    )
  }

  /** Called when the download checks of [request] failed or the user cancelled them. */
  fun onGatesAborted(request: ModelSelectionGateRequest) {
    val current = state.value
    if (current.gateRequest?.id != request.id) {
      return
    }
    update(current.copy(launchPhase = ModelSelectionLaunchPhase.IDLE, gateRequest = null))
  }

  /**
   * Stops the launch and cancels the downloads it started. Downloads of runtimes that can't be
   * cancelled (AICore, ML Kit) keep running.
   */
  fun cancelLaunch() {
    val currentBinding = binding.value ?: return
    val current = state.value
    launchJob?.cancel()
    val modelManagerUiState = currentBinding.host.uiState.value
    for (name in current.startedDownloads) {
      if (
        modelManagerUiState.modelDownloadStatus[name]?.status == ModelDownloadStatusType.SUCCEEDED
      ) {
        continue
      }
      val model = findModel(currentBinding, modelManagerUiState, name) ?: continue
      currentBinding.host.cancelDownloadModel(model)
    }
    update(
      current.copy(
        launchPhase = ModelSelectionLaunchPhase.IDLE,
        gateRequest = null,
        startedDownloads = emptySet(),
      )
    )
  }

  /** Called once the list scrolled to [TaskModelSelectionUiState.scrollToSectionId]. */
  fun onScrolled() {
    update(state.value.copy(scrollToSectionId = null))
  }

  /** Called once the screen navigated to the task with [TaskModelSelectionUiState.launchModel]. */
  fun onLaunchHandled() {
    update(state.value.copy(launchModel = null))
  }

  /** Dismisses the speech engine error popup of a model that wasn't ready. */
  fun dismissNotReadyModel() {
    update(state.value.copy(notReady = null))
  }

  /** Dismisses the error popup of a model that wasn't ready and runs Try it again. */
  fun retryNotReadyModel() {
    dismissNotReadyModel()
    onTryItClicked()
  }

  /**
   * Dismisses the error popup of a model that wasn't ready and expands its section so that the user
   * can choose another model.
   */
  fun chooseAnotherModelForNotReady() {
    val notReady = state.value.notReady ?: return
    update(state.value.copy(notReady = null))
    expandSectionById(notReady.sectionId)
  }

  /**
   * Expands the section that picks [target] and scrolls its header to the top, for a task that
   * sends the user back to choose another model.
   */
  fun expandSection(target: ModelSelectionTarget) {
    val currentBinding = binding.value ?: return
    val section = currentBinding.config.sections.firstOrNull { it.target == target } ?: return
    expandSectionById(section.id)
  }

  private fun expandSectionById(sectionId: String) {
    val current = state.value
    update(
      current.copy(
        expandedSectionIds = current.expandedSectionIds + sectionId,
        scrollToSectionId = sectionId,
      )
    )
  }

  /**
   * Launches once every selected model is downloaded: checks the status of the system-managed
   * speech models, saves the speech choices to the app settings and the main model for the task,
   * and asks the screen to navigate. Stops when a download fails.
   */
  private fun continueLaunch(currentBinding: Binding, modelManagerUiState: ModelManagerUiState) {
    // Reads the live state rather than the snapshot that triggered this call: a host update during
    // the launch (for example saving the choices, or a status refresh) can deliver a stale
    // DOWNLOADING snapshot after the launch moved on, which would launch again.
    val selectionState = state.value
    if (selectionState.launchPhase != ModelSelectionLaunchPhase.DOWNLOADING) {
      return
    }
    val uiState = buildUiState(currentBinding, modelManagerUiState, selectionState)
    val selected = uiState.sections.map { it.section to it.selectedModel }
    val statuses = modelManagerUiState.modelDownloadStatus
    val hasFailedModel = selected.any { (_, model) ->
      model == null ||
        statuses[model.name]?.status == ModelDownloadStatusType.FAILED ||
        statuses[model.name]?.authError != null
    }
    if (hasFailedModel) {
      // A selected model can no longer be used, for example because its download failed.
      update(
        state.value.copy(
          launchPhase = ModelSelectionLaunchPhase.IDLE,
          startedDownloads = emptySet(),
        )
      )
      return
    }
    if (selected.any { statuses[it.second?.name]?.status != ModelDownloadStatusType.SUCCEEDED }) {
      return
    }
    update(state.value.copy(launchPhase = ModelSelectionLaunchPhase.CHECKING))
    launchJob = viewModelScope.launch {
      for ((section, model) in selected) {
        if (model == null || !isSystemManagedSpeechModel(model)) {
          continue
        }
        val status = currentBinding.host.refreshModelStatus(model)
        if (status != null && status.status != ModelDownloadStatusType.SUCCEEDED) {
          Log.w(TAG, "Model '${model.name}' is not ready: ${status.status}")
          val error =
            speechEngineErrorFor(section.target, status)
              ?: SpeechEngineError(
                SpeechEngineKind.SPEECH_RECOGNITION,
                SpeechEngineErrorCause.OTHER,
              )
          update(
            state.value.copy(
              launchPhase = ModelSelectionLaunchPhase.IDLE,
              startedDownloads = emptySet(),
              notReady = ModelNotReadyUiState(model = model, sectionId = section.id, error = error),
            )
          )
          return@launch
        }
      }
      var mainModel: Model? = null
      for ((section, model) in selected) {
        if (model == null) continue
        if (section.target == ModelSelectionTarget.MAIN_MODEL) {
          mainModel = model
          currentBinding.host.saveMainModel(currentBinding.task, model)
        } else {
          currentBinding.host.saveSelection(section.target, model)
        }
      }
      update(
        state.value.copy(
          launchPhase = ModelSelectionLaunchPhase.IDLE,
          startedDownloads = emptySet(),
          launchModel = mainModel,
        )
      )
    }
  }

  private fun buildUiState(
    currentBinding: Binding,
    modelManagerUiState: ModelManagerUiState,
    selectionState: SelectionState,
  ): TaskModelSelectionUiState {
    val statuses = modelManagerUiState.modelDownloadStatus
    val sections =
      currentBinding.config.sections.map { section ->
        val selectedName = selectionState.selections[section.id]
        val candidates =
          candidatesFor(section, currentBinding.task, modelManagerUiState).map { model ->
            val status = statuses[model.name]
            val memoryLow = currentBinding.host.isMemoryLow(model)
            ModelSelectionCandidateUiState(
              model = model,
              downloadStatus = status,
              isSelected = model.name == selectedName,
              isSelectable = isModelSelectable(model, status, isMemoryLow = memoryLow),
              isExpanded = model.name !in selectionState.collapsedModelNames,
              isMemoryLow = memoryLow,
              supportedLanguages = supportedLanguagesForCandidate(section.target, model),
            )
          }
        ModelSelectionSectionUiState(
          section = section,
          candidates = candidates,
          selectedModel = candidates.firstOrNull { it.isSelected && it.isSelectable }?.model,
          isExpanded = section.id in selectionState.expandedSectionIds,
        )
      }
    val isComplete =
      sections.isNotEmpty() && sections.all { it.selectedCount >= it.section.requiredCount }
    return TaskModelSelectionUiState(
      sections = sections,
      isSummaryExpanded = selectionState.isSummaryExpanded,
      canTryIt = isComplete && selectionState.launchPhase == ModelSelectionLaunchPhase.IDLE,
      launchPhase = selectionState.launchPhase,
      downloadProgress = downloadProgress(selectionState.startedDownloads, statuses),
      gateRequest = selectionState.gateRequest,
      scrollToSectionId = selectionState.scrollToSectionId,
      launchModel = selectionState.launchModel,
      notReady = selectionState.notReady,
    )
  }

  private fun initialState(newBinding: Binding): SelectionState {
    val modelManagerUiState = newBinding.host.uiState.value
    val selections = mutableMapOf<String, String>()
    for (section in newBinding.config.sections) {
      val candidates = candidatesFor(section, newBinding.task, modelManagerUiState)
      val preselected =
        when (section.target) {
          ModelSelectionTarget.MAIN_MODEL -> {
            // The main model last launched for the task, as long as it can still be picked.
            val lastName = modelManagerUiState.lastMainModelByTaskId[newBinding.task.id]
            candidates.firstOrNull {
              it.name == lastName &&
                isModelSelectable(
                  model = it,
                  status = modelManagerUiState.modelDownloadStatus[it.name],
                  isMemoryLow = newBinding.host.isMemoryLow(it),
                )
            }
              ?: candidates.firstOrNull {
                newBinding.task.id in it.bestForTaskIds &&
                  isModelSelectable(
                    model = it,
                    status = modelManagerUiState.modelDownloadStatus[it.name],
                    isMemoryLow = newBinding.host.isMemoryLow(it),
                  )
              }
          }
          ModelSelectionTarget.ASR_MODEL,
          ModelSelectionTarget.TTS_MODEL ->
            candidates.firstOrNull {
              newBinding.host.isSavedSelection(section.target, it) &&
                isModelSelectable(
                  model = it,
                  status = modelManagerUiState.modelDownloadStatus[it.name],
                  isMemoryLow = newBinding.host.isMemoryLow(it),
                )
            }
        }
      if (preselected != null) {
        selections[section.id] = preselected.name
      }
    }
    return SelectionState(
      selections = selections,
      // Every section starts expanded by default, even when a model is already preselected.
      expandedSectionIds = newBinding.config.sections.map { it.id }.toSet(),
    )
  }

  private fun restoreState(): SelectionState {
    val selections =
      savedStateHandle
        .keys()
        .filter { it.startsWith(KEY_SELECTION_PREFIX) }
        .mapNotNull { key ->
          val modelName: String? = savedStateHandle[key]
          modelName?.let { key.removePrefix(KEY_SELECTION_PREFIX) to it }
        }
        .toMap()
    val expandedSectionIds: List<String> = savedStateHandle[KEY_EXPANDED_SECTIONS] ?: emptyList()
    val collapsedModelNames: List<String> = savedStateHandle[KEY_COLLAPSED_MODELS] ?: emptyList()
    val isSummaryExpanded: Boolean = savedStateHandle[KEY_SUMMARY_EXPANDED] ?: false
    return SelectionState(
      selections = selections,
      expandedSectionIds = expandedSectionIds.toSet(),
      collapsedModelNames = collapsedModelNames.toSet(),
      isSummaryExpanded = isSummaryExpanded,
    )
  }

  private fun update(newState: SelectionState) {
    state.update { newState }
    persist(newState)
  }

  private fun persist(selectionState: SelectionState) {
    for ((sectionId, modelName) in selectionState.selections) {
      savedStateHandle[KEY_SELECTION_PREFIX + sectionId] = modelName
    }
    savedStateHandle[KEY_EXPANDED_SECTIONS] = ArrayList(selectionState.expandedSectionIds)
    savedStateHandle[KEY_COLLAPSED_MODELS] = ArrayList(selectionState.collapsedModelNames)
    savedStateHandle[KEY_SUMMARY_EXPANDED] = selectionState.isSummaryExpanded
  }

  private fun findModel(
    currentBinding: Binding,
    modelManagerUiState: ModelManagerUiState,
    name: String,
  ): Model? =
    currentBinding.config.sections
      .asSequence()
      .flatMap { candidatesFor(it, currentBinding.task, modelManagerUiState) }
      .firstOrNull { it.name == name }
}

/**
 * Whether [model] still needs downloading before launch. Downloads already running are left alone.
 */
private fun needsDownload(model: Model, status: ModelDownloadStatus?): Boolean =
  when (status?.status) {
    ModelDownloadStatusType.SUCCEEDED,
    ModelDownloadStatusType.IN_PROGRESS,
    ModelDownloadStatusType.UNZIPPING -> false
    else -> true
  }

/**
 * Whether [model] is a speech model whose files the system manages (ML Kit), so its readiness is
 * checked right before launch.
 */
private fun isSystemManagedSpeechModel(model: Model): Boolean =
  model.isSupplementary && !model.supportsTopLevelDownload

/** Overall progress of the downloads in [names], or `null` while the total size is unknown. */
private fun downloadProgress(
  names: Set<String>,
  statuses: Map<String, ModelDownloadStatus>,
): Float? {
  if (names.isEmpty()) {
    return null
  }
  var received = 0L
  var total = 0L
  for (name in names) {
    val status = statuses[name] ?: return null
    if (status.status == ModelDownloadStatusType.SUCCEEDED) {
      if (status.totalBytes > 0) {
        received += status.totalBytes
        total += status.totalBytes
      }
      continue
    }
    if (status.totalBytes <= 0) {
      return null
    }
    received += status.receivedBytes
    total += status.totalBytes
  }
  return if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
}
