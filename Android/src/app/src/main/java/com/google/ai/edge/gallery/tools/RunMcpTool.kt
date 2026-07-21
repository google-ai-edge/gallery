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

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import android.os.Bundle
import android.util.Log
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.convertStringToJsonObject
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.mcp.McpServersProvider
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGRunMcpTool"

class RunMcpTool(
  private val mcpServersProvider: McpServersProvider,
  private val skillsProvider: SkillsProvider,
  private val taskId: String,
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /** Runs MCP tool */
  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    Log.d(TAG, "Run MCP tool:\n- name: $toolName\n- input: $input")
    return runBlocking(Dispatchers.IO) {
      val serverState =
        mcpServersProvider.mcpServers.find { serverState ->
          serverState.mcpServer.toolsList.any { it.name == toolName }
        }

      if (serverState == null) {
        Log.w(TAG, "MCP server or tool not found for: $toolName")
        logMcpExecution(success = false, errorType = "tool_not_found")
        return@runBlocking guardMissingEntityWithSkillFallback(name = toolName, type = "Tool")
      }

      val client = serverState.client
      if (client == null) {
        logMcpExecution(success = false, errorType = "client_not_initialized")
        return@runBlocking mapOf("error" to "Client not initialized", "status" to "failed")
      }

      // Check if the MCP tool requires user permission. If not always allowed,
      // send an action to ask for permission and wait for the result.
      val mcpTool = serverState.mcpServer.toolsList.find { it.name == toolName }
      val isAlwaysAllow = mcpTool?.alwaysAllow ?: false

      if (!isAlwaysAllow) {
        val permissionAction = AskMcpToolCallPermissionAction(toolName = toolName, argument = input)
        executionContext?.actionChannel?.send(permissionAction)
        val permissionResult = permissionAction.result.await()
        if (permissionResult == PermissionResult.DENY) {
          executionContext
            ?.actionChannel
            ?.send(
              SkillProgressToolAction(
                label = "Permission denied for MCP tool \"$toolName\"",
                inProgress = false,
              )
            )
          logMcpExecution(success = false, errorType = "permission_denied")
          return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
        }
      }

      try {
        executionContext
          ?.actionChannel
          ?.send(
            SkillProgressToolAction(
              label = "Calling MCP tool \"$toolName\"",
              inProgress = true,
              addItemTitle = "Call MCP tool: \"$toolName\"",
              addItemDescription = "- Input: $input",
            )
          )
        val result =
        client.callTool(
          request = CallToolRequest(
            CallToolRequestParams(
              name = toolName,
              arguments = kotlinx.serialization.json.Json.parseToJsonElement(input).jsonObject
            )
          )
        )

        if (result == null) {
          Log.d(TAG, "Tool execution returned null result")
          executionContext
            ?.actionChannel
            ?.send(
              SkillProgressToolAction(
                label = "Failed to call MCP tool \"$toolName\"",
                inProgress = false,
              )
            )
          logMcpExecution(success = false, errorType = "null_result")
          return@runBlocking mapOf("error" to "Null result", "status" to "failed")
        }

        if (result.isError == true) {
          val errorText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          Log.e(TAG, "MCP tool \"$toolName\" failed: $errorText")
          executionContext
            ?.actionChannel
            ?.send(
              SkillProgressToolAction(
                label = "Failed to call MCP tool \"$toolName\"",
                addItemTitle = "Call MCP tool \"$toolName\" failed",
                addItemDescription = errorText,
                inProgress = false,
              )
            )
          logMcpExecution(success = false, errorType = "tool_error")
          return@runBlocking mapOf("error" to errorText, "status" to "failed")
        } else {
          val successText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          Log.d(TAG, "MCP tool \"$toolName\" succeeded:\n$successText")
          executionContext
            ?.actionChannel
            ?.send(
              SkillProgressToolAction(
                label = "Succeeded calling MCP tool \"$toolName\"",
                inProgress = true,
                addItemTitle = "Call MCP tool \"$toolName\" succeeded",
                addItemDescription = successText,
              )
            )
          logMcpExecution(success = true, errorType = "")
          return@runBlocking mapOf("result" to successText, "status" to "succeeded")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error calling MCP tool", e)
        executionContext
          ?.actionChannel
          ?.send(
            SkillProgressToolAction(
              label = "Error calling MCP tool \"$toolName\"",
              inProgress = false,
              addItemTitle = "Call MCP tool \"$toolName\" failed",
              addItemDescription = e.message ?: "Unknown error",
            )
          )
        logMcpExecution(success = false, errorType = "exception")
        return@runBlocking mapOf("error" to (e.message ?: "Unknown error"), "status" to "failed")
      }
    }
  }

  /**
   * Guards against missing entities (tools or intents) by checking if they exist as skills. Returns
   * a failure response with a specific hint to the model to try running it as a skill if it is
   * found in the allowed skills list. This helps guide the model when it gets confused and tries to
   * call a skill as a tool or intent.
   */
  private suspend fun guardMissingEntityWithSkillFallback(
    name: String,
    type: String,
  ): Map<String, String> {
    val isSkill = skillsProvider.loadSkill(name) != null
    val error = if (isSkill) "$type not found. Try to run it as a skill" else "Tool not found"
    return mapOf("error" to error, "status" to "failed")
  }

  private fun logMcpExecution(success: Boolean, errorType: String) {
    Log.d(
      TAG,
      "Analytics: mcp_execution, capability_name=$taskId, success=$success, error_type=$errorType",
    )
    firebaseAnalytics?.logEvent(
      GalleryEvent.MCP_EXECUTION.id,
      Bundle().apply {
        putString("capability_name", taskId)
        putBoolean("success", success)
        if (errorType.isNotEmpty()) {
          putString("error_type", errorType)
        }
      },
    )
  }
}
