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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageMapper
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val systemPromptRepository: SystemPromptRepository? = null,
  private val modelFeedbackRepository: Any? = null,
  override val runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
) : ChatViewModel(runtimeExecutor, llmSessionManager) {
  private val _uiSystemPrompt = MutableStateFlow("")
  val uiSystemPrompt = _uiSystemPrompt.asStateFlow()
  // Map to track if the session was stopped by the model for a given model name.
  private val sessionStoppedByModel = mutableMapOf<String, Boolean>()
  // The current task ID for the session.
  private var currentTaskId: String = ""
  // Active session transition job to ensure transitions (restore, new session, reset) do not race.
  private var sessionTransitionJob: Job? = null

  /**
   * Sets the system prompt in the UI.
   *
   * This method updates the UI system prompt without saving it to the repository or resetting the
   * session. It is primarily used for initializing the UI system prompt.
   *
   * @param systemPrompt The new system prompt to set in the UI.
   */
  fun setUISystemPrompt(systemPrompt: String) {
    _uiSystemPrompt.value = systemPrompt
  }

  /**
   * Loads the system prompt for the given [task] from the repository.
   *
   * @param task The task to load the system prompt for.
   */
  fun loadSystemPrompt(task: Task) {
    currentTaskId = task.id
    viewModelScope.launch {
      val effectivePrompt =
        SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      _uiSystemPrompt.value = effectivePrompt
    }
  }

  /**
   * Applies a system prompt change to the given [task] and [model].
   *
   * This method updates the UI system prompt, saves the new prompt to the repository, and resets
   * the session with the new prompt.
   *
   * @param task The task to apply the system prompt change to.
   * @param model The model to apply the system prompt change to.
   * @param newPrompt The new system prompt to apply.
   * @param systemPromptUpdatedMessage The message to add to the chat after the system prompt is
   *   updated.
   */
  fun applySystemPromptChange(
    task: Task,
    model: Model,
    newPrompt: String,
    systemPromptUpdatedMessage: String,
  ) {
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = newPrompt,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage)) },
      )
    }
  }

  open fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      val attachments = mutableListOf<Attachment>()
      for (image in images) {
        attachments.add(Attachment.ImageBitmap(image))
      }
      for (audioMessage in audioMessages) {
        attachments.add(Attachment.AudioBytes(audioMessage.genByteArrayForWav()))
      }

      val enableThinking =
        allowThinking &&
          model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
      val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else emptyMap()
      val metadata =
        buildMap<String, Any> {
          put(AgentRequest.SESSION_ID, currentSessionId)
          if (extraContext.isNotEmpty()) {
            put(AgentRequest.LITERTLM_EXTRA_CONTEXT, extraContext)
          }
        }

      val request = AgentRequest(query = input, attachments = attachments, metadata = metadata)

      val context = AgentExecutionContext()

      var firstRun = true
      val start = System.currentTimeMillis()

      if (sessionStoppedByModel[model.name] == true) {
        sessionStoppedByModel[model.name] = false
        val initialMessages =
          (uiState.value.messagesByModel[model.name] ?: emptyList())
            .filterIsInstance<ChatMessageText>()
            .dropLast(1)
            .mapNotNull { convertToLitertMessage(it) }
        val config =
          AgentRuntimeConfig(
            model = model,
            taskId = currentTaskId,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
            systemInstruction = _uiSystemPrompt.value.ifEmpty { null },
            initialMessages = initialMessages,
          )
        runtimeExecutor.resetSession(config = config)
      }

      // Run inference.
      runtimeExecutor.executeStream(context = context, request = request).collect { event ->
        when (event) {
          is AgentEvent.LoopInitiated -> {}
          is AgentEvent.StreamToken -> {
            val lastMessage = getLastMessage(model = model)
            val wasLoading = lastMessage?.type == ChatMessageType.LOADING
            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            if (wasLoading) {
              removeLastMessage(model = model)
            }

            val thinkingText = event.thinking
            val isThinking = !thinkingText.isNullOrEmpty()
            var currentLastMessage = getLastMessage(model = model)

            // If thinking is enabled, add a thinking message.
            if (isThinking) {
              if (currentLastMessage?.type != ChatMessageType.THINKING) {
                addMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = "",
                      inProgress = true,
                      side = ChatSide.AGENT,
                      accelerator = accelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                    ),
                )
              }
              updateLastThinkingMessageContentIncrementally(
                model = model,
                partialContent = thinkingText!!,
              )
            } else {
              if (currentLastMessage?.type == ChatMessageType.THINKING) {
                val thinkingMsg = currentLastMessage as ChatMessageThinking
                if (thinkingMsg.inProgress) {
                  replaceLastMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = thinkingMsg.content,
                        inProgress = false,
                        side = thinkingMsg.side,
                        accelerator = thinkingMsg.accelerator,
                        hideSenderLabel = thinkingMsg.hideSenderLabel,
                      ),
                    type = ChatMessageType.THINKING,
                  )
                }
              }
              currentLastMessage = getLastMessage(model = model)
              if (
                currentLastMessage?.type != ChatMessageType.TEXT ||
                  currentLastMessage.side != ChatSide.AGENT
              ) {
                // Add an empty message that will receive streaming results.
                addMessage(
                  model = model,
                  message =
                    ChatMessageText(
                      content = "",
                      side = ChatSide.AGENT,
                      accelerator = accelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                          currentLastMessage?.type == ChatMessageType.THINKING,
                    ),
                )
              }

              // Incrementally update the streamed partial results.
              val latencyMs: Long = if (event.done) System.currentTimeMillis() - start else -1
              if (event.token.isNotEmpty() || wasLoading || event.done) {
                updateLastTextMessageContentIncrementally(
                  model = model,
                  partialContent = event.token,
                  latencyMs = latencyMs.toFloat(),
                )
              }
            }

            if (firstRun) {
              firstRun = false
              setPreparing(false)
              onFirstToken(model)
            }
          }
          is AgentEvent.LoopTerminated -> {
            val finalLastMessage = getLastMessage(model = model)
            if (finalLastMessage?.type == ChatMessageType.THINKING) {
              val thinkingMsg = finalLastMessage as ChatMessageThinking
              if (thinkingMsg.inProgress) {
                replaceLastMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = thinkingMsg.content,
                      inProgress = false,
                      side = thinkingMsg.side,
                      accelerator = thinkingMsg.accelerator,
                      hideSenderLabel = thinkingMsg.hideSenderLabel,
                    ),
                  type = ChatMessageType.THINKING,
                )
              }
            }
            setInProgress(false)
            setPreparing(false)
            onDone()
          }
          is AgentEvent.Error -> {
            Log.e(TAG, "Error occurred while running inference: ${event.errorMessage}")
            setInProgress(false)
            setPreparing(false)
            onError(event.errorMessage)
          }
          is AgentEvent.LoopCancelled -> {
            setInProgress(false)
            setPreparing(false)
          }
        }
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    } else {
      sessionStoppedByModel[model.name] = true
    }
    setInProgress(false)
    runtimeExecutor.interrupt()
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    actionChannel: SendChannel<ToolAction>? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    currentTaskId = task.id
    sessionTransitionJob?.cancel()
    sessionTransitionJob =
      viewModelScope.launch(Dispatchers.Default) {
        executeResetSession(
          task = task,
          model = model,
          systemInstruction = systemInstruction,
          actionChannel = actionChannel,
          supportImage = supportImage,
          supportAudio = supportAudio,
          onDone = onDone,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          initialMessages = initialMessages,
          clearHistory = clearHistory,
        )
      }
  }

  private suspend fun executeResetSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    actionChannel: SendChannel<ToolAction>? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    setIsResettingSession(true)
    try {
      if (clearHistory) {
        currentSessionId = generateSessionId()
        clearAllMessages(model = model)
      }
      stopResponse(model = model)
      sessionStoppedByModel[model.name] = false

      val config =
        AgentRuntimeConfig(
          sessionId = currentSessionId,
          model = model,
          taskId = task.id,
          actionChannel = actionChannel,
          supportImage = supportImage,
          supportAudio = supportAudio,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          systemInstruction = systemInstruction,
          initialMessages = initialMessages,
        )
      runtimeExecutor.resetSession(config = config)

      setIsResettingSession(false)
      onDone()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to reset session: ${e.message}", e)
      setIsResettingSession(false)
    }
  }

  /**
   * Restores a saved chat session from persistent storage into the UI state and model runtime.
   *
   * @param session The protobuf representation of the session to restore.
   * @param task The task associated with this session.
   * @param model The active model to restore into.
   * @param systemInstruction Optional system instruction prompt to apply.
   * @param supportImage Whether image input is supported.
   * @param supportAudio Whether audio input is supported.
   * @param onDone Callback invoked when restoration is complete.
   */
  open fun restoreSession(
    session: ChatSessionProto,
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
  ) {
    currentTaskId = task.id
    sessionTransitionJob?.cancel()
    sessionTransitionJob =
      viewModelScope.launch(Dispatchers.Default) {
        setIsResettingSession(true)
        stopResponse(model = model)

        try {
          val messages = ChatMessageMapper.deserializeProtoMessages(session.messagesList)
          ensureActive()

          currentSessionId = session.sessionId
          setRestoredMessages(model = model, messages = messages)

          val litertMessages = messages.mapNotNull { convertToLitertMessage(it) }
          executeResetSession(
            task = task,
            model = model,
            systemInstruction = systemInstruction ?: _uiSystemPrompt.value.ifEmpty { null },
            supportImage = supportImage,
            supportAudio = supportAudio,
            initialMessages = litertMessages,
            clearHistory = false,
            onDone = onDone,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restore session: ${e.message}", e)
          setIsResettingSession(false)
        }
      }
  }

  /**
   * Starts a new empty chat session, resetting both UI state and model runtime.
   *
   * @param task The task associated with the new session.
   * @param model The active model for the new session.
   * @param systemInstruction Optional system instruction prompt to apply.
   * @param supportImage Whether image input is supported.
   * @param supportAudio Whether audio input is supported.
   * @param onDone Callback invoked when creation is complete.
   */
  open fun startNewSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
  ) {
    currentTaskId = task.id
    resetSession(
      task = task,
      model = model,
      systemInstruction = systemInstruction ?: _uiSystemPrompt.value.ifEmpty { null },
      supportImage = supportImage,
      supportAudio = supportAudio,
      initialMessages = emptyList(),
      clearHistory = true,
      onDone = onDone,
    )
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      if (model.instance == null) {
        try {
          model.awaitInitialization()
        } catch (e: Exception) {
          onError("Model initialization failed: ${e.message}")
          return@launch
        }
      }
      if (model.instance == null) {
        onError("Model not initialized.")
        return@launch
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
            onDone = {
              // Add a warning message for re-initializing the session.
              addMessage(
                model = model,
                message = ChatMessageWarning(content = "Session re-initialized"),
              )
            },
            onError = {
              addMessage(
                model = model,
                message =
                  ChatMessageError(
                    content = "Failed to re-initialize session, please restart the app"
                  ),
              )
            },
          )
        },
      )
    }
  }
}

@HiltViewModel
open class LlmChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
) :
LlmChatViewModelBase(systemPromptRepository, null, runtimeExecutor,
llmSessionManager)

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
) :
LlmChatViewModelBase(systemPromptRepository, null, runtimeExecutor,
llmSessionManager)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
) :
LlmChatViewModelBase(systemPromptRepository, null, runtimeExecutor,
llmSessionManager)
