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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.proto.VideoClip

/**
 * A reusable card for displaying a saved video clip's thumbnail with an overlay duration label,
 * optional video display name subtitle, click-to-open interaction, and long-press context menu with
 * delete confirmation dialog.
 */
@Composable
fun ClipThumbnailCard(
  clip: VideoClip,
  modifier: Modifier = Modifier,
  videoDisplayName: String? = null,
  onClick: (() -> Unit)? = null,
  onDelete: (() -> Unit)? = null,
) {
  var showMenu by remember { mutableStateOf(false) }
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }

  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    ThumbnailCard(
      relativeThumbnailPath = clip.relativeScreenshotPath,
      durationInSeconds = (clip.endTimeMs - clip.startTimeMs).coerceAtLeast(0L) / 1000L,
      showOverlay = true,
      modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      onClick = onClick,
      onLongClick =
        if (onDelete != null) {
          { showMenu = true }
        } else null,
    ) {
      if (onDelete != null) {
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) },
            onClick = {
              logButtonClick("videomomentfinder_delete_clip_start")
              showMenu = false
              showConfirmDeleteDialog = true
            },
          )
        }
      }
    }

    if (!videoDisplayName.isNullOrEmpty()) {
      Text(
        text = stringResource(R.string.videomomentfinder_from_video, videoDisplayName),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }

  if (showConfirmDeleteDialog && onDelete != null) {
    ConfirmDeleteClipDialog(
      onConfirm = {
        logButtonClick("videomomentfinder_delete_clip_confirm")
        showConfirmDeleteDialog = false
        onDelete()
      },
      onDismiss = { showConfirmDeleteDialog = false },
      onCancelButtonClick = { logButtonClick("videomomentfinder_delete_clip_cancel") },
    )
  }
}
