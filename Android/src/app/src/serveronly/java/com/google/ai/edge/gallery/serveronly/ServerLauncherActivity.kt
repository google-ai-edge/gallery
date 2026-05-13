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

package com.google.ai.edge.gallery.serveronly

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.server.ImportedModelMeta
import com.google.ai.edge.gallery.server.LlmServerService
import com.google.ai.edge.gallery.server.ServerModelHolder
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sole entry point for the standalone Edge Gallery Server APK.
 *
 * Lets the user:
 * - import models from the full Edge Gallery app (via [ModelImportRepository])
 * - choose which imported model the server should auto-load
 * - start / stop the embedded HTTP server
 */
class ServerLauncherActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) { ServerLauncherScreen() }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerLauncherScreen() {
  val context = LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()

  var serverRunning by remember { mutableStateOf(LlmServerService.isRunning) }
  var localIp by remember { mutableStateOf(findLocalIpv4() ?: "<unknown>") }
  var imported by remember { mutableStateOf(ModelImportRepository.listImported(context)) }
  var defaultModel by remember { mutableStateOf(ModelImportRepository.getDefaultModel(context)) }
  var activeModel by remember { mutableStateOf(ServerModelHolder.activeModel?.name) }

  var showImportDialog by remember { mutableStateOf(false) }
  var importBusy by remember { mutableStateOf(false) }
  var importMessage by remember { mutableStateOf<String?>(null) }

  // Periodic refresh so the UI tracks server-side state changes (notification's "Stop" action,
  // an auto-load completing, etc.) without us building a full event bus.
  LaunchedEffect(Unit) {
    while (true) {
      serverRunning = LlmServerService.isRunning
      activeModel = ServerModelHolder.activeModel?.name
      delay(750)
    }
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text("Edge Gallery Server") }) },
  ) { padding ->
    Column(
      modifier =
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      StatusCard(
        running = serverRunning,
        localIp = localIp,
        port = LlmServerService.DEFAULT_PORT,
        activeModel = activeModel,
      )

      ImportSection(
        importedCount = imported.size,
        onImportClicked = { showImportDialog = true },
        importMessage = importMessage,
      )

      DefaultModelSection(
        imported = imported,
        defaultModel = defaultModel,
        onDefaultModelChange = { name ->
          ModelImportRepository.setDefaultModel(context, name)
          defaultModel = name
        },
      )

      ServerControls(
        running = serverRunning,
        canStart = !defaultModel.isNullOrEmpty(),
        onStart = {
          LlmServerService.start(context, LlmServerService.DEFAULT_PORT)
          serverRunning = true
        },
        onStop = {
          LlmServerService.stop(context)
          serverRunning = false
        },
        onRefreshIp = { localIp = findLocalIpv4() ?: "<unknown>" },
      )
    }
  }

  if (showImportDialog) {
    ImportDialog(
      busy = importBusy,
      onDismiss = { if (!importBusy) showImportDialog = false },
      onConfirm = { selected, onProgress ->
        importBusy = true
        scope.launch {
          val results =
            withContext(Dispatchers.IO) {
              selected.map { remote ->
                remote to
                  ModelImportRepository.importModel(context, remote) { p ->
                    onProgress(remote, p)
                  }
              }
            }
          val ok = results.count { it.second }
          val failed = results.size - ok
          importMessage =
            if (failed == 0) "Imported $ok model(s) successfully."
            else "Imported $ok of ${results.size} model(s); $failed failed."
          imported = ModelImportRepository.listImported(context)
          importBusy = false
          showImportDialog = false
        }
      },
    )
  }
}

