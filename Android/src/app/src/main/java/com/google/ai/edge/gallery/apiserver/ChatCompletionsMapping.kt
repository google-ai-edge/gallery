package com.google.ai.edge.gallery.apiserver

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}

@Serializable
data class ChatCompletionChoice(
  val index: Int = 0,
  val message: ChatCompletionResponseMessage,
  @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable data class ChatCompletionResponseMessage(val role: String = "assistant", val content: String)

@Serializable
data class ChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val choices: List<ChatCompletionChoice>,
)

@Serializable data class ErrorBody(val message: String)

@Serializable data class ErrorResponse(val error: ErrorBody)

/** Parses a raw OpenAI-shaped `/v1/chat/completions` request body. */
fun chatCompletionRequestFromJson(rawJson: String): ChatCompletionRequest =
  json.decodeFromString(rawJson)

/** Builds the OpenAI-shaped success response JSON for a completed turn's [output] text. */
fun chatCompletionSuccessJson(output: String): String {
  val response =
    ChatCompletionResponse(
      id = "gallery-${UUID.randomUUID()}",
      choices = listOf(ChatCompletionChoice(message = ChatCompletionResponseMessage(content = output))),
    )
  return json.encodeToString(response)
}

/** Builds the OpenAI-shaped error response JSON for a failure [message]. */
fun errorResponseJson(message: String): String = json.encodeToString(ErrorResponse(ErrorBody(message)))
