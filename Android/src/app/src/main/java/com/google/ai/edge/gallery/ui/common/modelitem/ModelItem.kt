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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST1
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST2
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST3
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST4
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST2
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors

private val DEFAULT_VERTICAL_PADDING = 16.dp

/**
 * Composable function to display a model item in the model manager list.
 *
 * This function renders a card representing a model, displaying its task icon, name, download
 * status, and providing action buttons. It supports expanding to show a model description and
 * buttons for learning more (opening a URL) and downloading/trying the model.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ModelItem(
  model: Model,
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  onModelClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
  showDeleteButton: Boolean = true,
  canExpand: Boolean = true,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val downloadStatus by remember {
    derivedStateOf { modelManagerUiState.modelDownloadStatus[model.name] }
  }

  val isBestOverall = model.bestForTaskIds.contains(task.id)
  var isExpanded by remember { mutableStateOf(isBestOverall) }

  var boxModifier =
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(size = 12.dp))
      .background(color = MaterialTheme.customColors.taskCardBgColor)
  boxModifier =
    if (canExpand) {
      boxModifier
        .semantics {
          contentDescription = "${model.displayName.ifEmpty { model.name }}, ${if (isExpanded) "expanded" else "collapsed"}"
        }
        .clickable(
          onClick = {
            if (!model.imported) {
              isExpanded = !isExpanded
            } else {
              onModelClicked(model)
            }
          },
          interactionSource = remember { MutableInteractionSource() },
          indication = ripple(bounded = true, radius = 1000.dp),
        )
    } else {
      boxModifier
    }

  Box(modifier = boxModifier) {
    SharedTransitionLayout {
      AnimatedContent(isExpanded, label = "item_layout_transition") { targetState ->
        val deleteModelButton =
          @Composable {
            DeleteModelButton(
              model = model,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
              downloadStatus = downloadStatus,
              showDeleteButton = showDeleteButton,
              modifier =
                Modifier.offset(y = (-12).dp, x = if (model.imported) 12.dp else 0.dp)
                  .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "action_button"),
                    animatedVisibilityScope = this@AnimatedContent,
                  ),
            )
          }

        val expandButton =
          @Composable {
            Icon(
              if (isExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
              contentDescription = if (isExpanded) "Collapse" else "Expand",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier.alpha(0.6f)
                  .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "expand_button"),
                    animatedVisibilityScope = this@AnimatedContent,
                  ),
            )
          }

        val description =
          @Composable {
            if (model.info.isNotEmpty()) {
              MarkdownText(
                model.info,
                smallFontSize = true,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                  Modifier.padding(top = 12.dp)
                    .sharedElement(
                      sharedContentState = rememberSharedContentState(key = "description"),
                      animatedVisibilityScope = this@AnimatedContent,
                    )
                    .skipToLookaheadSize(),
              )
            }
          }

        val downloadModelPanel =
          @Composable {
            DownloadModelPanel(
              task = task,
              model = model,
              downloadStatus = downloadStatus,
              animatedVisibilityScope = this@AnimatedContent,
              sharedTransitionScope = this@SharedTransitionLayout,
              modifier =
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "download_panel"),
                    animatedVisibilityScope = this@AnimatedContent,
                  )
                  .padding(top = if (isExpanded) 12.dp else 0.dp),
              modelManagerViewModel = modelManagerViewModel,
              isExpanded = isExpanded,
              onTryItClicked = { onModelClicked(model) },
            )
          }

        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelNameAndStatus(
              model = model,
              task = task,
              downloadStatus = downloadStatus,
              isExpanded = isExpanded,
              modifier = Modifier.weight(1f),
              animatedVisibilityScope = this@AnimatedContent,
              sharedTransitionScope = this@SharedTransitionLayout,
            )
            // Button to delete model and expand/collapse button at the right.
            Row(verticalAlignment = Alignment.Top) {
              if (model.localFileRelativeDirPathOverride.isEmpty()) {
                deleteModelButton()
              }
              if (!model.imported) {
                expandButton()
              }
            }
          }
          // Show description when expanded.
          if (targetState) {
            description()
          }
          // Model download panel.
          downloadModelPanel()
        }
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun PreviewModelItem() {
//   GalleryTheme {
//     Column(
//       verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)
//     ) {
//       ModelItem(
//         model = MODEL_TEST1,
//         task = TASK_TEST1,
//         onModelClicked = { },
//         modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
//       )
//       ModelItem(
//         model = MODEL_TEST2,
//         task = TASK_TEST1,
//         onModelClicked = { },
//         modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
//       )
//       ModelItem(
//         model = MODEL_TEST3,
//         task = TASK_TEST2,
//         onModelClicked = { },
//         modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
//       )
//       ModelItem(
//         model = MODEL_TEST4,
//         task = TASK_TEST2,
//         onModelClicked = { },
//         modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
//       )
//     }
//   }
// }
