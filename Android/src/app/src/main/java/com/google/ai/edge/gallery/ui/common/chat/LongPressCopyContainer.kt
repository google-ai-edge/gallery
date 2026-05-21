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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * A container that wraps a composable with long-press gesture detection to show a dropdown menu for
 * copying text.
 */
@Composable
fun LongPressCopyContainer(
  copyText: String,
  modifier: Modifier = Modifier,
  onCopyClicked: (String) -> Unit = {},
  content: @Composable () -> Unit,
) {
  var showMenu by remember { mutableStateOf(false) }
  val haptic = LocalHapticFeedback.current
  val moreOptionsLabel = stringResource(R.string.cd_more_options)
  Box(
    modifier =
      modifier
        .pointerInput(Unit) {
          detectTapGestures(
            onLongPress = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              showMenu = true
            }
          )
        }
        .semantics {
          onLongClick(moreOptionsLabel) {
            showMenu = true
            true
          }
        }
  ) {
    content()
    DropdownMenu(
      expanded = showMenu,
      onDismissRequest = { showMenu = false },
      shape = RoundedCornerShape(24.dp),
      tonalElevation = 8.dp,
      shadowElevation = 8.dp,
    ) {
      DropdownMenuItem(
        text = {
          Text(
            stringResource(R.string.copy),
            style =
              MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          )
        },
        leadingIcon = {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.copy),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = {
          showMenu = false
          onCopyClicked(copyText)
        },
      )
    }
  }
}
