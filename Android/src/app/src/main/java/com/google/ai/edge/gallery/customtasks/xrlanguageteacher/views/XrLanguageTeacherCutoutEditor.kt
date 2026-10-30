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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.segmentation.SEGMENTATION_STROKE_POINT_INTERVAL_MS
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmentationBrushMode
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmentationBrushModeButtons
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmentationEditorDraw
import com.google.ai.edge.gallery.customtasks.common.segmentation.instructionResId
import com.google.ai.edge.gallery.customtasks.common.segmentation.segmentationStrokeColor
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.NormalizedPoint
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.CutoutEditorMessage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.CutoutEditorUiState

/** The actions of the cutout editor, forwarded to its state holder. */
internal class CutoutEditorActions(
  val onCancel: () -> Unit,
  val onDone: () -> Unit,
  val onResetToAuto: () -> Unit,
  val onUseOriginalPhoto: () -> Unit,
  val onBrushModeChange: (SegmentationBrushMode) -> Unit,
  val onStrokeStart: (NormalizedPoint) -> Unit,
  val onStrokePoint: (NormalizedPoint) -> Unit,
  val onStrokeEnd: () -> Unit,
  val onTap: (NormalizedPoint) -> Unit,
  val onUndo: () -> Unit,
  val onRedo: () -> Unit,
  val onClear: () -> Unit,
)

/** Shows [XrLanguageTeacherCutoutEditor] full screen while [state] is open. */
@Composable
internal fun XrLanguageTeacherCutoutEditorDialog(
  state: CutoutEditorUiState,
  actions: CutoutEditorActions,
) {
  if (!state.isOpen) return
  Dialog(
    onDismissRequest = { if (!state.isApplying) actions.onCancel() },
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    XrLanguageTeacherCutoutEditor(state = state, actions = actions)
  }
}

/**
 * Full-screen "Edit cutout" editor for a vocabulary card's sticker: shows the card's original photo
 * with the selection's scrim, and Scrapbook's stroke tools (add / subtract / lasso brushes, undo,
 * redo, clear). "Reset to auto" goes back to the automatic cutout, "Use original photo" removes the
 * sticker so the card shows its whole photo (and closes the editor), Cancel closes the editor, and
 * Done regenerates the sticker from the selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun XrLanguageTeacherCutoutEditor(
  state: CutoutEditorUiState,
  actions: CutoutEditorActions,
  modifier: Modifier = Modifier,
) {
  val controlsEnabled = !state.isApplying
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
      // Top bar: Cancel, title, Done.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = actions.onCancel, enabled = controlsEnabled) {
          Text(stringResource(R.string.cancel))
        }
        Text(
          text = stringResource(R.string.xrteacher_edit_cutout_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          maxLines = 1,
          modifier = Modifier.weight(1f),
        )
        if (state.isApplying) {
          val savingDescription = stringResource(R.string.xrteacher_edit_cutout_saving_desc)
          Box(modifier = Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
              modifier = Modifier.size(24.dp).semantics { contentDescription = savingDescription },
              strokeWidth = 2.dp,
            )
          }
        } else {
          Button(onClick = actions.onDone, enabled = state.canApply) {
            Text(stringResource(R.string.done))
          }
        }
      }

      // Brush modes and undo / redo / clear.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SegmentationBrushModeButtons(
          selected = state.brushMode,
          onSelect = actions.onBrushModeChange,
          enabled = controlsEnabled,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = actions.onUndo, enabled = controlsEnabled && state.canUndo) {
            Icon(
              Icons.AutoMirrored.Rounded.Undo,
              contentDescription = stringResource(R.string.cd_undo),
            )
          }
          IconButton(onClick = actions.onRedo, enabled = controlsEnabled && state.canRedo) {
            Icon(
              Icons.AutoMirrored.Rounded.Redo,
              contentDescription = stringResource(R.string.cd_redo),
            )
          }
          IconButton(onClick = actions.onClear, enabled = controlsEnabled && state.canClear) {
            Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.clear_selection))
          }
        }
      }

      // Photo, selection scrim and the stroke being drawn.
      Box(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
        contentAlignment = Alignment.Center,
      ) {
        val photo = state.photo?.takeUnless { it.isRecycled }
        if (photo != null) {
          CutoutEditorCanvas(
            photo = remember(photo) { photo.asImageBitmap() },
            maskOverlay =
              remember(state.maskOverlay) {
                state.maskOverlay?.takeUnless { it.isRecycled }?.asImageBitmap()
              },
            state = state,
            enabled = controlsEnabled,
            actions = actions,
            photoDescription =
              stringResource(
                R.string.xrteacher_edit_cutout_photo_desc,
                state.target?.word.orEmpty(),
              ),
          )
        }
        if (state.isSegmenting) {
          CircularProgressIndicator(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
            strokeWidth = 2.dp,
          )
        }
      }

      Text(
        text = stringResource(state.brushMode.instructionResId),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      )
      val message = state.message
      if (message != null) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Text(
            text = stringResource(message.textResId()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          )
        }
      }
      Spacer(modifier = Modifier.size(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
      ) {
        val photoActionsEnabled = controlsEnabled && state.photo != null
        FilledTonalButton(
          onClick = actions.onResetToAuto,
          enabled = photoActionsEnabled,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Icon(
            Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(20.dp),
          )
          Text(stringResource(R.string.xrteacher_edit_cutout_reset_to_auto))
        }
        OutlinedButton(
          onClick = actions.onUseOriginalPhoto,
          enabled = photoActionsEnabled,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Icon(
            Icons.Rounded.Image,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(20.dp),
          )
          Text(stringResource(R.string.xrteacher_edit_cutout_use_original_photo))
        }
      }
    }
  }
}

/**
 * The photo fitted into the available space with rounded corners, the selection scrim over it, and
 * the stroke being drawn. Drags draw strokes and taps add single points, reported in normalized
 * photo coordinates.
 */
