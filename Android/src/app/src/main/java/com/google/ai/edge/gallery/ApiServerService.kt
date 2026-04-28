package com.google.ai.edge.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class ApiServerService : Service() {

    private var server: LiteRtApiServer? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        server = LiteRtApiServer(this, 8088)
        server?.startServer()

        Log.d("ApiServerService", "Server started")
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stopServer()
        Log.d("ApiServerService", "Server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val channelId = "api_server"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LiteRT API Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("LiteRT API Server")
            .setContentText("Running on port 8088")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }
}
