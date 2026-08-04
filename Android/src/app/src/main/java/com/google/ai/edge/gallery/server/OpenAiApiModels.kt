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

package com.google.ai.edge.gallery.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class OpenAiChatCompletionRequest(
  val model: String = "",
  val messages: List<OpenAiChatMessage>,
  val stream: Boolean = false,
  val temperature: Double? = null,
  @SerialName("top_p") val topP: Double? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable data class OpenAiChatMessage(val role: String, val content: JsonElement)

@Serializable
data class OpenAiModelList(
  @SerialName("object") val objectType: String = "list",
  val data: List<OpenAiModel>,
)

@Serializable
data class OpenAiModel(
  val id: String,
  @SerialName("object") val objectType: String = "model",
  val created: Long,
  @SerialName("owned_by") val ownedBy: String = "edge-gallery",
)

@Serializable
data class OpenAiChatCompletionResponse(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChatChoice>,
  val usage: OpenAiUsage,
)

@Serializable
data class OpenAiChatChoice(
  val index: Int = 0,
  val message: OpenAiAssistantMessage,
  @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class OpenAiAssistantMessage(val role: String = "assistant", val content: String)

@Serializable
data class OpenAiUsage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class OpenAiChatCompletionChunk(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChunkChoice>,
)

@Serializable
data class OpenAiChunkChoice(
  val index: Int = 0,
  val delta: OpenAiDelta,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable data class OpenAiDelta(val role: String? = null, val content: String? = null)

@Serializable data class OpenAiErrorEnvelope(val error: OpenAiError)

@Serializable
data class OpenAiError(
  val message: String,
  val type: String = "invalid_request_error",
  val param: String? = null,
  val code: String? = null,
)

internal fun OpenAiChatCompletionRequest.toInferencePrompt(): String {
  return messages
    .mapNotNull { message ->
      val text = message.content.asText().trim()
      if (text.isEmpty()) null
      else {
        val role =
          when (message.role.lowercase()) {
            "system" -> "System"
            "developer" -> "Developer"
            "assistant" -> "Assistant"
            "user" -> "User"
            else -> message.role.replaceFirstChar { it.uppercase() }
          }
        "$role: $text"
      }
    }
    .joinToString(separator = "\n\n", postfix = "\n\nAssistant:")
}

private fun JsonElement.asText(): String {
  return when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray ->
      mapNotNull { part ->
          val obj = part as? JsonObject ?: return@mapNotNull null
          if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
            obj["text"]?.jsonPrimitive?.contentOrNull
          } else {
            null
          }
        }
        .joinToString("\n")
    else -> ""
  }
}

internal fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(if (text.isEmpty()) 0 else 1)
