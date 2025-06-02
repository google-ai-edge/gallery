package com.google.ai.edge.gallery.data

import java.util.UUID

/**
 * Represents a user's profile information.
 *
 * @property name The name of the user.
 * @property summary A brief summary or bio of the user.
 * @property skills A list of the user's skills.
 * @property experience A list of strings, where each string can represent a job or project description.
 */
data class UserProfile(
    val name: String? = null,
    val summary: String? = null,
    val skills: List<String> = emptyList(),
    val experience: List<String> = emptyList()
)

/**
 * Represents an AI persona that can be used in conversations.
 *
 * @property id A unique identifier for the persona (e.g., a UUID).
 * @property name The name of the persona.
 * @property prompt The system prompt associated with this persona, defining its behavior and responses.
 * @property isDefault Indicates if this is a default persona.
 */
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val isDefault: Boolean = false
)

/**
 * Defines the role of the sender of a chat message.
 */
enum class ChatMessageRole {
    /** The message is from the end-user. */
    USER,
    /** The message is from the AI assistant. */
    ASSISTANT,
    /** The message is a system instruction or context. */
    SYSTEM
}

/**
 * Represents a single message within a chat conversation.
 *
 * @property id A unique identifier for the message (e.g., a UUID).
 * @property conversationId The ID of the conversation this message belongs to.
 * @property timestamp The time the message was created, in epoch milliseconds.
 * @property role The role of the message sender (user, assistant, or system).
 * @property content The textual content of the message.
 * @property personaUsedId The ID of the Persona active when this message was generated or sent, if applicable.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val timestamp: Long,
    val role: ChatMessageRole,
    val content: String,
    val personaUsedId: String? = null
)

/**
 * Represents a chat conversation.
 *
 * @property id A unique identifier for the conversation (e.g., a UUID).
 * @property title An optional user-defined title for the conversation.
 * @property creationTimestamp The time the conversation was created, in epoch milliseconds.
 * @property lastModifiedTimestamp The time the conversation was last modified, in epoch milliseconds.
 * @property initialSystemPrompt A custom system prompt for this specific conversation, which might override a Persona's default prompt.
 * @property messages A list of chat messages in this conversation. For local storage, embedding can work. For cloud sync, separate storage of messages linked by ID is better.
 * @property activePersonaId The ID of the persona primarily used in this conversation.
 * @property modelIdUsed The ID (e.g. name) of the AI model used in this conversation.
 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String? = null,
    val creationTimestamp: Long,
    var lastModifiedTimestamp: Long,
    val initialSystemPrompt: String? = null,
    val modelIdUsed: String? = null, // Add this
    val messages: List<ChatMessage> = emptyList(),
    val activePersonaId: String? = null
)

/**
 * Represents a document imported or managed by the user.
 *
 * @property id Unique identifier for the document (e.g., UUID).
 * @property fileName Original name of the file.
 * @property localPath Path to the locally stored copy of the document, if applicable.
 * @property originalSource Indicates where the document came from (e.g., "local", a URL for Google Docs).
 * @property fileType The MIME type or a simple extension string (e.g., "txt", "pdf", "docx").
 * @property extractedText The text content extracted from the document. Null if not yet extracted or not applicable.
 * @property importTimestamp Timestamp when the document was imported.
 * @property lastAccessedTimestamp Timestamp when the document was last used in a chat (optional).
 */
data class UserDocument(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val localPath: String? = null,
    val originalSource: String, // e.g., "local", "google_drive_id:<id>"
    val fileType: String, // e.g., "text/plain", "application/pdf"
    var extractedText: String? = null,
    val importTimestamp: Long = System.currentTimeMillis(),
    var lastAccessedTimestamp: Long? = null
)
