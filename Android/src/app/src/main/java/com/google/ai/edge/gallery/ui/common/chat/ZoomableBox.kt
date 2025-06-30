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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun ZoomableBox(
  modifier: Modifier = Modifier,
  minScale: Float = 1f,
  maxScale: Float = 5f,
  content: @Composable ZoomableBoxScope.() -> Unit,
) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }
  var size by remember { mutableStateOf(IntSize.Zero) }
  Box(
    modifier =
      modifier
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, zoom, _ ->
            scale = maxOf(minScale, minOf(scale * zoom, maxScale))
            val maxX = (size.width * (scale - 1)) / 2
            val minX = -maxX
            offsetX = maxOf(minX, minOf(maxX, offsetX + pan.x))
            val maxY = (size.height * (scale - 1)) / 2
            val minY = -maxY
            offsetY = maxOf(minY, minOf(maxY, offsetY + pan.y))
          }
        },
    contentAlignment = Alignment.TopEnd,
  ) {
    val scope = ZoomableBoxScopeImpl(scale, offsetX, offsetY)
    scope.content()
  }
}

interface ZoomableBoxScope {
  val scale: Float
  val offsetX: Float
  val offsetY: Float
}

private data class ZoomableBoxScopeImpl(
  override val scale: Float,
  override val offsetX: Float,
  override val offsetY: Float,
) : ZoomableBoxScope
