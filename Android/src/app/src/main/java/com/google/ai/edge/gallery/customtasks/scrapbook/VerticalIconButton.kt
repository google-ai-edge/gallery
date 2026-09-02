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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun VerticalIconButton(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  width: Dp = 60.dp,
  @StringRes textResId: Int? = null,
  enabled: Boolean = true,
  startPadding: Dp = 8.dp,
  endPadding: Dp = 8.dp,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      modifier.width(width + startPadding + endPadding - 16.dp).clickable(enabled = enabled) {
        onClick()
      },
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier =
        Modifier.padding(vertical = 8.dp)
          .padding(start = startPadding, end = endPadding)
          .graphicsLayer { alpha = if (enabled) 1f else 0.3f },
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        icon,
        modifier = Modifier.size(20.dp),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
      )
      if (textResId != null) {
        Text(stringResource(textResId), style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}
