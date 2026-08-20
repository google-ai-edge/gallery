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

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.channels.SendChannel

/**
 * Configuration options for model runtime initialization and inference decoding behavior.
 *
 * @property model The [Model] instance to be initialized and executed.
 * @property taskId Unique identifier for the active task.
 * @property sessionId Unique identifier for the conversation session.
 * @property actionChannel Optional channel for sending tool invocation events and UI action
 *   updates.
 * @property supportImage Whether image input modality is supported for model prefill.
 * @property supportAudio Whether audio input modality is supported for model prefill.
 * @property enableConversationConstrainedDecoding Whether constrained decoding (e.g., tool call
 *   schema constraints) is active during output generation.
 * @property systemInstruction System instructions formatted as a prompt prefix for the conversation
 *   turn.
 * @property initialMessages Messages used to seed or reinitialize conversation history upon reset.
 */
data class AgentRuntimeConfig(
  val model: Model,
  val taskId: String,
  val sessionId: String = "",
  val actionChannel: SendChannel<ToolAction>? = null,
  val supportImage: Boolean = true,
  val supportAudio: Boolean = true,
  val enableConversationConstrainedDecoding: Boolean = false,
  val systemInstruction: String? = null,
  val initialMessages: List<Message> = listOf(),
)
