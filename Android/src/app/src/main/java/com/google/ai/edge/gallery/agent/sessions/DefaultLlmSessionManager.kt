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
import com.google.ai.edge.gallery.data.Config
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * @param contextCompactor Optional primary [ContextCompactor] used to compact the conversation
 *   context when it approaches the model's token limit.
 * @param ioDispatcher Coroutine dispatcher for background IO operations.
 * @param fallbackContextCompactor Optional [ContextCompactor] used when [contextCompactor] does not
 *   need to compact (to let it evaluate its own threshold) or fails to compact (forcing it).
 */
@Singleton
class DefaultLlmSessionManager
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val chatSessionRepository: ChatSessionRepository,
  private val contextCompactor: ContextCompactor? = null,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
  private val fallbackContextCompactor: ContextCompactor? = null,
) : LlmSessionManager {

  private val feedbackLinks = ConcurrentHashMap<String, MutableList<SessionFeedbackLink>>()
  private val sessionConfigs = ConcurrentHashMap<String, SessionConfig>()
  private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
  private val activeSessionIdRef = AtomicReference<String?>(generateSessionId())

  private fun sessionMutex(sessionId: String): Mutex =
    sessionMutexes.getOrPut(sessionId) { Mutex() }

  override var activeSessionId: String?
    get() = activeSessionIdRef.get()
    set(value) {
      activeSessionIdRef.set(value)
    }

  override val chatSessions: Flow<List<ChatSessionProto>> = chatSessionRepository.chatSessions

  private suspend fun ensureModelInitialized(model: Model, operation: String) {
    try {
      model.awaitInitialization()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Model initialization await failed for $operation: ${e.message}", e)
      throw e
    }
  }

  override suspend fun createSession(config: SessionConfig): String =
    withContext(ioDispatcher) {
      val sessionId = generateSessionId()
      sessionMutex(sessionId).withLock {
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
            enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
            initialMessages = emptyList(),
          )
        } catch (e: Exception) {
          Log.e(TAG, "Error resetting conversation for new session: ${e.message}", e)
          throw e
        }
        sessionConfigs[sessionId] = config
        sessionId
      }
    }

  override suspend fun loadSession(
    sessionId: String,
    config: SessionConfig,
  ): List<ChatMessageProto> =
    withContext(ioDispatcher) {
      sessionMutex(sessionId).withLock {
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
            enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
            initialMessages = litertMessages,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Error resetting conversation on session load: ${e.message}", e)
          throw e
        }

        sessionConfigs[sessionId] = config

        history
      }
    }

  override suspend fun resetSession(
    sessionId: String,
    config: SessionConfig,
    initialMessages: List<Message>,
    enableConversationConstrainedDecoding: Boolean,
  ): Unit =
    withContext(ioDispatcher) {
      sessionMutex(sessionId).withLock {
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

        sessionConfigs[sessionId] =
          config.copy(enableConversationConstrainedDecoding = enableConversationConstrainedDecoding)
      }
    }

  override fun setSessionConfig(sessionId: String, config: SessionConfig) {
    activeSessionIdRef.set(sessionId)
    sessionConfigs[sessionId] = config
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
      sessionConfigs.remove(sessionId)
      sessionMutexes.remove(sessionId)
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
      sessionConfigs.clear()
      sessionMutexes.clear()
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
        originalModel?.ifEmpty { null }
          ?: sessionConfigs[sessionId]?.model?.name?.ifEmpty { null }
          ?: existing?.originalModel?.ifEmpty { null }
          ?: ""
      val resolvedTaskId =
        taskId?.ifEmpty { null }
          ?: sessionConfigs[sessionId]?.taskId?.ifEmpty { null }
          ?: existing?.taskId?.ifEmpty { null }
          ?: ""

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

  override suspend fun cacheSessionImages(sessionId: String, bitmaps: List<Bitmap>): List<String> =
    withContext(ioDispatcher) {
      val now = System.currentTimeMillis()
      buildList {
        bitmaps.forEachIndexed { index, bitmap ->
          val fileName = "img_${sessionId}_${now}_$index.png"
          val file = File(context.cacheDir, fileName)
          try {
            FileOutputStream(file).use { fos ->
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            add(file.absolutePath)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to cache session image to ${file.absolutePath}: ${e.message}", e)
          }
        }
      }
    }

  override suspend fun cacheSessionAudio(sessionId: String, audioData: ByteArray): String? =
    withContext(ioDispatcher) {
      val now = System.currentTimeMillis()
      val fileName = "audio_${sessionId}_$now.pcm"
      val file = File(context.cacheDir, fileName)
      try {
        FileOutputStream(file).use { fos -> fos.write(audioData) }
        file.absolutePath
      } catch (e: Exception) {
        Log.e(TAG, "Failed to cache session audio to ${file.absolutePath}: ${e.message}", e)
        null
      }
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
    extraContext: Map<String, String>,
  ) {
    if (model.instance == null) {
      try {
        model.awaitInitialization()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Model initialization await failed: ${e.message}", e)
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

  private data class InternalCompactionResult(
    val result: CompactionResult,
    val outcome: ContextCompactionOutcome? = null,
  )

  override suspend fun compactContextIfNeeded(
    sessionId: String,
    model: Model,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
  ): CompactionResult =
    compactContextInternal(
        sessionId = sessionId,
        model = model,
        onCompactionStart = onCompactionStart,
        force = force,
        source = ContextCompactionSource.TOKEN_LIMIT,
      )
      .result

  override suspend fun compactContextWithOutcome(
    sessionId: String,
    model: Model,
    onCompactionStart: (() -> Unit)?,
    onCompactionFailed: (() -> Unit)?,
    force: Boolean,
    source: ContextCompactionSource,
  ): ContextCompactionOutcome? {
    val internalResult =
      compactContextInternal(
        sessionId = sessionId,
        model = model,
        onCompactionStart = onCompactionStart,
        force = force,
        source = source,
      )
    if (internalResult.result == CompactionResult.FAILED) {
      onCompactionFailed?.invoke()
    }
    return internalResult.outcome
  }

  private suspend fun compactContextInternal(
    sessionId: String,
    model: Model,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
    source: ContextCompactionSource,
  ): InternalCompactionResult =
    withContext(ioDispatcher) {
      if (!Config.isContextCompactEnabled() || model.isAiCore) {
        return@withContext InternalCompactionResult(CompactionResult.NOT_NEEDED)
      }
      if (contextCompactor == null && fallbackContextCompactor == null) {
        return@withContext InternalCompactionResult(CompactionResult.NOT_NEEDED)
      }
      // The primary compactor may start compacting and then fail over to the fallback compactor,
      // so make sure the caller is only notified once.
      val compactionStartNotified = AtomicBoolean(false)
      val notifyStartOnce = {
        if (compactionStartNotified.compareAndSet(false, true)) {
          onCompactionStart?.invoke()
        }
      }
      sessionMutex(sessionId).withLock {
        val config = sessionConfigs[sessionId]
        val primaryReport =
          runCompactor(
            compactor = contextCompactor,
            sessionId = sessionId,
            model = model,
            config = config,
            onCompactionStart = notifyStartOnce,
            force = force,
          )
        when (primaryReport.result) {
          CompactionResult.COMPACTED -> {
            InternalCompactionResult(
              result = CompactionResult.COMPACTED,
              outcome =
                ContextCompactionOutcome(
                  algorithm =
                    contextCompactor?.algorithm ?: ContextCompactionAlgorithm.SUMMARIZATION,
                  source = source,
                  sizes = compactionSizes(sessionId, primaryReport.compactedCharCount),
                ),
            )
          }
          // Let the fallback compactor evaluate its own threshold, e.g. by estimating the token
          // usage of a session that has not been prefilled yet.
          CompactionResult.NOT_NEEDED -> {
            val fallbackReport =
              runCompactor(
                compactor = fallbackContextCompactor,
                sessionId = sessionId,
                model = model,
                config = config,
                onCompactionStart = notifyStartOnce,
                force = force,
              )
            if (fallbackReport.result == CompactionResult.COMPACTED) {
              InternalCompactionResult(
                result = CompactionResult.COMPACTED,
                outcome =
                  ContextCompactionOutcome(
                    algorithm =
                      fallbackContextCompactor?.algorithm ?: ContextCompactionAlgorithm.TRUNCATION,
                    source = source,
                    sizes = compactionSizes(sessionId, fallbackReport.compactedCharCount),
                  ),
              )
            } else {
              InternalCompactionResult(result = fallbackReport.result)
            }
          }
          // The primary compactor determined that compaction is needed but failed, so force the
          // fallback compactor to compact even if usage is below the fallback's own threshold.
          CompactionResult.FAILED -> {
            Log.w(TAG, "Primary context compaction failed for session $sessionId; falling back.")
            val fallbackReport =
              runCompactor(
                compactor = fallbackContextCompactor,
                sessionId = sessionId,
                model = model,
                config = config,
                onCompactionStart = notifyStartOnce,
                force = true,
              )
            if (fallbackReport.result == CompactionResult.COMPACTED) {
              InternalCompactionResult(
                result = CompactionResult.COMPACTED,
                outcome =
                  ContextCompactionOutcome(
                    algorithm =
                      fallbackContextCompactor?.algorithm ?: ContextCompactionAlgorithm.TRUNCATION,
                    source = ContextCompactionSource.SUMMARIZATION_FALLBACK,
                    sizes = compactionSizes(sessionId, fallbackReport.compactedCharCount),
                  ),
              )
            } else {
              InternalCompactionResult(result = CompactionResult.FAILED)
            }
          }
        }
      }
    }

  override fun isTokenLimitReached(sessionId: String, model: Model): Boolean {
    if (!Config.isContextCompactEnabled() || model.isAiCore) {
      return false
    }
    val config = sessionConfigs[sessionId]
    return (contextCompactor ?: fallbackContextCompactor)?.isTokenLimitReached(
      sessionId,
      model,
      config,
    ) ?: false
  }

  /**
   * Runs [compactor], treating a missing compactor as [CompactionResult.NOT_NEEDED] and an
   * exception thrown by it as [CompactionResult.FAILED].
   */
  private suspend fun runCompactor(
    compactor: ContextCompactor?,
    sessionId: String,
    model: Model,
    config: SessionConfig?,
    onCompactionStart: () -> Unit,
    force: Boolean,
  ): CompactionReport {
    if (compactor == null) {
      return CompactionReport(CompactionResult.NOT_NEEDED)
    }
    return try {
      compactor.compactContextReportingSize(
        sessionId = sessionId,
        model = model,
        sessionConfig = config,
        onCompactionStart = onCompactionStart,
        force = force,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Context compaction failed for session $sessionId", e)
      CompactionReport(CompactionResult.FAILED)
    }
  }

  /**
   * Returns the [ContextCompactionSizes] of a compaction of [sessionId] whose compactor reported
   * [compactedCharCount].
   *
   * The chat size is the total content length of the persisted text messages that are restored into
   * the conversation when the session is loaded. Returns `null` if the compactor did not report
   * [compactedCharCount], the persisted session cannot be read or is not found, or it has no such
   * text messages, so that the event never reports an empty chat size (e.g. as a ratio
   * denominator).
   */
  private suspend fun compactionSizes(
    sessionId: String,
    compactedCharCount: Int?,
  ): ContextCompactionSizes? {
    if (compactedCharCount == null) {
      return null
    }
    val sessions =
      try {
        chatSessionRepository.getAllChatSessions()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read persisted chat history for session $sessionId", e)
        return null
      }
    val session = sessions.firstOrNull { it.sessionId == sessionId } ?: return null
    val chatCharCount =
      session.messagesList.sumOf { if (it.isSeededTextMessage) it.content.length else 0 }
    if (chatCharCount == 0) {
      return null
    }
    return ContextCompactionSizes(
      chatCharCount = chatCharCount,
      compactedCharCount = compactedCharCount,
    )
  }

  override fun stopResponse(sessionId: String, model: Model) {
    Log.d(TAG, "Stopping response for session $sessionId on model ${model.name}")
    val targetModel = sessionConfigs[sessionId]?.model ?: model
    targetModel.runtimeHelper.stopResponse(targetModel)
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
    if (!proto.isSeededTextMessage) {
      return null
    }
    return if (proto.side == ChatSideProto.CHAT_SIDE_USER) {
      Message.user(proto.content)
    } else {
      Message.model(proto.content)
    }
  }
}
