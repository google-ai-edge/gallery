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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Search hits in what was said: frame at the moment, time and the passage. */
@Composable
fun SpokenResults(spoken: List<Spoken>, onOpen: (Spoken) -> Unit) {
  if (spoken.isEmpty()) return
  var all by remember(spoken) { mutableStateOf(false) }
  val loader = rememberGalleryImageLoader()
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    Text("Im Gesagten", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
    (if (all) spoken else spoken.take(3)).forEach { s ->
      Row(Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
          model = MediaThumb(s.item.uri, 256, timeMs = (s.t * 1000).toLong()),
          imageLoader = loader,
          contentDescription = s.item.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.padding(start = 10.dp)) {
          Text("bei " + formatDuration((s.t * 1000).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
          Text("„${s.text}“", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    if (spoken.size > 3 && !all) TextButton(onClick = { all = true }) { Text("Alle ${spoken.size} zeigen") }
  }
}
