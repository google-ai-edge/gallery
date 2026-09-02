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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING

@Composable
fun ConfirmDeleteCutoutsDialog(
  indicesToDelete: Set<Int>,
  viewModel: ScrapbookViewModel,
  inCollageEditor: Boolean,
  onDeleted: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = {},
    title = {
      Text(
        stringResource(
          if (inCollageEditor) R.string.delete_cutout_title
          else R.string.delete_selected_cutouts_title
        )
      )
    },
    text = {
      Text(
        stringResource(
          if (inCollageEditor) R.string.delete_cutout_msg else R.string.delete_selected_cutouts_msg
        )
      )
    },
    confirmButton = {
      Button(
        onClick = { viewModel.deleteCutouts(indices = indicesToDelete, onDone = { onDeleted() }) },
        contentPadding = SMALL_BUTTON_CONTENT_PADDING,
      ) {
        Text(stringResource(R.string.delete))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = { onDismiss() }, contentPadding = SMALL_BUTTON_CONTENT_PADDING) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