@Composable
private fun StatusCard(running: Boolean, localIp: String, port: Int, activeModel: String?) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Server status", fontWeight = FontWeight.SemiBold)
      Text(if (running) "Running" else "Stopped", style = MaterialTheme.typography.bodyLarge)
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      Text("Listening on http://$localIp:$port", style = MaterialTheme.typography.bodyMedium)
      Text(
        "POST to /v1/chat/completions from any client on the same network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (activeModel != null) {
        Text(
          "Active model: $activeModel",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      } else {
        Text(
          "No model loaded yet.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun ImportSection(
  importedCount: Int,
  onImportClicked: () -> Unit,
  importMessage: String?,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Import models", fontWeight = FontWeight.SemiBold)
      Text(
        "Copy models from the Edge Gallery app once. The standalone server then runs without it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "Currently imported: $importedCount model(s)",
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = onImportClicked) { Text("Import from Edge Gallery") }
      if (importMessage != null) {
        Text(importMessage, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultModelSection(
  imported: List<ImportedModelMeta>,
  defaultModel: String?,
  onDefaultModelChange: (String?) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Default model", fontWeight = FontWeight.SemiBold)
      Text(
        "Loaded automatically when the server starts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (imported.isEmpty()) {
        Text(
          "Import a model first.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        return@Card
      }
      var expanded by remember { mutableStateOf(false) }
      ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        TextField(
          value = defaultModel ?: "(none)",
          onValueChange = {},
          readOnly = true,
          label = { Text("Pick a model") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
          modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
        ) {
          imported.forEach { meta ->
            DropdownMenuItem(
              text = { Text(meta.name) },
              onClick = {
                onDefaultModelChange(meta.name)
                expanded = false
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ServerControls(
  running: Boolean,
  canStart: Boolean,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onRefreshIp: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Server controls", fontWeight = FontWeight.SemiBold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
          OutlinedButton(onClick = onStop) { Text("Stop server") }
        } else {
          Button(onClick = onStart, enabled = canStart) { Text("Start server") }
        }
        OutlinedButton(onClick = onRefreshIp) { Text("Refresh IP") }
      }
      if (!canStart && !running) {
        Text(
          "Pick a default model before starting.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun ImportDialog(
  busy: Boolean,
  onDismiss: () -> Unit,
  onConfirm:
    (
      selected: List<ModelImportRepository.RemoteModel>,
      onProgress: (ModelImportRepository.RemoteModel, Float) -> Unit,
    ) -> Unit,
) {
  val context = LocalContext.current
  var loading by remember { mutableStateOf(true) }
  var available by remember { mutableStateOf<List<ModelImportRepository.RemoteModel>>(emptyList()) }
  val selected = remember { mutableStateListOf<ModelImportRepository.RemoteModel>() }
  val progress = remember { mutableStateMapOf<String, Float>() }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    available =
      withContext(Dispatchers.IO) {
        try {
          ModelImportRepository.listAvailable(context)
        } catch (t: Throwable) {
          Log.w("ServerLauncher", "Failed to list available models", t)
          emptyList()
        }
      }
    loading = false
    if (available.isEmpty()) {
      error =
        "No downloaded models found. Open the Edge Gallery app and download a model first " +
          "(both apps must be signed with the same key)."
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Import from Edge Gallery") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          loading -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          error != null -> {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
          }
          else -> {
            available.forEach { remote ->
              val isSelected = selected.contains(remote)
              val pct = progress[remote.name] ?: 0f
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Checkbox(
                    checked = isSelected,
                    onCheckedChange = { checked ->
                      if (checked) selected.add(remote) else selected.remove(remote)
                    },
                    enabled = !busy,
                  )
                  Column {
                    Text(remote.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      "${remote.downloadFileName} • ${formatSize(remote.sizeInBytes)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                if (busy && isSelected) {
                  LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        enabled = !loading && !busy && selected.isNotEmpty() && error == null,
        onClick = {
          onConfirm(selected.toList()) { remote, p -> progress[remote.name] = p }
        },
      ) {
        if (busy) Text("Importing…") else Text("Import (${selected.size})")
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
    },
  )
}

private fun findLocalIpv4(): String? {
  return try {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (iface in ifaces) {
      if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
      for (addr in iface.inetAddresses) {
        if (addr is Inet4Address && !addr.isLoopbackAddress) {
          return addr.hostAddress
        }
      }
    }
    null
  } catch (_: Throwable) {
    null
  }
}

private fun formatSize(bytes: Long): String {
  if (bytes <= 0) return "?"
  val units = listOf("B", "KB", "MB", "GB")
  var size = bytes.toDouble()
  var unitIdx = 0
  while (size >= 1024 && unitIdx < units.size - 1) {
    size /= 1024
    unitIdx++
  }
  return "%.1f %s".format(size, units[unitIdx])
}
