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
        val response = """
            {
                "id": "chatcmpl-${System.currentTimeMillis()}",
                "object": "chat.completion",
                "created": ${System.currentTimeMillis() / 1000},
                "model": "placeholder",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "API Server is running. Real inference coming soon."
                    },
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private fun handleListModels(): Response {
        val models = mutableListOf<String>()

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan models: ${e.message}")
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

        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private fun handleHealth(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"status": "ok"}""")
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
