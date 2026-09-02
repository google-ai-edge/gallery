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
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import kotlin.math.roundToInt

private enum class ColorMode(@StringRes val labelResId: Int) {
  Solid(R.string.solid_color),
  LinearGradient(R.string.linear_gradient_color),
  RadialGradient(R.string.radial_gradient_color),
}

private val COLOR_STOP_HANDLE_SIZE_DP = 20.dp
private val DEFAULT_COLORED_BG_START_COLOR = Color(0xff009688)
private val DEFAULT_COLORED_BG_END_COLOR = Color(0xffff9800)

// Update interval for dragging/transformations, set to roughly match 60fps (~16.6ms)
private const val MIN_UPDATE_INTERVAL_MS = 16

@Composable
fun ColoredBackgroundEditor(bitmapSize: Size, onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
  var selectedColorMode by remember { mutableStateOf(ColorMode.Solid) }
  var selectedSolidColor by remember { mutableStateOf(DEFAULT_COLORED_BG_END_COLOR) }
  var curSize by remember { mutableStateOf(IntSize.Zero) }
  val halfSizePx = dpToPixel(dpValue = COLOR_STOP_HANDLE_SIZE_DP / 2)
  var startColor by remember { mutableStateOf(DEFAULT_COLORED_BG_START_COLOR) }
  var endColor by remember { mutableStateOf(DEFAULT_COLORED_BG_END_COLOR) }
  var showFillModeMenu by remember { mutableStateOf(false) }

  // Linear gradient related.
  val linearGradientInitialStartPosition = Offset(curSize.width / 2f - halfSizePx, 0f)
  val linearGradientInitialEndPosition =
    Offset(curSize.width / 2f - halfSizePx, curSize.height - halfSizePx * 2)
  var linearGradientStartPosition by
    remember(linearGradientInitialStartPosition) {
      mutableStateOf(linearGradientInitialStartPosition)
    }
  var linearGradientEndPosition by
    remember(linearGradientInitialEndPosition) { mutableStateOf(linearGradientInitialEndPosition) }

  // Radial gradient related.
  val radialGradientInitialCenterPosition =
    Offset(curSize.width / 2f - halfSizePx, curSize.height / 2f - halfSizePx)
  val radialGradientInitialEndPosition = Offset(curSize.width / 2f - halfSizePx, 0f)
  var radialGradientCenterPosition by
    remember(radialGradientInitialCenterPosition) {
      mutableStateOf(radialGradientInitialCenterPosition)
    }
  var radialGradientEndPosition by
    remember(radialGradientInitialEndPosition) { mutableStateOf(radialGradientInitialEndPosition) }

  var showColorPickerSheet by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = onDismiss) {
    Card(modifier = Modifier.fillMaxWidth().padding(0.dp), shape = RoundedCornerShape(16.dp)) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Fill mode selector.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.fill_mode), style = MaterialTheme.typography.labelLarge)
          Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            OutlinedButton(
              modifier = Modifier.fillMaxWidth(),
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
              onClick = { showFillModeMenu = true },
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  stringResource(selectedColorMode.labelResId),
                  style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                  Icons.Rounded.ArrowDropDown,
                  contentDescription = null,
                  modifier = Modifier.padding(start = 8.dp),
                )
              }
            }
            DropdownMenu(
              expanded = showFillModeMenu,
              onDismissRequest = { showFillModeMenu = false },
            ) {
              for (mode in ColorMode.entries) {
                DropdownMenuItem(
                  text = {
                    Text(
                      stringResource(mode.labelResId),
                      color =
                        if (mode == selectedColorMode) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                  },
                  onClick = {
                    selectedColorMode = mode
                    showFillModeMenu = false
                  },
                )
              }
            }
          }
        }

        // Color panel.
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .aspectRatio(1f)
              .clip(RoundedCornerShape(COLOR_STOP_HANDLE_SIZE_DP / 2))
              .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(COLOR_STOP_HANDLE_SIZE_DP / 2),
              ),
          contentAlignment = Alignment.Center,
        ) {
          when (selectedColorMode) {
            ColorMode.Solid -> {
              Box(modifier = Modifier.fillMaxSize().background(selectedSolidColor))
              FilledTonalButton(
                onClick = { showColorPickerSheet = true },
                contentPadding = SMALL_BUTTON_CONTENT_PADDING,
              ) {
                Text(stringResource(R.string.tap_to_change_color))
              }
            }

            ColorMode.LinearGradient -> {
              val colorStops = arrayOf(0.0f to startColor, 1f to endColor)
              Box(
                modifier =
                  Modifier.fillMaxSize()
                    .background(
                      Brush.linearGradient(
                        colorStops = colorStops,
                        start = linearGradientStartPosition.plus(Offset(halfSizePx, halfSizePx)),
                        end = linearGradientEndPosition.plus(Offset(halfSizePx, halfSizePx)),
                      )
                    )
                    .onSizeChanged { curSize = it },
                contentAlignment = Alignment.TopStart,
              ) {
                if (curSize.width != 0 && curSize.height != 0) {
                  ColorStopHandle(
                    initialPositionPx = linearGradientInitialStartPosition,
                    initialColor = startColor,
                    rangeLimit = Offset(0f, curSize.width - halfSizePx * 2),
                    onPositionChange = { offset -> linearGradientStartPosition = offset },
                    onColorChange = { startColor = it },
                    testTag = "linear_gradient_start_handle",
                  )
                  ColorStopHandle(
                    initialPositionPx = linearGradientInitialEndPosition,
                    initialColor = endColor,
                    rangeLimit = Offset(0f, curSize.width - halfSizePx * 2),
                    onPositionChange = { offset -> linearGradientEndPosition = offset },
                    onColorChange = { endColor = it },
                    testTag = "linear_gradient_end_handle",
                  )
                }
              }
            }

            ColorMode.RadialGradient -> {
              val colorStops = arrayOf(0.0f to startColor, 1f to endColor)
              Box(
                modifier =
                  Modifier.fillMaxSize()
                    .background(
                      Brush.radialGradient(
                        colorStops = colorStops,
                        center = radialGradientCenterPosition.plus(Offset(halfSizePx, halfSizePx)),
                        radius =
                          radialGradientEndPosition
                            .plus(Offset(halfSizePx, halfSizePx))
                            .minus(
                              radialGradientCenterPosition.plus(Offset(halfSizePx, halfSizePx))
                            )
                            .getDistance(),
                      )
                    )
                    .onSizeChanged { curSize = it },
                contentAlignment = Alignment.TopStart,
              ) {
                ColorStopHandle(
                  initialPositionPx = radialGradientInitialCenterPosition,
                  initialColor = startColor,
                  rangeLimit = Offset(0f, curSize.width - halfSizePx * 2),
                  onPositionChange = { offset -> radialGradientCenterPosition = offset },
                  onColorChange = { startColor = it },
                  showPlusAtCenter = true,
                  testTag = "radial_gradient_center_handle",
                )
                ColorStopHandle(
                  initialPositionPx = radialGradientInitialEndPosition,
                  initialColor = endColor,
                  rangeLimit = Offset(0f, curSize.width - halfSizePx * 2),
                  onPositionChange = { offset -> radialGradientEndPosition = offset },
                  onColorChange = { endColor = it },
                  testTag = "radial_gradient_end_handle",
                )
              }
            }
          }
        }

        // Button.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          // Cancel
          OutlinedButton(onClick = { onDismiss() }, contentPadding = SMALL_BUTTON_CONTENT_PADDING) {
            Text(stringResource(R.string.cancel))
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Done.
          Button(
            onClick = {
              val handleOffset = Offset(halfSizePx, halfSizePx)
              val scale = bitmapSize.width / curSize.width

              fun scaleOffset(offset: Offset): Offset {
                val truePreviewOffset = offset.plus(handleOffset)
                return Offset(x = truePreviewOffset.x * scale, y = truePreviewOffset.y * scale)
              }

              val bitmap =
                createBitmapFromColorMode(
                  colorMode = selectedColorMode,
                  bitmapWidth = bitmapSize.width.toInt(),
                  bitmapHeight = bitmapSize.height.toInt(),
                  solidColor = selectedSolidColor,
                  linearStartColor = startColor,
                  linearEndColor = endColor,
                  linearStartOffset = scaleOffset(linearGradientStartPosition),
                  linearEndOffset = scaleOffset(linearGradientEndPosition),
                  radialCenterColor = startColor,
                  radialEdgeColor = endColor,
                  radialCenterOffset = scaleOffset(radialGradientCenterPosition),
                  radialRadiusOffset = scaleOffset(radialGradientEndPosition),
                )

              onDone(bitmap)
            }
          ) {
            Text(stringResource(R.string.done))
          }
        }
      }
    }
  }

  if (showColorPickerSheet) {
    ColorPickerSheet(
      initialColor = selectedSolidColor,
      onSelectColor = { color -> selectedSolidColor = color },
      onDismiss = { showColorPickerSheet = false },
    )
  }
}

