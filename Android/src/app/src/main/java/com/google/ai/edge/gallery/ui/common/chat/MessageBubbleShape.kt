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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Custom Shape for creating message bubble outlines with configurable corner radii.
 *
 * This class defines a custom Shape that generates a rounded rectangle outline, suitable for
 * message bubbles. It allows specifying a uniform corner radius for most corners, but also provides
 * the option to have a hard (non-rounded) corner on either the left or right side.
 */
class MessageBubbleShape(
  private val radius: Dp,
  private val hardCornerAtLeftOrRight: Boolean = false,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val radiusPx = with(density) { radius.toPx() }
    val hardCornerOnLeft = hardCornerAtLeftOrRight != (layoutDirection == LayoutDirection.Rtl)
    val path =
      Path().apply {
        addRoundRect(
          RoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            topLeftCornerRadius =
              if (hardCornerOnLeft) CornerRadius(0f, 0f)
              else CornerRadius(radiusPx, radiusPx),
            topRightCornerRadius =
              if (hardCornerOnLeft) CornerRadius(radiusPx, radiusPx)
              else CornerRadius(0f, 0f),
            bottomLeftCornerRadius = CornerRadius(radiusPx, radiusPx),
            bottomRightCornerRadius = CornerRadius(radiusPx, radiusPx),
          )
        )
      }
    return Outline.Generic(path)
  }
}
