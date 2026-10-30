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

package com.google.ai.edge.gallery.customtasks.common.segmentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.customColors

/** Width of an add or subtract brush stroke while it is drawn. */
private val FINGER_BRUSH_SIZE = 16.dp

/** Width of a lasso stroke while it is drawn. */
private val LASSO_BRUSH_SIZE = 4.dp

/** Corner radius of the picture that strokes are drawn on. */
val SEGMENTATION_PICTURE_CORNER_RADIUS: Dp = 16.dp

/**
 * Minimum time between two stroke points added while dragging (about 60Hz), so long strokes don't
 * generate an excessive number of points.
 */
const val SEGMENTATION_STROKE_POINT_INTERVAL_MS = 17L

/** The color (ARGB) of the overlay that highlights the selected object. */
const val SEGMENTATION_MASK_OVERLAY_COLOR = 0x8012B5CB.toInt()

/** The instruction shown below the picture while this brush mode is selected. */
val SegmentationBrushMode.instructionResId: Int
  @StringRes
  get() =
    when (this) {
      SegmentationBrushMode.ADD -> R.string.segmentation_instruction_add
      SegmentationBrushMode.SUBTRACT -> R.string.segmentation_instruction_subtract
      SegmentationBrushMode.LASSO -> R.string.segmentation_instruction_lasso
    }

/** The add selection, subtract selection, and lasso segmented buttons. */
@Composable
fun SegmentationBrushModeButtons(
  selected: SegmentationBrushMode,
  onSelect: (SegmentationBrushMode) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val modes = SegmentationBrushMode.entries
  SingleChoiceSegmentedButtonRow(modifier = modifier) {
    modes.forEachIndexed { index, mode ->
      SegmentedButton(
        selected = selected == mode,
        onClick = { onSelect(mode) },
        enabled = enabled,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
        icon = {},
        contentPadding = PaddingValues(horizontal = 4.dp),
      ) {
        when (mode) {
          SegmentationBrushMode.ADD ->
            BrushIcon(
              badge = Icons.Rounded.Add,
              contentDescription = stringResource(R.string.segmentation_add_selection_desc),
            )
          SegmentationBrushMode.SUBTRACT ->
            BrushIcon(
              badge = Icons.Rounded.Remove,
              contentDescription = stringResource(R.string.segmentation_subtract_selection_desc),
            )
          SegmentationBrushMode.LASSO ->
            Icon(
              imageVector = ImageVector.vectorResource(R.drawable.lasso),
              contentDescription = stringResource(R.string.segmentation_lasso_desc),
              modifier = Modifier.size(24.dp),
            )
        }
      }
    }
  }
}

/** A brush gesture icon with a small [badge] (plus or minus) at its top-right corner. */
@Composable
private fun BrushIcon(badge: ImageVector, contentDescription: String) {
  Box(modifier = Modifier.size(24.dp)) {
    Icon(
      imageVector = Icons.Rounded.Gesture,
      contentDescription = contentDescription,
      modifier = Modifier.size(20.dp).align(Alignment.BottomStart),
    )
    Icon(
      imageVector = badge,
      contentDescription = null,
      modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
    )
  }
}

/** The color that strokes of [brushMode] are drawn in. */
@Composable
fun segmentationStrokeColor(brushMode: SegmentationBrushMode): Color =
  when (brushMode) {
    SegmentationBrushMode.ADD -> MaterialTheme.customColors.positiveStrokeColor
    SegmentationBrushMode.SUBTRACT -> MaterialTheme.customColors.negativeStrokeColor
    SegmentationBrushMode.LASSO -> MaterialTheme.customColors.lassoStrokeColor
  }

/** Canvas geometry and drawing helpers shared by the Scrapbook segmenter and the cutout editor. */
object SegmentationEditorDraw {
  /**
   * Draws the stroke through [points] (canvas positions) on [drawScope] as the user brushes it: a
   * white outline under a translucent [color] line, dashed for the lasso, or a dot for a single
   * point.
   */
  fun drawStroke(
    drawScope: DrawScope,
    points: List<Offset>,
    brushMode: SegmentationBrushMode,
    color: Color,
  ) {
    with(drawScope) {
      if (points.isEmpty()) return
      val brushSize =
        if (brushMode == SegmentationBrushMode.LASSO) LASSO_BRUSH_SIZE else FINGER_BRUSH_SIZE
      if (points.size == 1) {
        val center = points[0]
        drawCircle(
          color = Color.White,
          radius = (brushSize / 2 + 2.dp).toPx(),
          center = center,
          alpha = 0.8f,
        )
        drawCircle(color = color, radius = (brushSize / 2).toPx(), center = center, alpha = 0.5f)
        return
      }
      val pathEffect =
        if (brushMode == SegmentationBrushMode.LASSO) {
          PathEffect.dashPathEffect(floatArrayOf(15f, 30f), 0f)
        } else {
          null
        }
      val path =
        Path().apply {
          moveTo(points.first().x, points.first().y)
          for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
          }
        }
      drawPath(
        path = path,
        color = Color.White,
        alpha = 0.8f,
        style =
          Stroke(width = (brushSize + 4.dp).toPx(), cap = StrokeCap.Round, pathEffect = pathEffect),
      )
      drawPath(
        path = path,
        color = color,
        alpha = 0.5f,
        style = Stroke(width = brushSize.toPx(), cap = StrokeCap.Round, pathEffect = pathEffect),
      )
    }
  }

  /**
   * Returns the rectangle that fits an image of [imageWidth] x [imageHeight] inside [canvasSize],
   * keeping its aspect ratio: centered, or at the top when [toTop] is set.
   */
  fun fitRect(imageWidth: Int, imageHeight: Int, canvasSize: Size, toTop: Boolean = false): Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val width = imageWidth.toFloat()
    val height = imageHeight.toFloat()
    // The minimum of the two scales fits the entire image inside the canvas.
    val scale = minOf(canvasSize.width / width, canvasSize.height / height)
    val scaledWidth = width * scale
    val scaledHeight = height * scale
    val left = (canvasSize.width - scaledWidth) / 2
    val top = if (toTop) 0f else (canvasSize.height - scaledHeight) / 2
    return Rect(left, top, left + scaledWidth, top + scaledHeight)
  }

  /** The rounded rectangle path of a picture drawn in [rect], to clip it and its overlays to. */
  fun pictureClipPath(drawScope: DrawScope, rect: Rect): Path =
    with(drawScope) {
      Path().apply {
        addRoundRect(
          RoundRect(
            rect = rect,
            cornerRadius =
              CornerRadius(
                SEGMENTATION_PICTURE_CORNER_RADIUS.toPx(),
                SEGMENTATION_PICTURE_CORNER_RADIUS.toPx(),
              ),
          )
        )
      }
    }

  /** Draws [image] stretched into [rect] on [drawScope]. */
  fun drawImageInRect(drawScope: DrawScope, image: ImageBitmap, rect: Rect) {
    with(drawScope) {
      drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
        dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
        filterQuality = FilterQuality.High,
      )
    }
  }
}
