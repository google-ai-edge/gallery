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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val CLASSIFICATION_BAR_HEIGHT = 8.dp
val CLASSIFICATION_BAR_MAX_WIDTH = 200.dp

/**
 * Composable function to display classification results.
 *
 * This function renders a list of classifications, each with its label, score, and a visual score
 * bar.
 */
@Composable
fun MessageBodyClassification(
  message: ChatMessageClassification,
  modifier: Modifier = Modifier,
  oneLineLabel: Boolean = false,
) {
  Column(modifier = modifier.padding(12.dp)) {
    for (classification in message.classifications) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        // Classification label.
        Text(
          classification.label,
          maxLines = if (oneLineLabel) 1 else Int.MAX_VALUE,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.weight(1f),
        )
        // Classification score.
        Text(
          "%.2f".format(classification.score),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.align(Alignment.Bottom),
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      // Score bar.
      Box {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(CLASSIFICATION_BAR_HEIGHT)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceDim)
        )
        Box(
          modifier =
            Modifier.fillMaxWidth(classification.score)
              .height(CLASSIFICATION_BAR_HEIGHT)
              .clip(CircleShape)
              .background(classification.color)
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyClassificationPreview() {
//   GalleryTheme {
//     MessageBodyClassification(
//       message =
//         ChatMessageClassification(
//           classifications =
//             listOf(
//               Classification(label = "label1", score = 0.3f, color = Color.Red),
//               Classification(label = "label2", score = 0.7f, color = Color.Blue),
//             ),
//           latencyMs = 12345f,
//         )
//     )
//   }
// }
