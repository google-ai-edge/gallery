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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.InterpreterApi

class GemmaGazeInterpreter(private val context: Context) {
  companion object {
    private const val TAG = "GemmaGaze"
    private const val MODEL_ASSET = "gemmagaze.tflite"
    const val INPUT_H = 336
    const val INPUT_W = 480
    const val NUM_CELLS = 70
    const val CELLS_H = 7
    const val CELLS_W = 10
    const val CELL_PIXEL_SIZE = 48
    private const val MAX_FRAMES = 8
  }

  @Volatile private var interpreter: InterpreterApi? = null

  private var inputBuffer: ByteBuffer? = null
  private var pixelArray: IntArray? = null

  fun loadFromAssets(ctx: Context) {
    Log.d(TAG, "Loading GemmaGaze model from assets...")
    try {
      val modelBuffer = loadModelFile(ctx)
      Log.d(TAG, "Model file loaded, size=${modelBuffer.capacity()} bytes")
      val options = InterpreterApi.Options().setNumThreads(4)
      interpreter = InterpreterApi.create(modelBuffer, options)
      preallocateBuffers()
      Log.d(TAG, "GemmaGaze model loaded successfully, isLoaded=${isLoaded()}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load GemmaGaze model", e)
    }
  }

  private fun preallocateBuffers() {
    inputBuffer =
      ByteBuffer.allocateDirect(MAX_FRAMES * 3 * INPUT_H * INPUT_W * 4)
        .order(ByteOrder.nativeOrder())
    pixelArray = IntArray(INPUT_H * INPUT_W)
  }

  @Synchronized
  fun scoreFrames(frames: List<Bitmap>): Array<FloatArray> {
    val interp = interpreter ?: throw IllegalStateException("Model not loaded")
    val nFrames = frames.size.coerceAtMost(MAX_FRAMES)

    val buf =
      inputBuffer
        ?: ByteBuffer.allocateDirect(MAX_FRAMES * 3 * INPUT_H * INPUT_W * 4)
          .order(ByteOrder.nativeOrder())
    buf.clear()

    val pixels = pixelArray ?: IntArray(INPUT_H * INPUT_W)

    for (i in 0 until nFrames) {
      val frame = frames[i]
      val scaled =
        if (frame.width == INPUT_W && frame.height == INPUT_H) {
          frame
        } else {
          Bitmap.createScaledBitmap(frame, INPUT_W, INPUT_H, false)
        }

      scaled.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)

      for (c in 0 until 3) {
        val shift =
          when (c) {
            0 -> 16
            1 -> 8
            else -> 0
          }
        for (j in pixels.indices) {
          buf.putFloat(((pixels[j] shr shift) and 0xFF) / 127.5f - 1.0f)
        }
      }
      if (scaled !== frame) scaled.recycle()
    }

    if (nFrames < MAX_FRAMES) {
      val remaining = (MAX_FRAMES - nFrames) * 3 * INPUT_H * INPUT_W
      for (i in 0 until remaining) {
        buf.putFloat(0f)
      }
    }
    buf.rewind()

    val outputBuffer = Array(MAX_FRAMES) { FloatArray(NUM_CELLS) }
    interp.run(buf, outputBuffer)
    return outputBuffer.take(nFrames).toTypedArray()
  }

  fun selectTopCells(scores: Array<FloatArray>, k: Int = 8): Array<IntArray> {
    return scores
      .map { frameScores ->
        frameScores.indices.sortedByDescending { frameScores[it] }.take(k).sorted().toIntArray()
      }
      .toTypedArray()
  }

  fun isLoaded(): Boolean = interpreter != null

  fun close() {
    interpreter?.close()
    interpreter = null
    inputBuffer = null
    pixelArray = null
  }

  private fun loadModelFile(ctx: Context): MappedByteBuffer {
    val assetFd = ctx.assets.openFd(MODEL_ASSET)
    return assetFd.use { fd ->
      FileInputStream(fd.fileDescriptor).use { fis ->
        fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
      }
    }
  }
}
