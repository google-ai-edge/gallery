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

package com.google.ai.edge.gallery.ui.common.tts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Observes chat session lifecycle and streaming messages to drive [controller] auto-play and stop
 * speech on session resets or disposal.
 */
@Composable
fun TtsChatEffects(
  controller: TtsPlaybackController,
  modelManagerViewModel: ModelManagerViewModel,
  selectedModelName: String,
  isResettingSession: Boolean,
  isStreaming: Boolean,
  lastAgentMessageId: String?,
  lastAgentMessageContent: String?,
  capabilityName: String = "llm_chat",
) {
  val ttsSettings by modelManagerViewModel.ttsSettingsUiState.collectAsStateWithLifecycle()
  val activeMessageId by controller.activeMessageId.collectAsStateWithLifecycle()
  val autoPlayTts = ttsSettings.autoPlayTts

  var streamingMessageId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(isResettingSession, selectedModelName) {
    controller.stop()
    streamingMessageId = null
  }

  LaunchedEffect(
    lastAgentMessageId,
    lastAgentMessageContent,
    isStreaming,
    autoPlayTts,
    activeMessageId,
  ) {
    val canAutoPlay = controller.isAvailable && autoPlayTts
    val shouldStream =
      canAutoPlay || (lastAgentMessageId != null && activeMessageId == lastAgentMessageId)
    when {
      shouldStream -> {
        if (lastAgentMessageId != null && lastAgentMessageContent != null) {
          when {
            isStreaming -> {
              streamingMessageId = lastAgentMessageId
              if (lastAgentMessageContent.any { it in ".!?\n" }) {
                controller.streamAutoPlay(
                  messageId = lastAgentMessageId,
                  text = lastAgentMessageContent,
                  isDone = false,
                  capabilityName = capabilityName,
                )
              }
            }
            streamingMessageId == lastAgentMessageId -> {
              streamingMessageId = null
              controller.streamAutoPlay(
                messageId = lastAgentMessageId,
                text = lastAgentMessageContent,
                isDone = true,
                capabilityName = capabilityName,
              )
            }
          }
        }
      }
      !isStreaming -> {
        streamingMessageId = null
      }
    }
  }
}

/** Speaker button displayed on agent messages to trigger or control TTS playback. */
@Composable
fun TtsSpeakerButton(
  messageId: String,
  messageText: String,
  controller: TtsPlaybackController,
  modifier: Modifier = Modifier,
  capabilityName: String = "llm_chat",
) {
  val activeMessageId by controller.activeMessageId.collectAsStateWithLifecycle()
  val isActiveMessage = activeMessageId == messageId

  IconButton(
    onClick = {
      if (isActiveMessage) {
        controller.dismiss()
      } else {
        controller.startPlayback(messageId, messageText, capabilityName = capabilityName)
      }
    },
    modifier = modifier.size(28.dp),
  ) {
    AnimatedContent(
      targetState = isActiveMessage,
      transitionSpec = { fadeIn() togetherWith fadeOut() },
      label = "TtsSpeakerButtonAnimation",
    ) { active ->
      if (active) {
        Box(
          modifier =
            Modifier.size(28.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = stringResource(R.string.tts_dismiss),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
        }
      } else {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
          contentDescription = stringResource(R.string.tts_read_aloud),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
