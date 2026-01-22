/*
 * Copyright 2025 Google LLC
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
package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

private const val SYSTEM_PROMPT =
  """You are an assistant helping the user play a game about gardening.

The environment is a 3x3 grid of garden plots. The plots are numbered 1 through 9.

**Garden Plot Layout**:

- Row 1: Plots 1, 2, 3 (top row)
- Row 2: Plots 4, 5, 6 (middle row)
- Row 3: Plots 7, 8, 9 (bottom row)

Help the user plant seeds, water plots, and harvest flowers.

There are 4 kinds of seeds you can plant:

1. sunflower
2. daisy
3. rose
4. special (edge gallery, special, secret)

Plot Array: For each action, identify all individual plot numbers (1-9) or implied plots (e.g., 'top row' -> 1, 2, 3) and collect them into the `plots` list.

Tips:

- ""top row"" has plots 1, 2, 3.
- ""middle row"" has plots 4, 5, 6.
- ""bottom row"" has plots 7, 8, 9.
- ""left column"" has plots 1, 4, 7.
- ""middle column"" has plots 2, 5, 8.
- ""right column"" has plots 3, 6, 9.
"""

/** A custom task that demonstrates how to use FunctionGemma to play a simple gardening game. */
class TinyGardenTask @Inject constructor() : CustomTask {
  private val _updateChannel = Channel<TinyGardenCommand>(Channel.BUFFERED)
  private val commandFlow = _updateChannel.receiveAsFlow()
  private val tools =
    listOf(
      TinyGardenTools(
        onFunctionCalled = {
          val unused = _updateChannel.trySend(it)
        }
      )
    )

  override val task =
    Task(
      id = BuiltInTaskId.LLM_TINY_GARDEN,
      label = "Tiny Garden",
      description =
        "Use natural language to plant, water, and harvest in this fully offline mini-game.\n\nNote: This is powered by the experimental FunctionGemma model optimized for latency. Due to its compact size (270M), it works well on simple instructions but responses may vary to more complex interactions.",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/tinygarden",
      category = Category.LLM,
      icon = Icons.Outlined.LocalFlorist,
      agentNameRes = R.string.chat_agent_agent_name,
      models = mutableListOf(),
      handleModelConfigChangesInTask = true,
      experimental = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    clearQueue()
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = Contents.of(getTinyGardenSystemPrompt()),
      tools = tools,
      enableConversationConstrainedDecoding = true,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    clearQueue()
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    TinyGardenScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      tools = tools,
      bottomPadding = customTaskData.bottomPadding,
      commandFlow = commandFlow,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
      setTopBarVisible = customTaskData.setTopBarVisible,
    )
  }

  private fun clearQueue() {
    while (_updateChannel.tryReceive().isSuccess) {}
  }
}

fun getTinyGardenSystemPrompt(
  prevSeed: String = "",
  prevPlots: String = "",
  prevAction: String = "",
): String {
  val parts = mutableListOf(SYSTEM_PROMPT)
  if (prevSeed.isNotEmpty() || prevPlots.isNotEmpty() || prevAction.isNotEmpty()) {
    parts.add("Here is the info about user's last action:")
  }
  if (prevSeed.isNotEmpty()) {
    parts.add("- seed: $prevSeed")
  }
  if (prevPlots.isNotEmpty()) {
    parts.add("- plots: $prevPlots")
  }
  if (prevAction.isNotEmpty()) {
    parts.add("- action: $prevAction")
  }
  return parts.joinToString(separator = "\n")
}
