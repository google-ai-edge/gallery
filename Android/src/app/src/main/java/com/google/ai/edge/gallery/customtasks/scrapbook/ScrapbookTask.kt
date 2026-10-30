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
package com.google.ai.edge.gallery.customtasks.scrapbook

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.segmentation.InitializedSegmenter
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmenterBackend
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmenterFactory
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AGScrapbookTask"

/**
 * A custom task that demonstrates MediaPipe interactive image segmentation task in a scrapbook demo
 * where users extract, edit, and create cutouts.
 */
class ScrapbookTask @Inject constructor() : CustomTask {
  // The backends that the initialized segmenters run on, keyed by model name.
  private val backends = MutableStateFlow<Map<String, SegmenterBackend>>(emptyMap())

  override val task =
    Task(
      id = BuiltInTaskId.MP_SCRAPBOOK,
      label = "Scrapbook",
      description =
        "Extract cutouts from your pictures using MediaPipe interactive image segmentation task and create your own scrapbook",
      docUrl = "https://ai.google.dev/edge/mediapipe/solutions/vision/interactive_segmenter",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/scrapbook",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.scrapbook_task_icon,
      useThemeColor = true,
      models = mutableListOf(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (error: String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      backends.update { it - model.name }
      try {
        val initializedSegmenter = createInitializedSegmenter(context = context, model = model)
        // Creating the segmenter blocks in native code, which cancellation can't interrupt. Don't
        // publish it if the init was cancelled meanwhile (e.g. the screen was closed): nothing
        // would
        // clean it up.
        if (!isActive) {
          Log.d(TAG, "Initialization of '${model.name}' was cancelled. Closing the segmenter.")
          closeSegmenter(model = model, segmenter = initializedSegmenter.segmenter)
          return@launch
        }
        Log.i(TAG, "Initialized '${model.name}' on ${initializedSegmenter.backend}.")
        backends.update { it + (model.name to initializedSegmenter.backend) }
        model.instance = initializedSegmenter.segmenter
        onDone("")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Show a localized message; the details are in the log.
        Log.e(TAG, "Failed to initialize '${model.name}'", e)
        onDone(context.getString(R.string.scrapbook_model_init_failed, model.name))
      }
    }
  }

  /** Creates a segmenter for [model] on the best runtime available. Call off the main thread. */
  private suspend fun createInitializedSegmenter(
    context: Context,
    model: Model,
  ): InitializedSegmenter {
    val path = model.getPath(context = context)
    return SegmenterFactory.createBundledSegmenter(context = context, modelPath = path)
  }

  /** Closes [segmenter] and releases the runtime resources kept alive for [model]. */
  private fun closeSegmenter(model: Model, segmenter: InteractiveSegmenter) {
    segmenter.close()
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val instance = model.instance
    if (instance != null) {
      closeSegmenter(model = model, segmenter = instance as InteractiveSegmenter)
      backends.update { it - model.name }
      model.instance = null
      onDone()
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    val curBackends by backends.collectAsStateWithLifecycle()
    val modelManagerUiState by
      customTaskData.modelManagerViewModel.uiState.collectAsStateWithLifecycle()
    ScrapbookScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      backend = curBackends[modelManagerUiState.selectedModel.name],
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
      setTopBarVisible = customTaskData.setTopBarVisible,
      setCustomNavigateUpCallback = customTaskData.setCustomNavigateUpCallback,
    )
  }
}
