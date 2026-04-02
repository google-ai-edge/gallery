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

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R

private const val TAG = "AGAddSkillFromLocalImportDialog"

@Composable
fun AddSkillFromLocalImportDialog(
  skillManagerViewModel: SkillManagerViewModel,
  onDismissRequest: () -> Unit,
  onSuccess: () -> Unit,
) {
  val uiState by skillManagerViewModel.uiState.collectAsState()
  val validating = uiState.validating
  val validationError = uiState.validationError
  val directoryUri = uiState.importDirectoryUri
  var showReplaceSkillConfirmationDialog by remember { mutableStateOf(false) }

  val context = LocalContext.current

  val directoryPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      skillManagerViewModel.setImportDirectoryUri(uri)
      skillManagerViewModel.setValidationError(null)
    }

  Dialog(onDismissRequest = onDismissRequest) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
          // Title.
          Text(
            text = stringResource(R.string.add_skill_dialog_title_from_local_import),
            style = MaterialTheme.typography.titleMedium,
          )

          // Subtitle.
          Text(
            text = stringResource(R.string.add_skill_dialog_subtitle_from_local_import),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp),
          )
        }

        // Row: Directory Picker
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.pick_skill_dir),
            style = MaterialTheme.typography.labelMedium,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(
              modifier =
                Modifier.weight(1f)
                  .clip(RoundedCornerShape(4.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
              Text(
                text =
                  directoryUri?.let { getDisplayName(context, it) }
                    ?: stringResource(R.string.no_directory_selected),
                style = MaterialTheme.typography.bodyMedium,
                color =
                  if (directoryUri == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
              Icon(
                Icons.Outlined.FileOpen,
                contentDescription = stringResource(R.string.cd_pick_file),
              )
            }
          }
          validationError?.let { error ->
            Text(
              text = error,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        // Show spinner when validating.
        if (validating) {
          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        } else {
          // Button row
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              enabled = directoryUri != null,
              onClick = {
                directoryUri?.let { uri ->
                  if (skillManagerViewModel.checkLocalSkillExisted(uri)) {
                    showReplaceSkillConfirmationDialog = true
                  } else {
                    skillManagerViewModel.validateAndAddSkillFromLocalImport(
                      onSuccess = {
                        onDismissRequest()
                        onSuccess()
                      },
                      onValidationError = {},
                    )
                  }
                }
              },
            ) {
              Text(stringResource(R.string.add))
            }
          }
        }
      }
    }
  }

  if (showReplaceSkillConfirmationDialog) {
    AlertDialog(
      onDismissRequest = { showReplaceSkillConfirmationDialog = false },
      title = { Text(stringResource(R.string.replace_skill_dialog_title)) },
      text = { Text(stringResource(R.string.replace_skill_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            showReplaceSkillConfirmationDialog = false
            skillManagerViewModel.validateAndAddSkillFromLocalImport(
              onSuccess = {
                onDismissRequest()
                onSuccess()
              },
              onValidationError = {},
            )
          }
        ) {
          Text(stringResource(R.string.replace))
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showReplaceSkillConfirmationDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}
