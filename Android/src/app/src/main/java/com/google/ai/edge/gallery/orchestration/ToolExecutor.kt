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

package com.google.ai.edge.gallery.orchestration

/** Result of executing a single tool. */
data class ToolExecutionResult(
  val success: Boolean,
  val output: String,
  val error: String? = null,
)

/**
 * Abstraction over tool execution.
 *
 * The app layer implements this interface (wrapping AgentTools / SkillManagerViewModel) and injects
 * it into the orchestration module.
 */
interface ToolExecutor {
  /** Execute a tool by name with the given arguments. */
  suspend fun executeTool(toolName: String, args: Map<String, String>): ToolExecutionResult

  /** Return the list of currently available skills. */
  fun getAvailableSkills(): List<SkillSummary>
}
