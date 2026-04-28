package com.google.ai.edge.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class LiteRtApiServer(
    private val context: Context,
    port: Int = 8088
) : NanoHTTPD(port) {

    private val serverPort = port
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun startServer() {
        if (isRunning) return
        scope.launch {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                isRunning = true

                // 等待监听生效
                delay(500)

                val listenStatus = checkPortListeningByProc()
                val msg = if (listenStatus) {
                    "✅ 端口 $serverPort 监听正常 (系统确认)"
                } else {
                    "❌ 端口 $serverPort 未在系统监听！"
                }

                Log.d("LiteRtApiServer", msg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    updateNotification(msg)
                }
            } catch (e: Exception) {
                val msg = "启动异常: ${e.javaClass.simpleName} - ${e.message}"
                Log.e("LiteRtApiServer", msg, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    updateNotification(msg)
                }
            }
        }
    }

    private fun checkPortListeningByProc(): Boolean {
        try {
            val hexPort = String.format("%04X", serverPort)
            val process = Runtime.getRuntime().exec("cat /proc/net/tcp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            reader.close()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size > 3) {
                    val local = parts[1]
                    val state = parts[3]
                    val localPort = local.substringAfter(':')
                    if (localPort.equals(hexPort, ignoreCase = true) && state.equals("0A", ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiteRtApiServer", "检查端口失败: ${e.message}")
        }
        return false
    }

    private fun updateNotification(message: String) {
        try {
            val channelId = "api_server"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "API Server", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("API Server 状态")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()

            nm.notify(1, notification)
        } catch (e: Exception) {
            Log.e("LiteRtApiServer", "无法更新通知: ${e.message}")
        }
    }

    fun stopServer() {
        if (!isRunning) return
        stop()
        isRunning = false
        scope.cancel()
        Log.d("LiteRtApiServer", "服务器已停止")
    }

    override fun serve(session: IHTTPSession?): Response {
        session ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "请求为空"
        )

        return when (session.uri) {
            "/v1/chat/completions" -> handleChatCompletions(session)
            "/v1/models" -> handleListModels()
            "/health" -> handleHealth()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "未找到")
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
                        "content": "API 服务正在运行。"
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
            """{"status":"ok"}""")
    }
}
