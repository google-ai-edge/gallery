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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FloatingBanner(
  visible: Boolean,
  text: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  AnimatedVisibility(
    visible = visible,
    enter = slideInVertically() + fadeIn(),
    exit = fadeOut() + slideOutVertically(),
    modifier = modifier,
  ) {
    val hasAction = actionLabel != null && onActionClick != null
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
          .padding(
            start = 16.dp,
            end = if (hasAction) 8.dp else 16.dp,
            top = if (hasAction) 6.dp else 16.dp,
            bottom = if (hasAction) 6.dp else 16.dp,
          )
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        if (hasAction) {
          TextButton(onClick = onActionClick) {
            Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    }
  }
}
