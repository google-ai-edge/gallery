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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val MODEL_NAME = "EmbeddingGemma-2"

/** The search model is required: queries are embedded on the phone, also without morgenschiss. */
@Composable
fun OfflineIndexSection(modelManager: ModelManagerViewModel, localSearch: LocalSearch) {
  val ui by modelManager.uiState.collectAsState()
  val model = modelManager.getModelByName(MODEL_NAME)
  val status = model?.let { ui.modelDownloadStatus[it.name] }
  HorizontalDivider()
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Suchmodell", style = MaterialTheme.typography.titleMedium)
    Text(
      "Die Suche läuft auf dem Handy, auch ohne Netz. Dafür braucht die Galerie ein Suchmodell (485 MB). morgenschiss rechnet nur die erste große Indexierung, die Suchdaten kommen beim Abgleich im WLAN aufs Handy.",
      style = MaterialTheme.typography.bodyMedium,
    )
    val count by produceState(-1) { value = localSearch.vectorCount() }
    if (count >= 0) Text("Suchdaten auf dem Handy: $count Einträge", style = MaterialTheme.typography.bodySmall)
    ModelStatus(modelManager, status, model)
  }
}

/** On the start page until the model is there. */
@Composable
fun SearchModelBanner(modelManager: ModelManagerViewModel) {
  val ui by modelManager.uiState.collectAsState()
  val model = modelManager.getModelByName(MODEL_NAME)
  val status = model?.let { ui.modelDownloadStatus[it.name] } ?: return
  if (status.status == ModelDownloadStatusType.SUCCEEDED) return
  Column(
    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("Für die Suche braucht die Galerie ihr Suchmodell (485 MB, am besten im WLAN).", style = MaterialTheme.typography.bodyMedium)
    ModelStatus(modelManager, status, model)
  }
}

@Composable
private fun ModelStatus(modelManager: ModelManagerViewModel, status: ModelDownloadStatus?, model: Model?) {
  when (status?.status) {
    null -> Text("Modellliste wird geladen …", style = MaterialTheme.typography.bodySmall)
    ModelDownloadStatusType.SUCCEEDED -> Text("Modell ist geladen.", style = MaterialTheme.typography.bodySmall)
    ModelDownloadStatusType.IN_PROGRESS, ModelDownloadStatusType.UNZIPPING -> {
      val total = status.totalBytes.coerceAtLeast(1)
      Text("Lädt: ${status.receivedBytes / 1_000_000} von ${total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
      LinearProgressIndicator(progress = { status.receivedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
    else -> {
      if (status.status == ModelDownloadStatusType.FAILED) {
        Text("Laden fehlgeschlagen: ${status.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      Button(onClick = { model?.let { modelManager.downloadModel(task = null, model = it) } }) { Text("Modell laden (485 MB)") }
    }
  }
}
