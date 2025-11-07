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

package com.neuralforge.mobile.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.ModelDownloadStatusType
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.common.DownloadAndTryButton
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel

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
  val downloading =
    curStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
      curStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
      curStatus?.status == ModelDownloadStatusType.UNZIPPING

  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    // Animation.
    Column(verticalArrangement = Arrangement.Bottom, modifier = Modifier.weight(1f)) {
      AnimatedVisibility(
        visible = downloading,
        enter = scaleIn(initialScale = 0.9f) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
      ) {
        ModelDownloadingAnimation(
          model = model,
          task = task,
          modelManagerViewModel = modelManagerViewModel,
        )
      }
    }

    // Download button and progress.
    DownloadAndTryButton(
      task = task,
      model = model,
      enabled = true,
      downloadStatus = curStatus,
      modelManagerViewModel = modelManagerViewModel,
      modifier = Modifier.padding(horizontal = 32.dp).padding(top = 4.dp, bottom = 16.dp),
      onClicked = {},
      canShowTryIt = false,
    )

    // Info text.
    Column(verticalArrangement = Arrangement.Top, modifier = Modifier.weight(1f)) {
      AnimatedVisibility(
        visible = downloading,
        enter = scaleIn(initialScale = 0.9f) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
      ) {
        Text(
          "Feel free to switch apps or lock your device.\n" +
            "The download will continue in the background.\n" +
            "We'll send a notification when it's done.",
          style = MaterialTheme.typography.bodyLarge,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
