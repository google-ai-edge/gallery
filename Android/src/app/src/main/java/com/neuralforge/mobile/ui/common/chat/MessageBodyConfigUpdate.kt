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
// import com.neuralforge.mobile.ui.preview.MODEL_TEST1
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralforge.mobile.data.convertValueToTargetType
import com.neuralforge.mobile.data.getConfigValueString
import com.neuralforge.mobile.ui.theme.bodySmallNarrow
import com.neuralforge.mobile.ui.theme.titleSmaller

/**
 * Composable function to display a message indicating configuration value changes.
 *
 * This function renders a centered row containing a box that displays the old and new values of
 * configuration settings that have been updated.
 */
@Composable
fun MessageBodyConfigUpdate(message: ChatMessageConfigValuesChange) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
    Box(
      modifier =
        Modifier.clip(RoundedCornerShape(4.dp))
          .background(MaterialTheme.colorScheme.tertiaryContainer)
    ) {
      Column(modifier = Modifier.padding(8.dp)) {
        // Title.
        Text(
          "Configs updated",
          color = MaterialTheme.colorScheme.onTertiaryContainer,
          style = titleSmaller,
        )

        Row(modifier = Modifier.padding(top = 8.dp)) {
          // Keys
          Column {
            for (config in message.model.configs) {
              Text("${config.key.label}:", style = bodySmallNarrow, modifier = Modifier.alpha(0.6f))
            }
          }

          Spacer(modifier = Modifier.width(4.dp))

          // Values
          Column {
            for (config in message.model.configs) {
              val key = config.key.label
              val oldValue: Any =
                convertValueToTargetType(
                  value = message.oldValues.getValue(key),
                  valueType = config.valueType,
                )
              val newValue: Any =
                convertValueToTargetType(
                  value = message.newValues.getValue(key),
                  valueType = config.valueType,
                )
              if (oldValue == newValue) {
                Text("$newValue", style = bodySmallNarrow)
              } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(getConfigValueString(oldValue, config), style = bodySmallNarrow)
                  Text(
                    "▸",
                    style = bodySmallNarrow.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                  )
                  Text(
                    getConfigValueString(newValue, config),
                    style = bodySmallNarrow.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyConfigUpdatePreview() {
//   GalleryTheme {
//     Row(modifier = Modifier.padding(16.dp)) {
//       MessageBodyConfigUpdate(
//         message = ChatMessageConfigValuesChange(
//           model = MODEL_TEST1,
//           oldValues = mapOf(
//             ConfigKeys.MAX_RESULT_COUNT.label to 100,
//             ConfigKeys.USE_GPU.label to false
//           ),
//           newValues = mapOf(
//             ConfigKeys.MAX_RESULT_COUNT.label to 200,
//             ConfigKeys.USE_GPU.label to true
//           )
//         )
//       )
//     }
//   }
// }
