/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.launch

private val PROMPT_TEMPLATE =
  """
  # Task: Custom HTML/JS Implementation
  Generate a single, self-contained HTML file that implements a specific feature or logic as described below.

  ## 1. Requirement
  The implementation must fulfill the following:
  > ___requirement___

  ## 2. Technical Specifications
  * **Structure:** A complete, valid HTML5 document.
  * **Head (Dependencies):** If third-party JS libraries (e.g., Three.js, D3, Lodash, GSAP) are required, include them via CDN using `<script src="..." defer>` tags inside the `<head>`. Do not put implementation logic here.
  * **Body (Implementation):** Place the actual logic implementation inside a single `<script>` tag at the very end of the `<body>`.
  * **Global Interface:** Within the body script, you must expose an `async` function to the global `window` object named: `window['ai_edge_gallery_get_result']`.

  ## 3. Data Interface & Serialization
  * **Parameter 1 (`data`):** A **JSON-stringified string**.
      * Once parsed, the input object follows this schema: `___input_data_schema___`
  * **Parameter 2 (`secret`):** A **string** representing a sensitive token or API key (e.g., Bearer token, private key). The implementation should use this if the requirement involves authenticated API calls or encrypted operations.
  * **Output (return value):** The function must return a **JSON-stringified string** with the following exact structure:
      ```json
      {
        "result": "___output_data_schema___",
        "image": { "base64": "data:image/png;base64,..." },
        "error": "Error message string or null"
      }
      ```
      **CRITICAL RULES:**
      1. **Dual Output:** The `"result"` and `"image"` fields can and should coexist in the same response if the requirement involves returning both data/text and a visual asset.
      2. **Result Serialization:** The value for `"result"` must be a JSON-stringified representation of the output data. Set to `null` only if no data is produced.
      3. **Image Serialization:** The `"image.base64"` field must contain a full Data URI. Set the entire `"image"` object to `null` only if no image is produced.

  ## 4. Error Handling
  * Wrap the entire function logic in a `try/catch` block.
  * If an error occurs, the function should return a JSON string where `result` is `null` and `error` contains the error message.

  ## 5. Response Constraints
  * Return the **raw HTML code only**.
  * Do not provide any introductory text, markdown backticks, or concluding remarks.
  * Start the response immediately with `<!DOCTYPE html>`.
  * Put the output code into a Markdown code block so I can easily copy.
  """
    .trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateLlmPromptBottomSheet(
  curDescription: String,
  requirements: String,
  onRequirementsChange: (String) -> Unit,
  inputData: String,
  onInputDataChange: (String) -> Unit,
  outputData: String,
  onOutputDataChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onLlmPromptGenerated: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboard.current

  LaunchedEffect(curDescription) {
    if (requirements.isBlank()) {
      onRequirementsChange(curDescription)
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding()) {
        Text(
          stringResource(R.string.generate_llm_prompt_title),
          style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
          modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          CursorTrackingTextField(
            labelResId = R.string.describe_requirements,
            initialValue = requirements,
            onValueChange = onRequirementsChange,
            minLines = 3,
          )
          CursorTrackingTextField(
            labelResId = R.string.describe_input_data,
            initialValue = inputData,
            onValueChange = onInputDataChange,
            minLines = 7,
            supportingTextResId = R.string.describe_input_data_support_text,
          )
          CursorTrackingTextField(
            labelResId = R.string.describe_output_data,
            initialValue = outputData,
            onValueChange = onOutputDataChange,
            minLines = 5,
            modifier = Modifier.padding(top = 8.dp),
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          Button(
            onClick = {
              val prompt =
                PROMPT_TEMPLATE.replace("___requirement___", requirements)
                  .replace("___input_data_schema___", inputData)
                  .replace("___output_data_schema___", outputData)
              scope.launch {
                val clipData = ClipData.newPlainText("prompt", prompt)
                val clipEntry = ClipEntry(clipData = clipData)
                clipboard.setClipEntry(clipEntry = clipEntry)
                onLlmPromptGenerated(prompt)
                sheetState.hide()
                onDismiss()
              }
            }
          ) {
            Text(stringResource(R.string.generate_and_copy))
          }
        }
      }
    }
  }
}
