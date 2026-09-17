package com.google.ai.edge.gallery.apiserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsMappingTest {
  @Test
  fun `textContent extracts plain string content`() {
    val message = ChatCompletionMessage(role = "user", content = TextOrParts.Text("hello"))
    assertEquals("hello", message.textContent())
  }

  @Test
  fun `textContent concatenates text parts and ignores image parts`() {
    val message =
      ChatCompletionMessage(
        role = "user",
        content =
          TextOrParts.Parts(
            listOf(
              ContentPart.Text(text = "what is in this image?"),
              ContentPart.ImageUrl(imageUrl = ImageUrlValue(url = "data:image/png;base64,AAAA")),
            )
          ),
      )
    assertEquals("what is in this image?", message.textContent())
  }

  @Test
  fun `imageDataUris returns only the base64 payloads from image parts`() {
    val message =
      ChatCompletionMessage(
        role = "user",
        content =
          TextOrParts.Parts(
            listOf(
              ContentPart.Text(text = "describe this"),
              ContentPart.ImageUrl(imageUrl = ImageUrlValue(url = "data:image/png;base64,AAAA")),
            )
          ),
      )
    assertEquals(listOf("AAAA"), message.imageBase64Payloads())
  }

  @Test
  fun `imageDataUris is empty for a plain text message`() {
    val message = ChatCompletionMessage(role = "user", content = TextOrParts.Text("hello"))
    assertTrue(message.imageBase64Payloads().isEmpty())
  }

  @Test
  fun `toLiteRtRole maps user and assistant roles`() {
    assertEquals(
      LiteRtRole.USER,
      ChatCompletionMessage(role = "user", content = TextOrParts.Text("hi")).liteRtRole(),
    )
    assertEquals(
      LiteRtRole.MODEL,
      ChatCompletionMessage(role = "assistant", content = TextOrParts.Text("hi")).liteRtRole(),
    )
    assertNull(ChatCompletionMessage(role = "system", content = TextOrParts.Text("hi")).liteRtRole())
  }

  @Test
  fun `request deserializes plain string content`() {
    val request =
      chatCompletionRequestFromJson(
        """{"messages":[{"role":"user","content":"hello"}]}"""
      )
    assertEquals("hello", request.messages.single().textContent())
  }

  @Test
  fun `request deserializes parts array content with an image`() {
    val request =
      chatCompletionRequestFromJson(
        """{"messages":[{"role":"user","content":[
          {"type":"text","text":"what is this?"},
          {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
        ]}]}"""
      )
    val message = request.messages.single()
    assertEquals("what is this?", message.textContent())
    assertEquals(listOf("AAAA"), message.imageBase64Payloads())
  }

  @Test
  fun `successResponse wraps output in the OpenAI choices shape`() {
    val json = chatCompletionSuccessJson(output = "hi there")
    assertTrue(json.contains("\"role\":\"assistant\""))
    assertTrue(json.contains("\"content\":\"hi there\""))
    assertTrue(json.contains("\"object\":\"chat.completion\""))
  }

  @Test
  fun `errorResponse wraps a message in the OpenAI error shape`() {
    val json = errorResponseJson(message = "No model loaded")
    assertTrue(json.contains("\"message\":\"No model loaded\""))
  }
}
