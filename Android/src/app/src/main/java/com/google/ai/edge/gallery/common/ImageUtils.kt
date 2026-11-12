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
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "AGImageUtils"

/** Utility object for image operations. */
object ImageUtils {
  /** Encodes the given bitmap into a TGA byte array (32-bit BGRA, Top-Down). */
  fun encodeTga(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    // Pixels of a hardware bitmap live in graphics memory and can only be read from a copy.
    val isHardwareBitmap =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    if (isHardwareBitmap) {
      val softwareBitmap =
        checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false)) {
          "Failed to copy hardware bitmap"
        }
      try {
        softwareBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
      } finally {
        softwareBitmap.recycle()
      }
    } else {
      bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    }

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

    return tgaData
  }

  /** Encodes the given bitmap into a TGA byte array (32-bit BGRA, Top-Down) on [dispatcher]. */
  suspend fun encodeTga(bitmap: Bitmap, dispatcher: CoroutineDispatcher): ByteArray =
    withContext(dispatcher) { encodeTga(bitmap) }

  /**
   * Decodes an image byte array (supporting standard formats JPEG/PNG/WEBP as well as TGA) into a
   * [Bitmap].
   */
  fun decodeBitmap(bytes: ByteArray): Bitmap? {
    // Check for an uncompressed True-color TGA image (24-bit or 32-bit) first, and only then try
    // BitmapFactory for standard formats (JPEG, PNG, WEBP, etc.). Checking TGA first when the
    // header and size match avoids noisy BitmapFactory/Skia logcat errors ("Failed to create image
    // decoder with message 'unimplemented'").
    val tgaBitmap =
      try {
        decodeTgaBitmap(bytes)
      } catch (e: RuntimeException) {
        Log.w(TAG, "Failed to decode TGA image", e)
        null
      }
    return tgaBitmap ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  /**
   * Decodes [bytes] as an uncompressed True-color TGA image, or returns `null` if it is not one.
   */
  private fun decodeTgaBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.size < 18) return null
    val idLength = bytes[0].toInt() and 0xFF
    val colorMapType = bytes[1].toInt() and 0xFF
    val imageType = bytes[2].toInt() and 0xFF
    val bpp = bytes[16].toInt() and 0xFF
    if (colorMapType != 0 || imageType != 2 || (bpp != 32 && bpp != 24)) {
      return null
    }
    val width = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
    val height = (bytes[14].toInt() and 0xFF) or ((bytes[15].toInt() and 0xFF) shl 8)
    if (width <= 0 || height <= 0) return null

    val bytesPerPixel = bpp / 8
    val pixelOffset = 18 + idLength
    // Computed as Long: the pixel data of a malformed header can be larger than Int.MAX_VALUE.
    val pixelBytesCount = width.toLong() * height.toLong() * bytesPerPixel.toLong()
    if (bytes.size.toLong() < pixelOffset.toLong() + pixelBytesCount) {
      return null
    }

    val isTopDown = (bytes[17].toInt() and 0x20) != 0
    val pixels = IntArray(width * height)
    if (bpp == 32 && isTopDown) {
      val buffer =
        ByteBuffer.wrap(bytes, pixelOffset, width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
      buffer.asIntBuffer().get(pixels)
    } else {
      for (y in 0 until height) {
        val srcY = if (isTopDown) y else (height - 1 - y)
        var srcIdx = pixelOffset + srcY * width * bytesPerPixel
        var dstIdx = y * width
        for (x in 0 until width) {
          val b = bytes[srcIdx].toInt() and 0xFF
          val g = bytes[srcIdx + 1].toInt() and 0xFF
          val r = bytes[srcIdx + 2].toInt() and 0xFF
          val a = if (bpp == 32) (bytes[srcIdx + 3].toInt() and 0xFF) else 0xFF
          pixels[dstIdx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
          srcIdx += bytesPerPixel
        }
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }
}
