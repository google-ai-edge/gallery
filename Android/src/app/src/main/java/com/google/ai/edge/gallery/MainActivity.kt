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
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.animation.doOnEnd
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    modelManagerViewModel.loadModelAllowlist()

    // Show splash screen.
    val splashScreen = installSplashScreen()

    // Cross-fade transition from the splash screen to the main content.
    //
    // The logic performs the following key actions:
    // 1. Synchronizes Timing: It calculates the remaining duration of the default icon
    //    animation. It then delays its own animations to ensure the custom fade-out begins just
    //    before the original icon animation would have finished.
    // 2. Initiates a cross-fade:
    //    - Fade out the splash screen.
    //    - Fade in the main content.
    // 3. Cleans up: An `onEnd` listener on the fade-out animator calls
    //    `splashScreenView.remove()` to properly remove the splash screen from the view hierarchy
    //    once it's fully transparent.
    splashScreen.setOnExitAnimationListener { splashScreenView ->
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
        fadeOut.start()
        setContent {
          GalleryTheme {
            var enabled by remember { mutableStateOf(false) }
            val animatedAlpha: Float by
              animateFloatAsState(if (enabled) 1f else 0f, label = "alpha")
            LaunchedEffect(Unit) { enabled = true }
            Surface(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = animatedAlpha }) {
              GalleryApp(modelManagerViewModel = modelManagerViewModel)
            }
          }
        }
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
