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
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

data class LaunchInfo(val ts: Long)

private const val TAG = "AGUtils"
private const val LAUNCH_INFO_FILE_NAME = "launch_info"
private const val START_THINKING = "***Thinking...***"
private const val DONE_THINKING = "***Done thinking***"

fun readLaunchInfo(context: Context): LaunchInfo? {
  try {
    val gson = Gson()
    val type = object : TypeToken<LaunchInfo>() {}.type
    val file = File(context.getExternalFilesDir(null), LAUNCH_INFO_FILE_NAME)
    val content = file.readText()
    return gson.fromJson(content, type)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to read launch info", e)
    return null
  }
}

fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val index = message.indexOf("=== Source Location Trace")
  if (index >= 0) {
    return message.substring(0, index)
  }
  return message
}

fun processLlmResponse(response: String): String {
  // Add "thinking" and "done thinking" around the thinking content.
  var newContent =
    response.replace("<think>", "$START_THINKING\n").replace("</think>", "\n$DONE_THINKING")

  // Remove empty thinking content.
  val endThinkingIndex = newContent.indexOf(DONE_THINKING)
  if (endThinkingIndex >= 0) {
    val thinkingContent =
      newContent
        .substring(0, endThinkingIndex + DONE_THINKING.length)
        .replace(START_THINKING, "")
        .replace(DONE_THINKING, "")
    if (thinkingContent.isBlank()) {
      newContent = newContent.substring(endThinkingIndex + DONE_THINKING.length)
    }
  }

  newContent = newContent.replace("\\n", "\n")

  return newContent
}

fun writeLaunchInfo(context: Context) {
  try {
    val gson = Gson()
    val launchInfo = LaunchInfo(ts = System.currentTimeMillis())
    val jsonString = gson.toJson(launchInfo)
    val file = File(context.getExternalFilesDir(null), LAUNCH_INFO_FILE_NAME)
    file.writeText(jsonString)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to write launch info", e)
  }
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
      val type = object : TypeToken<T>() {}.type
      val jsonObj = gson.fromJson<T>(response, type)
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
    val inputStream = context.contentResolver.openInputStream(stereoUri) ?: return null
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
