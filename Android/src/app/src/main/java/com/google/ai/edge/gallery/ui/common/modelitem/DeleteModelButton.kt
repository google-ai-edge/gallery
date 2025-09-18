/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/** Composable function to display a button for deleting the downloaded model. */
@Composable
fun DeleteModelButton(
  model: Model,
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatus?,
  modifier: Modifier = Modifier,
  showDeleteButton: Boolean = true,
) {
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    when (downloadStatus?.status) {
      // Button to delete the download.
      ModelDownloadStatusType.SUCCEEDED -> {
        if (showDeleteButton) {
          IconButton(onClick = { showConfirmDeleteDialog = true }) {
            Icon(
              Icons.Outlined.Delete,
              contentDescription = stringResource(R.string.cd_delete_icon),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.alpha(0.6f),
            )
          }
        }
      }

      else -> {}
    }
  }

  if (showConfirmDeleteDialog) {
    ConfirmDeleteModelDialog(
      model = model,
      onConfirm = {
        modelManagerViewModel.deleteModel(task = task, model = model)
        showConfirmDeleteDialog = false
      },
      onDismiss = { showConfirmDeleteDialog = false },
    )
  }
}
