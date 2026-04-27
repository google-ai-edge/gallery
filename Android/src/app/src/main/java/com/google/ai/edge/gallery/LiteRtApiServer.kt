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
        if (engine == null || currentModelName != modelName) {
            engine?.close()
            val modelPath = findModelFile(modelName)

            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(config)
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

    private fun findModelFile(modelName: String): String {
        // Search in Download folder first
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        val exactFile = File(downloadDir, "$modelName.litertlm")
        if (exactFile.exists()) {
            Log.d(TAG, "Found model in Download: ${exactFile.absolutePath}")
            return exactFile.absolutePath
        }

        // Search all files in Download for partial match
        val files = downloadDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.name.contains(modelName, ignoreCase = true) &&
                    file.name.endsWith(".litertlm")) {
                    Log.d(TAG, "Found model in Download (fuzzy): ${file.absolutePath}")
                    return file.absolutePath
                }
            }
        }

        // Also search in app's external files dir (where Gallery downloads models)
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null && extDir.exists()) {
            val found = findFileRecursive(extDir, modelName)
            if (found != null) {
                Log.d(TAG, "Found model in app files: $found")
                return found
            }
        }

        throw IllegalStateException(
            "Model '$modelName' not found. Please download it in the Gallery app first."
        )
    }

    private fun findFileRecursive(dir: File, modelName: String): String? {
        val files = dir.listFiles() ?: return null
        for (file in files) {
            if (file.isFile &&
                file.name.contains(modelName, ignoreCase = true) &&
                file.name.endsWith(".litertlm")) {
                return file.absolutePath
            }
            if (file.isDirectory) {
                val found = findFileRecursive(file, modelName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun handleListModels(): Response {
        val models = mutableListOf<String>()

        // Scan Download folder
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
