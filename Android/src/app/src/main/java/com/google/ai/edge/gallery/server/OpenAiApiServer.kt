/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGOpenAiApiServer"

enum class OpenAiApiServerStatus {
  STOPPED,
  STARTING,
  RUNNING,
  ERROR,
}

data class OpenAiApiServerState(
  val status: OpenAiApiServerStatus = OpenAiApiServerStatus.STOPPED,
  val port: Int = 8080,
  val endpoint: String = "",
  val activeModel: String = "",
  val requestCount: Long = 0,
  val error: String = "",
)

@Singleton
class OpenAiApiServer
@Inject
constructor(@ApplicationContext private val context: Context) {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }
  private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val inferenceMutex = Mutex()
  private val _state = MutableStateFlow(OpenAiApiServerState())
  val state = _state.asStateFlow()

  @Volatile private var availableModels: Map<String, Model> = emptyMap()
  private var activeRuntimeModel: Model? = null
  private var activePublicModelName: String = ""
  private var engine: EmbeddedServer<*, *>? = null

  fun updateModels(models: List<Model>) {
    availableModels = models.associateBy { it.name }
  }

  @Synchronized
  fun start(port: Int, apiKey: String, defaultModel: String) {
    Log.i(TAG, "Starting API server on port $port...")
    if (engine != null && _state.value.port == port) {
      Log.i(TAG, "Server already running on port $port")
      return
    }
    stop()
    if (port !in 1024..65535) {
      val error = "Port must be between 1024 and 65535."
      Log.e(TAG, error)
      _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.ERROR, error = error)
      return
    }
    if (apiKey.isBlank()) {
      val error = "API key is missing."
      Log.e(TAG, error)
      _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.ERROR, error = error)
      return
    }
    if (availableModels.isEmpty()) {
      val error = "Download an LLM before starting the API server."
      Log.e(TAG, error)
      _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.ERROR, error = error)
      return
    }

    _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.STARTING, port = port)
    try {
      val address = findLanAddress()
      val host = address ?: "0.0.0.0"
      Log.i(TAG, "Creating engine on $host:$port...")
      
      val newEngine = embeddedServer(CIO, host = host, port = port) {
        configureApi(apiKey = apiKey, defaultModel = defaultModel)
      }
      Log.i(TAG, "Engine created, starting...")
      newEngine.start(wait = false)
      engine = newEngine
      val endpoint = "http://${address ?: "127.0.0.1"}:$port/v1"
      Log.i(TAG, "API server is running at $endpoint")
      _state.value =
        OpenAiApiServerState(
          status = OpenAiApiServerStatus.RUNNING,
          port = port,
          endpoint = endpoint,
          activeModel = defaultModel,
        )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start API server", e)
      engine = null
      _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.ERROR, port = port, error = e.message ?: "Unable to start server.")
    }
  }

  @Synchronized
  fun stop() {
    try {
      engine?.stop(500, 2_000)
    } catch (e: Exception) {
      Log.w(TAG, "Error while stopping API server", e)
    }
    engine = null
    val model = activeRuntimeModel
    activeRuntimeModel = null
    activePublicModelName = ""
    if (model != null) {
      serverScope.launch { model.runtimeHelper.cleanUp(model) {} }
    }
    _state.value = OpenAiApiServerState(status = OpenAiApiServerStatus.STOPPED, port = _state.value.port)
  }

  private fun Application.configureApi(apiKey: String, defaultModel: String) {
    install(ContentNegotiation) { json(json) }
    install(CORS) {
      anyHost()
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowHeader(HttpHeaders.Authorization)
      allowHeader(HttpHeaders.ContentType)
    }
    routing {
      intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod.value
        val uri = call.request.uri
        Log.d(TAG, "Incoming request: $method $uri")
        try {
          proceed()
          Log.d(TAG, "Response finished for: $method $uri")
        } catch (e: Exception) {
          Log.e(TAG, "Error processing $method $uri", e)
          throw e
        }
      }
      get("/") {
        call.respond(mapOf("status" to "ok", "api" to "OpenAI compatible", "base_url" to "/v1"))
      }
      get("/health") { call.respond(mapOf("status" to "ok")) }
      get("/v1/models") {
        if (!call.authorize(apiKey)) return@get
        val created = Instant.now().epochSecond
        call.respond(OpenAiModelList(data = availableModels.keys.sorted().map { OpenAiModel(id = it, created = created) }))
      }
      post("/v1/chat/completions") {
        if (!call.authorize(apiKey)) return@post
        try {
          val request = call.receive<OpenAiChatCompletionRequest>()
          if (request.messages.isEmpty()) {
            call.respondError(HttpStatusCode.BadRequest, "messages must not be empty", "messages")
            return@post
          }
          val modelName = request.model.ifBlank { defaultModel.ifBlank { availableModels.keys.firstOrNull().orEmpty() } }
          if (!availableModels.containsKey(modelName)) {
            call.respondError(HttpStatusCode.NotFound, "Model '$modelName' is not available on this device.", "model", "model_not_found")
            return@post
          }
          val prompt = request.toInferencePrompt()
          if (prompt.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "No supported text content was found in messages.", "messages")
            return@post
          }
          val id = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}" 
          val created = Instant.now().epochSecond
          if (request.stream) {
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
              writeSse(OpenAiChatCompletionChunk(id = id, created = created, model = modelName, choices = listOf(OpenAiChunkChoice(delta = OpenAiDelta(role = "assistant")))))
              complete(modelName = modelName, prompt = prompt) { token ->
                if (token.isNotEmpty()) {
                  writeSse(OpenAiChatCompletionChunk(id = id, created = created, model = modelName, choices = listOf(OpenAiChunkChoice(delta = OpenAiDelta(content = token)))))
                }
              }
              writeSse(OpenAiChatCompletionChunk(id = id, created = created, model = modelName, choices = listOf(OpenAiChunkChoice(delta = OpenAiDelta(), finishReason = "stop"))))
              write("data: [DONE]\n\n")
              flush()
            }
          } else {
            val responseText = complete(modelName = modelName, prompt = prompt)
            val promptTokens = estimateTokens(prompt)
            val completionTokens = estimateTokens(responseText)
            call.respond(
              OpenAiChatCompletionResponse(
                id = id,
                created = created,
                model = modelName,
                choices = listOf(OpenAiChatChoice(message = OpenAiAssistantMessage(content = responseText))),
                usage = OpenAiUsage(promptTokens, completionTokens, promptTokens + completionTokens),
              )
            )
          }
          _state.value = _state.value.copy(requestCount = _state.value.requestCount + 1, activeModel = modelName, error = "")
        } catch (e: Exception) {
          Log.e(TAG, "Completion failed", e)
          _state.value = _state.value.copy(error = e.message ?: "Inference failed")
          if (!call.response.isCommitted) {
            call.respondError(HttpStatusCode.InternalServerError, e.message ?: "Inference failed", code = "server_error")
          }
        }
      }
    }
  }

  private suspend fun complete(modelName: String, prompt: String, onToken: suspend (String) -> Unit = {}): String {
    return inferenceMutex.withLock {
      val model = ensureModel(modelName)
      model.runtimeHelper.resetConversation(model = model)
      val response = StringBuilder()
      inferenceFlow(model, prompt).collect { token ->
        response.append(token)
        onToken(token)
      }
      response.toString()
    }
  }

  private suspend fun ensureModel(publicModelName: String): Model {
    if (activeRuntimeModel != null && activePublicModelName == publicModelName) return activeRuntimeModel!!
    activeRuntimeModel?.let { previous ->
      suspendCancellableCoroutine<Unit> { continuation ->
        previous.runtimeHelper.cleanUp(previous) { if (continuation.isActive) continuation.resume(Unit) }
      }
    }
    val source = availableModels[publicModelName] ?: error("Model '$publicModelName' is unavailable.")
    val runtimeModel =
      source.copy(
        name = "${source.name}__openai_api",
        localModelFilePathOverride = source.getPath(context),
        instance = null,
        initializing = false,
        cleanUpAfterInit = false,
      )
    activeRuntimeModel = runtimeModel
    activePublicModelName = publicModelName
    suspendCancellableCoroutine<Unit> { continuation ->
      val completed = AtomicBoolean(false)
      runtimeModel.runtimeHelper.initialize(
        context = context,
        model = runtimeModel,
        taskId = BuiltInTaskId.LLM_CHAT,
        supportImage = false,
        supportAudio = false,
        coroutineScope = serverScope,
        onDone = { message ->
          if (runtimeModel.instance != null && completed.compareAndSet(false, true)) {
            if (continuation.isActive) continuation.resume(Unit)
          } else if (
            runtimeModel.instance == null &&
              (message.contains("failed", ignoreCase = true) || message.contains("unavailable", ignoreCase = true)) &&
              completed.compareAndSet(false, true)
          ) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
          }
        },
      )
      continuation.invokeOnCancellation { runtimeModel.runtimeHelper.stopResponse(runtimeModel) }
    }
    return runtimeModel
  }

  private fun inferenceFlow(model: Model, prompt: String): Flow<String> = callbackFlow {
    val finished = AtomicBoolean(false)
    model.runtimeHelper.runInference(
      model = model,
      input = prompt,
      coroutineScope = serverScope,
      resultListener = { token, done, _ ->
        if (token.isNotEmpty()) trySend(token)
        if (done && finished.compareAndSet(false, true)) close()
      },
      cleanUpListener = { if (finished.compareAndSet(false, true)) close() },
      onError = { message -> if (finished.compareAndSet(false, true)) close(IllegalStateException(message)) },
    )
    awaitClose {
      if (!finished.get()) model.runtimeHelper.stopResponse(model)
    }
  }

  private suspend fun ApplicationCall.authorize(apiKey: String): Boolean {
    val supplied = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
    if (supplied.isNotEmpty() && MessageDigest.isEqual(supplied.toByteArray(), apiKey.toByteArray())) return true
    respondError(HttpStatusCode.Unauthorized, "Invalid or missing bearer token.", code = "invalid_api_key")
    return false
  }

  private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String, param: String? = null, code: String? = null) {
    respond(status, OpenAiErrorEnvelope(OpenAiError(message = message, param = param, code = code)))
  }

  private suspend fun java.io.Writer.writeSse(chunk: OpenAiChatCompletionChunk) {
    write("data: ${json.encodeToString(chunk)}\n\n")
    flush()
  }

  private fun findLanAddress(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
          .flatMap { it.inetAddresses.toList() }
          .filterIsInstance<Inet4Address>()
          .firstOrNull { address ->
            !address.isLoopbackAddress && !address.isLinkLocalAddress && address.hostAddress?.let(::isPrivateIpv4) == true
          }
          ?.hostAddress
      }
      .getOrNull()
  }

  private fun isPrivateIpv4(value: String): Boolean {
    val octets = value.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return false
    return octets[0] == 10 ||
      (octets[0] == 172 && octets[1] in 16..31) ||
      (octets[0] == 192 && octets[1] == 168)
  }
}
