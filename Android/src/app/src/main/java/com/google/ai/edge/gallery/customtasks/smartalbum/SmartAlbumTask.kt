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

package com.google.ai.edge.gallery.customtasks.smartalbum

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.gallery.ui.common.RotationalLoader
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class SmartAlbumTask @Inject constructor(@ApplicationContext private val context: Context) :
  CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.SMART_ALBUM,
      label = context.getString(R.string.smartalbum_welcome_title),
      category = Category.LLM,
      icon = Icons.Outlined.PhotoLibrary,
      description = context.getString(R.string.task_desc_smart_album),
      shortDescription = context.getString(R.string.task_short_desc_smart_album),
      models = mutableListOf(),
      newFeature = true,
      handleModelConfigChangesInTask = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    // Initializes the on-device Gemma embedding model and assigns the embedder to model.instance.
    // The semantic retrieval services in SmartAlbumViewModel consume this embedder via
    // GemmaEmbeddingModelStore.
    try {
      val modelPath = model.getPath(context)
      val accelerator = SmartAlbumViewModel.getDefaultAccelerator(model)
      val embedder =
        GemmaEmbeddingModelStore.getOrCreateEmbedder(
          modelPath = modelPath,
          accelerator = accelerator,
          context = context,
          maxInputSequenceLength = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
          visionTokenBudget = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
          visionAccelerator = model.backendSpec.visionAccelerator,
          audioAccelerator = model.backendSpec.audioAccelerator,
        )
      embedder.initialize()
      model.instance = embedder
      onDone("")
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to initialize Gemma embedding model", e)
      logErrorToFirebase(GalleryEvent.GENERATE_ACTION, "smartalbum_model_init_error", e.message)
      val unused = GemmaEmbeddingModelStore.removeEmbedders(model.getPath(context))
      onDone(e.message ?: "Failed to initialize Gemma embedding model")
    }
  }

  override val keepModelAlive: Boolean = false

  private fun cleanUpModel(context: Context, model: Model) {
    val modelPath = model.getPath(context)
    GemmaEmbeddingModelStore.closeServices(modelPath)
    val unused = GemmaEmbeddingModelStore.removeEmbedders(modelPath)
    val instance = model.instance
    if (instance is AutoCloseable) {
      try {
        instance.close()
      } catch (e: Exception) {
        Log.w(TAG, "Error closing model instance: ${e.message}")
      }
    }
    model.instance = null
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    cleanUpModel(context, model)
    onDone()
  }

  override fun onDeleteModelFn(context: Context, model: Model) {
    cleanUpModel(context, model)
    SmartAlbumIndexingWorker.cancel(context, SmartAlbumSource.USER_PHOTOS)
    SmartAlbumIndexingWorker.cancel(context, SmartAlbumSource.SAMPLE_ALBUM)
    val userDbPath =
      GemmaEmbeddingModelStore.getDatabasePath(
        context,
        model,
        DefaultSemanticRetrievalServiceProvider.USER_DATABASE_NAME,
      )
    val sampleDbPath =
      GemmaEmbeddingModelStore.getDatabasePath(
        context,
        model,
        DefaultSemanticRetrievalServiceProvider.SAMPLE_DATABASE_NAME,
      )
    context.deleteDatabase(userDbPath)
    File(userDbPath).delete()
    File("$userDbPath-wal").delete()
    File("$userDbPath-shm").delete()
    File("$userDbPath-journal").delete()
    context.deleteDatabase(sampleDbPath)
    File(sampleDbPath).delete()
    File("$sampleDbPath-wal").delete()
    File("$sampleDbPath-shm").delete()
    File("$sampleDbPath-journal").delete()
    val rootNormalizedName =
      model.hierarchy.parentModelName?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: model.normalizedName
    val modelStorageDir = getModelStorageDir(context)
    val otherVariantExists =
      modelStorageDir.listFiles()?.any { dir ->
        dir.isDirectory &&
          dir.name != model.normalizedName &&
          (dir.name == rootNormalizedName || dir.name.startsWith("${rootNormalizedName}_")) &&
          dir.walkTopDown().any { it.isFile }
      } == true
    if (!otherVariantExists) {
      try {
        SampleAlbumStorageManager(context, model).deleteSamplePhotos()
      } catch (e: Exception) {
        Log.w(TAG, "Error clearing sample photos on model delete: ${e.message}")
      }
    }
  }

  override fun onDeleteExtraDataFn(context: Context, model: Model) {
    val modelPath = model.getPath(context)
    GemmaEmbeddingModelStore.closeServices(modelPath)
    SmartAlbumIndexingWorker.cancel(context, SmartAlbumSource.SAMPLE_ALBUM)
    val sampleDbPath =
      GemmaEmbeddingModelStore.getDatabasePath(
        context,
        model,
        DefaultSemanticRetrievalServiceProvider.SAMPLE_DATABASE_NAME,
      )
    context.deleteDatabase(sampleDbPath)
    File(sampleDbPath).delete()
    File("$sampleDbPath-wal").delete()
    File("$sampleDbPath-shm").delete()
    File("$sampleDbPath-journal").delete()
    try {
      SampleAlbumStorageManager(context, model).deleteSamplePhotos()
    } catch (e: Exception) {
      Log.w(TAG, "Error clearing sample photos on extra data delete: ${e.message}")
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customData = data as CustomTaskData
    val modelManagerState by customData.modelManagerViewModel.uiState.collectAsStateWithLifecycle()
    val selectedModel =
      task.models.find { it.name == modelManagerState.selectedModel.name }
        ?: task.models.firstOrNull()
        ?: modelManagerState.selectedModel

    val initStatus by selectedModel.initStatusFlow.collectAsStateWithLifecycle()

    LaunchedEffect(selectedModel) {
      if (selectedModel.initStatusFlow.value is Model.InitializationStatus.Idle) {
        customData.modelManagerViewModel.initializeModel(
          context,
          task = task,
          model = selectedModel,
        )
      }
    }

    when (initStatus) {
      is Model.InitializationStatus.Idle,
      is Model.InitializationStatus.Initializing -> {
        SmartAlbumLoadingScreen(bottomPadding = customData.bottomPadding)
      }
      is Model.InitializationStatus.Failed -> {
        Box(modifier = Modifier.fillMaxSize().padding(bottom = customData.bottomPadding))
      }
      is Model.InitializationStatus.Initialized -> {
        val extraDataStatus =
          modelManagerState.extraDataDownloadStatus[selectedModel.name]
            ?: selectedModel.hierarchy.parentModelName?.let {
              modelManagerState.extraDataDownloadStatus[it]
            }
        val viewModel =
          remember(selectedModel.name) {
            try {
              SmartAlbumViewModel.create(context, selectedModel)
            } catch (e: Throwable) {
              Log.e(TAG, "Failed to initialize SmartAlbumViewModel", e)
              selectedModel.markInitializationFailed(e)
              null
            }
          }

        if (viewModel == null) {
          return
        }

        LaunchedEffect(extraDataStatus?.status) { viewModel.refreshSamplePhotos() }

        DisposableEffect(viewModel) { onDispose { viewModel.cleanup() } }

        val searchViewModel =
          remember(viewModel) {
            SmartAlbumSearchViewModel(
              viewModel.photoLibraryService,
              viewModel.semanticRetrievalService,
              viewModel,
            )
          }

        SmartAlbumScreen(
          viewModel = viewModel,
          searchViewModel = searchViewModel,
          bottomPadding = customData.bottomPadding,
          setCustomNavigateUpCallback = customData.setCustomNavigateUpCallback,
          setTopBarVisible = customData.setTopBarVisible,
        )
      }
    }
  }

  companion object {
    private const val TAG = "SmartAlbumTask"
  }
}

/** Loading screen displayed while the embedding model is initializing. */
@Composable
fun SmartAlbumLoadingScreen(
  modifier: Modifier = Modifier,
  bottomPadding: Dp = 0.dp,
  showProgressIndicator: Boolean = true,
) {
  Box(
    modifier = modifier.fillMaxSize().padding(bottom = bottomPadding),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      if (showProgressIndicator) {
        RotationalLoader(size = 64.dp)
      } else {
        Spacer(modifier = Modifier.size(64.dp))
      }
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text = stringResource(R.string.smartalbum_model_initializing),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.smartalbum_please_wait),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
