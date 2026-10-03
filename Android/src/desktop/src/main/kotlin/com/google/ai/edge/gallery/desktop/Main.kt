package com.google.ai.edge.gallery.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.google.ai.edge.gallery.GalleryApp
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags

fun main() {
  // The packaged app has no console: route every uncaught exception to app.log.
  Thread.setDefaultUncaughtExceptionHandler { thread, e ->
    android.util.Log.e("AGUncaught", "Uncaught exception on thread '${thread.name}'", e)
  }
  runApp()
}

private fun runApp() = application {
  // Initialize allowlist file from bundled fallback if not present
  DesktopAppModule.initializeAllowlistIfMissing()

  // Start loading model allowlist
  DesktopAppModule.modelManagerViewModel.loadModelAllowlist()

  @OptIn(ExperimentalApi::class)
  ExperimentalFlags.enableBenchmark = false

  val windowState = rememberWindowState(width = 1200.dp, height = 850.dp)
  val rootViewModelStoreOwner = remember { DesktopViewModelStoreOwner() }

  Window(
    onCloseRequest = ::exitApplication,
    title = "Google AI Edge Gallery",
    state = windowState,
  ) {
    CompositionLocalProvider(
      LocalViewModelStoreOwner provides rootViewModelStoreOwner,
    ) {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          GalleryApp(modelManagerViewModel = DesktopAppModule.modelManagerViewModel)
        }
      }
    }
  }
}
