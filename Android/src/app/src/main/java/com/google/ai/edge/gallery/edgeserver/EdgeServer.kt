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

package com.google.ai.edge.gallery.edgeserver

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "EdgeServer"

/**
 * On-device HTTP server that exposes an OpenAI-compatible REST API backed by
 * AI Edge Gallery's GPU-accelerated LLM inference.
 *
 * This allows any OpenAI-compatible client (curl, Open WebUI, custom scripts,
 * etc.) to interact with on-device models over localhost without cloud access.
 *
 * Endpoints:
 *   GET  /health                  → server & model status
 *   GET  /v1/models               → list loaded models
 *   POST /v1/chat/completions     → chat completions (streaming & non-streaming)
 */
class EdgeServer(
  hostname: String = DEFAULT_HOST,
  port: Int = DEFAULT_PORT,
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : NanoHTTPD(hostname, port) {

  companion object {
    const val DEFAULT_HOST = "0.0.0.0"
    const val DEFAULT_PORT = 8888
    const val DEFAULT_TIMEOUT_SECONDS = 300L
    private const val MIME_JSON = "application/json"
  }

  /** The active model, set via [EdgeServerManager.bindModel]. */
  @Volatile var activeModel: Model? = null
  @Volatile var activeModelHelper: LlmModelHelper? = null
  @Volatile var activeModelDisplayName: String = ""

  /**
   * Optional callback invoked when a request arrives but no model is bound.
   * The callback should attempt to discover and initialize a downloaded model.
   */
  @Volatile var modelFinder: (() -> Unit)? = null

  private val inferenceLock = ReentrantLock()
  private val gson = Gson()

  // ───────────────────────────────────────────────────────────────────────
  // Request routing
  // ───────────────────────────────────────────────────────────────────────

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri ?: ""
    val method = session.method

    // Auto-discover model if not yet bound.
    if (activeModel?.instance == null) {
      tryAutoDiscoverModel()
    }

    return try {
      when {
        uri == "/health" -> handleHealth()
        uri == "/v1/models" && method == Method.GET -> handleListModels()
        uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
        method == Method.OPTIONS -> newFixedLengthResponse(
          Response.Status.OK, MIME_PLAINTEXT, ""
        ).applyCors()
        else -> newFixedLengthResponse(
          Response.Status.NOT_FOUND, MIME_JSON,
          """{"error":{"message":"Not found: $uri","type":"invalid_request_error"}}"""
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Unhandled server error", e)
      errorResponse(500, e.message ?: "Internal server error")
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /health
  // ───────────────────────────────────────────────────────────────────────

  private fun handleHealth(): Response {
    val loaded = activeModel?.instance != null
    val json = """{"status":"ok","model_loaded":$loaded,"model":"$activeModelDisplayName"}"""
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /v1/models
  // ───────────────────────────────────────────────────────────────────────

  private fun handleListModels(): Response {
    val model = activeModel
    val body = if (model != null) {
      val obj = JsonObject().apply {
        addProperty("id", activeModelDisplayName.ifEmpty { model.name })
        addProperty("object", "model")
        addProperty("owned_by", "ai-edge-gallery")
      }
      """{"object":"list","data":[${gson.toJson(obj)}]}"""
    } else {
      """{"object":"list","data":[]}"""
    }
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, body).applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // POST /v1/chat/completions
  // ───────────────────────────────────────────────────────────────────────

  private fun handleChatCompletions(session: IHTTPSession): Response {
    // NanoHTTPD requires parseBody() before reading POST data.
    val bodyFiles = HashMap<String, String>()
    try {
      session.parseBody(bodyFiles)
    } catch (e: Exception) {
      return errorResponse(400, "Failed to parse request body: ${e.message}")
    }

    val bodyStr = bodyFiles["postData"] ?: ""
    if (bodyStr.isEmpty()) {
      return errorResponse(400, "Empty request body")
    }

    val body: JsonObject = try {
      val reader = JsonReader(java.io.StringReader(bodyStr))
      reader.isLenient = true
      JsonParser.parseReader(reader).asJsonObject
    } catch (e: Exception) {
      return errorResponse(400, "Invalid JSON: ${e.message}")
    }

    val model = activeModel
    val helper = activeModelHelper
    if (model == null || helper == null || model.instance == null) {
      return errorResponse(503, "No model loaded. Open the Gallery app and load a model first.")
    }

    val messages = body.getAsJsonArray("messages")
    if (messages == null || messages.size() == 0) {
      return errorResponse(400, "\"messages\" array is required and must not be empty")
    }

    val prompt = buildPrompt(messages)
    val stream = body.get("stream")?.asBoolean ?: false
    val requestId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
    val modelId = activeModelDisplayName.ifEmpty { model.name }

    return if (stream) {
      handleStreamingResponse(model, helper, prompt, requestId, modelId)
    } else {
      handleNonStreamingResponse(model, helper, prompt, requestId, modelId)
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Prompt builder
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Converts an OpenAI-style messages array into a Gemma prompt string.
   *
   * Handles three `content` shapes:
   *  - String literal
   *  - Array of `{"type":"text","text":"..."}` (multi-modal format)
   *  - Single object with a `text` field
   */
  private fun buildPrompt(messages: com.google.gson.JsonArray): String = buildString {
    for (el in messages) {
      val obj = el.asJsonObject
      val role = obj.get("role")?.asString ?: "user"
      val content = extractContent(obj.get("content"))
      if (content.isNotEmpty()) {
        append("<start_of_turn>$role\n$content<end_of_turn>\n")
      }
    }
    append("<start_of_turn>model\n")
  }

  /** Extracts text from an OpenAI `content` field (String | Array | Object | null). */
  private fun extractContent(element: com.google.gson.JsonElement?): String {
    if (element == null || element.isJsonNull) return ""
    if (element.isJsonPrimitive) return element.asString
    if (element.isJsonArray) {
      return buildString {
        for (part in element.asJsonArray) {
          if (part.isJsonObject) {
            part.asJsonObject.get("text")?.asString?.let { append(it) }
          } else if (part.isJsonPrimitive) {
            append(part.asString)
          }
        }
      }
    }
    if (element.isJsonObject) {
      return element.asJsonObject.get("text")?.asString ?: element.toString()
    }
    return ""
  }

  // ───────────────────────────────────────────────────────────────────────
  // Streaming response (SSE)
  // ───────────────────────────────────────────────────────────────────────

  private fun handleStreamingResponse(
    model: Model, helper: LlmModelHelper, prompt: String,
    requestId: String, modelId: String,
  ): Response {
    if (!inferenceLock.tryLock(5, TimeUnit.SECONDS)) {
      return errorResponse(429, "Server busy. Try again later.")
    }

    val pipedOut = PipedOutputStream()
    val pipedIn = PipedInputStream(pipedOut, 64 * 1024)
    val done = AtomicBoolean(false)

    Thread {
      try {
        val latch = CountDownLatch(1)
        helper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, isDone, _ ->
            try {
              if (partial.isNotEmpty()) {
                val chunk = sseChunk(requestId, modelId, partial, null)
                pipedOut.write("data: $chunk\n\n".toByteArray())
                pipedOut.flush()
              }
              if (isDone) {
                pipedOut.write("data: ${sseChunk(requestId, modelId, "", "stop")}\n\n".toByteArray())
                pipedOut.write("data: [DONE]\n\n".toByteArray())
                pipedOut.flush()
                done.set(true)
                latch.countDown()
              }
            } catch (e: Exception) {
              Log.e(TAG, "SSE write error", e)
              done.set(true); latch.countDown()
            }
          },
          cleanUpListener = { if (!done.get()) { done.set(true); latch.countDown() } },
          onError = { msg ->
            try {
              pipedOut.write("data: {\"error\":{\"message\":\"${escapeJson(msg)}\"}}\n\n".toByteArray())
              pipedOut.write("data: [DONE]\n\n".toByteArray())
              pipedOut.flush()
            } catch (_: Exception) {}
            done.set(true); latch.countDown()
          },
        )
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Log.e(TAG, "Inference error", e)
      } finally {
        try { pipedOut.close() } catch (_: Exception) {}
        inferenceLock.unlock()
      }
    }.start()

    return newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn).apply {
      addHeader("Cache-Control", "no-cache")
      addHeader("Connection", "keep-alive")
    }.applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // Non-streaming response
  // ───────────────────────────────────────────────────────────────────────

  private fun handleNonStreamingResponse(
    model: Model, helper: LlmModelHelper, prompt: String,
    requestId: String, modelId: String,
  ): Response {
    if (!inferenceLock.tryLock(5, TimeUnit.SECONDS)) {
      return errorResponse(429, "Server busy. Try again later.")
    }
    try {
      val result = StringBuilder()
      val latch = CountDownLatch(1)
      var errorMsg: String? = null

      helper.runInference(
        model = model, input = prompt,
        resultListener = { partial, isDone, _ ->
          if (partial.isNotEmpty()) result.append(partial)
          if (isDone) latch.countDown()
        },
        cleanUpListener = { latch.countDown() },
        onError = { msg -> errorMsg = msg; latch.countDown() },
      )

      if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        return errorResponse(504, "Inference timed out after ${timeoutSeconds}s")
      }
      if (errorMsg != null) {
        return errorResponse(500, "Inference error: $errorMsg")
      }

      val json = buildString {
        append("""{"id":"$requestId","object":"chat.completion",""")
        append(""""created":${System.currentTimeMillis() / 1000},"model":"$modelId",""")
        append(""""choices":[{"index":0,"message":{"role":"assistant",""")
        append(""""content":"${escapeJson(result.toString())}"},"finish_reason":"stop"}],""")
        append(""""usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""")
      }
      return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).applyCors()
    } finally {
      inferenceLock.unlock()
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────────────

  private fun tryAutoDiscoverModel() {
    val finder = modelFinder ?: return
    Log.i(TAG, "No model bound — invoking modelFinder...")
    finder.invoke()
    // Wait up to 90s for model initialization (GPU init can be slow).
    var waited = 0L
    while (activeModel?.instance == null && waited < 90_000L) {
      Thread.sleep(2_000L)
      waited += 2_000L
      if (waited % 10_000L == 0L) Log.i(TAG, "Waiting for model init... (${waited / 1000}s)")
    }
    if (activeModel?.instance != null) {
      Log.i(TAG, "Model available after ${waited / 1000}s")
    } else {
      Log.w(TAG, "Model not available after ${waited / 1000}s")
    }
  }

  private fun sseChunk(id: String, model: String, content: String, finishReason: String?): String {
    val delta = if (content.isNotEmpty()) """{"content":"${escapeJson(content)}"}""" else "{}"
    val reason = if (finishReason != null) "\"$finishReason\"" else "null"
    return """{"id":"$id","object":"chat.completion.chunk","created":${System.currentTimeMillis() / 1000},"model":"$model","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}]}"""
  }

  private fun escapeJson(s: String): String = s
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

  private fun errorResponse(code: Int, message: String): Response {
    val status = when (code) {
      400 -> Response.Status.BAD_REQUEST
      404 -> Response.Status.NOT_FOUND
      429 -> Response.Status.lookup(429) ?: Response.Status.INTERNAL_ERROR
      503 -> Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR
      504 -> Response.Status.lookup(504) ?: Response.Status.INTERNAL_ERROR
      else -> Response.Status.INTERNAL_ERROR
    }
    val json = """{"error":{"message":"${escapeJson(message)}","type":"server_error","code":$code}}"""
    return newFixedLengthResponse(status, MIME_JSON, json).applyCors()
  }

  private fun Response.applyCors(): Response {
    addHeader("Access-Control-Allow-Origin", "*")
    addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    return this
  }
}
