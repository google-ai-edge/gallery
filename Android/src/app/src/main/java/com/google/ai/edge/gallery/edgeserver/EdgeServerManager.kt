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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "EdgeServerManager"

/**
 * Singleton that manages the Edge Server lifecycle and exposes observable
 * state for the Compose UI.
 *
 * The manager holds a direct reference to the [EdgeServer] instance so that
 * model binding works immediately, even before the Android Service connection
 * completes (which is asynchronous).
 */
object EdgeServerManager {

  data class ServerState(
    val isRunning: Boolean = false,
    val host: String = EdgeServer.DEFAULT_HOST,
    val port: Int = EdgeServer.DEFAULT_PORT,
    val modelName: String = "",
  )

  private val _state = MutableStateFlow(ServerState())
  val state: StateFlow<ServerState> = _state.asStateFlow()

  /** Direct server reference for immediate model binding. */
  @Volatile var server: EdgeServer? = null
    private set

  /** Callback invoked by the server when it needs a model. Set by NavGraph. */
  @Volatile var modelFinderCallback: (() -> Unit)? = null

  private var service: EdgeServerService? = null
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as EdgeServerService.LocalBinder).getService()
      bound = true
      refreshState()
      Log.i(TAG, "Service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      service = null
      bound = false
      refreshState()
      Log.i(TAG, "Service disconnected")
    }
  }

  /** Start the Edge Server on [host]:[port] with a foreground service. */
  fun startServer(context: Context, host: String = EdgeServer.DEFAULT_HOST, port: Int = EdgeServer.DEFAULT_PORT) {
    if (server == null || !server!!.isAlive) {
      server = EdgeServer(hostname = host, port = port).also { it.modelFinder = modelFinderCallback }
      try {
        server?.start()
        Log.i(TAG, "Server started on $host:$port")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start server", e)
      }
    }

    val intent = Intent(context, EdgeServerService::class.java).apply {
      putExtra("host", host)
      putExtra("port", port)
    }
    context.startForegroundService(intent)
    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    _state.value = _state.value.copy(isRunning = true, host = host, port = port)
  }

  /** Stop the server and foreground service. */
  fun stopServer(context: Context) {
    server?.stop()
    server = null

    if (bound) {
      try { context.unbindService(connection) } catch (e: Exception) {
        Log.w(TAG, "Unbind error: ${e.message}")
      }
      bound = false
    }

    context.stopService(Intent(context, EdgeServerService::class.java))
    service = null
    _state.value = ServerState()
    Log.i(TAG, "Server stopped")
  }

  /** Bind a loaded model so the server can serve inference requests. */
  fun bindModel(model: Model, helper: LlmModelHelper, displayName: String) {
    server?.activeModel = model
    server?.activeModelHelper = helper
    server?.activeModelDisplayName = displayName
    service?.setActiveModel(model, helper, displayName)
    _state.value = _state.value.copy(modelName = displayName)
    Log.i(TAG, "Model bound: $displayName")
  }

  /** Unbind the current model. */
  fun unbindModel() {
    server?.activeModel = null
    server?.activeModelHelper = null
    server?.activeModelDisplayName = ""
    service?.clearActiveModel()
    _state.value = _state.value.copy(modelName = "")
  }

  private fun refreshState() {
    val running = server?.isAlive == true || service?.isServerRunning() == true
    val port = server?.listeningPort ?: service?.getPort() ?: _state.value.port
    _state.value = _state.value.copy(isRunning = running, port = port)
  }
}
