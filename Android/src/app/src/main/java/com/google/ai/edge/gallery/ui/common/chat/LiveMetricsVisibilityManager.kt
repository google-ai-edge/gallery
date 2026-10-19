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

package com.google.ai.edge.gallery.ui.common.chat

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.BUTTON_CLICK_EVENT_LIVE_METRICS_TOGGLE
import com.google.ai.edge.gallery.ButtonClickAction
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGLiveMetricsVisMgr"

/**
 * Persistent promo ID prefix for the per-task expandable discovery pill on the live metrics toggle.
 */
private const val LIVE_METRICS_TOGGLE_PROMPT_PROMO_ID = "live_metrics_toggle_prompt"

/**
 * Returns the persistent per-task promo ID for [taskId]'s live metrics expandable discovery pill.
 */
@VisibleForTesting
fun liveMetricsTogglePromptPromoId(taskId: String): String =
  "${LIVE_METRICS_TOGGLE_PROMPT_PROMO_ID}_$taskId"

/**
 * Manages per-task visibility, one-time discovery prompt state, [DataStoreRepository] persistence,
 * `MetricsTracker` synchronization, and toggle analytics for the live inference metrics banner.
 */
internal class LiveMetricsVisibilityManager(
  private val uiState: MutableStateFlow<ChatUiState>,
  private val dataStoreRepository: DataStoreRepository?,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  private val onSyncVisibility: (Model?, Boolean) -> Unit,
) {
  var currentTaskId: String = ""
    private set

  private val visibilityByTask = mutableMapOf<String, Boolean>()
  private val writeMutex = Mutex()

  /**
   * Loads the persisted live metrics visibility setting for [taskId] into [ChatUiState] and
   * synchronizes the active metrics tracker.
   */
  suspend fun loadVisibility(taskId: String) {
    if (taskId.isEmpty()) {
      return
    }
    val isSwitchingTask = currentTaskId != taskId
    currentTaskId = taskId
    val cached = visibilityByTask[taskId]
    if (cached != null) {
      uiState.update { state ->
        state.copy(
          showLiveMetrics = cached,
          showLiveMetricsPrompt =
            if (isSwitchingTask || cached) false else state.showLiveMetricsPrompt,
        )
      }
      onSyncVisibility(null, cached)
      return
    }
    if (isSwitchingTask && uiState.value.showLiveMetricsPrompt) {
      uiState.update { it.copy(showLiveMetricsPrompt = false) }
    }
    val repo = dataStoreRepository
    if (repo == null) {
      visibilityByTask[taskId] = false
      uiState.update { it.copy(showLiveMetrics = false) }
      onSyncVisibility(null, false)
      return
    }
    val visible = withContext(ioDispatcher) { repo.readShowLiveMetrics(taskId) }
    val resolved = visibilityByTask.getOrPut(taskId) { visible }
    if (currentTaskId == taskId) {
      uiState.update { state ->
        state.copy(
          showLiveMetrics = resolved,
          showLiveMetricsPrompt = if (resolved) false else state.showLiveMetricsPrompt,
        )
      }
      onSyncVisibility(null, resolved)
    }
  }

  /**
   * Toggles the visibility of the live inference metrics banner for [taskId], persists the
   * preference, updates the active metrics tracker, and logs the action to Firebase Analytics.
   */
  fun toggle(taskId: String, model: Model) {
    if (taskId.isEmpty()) {
      return
    }
    currentTaskId = taskId
    val currentVisible = visibilityByTask[taskId] ?: uiState.value.showLiveMetrics
    val newVisible = !currentVisible
    visibilityByTask[taskId] = newVisible
    uiState.update { it.copy(showLiveMetrics = newVisible, showLiveMetricsPrompt = false) }
    onSyncVisibility(model, newVisible)
    dataStoreRepository?.let { repo ->
      scope.launch(ioDispatcher) {
        writeMutex.withLock { repo.saveShowLiveMetrics(taskId, newVisible) }
      }
    }
    val action = if (newVisible) ButtonClickAction.TURN_ON else ButtonClickAction.TURN_OFF
    Log.d(
      TAG,
      "Analytics: button_clicked, event_type=$BUTTON_CLICK_EVENT_LIVE_METRICS_TOGGLE, action=${action.id}, capability_name=$taskId, model_id=${model.name}",
    )
    logButtonClick(
      eventType = BUTTON_CLICK_EVENT_LIVE_METRICS_TOGGLE,
      action = action,
      capabilityName = taskId,
      modelId = model.name,
    )
  }

  /**
   * Shows the once-per-task expandable discovery pill label on the live metrics toggle button if
   * the user has not viewed it for [taskId] before and the live metrics banner is currently
   * collapsed.
   */
  suspend fun maybeShowPrompt(taskId: String) {
    if (taskId.isEmpty()) {
      return
    }
    loadVisibility(taskId)
    if (uiState.value.showLiveMetrics || uiState.value.showLiveMetricsPrompt) {
      return
    }
    val repo = dataStoreRepository ?: return
    val promoId = liveMetricsTogglePromptPromoId(taskId)
    val shouldShow =
      withContext(ioDispatcher) {
        if (!repo.hasViewedPromo(promoId)) {
          repo.addViewedPromoId(promoId)
          true
        } else {
          false
        }
      }
    if (shouldShow && currentTaskId == taskId && !uiState.value.showLiveMetrics) {
      uiState.update { it.copy(showLiveMetricsPrompt = true) }
    }
  }

  /** Dismisses the live metrics discovery pill prompt if currently visible. */
  fun dismissPrompt() {
    if (uiState.value.showLiveMetricsPrompt) {
      uiState.update { it.copy(showLiveMetricsPrompt = false) }
    }
  }
}
