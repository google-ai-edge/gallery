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
// import com.google.ai.edge.gallery.ui.preview.ALL_PREVIEW_TASKS
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.PromptTemplate
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskIconColor

private const val CARD_HEIGHT = 100

@Composable
fun MessageBodyPromptTemplates(
  message: ChatMessagePromptTemplates,
  task: Task,
  onPromptClicked: (PromptTemplate) -> Unit = {},
) {
  val rowCount = message.templates.size.toFloat()
  val color = getTaskIconColor(task)
  val gradientColors = listOf(color.copy(alpha = 0.5f), color)

  Column(
    modifier = Modifier.padding(top = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "Try an example prompt",
      style =
        MaterialTheme.typography.titleLarge.copy(
          fontWeight = FontWeight.Bold,
          brush = Brush.linearGradient(colors = gradientColors),
        ),
      modifier = Modifier.fillMaxWidth(),
      textAlign = TextAlign.Center,
    )
    if (message.showMakeYourOwn) {
      Text(
        "Or make your own",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().offset(y = (-4).dp),
        textAlign = TextAlign.Center,
      )
    }
    LazyColumn(
      modifier = Modifier.height((rowCount * (CARD_HEIGHT + 8)).dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Cards.
      items(message.templates) { template ->
        Box(
          modifier =
            Modifier.border(
                width = 1.dp,
                color = color.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp),
              )
              .height(CARD_HEIGHT.dp)
              .shadow(elevation = 2.dp, shape = RoundedCornerShape(24.dp), spotColor = color)
              .background(MaterialTheme.colorScheme.surface)
              .clickable { onPromptClicked(template) }
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              template.title,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
              template.description,
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyPromptTemplatesPreview() {
//   for ((index, task) in ALL_PREVIEW_TASKS.withIndex()) {
//     task.index = index
//     for (model in task.models) {
//       model.preProcess()
//     }
//   }

//   GalleryTheme {
//     Row(modifier = Modifier.padding(16.dp)) {
//       MessageBodyPromptTemplates(
//         message =
//           ChatMessagePromptTemplates(
//             templates =
//               listOf(
//                 PromptTemplate(
//                   title = "Math Worksheets",
//                   description = "Create a set of math worksheets for parents",
//                   prompt = "",
//                 ),
//                 PromptTemplate(
//                   title = "Shape Sequencer",
//                   description = "Find the next shape in a sequence",
//                   prompt = "",
//                 ),
//               )
//           ),
//         task = TASK_TEST1,
//       )
//     }
//   }
// }
