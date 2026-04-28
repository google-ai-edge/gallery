package com.google.ai.edge.gallery


import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.IOException

class LiteRtApiServer(
    port: Int = 8088
) : NanoHTTPD(port) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun startServer() {
        if (isRunning) return
        scope.launch {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                isRunning = true
                Log.d("LiteRtApiServer", "API Server started")
            } catch (e: IOException) {
                Log.e("LiteRtApiServer", "Failed: ${e.message}")
            }
        }
    }

    fun stopServer() {
        if (!isRunning) return
        stop()
        isRunning = false
        scope.cancel()
    }

    override fun serve(session: IHTTPSession?): Response {
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"status": "ok"}"""
        )
    }
}
