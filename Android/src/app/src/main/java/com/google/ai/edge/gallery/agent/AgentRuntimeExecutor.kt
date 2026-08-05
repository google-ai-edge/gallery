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
import kotlinx.coroutines.flow.Flow

/**
 * Main entry point for executing the continuous Agent ReAct loop.
 *
 * The [AgentRuntimeExecutor] coordinates the lifecycle of agent execution, including model
 * initialization, task execution with streaming or synchronous outputs, session interruption, and
 * resource cleanup. It manages context assembly, system prompt injection, LiteRT-LM model
 * inference, tool execution, and event streaming.
 *
 * ### Lifecycle
 * 1. **Initialization:** Call [initialize] with the target [Context] and [AgentRuntimeConfig] to
 *    configure the model engine, prompt context, system instructions, and tool dispatchers prior to
 *    execution.
 * 2. **Execution:** Use [executeStream] for real-time reactive event streaming (tokens, loop
 *    state), or [execute] for a suspending call that collects the stream and returns the final
 *    [AgentResponse].
 * 3. **Interruption:** Call [interrupt] at any time to halt ongoing inference or tool execution.
 * 4. **Cleanup:** Call [cleanUp] when the executor or session is no longer needed to release model
 *    weights and system resources.
 *
 * **Thread Safety:** All implementations of [AgentRuntimeExecutor] MUST be thread-safe. Methods
 * such as [initialize], [execute], [executeStream], [interrupt], and [cleanUp] may be invoked from
 * different threads or coroutine dispatchers concurrently, and implementations must protect
 * internal state against concurrent access and race conditions.
 */
interface AgentRuntimeExecutor {
  /**
   * Initializes the underlying model engine, tool dispatcher, and prompt context for an agent
   * session.
   *
   * Must be called before invoking [execute] or [executeStream]. This sets up the target model,
   * configures tool execution contexts, and initializes the LLM engine with system instructions and
   * decoding parameters.
   *
   * @param context the Android application or component [Context] used for loading model assets and
   *   native runtimes
   * @param config the [AgentRuntimeConfig] containing model details, task ID, tool channels,
   *   decoding settings, and system instructions
   * @param onDone callback invoked upon initialization completion, receiving an empty string on
   *   success or an error message description on failure
   */
  suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  )

  /**
   * Initiates or resumes the ReAct loop for a given session, streaming lifecycle events and tokens.
   *
   * Returns a cold [Flow] that emits [AgentEvent]s as loop progresses.
   *
   * Flow collection can be cancelled to abort downstream processing.
   *
   * @param context the [AgentExecutionContext] containing metadata and operational state for this
   *   execution turn
   * @param request the [AgentRequest] containing the task objective, optional attachments, and
   *   request metadata
   * @return a [Flow] emitting continuous [AgentEvent]s describing the agent turn lifecycle and
   *   generated outputs
   */
  fun executeStream(context: AgentExecutionContext, request: AgentRequest): Flow<AgentEvent>

  /**
   * Synchronously executes the ReAct loop until objective completion, returning the terminal
   * response.
   *
   * This is a suspending convenience wrapper over [executeStream] that collects all streamed events
   * until the loop terminates or encounters an error, returning a consolidated [AgentResponse].
   *
   * @param context the [AgentExecutionContext] containing metadata and operational state for this
   *   execution turn
   * @param request the [AgentRequest] containing the task objective, optional attachments, and
   *   request metadata
   * @return an [AgentResponse] containing the aggregated output string and an execution success
   *   indicator
   */
  suspend fun execute(context: AgentExecutionContext, request: AgentRequest): AgentResponse

  /**
   * Actively interrupts an ongoing agent execution run, halting active tool executions and model
   * inference.
   *
   * Calling this method signals the underlying model engine to stop response generation and cancels
   * any active jobs. Safe to call from any thread while [execute] or [executeStream] is actively
   * running.
   */
  fun interrupt()

  /**
   * Releases resources associated with the initialized model engine and execution context.
   *
   * Should be invoked when the executor is being destroyed or reset to ensure model weights, native
   * buffers, and context handles are freed from memory.
   *
   * @param onDone callback invoked once resource teardown is completed
   */
  fun cleanUp(onDone: () -> Unit = {})
}
