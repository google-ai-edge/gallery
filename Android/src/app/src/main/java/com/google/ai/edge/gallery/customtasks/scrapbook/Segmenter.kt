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
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.common.EmptyStateButtonConfig
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke.BrushMode
import kotlinx.coroutines.launch

private const val TAG = "AGSegmenter"
private val FINGER_BRUSH_SIZE = 16.dp
private val LASSO_BRUSH_SIZE = 4.dp
private var curId = 0

/** The tab where users brush strokes on a picture to extract and create cutouts. */
@Composable
fun Segmenter(
  model: Model,
  viewModel: ScrapbookViewModel,
  bottomPadding: Dp,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  var showAddPictureMenu by remember { mutableStateOf(false) }
  var lastStrokeUpdateTs by remember { mutableLongStateOf(0L) }
  var curPicRect by remember { mutableStateOf(Rect.Zero) }
  var showGestureOverlay by remember { mutableStateOf(false) }
  var curCanvasSize by remember { mutableStateOf(Size.Zero) }
  val snackbarHostState = remember { SnackbarHostState() }
  val positiveStrokeColor = MaterialTheme.customColors.positiveStrokeColor
  val negativeStrokeColor = MaterialTheme.customColors.negativeStrokeColor
  val lassoStrokeColor = MaterialTheme.customColors.lassoStrokeColor
  val scope = rememberCoroutineScope()

  val hasMask = uiState.segmenterMaskBitmap != null

  fun clear(clearPic: Boolean = true) {
    if (clearPic) {
      curPicRect = Rect.Zero
    }
    viewModel.setSegmenterMaskBitmap(maskBitmap = null)
    viewModel.clearSegmenterStrokes()
    showGestureOverlay = false
  }

  fun updateBitmap(bitmap: Bitmap) {
    viewModel.setSegmenterBitmap(bitmap = bitmap)
    viewModel.resetSegmenterSession(model = model, bitmap = bitmap)
    clear()
  }

  fun segment(bitmap: Bitmap, onDone: () -> Unit) {
    viewModel.segment(
      id = ++curId,
      model = model,
      bitmap = bitmap,
      picRect = curPicRect,
      overlayColor = Color(0x8012B5CB).toArgb(),
      onResult = { resultId, maskBitmap ->
        if (resultId == curId) {
          viewModel.setSegmenterMaskBitmap(maskBitmap = maskBitmap)
          onDone()
        } else {
          onDone()
        }
      },
    )
  }

  DisposableEffect(Unit) { onDispose { Log.d(TAG, "Disposing segmenter...") } }

  SharedTransitionLayout() {
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
      // Segmentation view.
      if (!showEditor) {
        val bitmap = uiState.segmenterBitmap
        Box(
          modifier = Modifier.fillMaxSize().padding(bottom = TAB_BAR_HEIGHT + bottomPadding),
          contentAlignment = Alignment.BottomCenter,
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            // Info message when no picture is picked.
            if (bitmap == null) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                  icon = ImageVector.vectorResource(R.drawable.add_cutout),
                  titleResId = R.string.create_cutout_empty_state_info,
                  descriptionResId = R.string.create_cutout_empty_state_description,
                  buttonConfig =
                    EmptyStateButtonConfig(
                      buttonLabelResId = R.string.create_new_cutout_button_label,
                      buttonIcon = Icons.Rounded.Add,
                      onButtonClick = { showAddPictureMenu = true },
                      extraContent = {
                        AddPictureDropdown(
                          show = showAddPictureMenu,
                          onSelectBitmap = { updateBitmap(it) },
                          onDismiss = { showAddPictureMenu = false },
                        )
                      },
                    ),
                )
              }
            }

            // Picture picked.
            else {
              // Top button row.
              var selectedSegmentMode by remember { mutableIntStateOf(0) }
              Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              ) {
                // Segmented button for selection mode.
                SingleChoiceSegmentedButtonRow {
                  // Add selection mode.
                  SegmentedButton(
                    selected = selectedSegmentMode == 0,
                    onClick = { selectedSegmentMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp),
                  ) {
                    Box(modifier = Modifier.size(24.dp)) {
                      Icon(
                        imageVector = Icons.Rounded.Gesture,
                        contentDescription = "Add selection",
                        modifier = Modifier.size(20.dp).align(Alignment.BottomStart),
                      )
                      Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
                      )
                    }
                  }

                  // Subtract selection mode.
                  SegmentedButton(
                    selected = selectedSegmentMode == 1,
                    onClick = { selectedSegmentMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp),
                  ) {
                    Box(modifier = Modifier.size(24.dp)) {
                      Icon(
                        imageVector = Icons.Rounded.Gesture,
                        contentDescription = "Subtract selection",
                        modifier = Modifier.size(20.dp).align(Alignment.BottomStart),
                      )
                      Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
                      )
                    }
                  }

                  // Lasso select mode.
                  SegmentedButton(
                    selected = selectedSegmentMode == 2,
                    onClick = { selectedSegmentMode = 2 },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp),
                  ) {
                    Icon(
                      imageVector = ImageVector.vectorResource(R.drawable.lasso),
                      contentDescription = "Lasso select",
                      modifier = Modifier.size(24.dp),
                    )
                  }
                }

                // Undo/Redo/Clear
                Row(verticalAlignment = Alignment.CenterVertically) {
                  // Undo button.
                  SBIconButton(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = stringResource(R.string.cd_undo),
                    enable =
                      uiState.segmenterStrokes.isNotEmpty() &&
                        uiState.segmenterCurrentEndStrokeIndex > 0,
                    onClick = {
                      viewModel.undoStroke()
                      segment(bitmap = bitmap, onDone = {})
                    },
                  )

                  // Redo button.
                  SBIconButton(
                    icon = Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = stringResource(R.string.cd_redo),
                    enable =
                      uiState.segmenterStrokes.isNotEmpty() &&
                        uiState.segmenterCurrentEndStrokeIndex < uiState.segmenterStrokes.size,
                    onClick = {
                      viewModel.redoStroke()
                      segment(bitmap = bitmap, onDone = {})
                    },
                  )

                  // Button to clear selection.
                  SBIconButton(
                    icon = Icons.Rounded.Clear,
                    contentDescription = stringResource(R.string.clear_selection),
                    enable = hasMask,
                    onClick = { clear(clearPic = false) },
                  )
                }
              }

              // Current picture.
              Box(
                modifier =
                  Modifier.sharedBounds(
                      rememberSharedContentState(key = "cutout_candidate"),
                      animatedVisibilityScope = this@AnimatedContent,
                    )
                    // Click empty space to clear segmentation mask.
                    .clickable(
                      // Disable ripple.
                      interactionSource = remember { MutableInteractionSource() },
                      indication = null,
                    ) {
                      clear(clearPic = false)
                    }
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = BUTTON_HEIGHT + 24.dp),
                contentAlignment = Alignment.Center,
              ) {
                Canvas(
                  modifier =
                    Modifier.fillMaxSize()
                      .semantics { testTag = "picture_canvas" }
                      // Detect drag gesture.
                      .pointerInput(bitmap) {
                        detectDragGestures(
                          onDragStart = { offset ->
                            // Start of the drag:
                            // - Add a new stroke.
                            // - Add point to the path.
                            viewModel.addNewSegmenterStroke(getBrushMode(selectedSegmentMode))
                            viewModel.addSegmenterStrokePoint(offset)
                            showGestureOverlay = true
                          },
                          onDrag = { change, dragAmount ->
                            // During drag: Consume the event and add the new point
                            //
                            // Throttle point collection to roughly 60Hz (17ms per update) to avoid
                            // generating an excessive number of points that could degrade
                            // performance.
                            //
                            // Use change.uptimeMillis instead of System.currentTimeMillis() so that
                            // Compose UI tests can deterministically advance event time during
                            // simulated touch inputs.
                            val curTs = change.uptimeMillis
                            if (curTs - lastStrokeUpdateTs > 17) {
                              change.consume()
                              viewModel.addSegmenterStrokePoint(change.position)
                              lastStrokeUpdateTs = curTs
                            }
                          },
                          onDragEnd = {
                            // End of drag: Mark as finished and process the list
                            segment(bitmap = bitmap, onDone = { showGestureOverlay = false })
                          },
                        )
                      }
                      // Detect Tap Gestures
                      .pointerInput(bitmap) {
                        detectTapGestures { offset ->
                          // Tap on empty space to clear.
                          if (!curPicRect.contains(offset)) {
                            clear(clearPic = false)
                          } else {
                            showGestureOverlay = true
                            viewModel.addNewSegmenterStroke(getBrushMode(selectedSegmentMode))
                            viewModel.addSegmenterStrokePoint(offset)
                            segment(bitmap = bitmap, onDone = { showGestureOverlay = false })
                          }
                        }
                      }
                ) {
                  val fitRect = getFitRect(bitmap = bitmap, canvasSize = size, toTop = false)
                  curPicRect = fitRect
                  curCanvasSize = size

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

                // Mask.
                Canvas(modifier = Modifier.fillMaxSize()) {
                  val maskBitmap = uiState.segmenterMaskBitmap
                  if (maskBitmap != null) {
                    val fitRect = getFitRect(bitmap = maskBitmap, canvasSize = size, toTop = false)

                    val roundedRectPath =
                      Path().apply {
                        addRoundRect(
                          RoundRect(
                            rect = fitRect,
                            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                          )
                        )
                      }
                    clipPath(roundedRectPath) {
                      drawImage(
                        image = maskBitmap.asImageBitmap(),
                        srcOffset = IntOffset(0, 0),
                        srcSize = IntSize(maskBitmap.width, maskBitmap.height),
                        dstOffset = IntOffset(fitRect.left.toInt(), fitRect.top.toInt()),
                        dstSize = IntSize(fitRect.width.toInt(), fitRect.height.toInt()),
                        filterQuality = FilterQuality.High,
                      )
                    }
                  }
                }

                // User gesture overlay.
                Canvas(modifier = Modifier.fillMaxSize()) {
                  val (offsets, strokeMode) =
                    if (uiState.segmenterStrokes.isNotEmpty()) {
                      uiState.segmenterStrokes.last()
                    } else {
                      SegmenterStroke(listOf(), BrushMode.UNSPECIFIED)
                    }
                  val strokeColor =
                    when (strokeMode) {
                      BrushMode.POSITIVE -> positiveStrokeColor
                      BrushMode.NEGATIVE -> negativeStrokeColor
                      BrushMode.LASSO -> lassoStrokeColor
                      else -> positiveStrokeColor
                    }
                  val actualBrushSize =
                    if (strokeMode == BrushMode.LASSO) LASSO_BRUSH_SIZE else FINGER_BRUSH_SIZE
                  val pathEffect =
                    if (strokeMode == BrushMode.LASSO) {
                      PathEffect.dashPathEffect(floatArrayOf(15f, 30f), 0f)
                    } else {
                      null
                    }
                  if (offsets.isNotEmpty() && showGestureOverlay) {
                    // Clip the drawing area to the picture bounds to avoid rendering any strokes
                    // outside it.
                    val fitRect = getFitRect(bitmap = bitmap, canvasSize = size, toTop = false)
                    val roundedRectPath =
                      Path().apply {
                        addRoundRect(
                          RoundRect(
                            rect = fitRect,
                            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                          )
                        )
                      }
                    clipPath(roundedRectPath) {
                      // Path.
                      if (offsets.size > 1) {
                        // Create a Path object from the list of Offsets
                        val path =
                          Path().apply {
                            // Start the path at the first point
                            moveTo(offsets.first().x, offsets.first().y)

                            // Draw a line to every subsequent point
                            for (i in 1 until offsets.size) {
                              val point = offsets[i]
                              lineTo(point.x, point.y)
                            }
                          }

                        // Draw the Path with the desired style
                        drawPath(
                          path = path,
                          color = Color.White,
                          alpha = 0.8f,
                          style =
                            Stroke(
                              width = (actualBrushSize + 4.dp).toPx(),
                              cap = StrokeCap.Round,
                              pathEffect = pathEffect,
                            ),
                        )
                        drawPath(
                          path = path,
                          color = strokeColor,
                          alpha = 0.5f,
                          style =
                            Stroke(
                              width = actualBrushSize.toPx(),
                              cap = StrokeCap.Round,
                              pathEffect = pathEffect,
                            ),
                        )
                      }
                      // Dot.
                      else {
                        val tappedOffset = offsets[0]
                        drawCircle(
                          color = Color.White,
                          alpha = 0.8f,
                          radius = (actualBrushSize / 2 + 2.dp).toPx(),
                          center = tappedOffset,
                        )
                        drawCircle(
                          color = strokeColor,
                          alpha = 0.5f,
                          radius = (actualBrushSize / 2).toPx(),
                          center = tappedOffset,
                        )
                      }
                    }
                  }
                }

                // Info message below the bottom edge of the picture.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                  val bottomSpaceHeight =
                    pixelToDp(
                      pixelValue = curCanvasSize.height.toInt() / 2 - curPicRect.height.toInt() / 2
                    )

                  Row(
                    modifier =
                      Modifier.offset(y = -bottomSpaceHeight + 28.dp)
                        .clickable(enabled = false) {}
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                  ) {
                    AnimatedContent(
                      targetState = selectedSegmentMode,
                      transitionSpec = {
                        if (targetState > initialState) {
                          slideInVertically { height -> height } + fadeIn() togetherWith
                            slideOutVertically { height -> -height } + fadeOut()
                        } else {
                          slideInVertically { height -> -height } + fadeIn() togetherWith
                            slideOutVertically { height -> height } + fadeOut()
                        }
                      },
                      label = "InstructionTextAnimation",
                    ) { mode ->
                      val instructionResId =
                        when (mode) {
                          0 -> R.string.segmentation_instruction_add
                          1 -> R.string.segmentation_instruction_subtract
                          else -> R.string.segmentation_instruction_lasso
                        }
                      Text(
                        stringResource(instructionResId),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                      )
                    }
                  }
                }
              }

              // Bottom button row.
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                // Button to change picture.
                Box() {
                  FilledTonalButton(
                    contentPadding = SMALL_BUTTON_CONTENT_PADDING,
                    onClick = { showAddPictureMenu = true },
                  ) {
                    Icon(
                      Icons.Rounded.PhotoLibrary,
                      contentDescription = stringResource(R.string.cd_change_picture),
                      modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    )
                    Text(
                      stringResource(R.string.change_picture),
                      maxLines = 1,
                      autoSize = BUTTON_TEXT_AUTO_SIZE,
                    )
                  }
                  AddPictureDropdown(
                    show = showAddPictureMenu,
                    onSelectBitmap = { updateBitmap(it) },
                    onDismiss = { showAddPictureMenu = false },
                  )
                }

                // Create new cutout button.
                Button(
                  enabled = hasMask,
                  contentPadding = SMALL_BUTTON_CONTENT_PADDING,
                  onClick = {
                    viewModel.createAndUpdateCandidateCutoutInfoFromSegmentedObject(bitmap = bitmap)
                  },
                ) {
                  Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.cd_create_new_cutout),
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                  )
                  Text(
                    stringResource(R.string.create_new_cutout_button_label),
                    maxLines = 1,
                    autoSize = BUTTON_TEXT_AUTO_SIZE,
                  )
                }
              }
            }
          }

          SnackbarHost(hostState = snackbarHostState)
        }
      }
      // Edit view.
      else {
        uiState.candidateCutoutInfo?.let { cutoutInfo ->
          Column(modifier = Modifier.fillMaxSize()) {
            val savedToMyCutoutsMessage =
              stringResource(R.string.snackbar_save_to_my_cutouts_success)
            CutoutEditor(
              index = -1,
              viewModel = viewModel,
              bottomPadding = bottomPadding,
              setCustomNavigateUpCallback = setCustomNavigateUpCallback,
              sharedTransitionScope = this@SharedTransitionLayout,
              animatedContentScope = this@AnimatedContent,
              onCancel = {
                // Hide the editor.
                //
                // The bitmaps inside the candidate cutout info will be cleaned up when editing a
                // new cutout.
                viewModel.setShowCandidateCutoutEditor(show = false)
              },
              onDone = {
                // Hide the editor.
                //
                // The editor already handled saving the edit and update storage/state.
                viewModel.setShowCandidateCutoutEditor(show = false)

                // Show snackbar.
                scope.launch {
                  snackbarHostState.showSnackbar(savedToMyCutoutsMessage, withDismissAction = true)
                }

                // Clear current mask.
                clear(clearPic = false)
              },
            )
          }
        }
      }
    }
  }
}

private fun getBrushMode(selectedSegmentMode: Int): BrushMode {
  return when (selectedSegmentMode) {
    1 -> BrushMode.NEGATIVE
    2 -> BrushMode.LASSO
    else -> BrushMode.POSITIVE
  }
}
