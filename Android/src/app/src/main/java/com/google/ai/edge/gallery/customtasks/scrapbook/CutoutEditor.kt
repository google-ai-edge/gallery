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

import android.graphics.BlurMaskFilter
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PanoramaWideAngle
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Cutout
import com.google.ai.edge.gallery.proto.FillMode
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import com.google.ai.edge.gallery.ui.common.chat.ZoomableImage

private const val TAG = "AGCutoutEditor"
const val DEFAULT_BORDER_WIDTH = 0
val DEFAULT_BORDER_COLOR = Color.White
const val MAX_BORDER_WIDTH = 30f
private val DEFAULT_FILL_COLOR = Color(0xFF81D8D0)
private const val DEFAULT_BRUSH_SIZE = 8f
private const val DEFAULT_BRUSH_SOFTNESS = 0f
private val DEFAULT_BRUSH_COLOR = Color(0xFF025D8C)
private val DOODLE_CONTROL_ROW_HEIGHT = COLOR_PICKER_SIZE
private val DOODLE_BRUSH_PREVIEW_WIDTH = COLOR_PICKER_SIZE * 3
private val DOODLE_BRUSH_PREVIEW_HEIGHT = COLOR_PICKER_SIZE * 4
private val TAB_CONTENT_HORIZONTAL_PADDING = 20.dp

@Suppress("ImmutableEnum")
private enum class EditorTab(@StringRes val labelResId: Int, val icon: ImageVector) {
  EDIT(labelResId = R.string.editor_tab_edit, icon = Icons.Rounded.Edit),
  DRAW(labelResId = R.string.editor_tab_draw, icon = Icons.Rounded.Gesture),
}

private val TABS = listOf(EditorTab.EDIT, EditorTab.DRAW)

private enum class EditorControlType {
  ROTATE,
  BORDER,
  FILL,
}

private data class EditorControl(
  val type: EditorControlType,
  val icon: ImageVector,
  @StringRes val contentDescriptionResId: Int,
)

private val CONTROLS: List<EditorControl> =
  listOf(
    EditorControl(
      type = EditorControlType.ROTATE,
      icon = Icons.Rounded.Rotate90DegreesCw,
      contentDescriptionResId = R.string.cd_rotate,
    ),
    EditorControl(
      type = EditorControlType.BORDER,
      icon = Icons.Rounded.PanoramaWideAngle,
      contentDescriptionResId = R.string.cd_add_border,
    ),
    EditorControl(
      type = EditorControlType.FILL,
      icon = Icons.Rounded.FormatColorFill,
      contentDescriptionResId = R.string.cd_fill_color,
    ),
  )

private data class BlurTypeOption(
  val blurType: BlurMaskFilter.Blur,
  @StringRes val labelResId: Int,
  @StringRes val descriptionRedId: Int,
)

private val BLUR_TYPE_OPTIONS =
  listOf(
    BlurTypeOption(
      blurType = BlurMaskFilter.Blur.NORMAL,
      labelResId = R.string.blur_normal_label,
      descriptionRedId = R.string.blur_normal_desc,
    ),
    BlurTypeOption(
      blurType = BlurMaskFilter.Blur.SOLID,
      labelResId = R.string.blur_solid_label,
      descriptionRedId = R.string.blur_solid_desc,
    ),
    BlurTypeOption(
      blurType = BlurMaskFilter.Blur.OUTER,
      labelResId = R.string.blur_outer_label,
      descriptionRedId = R.string.blur_outer_desc,
    ),
    BlurTypeOption(
      blurType = BlurMaskFilter.Blur.INNER,
      labelResId = R.string.blur_inner_label,
      descriptionRedId = R.string.blur_inner_desc,
    ),
  )

private enum class FillModeInfo(@StringRes val labelResId: Int, val fillMode: FillMode) {
  DISABLED(labelResId = R.string.disabled, fillMode = FillMode.FILL_MODE_DISABLED),
  SOLID(labelResId = R.string.solid, fillMode = FillMode.FILL_MODE_SOLID),
  COLORIZE(labelResId = R.string.colorize, fillMode = FillMode.FILL_MODE_COLORIZE),
}

