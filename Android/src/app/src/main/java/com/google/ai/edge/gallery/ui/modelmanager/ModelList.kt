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

package com.google.ai.edge.gallery.ui.modelmanager

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.RevealingText
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.getTaskBgColor
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.theme.bodyLargeNarrow
import com.google.ai.edge.gallery.ui.theme.headlineLargeMedium

private const val TAG = "AGModelList"
private val CONTENT_ANIMATION_OFFSET = 16.dp
private const val ANIMATION_INIT_DELAY = 80L
private const val TASK_DESCRIPTION_SECTION_ANIMATION_START = 400
private const val MODEL_LIST_ANIMATION_START = TASK_DESCRIPTION_SECTION_ANIMATION_START + 150
private const val DEFAULT_ANIMATION_DURATION = 700
private const val TASK_ICON_ANIMATION_DURATION = 1100

/** The list of models in the model manager. */
@Composable
fun ModelList(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  contentPadding: PaddingValues,
  onModelClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  // This is just to update "models" list when task.updateTrigger is updated so that the UI can
  // be properly updated.
  val models by
    remember(task) {
      derivedStateOf {
        val trigger = task.updateTrigger.value
        if (trigger >= 0) {
          task.models.toList().filter { !it.imported }
        } else {
          listOf()
        }
      }
    }
  val importedModels by
    remember(task) {
      derivedStateOf {
        val trigger = task.updateTrigger.value
        if (trigger >= 0) {
          task.models.toList().filter { it.imported }
        } else {
          listOf()
        }
      }
    }

  val listState = rememberLazyListState()

  val taskIconProgress =
    rememberDelayedAnimationProgress(
      initialDelay = ANIMATION_INIT_DELAY,
      animationDurationMs = TASK_ICON_ANIMATION_DURATION,
      animationLabel = "task icon",
    )

  val taskLabelProgress =
    rememberDelayedAnimationProgress(
      initialDelay = ANIMATION_INIT_DELAY + 300,
      animationDurationMs = TASK_ICON_ANIMATION_DURATION,
      animationLabel = "task label",
    )

  val descriptionProgress =
    rememberDelayedAnimationProgress(
      initialDelay = ANIMATION_INIT_DELAY + TASK_DESCRIPTION_SECTION_ANIMATION_START,
      animationDurationMs = DEFAULT_ANIMATION_DURATION,
      animationLabel = "description",
    )

  val modelListProgress =
    rememberDelayedAnimationProgress(
      initialDelay = ANIMATION_INIT_DELAY + MODEL_LIST_ANIMATION_START,
      animationDurationMs = DEFAULT_ANIMATION_DURATION,
      animationLabel = "model_list",
    )

  Box(
    contentAlignment = Alignment.BottomEnd,
    modifier = Modifier.background(color = getTaskBgColor(task = task)),
  ) {
    LazyColumn(
      modifier = modifier.padding(top = 32.dp).padding(horizontal = 16.dp),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      state = listState,
    ) {
      // Task header area.
      item(key = "taskHeader") {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        ) {
          // Task icon.
          TaskIcon(task = task, width = 64.dp, animationProgress = taskIconProgress)

          // Task name.
          Box(
            modifier =
              Modifier.offset(x = (20f * (1f - taskIconProgress)).dp).semantics {
                contentDescription = task.label
              }
          ) {
            RevealingText(
              text = task.label,
              style =
                headlineLargeMedium.copy(
                  brush = Brush.linearGradient(getTaskBgGradientColors(task = task))
                ),
              textAlign = TextAlign.Center,
              animationProgress = taskIconProgress,
            )
            RevealingText(
              text = task.label,
              style = headlineLargeMedium,
              textAlign = TextAlign.Center,
              animationProgress = taskLabelProgress,
            )
          }

          // Description.
          Text(
            task.description,
            textAlign = TextAlign.Center,
            style = bodyLargeNarrow,
            modifier =
              Modifier.graphicsLayer {
                alpha = descriptionProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - descriptionProgress)).toPx()
              },
          )

          // Urls.
          if (task.docUrl.isNotEmpty() || task.sourceCodeUrl.isNotEmpty()) {
            Box(
              modifier =
                Modifier.padding(vertical = 8.dp).graphicsLayer {
                  alpha = descriptionProgress
                  translationY = (CONTENT_ANIMATION_OFFSET * (1 - descriptionProgress)).toPx()
                }
            ) {
              Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                if (task.docUrl.isNotEmpty()) {
                  ClickableLink(
                    url = task.docUrl,
                    linkText = "API Documentation",
                    icon = Icons.Outlined.Description,
                  )
                }
                if (task.sourceCodeUrl.isNotEmpty()) {
                  ClickableLink(
                    url = task.sourceCodeUrl,
                    linkText = "Example code",
                    icon = Icons.Outlined.Code,
                  )
                }
              }
            }
          }

          // Models available.
          val resources = LocalContext.current.resources
          Text(
            resources.getQuantityString(
              R.plurals.model_list_number_of_models_available,
              models.size + importedModels.size,
              models.size + importedModels.size,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
              Modifier.alpha(0.6f).graphicsLayer {
                alpha = descriptionProgress * 0.6f
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - descriptionProgress)).toPx()
              },
          )
        }
      }

      // Title for recommended models.
      if (!models.isEmpty())
        item(key = "recommendedModelsTitle") {
          Text(
            stringResource(R.string.model_list_recommended_models_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier =
              Modifier.padding(horizontal = 16.dp, vertical = 8.dp).graphicsLayer {
                alpha = modelListProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
              },
          )
        }

      // List of models within a task.
      items(items = models) { model ->
        ModelItem(
          model = model,
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          onModelClicked = onModelClicked,
          modifier =
            Modifier.graphicsLayer {
              alpha = modelListProgress
              translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
            },
        )
      }

      // Title for imported models.
      if (importedModels.isNotEmpty()) {
        item(key = "importedModelsTitle") {
          Text(
            stringResource(R.string.model_list_imported_models_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier =
              Modifier.padding(horizontal = 16.dp)
                .padding(top = 32.dp, bottom = 8.dp)
                .graphicsLayer {
                  alpha = modelListProgress
                  translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
                },
          )
        }
      }

      // List of imported models within a task.
      items(items = importedModels, key = { it.name }) { model ->
        Box {
          ModelItem(
            model = model,
            task = task,
            modelManagerViewModel = modelManagerViewModel,
            onModelClicked = onModelClicked,
            modifier =
              Modifier.graphicsLayer {
                alpha = modelListProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
              },
          )
        }
      }

      item(key = "paddingBottom") { Spacer(modifier = Modifier.height(40.dp)) }
    }

    // Gradient overlay at the bottom.
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(96.dp)
          .background(
            Brush.verticalGradient(
              colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
            )
          )
          .align(Alignment.BottomCenter)
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun ModelListPreview() {
//   val context = LocalContext.current

//   GalleryTheme {
//     ModelList(
//       task = TASK_TEST1,
//       modelManagerViewModel = PreviewModelManagerViewModel(context = context),
//       onModelClicked = {},
//       contentPadding = PaddingValues(all = 16.dp),
//     )
//   }
// }
