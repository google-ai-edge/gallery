/*
 * Copyright 2025 Google LLC
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

package com.neuralforge.mobile.ui.common.chat

// import androidx.compose.ui.tooling.preview.Preview
// import com.neuralforge.mobile.ui.theme.GalleryTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Composable function to display benchmark LLM results within a chat message.
 *
 * This function renders benchmark statistics (e.g., various token speed) in data cards
 */
@Composable
fun MessageBodyBenchmarkLlm(message: ChatMessageBenchmarkLlmResult, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Data cards.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      for (stat in message.orderedStats) {
        DataCard(label = stat.label, unit = stat.unit, value = message.statValues[stat.id])
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyBenchmarkLlmPreview() {
//   GalleryTheme {
//     MessageBodyBenchmarkLlm(
//       message = ChatMessageBenchmarkLlmResult(
//         orderedStats = listOf(
//           Stat(id = "stat1", label = "Stat1", unit = "tokens/s"),
//           Stat(id = "stat2", label = "Stat2", unit = "tokens/s")
//         ),
//         statValues = mutableMapOf(
//           "stat1" to 0.3f,
//           "stat2" to 0.4f,
//         ),
//         running = false,
//       )
//     )
//   }
// }
