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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(viewModel: AnalysisViewModel, onBack: () -> Unit) {
  val labels by viewModel.categoryLabels.collectAsState()
  val state by viewModel.categories.collectAsState()
  var editing by remember { mutableStateOf(false) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Kategorien") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { TextButton(onClick = { editing = !editing }) { Text(if (editing) "Fertig" else "Labels") } },
      )
    }
  ) { padding ->
    LazyColumn(
      contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (editing || state is Loadable.Idle) {
        item {
          Text(
            "Jedes Foto und Video landet in der Kategorie, die am besten passt. Beschreibende Sätze klappen besser als einzelne Wörter.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        item { LabelEditor("Kategorien", labels, viewModel::updateCategoryLabels) }
      }
      item {
        Button(enabled = state !is Loadable.Loading && labels.size >= 2, onClick = { editing = false; viewModel.runCategories() }) {
          Text(if (state is Loadable.Loading) "Wertet aus …" else "Auswerten")
        }
      }
      when (val s = state) {
        is Loadable.Error -> item { Text(s.message, color = MaterialTheme.colorScheme.error) }
        is Loadable.Done -> {
          val total = s.value.sumOf { it.count }.coerceAtLeast(1)
          item { Text("$total Medien ausgewertet", style = MaterialTheme.typography.labelLarge) }
          items(s.value, key = { it.label }) { stat -> CategoryRow(stat, total) }
        }
        else -> {}
      }
    }
  }
}

@Composable
private fun CategoryRow(stat: CategoryStat, total: Int) {
  val share = stat.count.toFloat() / total
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row {
      Text(stat.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text("${stat.count} · %.0f %%".format(share * 100), style = MaterialTheme.typography.bodyMedium)
    }
    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
    if (stat.byYear.isNotEmpty()) {
      Text(
        stat.byYear.entries.joinToString("   ") { "${it.key}: ${it.value}" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
