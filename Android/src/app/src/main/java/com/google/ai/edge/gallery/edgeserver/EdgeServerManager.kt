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
 * (the start/stop toggle in the settings dialog and the persistent notification of
 * [com.google.ai.edge.gallery.services.LocalApiServerService], which owns the server's lifecycle).
 */
object EdgeServerManager {

  data class ServerState(
    val isRunning: Boolean = false,
    val host: String = EdgeServer.DEFAULT_HOST,
    val port: Int = EdgeServer.DEFAULT_PORT,
    /** Set when starting the server failed (e.g. the port is already in use). */
    val errorMessage: String? = null,
  )

  private val _state = MutableStateFlow(ServerState())

  /** Observable server state. */
  val state: StateFlow<ServerState> = _state.asStateFlow()

  /**
   * Resolves the model the server serves: the app's single active model (the one selected in
   * Chat), or null when none is selected. The server consults it once per request. Wired by
   * [com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel] so the server follows the
   * active model instead of silently serving whichever instance happens to be alive.
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
      // Surface the failure (e.g. port already in use) to the UI instead of only logging it.
      Log.e(TAG, "Failed to start edge server", e)
      _state.value = ServerState(isRunning = false, errorMessage = e.message ?: "Unknown error")
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
    // Deliberately keep modelLookup wired: it is owned by ModelManagerViewModel (assigned in its
    // init) and must keep resolving the active model for later start/stop cycles in this session,
    // because nothing else re-wires it. A freshly created ViewModel instance re-assigns it.
    _state.value = ServerState(isRunning = false)
    Log.i(TAG, "Edge server stopped")
  }
}
