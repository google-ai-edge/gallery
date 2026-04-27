package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
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
    private var modelManagerViewModel: ModelManagerViewModel? = null

    fun setModelManager(viewModel: ModelManagerViewModel) {
        this.modelManagerViewModel = viewModel
    }

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
            val modelId = requestJson.optString("model", "litert-community/gemma-4-E2B-it-litert-lm")
            val messagesArray = requestJson.optJSONArray("messages") ?: JSONArray()

            val prompt = extractLastUserMessage(messagesArray)
            if (prompt.isNullOrBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    """{"error": "No user message found"}""")
            }

            val result = runInference(modelId, prompt)

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

    private suspend fun runInference(modelId: String, userPrompt: String): String {
        val viewModel = modelManagerViewModel
            ?: throw IllegalStateException("ModelManager not initialized")

        // Use Gallery's built-in inference API
        val result = withContext(Dispatchers.IO) {
            viewModel.runChatInference(modelId, userPrompt)
        }

        return """
            {
                "id": "chatcmpl-${System.currentTimeMillis()}",
                "object": "chat.completion",
                "created": ${System.currentTimeMillis() / 1000},
                "model": "$modelId",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": ${JSONObject.quote(result ?: "(empty response)")}
                    },
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()
    }

    private fun handleListModels(): Response {
        val viewModel = modelManagerViewModel
        val models = viewModel?.getAvailableModels() ?: emptyList()

        val jsonArray = JSONArray()
        for (model in models) {
            val obj = JSONObject()
            obj.put("id", model)
            obj.put("object", "model")
            obj.put("created", System.currentTimeMillis() / 1000)
            obj.put("owned_by", "user")
            jsonArray.put(obj)
        }

        val response = """
            {
                "object": "list",
                "data": $jsonArray
            }
        """.trimIndent()

        return jsonResponse(Response.Status.OK, response)
    }

    private fun handleHealth(): Response {
        return jsonResponse(Response.Status.OK,
            """{"status": "ok"}""")
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
