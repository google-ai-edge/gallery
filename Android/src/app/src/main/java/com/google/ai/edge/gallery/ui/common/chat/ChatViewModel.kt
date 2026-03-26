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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
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
)

/** ViewModel responsible for managing the chat UI state and handling chat-related operations. */
abstract class ChatViewModel() : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()

  fun addMessage(model: Model, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Remove prompt template message if it is the current last message.
    if (newMessages.size > 0 && newMessages.last().type == ChatMessageType.PROMPT_TEMPLATES) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessages.add(message)
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun insertMessageAfter(model: Model, anchorMessage: ChatMessage, messageToAdd: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Find the index of the anchor message
    val anchorIndex = newMessages.indexOf(anchorMessage)
    if (anchorIndex != -1) {
      // Insert the new message after the anchor message
      newMessages.add(anchorIndex + 1, messageToAdd)
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeMessageAt(model: Model, index: Int) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      if (index >= 0 && index < newMessages.size) {
        newMessages.removeAt(index)
      }
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

  fun getLastMessageWithType(model: Model, type: ChatMessageType): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull { it.type == type }
  }

  fun getLastMessageWithTypeAndSide(
    model: Model,
    type: ChatMessageType,
    side: ChatSide,
  ): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull {
      it.type == type && it.side == side
    }
  }

  fun updateLastThinkingMessageContentIncrementally(model: Model, partialContent: String) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageThinking) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageThinking(
            content = newContent,
            inProgress = lastMessage.inProgress,
            side = lastMessage.side,
            hideSenderLabel = lastMessage.hideSenderLabel,
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

  fun updateLastTextMessageContentIncrementally(
    model: Model,
    partialContent: String,
    latencyMs: Float,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageText(
            content = newContent,
            side = lastMessage.side,
            latencyMs = latencyMs,
            accelerator = lastMessage.accelerator,
            hideSenderLabel = lastMessage.hideSenderLabel,
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
    if (index >= 0 && index < newMessages.size) {
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

  fun updateCollapsableProgressPanelMessage(
    model: Model,
    title: String,
    inProgress: Boolean,
    doneIcon: ImageVector,
    addItemTitle: String,
    addItemDescription: String,
    customData: Any? = null,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()

    val createNewCollapsableMessage = {
      ChatMessageCollapsableProgressPanel(
        title = title,
        inProgress = inProgress,
        doneIcon = doneIcon,
        items =
          if (addItemTitle.isNotEmpty()) {
            listOf(ProgressPanelItem(title = addItemTitle, description = addItemDescription))
          } else {
            listOf()
          },
        accelerator = accelerator,
        customData = customData,
      )
    }

    if (newMessages.isNotEmpty() && newMessages.last() is ChatMessageLoading) {
      newMessages.removeAt(newMessages.size - 1)
      newMessages.add(createNewCollapsableMessage())
    } else {
      val lastProgressPanelMessage =
        getLastMessageWithType(model = model, type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL)
      val lastProgressPanelMessageIndex = newMessages.indexOf(lastProgressPanelMessage)
      val lastUserTextMessage =
        getLastMessageWithTypeAndSide(
          model = model,
          type = ChatMessageType.TEXT,
          side = ChatSide.USER,
        )
      val lastUserTextMessageIndex = newMessages.indexOf(lastUserTextMessage)

      // If the last user text message is after the last progress panel message, insert the new
      // collapsable message after the last user text message.
      if (
        lastProgressPanelMessage != null &&
          lastUserTextMessage != null &&
          lastUserTextMessageIndex > lastProgressPanelMessageIndex
      ) {
        newMessages.add(lastUserTextMessageIndex + 1, createNewCollapsableMessage())
      }
      // If the last progress panel message is a collapsable progress panel, update it.
      else if (
        lastProgressPanelMessage != null &&
          lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
      ) {
        val updatedMessage =
          ChatMessageCollapsableProgressPanel(
            title = title,
            accelerator = accelerator,
            inProgress = inProgress,
            doneIcon = doneIcon,
            items =
              lastProgressPanelMessage.items +
                if (addItemTitle.isNotEmpty()) {
                  listOf(ProgressPanelItem(title = addItemTitle, description = addItemDescription))
                } else {
                  listOf()
                },
            customData = lastProgressPanelMessage.customData,
            logMessages = lastProgressPanelMessage.logMessages,
          )
        newMessages[lastProgressPanelMessageIndex] = updatedMessage
      } else {
        // If none of the above conditions match (for example, the chat history for the
        // current model is empty after a model switch during skill execution),
        // simply append a new collapsable progress panel to show the running skill status.
        newMessages.add(createNewCollapsableMessage())
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun addLogMessageToLastCollapsableProgressPanel(model: Model, logMessage: LogMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastCollapsableIndex = newMessages.indexOfLast {
        it is ChatMessageCollapsableProgressPanel
      }
      if (lastCollapsableIndex != -1) {
        val lastMessage = newMessages[lastCollapsableIndex] as ChatMessageCollapsableProgressPanel
        val newLogMessages = lastMessage.logMessages + logMessage
        val updatedMessage =
          ChatMessageCollapsableProgressPanel(
            title = lastMessage.title,
            inProgress = lastMessage.inProgress,
            accelerator = lastMessage.accelerator,
            doneIcon = lastMessage.doneIcon,
            items = lastMessage.items,
            logMessages = newLogMessages,
            customData = lastMessage.customData,
          )
        newMessages[lastCollapsableIndex] = updatedMessage
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
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

  private fun createUiState(): ChatUiState {
    return ChatUiState()
  }
}
