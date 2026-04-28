package com.google.ai.edge.gallery

import android.content.Context
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class LiteRtApiServer(
    private val context: Context,
    private val serverPort: Int = 8088
) : NanoHTTPD(serverPort) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

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
        try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val jsonBody = body["postData"] ?: "{}"

            val requestJson = JSONObject(jsonBody)
            val modelName = requestJson.optString("model", "")
            val messagesArray = requestJson.optJSONArray("messages") ?: JSONArray()

            val prompt = extractLastUserMessage(messagesArray)
            if (prompt.isNullOrBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    """{"error": "No user message found"}""")
            }

            // Try to find model and run inference
            val modelPath = try {
                findModelFile(modelName)
            } catch (e: Exception) {
                null
            }

            if (modelPath != null) {
                try {
                    val result = runInference(modelPath, prompt)
                    return jsonResponse(Response.Status.OK, result)
                } catch (e: Exception) {
                    Log.e(TAG, "Inference failed: ${e.message}")
                }
            }

            // Fallback placeholder
            val fallback = """
                {
                    "id": "chatcmpl-${System.currentTimeMillis()}",
                    "object": "chat.completion",
                    "created": ${System.currentTimeMillis() / 1000},
                    "model": "placeholder",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Model not loaded. Please check if model file exists in Download folder."
                        },
                        "finish_reason": "stop"
                    }]
                }
            """.trimIndent()
            return jsonResponse(Response.Status.OK, fallback)
        } catch (e: Exception) {
            Log.e(TAG, "Chat error: ${e.message}")
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                """{"error": "${e.message}")""")
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

    private fun runInference(modelPath: String, userPrompt: String): String {
        val config = com.google.ai.edge.litertlm.EngineConfig(
            modelPath = modelPath,
            backend = com.google.ai.edge.litertlm.Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val engine = com.google.ai.edge.litertlm.Engine(config)
        engine.initialize()
        val conversation = engine.createConversation()
        val response = conversation.sendMessage(userPrompt)
        val responseText = response.toString()
        conversation.close()
        engine.close()

        return """
            {
                "id": "chatcmpl-${System.currentTimeMillis()}",
                "object": "chat.completion",
                "created": ${System.currentTimeMillis() / 1000},
                "model": "gemma4",
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

    private fun findModelFile(modelName: String): String {
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        if (modelName.isNotEmpty()) {
            val exactFile = File(downloadDir, "$modelName.litertlm")
            if (exactFile.exists()) return exactFile.absolutePath
        }

        val files = downloadDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.name.endsWith(".litertlm")) {
                    return file.absolutePath
                }
            }
        }

        throw Exception("No .litertlm file found in Download folder")
    }

    private fun handleListModels(): Response {
        val models = mutableListOf<String>()
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        val files = downloadDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.name.endsWith(".litertlm")) {
                    models.add(file.nameWithoutExtension)
                }
            }
        }

        val jsonArray = JSONArray()
        for (model in models) {
            JSONObject().apply {
                put("id", model)
                put("object", "model")
                put("created", System.currentTimeMillis() / 1000)
                put("owned_by", "user")
            }.also { jsonArray.put(it) }
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
        return jsonResponse(Response.Status.OK, """{"status": "ok"}""")
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
