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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.McpAuth
import java.net.URI

private val APPROVED_MCP_HOSTS = listOf("googleapis.com")

/** A dialog composable for adding a new MCP server by entering its URL. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpServerFromUrlDialog(
  mcpManagerViewModel: McpManagerViewModel,
  onDismissRequest: () -> Unit,
  onSuccess: () -> Unit,
) {
  val uiState by mcpManagerViewModel.uiState.collectAsState()
  val loading = uiState.loadingMcpServer
  val error = uiState.error

  val interactionSource = remember { MutableInteractionSource() }
  var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
  var isAdding by remember { mutableStateOf(false) }
  var showDisclaimerDialog by remember { mutableStateOf(false) }
  var showDuplicateWarningDialog by remember { mutableStateOf(false) }
  var authType by remember { mutableStateOf(McpAuth.AuthMethodCase.NONE) }
  var dropdownExpanded by remember { mutableStateOf(false) }
  var headerName by remember { mutableStateOf(TextFieldValue("")) }
  var headerValue by remember { mutableStateOf(TextFieldValue("")) }

  val safeDismiss: () -> Unit = {
    mcpManagerViewModel.clearError()
    onDismissRequest()
  }

  // Effect to handle the result of adding an MCP server when the loading state changes.
  LaunchedEffect(loading) {
    if (isAdding && !loading) {
      if (error == null) {
        mcpManagerViewModel.clearError()
        onDismissRequest()
        onSuccess()
      } else {
        isAdding = false
        textFieldValue = textFieldValue.copy(selection = TextRange(0, textFieldValue.text.length))
      }
    }
  }

  Dialog(onDismissRequest = { if (!loading) safeDismiss() }) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null,
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog Title
        Text(
          stringResource(R.string.add_mcp_server_from_url_dialog_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 8.dp),
        )
        // Container for input label and text field
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            stringResource(R.string.enter_mcp_server_url),
            style = MaterialTheme.typography.labelMedium,
          )
          // Input field for the URL
          OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
              val oldText = textFieldValue.text
              textFieldValue = newValue
              if (newValue.text != oldText) {
                mcpManagerViewModel.clearError()
              }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            trailingIcon = {
              if (textFieldValue.text.isNotEmpty()) {
                IconButton(
                  onClick = {
                    textFieldValue = TextFieldValue("")
                    mcpManagerViewModel.clearError()
                  }
                ) {
                  Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.clear))
                }
              }
            },
          )
          // Error message if adding the server failed.
          error?.let { errorMsg ->
            Text(
              text = errorMsg,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 3,
              overflow = TextOverflow.MiddleEllipsis,
            )
          }
        }

        // Authorization section
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          // Authorization selector.
          Text(
            stringResource(R.string.mcp_server_authorization),
            style = MaterialTheme.typography.labelMedium,
          )
          ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = !dropdownExpanded },
          ) {
            OutlinedTextField(
              value =
                when (authType) {
                  McpAuth.AuthMethodCase.NONE -> stringResource(R.string.mcp_server_auth_none)
                  McpAuth.AuthMethodCase.REQUEST_HEADER ->
                    stringResource(R.string.mcp_server_auth_request_header)
                  McpAuth.AuthMethodCase.OAUTH -> stringResource(R.string.mcp_server_auth_oauth_wip)
                  else -> stringResource(R.string.mcp_server_auth_none)
                },
              onValueChange = {},
              readOnly = true,
              modifier =
                Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable)
                  .fillMaxWidth(),
              textStyle = MaterialTheme.typography.bodySmall,
              trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
              },
              colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
              expanded = dropdownExpanded,
              onDismissRequest = { dropdownExpanded = false },
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.mcp_server_auth_none)) },
                onClick = {
                  authType = McpAuth.AuthMethodCase.NONE
                  dropdownExpanded = false
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.mcp_server_auth_request_header)) },
                onClick = {
                  authType = McpAuth.AuthMethodCase.REQUEST_HEADER
                  dropdownExpanded = false
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.mcp_server_auth_oauth_wip)) },
                onClick = {
                  authType = McpAuth.AuthMethodCase.OAUTH
                  dropdownExpanded = false
                },
                enabled = false,
              )
            }
          }
        }

        // Conditional fields for Request Header
        if (authType == McpAuth.AuthMethodCase.REQUEST_HEADER) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              stringResource(R.string.mcp_server_header_name),
              style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
              value = headerName,
              onValueChange = { headerName = it },
              modifier = Modifier.fillMaxWidth(),
              trailingIcon = {
                if (headerName.text.isNotEmpty()) {
                  IconButton(onClick = { headerName = TextFieldValue("") }) {
                    Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.clear))
                  }
                }
              },
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              stringResource(R.string.mcp_server_header_value),
              style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
              value = headerValue,
              onValueChange = { headerValue = it },
              modifier = Modifier.fillMaxWidth(),
              trailingIcon = {
                if (headerValue.text.isNotEmpty()) {
                  IconButton(onClick = { headerValue = TextFieldValue("") }) {
                    Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.clear))
                  }
                }
              },
            )
          }
        }

        if (loading && isAdding) {
          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        } else {
          // Button row.
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(onClick = safeDismiss) { Text(stringResource(R.string.cancel)) }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
              enabled = textFieldValue.text.trim().isNotEmpty(),
              onClick = {
                val url = textFieldValue.text.trim()
                if (url.isNotEmpty()) {
                  if (mcpManagerViewModel.hasMcpServer(url)) {
                    showDuplicateWarningDialog = true
                  } else if (isMcpHostApproved(url)) {
                    isAdding = true
                    mcpManagerViewModel.addMcpServer(
                      url,
                      authType,
                      headerName.text,
                      headerValue.text,
                    )
                  } else {
                    showDisclaimerDialog = true
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

  if (showDuplicateWarningDialog) {
    AlertDialog(
      onDismissRequest = { showDuplicateWarningDialog = false },
      title = { Text(stringResource(R.string.mcp_server_duplicate_title)) },
      text = { Text(stringResource(R.string.mcp_server_duplicate_content)) },
      confirmButton = {
        Button(onClick = { showDuplicateWarningDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // Shown conditionally for unapproved hosts
  if (showDisclaimerDialog) {
    AddMcpDisclaimerDialog(
      onDismiss = { showDisclaimerDialog = false },
      onConfirm = {
        showDisclaimerDialog = false
        val url = textFieldValue.text.trim()
        if (url.isNotEmpty()) {
          isAdding = true
          mcpManagerViewModel.addMcpServer(url, authType, headerName.text, headerValue.text)
        }
      },
    )
  }
}

private fun isMcpHostApproved(url: String): Boolean {
  return try {
    val uri = URI(url).normalize()
    val parsedHost = uri.host?.lowercase() ?: return false

    APPROVED_MCP_HOSTS.any { allowed -> parsedHost == allowed || parsedHost.endsWith(".$allowed") }
  } catch (e: Exception) {
    false // Invalid URI syntax
  }
}
