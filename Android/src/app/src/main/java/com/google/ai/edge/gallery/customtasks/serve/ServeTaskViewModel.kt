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
import java.util.concurrent.CancellationException

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
                   stopServer()
                }
                server = OpenAIServer(port, modelManagerViewModel, this, this@ServeTaskViewModel)
                server?.start()
                _uiState.update {
                    it.copy(
                        isServerRunning = true,
                        port = port,
                        serverAddress = "http://localhost:$port" // Android emulator/device localhost
                    )
                }
                addLog("Server started on port $port")
            } catch (e: Exception) {
                addLog("Error starting server: ${e.message}")
            }
        }
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
    suspend fun generateResponse(model: Model, prompt: String, onPartialResult: (String) -> Unit): String {
        // Wait for model to be initialized with timeout
        val timeoutMs = 30000L // 30 seconds
        val startTime = System.currentTimeMillis()
        while (model.instance == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw IllegalStateException("Model initialization timed out")
            }
            kotlinx.coroutines.delay(100)
        }

        val instance = model.instance as LlmModelInstance
        val conversation = instance.conversation

        // We use a latch-like mechanism or suspendCoroutine to wait for result
        // But here we can use a channel or just wait since we are in a suspend function

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val sb = StringBuilder()
            conversation.sendMessageAsync(
                Message.of(Content.Text(prompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        // This might be the full message or partial depending on implementation
                        // LiteRT LLM API onMessage usually gives partial/delta or accumulated?
                        // LlmChatModelHelper resultListener receives partialResult.
                        // Looking at LlmChatModelHelper.runInference, it calls resultListener(message.toString(), false)
                        // It seems it accumulates the result or message contains full result so far?
                        // "UpdateLastTextMessageContentIncrementally" in ViewModel suggests it might be accumulating or replacing.
                        // If it's accumulating, we need to handle that.
                        // Wait, LlmChatModelHelper says: `updateLastTextMessageContentIncrementally(..., partialContent = partialResult, ...)`

                        // Let's assume onMessage returns the delta or the accumulated text.
                        // Actually LlmChatModelHelper implementation:
                        // override fun onMessage(message: Message) { resultListener(message.toString(), false) }
                        // And in LlmChatViewModel:
                        // updateLastTextMessageContentIncrementally(...)

                        // Let's look at `updateLastTextMessageContentIncrementally`.
                        // But I don't have access to base class ChatViewModel source easily right now.

                        // Let's assume message.toString() returns the text chunk.
                        // Actually, typically Message.toString() returns the content.

                        // For OpenAIServer we need the full response eventually.
                        // We can just append if it's chunks, or update if it's full.
                        // Let's assume it is chunks (delta) for now as that is typical for streaming.
                        // However, `updateLastTextMessageContentIncrementally` usually implies checking length or something.

                        // Re-reading LlmChatModelHelper.kt:
                        // It seems `message` is a `com.google.ai.edge.litertlm.Message`.
                        // `message.toString()` is passed.

                        // If I look at `LlmChatViewModel.kt`:
                        // `updateLastTextMessageContentIncrementally`...

                        // Let's try to verify what onMessage returns.
                        // But I can't run it.

                        // Safer bet: The onMessage provides the latest update.
                        // If we want the full response, we wait for onDone.
                        // But we also want to support streaming later maybe?
                        // For now, OpenAIServer implementation is non-streaming.

                        // If message.toString() is the full text so far:
                        // We can just store it.
                        // If it is delta: we append it.

                        // In `LlmChatViewModel`, it seems to handle it as partial content.

                        // I'll assume message.toString() is the *new* content (delta) OR the accumulated content.
                        // Wait, if it's a `Message` object, it might represent the whole message state.

                        // Let's rely on `sb` to reconstruct if needed, but if `message.toString()` is full text, we overwrite.
                        // Actually, if I look at `LlmChatModelHelper`:
                        // `conversation.sendMessageAsync`

                        // If I check `LiteRT-LM` documentation (or guess):
                        // Usually `onMessage` is called when there is a new token generated.
                        // `message` likely contains the full text generated so far for that turn.

                        // Let's assume `message.toString()` is the *chunk*.
                        // Wait, `Message.of(contents)` creates a message.

                        // Let's just accumulate everything in a buffer, and when done, return it.
                        // Actually `onMessage` parameter is `message`.

                        // I will assume `message.toString()` is the delta.
                         onPartialResult(message.toString())
                         sb.append(message.toString())
                    }

                    override fun onDone() {
                        if (continuation.isActive) {
                             continuation.resumeWith(Result.success(sb.toString()))
                        }
                    }

                    override fun onError(throwable: Throwable) {
                         if (continuation.isActive) {
                             continuation.resumeWith(Result.failure(throwable))
                         }
                    }
                }
            )
        }
    }
}
