package com.server.edge.gallery.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Represents a single chat message, matching the Flutter ChatMessageItem model.
 */
data class ChatMessage(
    val id: String,
    val role: String, // "user" or "assistant"
    val text: String,
    val thinking: String = "",
    val isError: Boolean = false,
)

/**
 * Represents a saved chat session, matching the Flutter ChatSessionItem model.
 * Each session holds a list of messages, a title, and the model name used.
 */
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: String, // ISO-8601
    val messages: List<ChatMessage>,
    val modelName: String? = null,
) {
    companion object {
        private val gson = Gson()
        private val sessionsType = object : TypeToken<List<ChatSession>>() {}.type

        fun fromJson(json: String): List<ChatSession> {
            return try {
                gson.fromJson(json, sessionsType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toJson(sessions: List<ChatSession>): String {
            return gson.toJson(sessions)
        }
    }
}
