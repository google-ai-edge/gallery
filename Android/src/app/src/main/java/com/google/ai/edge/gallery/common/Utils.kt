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
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
