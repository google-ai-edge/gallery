package com.google.ai.edge.gallery

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
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
        Log.d("LiteRtApiServer", "Request received: ${session?.uri}")
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

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val modelFile = File(downloadDir, "$modelName.litertlm")
            if (!modelFile.exists()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json",
                    """{"error": "Model file not found: $modelName.litertlm"}"""
                )
            }

            val result = runBlocking(Dispatchers.IO) {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val engine = Engine(config)
                engine.initialize()
                val conversation = engine.createConversation()
                val response = conversation.sendMessage(prompt)
                val text = response.toString()
                conversation.close()
                engine.close()
                text
            }

            val responseJson = """
                {
                    "id": "chatcmpl-${System.currentTimeMillis()}",
                    "object": "chat.completion",
                    "created": ${System.currentTimeMillis() / 1000},
                    "model": "$modelName",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": ${JSONObject.quote(result)}
                        },
                        "finish_reason": "stop"
                    }]
                }
            """.trimIndent()

            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
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

    private fun handleListModels(): Response {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = downloadDir.listFiles()?.filter { it.name.endsWith(".litertlm") } ?: emptyList()
        val jsonArray = JSONArray()
        for (file in files) {
            val obj = JSONObject()
            obj.put("id", file.nameWithoutExtension)
            obj.put("object", "model")
            obj.put("created", System.currentTimeMillis() / 1000)
            obj.put("owned_by", "user")
            jsonArray.put(obj)
        }
        val response = """{"object":"list","data":$jsonArray}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private fun handleHealth(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
    }
}
