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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Round toggle button that shows or hides the live inference metrics banner ([LiveMetricsBar]).
 *
 * @param checked Whether the banner is shown.
 * @param enabled Whether the button can be toggled.
 * @param showPromptLabel Whether the button expands to show the one-time discovery label.
 * @param onToggle Called when the button is toggled.
 * @param modifier The modifier applied to the button.
 */
@Composable
fun LiveMetricsToggleButton(
  checked: Boolean,
  enabled: Boolean,
  showPromptLabel: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor =
    when {
      !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
      checked -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
  val contentColor =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
      checked -> MaterialTheme.colorScheme.onSecondaryContainer
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val startPadding by
    animateDpAsState(
      targetValue = if (showPromptLabel) 11.dp else 10.dp,
      label = "liveMetricsStartPadding",
    )
  val endPadding by
    animateDpAsState(
      targetValue = if (showPromptLabel) 14.dp else 10.dp,
      label = "liveMetricsEndPadding",
    )

  // Uses the toggleable Surface overload so the 40dp circle can expand to hold the one-time
  // discovery prompt label while preserving TalkBack on/off toggle semantics.
  Surface(
    checked = checked,
    onCheckedChange = { onToggle() },
    enabled = enabled,
    shape = CircleShape,
    color = containerColor,
    contentColor = contentColor,
    modifier = modifier.height(40.dp).widthIn(min = 40.dp),
  ) {
    Row(
      modifier = Modifier.padding(start = startPadding, end = endPadding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        Icons.Outlined.InsertChart,
        contentDescription = stringResource(R.string.cd_toggle_live_metrics),
        modifier = Modifier.size(20.dp),
      )
      AnimatedVisibility(visible = showPromptLabel) {
        Text(
          text = stringResource(R.string.live_metrics_toggle_prompt),
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
        )
      }
    }
  }
}
