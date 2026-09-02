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
import android.graphics.Color as AndroidColor
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Credit:
// https://proandroiddev.com/color-picker-in-compose-f8c29744705

private val PREDEFINED_COLORS =
  listOf(
    Color(0xFFFFFFFF),
    Color(0xFF000000),
    Color(0xFFf44336),
    Color(0xFFe91e63),
    Color(0xFF9c27b0),
    Color(0xFF673ab7),
    Color(0xFF3f51b5),
    Color(0xFF2196f3),
    Color(0xFF03a9f4),
    Color(0xFF00bcd4),
    Color(0xFF009688),
    Color(0xFF4caf50),
    Color(0xFF8bc34a),
    Color(0xFFcddc39),
    Color(0xFFffeb3b),
    Color(0xFFffc107),
    Color(0xFFff9800),
    Color(0xFFff5722),
    Color(0xFF795548),
    Color(0xFF9e9e9e),
    Color(0xFF607d8b),
  )

const val MIN_CONTRAST_THRESHOLD = 1.3f
val COLOR_PICKER_SIZE = 28.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPicker(
  modifier: Modifier = Modifier,
  initialColor: Color = Color.Yellow,
  onColorUpdated: (Color) -> Unit,
) {
  var showBottomSheetPicker by remember { mutableStateOf(false) }

  val initialHsv =
    remember(initialColor) {
      val hsvArray = floatArrayOf(0f, 0f, 0f)
      AndroidColor.colorToHSV(initialColor.toArgb(), hsvArray)
      mutableStateOf(Triple(hsvArray[0], hsvArray[1], hsvArray[2])) // (H, S, V)
    }

  // State to hold the currently selected color
  val selectedColor =
    remember(initialHsv.value) {
      mutableStateOf(
        Color.hsv(initialHsv.value.first, initialHsv.value.second, initialHsv.value.third)
      )
    }

  ColorCircle(
    color = selectedColor.value,
    onClick = { showBottomSheetPicker = true },
    modifier = modifier,
  )

  if (showBottomSheetPicker) {
    ColorPickerSheet(
      initialColor = initialColor,
      onSelectColor = { color ->
        selectedColor.value = color
        onColorUpdated(color)
      },
      onDismiss = { showBottomSheetPicker = false },
    )
  }
}

@Composable
private fun HueBar(initialHue: Float, setColor: (Float) -> Unit) {
  val scope = rememberCoroutineScope()
  val interactionSource = remember { MutableInteractionSource() }
  val pressOffset = remember { mutableStateOf(Offset.Zero) }
  var isInitialized by remember { mutableStateOf(false) }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }

  Canvas(
    modifier =
      Modifier.height(COLOR_PICKER_SIZE)
        .fillMaxWidth()
        .clip(CircleShape)
        .emitDragGesture(interactionSource)
  ) {
    val drawScopeSize = size
    if (bitmap == null) {
      bitmap = createBitmap(size.width.toInt(), size.height.toInt())
    }
    bitmap?.let { curBitmap ->
      val hueCanvas = android.graphics.Canvas(curBitmap)
      val huePanel = RectF(0f, 0f, curBitmap.width.toFloat(), curBitmap.height.toFloat())
      val hueColors = IntArray((huePanel.width()).toInt())
      var hue = 0f
      for (i in hueColors.indices) {
        hueColors[i] = AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))
        hue += 360f / hueColors.size
      }
      val linePaint = Paint()
      linePaint.strokeWidth = 0F
      for (i in hueColors.indices) {
        linePaint.color = hueColors[i]
        hueCanvas.drawLine(i.toFloat(), 0F, i.toFloat(), huePanel.bottom, linePaint)
      }
      drawBitmap(bitmap = curBitmap, panel = huePanel)

      // Initialize the offset ONLY once when the canvas size is known
      if (!isInitialized) {
        val initialX = initialHue * huePanel.width() / 360f
        pressOffset.value = Offset(initialX, size.height / 2)
        isInitialized = true
      }

      fun pointToHue(pointX: Float): Float {
        val width = huePanel.width()
        val x =
          when {
            pointX < huePanel.left -> 0F
            pointX > huePanel.right -> width
            else -> pointX - huePanel.left
          }
        return x * 360f / width
      }

      scope.collectForPress(interactionSource) { pressPosition ->
        val pressPos = pressPosition.x.coerceIn(0f..drawScopeSize.width)
        pressOffset.value = Offset(pressPos, 0f)
        val selectedHue = pointToHue(pressPos)
        setColor(selectedHue)
      }

      drawCircle(
        Color.White,
        radius = size.height / 2,
        center = Offset(pressOffset.value.x, size.height / 2),
        style = Stroke(width = 2.dp.toPx()),
      )
    }
  }
}

