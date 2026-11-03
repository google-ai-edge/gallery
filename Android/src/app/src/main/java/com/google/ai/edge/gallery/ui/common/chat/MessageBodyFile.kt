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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Composable function to display the attached file content of a ChatMessageFile.
 *
 * Tapping the file opens it via [onFileClicked]; long-pressing shows a menu to copy its content.
 */
@Composable
fun MessageBodyFile(
  message: ChatMessageFile,
  modifier: Modifier = Modifier,
  onCopyClicked: (String) -> Unit = {},
  onFileClicked: (ChatMessageFile) -> Unit = {},
) {
  val isUser = message.side == ChatSide.USER
  val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
  val subtitleColor =
    if (isUser) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
  val iconColor = if (isUser) Color.White else MaterialTheme.colorScheme.primary

  LongPressCopyContainer(
    copyText = message.content,
    onCopyClicked = onCopyClicked,
    onTap = { onFileClicked(message) },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Icon(
        if (message.isPdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.Article,
        contentDescription = null,
        tint = iconColor,
        modifier = Modifier.size(24.dp),
      )
      Column {
        Text(
          text = message.name,
          style = MaterialTheme.typography.labelLarge,
          color = textColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthIn(max = 240.dp),
        )
        FileSubtitle(
          sizeString = message.sizeString,
          hasText = message.content.isNotBlank(),
          color = subtitleColor,
        )
      }
    }
  }
}

/**
 * The subtitle of an attached file: its size, or a "No readable text" warning when [hasText] is
 * false (e.g. a PDF whose text could not be extracted), so the user knows right away that the model
 * cannot read the file.
 */
@Composable
internal fun FileSubtitle(sizeString: String, hasText: Boolean, color: Color) {
  if (!hasText) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        Icons.Rounded.WarningAmber,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = stringResource(R.string.file_attachment_no_text),
        style = MaterialTheme.typography.bodySmall,
        color = color,
      )
    }
  } else if (sizeString.isNotEmpty()) {
    Text(text = sizeString, style = MaterialTheme.typography.bodySmall, color = color)
  }
}
