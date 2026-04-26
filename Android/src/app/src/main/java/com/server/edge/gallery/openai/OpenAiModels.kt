package com.server.edge.gallery.openai

import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<OpenAiTool>? = null,
)

@Serializable
data class ChatMessage(
    val role: String,   // "system", "user", "assistant"
    val content: String,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage? = null
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>
)

@Serializable
data class ChatChunkChoice(
    val index: Int,
    val delta: ChatDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<ModelData>
)

@Serializable
data class ModelData(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    val owned_by: String = "local"
)

// --- Tool definitions ---

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiToolFunction
)

@Serializable
data class OpenAiToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: OpenAiToolParameters? = null
)

@Serializable
data class OpenAiToolParameters(
    val type: String = "object",
    val properties: Map<String, OpenAiToolProperty>? = null,
    val required: List<String>? = null
)

@Serializable
data class OpenAiToolProperty(
    val type: String,
    val description: String? = null
)

// --- Legacy completions API ---

@Serializable
data class CompletionRequest(
    val model: String,
    val prompt: String,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: Usage? = null
)

@Serializable
data class CompletionChoice(
    val index: Int,
    val text: String,
    val finish_reason: String? = null
)

@Serializable
data class CompletionChunk(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChunkChoice>
)

@Serializable
data class CompletionChunkChoice(
    val index: Int,
    val text: String,
    val finish_reason: String? = null
)
