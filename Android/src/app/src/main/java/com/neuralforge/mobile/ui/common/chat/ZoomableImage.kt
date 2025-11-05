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

package com.neuralforge.mobile.ui.common.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * A Composable function that displays a zoomable and pannable image.
 *
 * This function handles multi-touch gestures for zooming and panning. It's designed to be used
 * within a Pager to prevent the Pager from scrolling while the user is interacting with the image.
 */
@Composable
fun ZoomableImage(
  bitmap: ImageBitmap,
  modifier: Modifier = Modifier,
  minScale: Float = 1f,
  maxScale: Float = 3f,
  contentScale: ContentScale = ContentScale.Fit,
  pagerState: PagerState,
) {
  val scale = remember { mutableStateOf(1f) }
  val offsetX = remember { mutableStateOf(1f) }
  val offsetY = remember { mutableStateOf(1f) }

  val coroutineScope = rememberCoroutineScope()
  Box(
    contentAlignment = Alignment.TopCenter,
    modifier =
      Modifier.fillMaxSize().background(Color.Transparent).pointerInput(Unit) {
        // It uses the `pointerInput` modifier to detect gestures.
        //
        // When a user performs a pinch-to-zoom gesture, the `scale` state is updated.
        // Once the content is zoomed in (`scale.value > 1`), pan gestures are enabled, and the
        // `offsetX` and `offsetY` states are updated to move the content.
        //
        // To prevent a parent Pager component from scrolling horizontally during a pan gesture, the
        // `pagerState`'s scrolling is temporarily disabled and then re-enabled after the pan event.
        // If the content is zoomed back out to its original size, the scale and offsets are reset.
        awaitEachGesture {
          awaitFirstDown()
          do {
            val event = awaitPointerEvent()
            scale.value *= event.calculateZoom()
            if (scale.value > 1) {
              coroutineScope.launch { pagerState.setScrolling(false) }
              val offset = event.calculatePan()
              offsetX.value += offset.x
              offsetY.value += offset.y
              coroutineScope.launch { pagerState.setScrolling(true) }
            } else {
              scale.value = 1f
              offsetX.value = 1f
              offsetY.value = 1f
            }
          } while (event.changes.any { it.pressed })
        }
      },
  ) {
    Image(
      bitmap = bitmap,
      contentDescription = null,
      contentScale = contentScale,
      modifier =
        modifier.align(Alignment.Center).graphicsLayer {
          scaleX = maxOf(minScale, minOf(maxScale, scale.value))
          scaleY = maxOf(minScale, minOf(maxScale, scale.value))
          translationX = offsetX.value
          translationY = offsetY.value
        },
    )
  }
}

/**
 * An extension function on [PagerState] to temporarily disable or enable scrolling.
 *
 * This function uses a [MutatePriority.PreventUserInput] scroll block to ensure that no other
 * scrolls (like the user swiping) can happen while this block is active.
 */
suspend fun PagerState.setScrolling(value: Boolean) {
  scroll(scrollPriority = MutatePriority.PreventUserInput) {
    when (value) {
      true -> Unit
      else -> awaitCancellation()
    }
  }
}
