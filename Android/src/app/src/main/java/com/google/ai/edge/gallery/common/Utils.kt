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

package com.google.ai.edge.gallery.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "AGUtils"

fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val index = message.indexOf("=== Source Location Trace")
  if (index >= 0) {
    return message.substring(0, index)
  }
  return message
}

fun processLlmResponse(response: String): String {
  return response.replace("\\n", "\n")
}

inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connect()

    val responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
      val inputStream = connection.inputStream
      val response = inputStream.bufferedReader().use { it.readText() }

      val gson = Gson()
      val jsonObj = gson.fromJson(response, T::class.java)
      return JsonObjAndTextContent(jsonObj = jsonObj, textContent = response)
    } else {
      Log.e("AGUtils", "HTTP error: $responseCode")
    }
  } catch (e: Exception) {
    Log.e("AGUtils", "Error when getting json response: ${e.message}")
    e.printStackTrace()
  }

  return null
}

fun convertWavToMonoWithMaxSeconds(
  context: Context,
  stereoUri: Uri,
  maxSeconds: Int = 30,
): AudioClip? {
  Log.d(TAG, "Start to convert wav file to mono channel")

  try {
    val inputStream =
      (if (stereoUri.scheme == null || stereoUri.scheme == "file") {
        FileInputStream(stereoUri.path ?: "")
      } else {
        context.contentResolver.openInputStream(stereoUri)
      }) ?: return null
    val originalBytes = inputStream.readBytes()
    inputStream.close()

    // Read WAV header
    if (originalBytes.size < 44) {
      // Not a valid WAV file
      Log.e(TAG, "Not a valid wav file")
      return null
    }

    val headerBuffer = ByteBuffer.wrap(originalBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    val channels = headerBuffer.getShort(22)
    var sampleRate = headerBuffer.getInt(24)
    val bitDepth = headerBuffer.getShort(34)
    Log.d(TAG, "File metadata: channels: $channels, sampleRate: $sampleRate, bitDepth: $bitDepth")

    // Normalize audio to 16-bit.
    val audioDataBytes = originalBytes.copyOfRange(fromIndex = 44, toIndex = originalBytes.size)
    var sixteenBitBytes: ByteArray =
      if (bitDepth.toInt() == 8) {
        Log.d(TAG, "Converting 8-bit audio to 16-bit.")
        convert8BitTo16Bit(audioDataBytes)
      } else {
        // Assume 16-bit or other format that can be handled directly
        audioDataBytes
      }

    // Convert byte array to short array for processing
    val shortBuffer =
      ByteBuffer.wrap(sixteenBitBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var pcmSamples = ShortArray(shortBuffer.remaining())
    shortBuffer.get(pcmSamples)

    // Resample if sample rate is less than 16000 Hz ---
    if (sampleRate < SAMPLE_RATE) {
      Log.d(TAG, "Resampling from $sampleRate Hz to $SAMPLE_RATE Hz.")
      pcmSamples = resample(pcmSamples, sampleRate, SAMPLE_RATE, channels.toInt())
      sampleRate = SAMPLE_RATE
      Log.d(TAG, "Resampling complete. New sample count: ${pcmSamples.size}")
    }

    // Convert stereo to mono if necessary
    var monoSamples =
      if (channels.toInt() == 2) {
        Log.d(TAG, "Converting stereo to mono.")
        val mono = ShortArray(pcmSamples.size / 2)
        for (i in mono.indices) {
          val left = pcmSamples[i * 2]
          val right = pcmSamples[i * 2 + 1]
          mono[i] = ((left + right) / 2).toShort()
        }
        mono
      } else {
        Log.d(TAG, "Audio is already mono. No channel conversion needed.")
        pcmSamples
      }

    // Trim the audio to maxSeconds ---
    val maxSamples = maxSeconds * sampleRate
    if (monoSamples.size > maxSamples) {
      Log.d(TAG, "Trimming clip from ${monoSamples.size} samples to $maxSamples samples.")
      monoSamples = monoSamples.copyOfRange(0, maxSamples)
    }

    val monoByteBuffer = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    monoByteBuffer.asShortBuffer().put(monoSamples)
    return AudioClip(audioData = monoByteBuffer.array(), sampleRate = sampleRate)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to convert wav to mono", e)
    return null
  }
}

/** Converts 8-bit unsigned PCM audio data to 16-bit signed PCM. */
private fun convert8BitTo16Bit(eightBitData: ByteArray): ByteArray {
  // The new 16-bit data will be twice the size
  val sixteenBitData = ByteArray(eightBitData.size * 2)
  val buffer = ByteBuffer.wrap(sixteenBitData).order(ByteOrder.LITTLE_ENDIAN)

  for (byte in eightBitData) {
    // Convert the unsigned 8-bit byte (0-255) to a signed 16-bit short (-32768 to 32767)
    // 1. Get the unsigned value by masking with 0xFF
    // 2. Subtract 128 to center the waveform around 0 (range becomes -128 to 127)
    // 3. Scale by 256 to expand to the 16-bit range
    val unsignedByte = byte.toInt() and 0xFF
    val sixteenBitSample = ((unsignedByte - 128) * 256).toShort()
    buffer.putShort(sixteenBitSample)
  }
  return sixteenBitData
}

/** Resamples PCM audio data from an original sample rate to a target sample rate. */
private fun resample(
  inputSamples: ShortArray,
  originalSampleRate: Int,
  targetSampleRate: Int,
  channels: Int,
): ShortArray {
  if (originalSampleRate == targetSampleRate) {
    return inputSamples
  }

  val ratio = targetSampleRate.toDouble() / originalSampleRate
  val outputLength = (inputSamples.size * ratio).toInt()
  val resampledData = ShortArray(outputLength)

  if (channels == 1) { // Mono
    for (i in resampledData.indices) {
      val position = i / ratio
      val index1 = floor(position).toInt()
      val index2 = index1 + 1
      val fraction = position - index1

      val sample1 = if (index1 < inputSamples.size) inputSamples[index1].toDouble() else 0.0
      val sample2 = if (index2 < inputSamples.size) inputSamples[index2].toDouble() else 0.0

      resampledData[i] = (sample1 * (1 - fraction) + sample2 * fraction).toInt().toShort()
    }
  }

  return resampledData
}

fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
  // Wrap the byte array in a ByteBuffer and set the order to little-endian
  val shortBuffer =
    ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

  var maxAmplitude = 0
  // Iterate through the short buffer to find the maximum absolute value
  while (shortBuffer.hasRemaining()) {
    val currentSample = abs(shortBuffer.get().toInt())
    if (currentSample > maxAmplitude) {
      maxAmplitude = currentSample
    }
  }
  return maxAmplitude
}

fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
  // First, decode with inJustDecodeBounds=true to check dimensions
  val options =
    BitmapFactory.Options().apply {
      inJustDecodeBounds = true
      (if (uri.scheme == null || uri.scheme == "file") {
          FileInputStream(uri.path ?: "")
        } else {
          context.contentResolver.openInputStream(uri)
        })
        ?.use { BitmapFactory.decodeStream(it, null, this) }

      // Calculate inSampleSize
      inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

      // Decode bitmap with inSampleSize set
      inJustDecodeBounds = false
    }

  return (if (uri.scheme == null || uri.scheme == "file") {
      FileInputStream(uri.path ?: "")
    } else {
      context.contentResolver.openInputStream(uri)
    })
    ?.use { BitmapFactory.decodeStream(it, null, options) }
}

fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_NORMAL -> return bitmap
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(
  options: BitmapFactory.Options,
  reqWidth: Int,
  reqHeight: Int,
): Int {
  // Raw height and width of image
  val height: Int = options.outHeight
  val width: Int = options.outWidth
  var inSampleSize = 1

  if (height > reqHeight || width > reqWidth) {
    // Calculate the ratio of height and width to the requested height and width
    val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
    val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()

    // Choose the largest ratio as inSampleSize value to ensure
    // that both dimensions are smaller than or equal to the requested dimensions.
    inSampleSize = max(heightRatio, widthRatio)
  }

  return inSampleSize
}

fun readFileToByteBuffer(file: File): ByteBuffer? {
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
