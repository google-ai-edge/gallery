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
}
