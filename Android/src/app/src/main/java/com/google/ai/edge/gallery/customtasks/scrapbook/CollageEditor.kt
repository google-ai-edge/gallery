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

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.common.EmptyStateButtonConfig
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import kotlin.collections.set
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val CUTOUT_PREVIEW_SIZE = 80
private const val TAG = "AGCollageEditor"

private val FRAME_COLOR = Color(0xFF508AE5)
private val FRAME_BORDER_WIDTH = 3.dp

// The size of the rotate handle (a circle).
private val ROTATE_HANDLE_SIZE = 18.dp

// The size of the resize handle (a rounded rectangle).
// Make it slightly smaller than the rotate handle to make them visually similar on size.
private val RESIZE_HANDLE_SIZE = 16.dp

// Minimum size that a cutout can scale to.
private val MIN_SCALE_SIZE = 36.dp

// Defines the minimum distance, in dp, a cutout must remain inside the
// background's boundaries. This ensures a portion of the cutout is always
// visible and prevents it from being moved completely out of bounds, which
// would make it impossible for the user to select and move it back.
private val CUTOUT_MOVEMENT_CUSHION = 20.dp

// Update interval for dragging/transformations, set to roughly match 60fps (~16.6ms)
private const val MIN_UPDATE_INTERVAL_MS = 16

