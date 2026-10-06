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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class VideoMomentFinderTask @Inject constructor(@ApplicationContext private val context: Context) :
  CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.VIDEO_MOMENT_FINDER,
      label = context.getString(R.string.videomomentfinder_feature_name),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.video_search,
      description = context.getString(R.string.task_desc_video_moment_finder),
      shortDescription = context.getString(R.string.task_short_desc_video_moment_finder),
      models = mutableListOf(),
      useThemeColor = true,
      newFeature = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    // Creates the semantic retrieval service backing frame indexing and search. The service owns
    // the vector database (stored in the model directory) and the shared Gemma embedder.
    //
    // Declared out here so the catch can still clean up, but resolved inside the try: anything
    // that escapes this method skips onDone, which leaves the model stuck initializing with no
    // error ever surfaced to the user.
    var modelPath: String? = null
    try {
      modelPath = model.getPath(context)
      val service =
        GemmaEmbeddingModelStore.makeService(
          context = context,
          model = model,
          databaseName = DATABASE_NAME,
          maxInputSequenceLength = MAX_INPUT_SEQUENCE_LENGTH,
          visionTokenBudget = VISION_TOKEN_BUDGET,
          visionAccelerator = model.backendSpec.visionAccelerator,
          // Defaulted rather than left unset: the embedding bundles constrain their audio encoder
          // to CPU, and an unset audio delegate inherits the main one (e.g. GPU), which the engine
          // rejects while validating backend constraints, failing to create the engine at all.
          audioAccelerator = model.backendSpec.audioAccelerator ?: Accelerator.CPU,
        )
      // Rethrow so the failure reaches onDone below instead of being swallowed into a Toast.
      service.initializeEmbedder(onError = { throw it })
      model.instance = service
      onDone("")
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to initialize Gemma embedder", e)
      logErrorToFirebase(
        GalleryEvent.GENERATE_ACTION,
        "videomomentfinder_model_init_error",
        e.message,
      )
      // Null only when getPath itself failed, in which case nothing can have been registered.
      modelPath?.let { path ->
        GemmaEmbeddingModelStore.closeServices(path)
        val unused = GemmaEmbeddingModelStore.removeEmbedders(path)
      }
      onDone(e.message ?: "Failed to initialize Gemma embedder")
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val modelPath = model.getPath(context)
    GemmaEmbeddingModelStore.closeServices(modelPath)
    val unused = GemmaEmbeddingModelStore.removeEmbedders(modelPath)
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customData = data as CustomTaskData

    VideoMomentFinderScreen(
      modelManagerViewModel = customData.modelManagerViewModel,
      bottomPadding = customData.bottomPadding,
      setTopBarVisible = customData.setTopBarVisible,
      setCustomNavigateUpCallback = customData.setCustomNavigateUpCallback,
    )
  }

  companion object {
    private const val TAG = "AGVideoMomentFinderTask"
    const val MAX_INPUT_SEQUENCE_LENGTH = 1024
    const val VISION_TOKEN_BUDGET = 70

    /** Vector database for indexed video frame windows, stored in the model directory. */
    const val DATABASE_NAME = "video_moment_finder_vectors.db"
  }
}
