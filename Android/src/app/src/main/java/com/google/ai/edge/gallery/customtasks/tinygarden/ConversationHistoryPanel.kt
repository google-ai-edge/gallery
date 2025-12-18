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
package com.google.ai.edge.gallery.customtasks.tinygarden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyError
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyText
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyWarning
import com.google.ai.edge.gallery.ui.common.chat.MessageBubbleShape
import com.google.ai.edge.gallery.ui.common.chat.MessageSender
import com.google.ai.edge.gallery.ui.theme.customColors

/** A panel to show the conversation history. */
@Composable
fun ConversationHistoryPanel(
  task: Task,
  bottomPadding: Dp,
  viewModel: TinyGardenViewModel,
  onDismiss: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  val listState = rememberScrollState()

  Column(
    modifier =
      Modifier.background(color = MaterialTheme.colorScheme.surface)
        .fillMaxSize()
        .padding(bottom = bottomPadding)
  ) {
    // Scroll to bottom when adding a new message.
    LaunchedEffect(uiState.messages.size) {
      if (uiState.messages.isNotEmpty()) {
        listState.animateScrollTo(1000000)
      }
    }

    // Title and button to dismiss.
    Row(
      modifier =
        Modifier.background(color = MaterialTheme.colorScheme.surfaceContainerHighest)
          .fillMaxWidth()
          .padding(start = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        stringResource(R.string.conversation_history),
        style = MaterialTheme.typography.titleMedium,
      )
      IconButton(onClick = { onDismiss() }) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_icon),
        )
      }
    }

    // Message list.
    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(state = listState)
    ) {
      for (message in uiState.messages) {
        var hAlign: Alignment.Horizontal = Alignment.End
        var backgroundColor: Color = MaterialTheme.customColors.userBubbleBgColor
        var hardCornerAtLeftOrRight = false
        var extraPaddingStart = 48.dp
        var extraPaddingEnd = 0.dp
        if (message.side == ChatSide.AGENT) {
          hAlign = Alignment.Start
          backgroundColor = MaterialTheme.customColors.agentBubbleBgColor
          hardCornerAtLeftOrRight = true
          extraPaddingStart = 0.dp
          extraPaddingEnd = 48.dp
        } else if (message.side == ChatSide.SYSTEM) {
          extraPaddingStart = 24.dp
          extraPaddingEnd = 24.dp
        }
        val bubbleBorderRadius = dimensionResource(R.dimen.chat_bubble_corner_radius)

        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(start = extraPaddingStart, end = extraPaddingEnd, top = 6.dp, bottom = 6.dp),
          horizontalAlignment = hAlign,
        ) messageColumn@{
          // Sender row.
          var agentName = stringResource(task.agentNameRes)
          if (message.accelerator.isNotEmpty()) {
            agentName = "$agentName on ${message.accelerator}"
          }
          MessageSender(message = message, agentName = agentName)

          when (message) {
            // Warning.
            is ChatMessageWarning -> MessageBodyWarning(message = message)

            // Error.
            is ChatMessageError -> MessageBodyError(message = message)

            else -> {
              // Message body.
              when (message) {
                // Text
                is ChatMessageText -> {
                  Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Box(
                      modifier =
                        Modifier.clip(
                            MessageBubbleShape(
                              radius = bubbleBorderRadius,
                              hardCornerAtLeftOrRight = hardCornerAtLeftOrRight,
                            )
                          )
                          .background(backgroundColor)
                    ) {
                      MessageBodyText(message = message, inProgress = false)
                    }
                  }
                }
                else -> {}
              }
            }
          }
        }
      }
    }
  }
}
