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

package com.google.ai.edge.gallery.ui.common.chat

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.bodySmallNarrow

data class MessageLayoutConfig(
  val horizontalArrangement: Arrangement.Horizontal,
  val modifier: Modifier,
  val userLabel: String,
  val rightSideLabel: String,
)

/**
 * Composable function to display the sender information for a chat message.
 *
 * This function handles different types of chat messages, including system messages, benchmark
 * results, and image generation results, and displays the appropriate sender label and status
 * information.
 */
@Composable
fun MessageSender(message: ChatMessage, agentName: String = "", imageHistoryCurIndex: Int = 0) {
  // No user label for system messages.
  if (message.side == ChatSide.SYSTEM) {
    return
  }

  val (horizontalArrangement, modifier, userLabel, rightSideLabel) =
    getMessageLayoutConfig(
      message = message,
      agentName = agentName,
      imageHistoryCurIndex = imageHistoryCurIndex,
    )

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = horizontalArrangement,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      // Sender label.
      Text(userLabel, style = MaterialTheme.typography.titleSmall)

      when (message) {
        // Benchmark running status.
        is ChatMessageBenchmarkResult -> {
          if (message.isRunning()) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(10.dp),
              strokeWidth = 1.5.dp,
              color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.width(4.dp))
          }
          val statusLabel =
            if (message.isWarmingUp()) {
              stringResource(R.string.warming_up)
            } else if (message.isRunning()) {
              stringResource(R.string.running)
            } else ""
          if (statusLabel.isNotEmpty()) {
            Text(statusLabel, color = MaterialTheme.colorScheme.secondary, style = bodySmallNarrow)
          }
        }

        // Benchmark LLM running status.
        is ChatMessageBenchmarkLlmResult -> {
          if (message.running) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(10.dp),
              strokeWidth = 1.5.dp,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        }

        // Image generation running status.
        is ChatMessageImageWithHistory -> {
          if (message.isRunning()) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(10.dp),
              strokeWidth = 1.5.dp,
              color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              stringResource(R.string.running),
              color = MaterialTheme.colorScheme.secondary,
              style = bodySmallNarrow,
            )
          }
        }
      }
    }

    // Right-side text.
    when (message) {
      is ChatMessageBenchmarkResult,
      is ChatMessageImageWithHistory,
      is ChatMessageBenchmarkLlmResult -> {
        Text(rightSideLabel, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun getMessageLayoutConfig(
  message: ChatMessage,
  agentName: String,
  imageHistoryCurIndex: Int,
): MessageLayoutConfig {
  var userLabel = stringResource(R.string.chat_you)
  var rightSideLabel = ""
  var horizontalArrangement = Arrangement.End
  var modifier = Modifier.padding(bottom = 2.dp)

  if (message.side == ChatSide.AGENT) {
    userLabel = agentName
  }

  when (message) {
    is ChatMessageBenchmarkResult -> {
      horizontalArrangement = Arrangement.SpaceBetween
      modifier = modifier.fillMaxWidth()
      userLabel = "Benchmark"
      rightSideLabel =
        if (message.isWarmingUp()) {
          "${message.warmupCurrent}/${message.warmupTotal}"
        } else {
          "${message.iterationCurrent}/${message.iterationTotal}"
        }
    }

    is ChatMessageBenchmarkLlmResult -> {
      horizontalArrangement = Arrangement.SpaceBetween
      modifier = modifier.fillMaxWidth()
      userLabel = "Stats"
      if (message.accelerator.isNotEmpty()) {
        userLabel = "${userLabel} on ${message.accelerator}"
      }
    }

    is ChatMessageImageWithHistory -> {
      horizontalArrangement = Arrangement.SpaceBetween
      if (message.bitmaps.isNotEmpty()) {
        modifier = modifier.width(200.dp)
      }
      rightSideLabel = "${imageHistoryCurIndex + 1}/${message.totalIterations}"
    }
  }

  return MessageLayoutConfig(
    horizontalArrangement = horizontalArrangement,
    modifier = modifier,
    userLabel = userLabel,
    rightSideLabel = rightSideLabel,
  )
}

// @Preview(showBackground = true)
// @Composable
// fun MessageSenderPreview() {
//   GalleryTheme {
//     Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp))
// {
//       // Agent message.
//       MessageSender(
//         message = ChatMessageText(content = "hello world", side = ChatSide.AGENT),
//         agentName = stringResource(R.string.chat_generic_agent_name),
//       )
//       // User message.
//       MessageSender(
//         message = ChatMessageText(content = "hello world", side = ChatSide.USER),
//         agentName = stringResource(R.string.chat_generic_agent_name),
//       )
//       // Benchmark during warmup.
//       MessageSender(
//         message =
//           ChatMessageBenchmarkResult(
//             orderedStats = listOf(),
//             statValues = mutableMapOf(),
//             values = listOf(),
//             histogram = Histogram(listOf(), 0),
//             warmupCurrent = 10,
//             warmupTotal = 50,
//             iterationCurrent = 0,
//             iterationTotal = 200,
//           ),
//         agentName = stringResource(R.string.chat_generic_agent_name),
//       )
//       // Benchmark during running.
//       MessageSender(
//         message =
//           ChatMessageBenchmarkResult(
//             orderedStats = listOf(),
//             statValues = mutableMapOf(),
//             values = listOf(),
//             histogram = Histogram(listOf(), 0),
//             warmupCurrent = 50,
//             warmupTotal = 50,
//             iterationCurrent = 123,
//             iterationTotal = 200,
//           ),
//         agentName = stringResource(R.string.chat_generic_agent_name),
//       )
//       // Image generation during running.
//       MessageSender(
//         message =
//           ChatMessageImageWithHistory(
//             bitmaps = listOf(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)),
//             imageBitMaps = listOf(),
//             totalIterations = 10,
//             ChatSide.AGENT,
//           ),
//         agentName = stringResource(R.string.chat_generic_agent_name),
//         imageHistoryCurIndex = 4,
//       )
//     }
//   }
// }
