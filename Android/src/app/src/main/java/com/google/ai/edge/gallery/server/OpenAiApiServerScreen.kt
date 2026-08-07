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

package com.google.ai.edge.gallery.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAiApiServerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: OpenAiApiServerViewModel = hiltViewModel(),
  onBackClicked: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val scrollState = rememberScrollState()

  val frpPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri ->
    uri?.let { viewModel.importFrpBinary(it) }
  }

  LaunchedEffect(Unit) {
    viewModel.updateModels(modelManagerViewModel.getAllDownloadedModels())
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.openai_api_server_title)) },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back_icon))
          }
        }
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .padding(innerPadding)
        .padding(16.dp)
        .fillMaxSize()
        .verticalScroll(scrollState),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
      Text(
        stringResource(R.string.openai_api_server_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      // Server Toggle
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.openai_api_server_enabled),
              style = MaterialTheme.typography.titleMedium
            )
            Text(
              uiState.status.name,
              style = MaterialTheme.typography.bodySmall,
              color = if (uiState.status == OpenAiApiServerStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
          }
          Switch(
            checked = uiState.enabled,
            onCheckedChange = {
              if (it) viewModel.start(uiState.port, uiState.selectedModel)
              else viewModel.stop()
            }
          )
        }
      }

      if (uiState.error.isNotEmpty()) {
        Text(
          uiState.error,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(horizontal = 8.dp)
        )
      }

      // Configuration
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Port
        var portText by remember(uiState.port) { mutableStateOf(uiState.port.toString()) }
        OutlinedTextField(
          value = portText,
          onValueChange = {
            portText = it
            it.toIntOrNull()?.let { port ->
              if (port in 1024..65535) {
                if (uiState.enabled) viewModel.start(port, uiState.selectedModel)
              }
            }
          },
          label = { Text(stringResource(R.string.openai_api_server_port)) },
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true
        )

        // Model Selector
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
          expanded = expanded,
          onExpandedChange = { expanded = !expanded },
          modifier = Modifier.fillMaxWidth()
        ) {
          OutlinedTextField(
            value = uiState.selectedModel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.openai_api_server_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
              .menuAnchor()
              .fillMaxWidth()
          )
          ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
          ) {
            uiState.availableModels.forEach { modelName ->
              DropdownMenuItem(
                text = { Text(modelName) },
                onClick = {
                  expanded = false
                  if (uiState.enabled) viewModel.start(uiState.port, modelName)
                  else viewModel.updateModels(modelManagerViewModel.getAllDownloadedModels(), preferredModel = modelName)
                }
              )
            }
          }
        }
      }

      // FRP Configuration
      Text(
        stringResource(R.string.openai_api_server_frp_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp)
      )
      
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier
              .padding(16.dp)
              .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.openai_api_server_frp_enable), style = MaterialTheme.typography.titleMedium)
              Text(
                if (uiState.frpRunning) stringResource(R.string.openai_api_server_frp_running) else stringResource(R.string.openai_api_server_frp_stopped),
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.frpRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(
              checked = uiState.frpEnabled,
              onCheckedChange = {
                viewModel.updateFrpConfig(it, uiState.frpServerAddr, uiState.frpServerPort, uiState.frpToken, uiState.frpRemotePort, uiState.frpCustomDomain)
              }
            )
          }
          if (!uiState.frpBinaryMissing) {
            TextButton(
              onClick = { viewModel.deleteFrpBinary() },
              modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            ) {
              Text("Reset/Delete frpc binary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
          }
          if (uiState.frpBinaryMissing) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                "frpc binary missing! You can download it from GitHub (arm64-v8a) and import it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                  onClick = { frpPickerLauncher.launch("*/*") },
                  modifier = Modifier.height(32.dp),
                  contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                ) {
                  Text("Import frpc", style = MaterialTheme.typography.labelMedium)
                }
                ClickableLink(
                  url = "https://github.com/fatedier/frp/releases",
                  linkText = "Download from GitHub"
                )
              }
            }
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
          value = uiState.frpServerAddr,
          onValueChange = {
            viewModel.updateFrpConfig(uiState.frpEnabled, it, uiState.frpServerPort, uiState.frpToken, uiState.frpRemotePort, uiState.frpCustomDomain)
          },
          label = { Text(stringResource(R.string.openai_api_server_frp_address)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          OutlinedTextField(
            value = uiState.frpServerPort.toString(),
            onValueChange = {
              it.toIntOrNull()?.let { port ->
                viewModel.updateFrpConfig(uiState.frpEnabled, uiState.frpServerAddr, port, uiState.frpToken, uiState.frpRemotePort, uiState.frpCustomDomain)
              }
            },
            label = { Text(stringResource(R.string.openai_api_server_frp_port)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
          )
          OutlinedTextField(
            value = uiState.frpRemotePort.toString(),
            onValueChange = {
              it.toIntOrNull()?.let { port ->
                viewModel.updateFrpConfig(uiState.frpEnabled, uiState.frpServerAddr, uiState.frpServerPort, uiState.frpToken, port, uiState.frpCustomDomain)
              }
            },
            label = { Text(stringResource(R.string.openai_api_server_frp_remote_port)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
          )
        }

        OutlinedTextField(
          value = uiState.frpCustomDomain,
          onValueChange = {
            viewModel.updateFrpConfig(uiState.frpEnabled, uiState.frpServerAddr, uiState.frpServerPort, uiState.frpToken, uiState.frpRemotePort, it)
          },
          label = { Text(stringResource(R.string.openai_api_server_frp_custom_domain)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        OutlinedTextField(
          value = uiState.frpToken,
          onValueChange = {
            viewModel.updateFrpConfig(uiState.frpEnabled, uiState.frpServerAddr, uiState.frpServerPort, it, uiState.frpRemotePort, uiState.frpCustomDomain)
          },
          label = { Text(stringResource(R.string.openai_api_server_frp_token)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }

      // Connection Details
      if (uiState.status == OpenAiApiServerStatus.RUNNING) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          DetailItem(
            label = stringResource(R.string.openai_api_server_endpoint),
            value = uiState.endpoint,
            onCopy = { copyToClipboard(context, "Endpoint", uiState.endpoint) }
          )

          DetailItem(
            label = stringResource(R.string.openai_api_server_api_key),
            value = uiState.apiKey,
            onCopy = { copyToClipboard(context, "API Key", uiState.apiKey) },
            onRegenerate = { viewModel.regenerateApiKey() }
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              stringResource(R.string.openai_api_server_requests),
              style = MaterialTheme.typography.titleSmall
            )
            Text(
              uiState.requestCount.toString(),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
      
      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun DetailItem(
  label: String,
  value: String,
  onCopy: () -> Unit,
  onRegenerate: (() -> Unit)? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        value,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.weight(1f)
      )
      IconButton(onClick = onCopy) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp))
      }
      if (onRegenerate != null) {
        IconButton(onClick = onRegenerate) {
          Icon(Icons.Rounded.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip = ClipData.newPlainText(label, text)
  clipboard.setPrimaryClip(clip)
}
