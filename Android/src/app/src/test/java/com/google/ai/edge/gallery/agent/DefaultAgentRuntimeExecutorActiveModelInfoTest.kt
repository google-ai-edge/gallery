package com.google.ai.edge.gallery.agent

import android.graphics.Bitmap
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.apiserver.ApiServerSessionHold
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.RuntimeToolsProvider
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeLlmSessionManager : LlmSessionManager {
  override suspend fun createSession(config: SessionConfig): String = generateSessionId()

  override suspend fun loadSession(sessionId: String, config: SessionConfig): List<ChatMessageProto> =
    emptyList()

  override suspend fun resetSession(
    sessionId: String,
    config: SessionConfig,
    initialMessages: List<Message>,
    enableConversationConstrainedDecoding: Boolean,
  ) {}

  override suspend fun listSessions(taskId: String?): List<ChatSessionProto> = emptyList()

  override suspend fun deleteSession(sessionId: String) {}

  override suspend fun clearAllSessions() {}

  override suspend fun saveSessionHistory(
    sessionId: String,
    messages: List<ChatMessageProto>,
    originalModel: String?,
    taskId: String?,
  ) {}

  override suspend fun generateResponse(
    sessionId: String,
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    extraContext: Map<String, String>?,
  ) {}

  override fun stopResponse(sessionId: String, model: Model) {}

  override suspend fun linkFeedbackToSession(
    sessionId: String,
    feedbackId: String,
    messageIndex: Int?,
  ) {}
}

class DefaultAgentRuntimeExecutorActiveModelInfoTest {
  private fun newExecutor() =
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = FakeLlmSessionManager(),
      apiServerSessionHold = ApiServerSessionHold(),
    )

  @Test
  fun `activeModelInfo is null before any session is set`() {
    val executor = newExecutor()
    assertNull(executor.activeModelInfo)
  }

  @Test
  fun `activeModelInfo reflects the model from the last resetSession call`() = runBlocking {
    val executor = newExecutor()
    val model = Model(name = "test-model")

    executor.resetSession(
      AgentRuntimeConfig(model = model, taskId = "llm_chat", supportImage = true)
    )

    val info = executor.activeModelInfo
    assertEquals("test-model", info?.model?.name)
    assertEquals("llm_chat", info?.taskId)
    assertEquals(true, info?.supportImage)
  }

  @Test
  fun `activeModelInfo is null again after cleanUp`() = runBlocking {
    val executor = newExecutor()
    val model = Model(name = "test-model")
    executor.resetSession(AgentRuntimeConfig(model = model, taskId = "llm_chat"))

    // The model was never actually initialized (no `instance` set), so the real
    // LlmChatModelHelper.cleanUp short-circuits without invoking onDone -- that's fine, we're
    // only verifying activeModelInfo itself is cleared by cleanUp().
    executor.cleanUp {}

    assertNull(executor.activeModelInfo)
  }
}
