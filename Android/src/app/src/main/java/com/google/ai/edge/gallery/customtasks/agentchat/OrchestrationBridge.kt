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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.orchestration.LlmInferenceProvider
import com.google.ai.edge.gallery.orchestration.SkillSummary
import com.google.ai.edge.gallery.orchestration.ToolExecutionResult
import com.google.ai.edge.gallery.orchestration.ToolExecutor
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase

/**
 * App-layer implementation of [LlmInferenceProvider].
 *
 * Wraps [LlmChatViewModelBase.generateInternalResponse] to provide LLM access to the orchestration
 * module without exposing ViewModel internals.
 */
class LlmInferenceProviderImpl(
  private val viewModel: LlmChatViewModelBase,
  private val modelProvider: () -> Model,
) : LlmInferenceProvider {

  override suspend fun generateResponse(prompt: String): String {
    android.util.Log.d("AGOrchBridge", "generateResponse called, prompt length=${prompt.length}")
    android.util.Log.d("AGOrchBridge", "prompt preview: ${prompt.take(200)}")
    val result = viewModel.generatePlanningResponse(modelProvider(), prompt)
    android.util.Log.d("AGOrchBridge", "generateResponse result length=${result.length}")
    android.util.Log.d("AGOrchBridge", "result preview: ${result.take(500)}")
    return result
  }

  override fun cancel() {
    viewModel.stopResponse(modelProvider())
  }
}

/**
 * App-layer implementation of [ToolExecutor].
 *
 * Wraps [AgentTools] and [SkillManagerViewModel] to provide tool execution to the orchestration
 * module.
 */
class ToolExecutorImpl(
  private val agentTools: AgentTools,
  private val skillManagerViewModel: SkillManagerViewModel,
) : ToolExecutor {

  override suspend fun executeTool(
    toolName: String,
    args: Map<String, String>,
  ): ToolExecutionResult {
    return try {
      val result =
        when (toolName) {
          "loadSkill" -> {
            val skillName = args["skillName"] ?: return ToolExecutionResult(
              success = false,
              output = "",
              error = "Missing skillName argument",
            )
            val map = agentTools.loadSkill(skillName)
            val instructions = map["skill_instructions"] ?: ""
            ToolExecutionResult(
              success = instructions != "Skill not found",
              output = instructions,
              error = if (instructions == "Skill not found") "Skill '$skillName' not found" else null,
            )
          }
          "runJs" -> {
            val skillName = args["skillName"] ?: return ToolExecutionResult(
              success = false,
              output = "",
              error = "Missing skillName argument",
            )
            val scriptName = args["scriptName"] ?: "index.html"
            val data = args["data"] ?: "{}"
            val map = agentTools.runJs(skillName, scriptName, data)
            val status = map["status"] as? String
            val output = (map["result"] as? String) ?: ""
            val error = map["error"] as? String
            ToolExecutionResult(
              success = status == "succeeded",
              output = output,
              error = error,
            )
          }
          "runIntent" -> {
            val intent = args["intent"] ?: return ToolExecutionResult(
              success = false,
              output = "",
              error = "Missing intent argument",
            )
            val parameters = args["parameters"] ?: "{}"
            val map = agentTools.runIntent(intent, parameters)
            val result = map["result"]
            ToolExecutionResult(
              success = result == "succeeded",
              output = result ?: "",
              error = if (result != "succeeded") "Intent failed" else null,
            )
          }
          else ->
            ToolExecutionResult(
              success = false,
              output = "",
              error = "Unknown tool: $toolName",
            )
        }
      result
    } catch (e: Exception) {
      ToolExecutionResult(
        success = false,
        output = "",
        error = e.message ?: "Tool execution failed",
      )
    }
  }

  override fun getAvailableSkills(): List<SkillSummary> {
    val skills = skillManagerViewModel.getSelectedSkills().map { skill ->
      SkillSummary(name = skill.name, description = skill.description, instructions = skill.instructions)
    }
    android.util.Log.d("AGOrchBridge", "getAvailableSkills: ${skills.size} skills: ${skills.map { it.name }}")
    return skills
  }
}
