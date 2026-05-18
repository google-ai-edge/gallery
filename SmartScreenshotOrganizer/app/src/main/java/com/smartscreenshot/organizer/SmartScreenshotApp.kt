package com.smartscreenshot.organizer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point with Hilt DI, custom WorkManager configuration,
 * and notification channel setup.
 *
 * WorkManager is initialized manually (not via AndroidX Startup) to support
 * Hilt-injected workers. The default initializer is removed in the manifest.
 */
@HiltAndroidApp
class SmartScreenshotApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    companion object {
        const val NOTIFICATION_CHANNEL_ANALYSIS = "screenshot_analysis"
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ANALYSIS,
                "Screenshot Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress notifications for screenshot analysis"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
