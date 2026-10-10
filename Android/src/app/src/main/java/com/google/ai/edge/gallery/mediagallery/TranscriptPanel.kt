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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Bottom panel over the video: start, progress, then sentences that jump to their time. */
@Composable
fun TranscriptPanel(
  viewModel: TranscriptViewModel,
  item: MediaItem,
  onSeek: (Double) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  Column(
    modifier
      .fillMaxWidth()
      .fillMaxHeight(0.45f)
      .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .navigationBarsPadding()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Transkript", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Schließen", tint = Color.White) }
    }
    when (val s = state) {
      TranscriptState.Idle -> {
        Text(
          "Die Tonspur geht an morgenschiss und wird dort mit Parakeet transkribiert. Danach springst du per Tippen an die Stelle.",
          color = Color.White.copy(alpha = 0.8f),
          style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { viewModel.start(item) }) { Text("Transkribieren") }
      }
      is TranscriptState.Working -> {
        Text(s.step, color = Color.White)
        if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      is TranscriptState.Failed -> {
        Text(s.message, color = MaterialTheme.colorScheme.error)
        Button(onClick = { viewModel.start(item) }) { Text("Nochmal") }
      }
      is TranscriptState.Done ->
        if (s.sentences.isEmpty()) {
          Text("Kein gesprochener Text erkannt.", color = Color.White.copy(alpha = 0.8f))
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.sentences) { sentence ->
              Row(Modifier.fillMaxWidth().clickable { onSeek(sentence.start) }.padding(vertical = 4.dp)) {
                Text(
                  formatDuration((sentence.start * 1000).toLong()),
                  color = MaterialTheme.colorScheme.primary,
                  style = MaterialTheme.typography.labelMedium,
                  modifier = Modifier.width(56.dp),
                )
                Text(sentence.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
        }
    }
  }
}
