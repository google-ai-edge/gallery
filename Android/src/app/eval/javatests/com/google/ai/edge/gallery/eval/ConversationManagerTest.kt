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

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationManagerTest {

  private lateinit var context: Context
  private lateinit var conversationManager: ConversationManager
  private lateinit var model: Model
  private lateinit var helper: FakeLlmModelHelper
  private val modelName = "test-model"
  private val sessionKey = "test-model:default"

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    conversationManager = ConversationManager()
    model = Model(name = modelName, runtimeType = RuntimeType.LITERT_LM, isLlm = true)
    helper = FakeLlmModelHelper()
  }

  @Test
  fun checkHistoryAndReset_newSession_resetsAndCaches() {
    val history = listOf(HistoryMessage("user", "Hello"))

    conversationManager.checkHistoryAndReset(sessionKey, history) { lmHistory ->
      helper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        initialMessages = lmHistory,
      )
    }

    assertThat(helper.resetCallCount.get()).isEqualTo(1)
    assertThat(helper.lastInitialMessagesSize).isEqualTo(1)
    assertThat(conversationManager.getHistory(sessionKey)).isEqualTo(history)
  }

  @Test
  fun checkHistoryAndReset_matchingHistory_doesNotReset() {
    val history = listOf(HistoryMessage("user", "Hello"))

    // First call to cache it
    conversationManager.checkHistoryAndReset(sessionKey, history) { lmHistory ->
      helper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        initialMessages = lmHistory,
      )
    }
    assertThat(helper.resetCallCount.get()).isEqualTo(1)

    // Second call with same history
    conversationManager.checkHistoryAndReset(sessionKey, history) { lmHistory ->
      helper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        initialMessages = lmHistory,
      )
    }
    assertThat(helper.resetCallCount.get()).isEqualTo(1) // Should still be 1
  }

  @Test
  fun checkHistoryAndReset_mismatchingHistory_resetsAndUpdates() {
    val history1 = listOf(HistoryMessage("user", "Hello"))
    val history2 =
      listOf(
        HistoryMessage("user", "Hello"),
        HistoryMessage("assistant", "Hi"),
        HistoryMessage("user", "How are you?"),
      )

    // First call
    conversationManager.checkHistoryAndReset(sessionKey, history1) { lmHistory ->
      helper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        initialMessages = lmHistory,
      )
    }
    assertThat(helper.resetCallCount.get()).isEqualTo(1)

    // Second call with different history
    conversationManager.checkHistoryAndReset(sessionKey, history2) { lmHistory ->
      helper.resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        initialMessages = lmHistory,
      )
    }
    assertThat(helper.resetCallCount.get()).isEqualTo(2)
    assertThat(helper.lastInitialMessagesSize).isEqualTo(3)
    assertThat(conversationManager.getHistory(sessionKey)).isEqualTo(history2)
  }

  @Test
  fun appendTurn_appendsCorrectly() {
    val initialHistory = listOf(HistoryMessage("user", "Hello"))
    conversationManager.updateHistory(sessionKey, initialHistory)

    conversationManager.appendTurn(sessionKey, "How are you?", "I am fine")

    val expected =
      initialHistory +
        HistoryMessage("user", "How are you?") +
        HistoryMessage("assistant", "I am fine")
    assertThat(conversationManager.getHistory(sessionKey)).isEqualTo(expected)
  }

  class FakeLlmModelHelper : LlmModelHelper {
    val resetCallCount = AtomicInteger(0)
    var lastInitialMessagesSize = -1

    override fun initialize(
      context: Context,
      model: Model,
      taskId: String,
      supportImage: Boolean,
      supportAudio: Boolean,
      onDone: (String) -> Unit,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      coroutineScope: CoroutineScope?,
    ) {}

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<Message>,
    ) {
      resetCallCount.incrementAndGet()
      lastInitialMessagesSize = initialMessages.size
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) {}

    override fun runInference(
      model: Model,
      input: String,
      resultListener: ResultListener,
      cleanUpListener: CleanUpListener,
      onError: (message: String) -> Unit,
      images: List<Bitmap>,
      audioClips: List<ByteArray>,
      coroutineScope: CoroutineScope?,
      extraContext: Map<String, String>?,
    ) {}

    override fun stopResponse(model: Model) {}
  }
}
