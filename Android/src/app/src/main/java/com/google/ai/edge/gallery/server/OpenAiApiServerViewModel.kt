/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.FrpPreferences
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.OpenAiApiServerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenAiApiServerUiState(
  val status: OpenAiApiServerStatus = OpenAiApiServerStatus.STOPPED,
  val enabled: Boolean = false,
  val port: Int = 8080,
  val selectedModel: String = "",
  val availableModels: List<String> = emptyList(),
  val apiKey: String = "",
  val endpoint: String = "",
  val requestCount: Long = 0,
  val error: String = "",
  // FRP state
  val frpEnabled: Boolean = false,
  val frpServerAddr: String = "",
  val frpServerPort: Int = 7000,
  val frpToken: String = "",
  val frpRemotePort: Int = 8080,
  val frpCustomDomain: String = "",
  val frpRunning: Boolean = false,
  val frpBinaryMissing: Boolean = false,
)

@HiltViewModel
class OpenAiApiServerViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val dataStoreRepository: DataStoreRepository,
  private val apiServer: OpenAiApiServer,
  private val frpManager: FrpManager,
) : ViewModel() {
  private val preferences = dataStoreRepository.readOpenAiApiServerPreferences()
  private val frpPrefs = dataStoreRepository.readFrpPreferences()
  private val _uiState =
    MutableStateFlow(
      OpenAiApiServerUiState(
        enabled = preferences.enabled,
        port = preferences.port,
        selectedModel = preferences.modelName,
        apiKey = loadOrCreateApiKey(),
        frpEnabled = frpPrefs.enabled,
        frpServerAddr = frpPrefs.serverAddress,
        frpServerPort = frpPrefs.serverPort,
        frpToken = frpPrefs.token,
        frpRemotePort = frpPrefs.remotePort,
        frpCustomDomain = frpPrefs.customDomain,
        frpBinaryMissing = !frpManager.isBinaryAvailable(),
      )
    )
  val uiState = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      apiServer.state.collect { serverState ->
        _uiState.update {
          it.copy(
            status = serverState.status,
            endpoint = serverState.endpoint,
            requestCount = serverState.requestCount,
            error = serverState.error,
          )
        }
      }
    }
    viewModelScope.launch {
      frpManager.isRunning.collect { running ->
        _uiState.update { it.copy(frpRunning = running) }
      }
    }
  }

  fun updateModels(models: List<Model>, preferredModel: String = "") {
    apiServer.updateModels(models)
    val names = models.map { it.name }.distinct().sorted()
    val current = _uiState.value.selectedModel
    val selected =
      when {
        current in names -> current
        preferredModel in names -> preferredModel
        else -> names.firstOrNull().orEmpty()
      }
    _uiState.update { it.copy(availableModels = names, selectedModel = selected) }
    if (_uiState.value.enabled && names.isNotEmpty() && apiServer.state.value.status == OpenAiApiServerStatus.STOPPED) {
      start(port = _uiState.value.port, modelName = selected)
    }
  }

  fun start(port: Int, modelName: String) {
    if (modelName.isBlank()) {
      _uiState.update { it.copy(error = "Download an LLM before starting the server.") }
      return
    }
    if (port !in 1024..65535) {
      _uiState.update { it.copy(error = "Port must be between 1024 and 65535.") }
      return
    }
    val prefs = OpenAiApiServerPreferences(enabled = true, port = port, modelName = modelName)
    dataStoreRepository.saveOpenAiApiServerPreferences(prefs)
    _uiState.update { it.copy(enabled = true, port = port, selectedModel = modelName, error = "") }
    ContextCompat.startForegroundService(
      context,
      Intent(context, OpenAiApiServerService::class.java)
        .setAction(OpenAiApiServerService.ACTION_START)
        .putExtra(OpenAiApiServerService.EXTRA_PORT, port)
        .putExtra(OpenAiApiServerService.EXTRA_API_KEY, _uiState.value.apiKey)
        .putExtra(OpenAiApiServerService.EXTRA_MODEL, modelName),
    )
    if (_uiState.value.frpEnabled) {
      frpManager.start(
        _uiState.value.frpServerAddr,
        _uiState.value.frpServerPort,
        _uiState.value.frpToken,
        port,
        _uiState.value.frpRemotePort,
        _uiState.value.frpCustomDomain
      )
    }
  }

  fun stop() {
    dataStoreRepository.saveOpenAiApiServerPreferences(
      OpenAiApiServerPreferences(enabled = false, port = _uiState.value.port, modelName = _uiState.value.selectedModel)
    )
    _uiState.update { it.copy(enabled = false) }
    context.startService(Intent(context, OpenAiApiServerService::class.java).setAction(OpenAiApiServerService.ACTION_STOP))
    frpManager.stop()
  }

  fun updateFrpConfig(enabled: Boolean, serverAddr: String, serverPort: Int, token: String, remotePort: Int, customDomain: String) {
    val prefs = FrpPreferences(
      enabled = enabled,
      serverAddress = serverAddr,
      serverPort = serverPort,
      token = token,
      remotePort = remotePort,
      customDomain = customDomain
    )
    dataStoreRepository.saveFrpPreferences(prefs)
    _uiState.update { 
      it.copy(
        frpEnabled = enabled,
        frpServerAddr = serverAddr,
        frpServerPort = serverPort,
        frpToken = token,
        frpRemotePort = remotePort,
        frpCustomDomain = customDomain,
        frpBinaryMissing = !frpManager.isBinaryAvailable()
      )
    }
    
    if (enabled && _uiState.value.enabled && _uiState.value.status == OpenAiApiServerStatus.RUNNING) {
      frpManager.start(serverAddr, serverPort, token, _uiState.value.port, remotePort, customDomain)
    } else {
      frpManager.stop()
    }
  }

  fun importFrpBinary(uri: Uri) {
    if (frpManager.importBinary(uri)) {
      _uiState.update { it.copy(frpBinaryMissing = false) }
    }
  }

  fun deleteFrpBinary() {
    if (frpManager.deleteBinary()) {
      _uiState.update { it.copy(frpBinaryMissing = true) }
    }
  }

  fun reportPermissionDenied() {
    _uiState.update { it.copy(error = "Local network permission is required to accept LAN connections.") }
  }

  fun regenerateApiKey() {
    val apiKey = generateApiKey()
    dataStoreRepository.saveSecret(API_KEY_SECRET, apiKey)
    _uiState.update { it.copy(apiKey = apiKey) }
    if (_uiState.value.status == OpenAiApiServerStatus.RUNNING) {
      start(_uiState.value.port, _uiState.value.selectedModel)
    }
  }

  private fun loadOrCreateApiKey(): String {
    return dataStoreRepository.readSecret(API_KEY_SECRET)?.takeIf { it.isNotBlank() }
      ?: generateApiKey().also { dataStoreRepository.saveSecret(API_KEY_SECRET, it) }
  }

  private fun generateApiKey(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return "sk-local-${Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)}"
  }

  companion object {
    private const val API_KEY_SECRET = "openai_api_server_key"
  }
}
