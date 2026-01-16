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

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGTGViewModel"

/** The UI state of the task. */
data class TinyGardenUiState(
  // Whether the app is processing the user input.
  val processing: Boolean = false,

  // Whether the app is resetting the engine (without resetting the game).
  val resettingEngine: Boolean = false,

  // The messages in the conversation history.
  val messages: List<ChatMessage> = listOf(),

  // The number of turns.
  val numTurns: Int = 0,
)

/** The ViewModel of the task screen. */
@HiltViewModel
class TinyGardenViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  protected val _uiState = MutableStateFlow(TinyGardenUiState())
  val uiState = _uiState.asStateFlow()

  private val _isResettingConversation = MutableStateFlow(false)
  private val isResettingConversation = _isResettingConversation.asStateFlow()

  /**
   * Sends the user instruction to the model and processes the response.
   *
   * The tools defined in [TinyGardenTools] will be invoked during the process.
   */
  fun getCommand(
    model: Model,
    instructionText: String,
    onDone: (String) -> Unit,
    onError: (String) -> Unit,
  ) {
    if (model.instance == null) {
      setProcessing(processing = false)
      return
    }

    // Count turn.
    incrementNumTurns()
    Log.d(TAG, "Turn #: ${uiState.value.numTurns}")

    // Add user prompt to history.
    this.addMessage(message = ChatMessageText(content = instructionText, side = ChatSide.USER))

    viewModelScope.launch(Dispatchers.Default) {
      Log.d(TAG, "Start processing user instruction: '$instructionText'")
      setProcessing(processing = true)

      // Wait until the conversation is NOT resetting.
      Log.d(TAG, "Waiting for any ongoing conversation reset to be done...")
      isResettingConversation.first { !it }
      Log.d(TAG, "Done waiting. Start inference.")

      val instance = model.instance as LlmModelInstance
      val conversation = instance.conversation
      val contents = mutableListOf<Content>()
      if (instructionText.trim().isNotEmpty()) {
        contents.add(Content.Text(instructionText))
      }

      try {
        val responseMessage = conversation.sendMessage(Message.of(contents))
        val response = responseMessage.toString()
        Log.d(TAG, "Done processing user instruction. Response: $response")
        onDone(response)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to run inference", e)
        onError(e.message ?: context.getString(R.string.unknown_error))
      } finally {
        setProcessing(processing = false)
      }
    }
  }

  fun addMessage(message: ChatMessage) {
    val newMessages = _uiState.value.messages.toMutableList()
    newMessages.add(message)
    _uiState.update { _uiState.value.copy(messages = newMessages) }
  }

  fun clearMessages() {
    _uiState.update { _uiState.value.copy(messages = listOf()) }
  }

  fun setProcessing(processing: Boolean) {
    _uiState.update { uiState.value.copy(processing = processing) }
  }

  fun setResettingEngine(resetting: Boolean) {
    _uiState.update { uiState.value.copy(resettingEngine = resetting) }
  }

  fun incrementNumTurns() {
    _uiState.update { uiState.value.copy(numTurns = uiState.value.numTurns + 1) }
  }

  fun resetNumTurns() {
    _uiState.update { uiState.value.copy(numTurns = 0) }
  }

  fun resetEngine(
    context: Context,
    model: Model,
    tools: List<TinyGardenTools>,
    onError: (error: String) -> Unit,
  ) {
    resetNumTurns()

    viewModelScope.launch(Dispatchers.Default) {
      setResettingEngine(resetting = true)
      LlmChatModelHelper.cleanUp(
        model = model,
        onDone = {
          LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false,
            onDone = { error ->
              setResettingEngine(resetting = false)
              if (error.isNotEmpty()) {
                onError(error)
              }
              addMessage(
                message =
                  ChatMessageWarning(content = context.getString(R.string.engin_reset_message))
              )
            },
            systemMessage = Message.of(getTinyGardenSystemPrompt()),
            tools = tools,
            enableConversationConstrainedDecoding = true,
          )
        },
      )
    }
  }

  fun resetConversation(
    model: Model,
    tools: List<TinyGardenTools>,
    prevSeed: String,
    prevPlots: String,
    prevAction: String,
  ) {
    resetNumTurns()

    viewModelScope.launch(Dispatchers.Default) {
      _isResettingConversation.value = true
      val curSystemPrompt =
        getTinyGardenSystemPrompt(
          prevSeed = prevSeed,
          prevPlots = prevPlots,
          prevAction = prevAction,
        )
      Log.d(TAG, "Current system prompt:\n$curSystemPrompt")
      LlmChatModelHelper.resetConversation(
        model = model,
        supportImage = false,
        supportAudio = false,
        systemMessage = Message.of(curSystemPrompt),
        tools = tools,
        enableConversationConstrainedDecoding = true,
      )
      _isResettingConversation.value = false
      addMessage(
        message =
          ChatMessageWarning(content = context.getString(R.string.conversation_reset_message))
      )
    }
  }
}
