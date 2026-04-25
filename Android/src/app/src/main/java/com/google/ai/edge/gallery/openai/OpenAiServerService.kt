package com.google.ai.edge.gallery.openai

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AGOpenAiServerService"
private const val CHANNEL_ID = "openai_server_channel"
private const val NOTIFICATION_ID = 1001
private const val ACTION_STOP_SERVER = "com.google.ai.edge.gallery.openai.STOP_SERVER"

class OpenAiServerService : Service() {

    private var server: OpenAiServer? = null
    private var tunnel: PublicTunnel? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tunnelUrlJob: Job? = null
    private var tunnelProcessJob: Job? = null

    companion object {
        val isRunning: StateFlow<Boolean> = OpenAiServerState.isRunning
        val localUrl: StateFlow<String?> = OpenAiServerState.localUrl
        val publicUrl: StateFlow<String?> = OpenAiServerState.publicUrl
        val tunnelProvider: StateFlow<String> = OpenAiServerState.tunnelProvider
        const val EXTRA_OPEN_SERVER_SCREEN = "open_server_screen"

        fun startService(
            context: Context,
            useTunnel: Boolean = false,
            tunnelProvider: String = OpenAiServerState.tunnelProvider.value,
        ) {
            val intent = Intent(context, OpenAiServerService::class.java).apply {
                putExtra("use_tunnel", useTunnel)
                putExtra("tunnel_provider", tunnelProvider)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OpenAiServerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        OpenAiServerState.loadTunnelPreference(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            stopSelf()
            return START_NOT_STICKY
        }

        val useTunnel = intent?.getBooleanExtra("use_tunnel", OpenAiServerState.isTunnelEnabled.value)
            ?: OpenAiServerState.isTunnelEnabled.value
        val requestedProvider =
            intent?.getStringExtra("tunnel_provider")
                ?: OpenAiServerState.tunnelProvider.value
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Starting server..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Starting server..."))
        }

        serviceScope.launch {
            val local = getReachableLocalUrl() ?: "http://localhost:8080"

            if (server == null) {
                val modelManagerVm = OpenAiServerState.modelManagerViewModel
                if (modelManagerVm != null) {
                    server = OpenAiServer(applicationContext, modelManagerVm)
                    server?.start(8080)
                    Log.i(TAG, "OpenAI API Server started on port 8080")
                } else {
                    Log.w(TAG, "ModelManagerViewModel not available; server cannot start")
                }
            } else {
                Log.d(TAG, "OpenAI API Server already running; skipping restart")
            }

            OpenAiServerState.setRunning(true, local = local)
            updateNotification("Server running at $local")

            if (useTunnel) {
                OpenAiServerState.setTunnelEnabled(true)
                OpenAiServerState.persistTunnelProvider(applicationContext, requestedProvider)
                tunnel?.stop()
                tunnel = createTunnel(requestedProvider)
                tunnelUrlJob?.cancel()
                tunnelUrlJob = serviceScope.launch {
                    tunnel?.publicUrl?.collect { url ->
                        OpenAiServerState.setPublicUrl(url)
                        if (url != null) {
                            updateNotification("Server: $local\n${providerLabel(requestedProvider)}: $url")
                        }
                    }
                }
                updateNotification("Starting ${providerLabel(requestedProvider).lowercase()}...")
                tunnelProcessJob?.cancel()
                tunnelProcessJob = serviceScope.launch {
                    tunnel?.start(8080)
                }
            } else {
                OpenAiServerState.setTunnelEnabled(false)
                OpenAiServerState.setPublicUrl(null)
                tunnel?.stop()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelUrlJob?.cancel()
        tunnelProcessJob?.cancel()
        serviceScope.cancel()
        tunnel?.stop()
        server?.stop()
        server = null
        OpenAiServerState.setRunning(false)
        Log.i(TAG, "OpenAI API Server Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenAI API Server Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_OPEN_SERVER_SCREEN, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, OpenAiServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Edge Gallery API Server")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getReachableLocalUrl(port: Int = 8080): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
                ?.let { "http://$it:$port" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to determine reachable local IP address", e)
            null
        }
    }

    private fun createTunnel(provider: String): PublicTunnel {
        return when (provider) {
            OpenAiServerState.TUNNEL_PROVIDER_NGROK -> NgrokTunnel(applicationContext)
            else -> CloudflareTunnel(applicationContext)
        }
    }

    private fun providerLabel(provider: String): String {
        return when (provider) {
            OpenAiServerState.TUNNEL_PROVIDER_NGROK -> "ngrok"
            else -> "Cloudflare"
        }
    }
}
