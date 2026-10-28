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

package com.google.ai.edge.gallery.ui.common.asr

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.common.hasHiltComponentManager
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import com.google.ai.edge.gallery.ui.common.PermissionAccessKind
import com.google.ai.edge.gallery.ui.common.rememberPermissionAccessState

/** Appends a dictated [transcript] onto [curMessage], preserving spacing around existing text. */
@Suppress("DiscourageTopLevelFunctions")
fun mergeDictationTranscript(curMessage: String, transcript: String): String =
  if (curMessage.isBlank()) transcript else "${curMessage.trimEnd()} $transcript"

/**
 * Resolves [ChatAsrDictationViewModel] when ASR voice input is enabled under Hilt. Dictation is
 * cancelled, discarding its transcript, when the app leaves the foreground.
 */
@Composable
fun rememberChatAsrDictationViewModel(context: Context): ChatAsrDictationViewModel? =
  if (hasHiltComponentManager(context)) {
    hiltViewModel<ChatAsrDictationViewModel>().also { viewModel ->
      OnAppBackgroundedEffect { viewModel.cancel() }
    }
  } else {
    null
  }

/** Self-contained callbacks and UI state coordinating live voice dictation in chat input bars. */
data class ChatAsrDictationActions(
  val uiState: ChatAsrDictationUiState,
  val onStartDictation: () -> Unit,
  val onStopDictation: () -> Unit,
  val onSendDictation: () -> Unit,
  val onCancelDictation: () -> Unit,
  val isMicPermissionDenied: Boolean = false,
)

/**
 * Coordinates microphone runtime permission checks, automatic disposal cancellation, draft text
 * merging, and direct message dispatch for live ASR dictation in AI Chat.
 */
@Composable
fun rememberChatAsrDictationActions(
  asrDictationUiState: ChatAsrDictationUiState?,
  curMessage: String,
  onValueChanged: (String) -> Unit,
  onSendTranscriptMessage: (String) -> Unit,
  onStartAsrDictation:
    ((onEndedBySession: (transcript: String, failed: Boolean) -> Unit) -> Unit)? =
    null,
  onFinishAsrDictation: ((onTranscript: (String) -> Unit) -> Unit)? = null,
  onCancelAsrDictation: (() -> Unit)? = null,
  onAsrDictationError: () -> Unit = {},
): ChatAsrDictationActions {
  val effectiveUiState = asrDictationUiState ?: ChatAsrDictationUiState()
  val latestMessage by rememberUpdatedState(curMessage)
  val latestOnValueChanged by rememberUpdatedState(onValueChanged)
  val latestOnSendTranscriptMessage by rememberUpdatedState(onSendTranscriptMessage)
  val latestOnStart by rememberUpdatedState(onStartAsrDictation)
  val latestOnFinish by rememberUpdatedState(onFinishAsrDictation)
  val latestOnCancel by rememberUpdatedState(onCancelAsrDictation)
  val latestOnError by rememberUpdatedState(onAsrDictationError)

  DisposableEffect(onCancelAsrDictation) { onDispose { onCancelAsrDictation?.invoke() } }

  val handleEndedBySession: (String, Boolean) -> Unit = { transcript, failed ->
    if (transcript.isNotEmpty()) {
      latestOnValueChanged(mergeDictationTranscript(latestMessage, transcript))
    }
    if (failed) {
      latestOnError()
    }
  }

  val micPermissionState = rememberPermissionAccessState(PermissionAccessKind.MICROPHONE)

  val handleStart: () -> Unit = {
    micPermissionState.requestOrRun { latestOnStart?.invoke(handleEndedBySession) }
  }

  val handleStop: () -> Unit = {
    latestOnFinish?.invoke { transcript ->
      if (transcript.isNotEmpty()) {
        latestOnValueChanged(mergeDictationTranscript(latestMessage, transcript))
      }
    }
  }

  val handleSend: () -> Unit = {
    latestOnFinish?.invoke { transcript ->
      if (transcript.isNotEmpty()) {
        latestOnSendTranscriptMessage(mergeDictationTranscript(latestMessage, transcript))
      }
    }
  }

  val handleCancel: () -> Unit = { latestOnCancel?.invoke() }

  return ChatAsrDictationActions(
    uiState = effectiveUiState,
    onStartDictation = handleStart,
    onStopDictation = handleStop,
    onSendDictation = handleSend,
    onCancelDictation = handleCancel,
    isMicPermissionDenied = micPermissionState.isDenied,
  )
}
