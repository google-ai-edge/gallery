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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.DownloadAndTryButton
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DownloadModelPanel(
  model: Model,
  task: Task?,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatusType?,
  downloadProgress: Float,
  isExpanded: Boolean,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onTryItClicked: () -> Unit,
  tosViewModel: TosViewModel? = null,
  modifier: Modifier = Modifier,
  downloadButtonBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  with(sharedTransitionScope) {
    Row(
      modifier = modifier,
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      fun isDownloadButtonEnabled(downloadStatus: ModelDownloadStatusType?, model: Model): Boolean {
        val downloadFailed = downloadStatus == ModelDownloadStatusType.FAILED
        val isLitertLm = model.runtimeType == RuntimeType.LITERT_LM
        return !downloadFailed || isLitertLm
      }

      DownloadAndTryButton(
        task = task,
        model = model,
        downloadStatus = downloadStatus,
        downloadProgress = downloadProgress,
        enabled = isDownloadButtonEnabled(downloadStatus, model),
        modelManagerViewModel = modelManagerViewModel,
        onClicked = onTryItClicked,
        compact = !isExpanded,
        modifier =
          Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = "download_button_${model.name}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        modifierWhenExpanded = Modifier.weight(1f),
        tosViewModel = tosViewModel ?: hiltViewModel(),
        downloadButtonBackgroundColor = downloadButtonBackgroundColor,
      )
    }
  }
}
