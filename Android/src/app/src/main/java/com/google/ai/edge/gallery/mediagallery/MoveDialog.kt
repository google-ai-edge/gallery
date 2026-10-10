/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Pick an existing folder or name a new one under Pictures/. Returns a relative path. */
@Composable
fun MoveDialog(folders: List<MediaFolder>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
  var newName by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Verschieben nach") },
    text = {
      Column {
        OutlinedTextField(
          newName,
          { newName = it },
          label = { Text("Neuer Ordner") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = newName.isNotBlank(), onClick = { onPick(MediaActions.newFolderPath(newName)) }) {
          Text("Neuen Ordner anlegen")
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
          items(folders.filter { it.relativePath.isNotBlank() }, key = { it.bucketId }) { f ->
            Column(Modifier.fillMaxWidth().clickable { onPick(f.relativePath) }.padding(vertical = 10.dp)) {
              Text(f.name, style = MaterialTheme.typography.bodyLarge)
              Text(f.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
  )
}
