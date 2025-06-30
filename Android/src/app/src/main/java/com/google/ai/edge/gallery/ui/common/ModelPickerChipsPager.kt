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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.modelitem.StatusIcon
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerChipsPager(
  task: Task,
  initialModel: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onModelSelected: (Model) -> Unit,
) {
  var showModelPicker by remember { mutableStateOf(false) }
  var modelPickerModel by remember { mutableStateOf<Model?>(null) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }

  val pagerState =
    rememberPagerState(
      initialPage = task.models.indexOf(initialModel),
      pageCount = { task.models.size },
    )

  // Sync scrolling.
  LaunchedEffect(modelManagerViewModel.pagerScrollState) {
    modelManagerViewModel.pagerScrollState.collect { state ->
      pagerState.scrollToPage(state.page, state.offset)
    }
  }

  HorizontalPager(state = pagerState, userScrollEnabled = false) { pageIndex ->
    val model = task.models[pageIndex]

    // Calculate the alpha of the current page based on how far they are from the center.
    val pageOffset =
      ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction).absoluteValue
    val curAlpha = 1f - (pageOffset * 1.5f).coerceIn(0f, 1f)

    val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]

    Box(
      modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = curAlpha },
      contentAlignment = Alignment.Center,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          modifier =
            Modifier.clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .clickable {
                modelPickerModel = model
                showModelPicker = true
              }
              .padding(start = 8.dp, end = 2.dp)
              .padding(vertical = 4.dp),
        ) Inner@{
          Box(contentAlignment = Alignment.Center, modifier = Modifier.size(21.dp)) {
            StatusIcon(downloadStatus = modelManagerUiState.modelDownloadStatus[model.name])
            this@Inner.AnimatedVisibility(
              visible =
                modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING,
              enter = scaleIn() + fadeIn(),
              exit = scaleOut() + fadeOut(),
            ) {
              // Circular progress indicator.
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp).alpha(0.5f),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Text(
            model.name,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp).widthIn(0.dp, screenWidthDp - 250.dp),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
          )
          Icon(
            Icons.Rounded.ArrowDropDown,
            modifier = Modifier.size(20.dp),
            contentDescription = "",
          )
        }
      }
    }
  }

  // Model picker.
  val curModelPickerModel = modelPickerModel
  if (showModelPicker && curModelPickerModel != null) {
    ModalBottomSheet(onDismissRequest = { showModelPicker = false }, sheetState = sheetState) {
      ModelPicker(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        onModelSelected = { selectedModel ->
          showModelPicker = false

          scope.launch(Dispatchers.Default) {
            // Scroll to the selected model.
            pagerState.animateScrollToPage(task.models.indexOf(selectedModel))
          }

          onModelSelected(selectedModel)
        },
      )
    }
  }
}
