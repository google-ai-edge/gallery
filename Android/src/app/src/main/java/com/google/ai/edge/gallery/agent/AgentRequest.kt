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

/**
 * Represents an incoming request or command dispatched into the Agent Loop.
 *
 * @property query The task objective or instruction prompt for this agent turn.
 * @property attachments Optional multimedia files added to this request turn.
 * @property metadata Contextual key-value attributes.
 */
data class AgentRequest(
  val query: String,
  val attachments: List<Attachment> = emptyList(),
  val metadata: Map<String, Any> = emptyMap(),
) {
  companion object {
    const val LITERTLM_EXTRA_CONTEXT = "litertlm_extra_context"
    const val SESSION_ID = "sessionId"
  }
}
