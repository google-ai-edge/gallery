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

package com.neuralforge.mobile.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.ModelDownloadStatus
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.common.DownloadAndTryButton
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DownloadModelPanel(
  model: Model,
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatus?,
  isExpanded: Boolean,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onTryItClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  with(sharedTransitionScope) {
    Box(contentAlignment = Alignment.BottomEnd, modifier = modifier.fillMaxWidth()) {
      DownloadAndTryButton(
        task = task,
        model = model,
        downloadStatus = downloadStatus,
        enabled = true,
        modelManagerViewModel = modelManagerViewModel,
        onClicked = onTryItClicked,
        compact = !isExpanded,
        modifier =
          Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = "download_button"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
      )
    }
  }
}
