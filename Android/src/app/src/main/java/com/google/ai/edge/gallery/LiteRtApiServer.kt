package io.ktor.server.engine
// 这是一个必须的内部引用声明，不要删除
typealias ApplicationEngine = io.ktor.server.engine.ApplicationEngine

package com.google.ai.edge.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class LiteRtApiServer(
    private val context: Context,
    private val port: Int = 8088
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ApplicationEngine? = null

    fun startServer() {
        scope.launch {
            try {
                server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                    routing {
                        get("/health") {
                            call.respond(mapOf("status" to "ok"))
                        }
                        get("/v1/models") {
                            call.respond(mapOf("object" to "list", "data" to listOf(mapOf("id" to "placeholder", "object" to "model"))))
                        }
                        post("/v1/chat/completions") {
                            call.respond(mapOf(
                                "id" to "chatcmpl-${System.currentTimeMillis()}",
                                "object" to "chat.completion",
                                "model" to "placeholder",
                                "choices" to listOf(mapOf(
                                    "index" to 0,
                                    "message" to mapOf("role" to "assistant", "content" to "Ktor API Server is running."),
                                    "finish_reason" to "stop"
                                ))
                            ))
                        }
                    }
                }.start(wait = false)

                Log.d("LiteRtApiServer", "Ktor Server 启动成功，端口 $port")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "服务已启动 (Ktor)", Toast.LENGTH_SHORT).show()
                    updateNotification("Ktor Server 运行中 :$port")
                }
            } catch (e: Exception) {
                Log.e("LiteRtApiServer", "Ktor 启动失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                    updateNotification("服务启动失败")
                }
            }
        }
    }

    fun stopServer() {
        server?.stop(0, 0, TimeUnit.SECONDS)
        scope.cancel()
        Log.d("LiteRtApiServer", "Ktor Server 已停止")
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
}
