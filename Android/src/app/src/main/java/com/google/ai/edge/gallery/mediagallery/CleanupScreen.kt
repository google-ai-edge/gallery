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

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Editable list of classify labels (server limit: 50 labels, 200 chars, no duplicates). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelEditor(title: String, labels: List<String>, onChange: (List<String>) -> Unit, max: Int = 50) {
  var draft by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      labels.forEach { label ->
        InputChip(
          selected = false,
          onClick = { onChange(labels - label) },
          label = { Text(label, style = MaterialTheme.typography.bodySmall) },
          trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Entfernen", modifier = Modifier.size(16.dp)) },
        )
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        draft,
        { draft = it.take(200) },
        placeholder = { Text("Neues Label") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        enabled = draft.isNotBlank() && labels.size < max && draft.trim() !in labels,
        onClick = {
          onChange(labels + draft.trim())
          draft = ""
        },
      ) {
        Text("Hinzufügen")
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(viewModel: AnalysisViewModel, onBack: () -> Unit) {
  val settings by viewModel.cleanupSettings.collectAsState()
  val state by viewModel.cleanup.collectAsState()
  val loader = rememberGalleryImageLoader()
  // ids the user wants to keep; everything else in the result is selected for deletion
  val keep = remember { mutableStateListOf<Long>() }
  var showSettings by remember { mutableStateOf(state !is Loadable.Done) }
  val candidates = (state as? Loadable.Done)?.value.orEmpty()
  val selected = candidates.filter { it.item.id !in keep }.map { it.item }
  var pendingDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
  val deleteLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) viewModel.onDeleted(pendingDelete)
    }
  LaunchedEffect(state) { if (state is Loadable.Done) showSettings = false }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Aufräumen") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { if (!showSettings) TextButton(onClick = { showSettings = true }) { Text("Einstellungen") } },
      )
    },
    bottomBar = {
      if (selected.isNotEmpty() && !showSettings) {
        Row(
          Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("${selected.size} von ${candidates.size} ausgewählt", modifier = Modifier.weight(1f))
          Button(
            onClick = {
              pendingDelete = selected
              viewModel.deleteRequest(selected)?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            }
          ) {
            Text("Löschen")
          }
        }
      }
    },
  ) { padding ->
    if (showSettings) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        item {
          Text(
            "Die Galerie fragt morgenschiss, welche Medien zu den Löschbar-Labels passen. Vorgeschlagen wird nur, was alt genug ist und eindeutig zugeordnet wurde. Du siehst alles vorher und wählst ab, was bleiben soll.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        item { LabelEditor("Löschbar", settings.junk, { viewModel.updateCleanup(settings.copy(junk = it)) }) }
        item { LabelEditor("Behalten (Gegengewicht)", settings.keep, { viewModel.updateCleanup(settings.copy(keep = it)) }) }
        item {
          Column {
            Text("Mindestens ${settings.minAgeMonths} Monate alt", style = MaterialTheme.typography.labelLarge)
            Slider(
              value = settings.minAgeMonths.toFloat(),
              onValueChange = { viewModel.updateCleanup(settings.copy(minAgeMonths = it.toInt())) },
              valueRange = 0f..60f,
              steps = 59,
            )
            Text("Sicherheit: Abstand zum zweitbesten Label mindestens %.2f".format(settings.minMargin), style = MaterialTheme.typography.labelLarge)
            Slider(
              value = settings.minMargin.toFloat(),
              onValueChange = { viewModel.updateCleanup(settings.copy(minMargin = it.toDouble())) },
              valueRange = 0f..0.1f,
            )
          }
        }
        item {
          Button(
            enabled = state !is Loadable.Loading && settings.junk.isNotEmpty() && settings.junk.size + settings.keep.size <= 50,
            onClick = {
              keep.clear()
              viewModel.runCleanup()
            },
          ) {
            Text(if (state is Loadable.Loading) "Sucht …" else "Löschbares suchen")
          }
        }
        (state as? Loadable.Error)?.let { e -> item { Text(e.message, color = MaterialTheme.colorScheme.error) } }
      }
      return@Scaffold
    }
    if (candidates.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Nichts gefunden, das zu den Löschbar-Labels passt.", style = MaterialTheme.typography.bodyLarge)
      }
      return@Scaffold
    }
    val groups = candidates.groupBy { it.label }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(96.dp),
      contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      groups.forEach { (label, list) ->
        item(key = "h-$label", span = { GridItemSpan(maxLineSpan) }) {
          Row(Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label (${list.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val allKept = list.all { it.item.id in keep }
            TextButton(onClick = { if (allKept) keep.removeAll(list.map { it.item.id }) else keep.addAll(list.map { it.item.id }.filter { it !in keep }) }) {
              Text(if (allKept) "Alle wählen" else "Alle behalten")
            }
          }
        }
        items(list, key = { it.item.id }) { c ->
          val isSelected = c.item.id !in keep
          Box {
            MediaCell(c.item, loader) { if (isSelected) keep.add(c.item.id) else keep.remove(c.item.id) }
            Icon(
              if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
              contentDescription = if (isSelected) "Zum Löschen ausgewählt" else "Bleibt",
              tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
              modifier =
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.Black.copy(alpha = 0.3f), MaterialTheme.shapes.extraLarge),
            )
          }
        }
      }
    }
  }
}
