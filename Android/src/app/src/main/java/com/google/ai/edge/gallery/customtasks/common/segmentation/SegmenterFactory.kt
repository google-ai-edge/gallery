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
package com.google.ai.edge.gallery.customtasks.common.segmentation

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke

private const val TAG = "AGSegmenterFactory"

/** The side length of the blank image used to verify a new segmenter. */
private const val WARM_UP_IMAGE_SIZE_PX = 64

/**
 * A runtime that the interactive segmenter can run on.
 *
 * @property chipTextResId The text of the chip that tells the user which runtime and accelerator
 *   the segmenter runs on, with the accelerator name as its only argument.
 */
enum class SegmenterRuntime(@StringRes val chipTextResId: Int) {
  /** The LiteRT runtime bundled in the app with MediaPipe. */
  MEDIAPIPE(R.string.scrapbook_backend_chip_mediapipe),
}

/** The runtime and the accelerator that an initialized segmenter runs on. */
data class SegmenterBackend(val runtime: SegmenterRuntime, val accelerator: Accelerator)

/** A segmenter that is ready to run, together with the [backend] it runs on. */
data class InitializedSegmenter(val segmenter: InteractiveSegmenter, val backend: SegmenterBackend)

/** Creates and verifies MediaPipe [InteractiveSegmenter] instances on GPU or CPU. */
object SegmenterFactory {
  /** Returns a [BaseOptions.Builder] that runs the model file at [modelPath] on [delegate]. */
  fun segmenterBaseOptions(modelPath: String, delegate: Delegate): BaseOptions.Builder =
    BaseOptions.builder().setModelAssetPath(modelPath).setDelegate(delegate)

  /**
   * Creates a segmenter for the model file at [modelPath] on the runtime bundled in the app, trying
   * the GPU first and falling back to the CPU if the GPU can't run the model.
   *
   * Throws if the segmenter can't be created on the CPU either. Call off the main thread.
   */
  fun createBundledSegmenter(context: Context, modelPath: String): InitializedSegmenter {
    val gpuSegmenter =
      createVerifiedSegmenter(
        context = context,
        baseOptions = segmenterBaseOptions(modelPath = modelPath, delegate = Delegate.GPU).build(),
      )
    if (gpuSegmenter != null) {
      return InitializedSegmenter(
        segmenter = gpuSegmenter,
        backend =
          SegmenterBackend(runtime = SegmenterRuntime.MEDIAPIPE, accelerator = Accelerator.GPU),
      )
    }
    Log.w(TAG, "The bundled runtime can't run the model on GPU. Falling back to CPU.")
    val cpuSegmenter =
      createSegmenter(
        context = context,
        baseOptions = segmenterBaseOptions(modelPath = modelPath, delegate = Delegate.CPU).build(),
      )
    return InitializedSegmenter(
      segmenter = cpuSegmenter,
      backend =
        SegmenterBackend(runtime = SegmenterRuntime.MEDIAPIPE, accelerator = Accelerator.CPU),
    )
  }

  /** Creates a segmenter with [baseOptions], throwing if MediaPipe can't create it. */
  private fun createSegmenter(context: Context, baseOptions: BaseOptions): InteractiveSegmenter =
    InteractiveSegmenter.createFromOptions(
      context,
      InteractiveSegmenterOptions.builder().setBaseOptions(baseOptions).build(),
    )

  /**
   * Creates a segmenter with [baseOptions] and verifies that it can run the model, or returns
   * `null` if it can't.
   *
   * MediaPipe only compiles the model for the delegate (e.g. GPU) once the segmenter first runs, so
   * creating the segmenter alone doesn't catch an unusable delegate: it would fail on the user's
   * first stroke instead. Call off the main thread.
   */
  fun createVerifiedSegmenter(context: Context, baseOptions: BaseOptions): InteractiveSegmenter? {
    var segmenter: InteractiveSegmenter? = null
    return try {
      segmenter = createSegmenter(context = context, baseOptions = baseOptions)
      warmUp(segmenter)
      segmenter
    } catch (e: Exception) {
      Log.w(TAG, "The segmenter can't run the model.", e)
      segmenter?.close()
      null
    }
  }

  /** Runs [segmenter] once on a blank image, throwing if it can't run the model. */
  private fun warmUp(segmenter: InteractiveSegmenter) {
    val image = createBitmap(width = WARM_UP_IMAGE_SIZE_PX, height = WARM_UP_IMAGE_SIZE_PX)
    // A single tap at the center of the image.
    val stroke =
      Stroke.builder()
        .setBrushMode(Stroke.BrushMode.POSITIVE)
        .setPoints(listOf(NormalizedKeypoint.create(/* x= */ 0.5f, /* y= */ 0.5f)))
        .setCompleted(true)
        .build()
    BitmapImageBuilder(image).build().use { mpImage ->
      segmenter.setImage(mpImage)
      segmenter.segment(listOf(stroke)).close()
    }
  }
}
