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

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.channels.SendChannel

/**
 * Execution context injected into [ToolDefinition] before each loop turn. Responsible for
 * publishing real-time status and permission prompts from running tools.
 *
 * @property taskId Identifies the specific Gallery feature or task executing the tool.
 * @property actionChannel Channel used by tools to publish live status events or emit dynamic UI
 *   side-effects.
 */
data class ToolExecutionContext(
  val taskId: String,
  val actionChannel: SendChannel<ToolAction>? = null,
)

/**
 * ToolDefinition interface extending LiteRT-LM's [ToolSet]. It represents a single tool. All
 * implementations annotate concrete tool methods with [@Tool] and parameters with [@ToolParam].
 */
interface ToolDefinition : ToolSet {
  /** Whether the tool is always allowed to run. */
  val alwaysAllow: Boolean
  /** Execution context for the current tool turn. */
  var executionContext: ToolExecutionContext?

  /**
   * Attaches and binds the live mobile runtime execution context to this tool instance for the
   * duration of the current agent turn.
   */
  fun onAttach(context: ToolExecutionContext) {
    this.executionContext = context
  }
}
