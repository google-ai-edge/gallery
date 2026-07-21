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

import com.google.ai.edge.litertlm.ToolManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Orchestrates tool dispatch lifecycle, permission middleware, and fallbacks. */
interface ToolDispatcher {
  /**
   * Attaches active runtime execution context (channels, permissions, UI listeners) across all
   * active [ToolDefinition]s prior to starting or resuming a conversation.
   */
  fun setupExecutionContext(tools: List<ToolDefinition>, context: ToolExecutionContext)

  /**
   * Dispatches manual tool execution when automaticToolCalling is false or when routing execution
   * requests for non-LiteRT models.
   */
  suspend fun dispatchManualCall(
    toolManager: ToolManager,
    functionName: String,
    arguments: JsonObject,
  ): JsonElement
}

/** Default runtime implementation of [ToolDispatcher]. */
open class RuntimeToolDispatcher : ToolDispatcher {
  override fun setupExecutionContext(tools: List<ToolDefinition>, context: ToolExecutionContext) {
    for (tool in tools) {
      tool.onAttach(context)
    }
  }

  override suspend fun dispatchManualCall(
    toolManager: ToolManager,
    functionName: String,
    arguments: JsonObject,
  ): JsonElement {
    return toolManager.execute(functionName, arguments)
  }
}
