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

class EvalService : Service() {

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    Log.i(TAG, "onStartCommand action: $action")

    if (action == ACTION_STOP_SERVER) {
      stopSelf()
      return START_NOT_STICKY
    }

    // Extract optional model configuration from the intent.
    // In CL 3, these will be used to pre-initialize the model.
    val port = intent?.getIntExtra("port", 8080) ?: 8080

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        createNotification(port),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, createNotification(port))
    }

    return START_STICKY
  }

  override fun onDestroy() {
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
