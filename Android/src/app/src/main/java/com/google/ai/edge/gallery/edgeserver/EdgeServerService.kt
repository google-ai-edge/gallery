/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.edgeserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper

private const val TAG = "EdgeServerService"
private const val CHANNEL_ID = "edge_server_channel"
private const val NOTIFICATION_ID = 19001

/**
 * Foreground Service that keeps the Edge Server HTTP API running in the
 * background so external clients can reach the on-device model at any time.
 */
class EdgeServerService : Service() {

  private var server: EdgeServer? = null
  private val binder = LocalBinder()

  inner class LocalBinder : Binder() {
    fun getService(): EdgeServerService = this@EdgeServerService
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val host = intent?.getStringExtra("host") ?: EdgeServer.DEFAULT_HOST
    val port = intent?.getIntExtra("port", EdgeServer.DEFAULT_PORT) ?: EdgeServer.DEFAULT_PORT
    startForeground(NOTIFICATION_ID, buildNotification(host, port))

    if (server == null || !server!!.isAlive) {
      server = EdgeServer(hostname = host, port = port)
      try {
        server?.start()
        Log.i(TAG, "Edge Server started on $host:$port")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start Edge Server", e)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    server?.stop()
    server = null
    Log.i(TAG, "Edge Server stopped")
    super.onDestroy()
  }

  fun setActiveModel(model: Model, helper: LlmModelHelper, displayName: String) {
    server?.activeModel = model
    server?.activeModelHelper = helper
    server?.activeModelDisplayName = displayName
    Log.i(TAG, "Model bound: $displayName")
  }

  fun clearActiveModel() {
    server?.activeModel = null
    server?.activeModelHelper = null
    server?.activeModelDisplayName = ""
  }

  fun isServerRunning(): Boolean = server?.isAlive == true
  fun getPort(): Int = server?.listeningPort ?: 0

  // ───────────────────────────────────────────────────────────────────────
  // Notification
  // ───────────────────────────────────────────────────────────────────────

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Edge Server",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Keeps the on-device AI API server running"
      }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun buildNotification(host: String, port: Int): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Edge Server")
      .setContentText("API running on $host:$port")
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
}
