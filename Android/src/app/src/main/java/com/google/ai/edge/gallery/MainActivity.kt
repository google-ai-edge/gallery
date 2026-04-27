/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.animation.ObjectAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var splashScreenAboutToExit: Boolean = false
  private var contentSet: Boolean = false
  private var apiServer: LiteRtApiServer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)

    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onCreate Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onCreate: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }

    fun setContent() {
      if (contentSet) {
        return
      }

      setContent {
        GalleryTheme {
          Surface(modifier = Modifier.fillMaxSize()) {
            GalleryApp(modelManagerViewModel = modelManagerViewModel)

            var startMaskFadeout by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { startMaskFadeout = true }
            AnimatedVisibility(
              !startMaskFadeout,
              enter = fadeIn(animationSpec = snap(0)),
              exit =
                fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
            ) {
              Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
              )
            }
          }
        }
      }

      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = false

      contentSet = true
    }

    modelManagerViewModel.loadModelAllowlist()

    startApiServer()

    val splashScreen = installSplashScreen()

    lifecycleScope.launch {
      delay(1000)
      if (!splashScreenAboutToExit) {
        setContent()
      }
    }

    splashScreen.setOnExitAnimationListener { splashScreenView ->
      splashScreenAboutToExit = true

      val now = System.currentTimeMillis()
      val iconAnimationStartMs = splashScreenView.iconAnimationStartMillis
      val duration = splashScreenView.iconAnimationDurationMillis
      val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
      fadeOut.interpolator = DecelerateInterpolator()
      fadeOut.duration = 300L
      fadeOut.doOnEnd { splashScreenView.remove() }
      lifecycleScope.launch {
        val setContentDelay = duration - (now - iconAnimationStartMs) - 300
        if (setContentDelay > 0) {
          delay(setContentDelay)
        }
        setContent()
        fadeOut.start()
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun startApiServer() {
    try {
      apiServer = LiteRtApiServer(this, 8088)
    
      apiServer?.startServer()

      var ipAddress = "N/A"
      try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
          val ni = interfaces.nextElement()
          if (ni.isLoopback || !ni.isUp) continue
          val addrs = ni.inetAddresses
          while (addrs.hasMoreElements()) {
            val addr = addrs.nextElement()
            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
              ipAddress = addr.hostAddress ?: "N/A"
              break
            }
          }
          if (ipAddress != "N/A") break
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to get IP: ${e.message}")
      }

      val serverUrl = "http://$ipAddress:8088/health"
      Log.d(TAG, "LiteRT API Server running at $serverUrl")

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel("api_server", "API Server", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification = android.app.Notification.Builder(this, "api_server")
          .setContentTitle("API Server Running")
          .setContentText(serverUrl)
          .setSmallIcon(android.R.drawable.ic_dialog_info)
          .setOngoing(true)
          .build()

        nm.notify(1, notification)
      }

      Toast.makeText(this, "API Server started:\n$serverUrl", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start API Server: ${e.message}")
      Toast.makeText(this, "API Server failed", Toast.LENGTH_SHORT).show()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      apiServer?.stopServer()
    } catch (e: Exception) {
      Log.e(TAG, "stop error: ${e.message}")
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onNewIntent Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }
    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onNewIntent: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
      } else {
        intent.data = link.toUri()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
