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

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private var firebaseAnalytics: FirebaseAnalytics? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    firebaseAnalytics =
      runCatching { Firebase.analytics }
        .onFailure { exception ->
          // Firebase.analytics can throw an exception if goolgle-services is not set up, e.g.,
          // missing google-services.json.
          Log.w(TAG, "Firebase Analytics is not available", exception)
        }
        .getOrNull()

    installSplashScreen()

    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    setContent { GalleryTheme { Surface(modifier = Modifier.fillMaxSize()) { GalleryApp() } } }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
    }
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
