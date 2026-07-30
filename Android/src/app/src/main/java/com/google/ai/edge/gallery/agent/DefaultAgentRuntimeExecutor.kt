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
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "AGDefaultAgentRuntimeExecutor"

/**
 * Default implementation of [AgentRuntimeExecutor] orchestrating context assembly, execution
 * context injection, LiteRT-LM inference turns, and event streaming.
 */
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
) : AgentRuntimeExecutor {

  private var model: Model? = null
  private var toolExecutionContext: ToolExecutionContext? = null

  private val activeJobs = ConcurrentHashMap<String, Job>()

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (String) -> Unit,
  ) {
    this.model = config.model

    val systemInstruction = Contents.of(config.systemInstruction ?: "")

    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)
    this.toolExecutionContext = executionContext
    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )

    LlmChatModelHelper.initialize(
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

    toolExecutionContext?.let { execCtx ->
      toolDispatcher.setupExecutionContext(
        tools = toolsProvider.getAvailableTools(),
        context = execCtx,
      )
    }

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
    val curModel =
      this@DefaultAgentRuntimeExecutor.model
        ?: error("Model not initialized in DefaultAgentRuntimeExecutor")
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
            finalResponse.clear()
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
    val curModel = this.model ?: return
    Log.d(TAG, "Interrupting session for model: ${curModel.name}")
    // TODO: we need to reset the conversation in executeStream if user sends a new request.
    curModel.runtimeHelper.stopResponse(curModel)
  }

  override fun cleanUp(onDone: () -> Unit) {
    val curModel = this.model
    if (curModel == null) {
      onDone()
      return
    }
    this.model = null
    this.toolExecutionContext = null
    curModel.runtimeHelper.cleanUp(model = curModel, onDone = onDone)
  }
}
