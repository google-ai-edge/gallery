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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.clearFocusOnKeyboardDismiss
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.SmallFilledTonalButton
import com.google.ai.edge.gallery.ui.common.SmallOutlinedButton
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A bottom sheet that allows users to manage configured MCP servers, search through them, add new
 * ones, toggle their enabled states, and launch secondary views for detailed tool inspection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun McpManagerBottomSheet(
  mcpManagerViewModel: McpManagerViewModel,
  onDismiss: (selectMcpsAndToolsChanged: Boolean) -> Unit,
) {
  val uiState by mcpManagerViewModel.uiState.collectAsState()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  var searchQuery by remember { mutableStateOf("") }
  var showAddMcpServerDialog by remember { mutableStateOf(false) }
  var showDeleteServerDialog by remember { mutableStateOf(false) }
  var serverToDeleteUrl by remember { mutableStateOf("") }
  var serverForToolsUrl by remember { mutableStateOf<String?>(null) }
  var savedSelectedMcpsAndToolsSummary by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    savedSelectedMcpsAndToolsSummary = mcpManagerViewModel.getSelectedMcpsAndToolsSummary()
  }

  val listState = rememberLazyListState()
  val focusManager = LocalFocusManager.current

  val trimmedQuery = searchQuery.trim().lowercase()
  val filteredServers =
    remember(uiState.mcpServers, trimmedQuery) {
      if (trimmedQuery.isEmpty()) {
        uiState.mcpServers
      } else {
        uiState.mcpServers.filter { serverState ->
          val server = serverState.mcpServer
          server.name.lowercase().contains(trimmedQuery) ||
            server.url.lowercase().contains(trimmedQuery) ||
            server.toolsList.any { tool -> tool.name.lowercase().contains(trimmedQuery) }
        }
      }
    }

  ModalBottomSheet(
    onDismissRequest = {
      onDismiss(
        savedSelectedMcpsAndToolsSummary != mcpManagerViewModel.getSelectedMcpsAndToolsSummary()
      )
    },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      // Show spinner when loading the initial server list.
      if (uiState.loadingMcpServer && uiState.mcpServers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp,
            modifier = Modifier.size(24.dp),
          )
        }
      }
      // Show empty state view when no MCP server has been added.
      else if (uiState.mcpServers.isEmpty()) {
        EmptyMcpServerView(
          onAddClick = { showAddMcpServerDialog = true },
          onDismiss = {
            scope.launch {
              sheetState.hide()
              onDismiss(
                savedSelectedMcpsAndToolsSummary !=
                  mcpManagerViewModel.getSelectedMcpsAndToolsSummary()
              )
            }
          },
        )
      }
      // Show the list of configured MCP servers.
      else {
        Column(
          modifier =
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxSize().pointerInput(
              Unit
            ) {
              detectTapGestures(onTap = { focusManager.clearFocus() })
            }
        ) {
          // Title Row
          Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                stringResource(R.string.manage_mcp_servers),
                style = MaterialTheme.typography.titleLarge,
              )
              ClickableLink(
                url = "https://github.com/google-ai-edge/gallery/tree/main/mcp",
                linkText = stringResource(R.string.learn_more_about_mcp),
                modifier = Modifier.padding(top = 4.dp),
                textAlign = TextAlign.Start,
              )
            }
            IconButton(
              modifier = Modifier.padding(end = 3.dp),
              onClick = {
                scope.launch {
                  sheetState.hide()
                  onDismiss(
                    savedSelectedMcpsAndToolsSummary !=
                      mcpManagerViewModel.getSelectedMcpsAndToolsSummary()
                  )
                }
              },
            ) {
              Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
            }
          }

          // Search and Add Button Row
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
              Modifier.padding(top = 8.dp, bottom = if (searchQuery.isEmpty()) 8.dp else 18.dp)
                .height(IntrinsicSize.Min),
          ) {
            TextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.weight(1f).clearFocusOnKeyboardDismiss(),
              shape = CircleShape,
              placeholder = { Text(stringResource(R.string.search_mcp_server)) },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
              trailingIcon = {
                if (searchQuery.trim().isNotEmpty()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Outlined.Cancel, contentDescription = null)
                  }
                }
              },
              singleLine = true,
              colors =
                TextFieldDefaults.colors(
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                  disabledIndicatorColor = Color.Transparent,
                ),
            )

            // Add Button
            Box(
              modifier =
                Modifier.fillMaxHeight()
                  .aspectRatio(1f)
                  .clip(CircleShape)
                  .clickable {
                    searchQuery = ""
                    showAddMcpServerDialog = true
                  }
                  .background(MaterialTheme.colorScheme.primary),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.cd_add_icon),
                tint = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }

          AnimatedVisibility(visible = searchQuery.isEmpty()) {
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
              // MCP Server Count
              Text(
                pluralStringResource(
                  R.plurals.mcp_servers_count,
                  uiState.mcpServers.size,
                  uiState.mcpServers.size,
                ),
                style = MaterialTheme.typography.labelLarge,
              )

              // Select all / Deselect all
              Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { mcpManagerViewModel.setAllMcpServerEnabled(true) }) {
                  Text(stringResource(R.string.turn_on_all))
                }
                TextButton(onClick = { mcpManagerViewModel.setAllMcpServerEnabled(false) }) {
                  Text(stringResource(R.string.turn_off_all))
                }
              }
            }
          }

          // Server List Content
          CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Box(modifier = Modifier.weight(1f)) {
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(filteredServers, key = { it.mcpServer.url }) { serverState ->
                  McpServerItemRow(
                    serverState = serverState,
                    onEnabledChange = { enabled ->
                      mcpManagerViewModel.setMcpServerEnabled(serverState.mcpServer.url, enabled)
                    },
                    onToolsClick = { serverForToolsUrl = serverState.mcpServer.url },
                    onDeleteClick = {
                      serverToDeleteUrl = serverState.mcpServer.url
                      showDeleteServerDialog = true
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showDeleteServerDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteServerDialog = false },
      title = { Text(stringResource(R.string.delete_mcp_server_dialog_title)) },
      text = { Text(stringResource(R.string.delete_mcp_server_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            mcpManagerViewModel.removeMcpServer(serverToDeleteUrl)
            showDeleteServerDialog = false
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.errorTextColor,
              contentColor = Color.White,
            ),
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showDeleteServerDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (showAddMcpServerDialog) {
    AddMcpServerFromUrlDialog(
      mcpManagerViewModel = mcpManagerViewModel,
      onDismissRequest = { showAddMcpServerDialog = false },
      onSuccess = {
        scope.launch {
          delay(300)
          if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
          }
        }
      },
    )
  }

  serverForToolsUrl?.let { url ->
    McpToolManagerBottomSheet(
      mcpManagerViewModel = mcpManagerViewModel,
      serverUrl = url,
      onDismiss = { serverForToolsUrl = null },
    )
  }
}

@Composable
private fun EmptyMcpServerView(onAddClick: () -> Unit, onDismiss: () -> Unit) {
  val focusManager = LocalFocusManager.current

  Column(
    modifier =
      Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxSize().pointerInput(
        Unit
      ) {
        detectTapGestures(onTap = { focusManager.clearFocus() })
      }
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(modifier = Modifier.padding(end = 3.dp), onClick = onDismiss) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
      }
    }
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
          onClick = onAddClick,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.add_mcp_server))
        }
        ClickableLink(
          url = "https://github.com/google-ai-edge/gallery/tree/main/mcp",
          linkText = stringResource(R.string.learn_more_about_mcp),
          modifier = Modifier.padding(top = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun McpServerItemRow(
  serverState: McpServerState,
  onEnabledChange: (Boolean) -> Unit,
  onToolsClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  val server = serverState.mcpServer
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape = RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Column to display server details like name, URL, and any error messages.
        Column(
          modifier = Modifier.weight(1f).padding(top = 2.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          val hasName = server.name.isNotEmpty()
          val primaryText = if (hasName) server.name else server.url
          // Displays the server name, or just the URL if the name is empty.
          Text(
            primaryText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          )
          // Displays the server version if available when a name is present.
          if (hasName && server.version.isNotEmpty()) {
            Text(
              "v${server.version}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // Displays the server URL below the version if the name is used as title.
          if (hasName) {
            Text(
              server.url,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.MiddleEllipsis,
            )
          }
          // Displays an error message if the server has one.
          serverState.error?.let { errorMsg ->
            Text(
              text = errorMsg,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Switch to toggle the enabled state of the MCP server.
        Switch(
          checked = server.enabled,
          onCheckedChange = onEnabledChange,
          enabled = serverState.error == null,
          modifier =
            Modifier.offset(y = (-4).dp).semantics { contentDescription = "Toggle ${server.url}" },
        )
      }

      // Buttons row
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.padding(top = 16.dp),
      ) {
        val enabledToolsCount = server.toolsList.count { it.enabled }
        val totalToolsCount = server.toolsList.size
        SmallFilledTonalButton(
          onClick = onToolsClick,
          label = "Tools ($enabledToolsCount/$totalToolsCount)",
          imageVector = Icons.Outlined.Tune,
          enabled = serverState.error == null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        SmallOutlinedButton(
          onClick = onDeleteClick,
          labelResId = R.string.delete,
          imageVector = Icons.Outlined.Delete,
        )
      }
    }
  }
}
