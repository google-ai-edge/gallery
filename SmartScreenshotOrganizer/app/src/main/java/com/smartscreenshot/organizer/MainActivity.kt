package com.smartscreenshot.organizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import com.smartscreenshot.organizer.ui.navigation.AppNavigation
import com.smartscreenshot.organizer.ui.theme.SmartScreenshotTheme
import com.smartscreenshot.organizer.worker.ScreenshotContentObserver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: ScreenshotRepository

    private lateinit var screenshotObserver: ScreenshotContentObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        screenshotObserver = ScreenshotContentObserver(this, repository)

        setContent {
            SmartScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start real-time screenshot detection when app is visible
        screenshotObserver.register()
    }

    override fun onStop() {
        super.onStop()
        // Stop real-time detection when backgrounded; WorkManager handles the rest
        screenshotObserver.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshotObserver.destroy()
    }
}
