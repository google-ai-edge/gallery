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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"
private val STATS =
  listOf(
    Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
    Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
    Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
    Stat(id = "latency", label = "Latency", unit = "sec"),
  )

open class LlmChatViewModelBase() : ChatViewModel() {
  fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onError: () -> Unit,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val instance = model.instance as LlmModelInstance
      var prefillTokens = instance.session.sizeInTokens(input)
      prefillTokens += images.size * 257
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
        // 150ms = 1 audio token
        val duration = audioMessage.getDurationInSeconds()
        prefillTokens += (duration * 1000f / 150f).toInt()
      }

      var firstRun = true
      var timeToFirstToken = 0f
      var firstTokenTs = 0L
      var decodeTokens = 0
      var prefillSpeed = 0f
      var decodeSpeed: Float
      val start = System.currentTimeMillis()

      try {
        LlmChatModelHelper.runInference(
          model = model,
          input = input,
          images = images,
          audioClips = audioClips,
          resultListener = { partialResult, done ->
            val curTs = System.currentTimeMillis()

            if (firstRun) {
              firstTokenTs = System.currentTimeMillis()
              timeToFirstToken = (firstTokenTs - start) / 1000f
              prefillSpeed = prefillTokens / timeToFirstToken
              firstRun = false
              setPreparing(false)
            } else {
              decodeTokens++
            }

            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            val lastMessage = getLastMessage(model = model)
            if (lastMessage?.type == ChatMessageType.LOADING) {
              removeLastMessage(model = model)

              // Add an empty message that will receive streaming results.
              addMessage(
                model = model,
                message =
                  ChatMessageText(content = "", side = ChatSide.AGENT, accelerator = accelerator),
              )
            }

            // Incrementally update the streamed partial results.
            val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
            updateLastTextMessageContentIncrementally(
              model = model,
              partialContent = partialResult,
              latencyMs = latencyMs.toFloat(),
            )

            if (done) {
              setInProgress(false)

              decodeSpeed = decodeTokens / ((curTs - firstTokenTs) / 1000f)
              if (decodeSpeed.isNaN()) {
                decodeSpeed = 0f
              }

              if (lastMessage is ChatMessageText) {
                updateLastTextMessageLlmBenchmarkResult(
                  model = model,
                  llmBenchmarkResult =
                    ChatMessageBenchmarkLlmResult(
                      orderedStats = STATS,
                      statValues =
                        mutableMapOf(
                          "prefill_speed" to prefillSpeed,
                          "decode_speed" to decodeSpeed,
                          "time_to_first_token" to timeToFirstToken,
                          "latency" to (curTs - start).toFloat() / 1000f,
                        ),
                      running = false,
                      latencyMs = -1f,
                      accelerator = accelerator,
                    ),
                )
              }
            }
          },
          cleanUpListener = {
            setInProgress(false)
            setPreparing(false)
          },
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError()
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      val instance = model.instance as LlmModelInstance
      instance.session.cancelGenerateResponseAsync()
    }
  }

  fun resetSession(task: Task, model: Model) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          LlmChatModelHelper.resetSession(task = task, model = model)
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
    }
  }

  fun runAgain(model: Model, message: ChatMessageText, onError: () -> Unit) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(model = model, input = message.content, onError = onError)
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    triggeredMessage: ChatMessageText?,
  ) {
    // Clean up.
    modelManagerViewModel.cleanupModel(context = context, task = task, model = model)

    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Remove the last Text message.
    if (getLastMessage(model = model) == triggeredMessage) {
      removeLastMessage(model = model)
    }

    // Add a warning message for re-initializing the session.
    addMessage(
      model = model,
      message = ChatMessageWarning(content = "Error occurred. Re-initializing the session."),
    )

    // Add the triggered message back.
    if (triggeredMessage != null) {
      addMessage(model = model, message = triggeredMessage)
    }

    // Re-initialize the session/engine.
    modelManagerViewModel.initializeModel(context = context, task = task, model = model)

    // Re-generate the response automatically.
    if (triggeredMessage != null) {
      generateResponse(model = model, input = triggeredMessage.content, onError = {})
    }
  }
}

@HiltViewModel class LlmChatViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
