/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class OpenAiApiServerService : Service() {
  @Inject lateinit var apiServer: OpenAiApiServer
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      apiServer.stop()
      releaseLocks()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
    val apiKey = intent?.getStringExtra(EXTRA_API_KEY).orEmpty()
    val model = intent?.getStringExtra(EXTRA_MODEL).orEmpty()
    val notification = createNotification(port)
    
    acquireLocks()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    apiServer.start(port = port, apiKey = apiKey, defaultModel = model)
    if (apiServer.state.value.status == OpenAiApiServerStatus.ERROR) {
      releaseLocks()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    apiServer.stop()
    releaseLocks()
    super.onDestroy()
  }

  private fun acquireLocks() {
    if (wakeLock == null) {
      val powerManager = getSystemService(POWER_SERVICE) as PowerManager
      wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenAiApiServer::WakeLock").apply {
        acquire()
      }
    }
    if (wifiLock == null) {
      val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
      wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenAiApiServer::WifiLock").apply {
        acquire()
      }
    }
  }

  private fun releaseLocks() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wifiLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    wifiLock = null
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createNotificationChannel() {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.openai_api_notification_channel),
        NotificationManager.IMPORTANCE_LOW,
      )
    )
  }

  private fun createNotification(port: Int): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, OpenAiApiServerService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher_monochrome)
      .setContentTitle(getString(R.string.openai_api_notification_title))
      .setContentText(getString(R.string.openai_api_notification_text, port))
      .setContentIntent(openIntent)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, getString(R.string.stop), stopIntent)
      .build()
  }

  companion object {
    const val ACTION_START = "com.google.ai.edge.gallery.server.START"
    const val ACTION_STOP = "com.google.ai.edge.gallery.server.STOP"
    const val EXTRA_PORT = "port"
    const val EXTRA_API_KEY = "api_key"
    const val EXTRA_MODEL = "model"
    private const val CHANNEL_ID = "openai_api_server"
    private const val NOTIFICATION_ID = 8402
  }
}