private val FILL_MODES = listOf(FillModeInfo.DISABLED, FillModeInfo.SOLID, FillModeInfo.COLORIZE)

private data class State(var bitmapWidth: Int = 0, var bitmapHeight: Int = 0)

private val STATE = State()

@Composable
fun CutoutEditor(
  index: Int,
  viewModel: ScrapbookViewModel,
  bottomPadding: Dp,
  sharedTransitionScope: SharedTransitionScope,
  animatedContentScope: AnimatedVisibilityScope,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  onCancel: () -> Unit,
  onDone: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()

  uiState.getCutoutInfoAtIndex(index = index)?.let { cutoutInfo ->
    var hasEdited by remember { mutableStateOf(hasEdit(cutout = cutoutInfo.cutout)) }
    var hasEditedSinceOpen by remember { mutableStateOf(false) }
    val bitmap = cutoutInfo.editingBitmap ?: cutoutInfo.bitmap
    if (bitmap != null) {
      STATE.bitmapWidth = bitmap.width
      STATE.bitmapHeight = bitmap.height
      var selectedControl by remember { mutableStateOf(CONTROLS[0]) }
      var curCanvasSize by remember { mutableStateOf(Size.Zero) }
      var initialScale by remember { mutableFloatStateOf(1f) }
      var curRotationDegree by remember { mutableIntStateOf(cutoutInfo.cutout.rotationDegree) }
      var curBorderWidth by remember { mutableIntStateOf(cutoutInfo.cutout.borderWidth) }
      var curBorderColor by remember {
        mutableStateOf(
          if (cutoutInfo.cutout.borderColor == 0) DEFAULT_BORDER_COLOR
          else Color(cutoutInfo.cutout.borderColor)
        )
      }
      var curFillColor by remember {
        mutableStateOf(
          if (cutoutInfo.cutout.fillColor == 0) DEFAULT_FILL_COLOR
          else Color(cutoutInfo.cutout.fillColor)
        )
      }
      var curFillMode by remember { mutableStateOf(cutoutInfo.cutout.fillMode) }
      val curDoodleOffsets = remember {
        mutableStateListOf(
          *(cutoutInfo.cutout.doodleStrokeList
            .toList()
            .map { strokePathProto ->
              StrokePath(
                points = strokePathProto.pointList.map { Offset(it.x, it.y) }.toMutableList(),
                brushColor = strokePathProto.brushColor,
                brushSize = strokePathProto.brushSize,
                brushSoftness = strokePathProto.brushSoftness,
                blurType = BlurMaskFilter.Blur.entries[strokePathProto.blurType],
              )
            }
            .toTypedArray())
        )
      }
      var curDoodleBrushSize by remember { mutableFloatStateOf(DEFAULT_BRUSH_SIZE) }
      var curDoodleBrushSoftness by remember { mutableFloatStateOf(DEFAULT_BRUSH_SOFTNESS) }
      var curDoodleBrushColor by remember { mutableStateOf(DEFAULT_BRUSH_COLOR) }
      var curDoodleBrushSoftnessType by remember { mutableStateOf(BLUR_TYPE_OPTIONS[0]) }
      var curDoodleStrokeIndex by remember {
        mutableIntStateOf(cutoutInfo.cutout.doodleStrokeList.size)
      }
      var showBlurTypeMenu by remember { mutableStateOf(false) }
      var zoomableImageOffsetX by remember { mutableFloatStateOf(0f) }
      var zoomableImageOffsetY by remember { mutableFloatStateOf(0f) }
      var zoomableImageScale by remember { mutableFloatStateOf(1f) }
      val defaultBoundingBoxColor = MaterialTheme.colorScheme.outline
      var showConfirmCloseAlertDialog by remember { mutableStateOf(false) }
      var selectedTabIndex by remember { mutableIntStateOf(0) }

      // -1 is reserved for the cutout candidate from the creation page.
      var revertToOriginCutoutIndex by remember { mutableIntStateOf(-100) }

      // Converts point from canvas coordinate to original bitmap's coordinates.
      fun convertPoint(offset: Offset): Offset {
        val x = offset.x - curCanvasSize.width / 2f + STATE.bitmapWidth / 2f * zoomableImageScale
        val y = offset.y - curCanvasSize.height / 2f + STATE.bitmapHeight / 2f * zoomableImageScale
        val originalBitmapWidth = cutoutInfo.originalBitmap?.width ?: 0
        val originalBitmapHeight = cutoutInfo.originalBitmap?.height ?: 0
        val retOffset =
          revertPointToOriginal(
            xFinal = x,
            yFinal = y,
            wOriginal = (originalBitmapWidth * zoomableImageScale).toInt(),
            hOriginal = (originalBitmapHeight * zoomableImageScale).toInt(),
            wNew = (STATE.bitmapWidth * zoomableImageScale).toInt(),
            hNew = (STATE.bitmapHeight * zoomableImageScale).toInt(),
            rotationDegree = curRotationDegree,
          )
        return retOffset.div(zoomableImageScale)
      }

      fun createEditing(cutoutIndex: Int, boundingBoxColor: Color = Color.Transparent): Editing {
        return Editing(
          index = cutoutIndex,
          rotationDegree = curRotationDegree,
          borderWidth = curBorderWidth,
          borderColor = curBorderColor.toArgb(),
          fillColor = curFillColor.toArgb(),
          fillMode = curFillMode,
          strokePaths = curDoodleOffsets.subList(0, curDoodleStrokeIndex),
          boundingBoxColor = boundingBoxColor.toArgb(),
        )
      }

      fun queueEditing(
        boundingBoxColor: Color = defaultBoundingBoxColor,
        markHasEdited: Boolean = true,
      ) {
        if (markHasEdited) {
          hasEdited = true
          hasEditedSinceOpen = true
        }
        viewModel.queueEditing(
          editing = createEditing(cutoutIndex = index, boundingBoxColor = boundingBoxColor)
        )
      }

      val doodleControlRowHeightPx = dpToPixel(DOODLE_BRUSH_PREVIEW_HEIGHT)
      val doodleControlRowWidthPx = dpToPixel(DOODLE_BRUSH_PREVIEW_WIDTH)
      fun updateBrushPreview() {
        viewModel.generateAndUpdateBrushPreview(
          width = doodleControlRowWidthPx.toInt(),
          height = doodleControlRowHeightPx.toInt(),
          brushColor = curDoodleBrushColor.toArgb(),
          brushSize = curDoodleBrushSize * zoomableImageScale * initialScale,
          softness = curDoodleBrushSoftness,
          blurType = curDoodleBrushSoftnessType.blurType,
        )
      }

      fun handleClickCancel() {
        viewModel.updateCutoutEditingBitmap(index = index, editingBitmap = null)
        onCancel()
      }

      fun checkEditedAndClickCancel() {
        if (hasEditedSinceOpen) {
          showConfirmCloseAlertDialog = true
        } else {
          handleClickCancel()
        }
      }

      LaunchedEffect(Unit) {
        updateBrushPreview()
        queueEditing(markHasEdited = false)

        // Set custom logic when user clicking the "back" button on the app bar.
        setCustomNavigateUpCallback({ checkEditedAndClickCancel() })
      }

      // Try to close the editor when back button is tapped.
      BackHandler { checkEditedAndClickCancel() }

      DisposableEffect(Unit) {
        onDispose {
          Log.d(TAG, "Disposing cutout editor...")
          handleClickCancel()

          // Clear custom logic when user clicking the "back" button on the app bar.
          setCustomNavigateUpCallback(null)
        }
      }

      with(sharedTransitionScope) {
        Column(
          modifier =
            Modifier.fillMaxSize().clickable(enabled = false) {}.padding(bottom = bottomPadding),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          // Top button row.
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Cancel button.
            OutlinedButton(
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
              onClick = { checkEditedAndClickCancel() },
            ) {
              Text(stringResource(R.string.cancel))
            }

            // Revert to original.
            FilledTonalButton(
              enabled = hasEdited,
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
              onClick = { revertToOriginCutoutIndex = index },
            ) {
              Icon(
                Icons.Rounded.UTurnLeft,
                contentDescription = stringResource(R.string.cd_revert_to_original),
                modifier = Modifier.padding(end = 8.dp).size(20.dp).rotate(90f),
              )
              Text(stringResource(R.string.revert_to_original))
            }
          }

          // Bitmap canvas.
          val isDoodleEditor = selectedTabIndex == 1
          val canvasModifier =
            if (index == -1)
              Modifier.sharedBounds(
                rememberSharedContentState(key = "cutout_candidate"),
                animatedVisibilityScope = animatedContentScope,
              )
            else
              Modifier.sharedElement(
                rememberSharedContentState(key = "image_$index"),
                animatedVisibilityScope = animatedContentScope,
              )
          val imageWrapperModifier =
            // Doodling related gesture processing.
            if (isDoodleEditor) {
              Modifier.pointerInput(Unit) {
                detectDragGestures(
                  onDragStart = { offset ->
                    // Clear elements for redo.
                    if (curDoodleStrokeIndex >= 0) {
                      curDoodleOffsets.removeRange(
                        fromIndex = curDoodleStrokeIndex,
                        toIndex = curDoodleOffsets.size,
                      )
                    }
                    val newSegment =
                      StrokePath(
                        points =
                          mutableListOf(
                            convertPoint(
                              offset.minus(Offset(zoomableImageOffsetX, zoomableImageOffsetY))
                            )
                          ),
                        brushColor = curDoodleBrushColor.toArgb(),
                        brushSize = curDoodleBrushSize,
                        brushSoftness = curDoodleBrushSoftness,
                        blurType = curDoodleBrushSoftnessType.blurType,
                      )
                    curDoodleOffsets.add(newSegment)
                    curDoodleStrokeIndex = curDoodleOffsets.size
                  },
                  onDrag = { change, dragAmount ->
                    change.consume()
                    if (curDoodleOffsets.isNotEmpty()) {
                      val lastSegment = curDoodleOffsets.last()
                      lastSegment.points.add(
                        convertPoint(
                          change.position.minus(Offset(zoomableImageOffsetX, zoomableImageOffsetY))
                        )
                      )
                    }
                    queueEditing()
                  },
                  onDragEnd = { queueEditing() },
                )
              }
            } else {
              Modifier
            }
          Column(modifier = canvasModifier.padding(horizontal = 12.dp).fillMaxWidth().weight(1f)) {
            // The canvas.
            Box(
              modifier =
                Modifier.testTag("doodle_canvas")
                  .then(imageWrapperModifier)
                  .fillMaxWidth()
                  .weight(1f)
                  .onSizeChanged {
                    curCanvasSize = it.toSize()
                    initialScale =
                      if (bitmap.width > curCanvasSize.width) {
                        curCanvasSize.width / bitmap.width
                      } else {
                        1f
                      }
                  }
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                  .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                  )
            ) {
              ZoomableImage(
                bitmap = bitmap.asImageBitmap(),
                minScale = 0.5f,
                maxScale = 5f,
                modifier = Modifier.fillMaxSize().testTag("zoomable_image"),
                twoFingerOnly = isDoodleEditor,
                contentScale = ContentScale.Inside,
                resetOnImageUpdate = false,
                onTransformed = { offsetX, offsetY, scale ->
                  val oldZoomableImageScale = zoomableImageScale
                  zoomableImageOffsetX = offsetX
                  zoomableImageOffsetY = offsetY
                  zoomableImageScale = scale

                  if (oldZoomableImageScale != zoomableImageScale) {
                    updateBrushPreview()
                  }
                },
              )
            }

            // Floating panel for undo/redo/clear drawing strokes.
            Column(
              modifier = Modifier.fillMaxWidth().height(50.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              AnimatedVisibility(
                selectedTabIndex == 1,
                enter =
                  slideInVertically(animationSpec = FAST_INT_OFFSET_SPEC) { -it / 2 } +
                    fadeIn(animationSpec = tween(150)),
                exit =
                  slideOutVertically(animationSpec = FAST_INT_OFFSET_SPEC) { -it / 2 } +
                    fadeOut(animationSpec = tween(150)),
              ) {
                Row(
                  modifier =
                    Modifier.clip(CircleShape)
                      .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
                      .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                      )
                ) {
                  // Undo.
                  val enableUndo = curDoodleStrokeIndex > 0
                  VerticalIconButton(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    startPadding = 4.dp,
                    endPadding = 0.dp,
                    enabled = enableUndo,
                  ) {
                    if (curDoodleStrokeIndex > 0) {
                      curDoodleStrokeIndex -= 1
                    }
                    queueEditing()
                  }

                  // Redo.
                  val enableRedo = curDoodleStrokeIndex < curDoodleOffsets.size
                  VerticalIconButton(
                    icon = Icons.AutoMirrored.Rounded.Redo,
                    startPadding = 0.dp,
                    endPadding = 4.dp,
                    enabled = enableRedo,
                  ) {
                    if (curDoodleStrokeIndex < curDoodleOffsets.size) {
                      curDoodleStrokeIndex += 1
                    }
                    queueEditing()
                  }

                  // Clear.
                  VerticalIconButton(
                    icon = Icons.Rounded.ClearAll,
                    startPadding = 0.dp,
                    endPadding = 4.dp,
                    enabled = curDoodleOffsets.isNotEmpty(),
                  ) {
                    curDoodleOffsets.clear()
                    curDoodleStrokeIndex = 0
                    queueEditing()
                  }
                }
              }
              if (selectedTabIndex == 1) {}
            }
          }

          // The bottom editing section.
          Column(modifier = Modifier.height(240.dp)) {
            // Tab bar to switch between "Edit Cutout" and "Draw".
            PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = Color.Transparent) {
              TABS.forEachIndexed { index, tab ->
                Tab(
                  selected = selectedTabIndex == index,
                  onClick = { selectedTabIndex = index },
                  text = {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                      val titleColor =
                        if (selectedTabIndex == index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                      Icon(tab.icon, contentDescription = null, tint = titleColor)
                      Text(stringResource(tab.labelResId), color = titleColor)
                    }
                  },
                )
              }
            }

            // Tab content.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
              AnimatedContent(
                selectedTabIndex,
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                  if (targetState > initialState) {
                    slideInHorizontally { 40 } + fadeIn() togetherWith
                      slideOutHorizontally { -40 } + fadeOut()
                  } else {
                    slideInHorizontally { -40 } + fadeIn() togetherWith
                      slideOutHorizontally { 40 } + fadeOut()
                  }
                },
              ) { tabIndex ->
                when (tabIndex) {
                  // Edit tab.
                  0 -> {
                    Column(verticalArrangement = Arrangement.Center) {
                      // Icons to pick editor.
                      Row(
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                      ) {
                        for (control in CONTROLS) {
                          IconButton(onClick = { selectedControl = control }) {
                            Icon(
                              control.icon,
                              contentDescription = stringResource(control.contentDescriptionResId),
                              tint =
                                if (control.type == selectedControl.type)
                                  MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                          }
                        }
                      }

                      // Editor controls.
                      Column(
                        modifier =
                          Modifier.padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING)
                            .padding(top = 8.dp, bottom = 16.dp)
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable(enabled = false) {},
                        verticalArrangement = Arrangement.Top,
                      ) {
                        AnimatedContent(
                          selectedControl.type,
                          transitionSpec = {
                            if (targetState > initialState) {
                              slideInHorizontally { 40 } + fadeIn() togetherWith
                                slideOutHorizontally { -40 } + fadeOut()
                            } else {
                              slideInHorizontally { -40 } + fadeIn() togetherWith
                                slideOutHorizontally { 40 } + fadeOut()
                            }
                          },
                        ) { type ->
                          // Editor control.
                          when (type) {
                            // Rotate.
                            EditorControlType.ROTATE -> {
                              Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.BottomCenter,
                              ) {
                                Column(
                                  modifier = Modifier.fillMaxWidth(),
                                  verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                  Text(
                                    "${stringResource(R.string.rotate)}: $curRotationDegree°",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                  )

                                  Box(
                                    modifier = Modifier.height(COLOR_PICKER_SIZE),
                                    contentAlignment = Alignment.Center,
                                  ) {
                                    Slider(
                                      value = curRotationDegree.toFloat(),
                                      onValueChange = {
                                        curRotationDegree = it.toInt()
                                        queueEditing()
                                      },
                                      valueRange = -180f..180f,
                                      modifier =
                                        Modifier.fillMaxWidth()
                                          .height(24.dp)
                                          .testTag("rotation_slider"),
                                    )
                                  }
                                }
                              }
                            }

                            // Fill.
                            EditorControlType.FILL -> {
                              Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.BottomCenter,
                              ) {
                                Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  verticalAlignment = Alignment.Top,
                                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                  // Fill mode.
                                  Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.Top,
                                  ) {
                                    Text(
                                      stringResource(R.string.fill_mode),
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      style = MaterialTheme.typography.labelLarge,
                                    )
                                    SingleChoiceSegmentedButtonRow(
                                      modifier = Modifier.height(32.dp)
                                    ) {
                                      FILL_MODES.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                          shape =
                                            SegmentedButtonDefaults.itemShape(
                                              index = index,
                                              count = FILL_MODES.size,
                                            ),
                                          contentPadding =
                                            PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                          onClick = {
                                            curFillMode = mode.fillMode
                                            queueEditing()
                                          },
                                          selected = mode.fillMode == curFillMode,
                                          label = {
                                            Text(
                                              stringResource(mode.labelResId),
                                              style = MaterialTheme.typography.labelMedium,
                                              maxLines = 1,
                                              overflow = TextOverflow.Ellipsis,
                                            )
                                          },
                                        )
                                      }
                                    }
                                  }
                                  // Fill color.
                                  Column(
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.Top,
                                  ) {
                                    Text(
                                      stringResource(R.string.fill_color),
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      style = MaterialTheme.typography.labelLarge,
                                    )
                                    Box(
                                      modifier = Modifier.height(32.dp),
                                      contentAlignment = Alignment.CenterStart,
                                    ) {
                                      ColorPicker(
                                        modifier = Modifier.testTag("fill_color_picker"),
                                        initialColor = curFillColor,
                                        onColorUpdated = {
                                          curFillColor = it
                                          queueEditing()
                                        },
                                      )
                                    }
                                  }
                                }
                              }
                            }

                            // Border.
                            EditorControlType.BORDER -> {
                              Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.BottomCenter,
                              ) {
                                Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  verticalAlignment = Alignment.CenterVertically,
                                  horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                  Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                  ) {
                                    Text(
                                      "${stringResource(R.string.border_width)}: ${curBorderWidth}",
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      style = MaterialTheme.typography.labelLarge,
                                    )
                                    Slider(
                                      value = curBorderWidth.toFloat(),
                                      onValueChange = {
                                        if (it.toInt() != curBorderWidth) {
                                          curBorderWidth = it.toInt()
                                          queueEditing()
                                        }
                                      },
                                      valueRange = 0f..MAX_BORDER_WIDTH,
                                      modifier =
                                        Modifier.fillMaxWidth()
                                          .height(24.dp)
                                          .testTag("border_width_slider"),
                                      steps = 20,
                                      colors = getSteppedSliderColors(),
                                    )
                                  }
                                  Spacer(modifier = Modifier.width(16.dp))
                                  Column(
                                    modifier = Modifier.width(IntrinsicSize.Max),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                  ) {
                                    Text(
                                      stringResource(R.string.color),
                                      modifier = Modifier.fillMaxWidth(),
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      style = MaterialTheme.typography.labelLarge,
                                      textAlign = TextAlign.Center,
                                    )
                                    Box(
                                      modifier = Modifier.fillMaxWidth(),
                                      contentAlignment = Alignment.CenterStart,
                                    ) {
                                      ColorPicker(
                                        modifier = Modifier.testTag("border_color_picker"),
                                        initialColor = curBorderColor,
                                        onColorUpdated = {
                                          curBorderColor = it
                                          queueEditing()
                                        },
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

                  // Draw tab.
                  1 -> {
                    Column(verticalArrangement = Arrangement.Center) {
                      Row(
                        modifier =
                          Modifier.fillMaxWidth()
                            .padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING)
                      ) {
                        // Preview
                        //
                        // Show a darker background color for low contrast ratio.
                        val fgColor = curDoodleBrushColor
                        var bgColor = MaterialTheme.colorScheme.surface
                        val contrastRatio = calculateContrastRatio(fgColor, bgColor)
                        if (contrastRatio < MIN_CONTRAST_THRESHOLD) {
                          bgColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        Column(
                          modifier =
                            Modifier.height(DOODLE_BRUSH_PREVIEW_HEIGHT)
                              .width(DOODLE_BRUSH_PREVIEW_WIDTH)
                              .clip(RoundedCornerShape(8.dp))
                              .background(bgColor)
                              .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp),
                              ),
                          horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                          Text(
                            stringResource(R.string.preview),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp),
                          )
                          uiState.brushPreviewBitmap?.let { curBrushPreviewBitmap ->
                            Image(
                              curBrushPreviewBitmap.asImageBitmap(),
                              modifier = Modifier.weight(1f),
                              contentDescription = null,
                              contentScale = ContentScale.None,
                            )
                          }
                        }
                        // Labels for controls.
                        Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp)) {
                          // Color.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.CenterStart,
                          ) {
                            Text(
                              stringResource(R.string.color),
                              style = MaterialTheme.typography.labelMedium,
                            )
                          }
                          // Size.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.CenterStart,
                          ) {
                            Text(
                              stringResource(R.string.brush_size),
                              style = MaterialTheme.typography.labelMedium,
                            )
                          }
                          // Softness.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.CenterStart,
                          ) {
                            Text(
                              stringResource(R.string.brush_softness),
                              style = MaterialTheme.typography.labelMedium,
                            )
                          }
                          // Blur type.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.CenterStart,
                          ) {
                            Text(
                              stringResource(R.string.blur_type),
                              style = MaterialTheme.typography.labelMedium,
                            )
                          }
                        }
                        // Controls.
                        Column(modifier = Modifier.weight(1f)) {
                          // Color.
                          Row(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                          ) {
                            ColorPicker(
                              modifier = Modifier.testTag("doodle_color_picker"),
                              initialColor = curDoodleBrushColor,
                              onColorUpdated = {
                                curDoodleBrushColor = it
                                updateBrushPreview()
                              },
                            )
                          }
                          // Size.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.Center,
                          ) {
                            Slider(
                              value = curDoodleBrushSize,
                              onValueChange = {
                                curDoodleBrushSize = it
                                updateBrushPreview()
                              },
                              valueRange = 1f..36f,
                              modifier =
                                Modifier.testTag("size_slider").fillMaxWidth().height(24.dp),
                            )
                          }
                          // Softness.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT),
                            contentAlignment = Alignment.Center,
                          ) {
                            Slider(
                              value = curDoodleBrushSoftness,
                              onValueChange = {
                                curDoodleBrushSoftness = it
                                updateBrushPreview()
                              },
                              valueRange = 0f..2f,
                              modifier =
                                Modifier.testTag("softness_slider").fillMaxWidth().height(24.dp),
                            )
                          }
                          // Softness type.
                          Box(
                            modifier = Modifier.height(DOODLE_CONTROL_ROW_HEIGHT).fillMaxWidth(),
                            contentAlignment = Alignment.CenterEnd,
                          ) {
                            CompactButton(
                              type = CompactButtonType.Lowkey,
                              textResId = curDoodleBrushSoftnessType.labelResId,
                              height = 20.dp,
                              onClick = { showBlurTypeMenu = true },
                            )
                            DropdownMenu(
                              expanded = showBlurTypeMenu,
                              onDismissRequest = { showBlurTypeMenu = false },
                            ) {
                              for (blurType in BLUR_TYPE_OPTIONS) {
                                DropdownMenuItem(
                                  text = {
                                    Column {
                                      Text(
                                        stringResource(blurType.labelResId),
                                        style = MaterialTheme.typography.labelMedium,
                                        color =
                                          if (curDoodleBrushSoftnessType == blurType)
                                            MaterialTheme.colorScheme.primary
                                          else MaterialTheme.colorScheme.onSurface,
                                      )
                                      Text(
                                        stringResource(blurType.descriptionRedId),
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                          if (curDoodleBrushSoftnessType == blurType)
                                            MaterialTheme.colorScheme.secondary
                                          else MaterialTheme.colorScheme.onSurfaceVariant,
                                      )
                                    }
                                  },
                                  onClick = {
                                    showBlurTypeMenu = false
                                    curDoodleBrushSoftnessType = blurType
                                    updateBrushPreview()
                                  },
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

            // Done/Create
            Button(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
              onClick = {
                viewModel.saveEditing(editing = createEditing(cutoutIndex = index), onDone = onDone)
              },
            ) {
              Text(stringResource(R.string.save_cutout))
            }
          }
        }
      }

      if (revertToOriginCutoutIndex >= -1) {
        AlertDialog(
          onDismissRequest = {},
          title = { Text(stringResource(R.string.revert_to_original)) },
          text = { Text(stringResource(R.string.revert_confirmation_msg)) },
          confirmButton = {
            Button(
              onClick = {
                viewModel.revertCutoutToOriginal(index = revertToOriginCutoutIndex)
                curRotationDegree = 0
                curBorderWidth = DEFAULT_BORDER_WIDTH
                curBorderColor = DEFAULT_BORDER_COLOR
                curFillMode = FillMode.FILL_MODE_DISABLED
                curFillColor = DEFAULT_FILL_COLOR
                curDoodleOffsets.clear()
                curDoodleStrokeIndex = 0
                curDoodleBrushColor = DEFAULT_BRUSH_COLOR
                curDoodleBrushSize = DEFAULT_BRUSH_SIZE
                curDoodleBrushSoftness = DEFAULT_BRUSH_SOFTNESS
                curDoodleBrushSoftnessType = BLUR_TYPE_OPTIONS[0]
                updateBrushPreview()
                revertToOriginCutoutIndex = -100
                hasEdited = false
                hasEditedSinceOpen = false
              }
            ) {
              Text(stringResource(R.string.revert))
            }
          },
          dismissButton = {
            OutlinedButton(onClick = { revertToOriginCutoutIndex = -100 }) {
              Text(stringResource(R.string.cancel))
            }
          },
        )
      }

      if (showConfirmCloseAlertDialog) {
        AlertDialog(
          onDismissRequest = {},
          title = { Text(stringResource(R.string.unsaved_changes)) },
          text = { Text(stringResource(R.string.unsaved_changes_message)) },
          confirmButton = {
            Button(
              onClick = {
                showConfirmCloseAlertDialog = false
                handleClickCancel()
              }
            ) {
              Text(stringResource(R.string.unsaved_changes_exit))
            }
          },
          dismissButton = {
            OutlinedButton(onClick = { showConfirmCloseAlertDialog = false }) {
              Text(stringResource(R.string.unsaved_changes_stay))
            }
          },
        )
      }
    }
  }
}

