package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
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

                post("/v1/chat/completions") {
                    val request = call.receive<ChatCompletionRequest>()
                    handleChatCompletion(call, request)
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

            // Reset conversation and replay messages
            LlmChatModelHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemInstruction = null,
                tools = emptyList(),
                enableConversationConstrainedDecoding = false
            )

            // Last message is the prompt, others are context (simplified)
            val prompt = request.messages.lastOrNull()?.content ?: ""
            // For now, we don't fully support multi-turn replay in a single API call due to LiteRT-LM API limitations 
            // of taking one input at a time. A better way would be to send previous messages one by one if needed.
            
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

    private suspend fun runInferenceBlocking(model: Model, prompt: String): ChatCompletionResponse {
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

        val resultText = completer.await()
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

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
