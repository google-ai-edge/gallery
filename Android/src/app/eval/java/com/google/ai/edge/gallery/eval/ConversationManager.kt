/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import java.util.concurrent.ConcurrentHashMap

class ConversationManager {

  private val modelHistory = ConcurrentHashMap<String, List<HistoryMessage>>()

  fun checkHistoryAndReset(
    modelName: String,
    incomingHistory: List<HistoryMessage>,
    model: Model,
    helper: LlmModelHelper,
  ) {
    val cachedHistory = modelHistory[modelName]
    if (cachedHistory == null || cachedHistory != incomingHistory) {
      Log.i(TAG, "History mismatch or new session. Resetting conversation for $modelName.")
      val lmHistory = PromptParser.convertToLmMessages(incomingHistory)
      helper.resetConversation(
        model = model,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        initialMessages = lmHistory,
      )
      modelHistory[modelName] = incomingHistory
    } else {
      Log.i(TAG, "History match. Appending to existing conversation for $modelName.")
    }
  }

  fun appendTurn(modelName: String, promptContentStr: String, assistantResult: String) {
    val currentHistory = modelHistory[modelName] ?: emptyList()
    modelHistory[modelName] =
      currentHistory +
        HistoryMessage("user", promptContentStr) +
        HistoryMessage("assistant", assistantResult)
  }

  // Exposed for testing
  fun getHistory(modelName: String): List<HistoryMessage>? {
    return modelHistory[modelName]
  }

  // Exposed for testing
  fun updateHistory(modelName: String, history: List<HistoryMessage>) {
    modelHistory[modelName] = history
  }

  companion object {
    private const val TAG = "ConversationManager"
  }
}
