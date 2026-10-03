package com.google.ai.edge.gallery.runtime.aicore

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

object AICoreModelHelper : LlmModelHelper {
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
  ) {
    onDone("AICore is only supported on Google Pixel Android devices.")
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {}

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: (String, Boolean, String?) -> Unit,
    cleanUpListener: () -> Unit,
    onError: (String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
    sessionId: String?,
    messageIndex: Int?,
  ) {
    resultListener("AICore is only available on supported Android devices.", true, null)
    cleanUpListener()
  }

  override fun stopResponse(model: Model) {}

  fun downloadModel(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onProgress: (downloaded: Long, total: Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    onError("AICore models are not supported on Windows.")
  }
}
