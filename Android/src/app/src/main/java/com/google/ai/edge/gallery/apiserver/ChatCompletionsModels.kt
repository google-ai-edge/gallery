package com.google.ai.edge.gallery.apiserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/** Request body for `POST /v1/chat/completions`, matching the OpenAI API's shape. */
@Serializable
data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatCompletionMessage> = emptyList(),
)

@Serializable
data class ChatCompletionMessage(val role: String, val content: TextOrParts) {
  /** The concatenated text of this message, ignoring any image parts. */
  fun textContent(): String =
    when (content) {
      is TextOrParts.Text -> content.value
      is TextOrParts.Parts ->
        content.parts.filterIsInstance<ContentPart.Text>().joinToString(separator = " ") { it.text }
    }

  /** The base64 payload (after the `data:...;base64,` prefix) of every image part, in order. */
  fun imageBase64Payloads(): List<String> =
    when (content) {
      is TextOrParts.Text -> emptyList()
      is TextOrParts.Parts ->
        content.parts.filterIsInstance<ContentPart.ImageUrl>().mapNotNull {
          val marker = ";base64,"
          val idx = it.imageUrl.url.indexOf(marker)
          if (idx == -1) null else it.imageUrl.url.substring(idx + marker.length)
        }
    }

  /** Maps this message's OpenAI `role` to the litertlm turn role, or null for unsupported roles. */
  fun liteRtRole(): LiteRtRole? =
    when (role) {
      "user" -> LiteRtRole.USER
      "assistant" -> LiteRtRole.MODEL
      else -> null
    }
}

enum class LiteRtRole {
  USER,
  MODEL,
}

/** A message's `content` field, which OpenAI allows to be either a plain string or a parts array. */
@Serializable(with = TextOrPartsSerializer::class)
sealed interface TextOrParts {
  data class Text(val value: String) : TextOrParts

  data class Parts(val parts: List<ContentPart>) : TextOrParts
}

@Serializable
sealed interface ContentPart {
  @Serializable @SerialName("text") data class Text(val text: String) : ContentPart

  @Serializable
  @SerialName("image_url")
  data class ImageUrl(@SerialName("image_url") val imageUrl: ImageUrlValue) : ContentPart
}

@Serializable data class ImageUrlValue(val url: String)

/**
 * Hand-written serializer for [TextOrParts]: a bare JSON string decodes to [TextOrParts.Text], a
 * JSON array decodes to [TextOrParts.Parts]. `JsonContentPolymorphicSerializer` only discriminates
 * between object shapes, not between a primitive and an array, so this reads/writes the raw
 * [JsonElement] directly instead.
 */
object TextOrPartsSerializer : KSerializer<TextOrParts> {
  override val descriptor = PrimitiveSerialDescriptor("TextOrParts", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): TextOrParts {
    val jsonDecoder = decoder as? JsonDecoder ?: error("TextOrParts can only be decoded from JSON")
    return when (val element = jsonDecoder.decodeJsonElement()) {
      is JsonArray ->
        TextOrParts.Parts(
          jsonDecoder.json.decodeFromJsonElement(ListSerializer(ContentPart.serializer()), element)
        )
      is JsonPrimitive -> TextOrParts.Text(element.content)
      else -> error("Unsupported `content` shape: $element")
    }
  }

  override fun serialize(encoder: Encoder, value: TextOrParts) {
    val jsonEncoder = encoder as? JsonEncoder ?: error("TextOrParts can only be encoded to JSON")
    val element: JsonElement =
      when (value) {
        is TextOrParts.Text -> JsonPrimitive(value.value)
        is TextOrParts.Parts ->
          jsonEncoder.json.encodeToJsonElement(ListSerializer(ContentPart.serializer()), value.parts)
      }
    jsonEncoder.encodeJsonElement(element)
  }
}
