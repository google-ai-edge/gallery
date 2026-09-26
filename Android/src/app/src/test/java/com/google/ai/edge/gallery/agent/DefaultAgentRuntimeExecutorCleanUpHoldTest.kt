package com.google.ai.edge.gallery.agent

import android.graphics.Bitmap
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.apiserver.ApiServerSessionHold
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

private class FakeLlmSessionManagerForHoldTest : LlmSessionManager {
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

class DefaultAgentRuntimeExecutorCleanUpHoldTest {
  private fun newExecutor(hold: ApiServerSessionHold) =
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = FakeLlmSessionManagerForHoldTest(),
      apiServerSessionHold = hold,
    )

  @Test
  fun `cleanUp is a no-op when the hold matches the active model`() = runBlocking {
    val hold = ApiServerSessionHold().apply { heldModelName = "test-model" }
    val executor = newExecutor(hold)
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    var onDoneCalled = false
    executor.cleanUp { onDoneCalled = true }

    assertEquals("test-model", executor.activeModelInfo?.model?.name)
    assertEquals(true, onDoneCalled)
  }

  @Test
  fun `cleanUp tears down when the hold does not match`() = runBlocking {
    val hold = ApiServerSessionHold().apply { heldModelName = "other-model" }
    val executor = newExecutor(hold)
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    executor.cleanUp {}

    assertNull(executor.activeModelInfo)
  }

  @Test
  fun `cleanUp tears down when there is no hold`() = runBlocking {
    val executor = newExecutor(ApiServerSessionHold())
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    executor.cleanUp {}

    assertNull(executor.activeModelInfo)
  }
}
