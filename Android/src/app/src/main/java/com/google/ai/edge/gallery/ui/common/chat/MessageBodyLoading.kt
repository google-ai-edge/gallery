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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IMAGE_RESOURCES =
  listOf(R.drawable.pantegon, R.drawable.double_circle, R.drawable.circle, R.drawable.four_circle)

private const val ANIMATION_DURATION = 300
private const val ANIMATION_DURATION2 = 300
private const val PAUSE_DURATION = 200
private const val PAUSE_DURATION2 = 0

/** Composable function to display a loading indicator. */
@Composable
fun MessageBodyLoading() {
  val progress = remember { Animatable(0f) }
  val alphaAnim = remember { Animatable(0f) }
  val activeImageIndex = remember { mutableIntStateOf(0) }

  LaunchedEffect(Unit) { // Run this once
    while (true) {
      var progressJob = launch {
        progress.animateTo(
          targetValue = 1f,
          animationSpec =
            tween(
              durationMillis = ANIMATION_DURATION,
              easing = multiBounceEasing(bounces = 3, decay = 0.02f),
            ),
        )
      }
      var alphaJob = launch {
        alphaAnim.animateTo(
          targetValue = 1f,
          animationSpec = tween(durationMillis = ANIMATION_DURATION / 2),
        )
      }
      progressJob.join()
      alphaJob.join()
      delay((PAUSE_DURATION).toLong())

      progressJob = launch {
        progress.animateTo(
          targetValue = 0f,
          animationSpec =
            tween(
              durationMillis = ANIMATION_DURATION2,
              easing = multiBounceEasing(bounces = 3, decay = 0.02f),
            ),
        )
      }
      alphaJob = launch {
        alphaAnim.animateTo(
          targetValue = 0f,
          animationSpec = tween(durationMillis = ANIMATION_DURATION2 / 2),
        )
      }

      progressJob.join()
      alphaJob.join()
      delay(PAUSE_DURATION2.toLong())

      activeImageIndex.intValue = (activeImageIndex.intValue + 1) % IMAGE_RESOURCES.size
    }
  }

  Box(contentAlignment = Alignment.Center) {
    for ((index, imageResource) in IMAGE_RESOURCES.withIndex()) {
      Image(
        painter = painterResource(id = imageResource),
        contentDescription = "",
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(getTaskIconColor(index = index)),
        modifier =
          Modifier.graphicsLayer {
              scaleX = progress.value * 0.2f + 0.8f
              scaleY = progress.value * 0.2f + 0.8f
              rotationZ = progress.value * 100
              alpha = if (index != activeImageIndex.intValue) 0f else alphaAnim.value
            }
            .size(24.dp),
      )
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyLoadingPreview() {
//   GalleryTheme { Row(modifier = Modifier.padding(16.dp)) { MessageBodyLoading() } }
// }
