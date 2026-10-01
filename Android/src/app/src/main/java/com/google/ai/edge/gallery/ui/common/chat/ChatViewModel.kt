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

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.ChatSessionProto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGChatViewModel"

enum class ContextCompactionStatus {
  IDLE,
  TOKEN_LIMIT_REACHED,
  COMPACTING,
  COMPACTED,
}

/** Source that triggered context compaction. */
enum class ContextCompactionTriggerType(val value: String) {
  AUTO("auto"),
  MANUAL("manual"),
}

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

  /** A map of model names to their context compaction status. */
  val contextCompactionStatusByModel: Map<String, ContextCompactionStatus> = mapOf(),

  /** A map of model names to whether auto-compaction is enabled by default in the thread. */
  val autoCompactByModel: Map<String, Boolean> = mapOf(),

)

/**
 * ViewModel responsible for managing the chat UI state and handling chat-related operations.
 *
 * @property runtimeExecutor Optional [AgentRuntimeExecutor] to delegate inference.
 * @property llmSessionManager [LlmSessionManager] to delegate session management and persistence.
 */
abstract class ChatViewModel(
  open val runtimeExecutor: AgentRuntimeExecutor? = null,
  val llmSessionManager: LlmSessionManager,
) : ViewModel() {
  /** The identifier for the current active chat session. */
  open var currentSessionId: String
    get() =
      llmSessionManager.activeSessionId
        ?: error("Cannot get currentSessionId: No active session found in LlmSessionManager")
    set(value) {
      llmSessionManager.activeSessionId = value
    }

  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()

  private var compactionJob: Job? = null

  /** Cancels any in-flight context compaction coroutine. */
  protected fun cancelCompaction() {
    compactionJob?.cancel()
    compactionJob = null
  }

  val historySessions: StateFlow<List<ChatSessionProto>> =
    llmSessionManager.chatSessions.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  fun addMessage(model: Model, message: ChatMessage) {
    _uiState.update { state ->
      val newMessagesByModel = state.messagesByModel.toMutableMap()
      val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
      newMessagesByModel[model.name] = newMessages
      // Remove prompt template message if it is the current last message.
      if (newMessages.isNotEmpty() && newMessages.last().type == ChatMessageType.PROMPT_TEMPLATES) {
        newMessages.removeAt(newMessages.size - 1)
      }
      newMessages.add(message)
      val status = state.contextCompactionStatusByModel[model.name]
      val newStatusMap =
        if (status == ContextCompactionStatus.COMPACTED && message.side == ChatSide.USER) {
          state.contextCompactionStatusByModel + (model.name to ContextCompactionStatus.IDLE)
        } else {
          state.contextCompactionStatusByModel
        }
      state.copy(
        messagesByModel = newMessagesByModel,
        contextCompactionStatusByModel = newStatusMap,
      )
    }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeLastMessage(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun clearAllMessages(model: Model) {
    cancelCompaction()
    _uiState.update { state ->
      state.copy(
        messagesByModel = state.messagesByModel + (model.name to mutableListOf()),
        contextCompactionStatusByModel =
          state.contextCompactionStatusByModel + (model.name to ContextCompactionStatus.IDLE),
      )
    }
  }

  /**
   * Updates whether automatic context compaction is enabled for the given [model] in both the
   * model's configuration values and the UI state.
   *
   * @param model The model for which to update the auto-compaction setting.
   * @param enabled Whether automatic context compaction should be enabled.
   */
  fun setAutoCompact(model: Model, enabled: Boolean) {
    val newConfigValues = model.configValues.toMutableMap()
    newConfigValues[ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT.label] = enabled
    model.configValues = newConfigValues
    _uiState.update { state ->
      state.copy(autoCompactByModel = state.autoCompactByModel + (model.name to enabled))
    }
  }

  /**
   * Returns whether automatic context compaction is enabled for the given [model].
   *
   * Checks the UI state override first, falling back to the model's
   * [ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT] configuration value (`false` by default).
   *
   * @param model The model to check.
   * @return `true` if automatic context compaction is enabled for [model], `false` otherwise.
   */
  fun isAutoCompactEnabled(model: Model): Boolean {
    return _uiState.value.autoCompactByModel[model.name]
      ?: model.getBooleanConfigValue(
        key = ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT,
        defaultValue = false,
      )
  }

  /**
   * Updates the [ContextCompactionStatus] for the given [model] in the UI state.
   *
   * @param model The model whose compaction status is being updated.
   * @param status The new [ContextCompactionStatus] to set.
   */
  fun setContextCompactionStatus(model: Model, status: ContextCompactionStatus) {
    _uiState.update { state ->
      state.copy(
        contextCompactionStatusByModel =
          state.contextCompactionStatusByModel + (model.name to status)
      )
    }
  }

  /**
   * Returns the current [ContextCompactionStatus] for the given [model], defaulting to
   * [ContextCompactionStatus.IDLE] if no status has been recorded.
   *
   * @param model The model whose compaction status to retrieve.
   * @return The current [ContextCompactionStatus] for [model].
   */
  fun getContextCompactionStatus(model: Model): ContextCompactionStatus {
    return _uiState.value.contextCompactionStatusByModel[model.name] ?: ContextCompactionStatus.IDLE
  }

  /**
   * Triggers context compaction for the active chat session on the given [model].
   *
   * Logs analytics events for the compaction request, updates the model's [ContextCompactionStatus]
   * in the UI state while compaction runs asynchronously, and invokes [onSuccess] or [onError]
   * based on the outcome.
   *
   * @param model The model whose session context should be compacted.
   * @param taskId The identifier of the task or capability associated with the chat session.
   * @param triggerType The [ContextCompactionTriggerType] indicating what initiated compaction.
   * @param onSuccess Callback invoked when context compaction completes and compacts the session.
   * @param onError Callback invoked if context compaction fails with an exception.
   */
  open fun compactContext(
    model: Model,
    taskId: String = "",
    triggerType: ContextCompactionTriggerType =
      if (isAutoCompactEnabled(model)) {
        ContextCompactionTriggerType.AUTO
      } else {
        ContextCompactionTriggerType.MANUAL
      },
    onSuccess: () -> Unit = {},
    onError: () -> Unit = {},
  ) {
    val unused =
      startCompactionJob(
        model = model,
        taskId = taskId,
        triggerType = triggerType,
        onSuccess = onSuccess,
        onError = onError,
      )
  }

  /**
   * Suspends while executing context compaction for the active chat session on the given [model].
   *
   * Launches compaction in a dedicated [compactionJob] so that calling [cancelCompaction] cancels
   * only the compaction work and not the caller's coroutine (e.g., an active generation loop).
   */
  protected open suspend fun executeCompactContext(
    model: Model,
    taskId: String = "",
    triggerType: ContextCompactionTriggerType =
      if (isAutoCompactEnabled(model)) {
        ContextCompactionTriggerType.AUTO
      } else {
        ContextCompactionTriggerType.MANUAL
      },
    onSuccess: () -> Unit = {},
    onError: () -> Unit = {},
  ) {
    val job =
      startCompactionJob(
        model = model,
        taskId = taskId,
        triggerType = triggerType,
        onSuccess = onSuccess,
        onError = onError,
      ) ?: return
    try {
      job.join()
    } catch (e: CancellationException) {
      job.cancel()
      throw e
    }
  }

  private fun startCompactionJob(
    model: Model,
    taskId: String,
    triggerType: ContextCompactionTriggerType,
    onSuccess: () -> Unit,
    onError: () -> Unit,
  ): Job? {
    if (!Config.isContextCompactEnabled()) {
      return null
    }
    cancelCompaction()
    val job = viewModelScope.launch {
      runCompaction(
        model = model,
        taskId = taskId,
        triggerType = triggerType,
        onSuccess = onSuccess,
        onError = onError,
      )
    }
    compactionJob = job
    return job
  }

  private suspend fun runCompaction(
    model: Model,
    taskId: String,
    triggerType: ContextCompactionTriggerType,
    onSuccess: () -> Unit,
    onError: () -> Unit,
  ) {
    val defaultToBehavior = isAutoCompactEnabled(model)
    Log.d(
      TAG,
      "Analytics: context_compression, capability_name=$taskId, model_id=${model.name}, model_version=${model.downloadInfo.version}, trigger_type=${triggerType.value}, default_to_behavior=$defaultToBehavior",
    )
    firebaseAnalytics?.logEvent(
      GalleryEvent.CONTEXT_COMPRESSION.id,
      Bundle().apply {
        putString("capability_name", taskId)
        putString("model_id", model.name)
        putString("model_version", model.downloadInfo.version)
        putString("trigger_type", triggerType.value)
        putBoolean("default_to_behavior", defaultToBehavior)
      },
    )
    if (triggerType == ContextCompactionTriggerType.MANUAL && defaultToBehavior) {
      Log.d(
        TAG,
        "Analytics: auto_compaction_enable, capability_name=$taskId, model_id=${model.name}, model_version=${model.downloadInfo.version}",
      )
      firebaseAnalytics?.logEvent(
        GalleryEvent.AUTO_COMPACTION_ENABLE.id,
        Bundle().apply {
          putString("capability_name", taskId)
          putString("model_id", model.name)
          putString("model_version", model.downloadInfo.version)
        },
      )
    }
    val thisJob = compactionJob
    setContextCompactionStatus(model, ContextCompactionStatus.COMPACTING)
    try {
      val success = llmSessionManager.compactContextIfNeeded(currentSessionId, model, force = true)
      if (success) {
        Log.d(TAG, "Context compacted for session $currentSessionId on model ${model.name}")
        setContextCompactionStatus(model, ContextCompactionStatus.COMPACTED)
        onSuccess()
      } else {
        Log.d(
          TAG,
          "Context compaction did not occur for session $currentSessionId on model ${model.name}",
        )
        setContextCompactionStatus(model, ContextCompactionStatus.IDLE)
      }
    } catch (e: CancellationException) {
      if (getContextCompactionStatus(model) == ContextCompactionStatus.COMPACTING) {
        setContextCompactionStatus(model, ContextCompactionStatus.IDLE)
      }
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to compact context for session $currentSessionId", e)
      setContextCompactionStatus(model, ContextCompactionStatus.IDLE)
      onError()
    } finally {
      if (compactionJob === thisJob) {
        compactionJob = null
      }
    }
  }

  /**
   * Sets the restored messages for a model atomically in the UI state.
   *
   * @param model The model associated with the messages.
   * @param messages The list of restored domain messages.
   */
  fun setRestoredMessages(model: Model, messages: List<ChatMessage>) {
    _uiState.update { state ->
      state.copy(
        messagesByModel = state.messagesByModel + (model.name to messages.toMutableList()),
      )
    }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun replaceMessage(model: Model, index: Int, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (index >= 0 && index < newMessages.size) {
      newMessages[index] = message
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun updateStreamingMessage(model: Model, message: ChatMessage) {
    val newStreamingMessagesByModel = _uiState.value.streamingMessagesByModel.toMutableMap()
    newStreamingMessagesByModel[model.name] = message
    _uiState.update { it.copy(streamingMessagesByModel = newStreamingMessagesByModel) }
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
    val accelerator = model.currentAccelerator?.name ?: ""
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
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
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { it.copy(inProgress = inProgress) }
  }

  fun setIsResettingSession(isResettingSession: Boolean) {
    _uiState.update { it.copy(isResettingSession = isResettingSession) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { it.copy(preparing = preparing) }
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

  /**
   * Saves the current chat session to the data store.
   *
   * Extracts the first text message to use as the title, converts message models to protos, and
   * updates the persistent storage.
   *
   * @param sessionId Unique identifier for the session.
   * @param messages List of messages to save.
   * @param originalModel The model active when the session was created.
   * @param taskId The task associated with this session.
   */
  fun saveSession(
    sessionId: String,
    messages: List<ChatMessage>,
    originalModel: String,
    taskId: String,
    context: Context? = null,
  ) {
    val messagesSnapshot = messages.toList()
    viewModelScope.launch(Dispatchers.IO) {
      val protoMessages = ChatMessageMapper.serializeMessages(messagesSnapshot, sessionId, context)
      llmSessionManager.saveSessionHistory(
        sessionId = sessionId,
        messages = protoMessages,
        originalModel = originalModel,
        taskId = taskId,
      )
    }
  }

  /**
   * Deletes a chat session from persistent storage by its ID.
   *
   * @param sessionId The ID of the session to delete.
   */
  fun deleteSession(sessionId: String, context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) { llmSessionManager.deleteSession(sessionId) }
  }

  /** Clears all saved chat sessions from persistent storage. */
  fun clearAllSessions(context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) { llmSessionManager.clearAllSessions() }
  }
}
