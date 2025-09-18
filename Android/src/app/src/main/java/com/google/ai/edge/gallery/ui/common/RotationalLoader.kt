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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.customColors

private const val GRID_SPACING_FACTOR = 0.1f
private const val ICON_SIZE_FACTOR = 0.3f

/**
 * A composable that displays a rotational and scaling animated loader, structured as a 2x2 grid.
 *
 * This loader uses two concurrent infinite animations:
 * 1. **Outer Rotation (rotationZ):** Continuously rotates the entire [LazyVerticalGrid] container
 *    using a custom [CubicBezierEasing] for a distinct non-linear rotation speed.
 * 2. **Inner Scale (scaleX, scaleY):** Cycles the scale of the individual grid items between 1.0
 *    and 0.4 using [EaseInOut] easing for a smooth pulsing/breathing effect.
 */
@Composable
fun RotationalLoader(size: Dp) {
  val infiniteTransition = rememberInfiniteTransition(label = "infinite")
  val rotationProgress by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(2000, easing = CubicBezierEasing(0.5f, 0.16f, 0f, 0.71f)),
          repeatMode = RepeatMode.Restart,
        ),
    )
  val scaleProgress by
    infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 0.4f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(1000, easing = EaseInOut),
          repeatMode = RepeatMode.Reverse,
        ),
    )
  val curRotationZ = 45f + rotationProgress * 360f
  val curScale = scaleProgress

  val gridSpacing = size * GRID_SPACING_FACTOR
  LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
    verticalArrangement = Arrangement.spacedBy(gridSpacing),
    modifier =
      Modifier.size(size).graphicsLayer { rotationZ = curRotationZ }.clearAndSetSemantics {},
  ) {
    itemsIndexed(
      listOf(
        R.drawable.four_circle,
        R.drawable.circle,
        R.drawable.double_circle,
        R.drawable.pantegon,
      )
    ) { index, imageResource ->
      Box(
        modifier = Modifier.size((size - gridSpacing) / 2),
        contentAlignment =
          when (index) {
            0 -> Alignment.BottomEnd
            1 -> Alignment.BottomStart
            2 -> Alignment.TopEnd
            3 -> Alignment.TopStart
            else -> Alignment.Center
          },
      ) {
        val colorIndex =
          when (index) {
            0 -> 2
            1 -> 1
            2 -> 0
            else -> 3
          }
        val brush =
          linearGradient(colors = MaterialTheme.customColors.taskBgGradientColors[colorIndex])
        Image(
          painter = painterResource(id = imageResource),
          contentDescription = null,
          modifier =
            Modifier.size(size * ICON_SIZE_FACTOR)
              .graphicsLayer {
                // This is important to make blending mode work.
                alpha = 0.99f
                rotationZ = -curRotationZ
                scaleX = curScale
                scaleY = curScale
              }
              .drawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.SrcIn)
              },
          contentScale = ContentScale.Fit,
        )
      }
    }
  }
}
