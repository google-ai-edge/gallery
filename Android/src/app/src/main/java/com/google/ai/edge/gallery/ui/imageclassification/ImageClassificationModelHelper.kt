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

package com.google.ai.edge.gallery.ui.imageclassification

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import com.google.ai.edge.gallery.ui.common.chat.Classification
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.LatencyProvider
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream

private const val TAG = "AGImageClassificationModelHelper"

class ImageClassificationInferenceResult(
  val categories: List<Classification>, override val latencyMs: Float
) : LatencyProvider

//TODO: handle error.

object ImageClassificationModelHelper {
  fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
    val useGpu = model.getBooleanConfigValue(key = ConfigKey.USE_GPU)
    TfLiteGpu.isGpuDelegateAvailable(context).continueWith { gpuTask ->
      val optionsBuilder = TfLiteInitializationOptions.builder()
      if (gpuTask.result) {
        optionsBuilder.setEnableGpuDelegateSupport(true)
      }
      val task = TfLite.initialize(
        context,
        optionsBuilder.build()
      )
      task.addOnSuccessListener {
        val interpreterOption =
          InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        if (useGpu) {
          interpreterOption.addDelegateFactory(GpuDelegateFactory())
        }
        val interpreter = InterpreterApi.create(
          File(model.getPath(context = context)), interpreterOption
        )
        model.instance = interpreter
        onDone("")
      }
    }
  }

  fun cleanUp(model: Model) {
    if (model.instance == null) {
      return
    }
    val instance = model.instance as InterpreterApi
    instance.close()
  }

  fun runInference(
    context: Context,
    model: Model,
    input: Bitmap,
    primaryColor: Color,
  ): ImageClassificationInferenceResult {
    val instance = model.instance
    if (instance == null) {
      Log.d(
        TAG, "Model '${model.name}' has not been initialized"
      )
      return ImageClassificationInferenceResult(categories = listOf(), latencyMs = 0f)
    }

    // Pre-process image.
    val start = System.currentTimeMillis()
    val interpreter = model.instance as InterpreterApi
    val inputShape = interpreter.getInputTensor(0).shape()
    val imageProcessor = ImageProcessor.Builder()
      .add(ResizeOp(inputShape[1], inputShape[2], ResizeOp.ResizeMethod.BILINEAR))
      .add(NormalizeOp(127.5f, 127.5f)) // Normalize pixel values
      .build()
    val tensorImage = TensorImage(DataType.FLOAT32)
    tensorImage.load(input)
    val inputTensorBuffer = imageProcessor.process(tensorImage).tensorBuffer

    // Output buffer
    val outputBuffer =
      TensorBuffer.createFixedSize(interpreter.getOutputTensor(0).shape(), DataType.FLOAT32)

    // Run inference
    interpreter.run(inputTensorBuffer.buffer, outputBuffer.buffer)

    // Post-process result.
    val output = outputBuffer.floatArray
    val labelsFilePath = model.getPath(
      context = context,
      fileName = model.getExtraDataFile(name = "labels")?.downloadFileName ?: "_"
    )
    val labelsFileInputStream = FileInputStream(File(labelsFilePath))
    val labels = FileUtil.loadLabels(labelsFileInputStream)
    labelsFileInputStream.close()
    val topIndices =
      getTopKMaxIndices(output = output, k = model.getIntConfigValue(ConfigKey.MAX_RESULT_COUNT))
    val categories: List<Classification> =
      topIndices.map { i ->
        Classification(
          label = labels[i],
          score = output[i],
          color = primaryColor
        )
      }
    return ImageClassificationInferenceResult(
      categories = categories,
      latencyMs = (System.currentTimeMillis() - start).toFloat()
    )
  }

  private fun getTopKMaxIndices(output: FloatArray, k: Int): List<Int> {
    if (k <= 0 || output.isEmpty()) {
      return emptyList()
    }

    val indexedValues = output.withIndex().toList()
    val sortedIndexedValues =
      indexedValues.sortedByDescending { it.value }
    return sortedIndexedValues.take(k).map { it.index } // Take the top k and extract indices
  }

}
