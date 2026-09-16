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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Generates a unique session identifier. */
fun generateSessionId(): String = UUID.randomUUID().toString()

/**
 * Data class representing session configuration (model, taskId, system prompt, tools).
 *
 * Note that a session ID does not tie the session to a specific model; a session configuration can
 * be created or updated with different models over the lifetime of the same session ID.
 *
 * @property model The [Model] associated with this session configuration.
 * @property taskId The task ID associated with this session.
 * @property supportImage Whether image input is enabled for this session.
 * @property supportAudio Whether audio input is enabled for this session.
 * @property systemInstruction Optional system instruction prompt prefix.
 * @property tools List of tool providers available to the model.
 */
data class SessionConfig(
  val model: Model,
  val taskId: String,
  val supportImage: Boolean = false,
  val supportAudio: Boolean = false,
  val systemInstruction: Contents? = null,
  val tools: List<ToolProvider> = emptyList(),
)

/**
 * Orchestration manager for LLM sessions in AI Edge Gallery.
 *
 * Coordinates session lifecycle, chat history persistence, LLM instance configuration, inference
 * execution, and feedback linkage across models and tasks.
 *
 * Assumptions:
 * - **Single Active Session**: Only one active LLM session can run at a time in the application. At
 *   any given moment, the system assumes a single session is executing inference or interacting
 *   with the runtime.
 * - **Model Independence**: A session ID identifies a conversation history and does not tie the
 *   session to a specific model. A conversation session can switch between different models across
 *   its lifecycle, and historical sessions can be restored or resumed using a different model than
 *   the one originally used to generate earlier messages.
 */
interface LlmSessionManager {
  /** The unique identifier of the currently active session, or null if uninitialized. */
  var activeSessionId: String?
    get() = null
    set(value) {}

  /** Reactive stream of persisted chat sessions, ordered by recency. */
  val chatSessions: Flow<List<ChatSessionProto>>
    get() = emptyFlow()

  /**
   * Creates a new LLM session, initializes the conversation context on the config's model, sets it
   * as [activeSessionId], and returns the newly generated unique session ID.
   *
   * By definition, a new session starts with an empty conversation history. It does not write an
   * empty placeholder record to persistent storage; persistence occurs when messages are saved via
   * [saveSessionHistory].
   *
   * @param config The [SessionConfig] for the new session.
   * @return The newly generated unique session ID.
   */
  suspend fun createSession(config: SessionConfig): String

  /**
   * Loads an existing session by ID from persistent storage, sets it as [activeSessionId],
   * initializes the conversation context on the config's model with its stored messages, and
   * returns the restored list of chat messages.
   *
   * @param sessionId The ID of the session to load.
   * @param config The [SessionConfig] for the session.
   * @return The restored list of chat messages for the session.
   */
  suspend fun loadSession(sessionId: String, config: SessionConfig): List<ChatMessageProto>

  /**
   * Resets or reconfigures a session's conversation context on the config's model with the provided
   * in-memory [initialMessages] (e.g. prompt update, tool toggles, turn rollback, or recovery after
   * interrupt).
   *
   * @param sessionId The session identifier to reset. Defaults to [activeSessionId] or a new ID.
   * @param config The updated [SessionConfig].
   * @param initialMessages The messages used to seed or reinitialize the conversation context.
   *   Defaults to empty.
   * @param enableConversationConstrainedDecoding Whether conversation constrained decoding is
   *   enabled.
   */
  suspend fun resetSession(
    sessionId: String = activeSessionId ?: generateSessionId(),
    config: SessionConfig,
    initialMessages: List<Message> = emptyList(),
    enableConversationConstrainedDecoding: Boolean = false,
  )

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
   * The session ID identifies the conversation history independently of the model. The optional
   * [originalModel] records the model currently or originally associated with this snapshot for
   * display or metadata purposes, but does not constrain the session to that model.
   *
   * @param sessionId The session identifier.
   * @param messages The complete list of messages in protobuf format to persist.
   * @param originalModel Optional name of the model associated with the session snapshot.
   * @param taskId Optional task ID associated with the session.
   */
  suspend fun saveSessionHistory(
    sessionId: String,
    messages: List<ChatMessageProto>,
    originalModel: String? = null,
    taskId: String? = null,
  )

  /**
   * Runs inference for the given session on the specified [model].
   *
   * Assumes only one session and inference operation can run at a time in the application.
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
   * Stops any ongoing inference for the given session on the specified [model].
   *
   * @param sessionId The session identifier whose active inference should be cancelled.
   * @param model The model to stop.
   */
  fun stopResponse(sessionId: String, model: Model)

  /**
   * Links a feedback submission ID to a session for tracking. This unifies chat history and
   * feedback history so feedback no longer needs to maintain its own version of the chat history.
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
