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

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

val FAST_INT_OFFSET_SPEC = tween<IntOffset>(durationMillis = 150)

val BUTTON_TEXT_AUTO_SIZE =
  TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 1.sp)

@Composable
fun getSteppedSliderColors(): SliderColors {
  return SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    activeTickColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
    inactiveTickColor = MaterialTheme.colorScheme.secondaryContainer,
  )
}

/**
 * Calculates the contrast ratio between two colors. The ratio ranges from 1:1 (no contrast) to 21:1
 * (max contrast, e.g., black on white).
 */
fun calculateContrastRatio(color1: Color, color2: Color): Float {
  val l1 = color1.relativeLuminance()
  val l2 = color2.relativeLuminance()
  // The contrast ratio formula is: (L1 + 0.05) / (L2 + 0.05)
  // where L1 is the lighter color's luminance and L2 is the darker color's luminance.
  return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/**
 * Calculates the relative luminance of a given Color based on the sRGB standard. Luminance is a
 * measure of the perceived brightness of a color. The value is normalized between 0.0 (black) and
 * 1.0 (white).
 */
fun Color.relativeLuminance(): Float {
  // Convert R, G, B components to linear light space (sRGB to linear RGB).
  fun convertToLinear(colorComponent: Float): Float {
    return if (colorComponent <= 0.03928f) {
      colorComponent / 12.92f
    } else {
      ((colorComponent + 0.055f) / 1.055f).let { it * it * it }
    }
  }

  val r = convertToLinear(red)
  val g = convertToLinear(green)
  val b = convertToLinear(blue)

  // Calculate luminance using the standard formula.
  return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

@Composable
fun SBIconButton(
  icon: ImageVector,
  contentDescription: String,
  enable: Boolean,
  onClick: () -> Unit,
) {
  OutlinedIconButton(
    onClick = onClick,
    enabled = enable,
    border =
      IconButtonDefaults.outlinedIconButtonBorder(true)
        .copy(
          brush =
            SolidColor(
              MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enable) 1f else 0.1f)
            )
        ),
    colors =
      IconButtonDefaults.outlinedIconButtonColors()
        .copy(
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        ),
  ) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
  }
}
