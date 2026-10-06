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
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService

/** Provides appropriate [SemanticRetrievalService] instances for user photos vs sample album. */
interface SemanticRetrievalServiceProvider {
  val userSemanticRetrievalService: SemanticRetrievalService
  val sampleSemanticRetrievalService: SemanticRetrievalService

  fun service(source: SmartAlbumSource): SemanticRetrievalService =
    when (source) {
      SmartAlbumSource.USER_PHOTOS -> userSemanticRetrievalService
      SmartAlbumSource.SAMPLE_ALBUM -> sampleSemanticRetrievalService
    }
}

class DefaultSemanticRetrievalServiceProvider(
  override val userSemanticRetrievalService: SemanticRetrievalService,
  override val sampleSemanticRetrievalService: SemanticRetrievalService,
  val modelPath: String? = null,
) : SemanticRetrievalServiceProvider, AutoCloseable {

  override fun close() {
    modelPath?.let { path ->
      GemmaEmbeddingModelStore.unregisterService(path, userSemanticRetrievalService)
      GemmaEmbeddingModelStore.unregisterService(path, sampleSemanticRetrievalService)
    }
    try {
      userSemanticRetrievalService.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing userSemanticRetrievalService: ${e.message}", e)
    }
    try {
      sampleSemanticRetrievalService.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing sampleSemanticRetrievalService: ${e.message}", e)
    }
  }

  companion object {
    private const val TAG = "DefaultSemanticRetrievalServiceProvider"

    /** Default database name for user photo vector records. */
    const val USER_DATABASE_NAME = "smart_album_vectors.db"

    /** Default database name for sample demo photo vector records. */
    const val SAMPLE_DATABASE_NAME = "smart_album_samples.db"

    fun create(
      context: Context,
      model: Model,
      maxInputSequenceLength: Int = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
      visionTokenBudget: Int = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
      visionAccelerator: Accelerator? = null,
      audioAccelerator: Accelerator? = null,
    ): DefaultSemanticRetrievalServiceProvider {
      val userService =
        GemmaEmbeddingModelStore.makeService(
          context = context,
          model = model,
          databaseName = USER_DATABASE_NAME,
          maxInputSequenceLength = maxInputSequenceLength,
          visionTokenBudget = visionTokenBudget,
          visionAccelerator = visionAccelerator,
          audioAccelerator = audioAccelerator,
        )
      val sampleService =
        GemmaEmbeddingModelStore.makeService(
          context = context,
          model = model,
          databaseName = SAMPLE_DATABASE_NAME,
          maxInputSequenceLength = maxInputSequenceLength,
          visionTokenBudget = visionTokenBudget,
          visionAccelerator = visionAccelerator,
          audioAccelerator = audioAccelerator,
        )
      return DefaultSemanticRetrievalServiceProvider(
        userSemanticRetrievalService = userService,
        sampleSemanticRetrievalService = sampleService,
        modelPath = model.getPath(context),
      )
    }
  }
}
