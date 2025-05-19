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

package com.google.ai.edge.gallery.ui.textclassification

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.LatencyProvider
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "AGTextClassificationModelHelper"

class TextClassificationInferenceResult(
  val categories: List<Category>, override val latencyMs: Float
) : LatencyProvider

// TODO: handle error.

/**
 * Helper object for managing text classification models.
 */
object TextClassificationModelHelper {
  fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
    val modelByteBuffer = readFileToByteBuffer(File(model.getPath(context = context)))
    if (modelByteBuffer != null) {
      val options = TextClassifier.TextClassifierOptions.builder().setBaseOptions(
        BaseOptions.builder().setModelAssetBuffer(modelByteBuffer).build()
      ).build()
      model.instance = TextClassifier.createFromOptions(context, options)
      onDone("")
    }
  }

  fun runInference(model: Model, input: String): TextClassificationInferenceResult {
    val instance = model.instance
    val start = System.currentTimeMillis()
    val classifier: TextClassifier = instance as TextClassifier
    val result = classifier.classify(input)
    val categories = result.classificationResult().classifications().first().categories()
    val latencyMs = (System.currentTimeMillis() - start).toFloat()
    return TextClassificationInferenceResult(categories = categories, latencyMs = latencyMs)
  }

  fun cleanUp(model: Model) {
    if (model.instance == null) {
      return
    }
    val instance = model.instance as TextClassifier

    try {
      instance.close()
    } catch (e: Exception) {
      // ignore
    }

    model.instance = null
    Log.d(TAG, "Clean up done.")
  }


  private fun readFileToByteBuffer(file: File): ByteBuffer? {
    return try {
      val fileInputStream = FileInputStream(file)
      val fileChannel: FileChannel = fileInputStream.channel
      val byteBuffer = ByteBuffer.allocateDirect(fileChannel.size().toInt())
      fileChannel.read(byteBuffer)
      byteBuffer.rewind()
      fileInputStream.close()
      byteBuffer
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  }
}