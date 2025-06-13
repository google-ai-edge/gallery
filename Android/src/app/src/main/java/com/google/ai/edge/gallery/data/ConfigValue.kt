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

package com.google.ai.edge.gallery.data

// @Serializable(with = ConfigValueSerializer::class)
sealed class ConfigValue {
  // @Serializable
  data class IntValue(val value: Int) : ConfigValue()

  // @Serializable
  data class FloatValue(val value: Float) : ConfigValue()

  // @Serializable
  data class StringValue(val value: String) : ConfigValue()
}

// /**
//  * Custom serializer for the ConfigValue class.
//  *
//  * This object implements the KSerializer interface to provide custom serialization and
//  * deserialization logic for the ConfigValue class. It handles different types of ConfigValue
//  * (IntValue, FloatValue, StringValue) and supports JSON format.
//  */
// object ConfigValueSerializer : KSerializer<ConfigValue> {
//   override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ConfigValue")

//   override fun serialize(encoder: Encoder, value: ConfigValue) {
//     when (value) {
//       is ConfigValue.IntValue -> encoder.encodeInt(value.value)
//       is ConfigValue.FloatValue -> encoder.encodeFloat(value.value)
//       is ConfigValue.StringValue -> encoder.encodeString(value.value)
//     }
//   }

//   override fun deserialize(decoder: Decoder): ConfigValue {
//     val input =
//       decoder as? JsonDecoder
//         ?: throw SerializationException("This serializer only works with Json")
//     return when (val element = input.decodeJsonElement()) {
//       is JsonPrimitive -> {
//         if (element.isString) {
//           ConfigValue.StringValue(element.content)
//         } else if (element.content.contains('.')) {
//           ConfigValue.FloatValue(element.content.toFloat())
//         } else {
//           ConfigValue.IntValue(element.content.toInt())
//         }
//       }

//       else -> throw SerializationException("Expected JsonPrimitive")
//     }
//   }
// }

fun getIntConfigValue(configValue: ConfigValue?, default: Int): Int {
  if (configValue == null) {
    return default
  }
  return when (configValue) {
    is ConfigValue.IntValue -> configValue.value
    is ConfigValue.FloatValue -> configValue.value.toInt()
    is ConfigValue.StringValue -> 0
  }
}

fun getFloatConfigValue(configValue: ConfigValue?, default: Float): Float {
  if (configValue == null) {
    return default
  }
  return when (configValue) {
    is ConfigValue.IntValue -> configValue.value.toFloat()
    is ConfigValue.FloatValue -> configValue.value
    is ConfigValue.StringValue -> 0f
  }
}

fun getStringConfigValue(configValue: ConfigValue?, default: String): String {
  if (configValue == null) {
    return default
  }
  return when (configValue) {
    is ConfigValue.IntValue -> "${configValue.value}"
    is ConfigValue.FloatValue -> "${configValue.value}"
    is ConfigValue.StringValue -> configValue.value
  }
}