private fun revertPointToOriginal(
  xFinal: Float,
  yFinal: Float,
  wOriginal: Int,
  hOriginal: Int,
  wNew: Int,
  hNew: Int,
  rotationDegree: Int,
): Offset {

  // 1. Calculate transformation components
  val rotationRad = Math.toRadians(rotationDegree.toDouble()).toFloat()

  val tx = (wNew - wOriginal) / 2f
  val ty = (hNew - hOriginal) / 2f

  val cx = wNew / 2f
  val cy = hNew / 2f

  // 2. Inverse Rotation (Pivot around new canvas center)
  val cosTheta = kotlin.math.cos(-rotationRad) // cos(-x) = cos(x)
  val sinTheta = kotlin.math.sin(-rotationRad) // sin(-x) = -sin(x)

  val deltaX = xFinal - cx
  val deltaY = yFinal - cy

  val xMid = cx + (deltaX * cosTheta - deltaY * sinTheta)
  val yMid = cy + (deltaX * sinTheta + deltaY * cosTheta)

  // 3. Inverse Translation
  val xOriginal = xMid - tx
  val yOriginal = yMid - ty

  return Offset(xOriginal, yOriginal)
}

private fun hasEdit(cutout: Cutout): Boolean {
  return cutout.rotationDegree != 0 ||
    cutout.borderWidth > 0 ||
    cutout.fillMode != FillMode.FILL_MODE_DISABLED ||
    cutout.doodleStrokeList.isNotEmpty()
}
