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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.R
import com.neuralforge.mobile.ui.theme.customColors
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val SHAPES: List<Int> =
  listOf(R.drawable.circle, R.drawable.double_circle, R.drawable.pantegon, R.drawable.four_circle)

data class Shape(
  val id: Long,
  val shape: Int,
  val relativeX: Float,
  val relativeY: Float,
  val size: Dp,
  val color: Color,
  val addedTs: Long,
)

private const val PARTICLE_ANIMATION_DURATION = 300
private const val PARTICLE_ALIVE_MS = 600
private const val PARTICLE_BASE_SIZE = 6
private const val BATCH_SIZE = 5
private const val BATCH_INTERVAL_MS = 300

var curId = 0L

@Composable
fun GlitteringShapesLoader() {
  var shapes by remember { mutableStateOf(listOf<Shape>()) }
  var boxSize by remember { mutableStateOf(IntSize.Zero) }
  val taskIconColors = MaterialTheme.customColors.taskIconColors

  // Use LaunchedEffect to manage the list of shapes
  LaunchedEffect(Unit) {
    withContext(Dispatchers.Default) {
      while (true) {
        val newShapes = mutableListOf<Shape>()
        for (i in 1..BATCH_SIZE) {
          val shape =
            Shape(
              id = curId++,
              shape = SHAPES[Random.nextInt(SHAPES.size)],
              relativeX = Random.nextFloat(),
              relativeY = Random.nextFloat(),
              size = (PARTICLE_BASE_SIZE + Random.nextInt(-2, 2)).dp,
              color = taskIconColors[Random.nextInt(taskIconColors.size)],
              addedTs = System.currentTimeMillis(),
            )
          newShapes.add(shape)
        }
        val curTs = System.currentTimeMillis()
        for (shape in shapes) {
          if (curTs - shape.addedTs > PARTICLE_ANIMATION_DURATION * 2 + PARTICLE_ALIVE_MS + 100) {
            continue
          }
          newShapes.add(shape)
        }
        shapes = newShapes
        delay(BATCH_INTERVAL_MS.toLong())
      }
    }
  }

  Box(
    modifier = Modifier.fillMaxSize().onSizeChanged { boxSize = it },
    contentAlignment = Alignment.TopStart,
  ) {
    for (shape in shapes) {
      key(shape.id) { Particle(shape = shape, boxSize = boxSize) }
    }
  }
}

@Composable
private fun Particle(shape: Shape, boxSize: IntSize) {
  var enterAnimation by remember { mutableStateOf(false) }
  val enterProgress: Float by
    animateFloatAsState(
      if (enterAnimation) 1f else 0f,
      animationSpec = tween(durationMillis = PARTICLE_ANIMATION_DURATION, easing = LinearEasing),
    )
  val initialDelay = remember { Random.nextLong(50) }
  LaunchedEffect(Unit) {
    delay(initialDelay)
    enterAnimation = true
  }

  var exitAnimation by remember { mutableStateOf(false) }
  val exitProgress: Float by
    animateFloatAsState(
      if (exitAnimation) 1f else 0f,
      animationSpec = tween(durationMillis = PARTICLE_ANIMATION_DURATION, easing = LinearEasing),
    )
  LaunchedEffect(Unit) {
    delay(initialDelay + PARTICLE_ALIVE_MS.toLong())
    exitAnimation = true
  }

  val progress = if (exitProgress > 0) (1 - exitProgress) else enterProgress
  Image(
    painter = painterResource(shape.shape),
    contentDescription = null,
    modifier =
      Modifier.size(shape.size).graphicsLayer {
        translationX = boxSize.width * shape.relativeX
        translationY = boxSize.height * shape.relativeY
        scaleX = progress
        scaleY = progress
      },
    colorFilter = ColorFilter.tint(lerp(shape.color, Color.White, 0.95f)),
    contentScale = ContentScale.Fit,
  )
}
