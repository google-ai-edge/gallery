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

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptParserTest {

  // 1x1 pixel transparent GIF base64
  private val validBase64Image = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
  private val dummyBase64Audio = "UklGRigAAABXQVZFZm10IBIAAAABAAEARKwAAIhYAQACABAAAABkYXRhAgAAAAAA"

  @Test
  fun parseContent_textOnly_returnsText() {
    val content = "Hello, world!"
    val parsed = PromptParser.parseContent(content)

    assertThat(parsed.text).isEqualTo("Hello, world!")
    assertThat(parsed.images).isEmpty()
    assertThat(parsed.audioClips).isEmpty()
  }

  @Test
  fun parseContent_jsonArrayTextOnly_returnsText() {
    val array =
      JSONArray().apply {
        put(
          JSONObject().apply {
            put("type", "text")
            put("text", "Hello from JSON!")
          }
        )
      }
    val parsed = PromptParser.parseContent(array)

    assertThat(parsed.text).isEqualTo("Hello from JSON!")
    assertThat(parsed.images).isEmpty()
    assertThat(parsed.audioClips).isEmpty()
  }

  @Test
  fun parseContent_multimodal_returnsParsedComponents() {
    val array =
      JSONArray().apply {
        put(
          JSONObject().apply {
            put("type", "text")
            put("text", "Describe this image and audio: ")
          }
        )
        put(
          JSONObject().apply {
            put("type", "image_url")
            put(
              "image_url",
              JSONObject().apply { put("url", "data:image/gif;base64,$validBase64Image") },
            )
          }
        )
        put(
          JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply { put("data", dummyBase64Audio) })
          }
        )
      }

    val parsed = PromptParser.parseContent(array)

    assertThat(parsed.text).isEqualTo("Describe this image and audio: ")
    assertThat(parsed.images).hasSize(1)
    assertThat(parsed.images[0]).isNotNull()
    assertThat(parsed.audioClips).hasSize(1)
    assertThat(parsed.audioClips[0]).isNotEmpty()
  }

  @Test
  fun jsonToContents_textOnly_returnsTextContent() {
    val content = "Simple text"
    val lmContents = PromptParser.jsonToContents(content)

    // LmContents doesn't expose easy getters, but we can verify it doesn't crash
    // and we can check its class type if needed.
    assertThat(lmContents).isNotNull()
  }

  @Test
  fun convertToLmMessages_convertsCorrectly() {
    val history =
      listOf(
        HistoryMessage("user", "Hello"),
        HistoryMessage("assistant", "Hi there"),
        HistoryMessage("system", "You are a helpful assistant"),
      )

    val lmMessages = PromptParser.convertToLmMessages(history)

    assertThat(lmMessages).hasSize(3)
    // LmMessage has factory methods user(), model(), system().
    // We can't easily check contents without reflection, but we verify size and non-null.
    assertThat(lmMessages[0]).isNotNull()
    assertThat(lmMessages[1]).isNotNull()
    assertThat(lmMessages[2]).isNotNull()
  }

  @Test
  fun convertToLmMessages_malformedBase64_doesNotCrash() {
    val malformedHistory =
      listOf(
        HistoryMessage(
          "user",
          JSONArray()
            .apply {
              put(
                JSONObject().apply {
                  put("type", "image_url")
                  put(
                    "image_url",
                    JSONObject().apply { put("url", "data:image/gif;base64,invalid_base64") },
                  )
                }
              )
              put(
                JSONObject().apply {
                  put("type", "input_audio")
                  put("input_audio", JSONObject().apply { put("data", "invalid_base64") })
                }
              )
            }
            .toString(),
        )
      )

    val lmMessages = PromptParser.convertToLmMessages(malformedHistory)

    assertThat(lmMessages).hasSize(1)
    assertThat(lmMessages[0]).isNotNull()
  }
}
