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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.tools.SensitiveDataDecision
import com.google.ai.edge.gallery.tools.SensitiveDataRequest
import com.google.ai.edge.gallery.tools.SensitiveDataType
import org.json.JSONException
import org.json.JSONObject

/** The user-facing text of a [SensitiveDataPermissionDialog]. */
private data class SensitiveDataPromptText(
  val title: String,
  val message: String,
  val detailsLabel: String,
  val details: String,
)

/**
 * A dialog that asks the user whether sensitive user data may be accessed on the AI assistant's
 * behalf. Dismissing the dialog denies the request.
 */
@Composable
fun SensitiveDataPermissionDialog(
  request: SensitiveDataRequest,
  onResult: (SensitiveDataDecision) -> Unit,
) {
  val text = sensitiveDataPromptText(request)
  AlertDialog(
    onDismissRequest = { onResult(SensitiveDataDecision.DENY) },
    title = { Text(text.title, style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(text = text.message, style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = text.detailsLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          val formattedDetails = remember(text.details) { prettyPrintJsonOrRaw(text.details) }
          Text(
            text = formattedDetails,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The narrowest grant is the primary action.
        Button(
          onClick = { onResult(SensitiveDataDecision.ALLOW_ONCE) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.sensitive_data_allow_once))
        }
        OutlinedButton(
          onClick = { onResult(SensitiveDataDecision.ALLOW_FOR_CONVERSATION) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.sensitive_data_allow_for_chat))
        }
        OutlinedButton(
          onClick = { onResult(SensitiveDataDecision.DENY) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.sensitive_data_dont_allow))
        }
      }
    },
  )
}

@Composable
private fun sensitiveDataPromptText(request: SensitiveDataRequest): SensitiveDataPromptText =
  when (request) {
    is SensitiveDataRequest.Read ->
      when (request.type) {
        SensitiveDataType.CALENDAR ->
          SensitiveDataPromptText(
            title = stringResource(R.string.sensitive_data_calendar_read_title),
            message = stringResource(R.string.sensitive_data_calendar_read_message),
            detailsLabel = stringResource(R.string.sensitive_data_request_label),
            details = request.details,
          )
      }
  }

private fun prettyPrintJsonOrRaw(value: String): String =
  try {
    JSONObject(value).toString(2)
  } catch (e: JSONException) {
    value
  }
