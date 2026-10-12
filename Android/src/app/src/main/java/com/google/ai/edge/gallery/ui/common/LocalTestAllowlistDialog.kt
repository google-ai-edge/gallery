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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R

/**
 * Warns that the model allowlist was loaded from a local test file instead of the regular source,
 * so that a leftover test file is never mistaken for the real model allowlist.
 *
 * @param filePath Absolute on-device path of the local test allowlist file that was loaded.
 * @param onDismiss Called when the user acknowledges or dismisses the dialog.
 */
@Composable
fun LocalTestAllowlistDialog(filePath: String, onDismiss: () -> Unit) {
  AlertDialog(
    icon = {
      Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    },
    title = { Text(stringResource(R.string.local_test_allowlist_dialog_title)) },
    text = { Text(stringResource(R.string.local_test_allowlist_dialog_message, filePath)) },
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
  )
}
