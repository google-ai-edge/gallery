package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.IOException

class LiteRtApiServer(
    private val context: Context,
    port: Int = 8088
) : NanoHTTPD(port) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun startServer() {
        if (isRunning) return
        scope.launch {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                isRunning = true
                Log.d("LiteRtApiServer", "API Server started on port $port")
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
                        "content": "API Server is running."
                    },
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private fun handleListModels(): Response {
        val response = """
            {
                "object": "list",
                "data": [{
                    "id": "placeholder",
                    "object": "model",
                    "created": ${System.currentTimeMillis() / 1000},
                    "owned_by": "google"
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
