package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGOpenAiServer"

class OpenAiServer(
    private val context: Context,
    private val modelManagerViewModel: ModelManagerViewModel
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val modelMutexes = ConcurrentHashMap<String, Mutex>()

    fun start(port: Int = 8080) {
        if (server != null) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
            }

            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok"))
                }

                get("/v1/models") {
                    val models = modelManagerViewModel.uiState.value.tasks
                        .flatMap { it.models }
                        .filter { it.runtimeType == RuntimeType.LITERT_LM && it.instance != null }
                        .distinctBy { it.name }
                        .map { ModelData(id = it.name, created = System.currentTimeMillis() / 1000) }
                    
                    call.respond(ModelsListResponse(data = models))
                }

                get("/v1/models/{modelId}") {
                    val modelId = call.parameters["modelId"]
                    val model = modelManagerViewModel.uiState.value.tasks
                        .flatMap { it.models }
                        .find { it.name == modelId && it.runtimeType == RuntimeType.LITERT_LM && it.instance != null }

                    if (model == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found or not initialized"))
                    } else {
                        call.respond(ModelData(id = model.name, created = System.currentTimeMillis() / 1000))
                    }
                }

                post("/v1/chat/completions") {
                    val request = call.receive<ChatCompletionRequest>()
                    handleChatCompletion(call, request)
                }

                post("/v1/completions") {
                    val request = call.receive<CompletionRequest>()
                    handleCompletion(call, request)
                }
            }
        }.start(wait = false)
        Log.i(TAG, "OpenAI API Server started on port $port")
    }

    private suspend fun handleChatCompletion(call: ApplicationCall, request: ChatCompletionRequest) {
        val model = modelManagerViewModel.uiState.value.tasks
            .flatMap { it.models }
            .find { it.name == request.model }

        if (model == null || model.instance == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found or not initialized"))
            return
        }

        val mutex = modelMutexes.getOrPut(model.name) { Mutex() }
        
        if (mutex.isLocked) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
            return
        }

        mutex.withLock {
            // Apply parameters
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
            request.max_tokens?.let { model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to it) }

            // Warn if tools are provided (not yet supported via API)
            if (!request.tools.isNullOrEmpty()) {
                Log.w(TAG, "Tools provided in API request but not yet supported. Ignoring.")
            }

            // Parse messages for system instruction and multi-turn context
            val systemMessages = request.messages.filter { it.role == "system" }
            val systemInstruction = if (systemMessages.isNotEmpty()) {
                Contents.of(Content.Text(systemMessages.joinToString("\n") { it.content }))
            } else null

            val conversationMessages = request.messages.filter { it.role != "system" }

            if (conversationMessages.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No user/assistant messages provided"))
                return@withLock
            }

            // Reset conversation with system instruction
            LlmChatModelHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemInstruction = systemInstruction,
                tools = emptyList(),
                enableConversationConstrainedDecoding = false
            )

            // Replay prior messages to build up conversation context
            for (i in 0 until conversationMessages.size - 1) {
                val msg = conversationMessages[i]
                when (msg.role) {
                    "user" -> {
                        try {
                            runInferenceBlockingText(model, msg.content)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to replay message history", e)
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to build conversation context: ${e.message}"))
                            return@withLock
                        }
                    }
                    "assistant" -> {
                        // LiteRT-LM does not support injecting assistant messages directly into
                        // conversation history. The model's generated responses from prior turns
                        // are used instead. This is a known limitation.
                        Log.w(TAG, "Skipping assistant message in context replay (not supported by LiteRT-LM)")
                    }
                    else -> {
                        Log.w(TAG, "Unknown role '${msg.role}' in message history, skipping")
                    }
                }
            }

            val lastMessage = conversationMessages.last()
            if (lastMessage.role != "user") {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Last message must be from user"))
                return@withLock
            }

            val prompt = lastMessage.content

            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    runInferenceStreaming(this, model, prompt)
                }
            } else {
                val response = runInferenceBlocking(model, prompt)
                call.respond(response)
            }
        }
    }

    private suspend fun handleCompletion(call: ApplicationCall, request: CompletionRequest) {
        val model = modelManagerViewModel.uiState.value.tasks
            .flatMap { it.models }
            .find { it.name == request.model }

        if (model == null || model.instance == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found or not initialized"))
            return
        }

        val mutex = modelMutexes.getOrPut(model.name) { Mutex() }
        
        if (mutex.isLocked) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
            return
        }

        mutex.withLock {
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
            request.max_tokens?.let { model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to it) }

            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    runCompletionStreaming(this, model, request.prompt)
                }
            } else {
                val responseText = runInferenceBlockingText(model, request.prompt)
                call.respond(CompletionResponse(
                    id = "cmpl-" + UUID.randomUUID().toString(),
                    created = System.currentTimeMillis() / 1000,
                    model = model.name,
                    choices = listOf(
                        CompletionChoice(
                            index = 0,
                            text = responseText,
                            finish_reason = "stop"
                        )
                    )
                ))
            }
        }
    }

    private suspend fun runInferenceBlockingText(model: Model, prompt: String): String {
        val completer = CompletableDeferred<String>()
        val fullResponse = StringBuilder()

        LlmChatModelHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { text, done, thought ->
                if (done) {
                    completer.complete(fullResponse.toString())
                } else {
                    fullResponse.append(text)
                }
            },
            cleanUpListener = {},
            onError = { completer.completeExceptionally(Exception(it)) },
            images = emptyList(),
            audioClips = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )

        return completer.await()
    }

    private suspend fun runInferenceBlocking(model: Model, prompt: String): ChatCompletionResponse {
        val resultText = runInferenceBlockingText(model, prompt)
        return ChatCompletionResponse(
            id = "chatcmpl-" + UUID.randomUUID().toString(),
            created = System.currentTimeMillis() / 1000,
            model = model.name,
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = resultText),
                    finish_reason = "stop"
                )
            )
        )
    }

    private suspend fun runInferenceStreaming(writer: ByteWriteChannel, model: Model, prompt: String) {
        val id = "chatcmpl-" + UUID.randomUUID().toString()
        val created = System.currentTimeMillis() / 1000
        val completer = CompletableDeferred<Unit>()

        LlmChatModelHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { text, done, thought ->
                runBlocking {
                    if (done) {
                        writer.writeStringUtf8("data: [DONE]\n\n")
                        writer.flush()
                        completer.complete(Unit)
                    } else {
                        val chunk = ChatCompletionChunk(
                            id = id,
                            created = created,
                            model = model.name,
                            choices = listOf(
                                ChatChunkChoice(
                                    index = 0,
                                    delta = ChatDelta(content = text)
                                )
                            )
                        )
                        writer.writeStringUtf8("data: ${Json.encodeToString(chunk)}\n\n")
                        writer.flush()
                    }
                }
            },
            cleanUpListener = {},
            onError = { 
                runBlocking {
                    writer.writeStringUtf8("data: {\"error\": \"$it\"}\n\n")
                    writer.writeStringUtf8("data: [DONE]\n\n")
                    writer.flush()
                }
                completer.completeExceptionally(Exception(it)) 
            },
            images = emptyList(),
            audioClips = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        completer.await()
    }

    private suspend fun runCompletionStreaming(writer: ByteWriteChannel, model: Model, prompt: String) {
        val id = "cmpl-" + UUID.randomUUID().toString()
        val created = System.currentTimeMillis() / 1000
        val completer = CompletableDeferred<Unit>()

        LlmChatModelHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { text, done, thought ->
                runBlocking {
                    if (done) {
                        writer.writeStringUtf8("data: [DONE]\n\n")
                        writer.flush()
                        completer.complete(Unit)
                    } else {
                        val chunk = CompletionChunk(
                            id = id,
                            created = created,
                            model = model.name,
                            choices = listOf(
                                CompletionChunkChoice(
                                    index = 0,
                                    text = text
                                )
                            )
                        )
                        writer.writeStringUtf8("data: ${Json.encodeToString(chunk)}\n\n")
                        writer.flush()
                    }
                }
            },
            cleanUpListener = {},
            onError = { 
                runBlocking {
                    writer.writeStringUtf8("data: {\"error\": \"$it\"}\n\n")
                    writer.writeStringUtf8("data: [DONE]\n\n")
                    writer.flush()
                }
                completer.completeExceptionally(Exception(it)) 
            },
            images = emptyList(),
            audioClips = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        completer.await()
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
