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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.ui.common.SmallFilledTonalButton
import com.google.ai.edge.gallery.ui.common.SmallOutlinedButton
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A bottom sheet that displays and manages the list of tools for a specific MCP server, allowing
 * users to toggle tool enablement and view detailed schemas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolManagerBottomSheet(
  mcpManagerViewModel: McpManagerViewModel,
  serverUrl: String,
  onDismiss: () -> Unit,
) {
  val uiState by mcpManagerViewModel.uiState.collectAsState()
  val serverState = uiState.mcpServers.find { it.mcpServer.url == serverUrl }
  val server = serverState?.mcpServer ?: return

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  var toolToView by remember { mutableStateOf<McpTool?>(null) }
  var toolToRevoke by remember { mutableStateOf<McpTool?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxSize()) {
      // Title Row
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.manage_tools),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          modifier = Modifier.padding(end = 3.dp),
          onClick = {
            scope.launch {
              sheetState.hide()
              onDismiss()
            }
          },
        ) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
        }
      }

      // MCP server label
      val serverName = server.name.ifEmpty { server.url }
      Text(
        text = stringResource(R.string.mcp_server_label, serverName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
      )

      // Tool Count and Action Row
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      ) {
        val toolsCount = server.toolsList.size
        Text(
          text = pluralStringResource(R.plurals.mcp_tools_count, toolsCount, toolsCount),
          style = MaterialTheme.typography.labelLarge,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { mcpManagerViewModel.setAllMcpToolsEnabled(server.url, true) }) {
            Text(stringResource(R.string.turn_on_all))
          }
          TextButton(onClick = { mcpManagerViewModel.setAllMcpToolsEnabled(server.url, false) }) {
            Text(stringResource(R.string.turn_off_all))
          }
        }
      }

      // List of tools
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(server.toolsList, key = { it.name }) { tool ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(shape = RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              // Tool name, description, and enable toggle
              Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.weight(1f).padding(end = 8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                  )
                  if (tool.description.isNotEmpty()) {
                    Text(
                      text = tool.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 3,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                Switch(
                  checked = tool.enabled,
                  onCheckedChange = { enabled ->
                    mcpManagerViewModel.setMcpToolEnabled(server.url, tool.name, enabled)
                  },
                  modifier = Modifier.offset(y = (-4).dp),
                )
              }

              // Action buttons for viewing schema and revoking "always allow"
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.padding(top = 8.dp),
              ) {
                SmallFilledTonalButton(
                  onClick = { toolToView = tool },
                  labelResId = R.string.view,
                  imageVector = Icons.Outlined.RemoveRedEye,
                )
                if (tool.alwaysAllow) {
                  Spacer(modifier = Modifier.width(8.dp))
                  SmallOutlinedButton(
                    onClick = { toolToRevoke = tool },
                    labelResId = R.string.mcp_tool_revoke_permission,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  toolToView?.let { tool ->
    AlertDialog(
      onDismissRequest = { toolToView = null },
      title = { Text(tool.name) },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (tool.description.isNotEmpty()) {
            Column {
              Text(
                text = stringResource(R.string.mcp_tool_description),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(tool.description, style = MaterialTheme.typography.bodyMedium)
            }
          }
          if (tool.inputSchema.isNotEmpty()) {
            Column {
              Text(
                text = stringResource(R.string.mcp_tool_schema),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
              val formattedSchema =
                remember(tool.inputSchema) {
                  try {
                    JSONObject(tool.inputSchema).toString(2)
                  } catch (e: Exception) {
                    tool.inputSchema
                      .replace("properties={", "properties={\n  ")
                      .replace(", required=[", ",\nrequired=[\n  ")
                  }
                }
              Text(
                text = formattedSchema,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        }
      },
      confirmButton = {
        Button(onClick = { toolToView = null }) { Text(stringResource(R.string.close)) }
      },
    )
  }

  // AlertDialog to confirm revoking "always allow" permission for a tool.
  toolToRevoke?.let { tool ->
    AlertDialog(
      onDismissRequest = { toolToRevoke = null },
      title = { Text(stringResource(R.string.mcp_tool_revoke_permission_title)) },
      text = { Text(stringResource(R.string.mcp_tool_revoke_permission_content)) },
      confirmButton = {
        Button(
          onClick = {
            mcpManagerViewModel.setMcpToolAlwaysAllow(server.url, tool.name, false)
            toolToRevoke = null
          }
        ) {
          Text(stringResource(R.string.mcp_tool_revoke))
        }
      },
      dismissButton = {
        TextButton(onClick = { toolToRevoke = null }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
}
