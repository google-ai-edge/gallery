/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Content as LmContent
import com.google.ai.edge.litertlm.Contents as LmContents
import com.google.ai.edge.litertlm.Message as LmMessage
import org.json.JSONArray

object PromptParser {
  private const val TAG = "PromptParser"

  data class ParsedPrompt(
    val text: String,
    val images: List<Bitmap>,
    val audioClips: List<ByteArray>,
  )

  fun parseContent(contentVal: Any): ParsedPrompt {
    val images = mutableListOf<Bitmap>()
    val audioClips = mutableListOf<ByteArray>()
    var textPrompt = ""

    if (contentVal is JSONArray) {
      for (i in 0 until contentVal.length()) {
        val part = contentVal.getJSONObject(i)
        val type = part.getString("type")
        when (type) {
          "text" -> {
            textPrompt += part.getString("text")
          }
          "image_url" -> {
            val urlObj = part.getJSONObject("image_url")
            val url = urlObj.getString("url")
            if (url.startsWith("data:image/")) {
              val base64Data = url.substringAfter("base64,")
              try {
                val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                if (bitmap != null) {
                  images.add(bitmap)
                } else {
                  Log.e(TAG, "Failed to decode bitmap from base64")
                }
              } catch (e: Exception) {
                Log.e(TAG, "Failed to parse base64 image", e)
              }
            }
          }
          "input_audio" -> {
            val audioObj = part.getJSONObject("input_audio")
            val base64Data = audioObj.getString("data")
            try {
              val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
              audioClips.add(decodedString)
            } catch (e: Exception) {
              Log.e(TAG, "Failed to parse base64 audio", e)
            }
          }
        }
      }
    } else {
      textPrompt = contentVal.toString()
    }
    return ParsedPrompt(textPrompt, images, audioClips)
  }

  fun jsonToContents(contentVal: Any): LmContents {
    if (contentVal is JSONArray) {
      val parts = mutableListOf<LmContent>()
      for (i in 0 until contentVal.length()) {
        val partObj = contentVal.getJSONObject(i)
        val type = partObj.getString("type")
        when (type) {
          "text" -> {
            parts.add(LmContent.Text(partObj.getString("text")))
          }
          "image_url" -> {
            val urlObj = partObj.getJSONObject("image_url")
            val url = urlObj.getString("url")
            if (url.startsWith("data:image/")) {
              val base64Data = url.substringAfter("base64,")
              try {
                val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
                parts.add(LmContent.ImageBytes(decodedString))
              } catch (e: Exception) {
                Log.e(TAG, "Failed to decode base64 image in jsonToContents", e)
              }
            }
          }
          "input_audio" -> {
            val audioObj = partObj.getJSONObject("input_audio")
            val base64Data = audioObj.getString("data")
            try {
              val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
              parts.add(LmContent.AudioBytes(decodedString))
            } catch (e: Exception) {
              Log.e(TAG, "Failed to decode base64 audio in jsonToContents", e)
            }
          }
        }
      }
      return LmContents.of(parts)
    } else {
      return LmContents.of(contentVal.toString())
    }
  }

  fun convertToLmMessages(history: List<HistoryMessage>): List<LmMessage> {
    return history.mapNotNull { msg ->
      val contentVal =
        try {
          if (msg.content.startsWith("[")) JSONArray(msg.content) else msg.content
        } catch (e: Exception) {
          msg.content
        }
      val lmContents = jsonToContents(contentVal)
      when (msg.role) {
        "user" -> LmMessage.user(lmContents)
        "assistant" -> LmMessage.model(lmContents)
        "system" -> LmMessage.system(lmContents)
        else -> null
      }
    }
  }
}

data class HistoryMessage(val role: String, val content: String)