@Composable
private fun ColorStopHandle(
  initialPositionPx: Offset,
  initialColor: Color,
  rangeLimit: Offset,
  onPositionChange: (offset: Offset) -> Unit,
  onColorChange: (color: Color) -> Unit,
  showPlusAtCenter: Boolean = false,
  testTag: String = "",
) {
  // Store the offset as a MutableState<Offset> (in PIXELS)
  var offset by
    remember(initialPositionPx) {
      mutableStateOf(Offset(x = initialPositionPx.x, y = initialPositionPx.y))
    }
  // Same as offset, but not a state (i.e. updating it won't trigger re-compose).
  var tempOffset = remember { Offset(x = initialPositionPx.x, y = initialPositionPx.y) }
  var showColorPickerSheet by remember { mutableStateOf(false) }
  val color =
    if (calculateContrastRatio(Color.White, initialColor) < MIN_CONTRAST_THRESHOLD) Color.Black
    else Color.White
  var lastUpdateTs by remember { mutableLongStateOf(0L) }

  Box(
    modifier =
      Modifier.offset { IntOffset(x = offset.x.roundToInt(), y = offset.y.roundToInt()) }
        .run { if (testTag.isNotEmpty()) testTag(testTag) else this }
        .pointerInput(Unit) {
          detectDragGestures(
            onDrag = { change, dragAmount ->
              change.consume()
              // Rate-limit re-compose to increase performance.
              tempOffset = tempOffset.plus(dragAmount).clamp(rangeLimit.x, rangeLimit.y)
              val curTs = change.uptimeMillis
              if (curTs - lastUpdateTs > MIN_UPDATE_INTERVAL_MS) {
                offset = tempOffset
                onPositionChange(offset)
                lastUpdateTs = curTs
              }
            },
            onDragEnd = {
              offset = tempOffset
              onPositionChange(offset)
            },
          )
        }
        .pointerInput(Unit) { detectTapGestures { showColorPickerSheet = true } }
        .size(COLOR_STOP_HANDLE_SIZE_DP)
        .clip(CircleShape)
        .border(width = 2.dp, color = color, shape = CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (showPlusAtCenter) {
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
    }
  }

  if (showColorPickerSheet) {
    ColorPickerSheet(
      initialColor = initialColor,
      onSelectColor = { onColorChange(it) },
      onDismiss = { showColorPickerSheet = false },
    )
  }
}

private fun Offset.clamp(min: Float, max: Float): Offset {
  return Offset(x = this.x.coerceIn(min, max), y = this.y.coerceIn(min, max))
}

private fun createBitmapFromColorMode(
  colorMode: ColorMode,
  bitmapWidth: Int,
  bitmapHeight: Int,
  solidColor: Color,
  linearStartColor: Color,
  linearEndColor: Color,
  linearStartOffset: Offset,
  linearEndOffset: Offset,
  radialCenterColor: Color,
  radialEdgeColor: Color,
  radialCenterOffset: Offset,
  radialRadiusOffset: Offset,
): Bitmap {
  val bitmap = createBitmap(bitmapWidth, bitmapHeight)
  val canvas = Canvas(bitmap)
  val paint = Paint()

  when (colorMode) {
    ColorMode.Solid -> {
      paint.color = solidColor.toArgb()
      canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), paint)
    }
    ColorMode.LinearGradient -> {
      val shader =
        LinearGradient(
          linearStartOffset.x,
          linearStartOffset.y,
          linearEndOffset.x,
          linearEndOffset.y,
          linearStartColor.toArgb(),
          linearEndColor.toArgb(),
          Shader.TileMode.CLAMP,
        )
      paint.shader = shader
      canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), paint)
    }
    ColorMode.RadialGradient -> {
      val radius = radialRadiusOffset.minus(radialCenterOffset).getDistance()
      val shader =
        RadialGradient(
          radialCenterOffset.x,
          radialCenterOffset.y,
          radius,
          radialCenterColor.toArgb(),
          radialEdgeColor.toArgb(),
          Shader.TileMode.CLAMP,
        )
      paint.shader = shader
      canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), paint)
    }
  }
  return bitmap
}