@Composable
private fun SatValPanel(
  hue: Float,
  initialSat: Float,
  initialVal: Float,
  setSatVal: (Float, Float) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val scope = rememberCoroutineScope()
  var sat: Float
  var value: Float
  val pressOffset = remember { mutableStateOf(Offset.Zero) }
  var isInitialized by remember { mutableStateOf(false) }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }

  Canvas(
    modifier =
      Modifier.fillMaxWidth()
        .aspectRatio(1f)
        .emitDragGesture(interactionSource)
        .clip(RoundedCornerShape(12.dp))
  ) {
    val cornerRadius = 12.dp.toPx()
    val satValSize = size
    if (bitmap == null) {
      bitmap = createBitmap(size.width.toInt(), size.height.toInt())
    }

    bitmap?.let { curBitmap ->
      val canvas = android.graphics.Canvas(curBitmap)
      val satValPanel = RectF(0f, 0f, curBitmap.width.toFloat(), curBitmap.height.toFloat())
      val rgb = AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))
      val satShader =
        LinearGradient(
          satValPanel.left,
          satValPanel.top,
          satValPanel.right,
          satValPanel.top,
          -0x1,
          rgb,
          Shader.TileMode.CLAMP,
        )
      val valShader =
        LinearGradient(
          satValPanel.left,
          satValPanel.top,
          satValPanel.left,
          satValPanel.bottom,
          -0x1,
          -0x1000000,
          Shader.TileMode.CLAMP,
        )
      canvas.drawRoundRect(
        satValPanel,
        cornerRadius,
        cornerRadius,
        Paint().apply { shader = ComposeShader(valShader, satShader, PorterDuff.Mode.MULTIPLY) },
      )
      drawBitmap(bitmap = curBitmap, panel = satValPanel)

      if (!isInitialized) {
        val initialX = initialSat * satValPanel.width()
        val initialY = (1f - initialVal) * satValPanel.height()
        pressOffset.value =
          Offset(initialX.coerceIn(0f, satValSize.width), initialY.coerceIn(0f, satValSize.height))
        isInitialized = true
      }

      fun pointToSatVal(pointX: Float, pointY: Float): Pair<Float, Float> {
        val width = satValPanel.width()
        val height = satValPanel.height()
        val x =
          when {
            pointX < satValPanel.left -> 0f
            pointX > satValPanel.right -> width
            else -> pointX - satValPanel.left
          }
        val y =
          when {
            pointY < satValPanel.top -> 0f
            pointY > satValPanel.bottom -> height
            else -> pointY - satValPanel.top
          }
        val satPoint = 1f / width * x
        val valuePoint = 1f - 1f / height * y
        return satPoint to valuePoint
      }
      scope.collectForPress(interactionSource) { pressPosition ->
        val pressPositionOffset =
          Offset(
            pressPosition.x.coerceIn(0f..satValSize.width),
            pressPosition.y.coerceIn(0f..satValSize.height),
          )

        pressOffset.value = pressPositionOffset
        val (satPoint, valuePoint) = pointToSatVal(pressPositionOffset.x, pressPositionOffset.y)
        sat = satPoint
        value = valuePoint
        setSatVal(sat, value)
      }
      drawCircle(
        color = Color.Black,
        radius = 8.dp.toPx(),
        center = pressOffset.value,
        style = Stroke(width = 4.dp.toPx()),
      )
      drawCircle(
        color = Color.White,
        radius = 8.dp.toPx(),
        center = pressOffset.value,
        style = Stroke(width = 2.dp.toPx()),
      )
      drawCircle(color = Color.White, radius = 2.dp.toPx(), center = pressOffset.value)
    }
  }
}

