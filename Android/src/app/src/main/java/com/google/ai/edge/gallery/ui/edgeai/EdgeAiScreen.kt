package com.google.ai.edge.gallery.ui.edgeai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.UUID

// Simple message type used by the new UI layer
data class Message(
  val id: String = UUID.randomUUID().toString(),
  val role: String, // "user" or "assistant"
  val text: String,
)

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun EdgeAiEntryPoint(modelManagerViewModel: ModelManagerViewModel) {
  val llmChatViewModel: LlmChatViewModel = hiltViewModel()
  val context = LocalContext.current
  var onboarded by remember { mutableStateOf(isOnboarded(context)) }

  if (!onboarded) {
    EdgeOnboarding(
      modelManagerViewModel = modelManagerViewModel,
      onDone = { onboarded = true },
    )
  } else {
    EdgeAiScreen(
      modelManagerViewModel = modelManagerViewModel,
      llmChatViewModel = llmChatViewModel,
    )
  }
}

// ── Main screen ───────────────────────────────────────────────────────────────

enum class EdgeScreen { CHAT, MODELS, SETTINGS }

@Composable
fun EdgeAiScreen(
  modelManagerViewModel: ModelManagerViewModel,
  llmChatViewModel: LlmChatViewModel,
) {
  val context = LocalContext.current
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val chatState by llmChatViewModel.uiState.collectAsState()

  var screen by remember { mutableStateOf(EdgeScreen.CHAT) }
  var drawerOpen by remember { mutableStateOf(false) }
  var voiceModeOpen by remember { mutableStateOf(false) }

  // Resolve the LLM_CHAT task and active model
  val llmTask = mmState.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }
  val activeModel = mmState.selectedModel
    .takeIf { it.name.isNotEmpty() && it.isLlm }
    ?: llmTask?.models?.firstOrNull {
      mmState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
    ?: llmTask?.models?.firstOrNull()

  // Initialize the model when it becomes available and is not yet initialized
  LaunchedEffect(activeModel?.name) {
    val model = activeModel ?: return@LaunchedEffect
    val task = llmTask ?: return@LaunchedEffect
    val initStatus = mmState.modelInitializationStatus[model.name]
    if (initStatus == null ||
      initStatus.status == com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType.NOT_INITIALIZED
    ) {
      modelManagerViewModel.initializeModel(context, task, model) {}
    }
  }

  // Reset chat session when active model changes
  LaunchedEffect(activeModel?.name) {
    val model = activeModel ?: return@LaunchedEffect
    val task = llmTask ?: return@LaunchedEffect
    llmChatViewModel.resetSession(task = task, model = model)
  }

  // Convert real ChatMessages → our simple Message type
  val realMessages = activeModel?.let { model ->
    chatState.messagesByModel[model.name]
      ?.filterIsInstance<ChatMessageText>()
      ?.map { msg ->
        Message(
          role = if (msg.side == ChatSide.USER) "user" else "assistant",
          text = msg.content,
        )
      } ?: emptyList()
  } ?: emptyList()

  val streamingText = activeModel?.let { model ->
    (chatState.streamingMessagesByModel[model.name] as? ChatMessageText)?.content ?: ""
  } ?: ""

  val streaming = chatState.inProgress
  val activeModelName = activeModel?.displayName?.ifEmpty { activeModel.name } ?: "No model"

  // send message via real inference
  fun sendMessage(text: String) {
    val model = activeModel ?: return
    llmChatViewModel.generateResponse(
      model = model,
      input = text,
      onError = { /* errors surface as ChatMessageError in the stream */ },
    )
  }

  // clear chat
  fun newChat() {
    val model = activeModel ?: return
    val task = llmTask ?: return
    llmChatViewModel.resetSession(task = task, model = model)
  }

  EdgeChatScreen(
    messages = realMessages,
    streaming = streaming,
    streamingText = streamingText,
    activeModelName = activeModelName,
    drawerOpen = drawerOpen,
    onMenuClick = { drawerOpen = true },
    onDrawerClose = { drawerOpen = false },
    onNewChat = { newChat() },
    onSend = { sendMessage(it) },
    onModelChipClick = { screen = EdgeScreen.MODELS },
    onVoiceClick = { voiceModeOpen = true },
    onModelsNav = { screen = EdgeScreen.MODELS },
    onSettingsNav = { screen = EdgeScreen.SETTINGS },
  )

  if (screen == EdgeScreen.MODELS) {
    EdgeModelHub(
      modelManagerViewModel = modelManagerViewModel,
      onBack = { screen = EdgeScreen.CHAT },
      onModelSelected = { model ->
        modelManagerViewModel.selectModel(model)
        screen = EdgeScreen.CHAT
      },
    )
  }

  if (screen == EdgeScreen.SETTINGS) {
    EdgeSettings(onBack = { screen = EdgeScreen.CHAT })
  }

  if (voiceModeOpen) {
    EdgeVoiceMode(onDismiss = { voiceModeOpen = false })
  }
}
