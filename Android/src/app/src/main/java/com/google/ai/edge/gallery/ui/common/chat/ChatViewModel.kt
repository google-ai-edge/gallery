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

package com.google.ai.edge.gallery.ui.common.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "AGChatViewModel"

data class ChatUiState(
  /** Indicates whether the runtime is currently processing a message. */
  val inProgress: Boolean = false,

  /** Indicates whether the session is being reset. */
  val isResettingSession: Boolean = false,

  /**
   * Indicates whether the model is preparing (before outputting any result and after initializing).
   */
  val preparing: Boolean = false,

  /** A map of model names to lists of chat messages. */
  val messagesByModel: Map<String, MutableList<ChatMessage>> = mapOf(),

  /** A map of model names to the currently streaming chat message. */
  val streamingMessagesByModel: Map<String, ChatMessage> = mapOf(),

  /*
   * A map of model names to a map of chat messages to a boolean indicating whether the message is
   * showing the stats below it.
   */
  val showingStatsByModel: Map<String, MutableSet<ChatMessage>> = mapOf(),
)

/** ViewModel responsible for managing the chat UI state and handling chat-related operations. */
abstract class ChatViewModel(val task: Task) : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState(task = task))
  val uiState = _uiState.asStateFlow()

  fun addMessage(model: Model, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      // Remove prompt template message if it is the current last message.
      if (newMessages.size > 0 && newMessages.last().type == ChatMessageType.PROMPT_TEMPLATES) {
        newMessages.removeAt(newMessages.size - 1)
      }
      newMessages.add(message)
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun insertMessageAfter(model: Model, anchorMessage: ChatMessage, messageToAdd: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      // Find the index of the anchor message
      val anchorIndex = newMessages.indexOf(anchorMessage)
      if (anchorIndex != -1) {
        // Insert the new message after the anchor message
        newMessages.add(anchorIndex + 1, messageToAdd)
      }
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeMessageAt(model: Model, index: Int) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      newMessages.removeAt(index)
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeLastMessage(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun clearAllMessages(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    newMessagesByModel[model.name] = mutableListOf()
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun getLastMessage(model: Model): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull()
  }

  fun updateLastTextMessageContentIncrementally(
    model: Model,
    partialContent: String,
    latencyMs: Float,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageText(
            content = newContent,
            side = lastMessage.side,
            latencyMs = latencyMs,
            accelerator = lastMessage.accelerator,
          )
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(newLastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun updateLastTextMessageLlmBenchmarkResult(
    model: Model,
    llmBenchmarkResult: ChatMessageBenchmarkLlmResult,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        lastMessage.llmBenchmarkResult = llmBenchmarkResult
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(lastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun replaceLastMessage(model: Model, message: ChatMessage, type: ChatMessageType) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val index = newMessages.indexOfLast { it.type == type }
      if (index >= 0) {
        newMessages[index] = message
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun replaceMessage(model: Model, index: Int, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      newMessages[index] = message
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun updateStreamingMessage(model: Model, message: ChatMessage) {
    val newStreamingMessagesByModel = _uiState.value.streamingMessagesByModel.toMutableMap()
    newStreamingMessagesByModel[model.name] = message
    _uiState.update { _uiState.value.copy(streamingMessagesByModel = newStreamingMessagesByModel) }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { _uiState.value.copy(inProgress = inProgress) }
  }

  fun setIsResettingSession(isResettingSession: Boolean) {
    _uiState.update { _uiState.value.copy(isResettingSession = isResettingSession) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { _uiState.value.copy(preparing = preparing) }
  }

  fun addConfigChangedMessage(
    oldConfigValues: Map<String, Any>,
    newConfigValues: Map<String, Any>,
    model: Model,
  ) {
    Log.d(TAG, "Adding config changed message. Old: ${oldConfigValues}, new: $newConfigValues")
    val message =
      ChatMessageConfigValuesChange(
        model = model,
        oldValues = oldConfigValues,
        newValues = newConfigValues,
      )
    addMessage(message = message, model = model)
  }

  fun getMessageIndex(model: Model, message: ChatMessage): Int {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).indexOf(message)
  }

  fun isShowingStats(model: Model, message: ChatMessage): Boolean {
    return _uiState.value.showingStatsByModel[model.name]?.contains(message) ?: false
  }

  fun toggleShowingStats(model: Model, message: ChatMessage) {
    val newShowingStatsByModel = _uiState.value.showingStatsByModel.toMutableMap()
    val newShowingStats = newShowingStatsByModel[model.name]?.toMutableSet() ?: mutableSetOf()
    if (newShowingStats.contains(message)) {
      newShowingStats.remove(message)
    } else {
      newShowingStats.add(message)
    }
    newShowingStatsByModel[model.name] = newShowingStats
    _uiState.update { _uiState.value.copy(showingStatsByModel = newShowingStatsByModel) }
  }

  private fun createUiState(task: Task): ChatUiState {
    val messagesByModel: MutableMap<String, MutableList<ChatMessage>> = mutableMapOf()
    for (model in task.models) {
      val messages: MutableList<ChatMessage> = mutableListOf()
      if (model.llmPromptTemplates.isNotEmpty()) {
        messages.add(ChatMessagePromptTemplates(templates = model.llmPromptTemplates))
      }
      messagesByModel[model.name] = messages
    }
    return ChatUiState(messagesByModel = messagesByModel)
  }
}
