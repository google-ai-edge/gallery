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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

////////////////////////////////////////////////////////////////////////////////////////////////////
// Shared base for multi-turn LLM chat tasks.

/**
 * Shared base [CustomTask] for multi-turn LLM chat tasks ([LlmChatTask] and [LlmTestTask]).
 *
 * Encapsulates model initialization, cleanup, and [LlmChatScreen] composition so concrete chat
 * tasks share core behavior without concrete-to-concrete inheritance.
 */
abstract class BaseLlmChatTask(
  context: Context,
  private val executor: AgentRuntimeExecutor,
  taskId: String,
  labelRes: Int,
  descriptionRes: Int,
  shortDescriptionRes: Int = descriptionRes,
  private val emptyStateTitleRes: Int = labelRes,
  private val emptyStateContentRes: Int = descriptionRes,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = taskId,
      label = context.getString(labelRes),
      category = Category.LLM,
      icon = Icons.Outlined.Forum,
      models = mutableListOf(),
      description = context.getString(descriptionRes),
      shortDescription = context.getString(shortDescriptionRes),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = model.supportImage,
          supportAudio = model.supportAudio,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmChatViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsStateWithLifecycle()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmChatScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      taskId = task.id,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      showImagePicker = true,
      showAudioPicker = true,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
      emptyStateComposable = { model ->
        Box(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier =
              Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              stringResource(emptyStateTitleRes),
              style = emptyStateTitle,
              modifier = Modifier.semantics { heading() },
            )
            Text(
              stringResource(emptyStateContentRes),
              style = emptyStateContent,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            val multimodalRes =
              when {
                model.supportImage && model.supportAudio -> {
                  if (model.isAiCore) {
                    R.string.aichat_emptystate_support_image_aicore_audio
                  } else {
                    R.string.aichat_emptystate_support_image_audio
                  }
                }
                model.supportImage -> {
                  if (model.isAiCore) {
                    R.string.aichat_emptystate_support_image_aicore
                  } else {
                    R.string.aichat_emptystate_support_image
                  }
                }
                model.supportAudio -> R.string.aichat_emptystate_support_audio
                else -> null
              }

            if (multimodalRes != null) {
              Text(
                stringResource(multimodalRes),
                style = emptyStateContent,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      },
    )
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// AI Chat.

class LlmChatTask
@Inject
constructor(@ApplicationContext context: Context, @AiChatExecutor executor: AgentRuntimeExecutor) :
  BaseLlmChatTask(
    context = context,
    executor = executor,
    taskId = BuiltInTaskId.LLM_CHAT,
    labelRes = R.string.task_label_ai_chat,
    descriptionRes = R.string.task_desc_ai_chat,
    shortDescriptionRes = R.string.task_short_desc_ai_chat,
    emptyStateTitleRes = R.string.aichat_emptystate_title,
    emptyStateContentRes = R.string.aichat_emptystate_content,
  )

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmChatTask(context, executor)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask image.

class LlmAskImageTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_IMAGE,
      label = context.getString(R.string.task_label_ask_image),
      category = Category.LLM,
      icon = Icons.Outlined.Mms,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_ask_image),
      shortDescription = context.getString(R.string.task_short_desc_ask_image),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = true,
          supportAudio = false,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskImageViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsStateWithLifecycle()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskImageScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskImageModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskImageTask(context, executor)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask audio.

class LlmAskAudioTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_AUDIO,
      label = context.getString(R.string.task_label_audio_scribe),
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_audio_scribe),
      shortDescription = context.getString(R.string.task_short_desc_audio_scribe),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = false,
          supportAudio = true,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskAudioViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsStateWithLifecycle()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskAudioScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskAudioModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskAudioTask(context, executor)
  }
}
