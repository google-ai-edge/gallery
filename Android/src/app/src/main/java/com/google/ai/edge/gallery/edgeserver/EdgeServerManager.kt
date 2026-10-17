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

package com.google.ai.edge.gallery.edgeserver

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "EdgeServerManager"

/**
 * Singleton that manages the [EdgeServer] lifecycle and exposes observable state for the UI
 * (the start/stop toggle in the settings dialog).
 *
 * Adapted from open PR google-ai-edge/gallery#647, simplified: no foreground service — the server
 * runs in-process while the app is alive, started and stopped from the settings dialog.
 */
object EdgeServerManager {

  data class ServerState(
    val isRunning: Boolean = false,
    val host: String = EdgeServer.DEFAULT_HOST,
    val port: Int = EdgeServer.DEFAULT_PORT,
  )

  private val _state = MutableStateFlow(ServerState())

  /** Observable server state. */
  val state: StateFlow<ServerState> = _state.asStateFlow()

  /**
   * Provides the currently loaded LLM model for inference. Assign before [startServer] — typically
   * a lambda over `ModelManagerViewModel`. The server consults it once per request.
   */
  @Volatile var modelLookup: (() -> Model?)? = null

  @Volatile var server: EdgeServer? = null
    private set

  /** Starts the server on 127.0.0.1:[EdgeServer.DEFAULT_PORT]. No-op if already running. */
  fun startServer() {
    if (server?.isAlive == true) {
      return
    }
    val newServer = EdgeServer(hostname = EdgeServer.DEFAULT_HOST, port = EdgeServer.DEFAULT_PORT)
    newServer.modelLookup = { modelLookup?.invoke() }
    try {
      newServer.start()
      server = newServer
      _state.value =
        ServerState(isRunning = true, host = EdgeServer.DEFAULT_HOST, port = newServer.listeningPort)
      Log.i(TAG, "Edge server started on ${EdgeServer.DEFAULT_HOST}:${newServer.listeningPort}")
    } catch (e: IOException) {
      Log.e(TAG, "Failed to start edge server", e)
      _state.value = ServerState(isRunning = false)
    }
  }

  /** Stops the server. No-op if not running. */
  fun stopServer() {
    val curServer = server ?: return
    server = null
    try {
      curServer.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping edge server", e)
    }
    // Release the model lookup (which captures a ViewModel) so the singleton does not retain the
    // activity-scoped ViewModel after the server is stopped; startServer re-assigns it.
    modelLookup = null
    _state.value = ServerState(isRunning = false)
    Log.i(TAG, "Edge server stopped")
  }
}
