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

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val BUTTON_HEIGHT = 30.dp

enum class CompactButtonType {
  Primary,
  Secondary,
  Lowkey,
}

@Composable
fun CompactButton(
  type: CompactButtonType,
  @StringRes textResId: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  height: Dp = BUTTON_HEIGHT,
  extraContent: @Composable () -> Unit = {},
) {
  val backgroundColor =
    when (type) {
      CompactButtonType.Primary -> MaterialTheme.colorScheme.primary
      CompactButtonType.Secondary -> MaterialTheme.colorScheme.secondaryContainer
      CompactButtonType.Lowkey -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
  val textColor =
    when (type) {
      CompactButtonType.Primary -> Color.White
      CompactButtonType.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
      CompactButtonType.Lowkey -> MaterialTheme.colorScheme.onSurface
    }
  Box(
    modifier =
      modifier
        .clip(CircleShape)
        .height(height)
        .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
        .clickable(enabled = enabled) { onClick() }
        .background(backgroundColor)
        .padding(horizontal = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(textResId), style = MaterialTheme.typography.labelMedium, color = textColor)
    extraContent()
  }
}
