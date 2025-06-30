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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.DownloadAndTryButton
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay

@Composable
fun ModelDownloadStatusInfoPanel(
  model: Model,
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Manages the conditional display of UI elements (download model button and downloading
  // animation) based on the corresponding download status.
  //
  // It uses delayed visibility ensuring they are shown only after a short delay if their
  // respective conditions remain true. This prevents UI flickering and provides a smoother
  // user experience.
  val curStatus = modelManagerUiState.modelDownloadStatus[model.name]
  var shouldShowDownloadingAnimation by remember { mutableStateOf(false) }
  var downloadingAnimationConditionMet by remember { mutableStateOf(false) }
  var shouldShowDownloadModelButton by remember { mutableStateOf(false) }
  var downloadModelButtonConditionMet by remember { mutableStateOf(false) }

  downloadingAnimationConditionMet =
    curStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
      curStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
      curStatus?.status == ModelDownloadStatusType.UNZIPPING
  downloadModelButtonConditionMet =
    curStatus?.status == ModelDownloadStatusType.FAILED ||
      curStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED

  LaunchedEffect(downloadingAnimationConditionMet) {
    if (downloadingAnimationConditionMet) {
      delay(100)
      shouldShowDownloadingAnimation = true
    } else {
      shouldShowDownloadingAnimation = false
    }
  }

  LaunchedEffect(downloadModelButtonConditionMet) {
    if (downloadModelButtonConditionMet) {
      delay(700)
      shouldShowDownloadModelButton = true
    } else {
      shouldShowDownloadModelButton = false
    }
  }

  AnimatedVisibility(
    visible = shouldShowDownloadingAnimation,
    enter = scaleIn(initialScale = 0.9f) + fadeIn(),
    exit = scaleOut(targetScale = 0.9f) + fadeOut(),
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      ModelDownloadingAnimation(
        model = model,
        task = task,
        modelManagerViewModel = modelManagerViewModel,
      )
    }
  }

  AnimatedVisibility(visible = shouldShowDownloadModelButton, enter = fadeIn(), exit = fadeOut()) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      DownloadAndTryButton(
        task = task,
        model = model,
        enabled = true,
        needToDownloadFirst = true,
        modelManagerViewModel = modelManagerViewModel,
        onClicked = {},
      )
    }
  }
}
