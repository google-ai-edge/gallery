package com.google.ai.edge.gallery.apiserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val TAG = "AGLocalApiServer"
private const val NOTIFICATION_CHANNEL_ID = "local_api_server"
private const val NOTIFICATION_ID = 4201

@AndroidEntryPoint
class LocalApiForegroundService : Service() {

  @Inject @AiChatExecutor lateinit var executor: AgentRuntimeExecutor
  @Inject lateinit var preferences: LocalApiServerPreferences

  private val requestMutex = Mutex()
  private var server: EmbeddedServer<*, *>? = null
  private val requestJson = Json { ignoreUnknownKeys = true }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val port = runBlocking { preferences.readPort() }
    val token = runBlocking { preferences.readOrCreateToken() }

    startForeground(
      NOTIFICATION_ID,
      buildNotification(port),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    try {
      server =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
              get("/v1/models") { handleListModels(call) }
              post("/v1/chat/completions") { handleChatCompletions(call, token) }
            }
          }
          .start(wait = false)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server on port $port", e)
      stopSelf()
    }

    return START_STICKY
  }

  override fun onDestroy() {
    server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    server = null
    super.onDestroy()
  }

  private suspend fun handleListModels(call: ApplicationCall) {
    val modelName = executor.activeModelInfo?.model?.name
    val ids = if (modelName != null) """[{"id":"$modelName"}]""" else "[]"
    call.respondText(contentType = ContentType.Application.Json, text = """{"data":$ids}""")
  }

  private suspend fun handleChatCompletions(call: ApplicationCall, expectedToken: String) {
    val authHeader = call.request.header("Authorization")
    if (authHeader != "Bearer $expectedToken") {
      call.respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
        text = errorResponseJson("Invalid or missing API token"),
      )
      return
    }

    val info = executor.activeModelInfo
    if (info == null) {
      call.respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.ServiceUnavailable,
        text = errorResponseJson("No model loaded in Gallery. Open the app and load a model first."),
      )
      return
    }

    val request =
      try {
        requestJson.decodeFromString(ChatCompletionRequest.serializer(), call.receiveText())
      } catch (e: Exception) {
        call.respondText(
          contentType = ContentType.Application.Json,
          status = HttpStatusCode.BadRequest,
          text = errorResponseJson("Malformed request: ${e.message}"),
        )
        return
      }

    if (request.messages.isEmpty()) {
      call.respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
        text = errorResponseJson("`messages` must not be empty"),
      )
      return
    }

    requestMutex.withLock {
      val history = request.messages.dropLast(1)
      val lastMessage = request.messages.last()

      val initialMessages =
        history.mapNotNull { msg ->
          when (msg.liteRtRole()) {
            LiteRtRole.USER -> Message.user(msg.textContent())
            LiteRtRole.MODEL -> Message.model(msg.textContent())
            null -> null
          }
        }

      executor.resetSession(
        AgentRuntimeConfig(
          model = info.model,
          taskId = info.taskId,
          supportImage = info.supportImage,
          initialMessages = initialMessages,
        )
      )

      val images = lastMessage.imageBase64Payloads().mapNotNull { decodeBase64ToBitmap(it) }
      val response =
        executor.execute(
          context = AgentExecutionContext(),
          request =
            AgentRequest(
              query = lastMessage.textContent(),
              attachments = images.map { Attachment.ImageBitmap(it) },
            ),
        )

      if (response.isSuccessful) {
        call.respondText(
          contentType = ContentType.Application.Json,
          text = chatCompletionSuccessJson(response.output),
        )
      } else {
        call.respondText(
          contentType = ContentType.Application.Json,
          status = HttpStatusCode.InternalServerError,
          text = errorResponseJson(response.output),
        )
      }
    }
  }

  private fun decodeBase64ToBitmap(base64: String): Bitmap? =
    try {
      val bytes = Base64.decode(base64, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode an image_url payload", e)
      null
    }

  private fun buildNotification(port: Int): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          "Local API server",
          NotificationManager.IMPORTANCE_LOW,
        )
      )
    }
    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Local API server running")
      .setContentText("Serving http://0.0.0.0:$port/v1")
      .setSmallIcon(android.R.drawable.ic_menu_share)
      .setOngoing(true)
      .build()
  }
}
