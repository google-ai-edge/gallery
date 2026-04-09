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

package com.google.ai.edge.gallery.customtasks.metasploitagent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/** Task ID for the Metasploit Agent – must match [MetasploitAgentTaskModule]. */
const val METASPLOIT_AGENT_TASK_ID = "llm_metasploit_agent"

/**
 * Main screen for the Metasploit Agent task.
 *
 * Wraps [LlmChatScreen] and wires [MetasploitTools.actionChannel] into the
 * standard collapsable-progress-panel and ask-info-dialog that AgentChatScreen uses,
 * so the LLM's tool-call progress is visible in the chat timeline.
 */
@Composable
fun MetasploitAgentScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  metasploitTools: MetasploitTools,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInput by remember { mutableStateOf("") }

  // Alert dialog for prompting the user (e.g. MSF password, API key).
  if (showAskInfoDialog) {
    val action = currentAskInfoAction
    AlertDialog(
      onDismissRequest = {
        action?.result?.complete("")
        showAskInfoDialog = false
        askInfoInput = ""
      },
      title = { Text(action?.dialogTitle ?: "Input required") },
      text = {
        OutlinedTextField(
          value = askInfoInput,
          onValueChange = { askInfoInput = it },
          label = { Text(action?.fieldLabel ?: "Value") },
          singleLine = true,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            action?.result?.complete(askInfoInput)
            showAskInfoDialog = false
            askInfoInput = ""
          }
        ) {
          Text("OK")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            action?.result?.complete("")
            showAskInfoDialog = false
            askInfoInput = ""
          }
        ) {
          Text("Cancel")
        }
      },
    )
  }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = METASPLOIT_AGENT_TASK_ID,
    navigateUp = navigateUp,
    composableBelowMessageList = { model ->
      val actionChannel = metasploitTools.actionChannel
      val doneIcon: ImageVector = Icons.Outlined.Security
      val currentModel by rememberUpdatedState(model)

      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
              )
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInput = ""
              showAskInfoDialog = true
            }
            else -> {}
          }
        }
      }
    },
  )
}
