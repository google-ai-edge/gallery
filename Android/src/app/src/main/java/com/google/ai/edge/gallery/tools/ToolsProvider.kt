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

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool

/** Discovers and provides [ToolDefinition] instances. */
interface ToolsProvider {
  fun getAvailableTools(): List<ToolDefinition>

  /**
   * Registers a new tool definition at runtime (e.g., dynamically connected MCP servers or user
   * skills).
   */
  fun registerTool(tool: ToolDefinition)

  /** Unregisters a tool definition when no longer active. */
  fun unregisterTool(tool: ToolDefinition)

  /** Converts ToolDefinitions into canonical LiteRT-LM [ToolProvider]s. */
  fun getLiteRtToolProviders(): List<ToolProvider> = getAvailableTools().map { tool(it) }
}

/** In-memory implementation of [ToolsProvider]. */
open class RuntimeToolsProvider(initialTools: List<ToolDefinition> = emptyList()) : ToolsProvider {
  private val tools = mutableListOf<ToolDefinition>().apply { addAll(initialTools) }

  override fun getAvailableTools(): List<ToolDefinition> = tools.toList()

  override fun registerTool(tool: ToolDefinition) {
    if (!tools.contains(tool)) {
      tools.add(tool)
    }
  }

  override fun unregisterTool(tool: ToolDefinition) {
    tools.remove(tool)
  }
}
