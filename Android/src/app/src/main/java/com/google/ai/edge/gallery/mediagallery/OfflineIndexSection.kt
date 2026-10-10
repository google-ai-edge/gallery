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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val MODEL_NAME = "EmbeddingGemma-2"

/** Offline search: download the on-device model; indexing then runs while charging. */
@Composable
fun OfflineIndexSection(modelManager: ModelManagerViewModel, localSearch: LocalSearch) {
  val ui by modelManager.uiState.collectAsState()
  val model = modelManager.getModelByName(MODEL_NAME)
  val status = model?.let { ui.modelDownloadStatus[it.name] }
  LaunchedEffect(status?.status) {
    if (status?.status == ModelDownloadStatusType.SUCCEEDED) localSearch.scheduleIndexing()
  }
  HorizontalDivider()
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Suche ohne morgenschiss", style = MaterialTheme.typography.titleMedium)
    Text(
      "Ein Suchmodell auf dem Handy (485 MB) baut nach und nach einen eigenen Index auf, nur während das Handy lädt und nicht benutzt wird. Ist morgenschiss nicht erreichbar, sucht die Galerie darin.",
      style = MaterialTheme.typography.bodyMedium,
    )
    when (status?.status) {
      null -> Text("Modellliste wird geladen …", style = MaterialTheme.typography.bodySmall)
      ModelDownloadStatusType.SUCCEEDED -> Text("Modell ist geladen, Index wächst beim Laden.", style = MaterialTheme.typography.bodySmall)
      ModelDownloadStatusType.IN_PROGRESS, ModelDownloadStatusType.UNZIPPING -> {
        val total = status.totalBytes.coerceAtLeast(1)
        Text("Lädt: ${status.receivedBytes / 1_000_000} von ${total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { status.receivedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
      }
      else -> {
        if (status.status == ModelDownloadStatusType.FAILED) {
          Text("Laden fehlgeschlagen: ${status.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { model?.let { modelManager.downloadModel(task = null, model = it) } }) {
          Text("Modell laden (485 MB)")
        }
      }
    }
  }
}
