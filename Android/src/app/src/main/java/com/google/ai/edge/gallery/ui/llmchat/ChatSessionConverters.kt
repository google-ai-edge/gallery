package com.google.ai.edge.gallery.ui.llmchat

import com.google.ai.edge.gallery.data.ChatMessage as DataChatMessage
import com.google.ai.edge.gallery.data.ChatSession
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage as UiChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import java.util.UUID

fun ChatSession.toUiMessages(): List<UiChatMessage> {
  android.util.Log.d("AGChatConverters", "toUiMessages: ${messages.size} data messages")
  return messages.map { msg ->
    when (msg.role) {
      "user" -> ChatMessageText(content = msg.text, side = ChatSide.USER)
      "assistant" -> {
        if (msg.thinking.isNotEmpty()) {
          ChatMessageThinking(
            content = msg.thinking,
            inProgress = false,
            side = ChatSide.AGENT,
          )
        } else if (msg.isError) {
          ChatMessageError(content = msg.text)
        } else {
          ChatMessageText(content = msg.text, side = ChatSide.AGENT)
        }
      }
      else -> ChatMessageText(content = msg.text, side = ChatSide.SYSTEM)
    }
  }
}

fun List<UiChatMessage>.toDataMessages(): List<DataChatMessage> {
  val result = mapIndexedNotNull { index, msg ->
    when (msg) {
      is ChatMessageText -> {
        val role =
          when (msg.side) {
            ChatSide.USER -> "user"
            ChatSide.AGENT -> "assistant"
            else -> "system"
          }
        DataChatMessage(
          id = index.toString(),
          role = role,
          text = msg.content,
          isError = false,
        )
      }
      is ChatMessageError -> {
        DataChatMessage(
          id = index.toString(),
          role = "assistant",
          text = msg.content,
          isError = true,
        )
      }
      is ChatMessageThinking -> {
        DataChatMessage(
          id = index.toString(),
          role = "assistant",
          text = "",
          thinking = msg.content,
          isError = false,
        )
      }
      else -> null // Skip images, audio, loading, info, warnings, etc.
    }
  }
  android.util.Log.d("AGChatConverters", "toDataMessages: ${this.size} UI messages -> ${result.size} data messages")
  return result
}

fun generateChatTitle(messages: List<DataChatMessage>): String {
  val firstUserText = messages.firstOrNull { it.role == "user" }?.text?.trim()
  return if (!firstUserText.isNullOrEmpty()) {
    firstUserText.take(30).replace("\n", " ")
  } else {
    "New Chat"
  }
}

fun generateSessionId(): String = UUID.randomUUID().toString()
