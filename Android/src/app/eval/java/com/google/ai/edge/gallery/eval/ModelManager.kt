/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.runtimeHelper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class ModelManager(
  private val context: Context,
  private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {

  private val modelCache = ConcurrentHashMap<String, Model>()
  private val initJobs = ConcurrentHashMap<String, CompletableDeferred<Model>>()

  /**
   * Pre-initializes the model in the background. This ensures the model is cached with the correct
   * capabilities (e.g. vision) before the first HTTP request arrives.
   */
  open fun preInitModel(
    modelPath: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    accelerator: String,
  ) {
    Log.i(
      TAG,
      "Pre-initializing model $modelPath (image: $supportImage, audio: $supportAudio, acc: $accelerator)",
    )
    val helper = getHelperForModelName(modelPath)
    if (helper == null) {
      Log.e(TAG, "Failed to get helper for model $modelPath")
      return
    }
    coroutineScope.launch {
      val unused = getOrInitModel(modelPath, helper, supportImage, supportAudio, accelerator)
    }
  }

  suspend fun getOrInitModel(
    modelName: String,
    helper: LlmModelHelper,
    supportImage: Boolean,
    supportAudio: Boolean,
    accelerator: String,
  ): Model? {
    val job: CompletableDeferred<Model>
    var isNew = false

    // Synchronize on modelCache to make the check-and-register atomic.
    // This prevents a race condition where a background pre-initialization
    // finishes and removes itself from initJobs before a concurrent request
    // thread can find it in either modelCache or initJobs.
    synchronized(modelCache) {
      val cachedModel = modelCache[modelName]
      if (cachedModel != null) {
        return cachedModel
      }
      val existingJob = initJobs[modelName]
      if (existingJob != null) {
        job = existingJob
      } else {
        job = CompletableDeferred<Model>()
        initJobs[modelName] = job
        isNew = true
      }
    }

    if (isNew) {
      val model =
        resolveModel(modelName, supportImage, supportAudio, accelerator)
          ?: run {
            synchronized(modelCache) { initJobs.remove(modelName)?.let {} }
            job.completeExceptionally(IllegalArgumentException("Unknown model: $modelName"))
            return null
          }

      Log.i(TAG, "Initializing model: $modelName")

      helper.initialize(
        context = context,
        model = model,
        taskId = "eval_task",
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        coroutineScope = coroutineScope,
        onDone = { status ->
          Log.i(TAG, "Init status for $modelName: $status")
          val isSuccess =
            status == "" || status == "Feature is available" || status == "Download completed"
          val isDownloading = status.startsWith("Downloading")

          if (isSuccess) {
            synchronized(modelCache) {
              modelCache[modelName] = model
              initJobs.remove(modelName)?.let {}
            }
            job.complete(model)
          } else if (!isDownloading) {
            synchronized(modelCache) { initJobs.remove(modelName)?.let {} }
            job.completeExceptionally(RuntimeException("Init failed: $status"))
          }
        },
      )
    }

    return try {
      job.await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize model $modelName", e)
      null
    }
  }

  open fun resolveModel(
    modelName: String,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    accelerator: String = "CPU",
  ): Model? {
    val configValues =
      mapOf(
        ConfigKeys.ACCELERATOR.label to accelerator,
        ConfigKeys.VISION_ACCELERATOR.label to accelerator,
      )
    return when {
      modelName.contains("aicore", ignoreCase = true) -> {
        Model(
            name = modelName,
            runtimeType = RuntimeType.AICORE,
            isLlm = true,
            llmSupportImage = supportImage,
            llmSupportAudio = supportAudio,
          )
          .apply { this.configValues = configValues }
      }
      modelName.startsWith("/") -> {
        Model(
            name = modelName,
            runtimeType = RuntimeType.LITERT_LM,
            localModelFilePathOverride = modelName,
            isLlm = true,
            llmSupportImage = supportImage,
            llmSupportAudio = supportAudio,
          )
          .apply { this.configValues = configValues }
      }
      else -> {
        Model(
            name = modelName,
            runtimeType = RuntimeType.LITERT_LM,
            isLlm = true,
            llmSupportImage = supportImage,
            llmSupportAudio = supportAudio,
          )
          .apply { this.configValues = configValues }
      }
    }
  }

  open fun getHelperForModelName(modelName: String): LlmModelHelper? {
    val model = resolveModel(modelName) ?: return null
    return model.runtimeHelper
  }

  companion object {
    private const val TAG = "ModelManager"
  }
}
