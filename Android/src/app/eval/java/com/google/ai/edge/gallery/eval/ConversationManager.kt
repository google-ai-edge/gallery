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
import com.google.ai.edge.litertlm.Message as LmMessage
import java.util.concurrent.ConcurrentHashMap

class ConversationManager {

  private val sessionHistory = ConcurrentHashMap<String, List<HistoryMessage>>()

  fun checkHistoryAndReset(
    sessionKey: String,
    incomingHistory: List<HistoryMessage>,
    resetAction: (List<LmMessage>) -> Unit,
  ) {
    val cachedHistory = sessionHistory[sessionKey]
    if (cachedHistory == null || cachedHistory != incomingHistory) {
      Log.i(TAG, "History mismatch or new session. Resetting conversation for $sessionKey.")
      val lmHistory = PromptParser.convertToLmMessages(incomingHistory)
      resetAction(lmHistory)
      sessionHistory[sessionKey] = incomingHistory
    } else {
      Log.i(TAG, "History match. Appending to existing conversation for $sessionKey.")
    }
  }

  fun appendTurn(sessionKey: String, promptContentStr: String, assistantResult: String) {
    val currentHistory = sessionHistory[sessionKey] ?: emptyList()
    sessionHistory[sessionKey] =
      currentHistory +
        HistoryMessage("user", promptContentStr) +
        HistoryMessage("assistant", assistantResult)
  }

  // Exposed for testing
  fun getHistory(sessionKey: String): List<HistoryMessage>? {
    return sessionHistory[sessionKey]
  }

  // Exposed for testing
  fun updateHistory(sessionKey: String, history: List<HistoryMessage>) {
    sessionHistory[sessionKey] = history
  }

  companion object {
    private const val TAG = "ConversationManager"
  }
}
