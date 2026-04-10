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

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal OpenAI-compatible HTTP server backed by the on-device LLM.
 *
 * Exposed endpoints:
 * - `GET  /`                       – human readable status page
 * - `GET  /health`                 – `{"status":"ok","model":"…"}` JSON health probe
 * - `GET  /v1/models`              – OpenAI-style model listing
 * - `POST /v1/chat/completions`    – OpenAI-style chat completion (non-streaming)
 * - `POST /v1/completions`         – OpenAI-style text completion (non-streaming)
 *
 * The server is intentionally non-streaming: the client gets the full generated response once the
 * on-device inference finishes. Streaming SSE can be added later if needed.
 */
class LlmHttpServer(port: Int) : NanoHTTPD(port) {
  companion object {
    private const val TAG = "AGLlmHttpServer"
    /** Hard upper bound on how long a single inference call is allowed to take. */
    private val INFERENCE_TIMEOUT = TimeUnit.MINUTES.toMillis(5)
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri.trimEnd('/').ifEmpty { "/" }
    val method = session.method
    Log.d(TAG, "Incoming request: $method $uri")

    return try {
      when {
        method == Method.GET && (uri == "/" || uri == "") -> handleRoot()
        method == Method.GET && uri == "/health" -> handleHealth()
        method == Method.GET && uri == "/v1/models" -> handleModels()
        method == Method.POST && uri == "/v1/chat/completions" ->
          handleChatCompletions(session)
        method == Method.POST && uri == "/v1/completions" -> handleCompletions(session)
        method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        else -> jsonError(Response.Status.NOT_FOUND, "Unknown endpoint: $method $uri")
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Unhandled error while serving $method $uri", t)
      jsonError(
        Response.Status.INTERNAL_ERROR,
        "Internal server error: ${t.message ?: t.javaClass.simpleName}",
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------------------------

  private fun handleRoot(): Response {
    val model = ServerModelHolder.activeModel
    val body =
      buildString {
        append("Edge Gallery local LLM server\n")
        append("=============================\n\n")
        append("Status: running\n")
        append("Active model: ${model?.name ?: "<none loaded>"}\n\n")
        append("Endpoints:\n")
        append("  GET  /health\n")
        append("  GET  /v1/models\n")
        append("  POST /v1/chat/completions\n")
        append("  POST /v1/completions\n")
      }
    return cors(newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body))
  }

  private fun handleHealth(): Response {
    val model = ServerModelHolder.activeModel
    val json =
      JSONObject()
        .put("status", "ok")
        .put("model", model?.name ?: JSONObject.NULL)
        .put("ready", model?.instance != null)
    return jsonOk(json)
  }

  private fun handleModels(): Response {
    val model = ServerModelHolder.activeModel
    val data = JSONArray()
    if (model != null) {
      data.put(
        JSONObject()
          .put("id", model.name)
          .put("object", "model")
          .put("owned_by", "edge-gallery")
      )
    }
    val json = JSONObject().put("object", "list").put("data", data)
    return jsonOk(json)
  }

  private fun handleChatCompletions(session: IHTTPSession): Response {
    val model =
      ServerModelHolder.activeModel
        ?: return jsonError(
          Response.Status.SERVICE_UNAVAILABLE,
          "No model is loaded. Open a chat in the Edge Gallery app first so the on-device model is initialised.",
        )
    if (model.instance == null) {
      return jsonError(
        Response.Status.SERVICE_UNAVAILABLE,
        "Model '${model.name}' is not initialised yet. Please wait for it to finish loading.",
      )
    }

    val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Empty request body")
    val json =
      try {
        JSONObject(body)
      } catch (e: Exception) {
        return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON: ${e.message}")
      }

    val prompt = flattenChatMessagesToPrompt(json.optJSONArray("messages"))
    if (prompt.isBlank()) {
      return jsonError(
        Response.Status.BAD_REQUEST,
        "'messages' must contain at least one entry with a non-empty 'content'",
      )
    }

    val result = runInferenceBlocking(model, prompt)
    if (result.error != null) {
      return jsonError(Response.Status.INTERNAL_ERROR, result.error)
    }

    val responseJson = buildChatCompletionResponse(model.name, result.text)
    return jsonOk(responseJson)
  }

  private fun handleCompletions(session: IHTTPSession): Response {
    val model =
      ServerModelHolder.activeModel
        ?: return jsonError(
          Response.Status.SERVICE_UNAVAILABLE,
          "No model is loaded. Open a chat in the Edge Gallery app first.",
        )
    if (model.instance == null) {
      return jsonError(
        Response.Status.SERVICE_UNAVAILABLE,
        "Model '${model.name}' is not initialised yet.",
      )
    }

    val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Empty request body")
    val json =
      try {
        JSONObject(body)
      } catch (e: Exception) {
        return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON: ${e.message}")
      }

    val prompt =
      when (val raw = json.opt("prompt")) {
        is String -> raw
        is JSONArray ->
          buildString {
            for (i in 0 until raw.length()) {
              if (i > 0) append("\n")
              append(raw.optString(i))
            }
          }
        else -> ""
      }
    if (prompt.isBlank()) {
      return jsonError(Response.Status.BAD_REQUEST, "'prompt' must be a non-empty string or array of strings")
    }

    val result = runInferenceBlocking(model, prompt)
    if (result.error != null) {
      return jsonError(Response.Status.INTERNAL_ERROR, result.error)
    }

    val responseJson = buildTextCompletionResponse(model.name, result.text)
    return jsonOk(responseJson)
  }

  // ---------------------------------------------------------------------------------------------
  // Inference bridge
  // ---------------------------------------------------------------------------------------------

  private data class InferenceResult(val text: String, val error: String?)

  /**
   * Runs inference synchronously against [model]. The LiteRT-LM runtime is callback based and
   * streams partial results; we accumulate them into a StringBuilder and block the serving thread
   * on a [CompletableFuture] until `done=true` or an error is reported.
   */
  private fun runInferenceBlocking(model: Model, input: String): InferenceResult {
    val future = CompletableFuture<InferenceResult>()
    val buffer = StringBuilder()

    val resultListener: (String, Boolean, String?) -> Unit = { partial, done, _ ->
      if (partial.isNotEmpty() && !partial.startsWith("<ctrl")) {
        buffer.append(partial)
      }
      if (done && !future.isDone) {
        future.complete(InferenceResult(buffer.toString(), null))
      }
    }

    val cleanUpListener: () -> Unit = {
      if (!future.isDone) {
        future.complete(InferenceResult(buffer.toString(), null))
      }
    }

    val onError: (String) -> Unit = { message ->
      if (!future.isDone) {
        future.complete(InferenceResult("", message))
      }
    }

    try {
      model.runtimeHelper.runInference(
        model = model,
        input = input,
        resultListener = resultListener,
        cleanUpListener = cleanUpListener,
        onError = onError,
      )
    } catch (t: Throwable) {
      Log.e(TAG, "runInference threw synchronously", t)
      return InferenceResult("", t.message ?: t.javaClass.simpleName)
    }

    return try {
      future.get(INFERENCE_TIMEOUT, TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
      try {
        model.runtimeHelper.stopResponse(model)
      } catch (_: Throwable) {
        // best effort
      }
      InferenceResult("", "Inference failed or timed out: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Request/response helpers
  // ---------------------------------------------------------------------------------------------

  private fun readBody(session: IHTTPSession): String? {
    // NanoHTTPD's parseBody() populates `files` with the raw body under the key "postData"
    // for JSON requests.
    val files = HashMap<String, String>()
    return try {
      session.parseBody(files)
      val posted = files["postData"]
      if (!posted.isNullOrEmpty()) {
        posted
      } else {
        val single = session.parameters["postData"]?.firstOrNull()
        if (!single.isNullOrEmpty()) single else null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse request body", e)
      null
    }
  }

  /**
   * Flattens a list of OpenAI chat messages into a single prompt string that the underlying LiteRT
   * conversation can consume. The conversation already tracks prior turns, so for simplicity we
   * only forward the last user message – matching how the Gallery chat UI itself works.
   *
   * If the request also contains a `system` role, we prepend it as context so callers can still
   * steer behaviour.
   */
  private fun flattenChatMessagesToPrompt(messages: JSONArray?): String {
    if (messages == null || messages.length() == 0) return ""
    var system: String? = null
    var lastUser: String? = null
    for (i in 0 until messages.length()) {
      val msg = messages.optJSONObject(i) ?: continue
      val role = msg.optString("role", "user")
      val content = extractMessageContent(msg.opt("content"))
      when (role) {
        "system" -> if (!content.isNullOrBlank()) system = content
        "user" -> if (!content.isNullOrBlank()) lastUser = content
        else -> {
          // "assistant" / "tool" roles are already encoded in the on-device conversation history.
        }
      }
    }
    return when {
      lastUser == null && system == null -> ""
      system == null -> lastUser!!
      lastUser == null -> system
      else -> "$system\n\n$lastUser"
    }
  }

  /** OpenAI chat content may be a plain string or an array of parts. Extract a plain-text view. */
  private fun extractMessageContent(content: Any?): String? {
    return when (content) {
      null -> null
      is String -> content
      is JSONArray -> {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
          val part = content.optJSONObject(i) ?: continue
          val type = part.optString("type")
          if (type == "text" || type.isEmpty()) {
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
              if (sb.isNotEmpty()) sb.append('\n')
              sb.append(text)
            }
          }
        }
        sb.toString()
      }
      else -> content.toString()
    }
  }

  private fun buildChatCompletionResponse(modelName: String, text: String): JSONObject {
    val message = JSONObject().put("role", "assistant").put("content", text)
    val choice =
      JSONObject()
        .put("index", 0)
        .put("message", message)
        .put("finish_reason", "stop")
    val usage =
      JSONObject()
        .put("prompt_tokens", 0)
        .put("completion_tokens", text.length)
        .put("total_tokens", text.length)
    return JSONObject()
      .put("id", "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}")
      .put("object", "chat.completion")
      .put("created", System.currentTimeMillis() / 1000)
      .put("model", modelName)
      .put("choices", JSONArray().put(choice))
      .put("usage", usage)
  }

  private fun buildTextCompletionResponse(modelName: String, text: String): JSONObject {
    val choice =
      JSONObject()
        .put("index", 0)
        .put("text", text)
        .put("finish_reason", "stop")
    return JSONObject()
      .put("id", "cmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}")
      .put("object", "text_completion")
      .put("created", System.currentTimeMillis() / 1000)
      .put("model", modelName)
      .put("choices", JSONArray().put(choice))
  }

  private fun jsonOk(body: JSONObject): Response =
    cors(
      newFixedLengthResponse(
        Response.Status.OK,
        "application/json; charset=utf-8",
        body.toString(),
      )
    )

  private fun jsonError(status: Response.IStatus, message: String): Response {
    val body =
      JSONObject()
        .put(
          "error",
          JSONObject()
            .put("message", message)
            .put("type", "edge_gallery_error")
        )
    return cors(
      newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
    )
  }

  /** Adds permissive CORS headers so the endpoint can be called from a browser tab if desired. */
  private fun cors(response: Response): Response {
    response.addHeader("Access-Control-Allow-Origin", "*")
    response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    return response
  }
}
