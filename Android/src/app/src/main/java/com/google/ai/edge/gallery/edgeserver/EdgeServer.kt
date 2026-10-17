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
import com.google.ai.edge.gallery.runtime.aicore.EXTRA_CONTEXT_EPHEMERAL_INFERENCE
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val TAG = "EdgeServer"

/**
 * On-device HTTP server that exposes an OpenAI-compatible REST API backed by AI Edge Gallery's
 * currently loaded LiteRT-LM model.
 *
 * Adapted from open PR google-ai-edge/gallery#647, with the binding restricted to localhost only
 * (127.0.0.1 — never 0.0.0.0) and streaming dropped; only the non-streaming JSON response is
 * implemented.
 *
 * Endpoints:
 *   GET  /health              → server & model status
 *   GET  /v1/models           → the currently loaded model
 *   POST /v1/chat/completions → chat completion (non-streaming JSON)
 */
class EdgeServer(
  hostname: String = DEFAULT_HOST,
  port: Int = DEFAULT_PORT,
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : NanoHTTPD(hostname, port) {

  companion object {
    /** Localhost only. The server must never bind to a public interface. */
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8888
    const val DEFAULT_TIMEOUT_SECONDS = 300L
    private const val MIME_JSON = "application/json"

    /** NanoHTTPD 2.3.1 has no 504 status; the runtime helper needs it for inference timeouts. */
    private val STATUS_GATEWAY_TIMEOUT =
      object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 504
        override fun getDescription(): String = "Gateway Timeout"
      }
  }

  /**
   * Returns the currently loaded LLM model (with an initialized instance), or null if no model is
   * loaded. Called on server worker threads for each request. Set by [EdgeServerManager].
   */
  @Volatile var modelLookup: (() -> Model?)? = null

  private val inferenceLock = ReentrantLock()
  private val gson = Gson()

  // ───────────────────────────────────────────────────────────────────────
  // Request routing
  // ───────────────────────────────────────────────────────────────────────

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    val method = session.method

    return try {
      when {
        uri == "/health" -> handleHealth()
        uri == "/v1/models" && method == Method.GET -> handleListModels()
        uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
        method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        else ->
          errorResponse(
            Response.Status.NOT_FOUND,
            "not_found",
            "Not found: $uri",
          )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Unhandled server error", e)
      errorResponse(Response.Status.INTERNAL_ERROR, "server_error", e.message ?: "Internal error")
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /health
  // ───────────────────────────────────────────────────────────────────────

  private fun handleHealth(): Response {
    val model = lookupModel()
    val json = JsonObject().apply {
      addProperty("status", "ok")
      addProperty("model_loaded", model != null)
      addProperty("model", model?.displayName?.ifEmpty { model?.name } ?: "")
    }
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(json))
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /v1/models
  // ───────────────────────────────────────────────────────────────────────

  private fun handleListModels(): Response {
    val model = lookupModel()
    val data = JsonArray()
    if (model != null) {
      data.add(
        JsonObject().apply {
          addProperty("id", model.displayName.ifEmpty { model.name })
          addProperty("object", "model")
          addProperty("owned_by", "ai-edge-gallery")
        }
      )
    }
    val json = JsonObject().apply {
      addProperty("object", "list")
      add("data", data)
    }
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(json))
  }

  // ───────────────────────────────────────────────────────────────────────
  // POST /v1/chat/completions
  // ───────────────────────────────────────────────────────────────────────

  private fun handleChatCompletions(session: IHTTPSession): Response {
    // NanoHTTPD requires parseBody() before reading POST data; the raw body is stored under
    // the "postData" key.
    val bodyFiles = HashMap<String, String>()
    try {
      session.parseBody(bodyFiles)
    } catch (e: Exception) {
      return errorResponse(
        Response.Status.BAD_REQUEST,
        "invalid_request_error",
        "Failed to parse request body: ${e.message}",
      )
    }

    val bodyStr = bodyFiles["postData"] ?: ""
    if (bodyStr.isEmpty()) {
      return errorResponse(Response.Status.BAD_REQUEST, "invalid_request_error", "Empty request body")
    }

    val body: JsonObject =
      try {
        JsonParser.parseString(bodyStr).asJsonObject
      } catch (e: Exception) {
        return errorResponse(
          Response.Status.BAD_REQUEST,
          "invalid_request_error",
          "Invalid JSON: ${e.message}",
        )
      }

    val model = lookupModel()
    if (model == null || model.instance == null) {
      return errorResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        "server_error",
        "No model loaded. Open the Gallery app and load a model first.",
      )
    }

    val messages = body.get("messages") as? JsonArray
    if (messages == null || messages.size() == 0) {
      return errorResponse(
        Response.Status.BAD_REQUEST,
        "invalid_request_error",
        "\"messages\" array is required and must not be empty",
      )
    }

    if (body.get("stream")?.asBoolean == true) {
      return errorResponse(
        Response.Status.BAD_REQUEST,
        "invalid_request_error",
        "Streaming is not supported; set \"stream\" to false.",
      )
    }

    val prompt = buildPrompt(messages)
    val requestId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
    val modelId = model.displayName.ifEmpty { model.name }

    if (!inferenceLock.tryLock(5, TimeUnit.SECONDS)) {
      return errorResponse(
        Response.Status.TOO_MANY_REQUESTS,
        "server_error",
        "Server busy. Try again later.",
      )
    }

    // Scope tied to this request: cancelled on timeout so the native generation actually stops
    // (LlmChatModelHelper cancels the ephemeral conversation when the scope's job is cancelled)
    // instead of running on after the client got its 504.
    val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    return try {
      val result = StringBuilder()
      val latch = CountDownLatch(1)
      var errorMsg: String? = null

      model.runtimeHelper.runInference(
        model = model,
        input = prompt,
        // Run in an ephemeral conversation so HTTP requests never touch the interactive
        // chat session shown in the app UI.
        extraContext = mapOf(EXTRA_CONTEXT_EPHEMERAL_INFERENCE to "true"),
        resultListener = { partial, isDone, _ ->
          if (partial.isNotEmpty()) {
            result.append(partial)
          }
          if (isDone) {
            latch.countDown()
          }
        },
        cleanUpListener = {},
        onError = { msg ->
          errorMsg = msg
          latch.countDown()
        },
        coroutineScope = inferenceScope,
      )

      if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        inferenceScope.cancel()
        return errorResponse(
          STATUS_GATEWAY_TIMEOUT,
          "server_error",
          "Inference timed out after ${timeoutSeconds}s",
        )
      }
      if (errorMsg != null) {
        return errorResponse(
          Response.Status.INTERNAL_ERROR,
          "server_error",
          "Inference error: $errorMsg",
        )
      }

      val json = JsonObject().apply {
        addProperty("id", requestId)
        addProperty("object", "chat.completion")
        addProperty("created", System.currentTimeMillis() / 1000)
        addProperty("model", modelId)
        add(
          "choices",
          JsonArray().apply {
            add(
              JsonObject().apply {
                addProperty("index", 0)
                add(
                  "message",
                  JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", result.toString())
                  }
                )
                addProperty("finish_reason", "stop")
              }
            )
          }
        )
        add(
          "usage",
          JsonObject().apply {
            addProperty("prompt_tokens", 0)
            addProperty("completion_tokens", 0)
            addProperty("total_tokens", 0)
          }
        )
      }
      newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(json))
    } finally {
      inferenceLock.unlock()
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────────────

  private fun lookupModel(): Model? =
    try {
      modelLookup?.invoke()
    } catch (e: Exception) {
      Log.w(TAG, "Model lookup failed", e)
      null
    }

  /**
   * Flattens an OpenAI-style messages array into a single prompt string. Role labels are used
   * instead of a model-specific template; the LiteRT-LM runtime applies its own chat template.
   */
  private fun buildPrompt(messages: JsonArray): String = buildString {
    for (element in messages) {
      if (!element.isJsonObject) {
        continue
      }
      val obj = element.asJsonObject
      val role = obj.get("role")?.asString ?: "user"
      val content = extractContent(obj.get("content"))
      if (content.isNotEmpty()) {
        append("$role: $content\n\n")
      }
    }
  }

  /** Extracts text from an OpenAI `content` field (String | Array of parts | Object | null). */
  private fun extractContent(element: com.google.gson.JsonElement?): String {
    if (element == null || element.isJsonNull) {
      return ""
    }
    if (element.isJsonPrimitive) {
      return element.asString
    }
    if (element.isJsonArray) {
      return element.asJsonArray.joinToString(separator = "") { part ->
        if (part.isJsonObject) part.asJsonObject.get("text")?.asString ?: "" else ""
      }
    }
    if (element.isJsonObject) {
      return element.asJsonObject.get("text")?.asString ?: ""
    }
    return ""
  }

  private fun errorResponse(status: Response.IStatus, type: String, message: String): Response {
    val json = JsonObject().apply {
      add(
        "error",
        JsonObject().apply {
          addProperty("message", message)
          addProperty("type", type)
          addProperty("code", status.requestStatus)
        }
      )
    }
    return newFixedLengthResponse(status, MIME_JSON, gson.toJson(json))
  }
}
