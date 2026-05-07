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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPageAppBar(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
  onModelSelected: (prev: Model, cur: Model) -> Unit,
  inProgress: Boolean,
  modelPreparing: Boolean,
  modifier: Modifier = Modifier,
  hideModelSelector: Boolean = false,
  useThemeColor: Boolean = false,
  onConfigChanged: (oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) -> Unit =
    { _, _ ->
    },
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  shouldShowHistoryButton: Boolean = false,
  onHistoryClicked: (Model) -> Unit = {},
) {
  var showConfigDialog by remember { mutableStateOf(false) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val isModelInitializing =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
  val isModelInitialized =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED

  CenterAlignedTopAppBar(
    title = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // Task type.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val tintColor =
            if (useThemeColor) MaterialTheme.colorScheme.onSurface
            else getTaskIconColor(task = task)
          Icon(
            task.icon ?: ImageVector.vectorResource(task.iconVectorResourceId!!),
            tint = tintColor,
            modifier = Modifier.size(24.dp),
            contentDescription = null,
          )
          Text(task.label, style = MaterialTheme.typography.titleMedium, color = tintColor)
        }

        // Model chips pager.
        if (!hideModelSelector) {
          val enableModelPickerChip = !isModelInitializing && !inProgress
          ModelPickerChip(
            enabled = enableModelPickerChip,
            task = task,
            initialModel = model,
            modelManagerViewModel = modelManagerViewModel,
            onModelSelected = onModelSelected,
          )
        }
      }
    },
    modifier = modifier,
    // The back button.
    navigationIcon = {
      val enableBackButton = !isModelInitializing && !inProgress
      IconButton(onClick = onBackClicked, enabled = enableBackButton) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.cd_navigate_back_icon),
        )
      }
    },
    // The config button for the model (if existed).
    actions = {
      val downloadSucceeded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      val showConfigButton = model.configs.isNotEmpty() && downloadSucceeded
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (showConfigButton) {
          val enableConfigButton = !isModelInitializing && !inProgress && isModelInitialized
          IconButton(
            onClick = { showConfigDialog = true },
            enabled = enableConfigButton,
            modifier = Modifier.alpha(if (!enableConfigButton) 0.5f else 1f),
          ) {
            Icon(
              imageVector = Icons.Rounded.Tune,
              contentDescription = stringResource(R.string.cd_model_settings_icon),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (downloadSucceeded && shouldShowHistoryButton) {
          val enableHistoryButton =
            !isModelInitializing && !modelPreparing && !inProgress && isModelInitialized
          IconButton(
            onClick = { onHistoryClicked(model) },
            enabled = enableHistoryButton,
            modifier = Modifier.alpha(if (!enableHistoryButton) 0.5f else 1f),
          ) {
            Icon(
              imageVector = Icons.Rounded.History,
              contentDescription = stringResource(R.string.cd_chat_history),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    },
  )

  // Config dialog.
  if (showConfigDialog) {
    // Remove the reset conversation turn count config for non-tiny-garden tasks.
    //
    // This may happen when user imports a model with "enable tiny garden" turned on and use the
    // model in another non-tiny-garden task.
    val modelConfigs = model.configs.toMutableList()
    if (task.id != BuiltInTaskId.LLM_TINY_GARDEN) {
      modelConfigs.removeIf { it.key == ConfigKeys.RESET_CONVERSATION_TURN_COUNT }
    }
    if (!task.allowCapability(ModelCapability.LLM_THINKING, model)) {
      modelConfigs.removeIf { it.key == ConfigKeys.ENABLE_THINKING }
    }
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
      com.google.ai.edge.litertlm.Capabilities(model.getPath(context)).use {
        supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    if (
      !supportsSpeculativeDecoding ||
        !task.allowCapability(ModelCapability.SPECULATIVE_DECODING, model)
    ) {
      modelConfigs.removeIf { it.key == ConfigKeys.ENABLE_SPECULATIVE_DECODING }
    }
    ConfigDialog(
      title = "Configurations",
      configs = modelConfigs,
      initialValues = model.configValues,
      onDismissed = { showConfigDialog = false },
      onOk = { curConfigValues, oldSystemPrompt, newSystemPrompt ->
        // Hide config dialog.
        showConfigDialog = false

        // Check if the configs are changed or not. Also check if the model needs to be
        // re-initialized.
        var same = true
        var needReinitialization = false
        for (config in modelConfigs) {
          val key = config.key.label
          val oldValue =
            convertValueToTargetType(
              value = model.configValues.getValue(key),
              valueType = config.valueType,
            )
          val newValue =
            convertValueToTargetType(
              value = curConfigValues.getValue(key),
              valueType = config.valueType,
            )
          if (oldValue != newValue) {
            same = false
            if (config.needReinitialization) {
              needReinitialization = true
            }
            break
          }
        }
        if (same) {
          if (newSystemPrompt != oldSystemPrompt) {
            onSystemPromptChanged(newSystemPrompt)
          }
          return@ConfigDialog
        }

        // Save the config values to Model.
        val oldConfigValues = model.configValues
        model.prevConfigValues = oldConfigValues
        model.configValues = curConfigValues
        modelManagerViewModel.updateConfigValuesUpdateTrigger()

        if (!task.handleModelConfigChangesInTask) {
          // Force to re-initialize the model with the new configs.
          if (needReinitialization) {
            modelManagerViewModel.initializeModel(
              context = context,
              task = task,
              model = model,
              force = true,
              onDone = {
                if (oldSystemPrompt != newSystemPrompt) {
                  onSystemPromptChanged(newSystemPrompt)
                }
              },
            )
          }

          // Notify.
          onConfigChanged(oldConfigValues, model.configValues)
        }
      },
      // AICore doesn't support system prompt yet.
      showSystemPromptEditorTab =
        allowEditingSystemPrompt && model.runtimeType != RuntimeType.AICORE,
      defaultSystemPrompt = task.defaultSystemPrompt,
      curSystemPrompt = curSystemPrompt,
    )
  }
}
