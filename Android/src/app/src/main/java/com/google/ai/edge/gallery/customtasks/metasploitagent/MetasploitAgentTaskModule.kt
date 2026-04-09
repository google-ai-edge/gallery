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

package com.google.ai.edge.gallery.customtasks.metasploitagent

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Metasploit Agent custom task.
 *
 * Gives the on-device LLM a set of tools that talk to the Metasploit RPC API
 * ([MetasploitTools]), so users can control a running `msfrpcd` instance by
 * chatting with the model in natural language.
 *
 * Prerequisites on device (Termux or rooted shell):
 *   gem install msfrpcd   # or use the bundled msfrpcd from metasploit-framework
 *   msfrpcd -P mypassword -U msf -n -f   # start in foreground, no SSL, port 55553
 */
class MetasploitAgentTask @Inject constructor() : CustomTask {
  private val metasploitTools = MetasploitTools()

  override val task: Task =
    Task(
      id = METASPLOIT_AGENT_TASK_ID,
      label = "Metasploit Agent",
      category = Category.LLM,
      icon = Icons.Outlined.Security,
      newFeature = true,
      models = mutableListOf(),
      description =
        "Chat with an AI agent that controls Metasploit Framework via its JSON RPC API. " +
          "The agent can search modules, configure and run exploits, manage sessions, " +
          "and execute arbitrary msfconsole commands.",
      shortDescription = "AI-powered Metasploit assistant",
      defaultSystemPrompt =
        """
        You are an expert Metasploit Framework assistant running on a mobile device.
        You control a Metasploit RPC service through a set of tools.

        WORKFLOW — always follow these steps for every user request:

        1. If not yet connected, call msfConnect with the server details.
           Default: host=localhost, port=55553, username=msf. Ask for the password if unknown.

        2. Use the most appropriate tool for the task:
           - msfConsoleCommand: run any msfconsole command (use, set, run, sessions -l, …)
           - msfSearchModules: find modules by keyword, CVE, or platform
           - msfModuleInfo / msfModuleOptions: inspect a module before running it
           - msfExecuteModule: launch a module as a background job
           - msfListSessions / msfSessionCommand: interact with active shells/meterpreter
           - msfListJobs / msfKillJob: manage background jobs
           - msfStatus: check connection state

        3. Present results clearly. For session output, format commands and their output
           in code blocks. For module info, highlight required options.

        CRITICAL RULES:
        - Only perform actions the user explicitly requests.
        - Before running an exploit, always confirm the target and required options.
        - Never output internal reasoning; reply only with the final result.
        """
          .trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = task.defaultSystemPrompt,
      tools = listOf(tool(metasploitTools)),
      enableConversationConstrainedDecoding = true,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    MetasploitAgentScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      metasploitTools = metasploitTools,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object MetasploitAgentTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return MetasploitAgentTask()
  }
}