@Composable
private fun CutoutEditorCanvas(
  photo: ImageBitmap,
  maskOverlay: ImageBitmap?,
  state: CutoutEditorUiState,
  enabled: Boolean,
  actions: CutoutEditorActions,
  photoDescription: String,
) {
  var isDrawing by remember { mutableStateOf(false) }
  var lastPointTimeMs by remember { mutableLongStateOf(0L) }
  val stroke = state.activeStrokes.lastOrNull()
  val strokeColor = segmentationStrokeColor(stroke?.brushMode ?: SegmentationBrushMode.ADD)

  Canvas(
    modifier =
      Modifier.fillMaxSize()
        .semantics { contentDescription = photoDescription }
        .pointerInput(photo, enabled) {
          if (!enabled) return@pointerInput
          detectDragGestures(
            onDragStart = { offset ->
              val rect = photoRect(photo, Size(size.width.toFloat(), size.height.toFloat()))
              isDrawing = true
              actions.onStrokeStart(rect.normalize(offset))
            },
            onDrag = { change, _ ->
              // Throttle points to about 60Hz so long strokes stay cheap to segment.
              if (change.uptimeMillis - lastPointTimeMs > SEGMENTATION_STROKE_POINT_INTERVAL_MS) {
                change.consume()
                val rect = photoRect(photo, Size(size.width.toFloat(), size.height.toFloat()))
                actions.onStrokePoint(rect.normalize(change.position))
                lastPointTimeMs = change.uptimeMillis
              }
            },
            onDragEnd = {
              isDrawing = false
              actions.onStrokeEnd()
            },
            onDragCancel = {
              isDrawing = false
              actions.onStrokeEnd()
            },
          )
        }
        .pointerInput(photo, enabled) {
          if (!enabled) return@pointerInput
          detectTapGestures { offset ->
            val rect = photoRect(photo, Size(size.width.toFloat(), size.height.toFloat()))
            if (rect.contains(offset)) {
              actions.onTap(rect.normalize(offset))
            }
          }
        }
  ) {
    val rect = photoRect(photo, size)
    clipPath(SegmentationEditorDraw.pictureClipPath(this, rect)) {
      SegmentationEditorDraw.drawImageInRect(this, photo, rect)
      if (maskOverlay != null) {
        SegmentationEditorDraw.drawImageInRect(this, maskOverlay, rect)
      }
      if (isDrawing && stroke != null) {
        SegmentationEditorDraw.drawStroke(
          drawScope = this,
          points = stroke.points.map { rect.denormalize(it) },
          brushMode = stroke.brushMode,
          color = strokeColor,
        )
      }
    }
  }
}

/** Returns the rectangle that fits [image] inside [canvasSize], centered, keeping its aspect. */
private fun photoRect(image: ImageBitmap, canvasSize: Size): Rect =
  SegmentationEditorDraw.fitRect(
    imageWidth = image.width,
    imageHeight = image.height,
    canvasSize = canvasSize,
  )

/** Returns [offset] in normalized coordinates of this rectangle, clamped to its bounds. */
private fun Rect.normalize(offset: Offset): NormalizedPoint =
  NormalizedPoint(
    x = if (width > 0f) ((offset.x - left) / width).coerceIn(0f, 1f) else 0.5f,
    y = if (height > 0f) ((offset.y - top) / height).coerceIn(0f, 1f) else 0.5f,
  )

/** Returns the canvas position of [point], given in normalized coordinates of this rectangle. */
private fun Rect.denormalize(point: NormalizedPoint): Offset =
  Offset(x = left + point.x * width, y = top + point.y * height)

@StringRes
private fun CutoutEditorMessage.textResId(): Int =
  when (this) {
    CutoutEditorMessage.PHOTO_UNAVAILABLE -> R.string.xrteacher_edit_cutout_photo_unavailable
    CutoutEditorMessage.SEGMENTER_UNAVAILABLE ->
      R.string.xrteacher_edit_cutout_segmenter_unavailable
    CutoutEditorMessage.SEGMENTATION_FAILED -> R.string.xrteacher_edit_cutout_segmentation_failed
    CutoutEditorMessage.SELECTION_REJECTED -> R.string.xrteacher_edit_cutout_selection_rejected
    CutoutEditorMessage.SAVE_FAILED -> R.string.xrteacher_edit_cutout_save_failed
  }
