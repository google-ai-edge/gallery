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

package com.google.ai.edge.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.edgeserver.EdgeServerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that owns the lifecycle of the local OpenAI-compatible API server
 * ([EdgeServerManager]). Running the server in a foreground service keeps it serving while the
 * app is backgrounded and lets it survive process death: the enabled state is persisted in
 * DataStore and the service is started again on app startup (see
 * [com.google.ai.edge.gallery.MainActivity]).
 *
 * The settings dialog's toggle starts/stops this service; the service itself starts/stops the
 * underlying server. Stopping the service (from the toggle or the notification's Stop action)
 * always persists the disabled state so the server isn't silently restored on the next app start.
 * The model it serves is the app's single active model, resolved through
 * [EdgeServerManager.modelLookup] (wired by
 * [com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel]).
 */
@AndroidEntryPoint
class LocalApiServerService : Service() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_SERVER) {
      // The user explicitly stopped the server from the notification; persist that so the next
      // app start doesn't re-enable it behind their back.
      dataStoreRepository.saveLocalApiServerEnabled(enabled = false)
      stopForegroundService()
      return START_NOT_STICKY
    }

    // Promote to a foreground service before anything else so the process isn't killed while
    // backgrounded. The dataSync type matches a local data server on API 34+.
    startForeground(
      NOTIFICATION_ID,
      buildNotification(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    EdgeServerManager.startServer()
    if (EdgeServerManager.state.value.isRunning) {
      Log.i(TAG, "Local API server started")
    } else {
      // Starting failed (e.g. port already in use); the error is exposed through
      // EdgeServerManager.state for the settings row. Tear the service down so the user isn't
      // left with a notification for a server that isn't running.
      Log.w(TAG, "Local API server failed to start: ${EdgeServerManager.state.value.errorMessage}")
      stopForegroundService()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // The service owns the server's lifecycle: stopping the service always stops the server.
    EdgeServerManager.stopServer()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun stopForegroundService() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun buildNotification(): Notification {
    // Tap opens the app; the explicit Stop action stops the server.
    val openAppIntent =
      Intent(this, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stopIntent =
      Intent(this, LocalApiServerService::class.java).apply { action = ACTION_STOP_SERVER }
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        1,
        stopIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_manage)
      .setContentTitle(getString(R.string.local_api_server_notification_title))
      .setContentText(getString(R.string.local_api_server_notification_text))
      .setOngoing(true)
      .setContentIntent(openAppPendingIntent)
      .addAction(
        /* icon = */ 0,
        getString(R.string.local_api_server_notification_stop),
        stopPendingIntent,
      )
      .build()
  }

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          getString(R.string.local_api_server_notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = getString(R.string.local_api_server_notification_channel_description)
        }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  companion object {
    private const val TAG = "LocalApiServerService"
    private const val NOTIFICATION_CHANNEL_ID = "local_api_server_channel"
    private const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_SERVER = "com.google.ai.edge.gallery.action.STOP_LOCAL_API_SERVER"

    /** Starts this service as a foreground service (no-op-safe if it is already running). */
    fun start(context: Context) {
      val intent = Intent(context, LocalApiServerService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }

    /** Stops this service, which in turn stops the underlying server. */
    fun stop(context: Context) {
      context.stopService(Intent(context, LocalApiServerService::class.java))
    }
  }
}
