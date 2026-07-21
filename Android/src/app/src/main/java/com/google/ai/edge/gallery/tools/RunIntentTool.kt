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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.intents.IntentAction
import com.google.ai.edge.gallery.intents.IntentHandler
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGRunIntentTool"

class RunIntentTool(private val context: Context, private val skillsProvider: SkillsProvider) :
  ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /** Run an Android intent */
  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      if (IntentAction.from(intent) == null) {
        Log.w(TAG, "Intent not found: '$intent'")
        return@runBlocking guardMissingEntityWithSkillFallback(name = intent, type = "Intent")
      }
      Log.d(TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
      executionContext
        ?.actionChannel
        ?.send(
          SkillProgressToolAction(
            label = "Executing intent \"$intent\"",
            inProgress = true,
            addItemTitle = "Execute intent \"$intent\"",
            addItemDescription = "Parameters: $parameters",
          )
        )
      val res =
        IntentHandler.handleAction(context, intent, parameters) { permission ->
          val permissionAction = RequestPermissionToolAction(permission = permission)
          executionContext?.actionChannel?.send(permissionAction)
          permissionAction.result.await()
        }
      return@runBlocking mapOf("action" to intent, "parameters" to parameters, "result" to res)
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
}
