package com.google.ai.edge.gallery

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.*
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
    private var engine: Engine? = null
    private var currentModelName = ""

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
            val modelName = requestJson.optString("model", "gemma-4-E2B-it")
            val messagesArray = requestJson.optJSONArray("messages") ?: JSONArray()

            val prompt = extractLastUserMessage(messagesArray)
            if (prompt.isNullOrBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    """{"error": "No user message found"}""")
            }

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
        val modelPath = findModelPath(modelName)

        if (engine == null || currentModelName != modelName) {
            engine?.close()

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(engineConfig)
            engine?.initialize()
            currentModelName = modelName
        }

        val conversation = engine!!.createConversation()
        val response = conversation.sendMessage(userPrompt)
        val responseText = response.toString()
        conversation.close()

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

    private fun findModelPath(modelName: String): String {
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        val exactFile = File(downloadDir, "$modelName.litertlm")
        if (exactFile.exists()) {
            return exactFile.absolutePath
        }

        val files = downloadDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.name.contains(modelName, ignoreCase = true) &&
                    file.name.endsWith(".litertlm")
                ) {
                    return file.absolutePath
                }
            }
        }

        throw IllegalStateException(
            "Model '$modelName' not found in Download folder. Please keep the .litertlm file in your Download directory."
        )
    }

    private fun handleListModels(): Response {
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val models = mutableListOf<String>()

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
            """{"status": "ok", "models_loaded": ${engine != null}}""")
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
