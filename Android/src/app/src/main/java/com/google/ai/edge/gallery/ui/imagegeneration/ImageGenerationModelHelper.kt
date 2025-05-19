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

package com.google.ai.edge.gallery.ui.imagegeneration

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.LatencyProvider
import com.google.ai.edge.gallery.ui.common.cleanUpMediapipeTaskErrorMessage
import kotlin.random.Random

private const val TAG = "AGImageGenerationModelHelper"

class ImageGenerationInferenceResult(
  val bitmap: Bitmap, override val latencyMs: Float
) : LatencyProvider

object ImageGenerationModelHelper {
  fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
    try {
      val options = ImageGenerator.ImageGeneratorOptions.builder()
        .setImageGeneratorModelDirectory(model.getPath(context = context))
        .build()
      model.instance = ImageGenerator.createFromOptions(context, options)
    } catch (e: Exception) {
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  fun cleanUp(model: Model) {
    if (model.instance == null) {
      return
    }
    val instance = model.instance as ImageGenerator
    try {
      instance.close()
    } catch (e: Exception) {
      // ignore
    }
    model.instance = null
    Log.d(TAG, "Clean up done.")
  }

  fun runInference(
    model: Model,
    input: String,
    onStep: (curIteration: Int, totalIterations: Int, ImageGenerationInferenceResult, isLast: Boolean) -> Unit
  ) {
    val start = System.currentTimeMillis()
    val instance = model.instance as ImageGenerator
    val iterations = model.getIntConfigValue(ConfigKey.ITERATIONS)
    instance.setInputs(input, iterations, Random.nextInt())
    for (i in 0..<iterations) {
      val result = ImageGenerationInferenceResult(
        bitmap = BitmapExtractor.extract(
          instance.execute(true)?.generatedImage(),
        ),
        latencyMs = (System.currentTimeMillis() - start).toFloat(),
      )
      onStep(i, iterations, result, i == iterations - 1)
    }
  }
}