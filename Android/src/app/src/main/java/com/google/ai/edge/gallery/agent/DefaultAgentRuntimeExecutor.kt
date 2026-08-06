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

package com.google.ai.edge.gallery.agent

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.tools.ToolDispatcher
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "AGDefaultAgentRuntimeExecutor"

/**
 * Default implementation of [AgentRuntimeExecutor] orchestrating context assembly, execution
 * context injection, LiteRT-LM inference turns, and event streaming.
 *
 * This implementation is thread-safe and lock-free. Internal session state such as the active model
 * and tool execution context is encapsulated within an immutable structure managed by an atomic
 * reference. This allows lifecycle operations ([initialize], [executeStream], [interrupt], and
 * [cleanUp]) to be invoked safely across concurrent threads or coroutines without contention, race
 * conditions, or deadlocks.
 */
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
) : AgentRuntimeExecutor {

  /** Active session state for the current conversation. */
  private data class ActiveSession(val model: Model, val toolExecutionContext: ToolExecutionContext)

  /** Atomic reference to the active session. */
  private val activeSession = AtomicReference<ActiveSession?>(null)

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (String) -> Unit,
  ) {
    val systemInstruction = Contents.of(config.systemInstruction ?: "")
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    activeSession.set(ActiveSession(model = config.model, toolExecutionContext = executionContext))

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )

    config.model.runtimeHelper.initialize(
      context = context,
      model = config.model,
      taskId = config.taskId,
      supportImage = config.supportImage,
      supportAudio = config.supportAudio,
      onDone = onDone,
      systemInstruction = systemInstruction,
      tools = toolsProvider.getLiteRtToolProviders(),
      enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
    )
  }

  private fun ProducerScope<AgentEvent>.emitEvent(event: AgentEvent) {
    trySend(event).onFailure { Log.w(TAG, "Failed to emit event: $event", it) }
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> = callbackFlow {
    emitEvent(AgentEvent.LoopInitiated(request = request))

    val session =
      activeSession.get() ?: error("Model not initialized in DefaultAgentRuntimeExecutor")
    val curModel = session.model

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = session.toolExecutionContext,
    )

    val images = request.attachments.filterIsInstance<Attachment.ImageBitmap>().map { it.bitmap }
    val audioClips =
      request.attachments.filterIsInstance<Attachment.AudioBytes>().map { it.audioBytes }

    val finalResponse = StringBuilder()
    val extraContext =
      (request.metadata[AgentRequest.LITERTLM_EXTRA_CONTEXT] as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
        ?.toMap()
        ?.ifEmpty { null }
    if (curModel.instance == null) {
      try {
        curModel.awaitInitialization()
      } catch (e: Exception) {
        emitEvent(AgentEvent.Error("Model initialization failed: ${e.message}"))
        close()
        return@callbackFlow
      }
    }
    if (curModel.instance == null) {
      emitEvent(AgentEvent.Error("Model not initialized."))
      close()
      return@callbackFlow
    }
    curModel.runtimeHelper.runInference(
      model = curModel,
      input = request.query,
      images = images,
      audioClips = audioClips,
      extraContext = extraContext,
      resultListener = { partialResult, done, partialThinking ->
        if (!partialResult.startsWith("<ctrl")) {
          if (partialResult.isNotEmpty() || !partialThinking.isNullOrEmpty() || done) {
            emitEvent(
              AgentEvent.StreamToken(token = partialResult, thinking = partialThinking, done = done)
            )
          }
          if (partialResult.isNotEmpty()) {
            finalResponse.append(partialResult)
          }
        }
        if (done) {
          emitEvent(AgentEvent.LoopTerminated(finalResponse = finalResponse.toString()))
          close()
        }
      },
      cleanUpListener = {
        // Clean up completed
        emitEvent(AgentEvent.LoopCancelled)
        close()
      },
      onError = { errorMsg ->
        Log.e(TAG, "Error in AgentLoop inference: $errorMsg")
        emitEvent(AgentEvent.Error(errorMessage = errorMsg))
        close()
      },
    )

    awaitClose {
      // Clean up when flow collection is cancelled
    }
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    var finalOutput = ""
    var isSuccess = true

    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> {
          finalOutput = event.finalResponse
        }
        is AgentEvent.Error -> {
          isSuccess = false
          finalOutput = event.errorMessage
        }
        else -> {}
      }
    }
    return AgentResponse(output = finalOutput, isSuccessful = isSuccess)
  }

  override fun interrupt() {
    val curModel = activeSession.get()?.model ?: return
    Log.d(TAG, "Interrupting session for model: ${curModel.name}")
    // TODO: we need to reset the conversation in executeStream if user sends a new request.
    curModel.runtimeHelper.stopResponse(curModel)
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val systemInstruction = Contents.of(config.systemInstruction ?: "")
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    activeSession.set(ActiveSession(model = config.model, toolExecutionContext = executionContext))

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )
    val curModel = config.model
    val maxRetries = 5
    val retryDelayMs = 200L

    for (attempt in 1..maxRetries) {
      try {
        curModel.runtimeHelper.resetConversation(
          model = curModel,
          supportImage = config.supportImage,
          supportAudio = config.supportAudio,
          systemInstruction = systemInstruction,
          tools = toolsProvider.getLiteRtToolProviders(),
          enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
          initialMessages = config.initialMessages,
        )
        return
      } catch (e: Exception) {
        if (attempt == maxRetries) {
          Log.e(
            TAG,
            "Failed to reset session for model: ${curModel.name} after $maxRetries attempts.",
            e,
          )
          return
        }
        Log.d(
          TAG,
          "Failed to reset session for model: ${curModel.name} (attempt $attempt/$maxRetries). Retrying...",
          e,
        )
        delay(retryDelayMs)
      }
    }
  }

  override fun cleanUp(onDone: () -> Unit) {
    val session = activeSession.getAndSet(null)
    if (session == null) {
      onDone()
      return
    }
    session.model.runtimeHelper.cleanUp(model = session.model, onDone = onDone)
  }
}
