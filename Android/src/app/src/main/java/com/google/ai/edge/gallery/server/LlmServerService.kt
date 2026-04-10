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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground [Service] that hosts the [LlmHttpServer].
 *
 * Android aggressively kills long-running background work when the screen is off, so the HTTP
 * server is owned by a foreground service with a persistent notification. The notification doubles
 * as a status display (it shows the IP address the user should point their client at) and hosts a
 * "Stop" action.
 */
class LlmServerService : Service() {

  companion object {
    private const val TAG = "AGLlmServerService"
    private const val NOTIFICATION_ID = 0x6e7474 // "ntt"
    private const val CHANNEL_ID = "edge_gallery_llm_server"
    private const val CHANNEL_NAME = "Local LLM server"
    private const val WAKE_LOCK_TAG = "EdgeGallery::LlmServerWakeLock"
    private const val WIFI_LOCK_TAG = "EdgeGallery::LlmServerWifiLock"

    const val ACTION_START = "com.google.ai.edge.gallery.server.action.START"
    const val ACTION_STOP = "com.google.ai.edge.gallery.server.action.STOP"
    const val EXTRA_PORT = "com.google.ai.edge.gallery.server.extra.PORT"
    const val DEFAULT_PORT = 8080

    @Volatile var isRunning: Boolean = false
      private set

    @Volatile var runningPort: Int = 0
      private set

    fun start(context: Context, port: Int = DEFAULT_PORT) {
      val intent =
        Intent(context, LlmServerService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PORT, port)
        }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, LlmServerService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }

  private var server: LlmHttpServer? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        Log.d(TAG, "Stop requested")
        stopServerAndSelf()
        return START_NOT_STICKY
      }
      ACTION_START, null -> {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        startServer(port)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "onDestroy")
    stopServerInternal()
    releaseLocks()
    super.onDestroy()
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private fun startServer(port: Int) {
    if (isRunning) {
      Log.d(TAG, "Server already running on port $runningPort, ignoring start request")
      // Keep the notification in sync anyway – the IP may have changed.
      startForegroundWithNotification(runningPort)
      return
    }

    val newServer = LlmHttpServer(port)
    try {
      newServer.start(NanoHttpdStartOptions.SOCKET_READ_TIMEOUT, false)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to start HTTP server on port $port", t)
      startForegroundWithErrorNotification(
        "Failed to start on port $port: ${t.message ?: t.javaClass.simpleName}"
      )
      // Tear ourselves down – there's nothing to serve.
      stopServerAndSelf()
      return
    }

    server = newServer
    runningPort = port
    isRunning = true
    acquireLocks()
    startForegroundWithNotification(port)
    Log.d(TAG, "Server started on port $port")
  }

  private fun stopServerAndSelf() {
    stopServerInternal()
    releaseLocks()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun stopServerInternal() {
    try {
      server?.stop()
    } catch (t: Throwable) {
      Log.w(TAG, "Error while stopping server", t)
    }
    server = null
    isRunning = false
    runningPort = 0
  }

  private fun acquireLocks() {
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
        pm
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
          .apply {
            setReferenceCounted(false)
            acquire()
          }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to acquire wake lock", t)
    }
    try {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      wifiLock =
        wm
          .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
          .apply {
            setReferenceCounted(false)
            acquire()
          }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to acquire wifi lock", t)
    }
  }

  private fun releaseLocks() {
    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    } catch (_: Throwable) {
    }
    wakeLock = null
    try {
      if (wifiLock?.isHeld == true) wifiLock?.release()
    } catch (_: Throwable) {
    }
    wifiLock = null
  }

  private fun ensureNotificationChannel() {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      val channel =
        NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
          description = "Shows when the on-device LLM HTTP server is running"
          setShowBadge(false)
        }
      mgr.createNotificationChannel(channel)
    }
  }

  private fun startForegroundWithNotification(port: Int) {
    val ip = findLocalIpv4() ?: "<unknown>"
    val activeModel = ServerModelHolder.activeModel?.name ?: "(no model loaded)"

    val tapIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, LlmServerService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Edge Gallery LLM server")
        .setContentText("Listening on http://$ip:$port  •  $activeModel")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setContentIntent(tapIntent)
        .addAction(0, "Stop", stopIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setStyle(
          NotificationCompat.BigTextStyle()
            .bigText(
              "Listening on http://$ip:$port\nActive model: $activeModel\n" +
                "POST JSON to /v1/chat/completions from any client on the same network."
            )
        )
        .build()

    // `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and the matching permission are mandatory on Android 14+
    // (API 34) and harmless on earlier versions (we target minSdk=31). The permission is declared
    // in AndroidManifest.xml as FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC.
    startForeground(
      NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun startForegroundWithErrorNotification(message: String) {
    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Edge Gallery LLM server")
        .setContentText(message)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    startForeground(
      NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun findLocalIpv4(): String? {
    return try {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
      for (iface in ifaces) {
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
        for (addr in iface.inetAddresses) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            return addr.hostAddress
          }
        }
      }
      null
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to enumerate network interfaces", t)
      null
    }
  }

  /**
   * Constants pulled out so callers don't have to reach into NanoHTTPD directly for the magic
   * numbers. NanoHTTPD's [fi.iki.elonen.NanoHTTPD.start] accepts a socket read timeout (ms). We use
   * the NanoHTTPD default.
   */
  private object NanoHttpdStartOptions {
    const val SOCKET_READ_TIMEOUT = fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT
  }
}
