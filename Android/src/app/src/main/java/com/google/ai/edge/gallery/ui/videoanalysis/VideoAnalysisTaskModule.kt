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

package com.google.ai.edge.gallery.ui.videoanalysis

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class VideoAnalysisTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.VIDEO_ANALYSIS,
      label = "Video Summary",
      category = Category.LLM,
      icon = Icons.Default.Videocam,
      models = mutableListOf(),
      description = "Capture and analyze video frames to understand scenes and objects using on-device vision language models",
      docUrl = "https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/videoanalysis/VideoAnalysisTaskModule.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    LlmChatModelHelper.initialize(context = context, task = task, model = model, onDone = onDone)
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
    VideoAnalysisScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object VideoAnalysisTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return VideoAnalysisTask()
  }
}
