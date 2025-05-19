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

package com.google.ai.edge.gallery.ui.imageclassification

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageClassification
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_IMAGE_CLASSIFICATION
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.runBasicBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class ImageClassificationViewModel : ChatViewModel(task = TASK_IMAGE_CLASSIFICATION) {
  private val mutex = Mutex()

  fun generateResponse(context: Context, model: Model, input: Bitmap, primaryColor: Color) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      val result = ImageClassificationModelHelper.runInference(
        context = context, model = model, input = input, primaryColor = primaryColor
      )

      super.addMessage(
        model = model,
        message = ChatMessageClassification(
          classifications = result.categories,
          latencyMs = result.latencyMs,
          maxBarWidth = 300.dp,
        ),
      )
    }
  }

  fun generateStreamingResponse(
    context: Context,
    model: Model,
    input: Bitmap,
    primaryColor: Color
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      if (mutex.tryLock()) {
        try {
          val result = ImageClassificationModelHelper.runInference(
            context = context, model = model, input = input, primaryColor = primaryColor
          )
          updateStreamingMessage(
            model = model,
            message = ChatMessageClassification(
              classifications = result.categories,
              latencyMs = result.latencyMs
            )
          )
        } finally {
          mutex.unlock()
        }
      } else {
        // skip call if the previous call has not been finished (mutex is still locked).
      }
    }
  }

  fun benchmark(
    context: Context,
    model: Model,
    message: ChatMessage,
    warmupCount: Int,
    iterations: Int,
    primaryColor: Color
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      if (message is ChatMessageImage) {
        setInProgress(true)
        runBasicBenchmark(
          model = model,
          warmupCount = warmupCount,
          iterations = iterations,
          chatViewModel = this@ImageClassificationViewModel,
          inferenceFn = {
            ImageClassificationModelHelper.runInference(
              context = context,
              model = model,
              input = message.bitmap,
              primaryColor = primaryColor
            )
          },
          chatMessageType = ChatMessageType.BENCHMARK_RESULT,
        )
        setInProgress(false)
      }
    }
  }

  fun runAgain(context: Context, model: Model, message: ChatMessage, primaryColor: Color) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      if (message is ChatMessageImage) {
        // Clone the clicked message and add it.
        addMessage(model = model, message = message.clone())

        // Run inference.
        val result =
          ImageClassificationModelHelper.runInference(
            context = context,
            model = model,
            input = message.bitmap,
            primaryColor = primaryColor
          )

        // Add response message.
        val newMessage = generateClassificationMessage(result = result)
        addMessage(model = model, message = newMessage)
      }
    }
  }

  private fun generateClassificationMessage(result: ImageClassificationInferenceResult): ChatMessageClassification {
    return ChatMessageClassification(
      classifications = result.categories,
      latencyMs = result.latencyMs,
      maxBarWidth = 300.dp,
    )
  }
}
