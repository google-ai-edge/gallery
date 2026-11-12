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

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.google.ai.edge.gallery.R

/**
 * Canonical circular Send button used across task prompt input bars.
 *
 * Encapsulates the [Icons.Rounded.ArrowUpward] glyph, Material 3 primary and disabled colors, and
 * localized accessibility description so individual task screens do not construct Send button
 * styling ad hoc.
 */
@Composable
fun SendButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconSize: Dp = Dp.Unspecified,
  contentDescription: String = stringResource(R.string.cd_send_prompt_icon),
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    colors =
      IconButtonDefaults.iconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      ),
    modifier = modifier,
  ) {
    Icon(
      imageVector = Icons.Rounded.ArrowUpward,
      contentDescription = contentDescription,
      modifier = if (iconSize.isSpecified) Modifier.size(iconSize) else Modifier,
    )
  }
}
