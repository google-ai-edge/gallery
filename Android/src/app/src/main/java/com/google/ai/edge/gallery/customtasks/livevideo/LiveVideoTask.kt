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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object LiveVideoGazeHolder {
  @Volatile var gemmaGaze: GemmaGazeInterpreter? = null

  fun close() {
    gemmaGaze?.close()
    gemmaGaze = null
  }
}

class LiveVideoTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = "llm_live_video",
      label = "Live Video",
      category = CategoryInfo(id = "live_video", label = "Live Video"),
      icon = Icons.Outlined.Videocam,
      description =
        "Real-time video understanding powered by GemmaGaze + Gemma 4. " +
          "Point your camera at anything and ask questions, get captions, " +
          "translate text, or use smart camera features.",
      models = mutableListOf(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      try {
        // Initialize Gemma 4 LLM via standard Gallery runtime
        model.runtimeHelper.initialize(
          context = context,
          model = model,
          taskId = task.id,
          supportImage = true,
          supportAudio = false,
          onDone = { status ->
            val isError =
              status.lowercase().contains("failed") || status.lowercase().contains("error")
            if (isError) {
              onDone(status)
              return@initialize
            }

            try {
              val gemmaGaze = GemmaGazeInterpreter(context)
              gemmaGaze.loadFromAssets(context)
              LiveVideoGazeHolder.gemmaGaze = gemmaGaze
              Log.d("LiveVideoTask", "GemmaGaze initialized, isLoaded=${gemmaGaze.isLoaded()}")
            } catch (e: Exception) {
              Log.w("LiveVideoTask", "GemmaGaze init failed, continuing without gaze tracking", e)
            }
            onDone(status)
          },
          coroutineScope = coroutineScope,
          systemInstruction = systemInstruction,
        )
      } catch (e: Exception) {
        onDone(e.message ?: "Failed to initialize Live Video")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LiveVideoGazeHolder.close()
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    LiveVideoScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}
