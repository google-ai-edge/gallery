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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.bodyMediumMedium
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlin.text.toFloat

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
  task: Task?,
  modelManagerViewModel: ModelManagerViewModel,
  onModelClicked: (Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean? = null,
  showDeleteButton: Boolean = true,
  canExpand: Boolean = true,
  showBenchmarkButton: Boolean = false,
  onExpanded: (Boolean) -> Unit = {},
  modelVariants: List<Model> = listOf(),
  tosViewModel: TosViewModel? = null,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val downloadStatus by remember {
    derivedStateOf { modelManagerUiState.modelDownloadStatus[model.name] }
  }

  val isBestOverall = model.bestForTaskIds.contains(task?.id ?: "")
  var isExpanded by remember { mutableStateOf(expanded ?: isBestOverall) }

  val isDownloadFailed = downloadStatus?.status == ModelDownloadStatusType.FAILED
  val isAicore = model.runtimeType == RuntimeType.AICORE

  var boxModifier =
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(size = 12.dp))
      .background(color = MaterialTheme.customColors.taskCardBgColor)
  boxModifier =
    if (canExpand) {
      boxModifier.clickable(
        onClick = {
          if (!model.imported) {
            isExpanded = !isExpanded
            onExpanded(isExpanded)
          } else if (!showBenchmarkButton) {
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
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(
        modifier = Modifier.semantics { isTraversalGroup = true },
        contentAlignment = Alignment.CenterStart,
      ) {
        ModelNameAndStatus(
          model = model,
          task = task,
          downloadStatus = downloadStatus,
          isExpanded = isExpanded,
          modifier = Modifier.fillMaxWidth(),
          showModelSizeAndDownloadProgressLabel = modelVariants.isEmpty(),
        )
        // Model action menu (benchmark, delete), and button to expand/collapse button at the right.
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.align(Alignment.TopEnd)) {
          if (
            modelVariants.isEmpty() && downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
          ) {
            ModelItemActionMenu(
              model = model,
              modelManagerViewModel = modelManagerViewModel,
              showBenchmarkButton = showBenchmarkButton,
              showDeleteButton =
                showDeleteButton && model.localFileRelativeDirPathOverride.isEmpty() && !isAicore,
              onBenchmarkClicked = { onBenchmarkClicked(model) },
              modifier = Modifier.offset(y = (-12).dp),
            )
          }
          if (!model.imported) {
            Icon(
              if (isExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
              contentDescription =
                stringResource(
                  if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon
                ),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.alpha(0.6f),
            )
          }
        }
      }
      AnimatedContent(isExpanded, label = "item_layout_transition") { targetState ->
        // Show description when expanded.
        if (targetState) {
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (model.info.isNotEmpty()) {
              MarkdownText(
                model.info,
                smallFontSize = true,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
              )
            }
            if (isAicore && isDownloadFailed) {
              AICoreAccessPanel()
            }
          }
        }
      }
      SharedTransitionLayout {
        // Show a single download panel if there are no variants.
        if (modelVariants.isEmpty()) {
          AnimatedContent(isExpanded) { targetIsExpanded ->
            DownloadModelPanel(
              task = task,
              model = model,
              downloadStatus = downloadStatus?.status,
              downloadProgress = calculateDownloadProgress(downloadStatus = downloadStatus),
              animatedVisibilityScope = this@AnimatedContent,
              sharedTransitionScope = this@SharedTransitionLayout,
              modifier =
                Modifier.fillMaxWidth().padding(top = if (targetIsExpanded) 12.dp else 0.dp),
              modelManagerViewModel = modelManagerViewModel,
              isExpanded = targetIsExpanded,
              onTryItClicked = { onModelClicked(model) },
              tosViewModel = tosViewModel,
            )
          }
        }
        // Show a list of variants with their name, status, and download panels.
        else {
          Column(
            modifier = Modifier.padding(top = if (isExpanded) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            for (variantModel in listOf(model) + modelVariants) {
              val variantDownloadStatus by remember {
                derivedStateOf { modelManagerUiState.modelDownloadStatus[variantModel.name] }
              }

              val isNotDownloaded =
                variantDownloadStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED

              val isDownloaded = variantDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED

              val showColumnLayout = (!isNotDownloaded && !isDownloaded) || isExpanded

              // Combine the state variables that affect the layout into a single object
              // to be used as the targetState for AnimatedContent below.
              val layoutState =
                remember(showColumnLayout, isExpanded, variantDownloadStatus?.status) {
                  Triple(showColumnLayout, isExpanded, variantDownloadStatus)
                }

              AnimatedContent(targetState = layoutState) {
                (targetShowColumnLayout, targetIsExpanded, targetVariantDownloadStatus) ->
                @Composable
                fun VariantHeader(modifier: Modifier = Modifier) {
                  ModelVariantHeader(
                    variantModel = variantModel,
                    task = task,
                    // Use variantDownloadStatus instead of targetVariantDownloadStatus to update
                    // the download progress because targetVariantDownloadStatus is only updated
                    // when the download status is updated, not when the download progress is
                    // updated.
                    downloadStatus = variantDownloadStatus,
                    isExpanded = targetIsExpanded,
                    modelManagerViewModel = modelManagerViewModel,
                    showBenchmarkButton = showBenchmarkButton,
                    showDeleteButton = showDeleteButton,
                    onBenchmarkClicked = onBenchmarkClicked,
                    modifier = modifier,
                    labelModifier =
                      Modifier.sharedElement(
                        sharedContentState =
                          rememberSharedContentState(key = "variant_label_${variantModel.name}"),
                        animatedVisibilityScope = this@AnimatedContent,
                      ),
                    menuModifier =
                      Modifier.offset(y = if (targetShowColumnLayout) 0.dp else 12.dp)
                        .sharedElement(
                          sharedContentState =
                            rememberSharedContentState(key = "variant_menu_${variantModel.name}"),
                          animatedVisibilityScope = this@AnimatedContent,
                        ),
                  )
                }

                @Composable
                fun VariantDownloadPanel(modifier: Modifier = Modifier) {
                  DownloadModelPanel(
                    task = task,
                    model = variantModel,
                    downloadStatus = targetVariantDownloadStatus?.status,
                    // Use variantDownloadStatus instead of targetVariantDownloadStatus to update
                    // the download progress because targetVariantDownloadStatus is only updated
                    // when the download status is updated, not when the download progress is
                    // updated.
                    downloadProgress =
                      calculateDownloadProgress(downloadStatus = variantDownloadStatus),
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    modifier = modifier,
                    modelManagerViewModel = modelManagerViewModel,
                    isExpanded = targetIsExpanded,
                    onTryItClicked = { onModelClicked(variantModel) },
                    downloadButtonBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  )
                }

                val containerModifier =
                  Modifier.fillMaxWidth()
                    .sharedElement(
                      sharedContentState =
                        rememberSharedContentState(key = "variant_container_${variantModel.name}"),
                      animatedVisibilityScope = this,
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 12.dp, horizontal = 16.dp)

                if (targetShowColumnLayout) {
                  Column(modifier = containerModifier) {
                    VariantHeader(modifier = Modifier.fillMaxWidth())
                    VariantDownloadPanel(
                      modifier =
                        Modifier.fillMaxWidth()
                          .padding(top = 8.dp)
                          .sharedElement(
                            sharedContentState =
                              rememberSharedContentState(key = "panel_${variantModel.name}"),
                            animatedVisibilityScope = this@AnimatedContent,
                          )
                    )
                  }
                } else {
                  Row(
                    modifier = containerModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    VariantHeader(modifier = Modifier.weight(1f))
                    VariantDownloadPanel(
                      modifier =
                        Modifier.padding(start = 8.dp)
                          .sharedElement(
                            sharedContentState =
                              rememberSharedContentState(key = "panel_${variantModel.name}"),
                            animatedVisibilityScope = this@AnimatedContent,
                          )
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Composable for displaying the header of a model variant item, including name, status, and action
 * menu.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ModelVariantHeader(
  variantModel: Model,
  task: Task?,
  downloadStatus: ModelDownloadStatus?,
  isExpanded: Boolean,
  modelManagerViewModel: ModelManagerViewModel,
  showBenchmarkButton: Boolean,
  showDeleteButton: Boolean,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
  labelModifier: Modifier = Modifier,
  menuModifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = labelModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      // Name.
      Text(
        text = variantModel.variantLabel ?: variantModel.name,
        style = bodyMediumMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 1.sp),
      )
      // Status.
      ModelStatusDetails(
        model = variantModel,
        task = task,
        downloadStatus = downloadStatus,
        isExpanded = isExpanded,
      )
    }
    // Model action menu (benchmark, delete)
    if (downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
      ModelItemActionMenu(
        model = variantModel,
        modelManagerViewModel = modelManagerViewModel,
        showBenchmarkButton = showBenchmarkButton,
        showDeleteButton =
          showDeleteButton &&
            variantModel.localFileRelativeDirPathOverride.isEmpty() &&
            variantModel.runtimeType != RuntimeType.AICORE,
        onBenchmarkClicked = { onBenchmarkClicked(variantModel) },
        modifier = menuModifier.offset(y = (-12).dp),
      )
    }
  }
}

/** A reusable composable for displaying an action menu for a model item. */
@Composable
fun ModelItemActionMenu(
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  showBenchmarkButton: Boolean,
  onBenchmarkClicked: () -> Unit,
  showDeleteButton: Boolean,
  modifier: Modifier = Modifier,
) {
  var showMenu by remember { mutableStateOf(false) }
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    IconButton(onClick = { showMenu = true }) {
      Icon(
        Icons.Default.MoreVert,
        contentDescription = stringResource(R.string.cd_more_options),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
      if (showBenchmarkButton) {
        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Outlined.BarChart, contentDescription = null)
              Text(stringResource(R.string.benchmark))
            }
          },
          onClick = {
            onBenchmarkClicked()
            showMenu = false
          },
        )
      }
      if (showDeleteButton) {
        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Outlined.Delete, contentDescription = null)
              Text(stringResource(R.string.delete))
            }
          },
          onClick = {
            showMenu = false
            showConfirmDeleteDialog = true
          },
        )
      }
    }
    if (showConfirmDeleteDialog) {
      ConfirmDeleteModelDialog(
        model = model,
        onConfirm = {
          modelManagerViewModel.deleteModel(model = model)
          showConfirmDeleteDialog = false
        },
        onDismiss = { showConfirmDeleteDialog = false },
      )
    }
  }
}

fun calculateDownloadProgress(downloadStatus: ModelDownloadStatus?): Float {
  val receivedBytes = downloadStatus?.receivedBytes ?: 0L
  val totalBytes = downloadStatus?.totalBytes ?: 0L
  if (totalBytes == 0L) return 0f
  return receivedBytes.toFloat() / totalBytes.toFloat()
}
