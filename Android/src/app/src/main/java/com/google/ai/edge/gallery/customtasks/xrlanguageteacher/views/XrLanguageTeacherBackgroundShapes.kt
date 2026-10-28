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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Soft decorative background shape colors on the Saved and Study tabs (light mode).
private val BLOB_GREEN_LIGHT = Color(0xFFE6F4EA)
private val BLOB_YELLOW_LIGHT = Color(0xFFFEF7E0)
private val BLOB_BLUE_LIGHT = Color(0xFFE8F0FE)
private val BLOB_PINK_LIGHT = Color(0xFFFCE8E6)

// Subtle translucent decorative shape colors in dark mode, matching VocabGameStartScreen.
private val BLOB_GREEN_DARK = Color(0x3834A853)
private val BLOB_YELLOW_DARK = Color(0x38FCBD00)
private val BLOB_BLUE_DARK = Color(0x384285F4)
private val BLOB_PINK_DARK = Color(0x38EA4335)

private data class BackgroundShapePalette(
  val green: Color,
  val yellow: Color,
  val blue: Color,
  val pink: Color,
)

@Composable
private fun rememberBackgroundShapePalette(): BackgroundShapePalette {
  val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  return if (isDark) {
    BackgroundShapePalette(
      green = BLOB_GREEN_DARK,
      yellow = BLOB_YELLOW_DARK,
      blue = BLOB_BLUE_DARK,
      pink = BLOB_PINK_DARK,
    )
  } else {
    BackgroundShapePalette(
      green = BLOB_GREEN_LIGHT,
      yellow = BLOB_YELLOW_LIGHT,
      blue = BLOB_BLUE_LIGHT,
      pink = BLOB_PINK_LIGHT,
    )
  }
}

/** Wavy outline for the blue background shapes on the Saved and Study tabs. */
private val StudyWavyShape: Shape = studyScallopedShape(lobes = 9, depth = 0.08f)

/** Flower outline for the pink background shape on the Saved and Study tabs. */
private val StudyFlowerShape: Shape = studyScallopedShape(lobes = 6, depth = 0.2f)

/**
 * Soft pastel shapes behind the Saved tab content, placed to match the Figma "SHAPES" group (frame
 * coordinates minus the status bar and top app bar above the tab). Shapes near the right or bottom
 * edge are anchored to that edge so they stay in view across screen sizes.
 */
@Composable
internal fun SavedBackgroundShapes(modifier: Modifier = Modifier) {
  val palette = rememberBackgroundShapePalette()
  Box(modifier = modifier.fillMaxSize()) {
    StudyShape(palette.green, CircleShape, 126.dp, Modifier.offset((-23).dp, 63.dp))
    StudyShape(palette.blue, StudyWavyShape, 106.dp, Modifier.offset((-51).dp, 323.dp))
    StudyShape(
      palette.pink,
      StudyFlowerShape,
      86.dp,
      Modifier.align(Alignment.BottomStart).offset((-20).dp, (-42).dp),
    )
    val topEnd = Modifier.align(Alignment.TopEnd)
    StudyShape(palette.yellow, CircleShape, 82.dp, topEnd.offset((-12).dp, 55.dp))
    StudyShape(palette.yellow, CircleShape, 82.dp, topEnd.offset((-68).dp, 288.dp))
    StudyShape(
      palette.blue,
      StudyWavyShape,
      187.dp,
      Modifier.align(Alignment.BottomEnd).offset(35.dp, (-25).dp),
    )
  }
}

/**
 * Soft pastel shapes behind the Study tab content, placed to match the Figma "SHAPES" group. Shapes
 * on the right are anchored to the right edge so they stay behind the cards on wider screens.
 */
@Composable
internal fun StudyBackgroundShapes(modifier: Modifier = Modifier) {
  val palette = rememberBackgroundShapePalette()
  Box(modifier = modifier.fillMaxSize()) {
    StudyShape(palette.green, CircleShape, 126.dp, Modifier.offset((-23).dp, 63.dp))
    StudyShape(palette.blue, StudyWavyShape, 106.dp, Modifier.offset((-51).dp, 323.dp))
    StudyShape(palette.pink, StudyFlowerShape, 80.dp, Modifier.offset((-20).dp, 581.dp))
    val topEnd = Modifier.align(Alignment.TopEnd)
    StudyShape(palette.yellow, CircleShape, 82.dp, topEnd.offset((-12).dp, 55.dp))
    StudyShape(palette.yellow, CircleShape, 82.dp, topEnd.offset((-68).dp, 288.dp))
    StudyShape(palette.blue, StudyWavyShape, 187.dp, topEnd.offset(35.dp, 495.dp))
  }
}

/** One flat pastel shape of [SavedBackgroundShapes] and [StudyBackgroundShapes]. */
@Composable
private fun StudyShape(color: Color, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(size).background(color, shape))
}

/**
 * A round outline with [lobes] soft scallops, approximating the Material expressive shapes in the
 * Figma Study background. [depth] is how far the scallops dip in, as a fraction of the radius.
 */
private fun studyScallopedShape(lobes: Int, depth: Float): Shape = GenericShape { size, _ ->
  val radius = size.minDimension / 2f
  val steps = lobes * 16
  for (step in 0..steps) {
    val angle = 2.0 * PI * step / steps
    val pointRadius = radius * (1f - depth * (1f - cos(lobes * angle).toFloat()) / 2f)
    val x = size.width / 2f + pointRadius * cos(angle).toFloat()
    val y = size.height / 2f + pointRadius * sin(angle).toFloat()
    if (step == 0) moveTo(x, y) else lineTo(x, y)
  }
  close()
}
