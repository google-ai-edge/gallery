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

package com.google.ai.edge.gallery.customtasks.serve

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServeTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = "serve",
      label = "Serve",
      category = Category.EXPERIMENTAL,
      description = "Serve the model via an OpenAI-compatible HTTP server.",
      icon = Icons.Filled.Share,
      models = mutableListOf(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (error: String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      try {
        // We initialize the model using LlmChatModelHelper, similar to Chat task
        LlmChatModelHelper.initialize(
          context = context,
          model = model,
          supportImage = false, // Serve usually text-only or multimodal later
          supportAudio = false,
          onDone = onDone
        )
      } catch (e: Exception) {
        onDone(e.message ?: "Unknown error")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
      // Clean up the model
      LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    // We expect data to contain ModelManagerViewModel
    // In GalleryNavGraph, it passes CustomTaskData or CustomTaskDataForBuiltinTask
    // But since this is a custom task, it will be CustomTaskData.

    // However, looking at GalleryNavGraph, it passes CustomTaskData which has modelManagerViewModel.

    ServeTaskScreen()
  }
}
