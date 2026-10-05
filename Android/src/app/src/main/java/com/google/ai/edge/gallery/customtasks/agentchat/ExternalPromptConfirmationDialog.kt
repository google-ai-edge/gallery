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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * A dialog that asks the user to review a prompt that was supplied by an external source (e.g. a
 * deep link opened from another app, a web page, a QR code or a notification) before it is sent to
 * the agent.
 *
 * Externally supplied prompts must never be sent to the agent automatically: the agent can invoke
 * tools and Android intents on the user's behalf, so an attacker-controlled link would otherwise be
 * able to drive those actions with a single tap. The prompt is shown verbatim and is only sent
 * after the user explicitly confirms.
 *
 * @param prompt The externally supplied prompt text to review.
 * @param sendEnabled Whether the send button is enabled. Callers should disable it until the model
 *   is ready to accept a message.
 * @param onConfirm Called when the user confirms that the prompt should be sent.
 * @param onDismiss Called when the user cancels or dismisses the dialog.
 */
@Composable
fun ExternalPromptConfirmationDialog(
  prompt: String,
  sendEnabled: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.external_prompt_dialog_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.external_prompt_dialog_content),
          style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.external_prompt_dialog_prompt_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = prompt,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = onConfirm, enabled = sendEnabled) {
        Text(stringResource(R.string.external_prompt_dialog_send))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
  )
}