@Composable
fun CollageEditor(
  viewModel: ScrapbookViewModel,
  bottomPadding: Dp,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  onCutoutsDeleted: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  var showAddPictureMenu by remember { mutableStateOf(false) }
  var showColoredBgEditor by remember { mutableStateOf(false) }
  var curPicRect by remember { mutableStateOf(Rect.Zero) }
  var editCutoutIndex by remember { mutableIntStateOf(-1) }
  val lazyListState = rememberLazyListState()
  val snackbarHostState = remember { SnackbarHostState() }
  var showLongPressMenu by remember { mutableStateOf(false) }
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }
  var longPressedItemIndex by remember { mutableIntStateOf(-1) }
  var longPressMenuXOffset by remember { mutableIntStateOf(0) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  fun updateBitmap(bitmap: Bitmap) {
    viewModel.setCollageEditorBitmap(bitmap = bitmap)
  }

  LaunchedEffect(lazyListState) {
    snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo }
      .collect { visibleItems ->
        for (item in visibleItems) {
          viewModel.loadCutoutBitmap(index = item.index)
        }
      }
  }

  val bitmap = uiState.collageEditorBitmap
  SharedTransitionLayout {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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
      ) { showEditor ->
        if (!showEditor) {
          Column(
            modifier =
              Modifier.padding(bottom = TAB_BAR_HEIGHT + bottomPadding + 4.dp)
                .clickable(enabled = false) {}
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
          ) {
            // Info message when no picture is picked.
            if (bitmap == null) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                  icon = ImageVector.vectorResource(R.drawable.collage),
                  titleResId = R.string.create_collage_empty_state_info,
                  descriptionResId = R.string.create_collage_empty_state_description,
                  buttonConfig =
                    EmptyStateButtonConfig(
                      buttonLabelResId = R.string.create_new_collage_button_label,
                      buttonIcon = Icons.Rounded.Add,
                      onButtonClick = { showAddPictureMenu = true },
                      extraContent = {
                        UpdateBackgroundMenu(
                          show = showAddPictureMenu,
                          onSelectBitmap = { updateBitmap(it) },
                          onDismiss = { showAddPictureMenu = false },
                          onSelectColoredBackground = {
                            showColoredBgEditor = true
                            showAddPictureMenu = false
                          },
                        )
                      },
                    ),
                )
              }
            } else {
              // Action buttons
              val saveSuccessMessage = stringResource(R.string.snackbar_save_to_album_success)
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp),
              ) {
                SBIconButton(
                  icon = Icons.Rounded.Download,
                  contentDescription = stringResource(R.string.cd_save_icon),
                  enable = true,
                ) {
                  viewModel.saveCollageEditorBitmapToAlbum(
                    size = curPicRect.size,
                    onDone = {
                      scope.launch {
                        snackbarHostState.showSnackbar(saveSuccessMessage, withDismissAction = true)
                      }
                    },
                  )
                }
                Box {
                  FilledTonalButton(
                    onClick = { showAddPictureMenu = true },
                    contentPadding = SMALL_BUTTON_CONTENT_PADDING,
                  ) {
                    Icon(
                      Icons.Rounded.PhotoLibrary,
                      contentDescription = stringResource(R.string.cd_change_picture),
                      modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    )
                    Text(stringResource(R.string.change_background))
                  }
                  UpdateBackgroundMenu(
                    show = showAddPictureMenu,
                    onSelectBitmap = { updateBitmap(it) },
                    onDismiss = { showAddPictureMenu = false },
                    onSelectColoredBackground = {
                      showColoredBgEditor = true
                      showAddPictureMenu = false
                    },
                  )
                }
                SBIconButton(
                  icon = Icons.Rounded.Share,
                  contentDescription = stringResource(R.string.cd_share),
                  enable = true,
                ) {
                  viewModel.shareCollageEditorBitmap(
                    context = context,
                    size = curPicRect.size,
                    onDone = {},
                  )
                }
              }

              // Current picture.
              val picWidthDp = pixelToDp(curPicRect.width.toInt())
              val picHeightDp = pixelToDp(curPicRect.height.toInt())
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .pointerInput(Unit) {
                      detectTapGestures { viewModel.setSelectedCutoutCollageItem(itemId = "") }
                    }
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
              ) {
                // Action buttons for the selected cutout item.
                Column(
                  Modifier.padding(top = pixelToDp(curPicRect.height.toInt()))
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                ) {
                  AnimatedVisibility(
                    uiState.selectedCutoutItemId.isNotEmpty(),
                    enter =
                      slideInVertically(animationSpec = FAST_INT_OFFSET_SPEC) { -it / 2 } +
                        fadeIn(animationSpec = tween(150)),
                    exit =
                      slideOutVertically(animationSpec = FAST_INT_OFFSET_SPEC) { -it / 2 } +
                        fadeOut(animationSpec = tween(150)),
                  ) {
                    Box(
                      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                      contentAlignment = Alignment.Center,
                    ) {
                      Row(
                        modifier =
                          Modifier.clip(CircleShape)
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
                          startPadding = 4.dp,
                          endPadding = 0.dp,
                        ) {
                          viewModel.deleteCutoutCollageItem(itemId = uiState.selectedCutoutItemId)
                        }

                        // Duplicate.
                        VerticalIconButton(
                          icon = Icons.Rounded.ContentCopy,
                          startPadding = 0.dp,
                          endPadding = 4.dp,
                        ) {
                          viewModel.cloneCutoutCollageItem(itemId = uiState.selectedCutoutItemId)
                        }
                      }
                    }
                  }
                }

                // Background picture.
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                Canvas(
                  modifier =
                    Modifier.fillMaxSize().padding(bottom = 40.dp).onSizeChanged { size ->
                      canvasSize = size
                      curPicRect =
                        getFitRect(
                          bitmap = bitmap,
                          canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
                          toTop = true,
                        )
                    }
                ) {
                  if (canvasSize == IntSize.Zero) return@Canvas
                  val fitRect = curPicRect

                  // Define the rounded rectangle shape for the mask.
                  // We want the mask to apply to the *area where the image will be drawn*.
                  // So, the mask's bounds should be the same as dstRect.
                  val roundedRectPath =
                    Path().apply {
                      addRoundRect(
                        RoundRect(
                          rect = fitRect,
                          cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        )
                      )
                    }

                  // Draw the bitmap
                  clipPath(path = roundedRectPath) {
                    drawImage(
                      image = bitmap.asImageBitmap(),
                      srcOffset = IntOffset(0, 0),
                      srcSize = IntSize(bitmap.width, bitmap.height),
                      dstOffset = IntOffset(fitRect.left.toInt(), fitRect.top.toInt()),
                      dstSize = IntSize(fitRect.width.toInt(), fitRect.height.toInt()),
                      filterQuality = FilterQuality.High,
                    )
                  }
                }

                // Cutout items.
                Box(
                  modifier =
                    Modifier.clip(RoundedCornerShape(16.dp))
                      .width(picWidthDp)
                      .height(picHeightDp)
                      .align(Alignment.TopCenter),
                  contentAlignment = Alignment.TopStart,
                ) {
                  val itemTransformations = remember {
                    mutableStateMapOf<String, ItemTransformation>()
                  }
                  // Same as itemPositions, but not a state (i.e. updating it won't trigger
                  // re-compose).
                  val tempItemTransformations = remember {
                    mutableMapOf<String, ItemTransformation>()
                  }
                  var lastUpdateTs by remember { mutableLongStateOf(0L) }
                  val minScaleSize = dpToPixel(MIN_SCALE_SIZE)
                  val moveCushion = dpToPixel(CUTOUT_MOVEMENT_CUSHION)
                  for (item in uiState.cutoutCollageItems) {
                    val cutoutInfo = item.cutoutInfo
                    val itemId = item.itemId
                    val curBitmap = cutoutInfo.bitmap
                    if (curBitmap != null) {
                      val width = curBitmap.width
                      val height = curBitmap.height
                      val ratio = width.toFloat() / height.toFloat()
                      if (!itemTransformations.containsKey(itemId)) {
                        tempItemTransformations[itemId] = item.transform.copy()
                        itemTransformations[itemId] = tempItemTransformations[itemId]!!
                      }
                      val transform = tempItemTransformations[itemId]!!
                      val curX = transform.x
                      val curY = transform.y
                      val curScale = transform.scale
                      val itemSelected = uiState.selectedCutoutItemId == itemId
                      TransformationFrame(
                        item = item,
                        x = curX,
                        y = curY,
                        width = curScale * width,
                        height = curScale * height,
                        rotationDegree = transform.rotationDegree,
                        selected = itemSelected,
                        onSelect = { viewModel.setSelectedCutoutCollageItem(itemId = item.itemId) },
                        onMoving = { dragAmount, uptimeMillis ->
                          // Rate-limit re-compose to increase performance.
                          val curId = item.itemId
                          val curTs = uptimeMillis
                          val transform = tempItemTransformations[curId]!!
                          val newPosX =
                            (transform.x + dragAmount.x).coerceIn(
                              -width * transform.scale / 2f + moveCushion,
                              curPicRect.width + width * transform.scale / 2f - moveCushion,
                            )
                          val newPosY =
                            (transform.y + dragAmount.y).coerceIn(
                              -height * transform.scale / 2f + moveCushion,
                              curPicRect.height + height * transform.scale / 2f - moveCushion,
                            )
                          tempItemTransformations[curId] =
                            ItemTransformation(
                              x = newPosX,
                              y = newPosY,
                              scale = transform.scale,
                              rotationDegree = transform.rotationDegree,
                            )
                          if (curTs - lastUpdateTs > MIN_UPDATE_INTERVAL_MS) {
                            itemTransformations[curId] = tempItemTransformations[curId]!!
                            lastUpdateTs = curTs
                          }
                        },
                        onResizing = { dragAmount, uptimeMillis ->
                          val curId = item.itemId
                          val curTs = uptimeMillis
                          val transform = tempItemTransformations[curId]!!
                          val picRatio =
                            curPicRect.width /
                              (if (curPicRect.height == 0f) 0.1f else curPicRect.height)
                          val scaleBaseLength = if (ratio > picRatio) width else height
                          val scaleDragAmount = if (ratio > picRatio) dragAmount.x else dragAmount.y
                          val scaleMaxLength =
                            if (ratio > picRatio) curPicRect.width else curPicRect.height
                          val scaleMinLength =
                            if (width < height) {
                              if (ratio > picRatio) minScaleSize
                              else minScaleSize / width.toFloat() * height.toFloat()
                            } else {
                              if (ratio <= picRatio) minScaleSize
                              else minScaleSize / height.toFloat() * width.toFloat()
                            }
                          val newScale =
                            ((scaleBaseLength * transform.scale + scaleDragAmount * 2).coerceIn(
                              scaleMinLength,
                              scaleMaxLength,
                            ) / scaleBaseLength)
                          tempItemTransformations[curId] =
                            ItemTransformation(
                              x = transform.x,
                              y = transform.y,
                              scale = newScale,
                              rotationDegree = transform.rotationDegree,
                            )
                          if (curTs - lastUpdateTs > MIN_UPDATE_INTERVAL_MS) {
                            itemTransformations[curId] = tempItemTransformations[curId]!!
                            lastUpdateTs = curTs
                          }
                        },
                        onRotating = { centerPosInWin, dragPosInWin, uptimeMillis ->
                          val curId = item.itemId
                          val curTs = uptimeMillis
                          val transform = tempItemTransformations[curId]!!
                          val newAngel =
                            PI -
                              atan2(
                                dragPosInWin.x - centerPosInWin.x,
                                dragPosInWin.y - centerPosInWin.y,
                              )
                          tempItemTransformations[curId] =
                            ItemTransformation(
                              x = transform.x,
                              y = transform.y,
                              scale = transform.scale,
                              rotationDegree = (newAngel / PI * 180f).toFloat(),
                            )
                          if (curTs - lastUpdateTs > MIN_UPDATE_INTERVAL_MS) {
                            itemTransformations[curId] = tempItemTransformations[curId]!!
                            lastUpdateTs = curTs
                          }
                        },
                        onTransformEnd = {
                          val curId = item.itemId
                          itemTransformations[curId] = tempItemTransformations[curId]!!
                          viewModel.updateCutoutCollageItemTransformation(
                            itemId = curId,
                            transform = itemTransformations[curId]!!,
                          )
                        },
                        content = {
                          Image(
                            curBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                          )
                        },
                      )
                    }
                  }
                }
              }

              // Cutout strip.
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))

                // Info message.
                Text(
                  stringResource(R.string.collage_editor_instruction),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Cutout row.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                  val spacePx = dpToPixel(4.dp).toInt()
                  LazyRow(
                    state = lazyListState,
                    modifier = Modifier.height(CUTOUT_PREVIEW_SIZE.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                  ) {
                    itemsIndexed(uiState.cutoutInfos) { index, cutoutInfo ->
                      CutoutPreview(
                        cutoutInfo = cutoutInfo,
                        index = index,
                        modifier =
                          Modifier.sharedElement(
                            rememberSharedContentState(key = "image_$index"),
                            animatedVisibilityScope = this@AnimatedContent,
                          ),
                        topLeftElement = {},
                        // Tap cutout to add to picture.
                        onClick = {
                          viewModel.addSelectedCutoutCollageItem(
                            cutoutInfo = cutoutInfo,
                            centerX = curPicRect.width / 2f,
                            centerY = curPicRect.height / 2f,
                          )
                        },
                        // Long press to edit.
                        onLongClick = {
                          longPressedItemIndex = index
                          showLongPressMenu = true
                          lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0)?.let {
                            firstVisibleItem ->
                            longPressMenuXOffset =
                              if (index == firstVisibleItem.index) {
                                0
                              } else if (index == firstVisibleItem.index + 1) {
                                firstVisibleItem.size + firstVisibleItem.offset + spacePx * 2
                              } else {
                                (index - firstVisibleItem.index - 1) * firstVisibleItem.size +
                                  (firstVisibleItem.size + firstVisibleItem.offset) +
                                  spacePx * (index - firstVisibleItem.index + 1)
                              }
                          }
                        },
                      )
                    }
                  }

                  Box(modifier = Modifier.offset { IntOffset(x = longPressMenuXOffset, y = 0) }) {
                    DropdownMenu(
                      expanded = showLongPressMenu,
                      onDismissRequest = { showLongPressMenu = false },
                      modifier = Modifier,
                    ) {
                      // Edit.
                      if (!uiState.cutoutInfos[longPressedItemIndex].errorLoadingBitmap) {
                        DropdownMenuItem(
                          text = {
                            Row(
                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                              verticalAlignment = Alignment.CenterVertically,
                            ) {
                              Icon(Icons.Rounded.Edit, contentDescription = null)
                              Text(stringResource(R.string.edit))
                            }
                          },
                          onClick = {
                            editCutoutIndex = longPressedItemIndex
                            viewModel.setShowCandidateCutoutEditor(show = true)
                            showLongPressMenu = false
                          },
                        )
                      }
                      // Delete.
                      DropdownMenuItem(
                        text = {
                          Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                          ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Text(stringResource(R.string.delete))
                          }
                        },
                        onClick = {
                          showLongPressMenu = false
                          showConfirmDeleteDialog = true
                        },
                      )
                      // Clone.
                      if (!uiState.cutoutInfos[longPressedItemIndex].errorLoadingBitmap) {
                        DropdownMenuItem(
                          text = {
                            Row(
                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                              verticalAlignment = Alignment.CenterVertically,
                            ) {
                              Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                              Text(stringResource(R.string.clone))
                            }
                          },
                          onClick = {
                            viewModel.cloneCutouts(indices = setOf(longPressedItemIndex))
                            showLongPressMenu = false
                          },
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        } else {
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

  if (showColoredBgEditor) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
    val bitmapSizePx = dpToPixel(screenWidthDp - 12.dp)
    ColoredBackgroundEditor(
      bitmapSize = Size(bitmapSizePx, bitmapSizePx),
      onDismiss = { showColoredBgEditor = false },
      onDone = {
        updateBitmap(it)
        showColoredBgEditor = false
      },
    )
  }

  if (showConfirmDeleteDialog) {
    ConfirmDeleteCutoutsDialog(
      indicesToDelete = setOf(longPressedItemIndex),
      viewModel = viewModel,
      inCollageEditor = true,
      onDeleted = {
        onCutoutsDeleted()
        showConfirmDeleteDialog = false
      },
      onDismiss = { showConfirmDeleteDialog = false },
    )
  }
}

@Composable
private fun TransformationFrame(
  item: CutoutCollageItem,
  x: Float,
  y: Float,
  width: Float,
  height: Float,
  rotationDegree: Float,
  selected: Boolean,
  onSelect: () -> Unit,
  onMoving: (Offset, uptimeMillis: Long) -> Unit,
  onResizing: (dragAmount: Offset, uptimeMillis: Long) -> Unit,
  onRotating: (centerPosInWin: Offset, dragPosInWin: Offset, uptimeMillis: Long) -> Unit,
  onTransformEnd: () -> Unit,
  content: @Composable () -> Unit = {},
) {
  val frameBorderWidthPx = dpToPixel(dpValue = FRAME_BORDER_WIDTH)
  val rotateHandleSizePx = dpToPixel(dpValue = ROTATE_HANDLE_SIZE)
  val resizeHandleSizePx = dpToPixel(dpValue = RESIZE_HANDLE_SIZE)
  val widthDp = pixelToDp(width.toInt())
  val heightDp = pixelToDp(height.toInt())
  var rotationHandleLayoutCoordinate: LayoutCoordinates? by remember { mutableStateOf(null) }
  var centerPositionInWindow by remember { mutableStateOf(Offset.Zero) }
  Box(
    modifier =
      Modifier.offset { IntOffset((x - width / 2).roundToInt(), (y - height / 2).roundToInt()) }
        .testTag("cutout_${item.cutoutInfo.cutout.id}")
        .pointerInput(item) {
          detectDragGestures(
            onDragStart = { onSelect() },
            onDrag = { change, dragAmount ->
              change.consume()
              onMoving(dragAmount, change.uptimeMillis)
            },
            onDragEnd = { onTransformEnd() },
          )
        }
        .rotate(rotationDegree)
        .pointerInput(item) { detectTapGestures { onSelect() } }
        .zIndex(if (selected) 1000f else 0f),
    contentAlignment = Alignment.TopStart,
  ) {
    // Content.
    Box(modifier = Modifier.width(widthDp).height(heightDp)) { content() }

    if (selected) {
      // Border
      Box(
        modifier =
          Modifier.width(widthDp)
            .height(heightDp)
            .border(width = FRAME_BORDER_WIDTH, color = FRAME_COLOR),
        contentAlignment = Alignment.Center,
      ) {
        // An invisible center point, used for calculating the angle of the rotation.
        Box(
          modifier =
            Modifier.size(ROTATE_HANDLE_SIZE)
              .graphicsLayer { alpha = 0f }
              .onGloballyPositioned { position ->
                centerPositionInWindow =
                  position
                    .positionInWindow()
                    .plus(Offset(rotateHandleSizePx / 2f, rotateHandleSizePx / 2f))
              }
        )
      }

      // Bottom-right corner for resizing.
      Box(
        modifier =
          Modifier.size(RESIZE_HANDLE_SIZE)
            .offset {
              IntOffset(
                (width - resizeHandleSizePx / 2f - frameBorderWidthPx / 2f).roundToInt(),
                (height - resizeHandleSizePx / 2f - frameBorderWidthPx).roundToInt(),
              )
            }
            .testTag("resize_handle_${item.cutoutInfo.cutout.id}")
            .clip(RoundedCornerShape(5.dp))
            .pointerInput(item) {
              detectDragGestures(
                onDragStart = { onSelect() },
                onDrag = { change, dragAmount ->
                  change.consume()
                  onResizing(dragAmount, change.uptimeMillis)
                },
                onDragEnd = { onTransformEnd() },
              )
            }
            .background(Color.White)
            .border(
              width = FRAME_BORDER_WIDTH,
              color = FRAME_COLOR,
              shape = RoundedCornerShape(5.dp),
            )
      )

      // Above-top-center for rotating.
      Box(
        modifier =
          Modifier.offset(y = (-16).dp)
            .width(FRAME_BORDER_WIDTH)
            .height(16.dp)
            .background(FRAME_COLOR)
            .align(Alignment.TopCenter)
      )
      Box(
        modifier =
          Modifier.size(ROTATE_HANDLE_SIZE)
            .offset(y = -ROTATE_HANDLE_SIZE / 2f - 16.dp)
            .testTag("rotate_handle_${item.cutoutInfo.cutout.id}")
            .clip(CircleShape)
            .onGloballyPositioned { coord -> rotationHandleLayoutCoordinate = coord }
            .pointerInput(item) {
              detectDragGestures(
                onDragStart = { onSelect() },
                onDrag = { change, dragAmount ->
                  rotationHandleLayoutCoordinate?.let { coord ->
                    val dragPosInWin = coord.localToRoot(change.position)
                    change.consume()
                    onRotating(centerPositionInWindow, dragPosInWin, change.uptimeMillis)
                  }
                },
                onDragEnd = { onTransformEnd() },
              )
            }
            .background(Color.White)
            .border(width = FRAME_BORDER_WIDTH, color = FRAME_COLOR, shape = CircleShape)
            .align(Alignment.TopCenter),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Rounded.Refresh,
          contentDescription = stringResource(R.string.cd_rotate),
          modifier = Modifier.size(ROTATE_HANDLE_SIZE - 8.dp),
          tint = Color(0xffaaaaaa),
        )
      }
    }
  }
}

@Composable
private fun UpdateBackgroundMenu(
  show: Boolean,
  onSelectBitmap: (Bitmap) -> Unit,
  onSelectColoredBackground: () -> Unit,
  onDismiss: () -> Unit,
) {
  AddPictureDropdown(
    show = show,
    onSelectBitmap = onSelectBitmap,
    onDismiss = onDismiss,
    extraContent = {
      DropdownMenuItem(
        text = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Palette,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.colored_background))
          }
        },
        onClick = onSelectColoredBackground,
      )
    },
  )
}
