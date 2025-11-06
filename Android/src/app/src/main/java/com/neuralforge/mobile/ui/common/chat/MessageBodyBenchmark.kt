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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val DEFAULT_HISTOGRAM_BAR_HEIGHT = 50f

/**
 * Composable function to display benchmark results within a chat message.
 *
 * This function renders benchmark statistics (e.g., average latency) in data cards and visualizes
 * the latency distribution using a histogram.
 */
@Composable
fun MessageBodyBenchmark(message: ChatMessageBenchmarkResult) {
  Column(
    modifier = Modifier.padding(12.dp).fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Data cards.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      for (stat in message.orderedStats) {
        DataCard(
          label = stat.label,
          unit = stat.unit,
          value = message.statValues[stat.id],
          highlight = stat.id == message.highlightStat,
          showPlaceholder = message.isWarmingUp(),
        )
      }
    }

    // Histogram
    if (message.histogram.buckets.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, count) in message.histogram.buckets.withIndex()) {
          var barBgColor = MaterialTheme.colorScheme.onSurfaceVariant
          var alpha = 0.3f
          if (count != 0) {
            alpha = 0.5f
          }
          if (index == message.histogram.highlightBucketIndex) {
            barBgColor = MaterialTheme.colorScheme.primary
            alpha = 0.8f
          }
          // Bar container.
          Column(
            modifier = Modifier.height(DEFAULT_HISTOGRAM_BAR_HEIGHT.dp).width(4.dp),
            verticalArrangement = Arrangement.Bottom,
          ) {
            // Bar content.
            Box(
              modifier =
                Modifier.height(
                    max(
                        1f,
                        count.toFloat() / message.histogram.maxCount.toFloat() *
                          DEFAULT_HISTOGRAM_BAR_HEIGHT,
                      )
                      .dp
                  )
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp))
                  .alpha(alpha)
                  .background(barBgColor)
            )
          }
        }
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyBenchmarkPreview() {
//   GalleryTheme {
//     MessageBodyBenchmark(
//       message = ChatMessageBenchmarkResult(
//         orderedStats = listOf(
//           Stat(id = "stat1", label = "Stat1", unit = "ms"),
//           Stat(id = "stat2", label = "Stat2", unit = "ms"),
//           Stat(id = "stat3", label = "Stat3", unit = "ms"),
//           Stat(id = "stat4", label = "Stat4", unit = "ms")
//         ),
//         statValues = mutableMapOf(
//           "stat1" to 0.3f,
//           "stat2" to 0.4f,
//           "stat3" to 0.5f,
//         ),
//         values = listOf(),
//         histogram = Histogram(listOf(), 0),
//         warmupCurrent = 0,
//         warmupTotal = 0,
//         iterationCurrent = 0,
//         iterationTotal = 0,
//         highlightStat = "stat2"
//       )
//     )
//   }
// }
