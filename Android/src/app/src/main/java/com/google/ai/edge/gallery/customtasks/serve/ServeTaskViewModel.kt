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

package com.google.ai.edge.gallery.customtasks.serve

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ServeTaskUiState(
    val isServerRunning: Boolean = false,
    val port: Int = 8080,
    val logs: List<String> = emptyList(),
    val serverAddress: String = ""
)

@HiltViewModel
class ServeTaskViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(ServeTaskUiState())
    val uiState = _uiState.asStateFlow()

    private var server: OpenAIServer? = null
    private val mutex = Mutex()

    fun toggleServer(
        context: Context,
        modelManagerViewModel: ModelManagerViewModel,
        port: Int
    ) {
        if (uiState.value.isServerRunning) {
            stopServer()
        } else {
            startServer(context, modelManagerViewModel, port)
        }
    }

    private fun startServer(
        context: Context,
        modelManagerViewModel: ModelManagerViewModel,
        port: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (server != null) {
                   server?.stop()
                   server = null
                }
                // Pass viewModelScope to server so it can launch inference jobs that survive beyond this launch block
                server = OpenAIServer(port, modelManagerViewModel, viewModelScope, this@ServeTaskViewModel)
                server?.start()

                val ipAddress = getIpAddress() ?: "localhost"
                _uiState.update {
                    it.copy(
                        isServerRunning = true,
                        port = port,
                        serverAddress = "http://$ipAddress:$port"
                    )
                }
                addLog("Server started on $ipAddress:$port")
            } catch (e: Exception) {
                addLog("Error starting server: ${e.message}")
            }
        }
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ServeTaskViewModel", "Error getting IP", e)
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure server is stopped when ViewModel is cleared
        if (server != null) {
             server?.stop()
        }
    }

    private fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                server?.stop()
                server = null
                _uiState.update {
                    it.copy(isServerRunning = false)
                }
                addLog("Server stopped")
            } catch (e: Exception) {
                addLog("Error stopping server: ${e.message}")
            }
        }
    }

    fun addLog(message: String) {
        _uiState.update {
            val newLogs = it.logs.toMutableList()
            newLogs.add(0, "[${System.currentTimeMillis()}] $message")
            if (newLogs.size > 100) {
                newLogs.removeAt(newLogs.size - 1)
            }
            it.copy(logs = newLogs)
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun generateResponse(
        model: Model,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        maxTokens: Int = -1,
        onPartialResult: (String) -> Unit
    ): String = mutex.withLock {
        val startTs = System.currentTimeMillis()
        addLog("Request received. Max tokens: $maxTokens")

        // Wait for model to be initialized with timeout
        val timeoutMs = 30000L // 30 seconds
        val startTime = System.currentTimeMillis()
        while (model.instance == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw IllegalStateException("Model initialization timed out")
            }
            kotlinx.coroutines.delay(100)
        }

        // Reset conversation to avoid state accumulation
        LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio
        )

        val instance = model.instance as LlmModelInstance
        val conversation = instance.conversation

        val contents = mutableListOf<Content>()
        for (image in images) {
            contents.add(Content.ImageBytes(image.toPngByteArray()))
        }
        if (prompt.isNotEmpty()) {
            contents.add(Content.Text(prompt))
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val sb = StringBuilder()
            var firstTokenTs = 0L
            var tokenCount = 0

            conversation.sendMessageAsync(
                Message.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val content = message.toString()
                        if (content.isNotEmpty()) {
                            if (firstTokenTs == 0L) {
                                firstTokenTs = System.currentTimeMillis()
                            }
                            tokenCount++

                            // Check max tokens limit
                            if (maxTokens > 0 && tokenCount > maxTokens) {
                                conversation.cancelProcess()
                                return
                            }

                            onPartialResult(content)
                            sb.append(content)
                        }
                    }

                    override fun onDone() {
                        val endTs = System.currentTimeMillis()
                        val decodeTime = (endTs - firstTokenTs) / 1000f
                        val prefillTime = (firstTokenTs - startTs) / 1000f
                        val decodeSpeed = if (decodeTime > 0) tokenCount / decodeTime else 0f

                        addLog("Request processed. Tokens: $tokenCount. Prefill: ${"%.2f".format(prefillTime)}s. Decode: ${"%.2f".format(decodeSpeed)} t/s")

                        if (continuation.isActive) {
                             continuation.resumeWith(Result.success(sb.toString()))
                        }
                    }

                    override fun onError(throwable: Throwable) {
                         Log.e("ServeTaskViewModel", "onError", throwable)
                         addLog("Request failed: ${throwable.message}")
                         if (continuation.isActive) {
                             if (throwable is CancellationException) {
                                 continuation.resumeWith(Result.success(sb.toString()))
                             } else {
                                 continuation.resumeWith(Result.failure(throwable))
                             }
                         }
                    }
                }
            )
        }
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
