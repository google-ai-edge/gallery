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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun Accordions(
  title: String,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String = "",
  boldTitle: Boolean = false,
  bgColor: Color = MaterialTheme.colorScheme.surface,
  titleRowAction: @Composable () -> Unit = {},
  hideTitleRowActionOnCollapse: Boolean = false,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier.background(bgColor).padding(8.dp)) {
    // Title.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier =
        Modifier.clip(RoundedCornerShape(8.dp))
          .clickable { onExpandedChange(!expanded) }
          .fillMaxWidth(),
    ) {
      Icon(
        if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowRight,
        contentDescription = null,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          title,
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontWeight = if (boldTitle) FontWeight.SemiBold else FontWeight.Normal
            ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
        if (subtitle.isNotEmpty()) {
          Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (hideTitleRowActionOnCollapse) {
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
          if (expanded) {
            titleRowAction()
          }
        }
      } else {
        titleRowAction()
      }
    }

    // Content.
    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
      Box(
        modifier = Modifier.padding(start = 4.dp).padding(top = 8.dp),
        contentAlignment = Alignment.TopStart,
      ) {
        content()
      }
    }
  }
}
