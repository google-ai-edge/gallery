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

package com.google.ai.edge.gallery.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Utility object for image operations. */
object ImageUtils {
  /** Encodes the given bitmap into a TGA byte array (32-bit BGRA, Top-Down). */
  suspend fun encodeTga(
    bitmap: Bitmap,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ): ByteArray =
    withContext(dispatcher) {
      val width = bitmap.width
      val height = bitmap.height
      val pixels = IntArray(width * height)
      bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

      val tgaData = ByteArray(20 + width * height * 4)
      val buffer = ByteBuffer.wrap(tgaData)
      buffer.order(ByteOrder.LITTLE_ENDIAN)

      // TGA Header (18 bytes) + 2 dummy bytes for alignment
      tgaData[0] = 2 // ID Length = 2 bytes (acts as padding)
      tgaData[2] = 2 // Uncompressed True-color
      tgaData[12] = (width and 0xFF).toByte()
      tgaData[13] = ((width shr 8) and 0xFF).toByte()
      tgaData[14] = (height and 0xFF).toByte()
      tgaData[15] = ((height shr 8) and 0xFF).toByte()
      tgaData[16] = 32 // 32 bits per pixel
      tgaData[17] = 0x28 // Top-down, 8 alpha bits

      // Clear bytes 18, 19 (Dummy ID bytes for alignment)
      tgaData[18] = 0
      tgaData[19] = 0

      // Fast Write Pixels
      buffer.position(20)
      val intBuffer = buffer.asIntBuffer()
      intBuffer.put(pixels)

      tgaData
    }

  /**
   * Decodes an image byte array (supporting standard formats JPEG/PNG/WEBP as well as TGA) into a
   * [Bitmap].
   */
  fun decodeBitmap(bytes: ByteArray): Bitmap? {
    // 1. Try decoding with BitmapFactory for standard formats (JPEG, PNG, WEBP, etc.)
    val standardBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (standardBitmap != null) {
      return standardBitmap
    }

    // 2. Check if the byte array is a 32-bit TGA image (as produced by encodeTga)
    if (bytes.size >= 20 && bytes[2] == 2.toByte() && bytes[16] == 32.toByte()) {
      try {
        val width = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
        val height = (bytes[14].toInt() and 0xFF) or ((bytes[15].toInt() and 0xFF) shl 8)
        if (width > 0 && height > 0 && bytes.size >= 20 + width * height * 4) {
          val buffer = ByteBuffer.wrap(bytes, 20, width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
          val pixels = IntArray(width * height)
          buffer.asIntBuffer().get(pixels)
          return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
      } catch (e: Exception) {
        // Ignored
      }
    }
    return null
  }
}
