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

package com.google.ai.edge.gallery.ui.textclassification

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.components.containers.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_TEXT_CLASSIFICATION
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageClassification
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Classification
import com.google.ai.edge.gallery.ui.common.getDistinctiveColor
import com.google.ai.edge.gallery.ui.common.runBasicBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGTextClassificationViewModel"

class TextClassificationViewModel : ChatViewModel(task = TASK_TEXT_CLASSIFICATION) {
  fun generateResponse(model: Model, input: String) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      val result = TextClassificationModelHelper.runInference(model = model, input = input)
      Log.d(TAG, "$result")

      addMessage(
        model = model,
        message = generateClassificationMessage(result = result),
      )
    }
  }

  fun runAgain(model: Model, message: ChatMessage) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      if (message is ChatMessageText) {
        // Clone the clicked message and add it.
        addMessage(model = model, message = message.clone())

        // Run inference.
        val result =
          TextClassificationModelHelper.runInference(model = model, input = message.content)

        // Add response message.
        val newMessage = generateClassificationMessage(result = result)
        addMessage(
          model = model,
          message = newMessage,
        )
      }
    }
  }

  fun benchmark(
    model: Model, message: ChatMessage, warmupCount: Int, itertations: Int
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      if (message is ChatMessageText) {
        setInProgress(true)
        runBasicBenchmark(
          model = model,
          warmupCount = warmupCount,
          iterations = itertations,
          chatViewModel = this@TextClassificationViewModel,
          inferenceFn = {
            TextClassificationModelHelper.runInference(model = model, input = message.content)
          },
          chatMessageType = ChatMessageType.BENCHMARK_RESULT,
        )
        setInProgress(false)
      }
    }
  }

  private fun generateClassificationMessage(result: TextClassificationInferenceResult): ChatMessageClassification {
    return ChatMessageClassification(classifications = result.categories.mapIndexed { index, category ->
      val color = when (category.categoryName().lowercase()) {
        "negative", "0" -> Color(0xffe6194B)
        "positive", "1" -> Color(0xff3cb44b)
        else -> getDistinctiveColor(index)
      }
      category.toClassification(color = color)
    }.sortedBy { it.label }, latencyMs = result.latencyMs)
  }
}

fun Category.toClassification(color: Color): Classification {
  var categoryName = this.categoryName()
  if (categoryName == "0") {
    categoryName = "negative"
  } else if (categoryName == "1") {
    categoryName = "positive"
  }
  return Classification(label = categoryName, score = this.score(), color = color)
}
