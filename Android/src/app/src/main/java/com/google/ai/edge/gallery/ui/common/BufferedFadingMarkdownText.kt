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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

private const val FADE_INTERVAL_MS = 120

/**
 * A Composable that displays Markdown text with a smooth fading transition when the text content
 * changes.
 *
 * This is achieved by using two layers of [MarkdownText]. When the input `text` changes:
 * 1. The new text is rendered on a hidden overlay layer (`text2`).
 * 2. The overlay layer is smoothly faded in.
 * 3. Once the fade-in is complete, the base layer (`text1`) is updated to the new text.
 * 4. The overlay layer is instantly hidden, ready for the next text update.
 */
@Composable
fun BufferedFadingMarkdownText(text: String, inProgress: Boolean, modifier: Modifier = Modifier) {
  var text1 by remember { mutableStateOf(text) }
  var text2 by remember { mutableStateOf("") }
  val alpha2 = remember { Animatable(0f) }
  val currentText by rememberUpdatedState(text)
  var showOverlay by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    snapshotFlow { currentText }
      .conflate()
      .collect { newText ->
        if (newText == text1) return@collect

        // Set the new text onto the hidden overlay layer
        text2 = newText
        alpha2.snapTo(0f)

        // Smoothly fade the new layout in.
        // Text1 remains 100% solid, unchanged, and stationary underneath.
        alpha2.animateTo(
          1f,
          animationSpec = tween(FADE_INTERVAL_MS, easing = LinearOutSlowInEasing),
        )

        // Swap the background text to match the new text instantly.
        text1 = newText

        // Wait a frame for the overlay layer to fully lay out and draw.
        val unused = awaitFrame()

        // Instantly hide the overlay so we don't double-render the text.
        alpha2.snapTo(0f)
      }
  }

  val previousInProgress = rememberUpdatedState(inProgress)
  LaunchedEffect(inProgress) {
    // Trigger only when inProgress changes from true to false.
    if (previousInProgress.value && !inProgress) {
      delay(FADE_INTERVAL_MS.toLong() * 2)
      showOverlay = false
    }
  }

  Box(modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
    // LAYER 1: Base text.
    //
    // The alpha of the base text is 1f - alpha2.value. As alpha2 animates from 0f to 1f,
    // the base text fades out from full opacity (1f) to transparent (0f).
    // This works in conjunction with BlendMode.Plus on the overlay layer:
    // As the overlay fades in, the base text fades out. Since the sum of their alphas is always 1,
    // and BlendMode.Plus adds the colors, the transition appears as a smooth crossfade
    // without any intermediate darkening or color artifacts.
    SelectionContainer() {
      MarkdownText(text = text1, modifier = Modifier.graphicsLayer { alpha = 1f - alpha2.value })
    }

    // LAYER 2: Overlay text.
    if (showOverlay) {
      MarkdownText(
        text = text2,
        modifier =
          // Ignore this layer in terms of accessibility semantics (won't be read by screen
          // readers).
          Modifier.clearAndSetSemantics {}
            .graphicsLayer {
              alpha = alpha2.value
              // Use BlendMode.Plus to ensure that when alpha2 is between 0 and 1,
              // the overlay text is added on top of the base text without
              // darkening or other complex blending effects.
              this.blendMode = BlendMode.Plus
            },
      )
    }
  }
}
