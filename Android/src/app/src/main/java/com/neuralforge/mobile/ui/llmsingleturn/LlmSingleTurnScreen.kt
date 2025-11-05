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

package com.neuralforge.mobile.ui.llmsingleturn

import androidx.hilt.navigation.compose.hiltViewModel

// import androidx.compose.ui.tooling.preview.Preview
// import com.neuralforge.mobile.ui.preview.PreviewLlmSingleTurnViewModel
// import com.neuralforge.mobile.ui.preview.PreviewModelManagerViewModel
// import com.neuralforge.mobile.ui.theme.GalleryTheme
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.os.bundleOf
import com.neuralforge.mobile.data.BuiltInTaskId
import com.neuralforge.mobile.data.ModelDownloadStatusType
import com.neuralforge.mobile.firebaseAnalytics
import com.neuralforge.mobile.ui.common.ErrorDialog
import com.neuralforge.mobile.ui.common.ModelPageAppBar
import com.neuralforge.mobile.ui.common.chat.ModelDownloadStatusInfoPanel
import com.neuralforge.mobile.ui.modelmanager.ModelInitializationStatusType
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import com.neuralforge.mobile.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGLlmSingleTurnScreen"

@Composable
fun LlmSingleTurnScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmSingleTurnViewModel = hiltViewModel(),
) {
  val task = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_PROMPT_LAB)!!
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }

  val handleNavigateUp = {
    navigatingUp = true
    navigateUp()

    // clean up all models.
    scope.launch(Dispatchers.Default) {
      for (model in task.models) {
        modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
      }
    }
  }

  // Handle system's edge swipe.
  BackHandler {
    val modelInitializationStatus =
      modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelInitializing =
      modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
    if (!isModelInitializing && !uiState.inProgress) {
      handleNavigateUp()
    }
  }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from LlmsingleTurnScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      ModelPageAppBar(
        task = task,
        model = selectedModel,
        modelManagerViewModel = modelManagerViewModel,
        inProgress = uiState.inProgress,
        modelPreparing = uiState.preparing,
        onConfigChanged = { _, _ -> },
        onBackClicked = { handleNavigateUp() },
        onModelSelected = { prevModel, newSelectedModel ->
          scope.launch(Dispatchers.Default) {
            if (prevModel.name != newSelectedModel.name) {
              // Clean up prev model.
              modelManagerViewModel.cleanupModel(context = context, task = task, model = prevModel)
            }

            // Update selected model.
            modelManagerViewModel.selectModel(model = newSelectedModel)
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.padding(
          top = innerPadding.calculateTopPadding(),
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val modelDownloaded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      AnimatedVisibility(
        visible = !modelDownloaded,
        enter = scaleIn(initialScale = 0.9f) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
      ) {
        ModelDownloadStatusInfoPanel(
          model = selectedModel,
          task = task,
          modelManagerViewModel = modelManagerViewModel,
        )
      }

      // Main UI after model is downloaded.
      var mainUiVisible by remember { mutableStateOf(modelDownloaded) }
      LaunchedEffect(modelDownloaded) { mainUiVisible = modelDownloaded }
      val animatedAlpha by animateFloatAsState(targetValue = if (mainUiVisible) 1.0f else 0f)
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier =
          Modifier.fillMaxSize()
            // Just hide the UI without removing it from the screen so that the scroll syncing
            // from ResponsePanel still works.
            .graphicsLayer { alpha = animatedAlpha },
      ) {
        VerticalSplitView(
          modifier = Modifier.fillMaxSize(),
          topView = {
            PromptTemplatesPanel(
              model = selectedModel,
              viewModel = viewModel,
              modelManagerViewModel = modelManagerViewModel,
              onSend = { fullPrompt ->
                viewModel.generateResponse(task = task, model = selectedModel, input = fullPrompt)

                firebaseAnalytics?.logEvent(
                  "generate_action",
                  bundleOf("capability_name" to task.id, "model_id" to selectedModel.name),
                )
              },
              onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
              modifier = Modifier.fillMaxSize(),
            )
          },
          bottomView = {
            Box(
              contentAlignment = Alignment.BottomCenter,
              modifier =
                Modifier.fillMaxSize().background(MaterialTheme.customColors.agentBubbleBgColor),
            ) {
              ResponsePanel(
                task = task,
                model = selectedModel,
                viewModel = viewModel,
                modelManagerViewModel = modelManagerViewModel,
                modifier =
                  Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
              )
            }
          },
        )
      }

      if (showErrorDialog) {
        ErrorDialog(
          error = modelInitializationStatus?.error ?: "",
          onDismiss = { showErrorDialog = false },
        )
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun LlmSingleTurnScreenPreview() {
//   val context = LocalContext.current
//   GalleryTheme {
//     LlmSingleTurnScreen(
//       modelManagerViewModel = PreviewModelManagerViewModel(context = context),
//       viewModel = PreviewLlmSingleTurnViewModel(),
//       navigateUp = {},
//     )
//   }
// }
