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
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * A custom task that demonstrates MediaPipe interactive image segmentation task in a scrapbook demo
 * where users extract, edit, and create cutouts.
 */
class ScrapbookTask @Inject constructor() : CustomTask {
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
    val path = model.getPath(context = context)
    val options =
      InteractiveSegmenterOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath(path).build())
        .build()
    val interactiveSegmenter = InteractiveSegmenter.createFromOptions(context, options)
    model.instance = interactiveSegmenter
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val instance = model.instance
    if (instance != null) {
      val interactiveSegmenter = instance as InteractiveSegmenter
      interactiveSegmenter.close()
      model.instance = null
      onDone()
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    ScrapbookScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
      setTopBarVisible = customTaskData.setTopBarVisible,
      setCustomNavigateUpCallback = customTaskData.setCustomNavigateUpCallback,
    )
  }
}
