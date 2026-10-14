/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class EvalService : Service() {
  private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var server: EvalServer? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    Log.i(TAG, "onStartCommand action: $action")

    if (action == ACTION_STOP_SERVER) {
      stopServer()
      stopSelf()
      return START_NOT_STICKY
    }

    // Extract optional model configuration from the intent to pre-initialize
    // the model at startup, aligning behavior with host-side runners.
    val port = intent?.getIntExtra("port", 8080) ?: 8080
    val modelPath = intent?.getStringExtra("model_path")
    val supportImage = intent?.getBooleanExtra("support_image", false) ?: false
    val supportAudio = intent?.getBooleanExtra("support_audio", false) ?: false
    val accelerator = intent?.getStringExtra("accelerator") ?: "CPU"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        createNotification(port),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, createNotification(port))
    }

    if (action == ACTION_START_SERVER || action == null) {
      startServer(port, modelPath, supportImage, supportAudio, accelerator)
    }

    return START_STICKY
  }

  private fun startServer(
    port: Int,
    modelPath: String?,
    supportImage: Boolean,
    supportAudio: Boolean,
    accelerator: String,
  ) {
    if (server != null) {
      Log.i(TAG, "Server already running")
      return
    }
    try {
      server = EvalServer(port, this, serviceScope)
      server?.start()
      Log.i(TAG, "Server started on port $port")
      if (modelPath != null) {
        server?.preInitModel(modelPath, supportImage, supportAudio, accelerator)
      }
    } catch (e: IOException) {
      Log.e(TAG, "Failed to start server", e)
    }
  }

  private fun stopServer() {
    server?.stop()
    server = null
    Log.i(TAG, "Server stopped")
  }

  override fun onDestroy() {
    stopServer()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(CHANNEL_ID, "Eval Service Channel", NotificationManager.IMPORTANCE_LOW)
    val manager = getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(channel)
  }

  private fun createNotification(port: Int): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Gallery Eval Server")
      .setContentText("Running on port $port")
      .setSmallIcon(android.R.drawable.ic_media_play)
      .build()
  }

  companion object {
    private const val TAG = "EvalService"
    private const val CHANNEL_ID = "EvalServiceChannel"
    private const val NOTIFICATION_ID = 1

    const val ACTION_START_SERVER = "com.google.ai.edge.gallery.eval.START_SERVER"
    const val ACTION_STOP_SERVER = "com.google.ai.edge.gallery.eval.STOP_SERVER"
  }
}
