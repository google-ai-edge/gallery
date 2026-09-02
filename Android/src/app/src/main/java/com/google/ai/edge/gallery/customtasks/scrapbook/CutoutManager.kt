/*
 * Copyright 2026 Google LLC
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
package com.google.ai.edge.gallery.customtasks.scrapbook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.South
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.launch

private const val TAG = "AGCutoutsManager"

@Composable
fun CutoutManager(
  viewModel: ScrapbookViewModel,
  bottomPadding: Dp,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  onCutoutsDeleted: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  var editCutoutIndex by remember { mutableIntStateOf(-1) }
  var showCollageMenu by remember { mutableStateOf(false) }
  var showGridMakerDialog by remember { mutableStateOf(false) }
  var gridType by remember { mutableStateOf(GridType.Horizontal) }
  var showDeleteCutoutsConfirmation by remember { mutableStateOf(false) }
  var multiSelectMode by remember { mutableStateOf(false) }
  val lazyGridState = rememberLazyGridState()
  val snackbarHostState = remember { SnackbarHostState() }
  val allCutoutsSelected = uiState.cutoutInfos.size == uiState.selectedCutoutIndices.size
  val scope = rememberCoroutineScope()

  fun exitMultiSelectMode() {
    multiSelectMode = false
    viewModel.deselectAllCutouts()
  }

  // Exit multi-select mode when system back button is tapped.
  BackHandler(enabled = multiSelectMode) { exitMultiSelectMode() }

  // Set custom logic when user clicks the "back" button on the app bar.
  DisposableEffect(multiSelectMode) {
    if (multiSelectMode) {
      setCustomNavigateUpCallback { exitMultiSelectMode() }
    } else {
      setCustomNavigateUpCallback(null)
    }
    onDispose { setCustomNavigateUpCallback(null) }
  }

  LaunchedEffect(lazyGridState) {
    snapshotFlow { lazyGridState.layoutInfo.visibleItemsInfo }
      .collect { visibleItems ->
        for (item in visibleItems) {
          viewModel.loadCutoutBitmap(index = item.index)
        }
      }
  }

  SharedTransitionLayout {
    Box(
      modifier = Modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize(),
      contentAlignment = Alignment.BottomCenter,
    ) {
      AnimatedContent(
        uiState.showCandidateCutoutEditor,
        transitionSpec = {
          // Show editor.
          if (targetState) {
            slideInVertically { 40 } + fadeIn() togetherWith slideOutVertically { -40 } + fadeOut()
          }
          // Hide editor
          else {
            slideInVertically { -40 } + fadeIn() togetherWith slideOutVertically { 40 } + fadeOut()
          }
        },
      ) { editView ->
        // Grid view.
        if (!editView) {
          Column(
            modifier = Modifier.fillMaxSize().padding(bottom = TAB_BAR_HEIGHT + bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (uiState.cutoutInfos.isNotEmpty()) {
              Row(
                modifier =
                  Modifier.height(48.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 8.dp, end = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                AnimatedContent(
                  multiSelectMode,
                  transitionSpec = {
                    if (multiSelectMode) {
                      slideInVertically(initialOffsetY = { height -> height }) togetherWith
                        slideOutVertically(targetOffsetY = { height -> -height })
                    } else {
                      slideInVertically(initialOffsetY = { height -> -height }) togetherWith
                        slideOutVertically(targetOffsetY = { height -> height })
                    }
                  },
                ) { curMultipleSelectMode ->
                  // Multi-select related controls.
                  if (curMultipleSelectMode) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      // Select/deselect all checkbox.
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier =
                          Modifier.clip(CircleShape)
                            .clickable {
                              if (allCutoutsSelected) {
                                viewModel.deselectAllCutouts()
                              } else {
                                viewModel.selectAllCutouts()
                              }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                      ) {
                        CheckCircle(checked = allCutoutsSelected)
                        Text(
                          stringResource(R.string.all),
                          style = MaterialTheme.typography.labelLarge,
                        )
                      }

                      // Show how many are selected with a cancel button.
                      Row(
                        modifier =
                          Modifier.clip(CircleShape)
                            .clickable { exitMultiSelectMode() }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        Text(
                          "${uiState.selectedCutoutIndices.size}",
                          style = MaterialTheme.typography.labelMedium,
                        )
                        Icon(
                          Icons.Rounded.Close,
                          modifier = Modifier.size(16.dp),
                          contentDescription = stringResource(R.string.cd_exit_multi_select),
                          tint = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                    }
                  }
                  // Single-tap mode.
                  else {
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      Text(
                        pluralStringResource(
                          R.plurals.cutouts_count_label,
                          uiState.cutoutInfos.size,
                          uiState.cutoutInfos.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                      )
                      Text(
                        stringResource(R.string.cutout_manager_info_message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                      )
                    }
                  }
                }
              }

              Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
              ) {
                // Cutout grid.
                LazyVerticalGrid(
                  columns = GridCells.Fixed(3),
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                  state = lazyGridState,
                  modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                  contentPadding =
                    PaddingValues(top = 16.dp, bottom = if (multiSelectMode) 68.dp else 16.dp),
                ) {
                  itemsIndexed(uiState.cutoutInfos) { index, cutoutInfo ->
                    CutoutPreview(
                      cutoutInfo = cutoutInfo,
                      index = index,
                      selected = uiState.selectedCutoutIndices.contains(index),
                      modifier =
                        Modifier.sharedElement(
                          rememberSharedContentState(key = "image_$index"),
                          animatedVisibilityScope = this@AnimatedContent,
                        ),
                      topLeftElement = {
                        // Checkbox for selection.
                        Column() {
                          AnimatedVisibility(
                            multiSelectMode,
                            enter =
                              scaleIn(initialScale = 0f, transformOrigin = TransformOrigin.Center),
                            exit =
                              scaleOut(
                                targetScale = 0.6f,
                                transformOrigin = TransformOrigin.Center,
                              ) + fadeOut(),
                          ) {
                            CheckCircle(
                              modifier = Modifier.padding(top = 8.dp, start = 8.dp),
                              checked = uiState.selectedCutoutIndices.contains(index),
                            )
                          }
                        }
                      },
                      onClick = {
                        if (!multiSelectMode) {
                          if (!cutoutInfo.errorLoadingBitmap) {
                            editCutoutIndex = index
                            viewModel.setShowCandidateCutoutEditor(show = true)
                          }
                        } else {
                          viewModel.toggleSelectedCutout(index = index)
                        }
                      },
                      onLongClick = {
                        if (!multiSelectMode) {
                          multiSelectMode = true
                          viewModel.toggleSelectedCutout(index = index)
                        } else {
                          editCutoutIndex = index
                          viewModel.setShowCandidateCutoutEditor(show = true)
                        }
                      },
                    )
                  }
                }

                // Action buttons.
                Column() {
                  AnimatedVisibility(
                    multiSelectMode,
                    enter =
                      slideInVertically(animationSpec = tween(durationMillis = 150)) { it / 2 } +
                        fadeIn(animationSpec = tween(durationMillis = 150)),
                    exit =
                      slideOutVertically(animationSpec = tween(durationMillis = 150)) { it / 2 } +
                        fadeOut(animationSpec = tween(durationMillis = 150)),
                  ) {
                    Row(
                      modifier =
                        Modifier.padding(bottom = 8.dp)
                          .clip(CircleShape)
                          .background(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                          )
                          .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                          )
                    ) {
                      // Delete.
                      VerticalIconButton(
                        icon = Icons.Rounded.Delete,
                        textResId = R.string.delete,
                        enabled = uiState.selectedCutoutIndices.isNotEmpty(),
                        width = 72.dp,
                        startPadding = 16.dp,
                      ) {
                        showDeleteCutoutsConfirmation = true
                      }

                      // Save
                      val saveSuccessMessage =
                        pluralStringResource(
                          R.plurals.snackbar_save_cutouts_to_album_success,
                          uiState.selectedCutoutIndices.size,
                          uiState.selectedCutoutIndices.size,
                        )
                      VerticalIconButton(
                        icon = Icons.Rounded.Download,
                        textResId = R.string.save,
                        enabled =
                          uiState.selectedCutoutIndices.toList().any {
                            !uiState.cutoutInfos[it].errorLoadingBitmap
                          },
                      ) {
                        viewModel.saveCutoutsToAlbum(
                          indices = uiState.selectedCutoutIndices,
                          onDone = {
                            scope.launch {
                              snackbarHostState.showSnackbar(
                                saveSuccessMessage,
                                withDismissAction = true,
                              )
                            }
                          },
                        )
                      }

                      // Clone.
                      VerticalIconButton(
                        icon = Icons.Rounded.ContentCopy,
                        textResId = R.string.clone,
                        enabled =
                          uiState.selectedCutoutIndices.toList().any {
                            !uiState.cutoutInfos[it].errorLoadingBitmap
                          },
                      ) {
                        viewModel.cloneCutouts(indices = uiState.selectedCutoutIndices)
                      }

                      // Grid.
                      Box(contentAlignment = Alignment.Center) {
                        VerticalIconButton(
                          icon = Icons.Rounded.GridView,
                          textResId = R.string.collage,
                          width = 72.dp,
                          enabled =
                            uiState.selectedCutoutIndices
                              .toList()
                              .filter { !uiState.cutoutInfos[it].errorLoadingBitmap }
                              .size > 1,
                          endPadding = 16.dp,
                        ) {
                          // Default to "vertical" type.
                          gridType = GridType.Vertical
                          showGridMakerDialog = true

                          // showCollageMenu = true
                        }

                        // Dropdown menu for collage.
                        DropdownMenu(
                          expanded = showCollageMenu,
                          onDismissRequest = { showCollageMenu = false },
                          modifier = Modifier.align(alignment = Alignment.BottomEnd),
                        ) {
                          // Vertical
                          DropdownMenuItem(
                            text = {
                              Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                              ) {
                                Icon(
                                  Icons.Rounded.South,
                                  contentDescription = null,
                                  tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(stringResource(R.string.vertical))
                              }
                            },
                            onClick = {
                              gridType = GridType.Vertical
                              showGridMakerDialog = true
                              showCollageMenu = false
                            },
                          )
                          // Horizontal
                          DropdownMenuItem(
                            text = {
                              Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                              ) {
                                Icon(
                                  Icons.Rounded.East,
                                  contentDescription = null,
                                  tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(stringResource(R.string.horizontal))
                              }
                            },
                            onClick = {
                              gridType = GridType.Horizontal
                              showGridMakerDialog = true
                              showCollageMenu = false
                            },
                          )
                        }
                      }
                    }
                  }
                }
              }
            } else {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                  stringResource(R.string.no_cutouts_collected),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
        // Edit view.
        else {
          CutoutEditor(
            index = editCutoutIndex,
            viewModel = viewModel,
            bottomPadding = bottomPadding,
            setCustomNavigateUpCallback = setCustomNavigateUpCallback,
            sharedTransitionScope = this@SharedTransitionLayout,
            animatedContentScope = this@AnimatedContent,
            onCancel = { viewModel.setShowCandidateCutoutEditor(show = false) },
            onDone = { viewModel.setShowCandidateCutoutEditor(show = false) },
          )
        }
      }

      SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
  }

  // Grid maker.
  if (showGridMakerDialog) {
    val saveSuccessMessage = stringResource(R.string.snackbar_save_to_album_success)
    GridMakerDialog(
      type = gridType,
      viewModel = viewModel,
      onDismiss = { showGridMakerDialog = false },
      onSaved = {
        // Show a snack bar for successful download.
        scope.launch {
          snackbarHostState.showSnackbar(saveSuccessMessage, withDismissAction = true)
        }
        showGridMakerDialog = false
      },
    )
  }

  // Delete cutouts confirmation.
  if (showDeleteCutoutsConfirmation) {
    ConfirmDeleteCutoutsDialog(
      indicesToDelete = uiState.selectedCutoutIndices,
      viewModel = viewModel,
      inCollageEditor = false,
      onDeleted = {
        onCutoutsDeleted()
        showDeleteCutoutsConfirmation = false
      },
      onDismiss = { showDeleteCutoutsConfirmation = false },
    )
  }
}
