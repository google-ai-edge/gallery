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
 * Represents the terminal execution result of a synchronous agent invocation.
 *
 * @property output The synthesized response or final report from the agent.
 * @property isSuccessful Whether the agent completed its objective without terminal failure.
 * @property finalMetadata Contextual attributes or artifacts generated during execution.
 */
data class AgentResponse(
  val output: String,
  val isSuccessful: Boolean = true,
  val finalMetadata: Map<String, Any> = emptyMap(),
)
