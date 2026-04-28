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
                Log.d("LiteRtApiServer", "API Server started on port $serverPort")
            } catch (e: IOException) {
                Log.e("LiteRtApiServer", "Failed to start server: ${e.message}")
            }
        }
    }

    fun stopServer() {
        if (!isRunning) return
        stop()
        isRunning = false
        scope.cancel()
        Log.d("LiteRtApiServer", "API Server stopped")
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
            val modelName = requestJson.optString("model", "gemma-4-E2B-it")
            val messagesArray = requestJson.optJSONArray("messages") ?: JSONArray()

            val prompt = extractLastUserMessage(messagesArray)
            if (prompt.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error": "No user message found"}"""
                )
            }

            // Find model file
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val modelFile = File(downloadDir, "$modelName.litertlm")
            if (!modelFile.exists()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json",
                    """{"error": "Model file not found: $modelName.litertlm"}"""
                )
            }

            // Run inference
            val result = runInference(modelFile.absolutePath, prompt)

            newFixedLengthResponse(Response.Status.OK, "application/json", result)
        } catch (e: Exception) {
            Log.e("LiteRtApiServer", "Inference error: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error": "${e.message?.replace("\"", "\\\"")}"}"""
            )
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
        val engineConfig = com.google.ai.edge.litertlm.EngineConfig(
            modelPath = modelPath,
            backend = com.google.ai.edge.litertlm.Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val engine = com.google.ai.edge.litertlm.Engine(engineConfig)
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
                "model": "gemma-4-E2B-it",
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

    private fun handleListModels(): Response {
        val response = """
            {
                "object": "list",
                "data": [{
                    "id": "gemma-4-E2B-it",
                    "object": "model",
                    "created": ${System.currentTimeMillis() / 1000},
                    "owned_by": "user"
                }]
            }
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private fun handleHealth(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"status": "ok"}""")
    }
}
