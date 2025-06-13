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

package com.google.ai.edge.gallery.ui.llmsingleturn

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.PreviewLlmSingleTurnViewModel
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Navigation destination data */
object LlmSingleTurnDestination {
  val route = "LlmSingleTurnRoute"
}

private const val TAG = "AGLlmSingleTurnScreen"

@Composable
fun LlmSingleTurnScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmSingleTurnViewModel = viewModel(factory = ViewModelProvider.Factory),
) {
  val task = viewModel.task
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
        modelManagerViewModel.cleanupModel(task = task, model = model)
      }
    }
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

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
        onModelSelected = { newSelectedModel ->
          scope.launch(Dispatchers.Default) {
            // Clean up current model.
            modelManagerViewModel.cleanupModel(task = task, model = selectedModel)

            // Update selected model.
            modelManagerViewModel.selectModel(model = newSelectedModel)
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.padding(
          top = innerPadding.calculateTopPadding(),
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      ModelDownloadStatusInfoPanel(
        model = selectedModel,
        task = task,
        modelManagerViewModel = modelManagerViewModel,
      )

      // Main UI after model is downloaded.
      val modelDownloaded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier =
          Modifier.weight(1f)
            // Just hide the UI without removing it from the screen so that the scroll syncing
            // from ResponsePanel still works.
            .alpha(if (modelDownloaded) 1.0f else 0.0f),
      ) {
        VerticalSplitView(
          modifier = Modifier.fillMaxSize(),
          topView = {
            PromptTemplatesPanel(
              model = selectedModel,
              viewModel = viewModel,
              modelManagerViewModel = modelManagerViewModel,
              onSend = { fullPrompt ->
                viewModel.generateResponse(model = selectedModel, input = fullPrompt)
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
