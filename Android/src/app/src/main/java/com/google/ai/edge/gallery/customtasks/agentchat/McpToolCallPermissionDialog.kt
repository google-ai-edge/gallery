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
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.PermissionResult
import org.json.JSONObject

/** A dialog that prompts the user for permission to execute a MCP tool call. */
@Composable
fun McpToolCallPermissionDialog(
  toolName: String,
  argument: String,
  onResult: (PermissionResult) -> Unit,
) {
  AlertDialog(
    onDismissRequest = { onResult(PermissionResult.DENY) },
    title = {
      Text(
        stringResource(R.string.mcp_tool_call_permission_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 1.sp),
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.mcp_tool_name_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(text = toolName, style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.mcp_tool_input_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          val formattedArgument =
            remember(argument) {
              try {
                JSONObject(argument).toString(2)
              } catch (e: Exception) {
                argument
              }
            }
          Text(
            text = formattedArgument,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
          onClick = { onResult(PermissionResult.ALWAYS_ALLOW) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_always_allow))
        }
        Button(
          onClick = { onResult(PermissionResult.ALLOW_ONCE) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_allow_once))
        }
        OutlinedButton(
          onClick = { onResult(PermissionResult.DENY) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_dont_allow))
        }
      }
    },
  )
}
