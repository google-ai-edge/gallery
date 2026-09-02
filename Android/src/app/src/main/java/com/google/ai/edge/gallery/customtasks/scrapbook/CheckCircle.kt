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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CheckCircle(modifier: Modifier = Modifier, checked: Boolean, size: Dp = 20.dp) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .size(size)
        .clip(CircleShape)
        .background(
          if (checked) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
        )
        .border(
          width = 2.dp,
          color =
            if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = CircleShape,
        ),
  ) {
    if (checked) {
      Icon(
        Icons.Rounded.Check,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = Color.White,
      )
    }
  }
}
