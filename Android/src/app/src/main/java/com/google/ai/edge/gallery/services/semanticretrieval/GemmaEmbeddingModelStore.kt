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

package com.google.ai.edge.gallery.services.semanticretrieval

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.HardwareUtils
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages retrieval, resolution, and setup of the local Gemma Embedding model
 * (`embedding-gemma-v2.litertlm`) for semantic retrieval in Android.
 */
object GemmaEmbeddingModelStore {
  private const val TAG = "GemmaEmbeddingModelStore"

  /** Default database name for vector records. */
  const val DEFAULT_DATABASE_NAME = "semantic_retrieval_gemma.db"

  /** Default embedding dimension for Gemma models. */
  const val DEFAULT_EMBEDDING_DIMENSION = 768

  /** Cache of embedders keyed by model path and accelerator. */
  private val embedderCache = ConcurrentHashMap<String, OnDeviceEmbedder>()

  /** Cache of active [SemanticRetrievalService] instances keyed by model path. */
  private val serviceCache = ConcurrentHashMap<String, MutableSet<SemanticRetrievalService>>()

  /** Returns the directory containing the model files for the given [modelPath]. */
  fun getModelDirectory(modelPath: String): File {
    val file = File(modelPath)
    return if (file.isDirectory) file else (file.parentFile ?: file)
  }

  /** Returns the directory containing the model files for the given [model]. */
  fun getModelDirectory(context: Context, model: Model): File {
    return getModelDirectory(model.getPath(context))
  }

