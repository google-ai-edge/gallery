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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString

/**
 * A dialog that prompts the user to enter a Hugging Face model URL or model ID.
 *
 * This dialog serves as the entry point for importing models directly from Hugging Face. Users can
 * input a direct link to a `.litertlm` file or a Hugging Face model card URL.
 *
 * @param urlInput The current text value of the URL input field.
 * @param onUrlInputChange Callback triggered when the URL input text changes.
 * @param onDismiss Callback triggered when the dialog is dismissed or cancelled.
 * @param onConfirm Callback triggered when the user clicks the "Next" or confirmation button.
 * @param modifier Optional [Modifier] for the dialog.
 */
@Composable
fun HuggingFaceUrlDialog(
  urlInput: String,
  onUrlInputChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.import_from_hugging_face_title)) },
    text = {
      HuggingFaceUrlDialogContent(urlInput = urlInput, onUrlInputChange = onUrlInputChange)
    },
    confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.next)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    modifier = modifier,
  )
}

/**
 * The internal content of the Hugging Face URL dialog.
 *
 * This is separated from the [AlertDialog] wrapper to allow for independent testing, reuse, or
 * embedding in other containers (like bottom sheets) if needed. It contains instructions, an
 * example link, and the text input field.
 *
 * @param urlInput The current text value of the URL input field.
 * @param onUrlInputChange Callback triggered when the URL input text changes.
 * @param modifier Optional [Modifier] for the content arrangement.
 */
@Composable
fun HuggingFaceUrlDialogContent(
  urlInput: String,
  onUrlInputChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      buildAnnotatedString {
        append(stringResource(R.string.enter_hugging_face_url))
        append(
          buildTrackableUrlAnnotatedString(
            url = stringResource(R.string.enter_hugging_face_url_example_link),
            linkText = stringResource(R.string.enter_hugging_face_url_example_link),
          )
        )
      }
    )
    OutlinedTextField(
      value = urlInput,
      onValueChange = onUrlInputChange,
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text(stringResource(R.string.hugging_face_url_placeholder)) },
      singleLine = true,
    )
  }
}
