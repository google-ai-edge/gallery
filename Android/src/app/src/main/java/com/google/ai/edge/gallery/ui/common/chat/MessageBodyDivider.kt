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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Composable function to display a horizontal divider with centered label text. */
@Composable
fun LabeledDivider(text: String, modifier: Modifier = Modifier) {
  if (text.isBlank()) {
    HorizontalDivider(
      modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
      color = MaterialTheme.colorScheme.outlineVariant,
    )
  } else {
    Row(
      modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      HorizontalDivider(
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.outlineVariant,
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
      )
      HorizontalDivider(
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.outlineVariant,
      )
    }
  }
}

/** Composable function to display a [ChatMessageDivider] within the chat message stream. */
@Composable
fun MessageBodyDivider(message: ChatMessageDivider, modifier: Modifier = Modifier) {
  val resolvedText = if (message.textResId != 0) stringResource(message.textResId) else message.text
  LabeledDivider(text = resolvedText, modifier = modifier)
}
