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

package com.google.ai.edge.gallery.ui.translation

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "AGTranslationTask"
private const val LITERT_WARMUP_PROMPT = "OK"
private const val LITERT_WARMUP_TIMEOUT_MS = 8_000L

class TranslationTask
@Inject
constructor(
  private val translationUserDataStore: DataStore<UserData>,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_TRANSLATION,
      label = "Translation",
      category = Category.LLM,
      icon = Icons.Outlined.Translate,
      models = mutableListOf(),
      description = "Translate text between languages using on-device large language models",
      shortDescription = "Translate text between languages",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/translation/TranslationTaskModule.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_translation,
      defaultSystemPrompt = buildTranslationSystemPrompt(TranslationLanguage.SPANISH),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val ttsInitializationJob =
      coroutineScope.launch(Dispatchers.IO) {
        try {
          val selectedTtsModel =
            TranslationTtsModel.fromStoredValue(
              translationUserDataStore.data.first().translationTtsModel
            )
          TranslationTtsEngineStore.initializeIfInstalled(context, selectedTtsModel)
        } catch (exception: CancellationException) {
          throw exception
        } catch (exception: Exception) {
          Log.w(TAG, "Translation TTS initialization warmup failed", exception)
        }
      }
    val completeInitialization: (String) -> Unit = { error ->
      coroutineScope.launch {
        ttsInitializationJob.join()
        onDone(error)
      }
    }

    coroutineScope.launch(Dispatchers.Default) {
      executor.initialize(
        context = context,
        config =
          AgentRuntimeConfig(
            model = model,
            taskId = task.id,
            supportImage = false,
            supportAudio = false,
            systemInstruction = systemInstruction?.toString(),
          ),
        onDone = { error ->
          if (
            !ProjectConfig.enableTranslationLlmWarmup ||
              model.runtimeType == RuntimeType.AICORE ||
              model.instance == null
          ) {
            completeInitialization(error)
          } else {
            warmUpLiteRtModel(
              model = model,
              systemInstruction = systemInstruction,
              coroutineScope = coroutineScope,
              initializationError = error,
              onDone = completeInitialization,
            )
          }
        },
      )
    }
  }

  private fun warmUpLiteRtModel(
    model: Model,
    systemInstruction: Contents?,
    coroutineScope: CoroutineScope,
    initializationError: String,
    onDone: (String) -> Unit,
  ) {
    val completed = AtomicBoolean(false)
    var timeoutJob: Job? = null

    fun completeWarmup(error: String? = null) {
      if (!completed.compareAndSet(false, true)) return
      timeoutJob?.cancel()
      if (error != null) {
        Log.w(TAG, "LiteRT LM warmup failed for '${model.name}': $error")
      }
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = systemInstruction,
      )
      Log.d(TAG, "LiteRT LM warmup finished for '${model.name}'")
      onDone(initializationError)
    }

    timeoutJob =
      coroutineScope.launch {
        delay(LITERT_WARMUP_TIMEOUT_MS)
        model.runtimeHelper.stopResponse(model)
        completeWarmup("Timed out")
      }

    Log.d(TAG, "Starting hidden LiteRT LM warmup for '${model.name}'")
    try {
      LlmChatModelHelper.warmUp(
        model = model,
        input = LITERT_WARMUP_PROMPT,
        onDone = { completeWarmup() },
        onError = { error -> completeWarmup(error) },
      )
    } catch (exception: Exception) {
      completeWarmup(exception.message ?: "Unknown error")
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
    val viewModel: TranslationViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadTargetLanguage(task) }
    TranslationScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object TranslationTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    translationUserDataStore: DataStore<UserData>,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return TranslationTask(translationUserDataStore, executor)
  }
}
