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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.neuralforge.mobile.ui.common.humanReadableDuration

/** Composable function to display the latency of a chat message, if available. */
@Composable
fun LatencyText(message: ChatMessage) {
  if (message.latencyMs >= 0) {
    Text(
      message.latencyMs.humanReadableDuration(),
      modifier = Modifier.alpha(0.5f),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun LatencyTextPreview() {
//   GalleryTheme {
//     Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp))
// {
//       for (latencyMs in listOf(123f, 1234f, 123456f, 7234567f)) {
//         LatencyText(
//           message =
//             ChatMessage(latencyMs = latencyMs, type = ChatMessageType.TEXT, side =
// ChatSide.AGENT)
//         )
//       }
//     }
//   }
// }
