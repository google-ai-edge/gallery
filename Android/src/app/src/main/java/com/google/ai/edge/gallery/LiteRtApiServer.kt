package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import android.widget.Toast
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket

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

                // 验证端口是否真的在监听
                val listenOk = isPortListening(serverPort)
                val msg = if (listenOk) {
                    "服务器已启动，端口 $serverPort 监听正常"
                } else {
                    "服务器已启动，但端口 $serverPort 监听验证失败，可能被防火墙拦截"
                }
                Log.d("LiteRtApiServer", msg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: BindException) {
                val msg = "端口 $serverPort 被占用，启动失败"
                Log.e("LiteRtApiServer", msg, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                val msg = "网络异常: ${e.message}"
                Log.e("LiteRtApiServer", msg, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val msg = "未知错误: ${e.message}"
                Log.e("LiteRtApiServer", msg, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isPortListening(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (e: Exception) {
            false
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
