package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LiteRtApiServer(
    private val context: Context,
    private val serverPort: Int = 8088
) : NanoHTTPD(serverPort) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var engine: Engine? = null

    fun startServer() {
        if (isRunning) return
        scope.launch {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                isRunning = true
                Log.d(TAG, "API Server started on port $serverPort")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server: ${e.message}")
            }
        }
    }

    fun stopServer() {
        if (!isRunning) return
        stop()
        isRunning = false
        engine?.close()
        engine = null
        scope.cancel()
        Log.d(TAG, "API Server stopped")
    }

    override fun serve(session: IHTTPSession?): Response {
        session ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty request"
        )

        return when (session.uri) {
            "/v1/chat/completions" -> handleChatCompletions(session)
            "/v1/models" -> handleListModels()
            "/health" -> handleHealth()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        return try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val jsonBody = body["postData"] ?: "{}"

            val requestJson = JSONObject(jsonBody)
            val modelName = requestJson.optString("model", "gemma3-1b-it")
            val messagesArray = requestJson.optJSONArray("messages") ?: JSONArray()

            // Extract the last user message as the prompt
            val prompt = extractLastUserMessage(messagesArray)
            if (prompt.isNullOrBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    """{"error": "No user message found"}""")
            }

            // Run inference
            val result = runInference(modelName, prompt)

            jsonResponse(Response.Status.OK, result)
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion error: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR,
                """{"error": "${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    private fun extractLastUserMessage(messages: JSONArray): String? {
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") == "user") {
                return msg.optString("content", "")
            }
        }
        return null
    }

    private fun runInference(modelName: String, userPrompt: String): String {
        // Determine model path based on model name
        // We rely on Gallery's built-in model manager to get the actual path
        val modelPath = getModelPath(modelName)

        // Create or reuse engine
        if (engine == null) {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(engineConfig)
            engine?.initialize()
        }

        val conversation = engine!!.createConversation()
        val response = conversation.sendMessage(userPrompt)
        conversation.close()

        val responseText = response.toString()

        return """
            {
                "id": "chatcmpl-${System.currentTimeMillis()}",
                "object": "chat.completion",
                "created": ${System.currentTimeMillis() / 1000},
                "model": "$modelName",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": ${JSONObject.quote(responseText)}
                    },
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()
    }

    private fun getModelPath(modelName: String): String {
        // Gallery stores models in the app's files directory
        val modelsDir = context.filesDir.resolve("models")
        val modelFile = modelsDir.resolve("$modelName.litertlm")
        if (modelFile.exists()) {
            return modelFile.absolutePath
        }
        // Fallback: try common locations
        val fallbackFile = context.filesDir.resolve("$modelName.litertlm")
        if (fallbackFile.exists()) {
            return fallbackFile.absolutePath
        }
        throw IllegalStateException(
            "Model '$modelName' not found. Please download it first in Gallery App (Models tab)."
        )
    }

    private fun handleListModels(): Response {
        val response = """
            {
                "object": "list",
                "data": [
                    {
                        "id": "gemma3-1b-it",
                        "object": "model",
                        "created": ${System.currentTimeMillis() / 1000},
                        "owned_by": "google"
                    },
                    {
                        "id": "gemma3-4b-it",
                        "object": "model",
                        "created": ${System.currentTimeMillis() / 1000},
                        "owned_by": "google"
                    },
                    {
                        "id": "qwen3-0.6b",
                        "object": "model",
                        "created": ${System.currentTimeMillis() / 1000},
                        "owned_by": "litert-community"
                    }
                ]
            }
        """.trimIndent()

        return jsonResponse(Response.Status.OK, response)
    }

    private fun handleHealth(): Response {
        return jsonResponse(Response.Status.OK, """{"status": "ok"}""")
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
