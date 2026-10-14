/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.tools.CallJsSkillResultImage
import com.google.ai.edge.gallery.tools.CallJsSkillResultWebview
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

open class FakeEvalAgentTools : AgentTools, ToolSet {
  override lateinit var context: Context

  override lateinit var skillsProvider: SkillsProvider
  override lateinit var dataStoreRepository: DataStoreRepository

  private var _mcpManagerViewModel: McpManagerViewModel? = null
  override var mcpManagerViewModel: McpManagerViewModel
    get() {
      if (_mcpManagerViewModel == null) {
        _mcpManagerViewModel = createDummyMcpManagerViewModel()
      }
      return _mcpManagerViewModel!!
    }
    set(value) {
      _mcpManagerViewModel = value
    }

  override var taskId: String = "eval_task"

  private val _actionChannel = Channel<ToolAction>(Channel.UNLIMITED)
  override val receiveActionChannel: ReceiveChannel<ToolAction> = _actionChannel
  override val sendActionChannel: SendChannel<ToolAction> = _actionChannel
  override var resultImageToShow: CallJsSkillResultImage? = null
  override var resultWebviewToShow: CallJsSkillResultWebview? = null

  // Pre-seeded results from Python request
  var runMcpToolResult: Map<String, String> = mapOf("result" to "runMcpTool success")
  var runJsResult: Map<String, Any> = mapOf("result" to "runJs success")
  var runIntentResult: Map<String, String> = mapOf("result" to "runIntent success")

  override fun getAvailableTools(): List<ToolDefinition> = emptyList()

  override fun registerTool(tool: ToolDefinition) {}

  override fun unregisterTool(tool: ToolDefinition) {}

  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    Log.i("FakeEvalAgentTools", "loadSkill called for '$skillName'")
    val skills = SkillManagerViewModel.loadBuiltInSkills(context)
    val skill = skills.find { it.name == skillName }
    if (skill != null) {
      Log.i("FakeEvalAgentTools", "loadSkill: Found skill '$skillName', returning instructions")
      // Return short mock instructions to prevent context length overflow (1024 token limit) during
      // headless evaluation.
      val mockInstructions =
        "You must call the tool 'runJs' with skillName='$skillName', scriptName='index.html', and data='test_data'."
      return mapOf("skill_name" to skill.name, "skill_instructions" to mockInstructions)
    }
    Log.w("FakeEvalAgentTools", "loadSkill: Skill '$skillName' not found")
    return mapOf("error" to "Skill '$skillName' not found on device")
  }

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    Log.i("FakeEvalAgentTools", "runMcpTool called: $toolName, input: $input")
    return runMcpToolResult
  }

  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    Log.i("FakeEvalAgentTools", "runJs called: skill=$skillName, script=$scriptName, data=$data")
    return runJsResult
  }

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
    Log.i("FakeEvalAgentTools", "runIntent called: intent=$intent, params=$parameters")
    return runIntentResult
  }

  override fun sendToolAction(action: ToolAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }

  private fun createDummyMcpManagerViewModel(): McpManagerViewModel {
    val dataStoreClass = Class.forName("androidx.datastore.core.DataStore")

    val dummyMcpServersDataStore =
      java.lang.reflect.Proxy.newProxyInstance(
        dataStoreClass.classLoader,
        arrayOf(dataStoreClass),
      ) { _, method, _ ->
        val returnType = method.returnType
        if (returnType == kotlinx.coroutines.flow.Flow::class.java) {
          flowOf(McpServers.getDefaultInstance())
        } else {
          null
        }
      } as DataStore<McpServers>

    val dummyUserDataDataStore =
      java.lang.reflect.Proxy.newProxyInstance(
        dataStoreClass.classLoader,
        arrayOf(dataStoreClass),
      ) { _, method, _ ->
        val returnType = method.returnType
        if (returnType == kotlinx.coroutines.flow.Flow::class.java) {
          flowOf(UserData.getDefaultInstance())
        } else {
          null
        }
      } as DataStore<UserData>

    return McpManagerViewModel(dummyMcpServersDataStore, dummyUserDataDataStore)
  }
}