@Composable
private fun ColorCircle(
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  checked: Boolean = false,
  backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  // Calculate the contrast ratio between the circle color and the background color.
  val contrastRatio = calculateContrastRatio(color, backgroundColor)

  // Determine if the border is necessary
  val needsBorder = contrastRatio < MIN_CONTRAST_THRESHOLD

  // Define border properties
  val borderWidth = 1.dp
  val borderColor = MaterialTheme.colorScheme.outlineVariant

  // Apply the border dynamically
  val borderModifier =
    if (needsBorder) {
      Modifier.border(width = borderWidth, color = borderColor, shape = CircleShape)
    } else {
      Modifier
    }

  Box(
    modifier =
      modifier
        .size(COLOR_PICKER_SIZE)
        .clip(CircleShape)
        .clickable { onClick() }
        .background(color)
        .then(borderModifier),
    contentAlignment = Alignment.Center,
  ) {
    if (checked) {
      Icon(
        Icons.Rounded.Check,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint =
          if (needsBorder) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.inverseOnSurface,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
  initialColor: Color,
  onSelectColor: (color: Color) -> Unit,
  onDismiss: () -> Unit,
) {
  val initialHsv =
    remember(initialColor) {
      val hsvArray = floatArrayOf(0f, 0f, 0f)
      AndroidColor.colorToHSV(initialColor.toArgb(), hsvArray)
      mutableStateOf(Triple(hsvArray[0], hsvArray[1], hsvArray[2])) // (H, S, V)
    }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Box(
      modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
      contentAlignment = Alignment.Center,
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SatValPanel(
          hue = initialHsv.value.first,
          initialSat = initialHsv.value.second,
          initialVal = initialHsv.value.third,
          setSatVal = { sat, value ->
            initialHsv.value = Triple(initialHsv.value.first, sat, value)
            onSelectColor(Color.hsv(initialHsv.value.first, sat, value))
          },
        )

        Spacer(modifier = Modifier.height(8.dp))

        HueBar(
          initialHue = initialHsv.value.first,
          setColor = { hue ->
            initialHsv.value = Triple(hue, initialHsv.value.second, initialHsv.value.third)
            onSelectColor(Color.hsv(hue, initialHsv.value.second, initialHsv.value.third))
          },
        )

        HorizontalDivider(
          modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )

        LazyVerticalGrid(
          columns = GridCells.FixedSize(COLOR_PICKER_SIZE),
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(PREDEFINED_COLORS) { color ->
            ColorCircle(
              modifier = Modifier.testTag("predefined_color_circle"),
              color = color,
              checked = color.value == initialColor.value,
              onClick = {
                scope.launch {
                  onSelectColor(color)
                  sheetState.hide()
                  onDismiss()
                }
              },
            )
          }
        }
      }
    }
  }
}

private fun CoroutineScope.collectForPress(
  interactionSource: InteractionSource,
  setOffset: (Offset) -> Unit,
) {
  launch {
    interactionSource.interactions.collect { interaction ->
      (interaction as? PressInteraction.Press)?.pressPosition?.let(setOffset)
    }
  }
}

private fun Modifier.emitDragGesture(interactionSource: MutableInteractionSource): Modifier =
  composed {
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        detectDragGestures { input, _ ->
          scope.launch { interactionSource.emit(PressInteraction.Press(input.position)) }
        }
      }
      .clickable(interactionSource, null) {}
  }

private fun DrawScope.drawBitmap(bitmap: Bitmap, panel: RectF) {
  drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, panel.toRect(), null) }
}