  /**
   * Resolves the absolute database path inside the model folder for the given [modelPath] and
   * [databaseName]. If a legacy database exists in Android's default databases directory and no
   * database exists yet in the model directory, migrates the legacy database to the model
   * directory.
   */
  fun getDatabasePath(modelPath: String, databaseName: String, context: Context? = null): String {
    if (File(databaseName).isAbsolute) {
      return databaseName
    }
    val modelDir = getModelDirectory(modelPath)
    if (!modelDir.exists()) {
      modelDir.mkdirs()
    }
    val modelFile = File(modelPath)
    val targetFileName =
      if (modelDir.name == "imports" && !modelFile.isDirectory && modelFile.name.isNotEmpty()) {
        "${modelFile.name}_$databaseName"
      } else {
        databaseName
      }
    val targetFile = File(modelDir, targetFileName)

    // Migrate legacy database if it exists in standard databases dir and not in model dir yet.
    if (context != null && !targetFile.exists()) {
      try {
        val legacyDb = context.getDatabasePath(databaseName)
        if (legacyDb.exists()) {
          Log.i(
            TAG,
            "Migrating legacy database from ${legacyDb.absolutePath} to ${targetFile.absolutePath}",
          )
          legacyDb.copyTo(targetFile, overwrite = true)
          val legacyWal = File("${legacyDb.absolutePath}-wal")
          if (legacyWal.exists()) {
            legacyWal.copyTo(File("${targetFile.absolutePath}-wal"), overwrite = true)
            legacyWal.delete()
          }
          val legacyShm = File("${legacyDb.absolutePath}-shm")
          if (legacyShm.exists()) {
            legacyShm.copyTo(File("${targetFile.absolutePath}-shm"), overwrite = true)
            legacyShm.delete()
          }
          legacyDb.delete()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to migrate legacy database: ${e.message}")
      }
    }

    return targetFile.absolutePath
  }

  /** Resolves the absolute database path inside the model folder for the given [model]. */
  fun getDatabasePath(context: Context, model: Model, databaseName: String): String {
    return getDatabasePath(model.getPath(context), databaseName, context)
  }

  /** Registers an active [SemanticRetrievalService] for the given [modelPath]. */
  fun registerService(modelPath: String, service: SemanticRetrievalService) {
    serviceCache.computeIfAbsent(modelPath) { ConcurrentHashMap.newKeySet() }.add(service)
  }

  /** Unregisters an active [SemanticRetrievalService]. */
  fun unregisterService(modelPath: String, service: SemanticRetrievalService) {
    serviceCache[modelPath]?.remove(service)
  }

  /**
   * Closes and clears all active [SemanticRetrievalService] instances for the given [modelPath].
   */
  fun closeServices(modelPath: String) {
    val prefix = "$modelPath/"
    val colonPrefix = "$modelPath:"
    serviceCache.keys
      .filter { it == modelPath || it.startsWith(prefix) || it.startsWith(colonPrefix) }
      .forEach { key ->
        serviceCache.remove(key)?.forEach { service ->
          try {
            service.close()
          } catch (e: Exception) {
            Log.w(TAG, "Error closing SemanticRetrievalService: ${e.message}")
          }
        }
      }
  }

  /** Returns a shared [OnDeviceEmbedder] for the given [modelPath] and [accelerator]. */
  fun getOrCreateEmbedder(
    modelPath: String,
    accelerator: Accelerator = Accelerator.GPU,
    context: Context? = null,
    maxInputSequenceLength: Int? = null,
    visionTokenBudget: Int? = null,
    visionAccelerator: Accelerator? = null,
    audioAccelerator: Accelerator? = null,
  ): OnDeviceEmbedder {
    val cacheKey =
      "$modelPath:${accelerator.label}:$maxInputSequenceLength:$visionTokenBudget:${visionAccelerator?.label}:${audioAccelerator?.label}"
    return embedderCache.computeIfAbsent(cacheKey) {
      UniversalOnDeviceEmbedder(
        modelPath = modelPath,
        accelerator = accelerator,
        context = context,
        maxInputSequenceLength = maxInputSequenceLength,
        visionTokenBudget = visionTokenBudget,
        visionAccelerator = visionAccelerator,
        audioAccelerator = audioAccelerator,
      )
    }
  }

  /** Removes and closes all cached [OnDeviceEmbedder] instances matching the given [modelPath]. */
  fun removeEmbedders(modelPath: String): List<OnDeviceEmbedder> {
    val prefix = "$modelPath:"
    val matchingKeys =
      embedderCache.keys().asSequence().filter { it == modelPath || it.startsWith(prefix) }.toList()
    return matchingKeys.mapNotNull { key ->
      embedderCache.remove(key)?.also { embedder ->
        try {
          embedder.close()
        } catch (e: Exception) {
          Log.w(TAG, "Error closing embedder: ${e.message}")
        }
      }
    }
  }

  /** Removes and returns a cached [OnDeviceEmbedder] matching the given [modelPath]. */
  fun removeEmbedder(modelPath: String): OnDeviceEmbedder? {
    return removeEmbedders(modelPath).firstOrNull()
  }

  /** Clears and closes all cached services and embedders. */
  fun clear() {
    for (services in serviceCache.values) {
      for (service in services) {
        try {
          service.close()
        } catch (e: Exception) {
          Log.w(TAG, "Error closing SemanticRetrievalService: ${e.message}")
        }
      }
    }
    serviceCache.clear()
    for (embedder in embedderCache.values) {
      try {
        embedder.close()
      } catch (e: Exception) {
        Log.w(TAG, "Error closing embedder: ${e.message}")
      }
    }
    embedderCache.clear()
  }

  /**
   * Returns the default accelerator for Instant Photo Search: [Accelerator.TPU] on Pixel 11,
   * [Accelerator.CPU] on Exynos chips or Mali GPUs (where long vision compute kernels stall EGL
   * RenderThread fences), and [Accelerator.GPU] on other chips.
   */
  fun getDefaultAccelerator(
    model: Model? = null,
    isOldMali: Boolean = HardwareUtils.isOldMaliGpu(),
  ): Accelerator {
    val accelerator = model?.backendSpec?.defaultAccelerator ?: Accelerator.GPU
    return if (accelerator == Accelerator.GPU && isOldMali) {
      Accelerator.CPU
    } else {
      accelerator
    }
  }

  /**
   * Creates a [SemanticRetrievalService] instance initialized with [UniversalOnDeviceEmbedder] with
   * the vector database placed in the model directory.
   */
  fun makeService(
    context: Context,
    model: Model,
    databaseName: String = DEFAULT_DATABASE_NAME,
    maxInputSequenceLength: Int? = null,
    visionTokenBudget: Int? = null,
    visionAccelerator: Accelerator? = null,
    audioAccelerator: Accelerator? = null,
  ): SemanticRetrievalService {
    val modelPath = model.getPath(context)
    val accelerator = getDefaultAccelerator(model)
    val effectiveVisionAccelerator = visionAccelerator ?: model.backendSpec.visionAccelerator
    val effectiveAudioAccelerator = audioAccelerator ?: model.backendSpec.audioAccelerator
    val embedder =
      getOrCreateEmbedder(
        modelPath = modelPath,
        accelerator = accelerator,
        context = context,
        maxInputSequenceLength = maxInputSequenceLength,
        visionTokenBudget = visionTokenBudget,
        visionAccelerator = effectiveVisionAccelerator,
        audioAccelerator = effectiveAudioAccelerator,
      )
    val resolvedDbPath = getDatabasePath(context, model, databaseName)
    val service =
      SemanticRetrievalService(
        databaseName = resolvedDbPath,
        context = context,
        customEmbedder = embedder,
      )
    registerService(modelPath, service)
    return service
  }
}
