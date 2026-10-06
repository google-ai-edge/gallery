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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING

@Composable
fun ConfirmDeleteProjectDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  onCancelButtonClick: () -> Unit = {},
) {
  ConfirmDeleteDialog(
    message = stringResource(R.string.videomomentfinder_confirm_delete_project),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    onCancelButtonClick = onCancelButtonClick,
  )
}

@Composable
fun ConfirmDeleteClipDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  onCancelButtonClick: () -> Unit = {},
) {
  ConfirmDeleteDialog(
    message = stringResource(R.string.videomomentfinder_confirm_delete_clip),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    onCancelButtonClick = onCancelButtonClick,
  )
}

/**
 * A delete confirmation dialog.
 *
 * [onDismiss] closes the dialog and runs for every dismissal, including a tap outside the dialog
 * and the system back gesture. [onCancelButtonClick] runs only when the Cancel button itself is
 * pressed, and is intended for analytics that must not count those other dismissals as a button
 * press. It runs in addition to [onDismiss], not instead of it.
 */
@Composable
fun ConfirmDeleteDialog(
  message: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  onCancelButtonClick: () -> Unit = {},
) {
  AlertDialog(
    onDismissRequest = { onDismiss() },
    title = { Text(stringResource(R.string.delete)) },
    text = { Text(message) },
    confirmButton = {
      Button(onClick = { onConfirm() }, contentPadding = SMALL_BUTTON_CONTENT_PADDING) {
        Text(stringResource(R.string.delete))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = {
          onCancelButtonClick()
          onDismiss()
        },
        contentPadding = SMALL_BUTTON_CONTENT_PADDING,
      ) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
