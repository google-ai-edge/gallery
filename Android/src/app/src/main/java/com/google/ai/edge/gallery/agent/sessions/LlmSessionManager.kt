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

import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider

/**
 * Orchestration manager for LLM sessions in AI Edge Gallery.
 *
 * Coordinates session lifecycle, chat history persistence, LLM instance configuration, inference
 * execution, and feedback linkage across models and tasks.
 */
interface LlmSessionManager {
  /**
   * Creates a new LLM session, initializes the underlying LLM instance, and persists a placeholder
   * session record.
   *
   * @param model The model to associate with the new session.
   * @param taskId The task ID for the session.
   * @param supportImage Whether image input is enabled for this session.
   * @param supportAudio Whether audio input is enabled for this session.
   * @param systemInstruction Optional system instruction for the LLM.
   * @param tools Optional list of tool providers available to the LLM.
   * @return The newly generated unique session ID.
   */
  suspend fun createNewSession(
    model: Model,
    taskId: String,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = emptyList(),
  ): String

  /**
   * Loads an existing session by ID, including its history, and ensures the LLM instance is
   * initialized and configured.
   *
   * @param sessionId The ID of the session to load.
   * @param model The model to associate with the loaded session.
   * @param taskId Optional task ID associated with the session.
   * @param supportImage Whether image input is enabled for this session.
   * @param supportAudio Whether audio input is enabled for this session.
   * @param defaultSystemPrompt Optional fallback default system prompt if session has none.
   * @return The restored list of chat messages for the session.
   */
  suspend fun loadSession(
    sessionId: String,
    model: Model,
    taskId: String? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    defaultSystemPrompt: String? = null,
  ): List<ChatMessageProto>

  /**
   * Lists metadata for all persisted chat sessions.
   *
   * @param taskId Optional task ID filter. If null, returns sessions across all tasks.
   * @return The list of persisted chat session metadata, ordered by recency.
   */
  suspend fun listSessions(taskId: String? = null): List<ChatSessionProto>

  /**
   * Deletes a session and its history from persistence.
   *
   * @param sessionId The unique ID of the session to delete.
   */
  suspend fun deleteSession(sessionId: String)

  /** Clears all saved chat sessions from persistent storage and cleans up cached resources. */
  suspend fun clearAllSessions()

  /**
   * Saves/updates the entire message history for a given session.
   *
   * @param sessionId The session identifier.
   * @param messages The complete list of messages in protobuf format to persist.
   * @param originalModel Optional name of the model associated with the session.
   * @param taskId Optional task ID associated with the session.
   */
  suspend fun saveSessionHistory(
    sessionId: String,
    messages: List<ChatMessageProto>,
    originalModel: String? = null,
    taskId: String? = null,
  )

  /**
   * Runs inference for the given session.
   *
   * @param sessionId The session identifier.
   * @param model The model to run inference on.
   * @param input The text query input.
   * @param resultListener Callback receiving partial tokens, completion status, and thinking
   *   channel output.
   * @param cleanUpListener Callback invoked when inference cleanup finishes.
   * @param onError Callback invoked upon an error during inference.
   * @param images Optional input images.
   * @param audioClips Optional input audio clips.
   * @param extraContext Optional key-value parameters for inference.
   */
  suspend fun generateResponse(
    sessionId: String,
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener = {},
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = emptyList(),
    audioClips: List<ByteArray> = emptyList(),
    extraContext: Map<String, String>? = null,
  )

  /**
   * Stops any ongoing inference for the given session.
   *
   * @param sessionId The session identifier whose active inference should be cancelled.
   * @param model Optional model to stop directly if session configuration is not found.
   */
  fun stopResponse(sessionId: String, model: Model? = null)

  /**
   * Updates the configuration of an existing session (e.g., system prompt, tools) and resets the
   * underlying LLM conversation state with the new config and existing history.
   *
   * @param sessionId The session identifier.
   * @param model The model to reconfigure.
   * @param taskId The task ID context.
   * @param supportImage Whether image input is enabled for this session.
   * @param supportAudio Whether audio input is enabled for this session.
   * @param systemInstruction The updated system prompt contents.
   * @param enableConversationConstrainedDecoding Whether conversation constrained decoding is
   *   enabled.
   * @param initialMessages Messages used to seed or reinitialize conversation history upon reset.
   *   If null, existing history from persistent storage will be used.
   */
  suspend fun updateSessionConfiguration(
    sessionId: String,
    model: Model,
    taskId: String,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = emptyList(),
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message>? = null,
  )

  /**
   * Links a feedback submission ID to a session for tracking. This unify the chat history and
   * feedback history such feedback no longer need to maintain it's own version of the chat history.
   *
   * @param sessionId The session identifier.
   * @param feedbackId Unique identifier for the feedback submission.
   * @param messageIndex Optional index of the message receiving feedback.
   */
  suspend fun linkFeedbackToSession(
    sessionId: String,
    feedbackId: String,
    messageIndex: Int? = null,
  )
}
