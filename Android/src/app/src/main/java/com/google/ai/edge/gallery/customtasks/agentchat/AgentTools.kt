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

import android.content.Context
import com.google.ai.edge.gallery.tools.CallJsSkillResultImage
import com.google.ai.edge.gallery.tools.CallJsSkillResultWebview
import com.google.ai.edge.gallery.tools.LoadSkillTool
import com.google.ai.edge.gallery.tools.RunIntentTool
import com.google.ai.edge.gallery.tools.RunJsTool
import com.google.ai.edge.gallery.tools.RunMcpTool
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.ToolsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking

interface AgentTools : ToolsProvider {
  var context: Context
  var skillManagerViewModel: SkillManagerViewModel
  var mcpManagerViewModel: McpManagerViewModel
  var taskId: String
  val receiveActionChannel: ReceiveChannel<ToolAction>
  val sendActionChannel: SendChannel<ToolAction>
  var resultImageToShow: CallJsSkillResultImage?
  var resultWebviewToShow: CallJsSkillResultWebview?

  fun sendToolAction(action: ToolAction)
}

open class AgentToolsImpl : AgentTools {
  override lateinit var context: Context
  override lateinit var skillManagerViewModel: SkillManagerViewModel
  override lateinit var mcpManagerViewModel: McpManagerViewModel
  override lateinit var taskId: String

  private val _actionChannel = Channel<ToolAction>(Channel.UNLIMITED)
  override val receiveActionChannel: ReceiveChannel<ToolAction> = _actionChannel
  override val sendActionChannel: SendChannel<ToolAction> = _actionChannel

  private val activeTools = mutableListOf<ToolDefinition>()

  val loadSkillTool by lazy { LoadSkillTool(skillsProvider = skillManagerViewModel) }

  val runMcpTool by lazy {
    RunMcpTool(
      mcpServersProvider = mcpManagerViewModel,
      skillsProvider = skillManagerViewModel,
      taskId = taskId,
    )
  }

  val runJsTool by lazy {
    RunJsTool(
      skillsProvider = skillManagerViewModel,
      dataStoreRepository = skillManagerViewModel.dataStoreRepository,
    )
  }

  val runIntentTool by lazy {
    RunIntentTool(context = context, skillsProvider = skillManagerViewModel)
  }

  override fun getAvailableTools(): List<ToolDefinition> {
    return listOf(loadSkillTool, runMcpTool, runJsTool, runIntentTool) + activeTools
  }

  override fun registerTool(tool: ToolDefinition) {
    if (!activeTools.contains(tool)) {
      activeTools.add(tool)
    }
  }

  override fun unregisterTool(tool: ToolDefinition) {
    activeTools.remove(tool)
  }

  override var resultImageToShow: CallJsSkillResultImage?
    get() = runJsTool.resultImageToShow
    set(value) {
      runJsTool.resultImageToShow = value
    }

  override var resultWebviewToShow: CallJsSkillResultWebview?
    get() = runJsTool.resultWebviewToShow
    set(value) {
      runJsTool.resultWebviewToShow = value
    }

  override fun sendToolAction(action: ToolAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }
}
