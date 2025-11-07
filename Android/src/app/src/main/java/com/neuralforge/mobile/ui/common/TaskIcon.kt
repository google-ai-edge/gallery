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

package com.neuralforge.mobile.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.R
import com.neuralforge.mobile.data.Task

private val SHAPES: List<Int> =
  listOf(
    // Ask image.
    R.drawable.circle,
    // Audio scribe
    R.drawable.double_circle,
    // Prompt lab
    R.drawable.pantegon,
    // AI chat,
    R.drawable.four_circle,
  )

/**
 * Composable that displays an icon representing a task. It consists of a background image and a
 * foreground icon, both centered within a square box.
 */
@Composable
fun TaskIcon(
  task: Task,
  modifier: Modifier = Modifier,
  width: Dp = 56.dp,
  animationProgress: Float = 1f,
) {
  val revealingBrush =
    linearGradient(
      colorStops =
        arrayOf(
          (1f + 0.2f) * (1 - animationProgress) - 0.2f to Color.Red,
          (1f + 0.2f) * (1 - animationProgress) to Color.Transparent,
        )
    )
  Box(modifier = modifier.width(width).aspectRatio(1f), contentAlignment = Alignment.Center) {
    val brush = linearGradient(colors = getTaskBgGradientColors(task = task))
    Image(
      painter = getTaskIconBgShape(task = task),
      contentDescription = null,
      modifier =
        Modifier.fillMaxSize()
          .graphicsLayer(
            // This is important to make blending mode work.
            alpha = 0.99f,
            compositingStrategy = CompositingStrategy.Offscreen,
            translationX = 80 * (1 - animationProgress),
            rotationZ = -180 * (1 - animationProgress),
          )
          .drawWithContent {
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.SrcIn)
            drawRect(brush = revealingBrush, blendMode = BlendMode.DstOut)
          },
      contentScale = ContentScale.FillHeight,
    )
    var iconAnimationProgress = 0f
    if (animationProgress >= 0.8) {
      iconAnimationProgress = (animationProgress - 0.8f) / 0.2f
    }
    Icon(
      task.icon ?: ImageVector.vectorResource(task.iconVectorResourceId!!),
      tint = Color.White,
      modifier =
        Modifier.size(width * 0.55f)
          .graphicsLayer { alpha = iconAnimationProgress }
          .scale(iconAnimationProgress),
      contentDescription = null,
    )
  }
}

@Composable
private fun getTaskIconBgShape(task: Task): Painter {
  val colorIndex: Int = task.index % SHAPES.size
  return painterResource(SHAPES[colorIndex])
}

// @Preview(showBackground = true)
// @Composable
// fun TaskIconPreview() {
//   for ((index, task) in TASKS.withIndex()) {
//     task.index = index
//   }
//
//   GalleryTheme {
//     Column(modifier = Modifier.background(Color.Gray)) {
//       TaskIcon(task = TASK_LLM_CHAT, width = 80.dp)
//     }
//   }
// }
