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

package com.google.ai.edge.gallery.agent.sessions

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val TAG = "AGDefaultLlmSessionMgr"

/**
 * Data class representing feedback submission link for a session.
 *
 * @property feedbackId Unique identifier for the feedback submission.
 * @property messageIndex Optional index of the message receiving feedback.
 */
data class SessionFeedbackLink(val feedbackId: String, val messageIndex: Int? = null)

/**
 * Default implementation of [LlmSessionManager] orchestrating session lifecycle, chat history
 * persistence, model instances, and feedback linkages.
 *
 * @param context The Android application [Context].
 * @param chatSessionRepository Repository for persisting and retrieving user session data.
 * @param ioDispatcher Coroutine dispatcher for background IO operations.
 */
@Singleton
class DefaultLlmSessionManager
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val chatSessionRepository: ChatSessionRepository,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LlmSessionManager {

  private val feedbackLinks = ConcurrentHashMap<String, MutableList<SessionFeedbackLink>>()
  private val activeSessionIdRef = AtomicReference<String?>(generateSessionId())

  override var activeSessionId: String?
    get() = activeSessionIdRef.get()
    set(value) {
      activeSessionIdRef.set(value)
    }

  override val chatSessions: Flow<List<ChatSessionProto>> = chatSessionRepository.chatSessions

  private suspend fun ensureModelInitialized(model: Model, operation: String) {
    try {
      model.awaitInitialization()
    } catch (e: Exception) {
      Log.e(TAG, "Model initialization await failed for $operation: ${e.message}", e)
      throw e
    }
  }

  override suspend fun createSession(config: SessionConfig): String =
    withContext(ioDispatcher) {
      val sessionId = generateSessionId()
      activeSessionIdRef.set(sessionId)
      Log.d(
        TAG,
        "Creating new session $sessionId for model ${config.model.name} and task ${config.taskId}",
      )

      ensureModelInitialized(config.model, "createSession")

      try {
        config.model.runtimeHelper.resetConversation(
          model = config.model,
          supportImage = config.supportImage,
          supportAudio = config.supportAudio,
          systemInstruction = config.systemInstruction,
          tools = config.tools,
          initialMessages = emptyList(),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error resetting conversation for new session: ${e.message}", e)
        throw e
      }

      sessionId
    }

  override suspend fun loadSession(
    sessionId: String,
    config: SessionConfig,
  ): List<ChatMessageProto> =
    withContext(ioDispatcher) {
      activeSessionIdRef.set(sessionId)
      Log.d(TAG, "Loading session $sessionId for model ${config.model.name}")
      val allSessions = chatSessionRepository.getAllChatSessions()
      val session = allSessions.firstOrNull { it.sessionId == sessionId }
      val history = session?.messagesList ?: emptyList()

      val litertMessages = history.mapNotNull { protoToLitertMessage(it) }

      ensureModelInitialized(config.model, "loadSession")

      try {
        config.model.runtimeHelper.resetConversation(
          model = config.model,
          supportImage = config.supportImage,
          supportAudio = config.supportAudio,
          systemInstruction = config.systemInstruction,
          tools = config.tools,
          initialMessages = litertMessages,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error resetting conversation on session load: ${e.message}", e)
        throw e
      }

      history
    }

  override suspend fun resetSession(
    sessionId: String,
    config: SessionConfig,
    initialMessages: List<Message>,
    enableConversationConstrainedDecoding: Boolean,
  ): Unit =
    withContext(ioDispatcher) {
      activeSessionIdRef.set(sessionId)
      Log.d(TAG, "Resetting session $sessionId on model ${config.model.name}")

      ensureModelInitialized(config.model, "resetSession")

      try {
        config.model.runtimeHelper.resetConversation(
          model = config.model,
          supportImage = config.supportImage,
          supportAudio = config.supportAudio,
          systemInstruction = config.systemInstruction,
          tools = config.tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          initialMessages = initialMessages,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error resetting session: ${e.message}", e)
        throw e
      }
    }

  override suspend fun listSessions(taskId: String?): List<ChatSessionProto> =
    withContext(ioDispatcher) {
      val allSessions = chatSessionRepository.getAllChatSessions()
      val filtered =
        if (taskId != null) {
          allSessions.filter { it.taskId == taskId }
        } else {
          allSessions
        }
      filtered.sortedByDescending { it.timestampMs }
    }

  override suspend fun deleteSession(sessionId: String): Unit =
    withContext(ioDispatcher) {
      activeSessionIdRef.compareAndSet(sessionId, generateSessionId())
      Log.d(TAG, "Deleting session $sessionId")
      chatSessionRepository.deleteChatSession(sessionId)
      feedbackLinks.remove(sessionId)

      val files = context.cacheDir.listFiles()
      files?.forEach { file ->
        if (
          file.name.startsWith("img_${sessionId}_") || file.name.startsWith("audio_${sessionId}_")
        ) {
          file.delete()
        }
      }
    }

  override suspend fun clearAllSessions(): Unit =
    withContext(ioDispatcher) {
      activeSessionIdRef.set(generateSessionId())
      Log.d(TAG, "Clearing all chat sessions")
      chatSessionRepository.clearAllChatSessions()
      feedbackLinks.clear()

      val files = context.cacheDir.listFiles()
      files?.forEach { file ->
        if (file.name.startsWith("img_") || file.name.startsWith("audio_")) {
          file.delete()
        }
      }
    }

  override suspend fun saveSessionHistory(
    sessionId: String,
    messages: List<ChatMessageProto>,
    originalModel: String?,
    taskId: String?,
  ): Unit =
    withContext(ioDispatcher) {
      val existingSessions = chatSessionRepository.getAllChatSessions()
      val existing = existingSessions.firstOrNull { it.sessionId == sessionId }
      val resolvedOriginalModel =
        originalModel?.ifEmpty { null } ?: existing?.originalModel?.ifEmpty { null } ?: ""
      val resolvedTaskId = taskId?.ifEmpty { null } ?: existing?.taskId?.ifEmpty { null } ?: ""

      val firstTextMessage =
        messages
          .firstOrNull { it.messageType == "TEXT" && it.side == ChatSideProto.CHAT_SIDE_USER }
          ?.content ?: messages.firstOrNull { it.messageType == "TEXT" }?.content
      val title =
        firstTextMessage?.take(30)?.let { if (it.length == 30) "$it..." else it }
          ?: existing?.title?.ifEmpty { null }
          ?: "New Chat Session"

      val updatedSession =
        ChatSessionProto.newBuilder()
          .setSessionId(sessionId)
          .setTitle(title)
          .setTimestampMs(System.currentTimeMillis())
          .setOriginalModel(resolvedOriginalModel)
          .setTaskId(resolvedTaskId)
          .addAllMessages(messages)
          .build()

      chatSessionRepository.saveChatSession(updatedSession)
    }

  override suspend fun generateResponse(
    sessionId: String,
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    extraContext: Map<String, String>?,
  ) {
    if (model.instance == null) {
      try {
        model.awaitInitialization()
      } catch (e: Exception) {
        Log.w(TAG, "Model initialization await failed: ${e.message}")
        onError("Model initialization failed: ${e.message}")
        return
      }
    }
    if (model.instance == null) {
      onError("Model not initialized.")
      return
    }
    model.runtimeHelper.runInference(
      model = model,
      input = input,
      images = images,
      audioClips = audioClips,
      extraContext = extraContext,
      resultListener = resultListener,
      cleanUpListener = cleanUpListener,
      onError = onError,
    )
  }

  override fun stopResponse(sessionId: String, model: Model) {
    Log.d(TAG, "Stopping response for session $sessionId on model ${model.name}")
    model.runtimeHelper.stopResponse(model)
  }

  override suspend fun linkFeedbackToSession(
    sessionId: String,
    feedbackId: String,
    messageIndex: Int?,
  ): Unit =
    withContext(ioDispatcher) {
      Log.d(TAG, "Linking feedback $feedbackId to session $sessionId at messageIndex $messageIndex")
      val links = feedbackLinks.getOrPut(sessionId) { mutableListOf() }
      synchronized(links) {
        links.add(SessionFeedbackLink(feedbackId = feedbackId, messageIndex = messageIndex))
      }
    }

  private fun protoToLitertMessage(proto: ChatMessageProto): Message? {
    if (proto.messageType == "TEXT") {
      return when (proto.side) {
        ChatSideProto.CHAT_SIDE_USER -> Message.user(proto.content)
        ChatSideProto.CHAT_SIDE_MODEL -> Message.model(proto.content)
        else -> null
      }
    }
    return null
  }
}
