package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ModelKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "model_keep_alive_channel"
        const val EXTRA_MODEL_NAME = "extra_model_name"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelName = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "A model"
        createNotificationChannel()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model loaded")
            .setContentText("$modelName is running in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(201, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
