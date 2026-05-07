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

package com.google.ai.edge.gallery.ui.common.chat

// import com.google.ai.edge.gallery.ui.preview.PreviewChatModel
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGChatView"

data class SendMessageTrigger(val model: Model, val messages: List<ChatMessage>)

/**
 * A composable that displays a chat interface, allowing users to interact with different models
 * associated with a given task.
 *
 * This composable provides a horizontal pager for switching between models, a model selector for
 * configuring the selected model, and a chat panel for sending and receiving messages. It also
 * manages model initialization, cleanup, and download status, and handles navigation and system
 * back gestures.
 */
@Composable
fun ChatView(
  task: Task,
  viewModel: ChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, Int, Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onResetSessionClicked: (Model) -> Unit = {},
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStopButtonClicked: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  showStopButtonInInputWhenInProgress: Boolean = false,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  // Image viewer related.
  var selectedImageIndex by remember { mutableIntStateOf(-1) }
  var allImageViewerImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  var showImageViewer by remember { mutableStateOf(false) }

  // Chat history drawer.
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val allHistorySessions by viewModel.historySessions.collectAsState()
  val historySessions =
    remember(allHistorySessions, task.id) { allHistorySessions.filter { it.taskId == task.id } }

  val context = LocalContext.current
  var feedFullHistoryOnNextMessage by remember { mutableStateOf(false) }

  val currentMessages = uiState.messagesByModel[selectedModel.name] ?: emptyList()
  LaunchedEffect(uiState.inProgress) {
    if (!uiState.inProgress && currentMessages.isNotEmpty()) {
      viewModel.saveSession(
        sessionId = viewModel.currentSessionId,
        messages = currentMessages,
        originalModel = selectedModel.name,
        taskId = task.id,
      )
    }
  }
  val scope = rememberCoroutineScope()
  var navigatingUp by remember { mutableStateOf(false) }

  val handleNavigateUp = {
    navigatingUp = true
    navigateUp()

    // clean up all models.
    scope.launch(Dispatchers.Default) {
      for (model in task.models) {
        modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
      }
    }
  }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(TAG, "Initializing model '${selectedModel.name}' from ChatView launched effect")
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  LaunchedEffect(sendMessageTrigger) {
    sendMessageTrigger?.let { trigger -> onSendMessage(trigger.model, trigger.messages) }
  }

  // Handle system's edge swipe.
  BackHandler {
    val modelInitializationStatus =
      modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelInitializing =
      modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
    if (!isModelInitializing && !uiState.inProgress) {
      handleNavigateUp()
    }
  }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
      drawerState = drawerState,
      drawerContent = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          ModalDrawerSheet {
            ChatHistorySideSheetContent(
              history = historySessions,
              onHistoryItemClicked = { sessionId ->
                val session = historySessions.firstOrNull { it.sessionId == sessionId }
                if (session != null) {
                  onResetSessionClicked(selectedModel)
                  viewModel.clearAllMessages(selectedModel)

                  val messages = deserializeProtoMessages(session.messagesList)
                  for (msg in messages) {
                    viewModel.addMessage(selectedModel, msg)
                  }

                  viewModel.currentSessionId = session.sessionId
                  feedFullHistoryOnNextMessage = true
                }
                scope.launch { drawerState.close() }
              },
              onHistoryItemDeleted = { sessionId -> viewModel.deleteSession(sessionId) },
              onHistoryItemsDeleteAll = { viewModel.clearAllSessions() },
              onNewChatClicked = {
                onResetSessionClicked(selectedModel)
                viewModel.currentSessionId = UUID.randomUUID().toString()
                scope.launch { drawerState.close() }
              },
              onDismissed = { scope.launch { drawerState.close() } },
            )
          }
        }
      },
      gesturesEnabled = drawerState.isOpen,
    ) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
          modifier = modifier,
          topBar = {
            ModelPageAppBar(
              task = task,
              model = selectedModel,
              modelManagerViewModel = modelManagerViewModel,
              inProgress = uiState.inProgress,
              modelPreparing = uiState.preparing,
              shouldShowHistoryButton = true,
              onConfigChanged = { old, new ->
                // Filter out config values that are not relevant to the task.
                //
                // - The "reset conversation turn count" is only valid for tiny garden task.
                val filteredOld = old.toMutableMap()
                val filteredNew = new.toMutableMap()
                if (task.id != BuiltInTaskId.LLM_TINY_GARDEN) {
                  filteredOld.remove(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label)
                  filteredNew.remove(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label)
                }
                viewModel.addConfigChangedMessage(
                  oldConfigValues = filteredOld,
                  newConfigValues = filteredNew,
                  model = selectedModel,
                )
              },
              onBackClicked = { handleNavigateUp() },
              onModelSelected = { prevModel, curModel ->
                if (prevModel.name != curModel.name) {
                  modelManagerViewModel.cleanupModel(
                    context = context,
                    task = task,
                    model = prevModel,
                  )
                }
                modelManagerViewModel.selectModel(model = curModel)
              },
              allowEditingSystemPrompt = allowEditingSystemPrompt,
              curSystemPrompt = curSystemPrompt,
              onSystemPromptChanged = onSystemPromptChanged,
              onHistoryClicked = { scope.launch { drawerState.open() } },
            )
          },
        ) { innerPadding ->
          Box {
            val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]

            composableBelowMessageList(selectedModel)

            Column(
              modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            ) {
              AnimatedContent(
                targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
              ) { targetState ->
                when (targetState) {
                  // Main UI when model is downloaded.
                  true ->
                    ChatPanel(
                      modelManagerViewModel = modelManagerViewModel,
                      task = task,
                      selectedModel = selectedModel,
                      viewModel = viewModel,
                      innerPadding = innerPadding,
                      navigateUp = navigateUp,
                      // TODO(zichuanwei): Update the logic here to use the proper litertlm api.
                      // the current logic is to be compatible with AICore logic, as AI core doesn't
                      // support message preloading or multi-turn conversations.
                      onSendMessage = { model, messages ->
                        if (feedFullHistoryOnNextMessage) {
                          feedFullHistoryOnNextMessage = false
                          val history = uiState.messagesByModel[model.name] ?: emptyList()
                          val originalShortMessage = messages.lastOrNull() as? ChatMessageText
                          val combinedMessage =
                            if (originalShortMessage != null) {
                              buildFirstMessageWithHistory(history, originalShortMessage)
                            } else null
                          if (combinedMessage != null) {
                            val modifiedList = messages.dropLast(1) + combinedMessage
                            onSendMessage(model, modifiedList)

                            // Revert the visible UI message back to the short one
                            scope.launch(Dispatchers.Default) {
                              delay(100)
                              viewModel.replaceLastMessage(
                                model,
                                originalShortMessage!!,
                                ChatMessageType.TEXT,
                              )
                            }
                          } else {
                            onSendMessage(model, messages)
                          }
                        } else {
                          onSendMessage(model, messages)
                        }
                      },
                      onRunAgainClicked = onRunAgainClicked,
                      onBenchmarkClicked = onBenchmarkClicked,
                      onStreamImageMessage = onStreamImageMessage,
                      onStreamEnd = { averageFps ->
                        viewModel.addMessage(
                          model = selectedModel,
                          message =
                            ChatMessageInfo(
                              content = "Live camera session ended. Average FPS: $averageFps"
                            ),
                        )
                      },
                      onStopButtonClicked = { onStopButtonClicked(selectedModel) },
                      onImageSelected = { bitmaps, selectedBitmapIndex ->
                        selectedImageIndex = selectedBitmapIndex
                        allImageViewerImages = bitmaps
                        showImageViewer = true
                      },
                      onSkillClicked = onSkillClicked,
                      modifier = Modifier.weight(1f),
                      showStopButtonInInputWhenInProgress = showStopButtonInInputWhenInProgress,
                      showImagePicker = showImagePicker,
                      showAudioPicker = showAudioPicker,
                      emptyStateComposable = emptyStateComposable,
                    )
                  // Model download
                  false ->
                    ModelDownloadStatusInfoPanel(
                      model = selectedModel,
                      task = task,
                      modelManagerViewModel = modelManagerViewModel,
                    )
                }
              }
            }

            // Image viewer.
            AnimatedVisibility(
              visible = showImageViewer,
              enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }) + fadeIn(),
              exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }) + fadeOut(),
            ) {
              val pagerState =
                rememberPagerState(
                  pageCount = { allImageViewerImages.size },
                  initialPage = selectedImageIndex,
                )
              val scrollEnabled = remember { mutableStateOf(true) }
              Box(
                modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())
              ) {
                HorizontalPager(
                  state = pagerState,
                  userScrollEnabled = scrollEnabled.value,
                  modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)),
                ) { page ->
                  allImageViewerImages[page].let { image ->
                    ZoomableImage(
                      bitmap = image.asImageBitmap(),
                      pagerState = pagerState,
                      modifier = Modifier.fillMaxSize(),
                    )
                  }
                }

                // Close button.
                IconButton(
                  onClick = { showImageViewer = false },
                  colors =
                    IconButtonDefaults.iconButtonColors(
                      containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                  modifier = Modifier.offset(x = (-8).dp, y = 8.dp).align(Alignment.TopEnd),
                ) {
                  Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close_image_viewer_icon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Helper function to construct the first message when a session is restored from history.
 *
 * It prepends the entire text chat history (from User and Model) as context for the message,
 * ensuring the model understands the prior conversation when running the newly restored session.
 *
 * @param history The list of past messages for the selected model.
 * @param originalShortMessage The newly entered message to be added to the history.
 * @return A new [ChatMessageText] with history prepended, or null if there is no valid history.
 */
private fun buildFirstMessageWithHistory(
  history: List<ChatMessage>,
  originalShortMessage: ChatMessageText,
): ChatMessageText? {
  val prefix =
    history
      .mapNotNull {
        when (it) {
          is ChatMessageText ->
            if (it.side == ChatSide.USER) "User:\n${it.content}" else "Model:\n${it.content}"
          else -> null
        }
      }
      .joinToString("\n\n")

  if (prefix.isEmpty()) {
    return null
  }

  return ChatMessageText(
    content = "$prefix\n\nUser:\n${originalShortMessage.content}",
    side = originalShortMessage.side,
    latencyMs = originalShortMessage.latencyMs,
    isMarkdown = originalShortMessage.isMarkdown,
    llmBenchmarkResult = originalShortMessage.llmBenchmarkResult,
    accelerator = originalShortMessage.accelerator,
    hideSenderLabel = originalShortMessage.hideSenderLabel,
    data = originalShortMessage.data,
  )
}

/**
 * Deserializes a list of [com.google.ai.edge.gallery.proto.ChatMessageProto] from persistent
 * storage into the corresponding [ChatMessage] UI models.
 *
 * @param protoMessages The list of saved protobuf messages.
 * @return The list of restored UI/domain message objects.
 */
private fun deserializeProtoMessages(
  protoMessages: List<com.google.ai.edge.gallery.proto.ChatMessageProto>
): List<ChatMessage> {
  return protoMessages.mapNotNull { protoMsg ->
    val side =
      when (protoMsg.side) {
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_USER -> ChatSide.USER
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_MODEL -> ChatSide.AGENT
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_SYSTEM -> ChatSide.SYSTEM
        else -> ChatSide.SYSTEM
      }

    when (protoMsg.messageType) {
      "TEXT" ->
        ChatMessageText(
          content = protoMsg.content,
          side = side,
          latencyMs = protoMsg.latencyMs,
          isMarkdown = protoMsg.isMarkdown,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "THINKING" ->
        ChatMessageThinking(
          content = protoMsg.content,
          side = side,
          inProgress = protoMsg.inProgress,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "INFO" -> ChatMessageInfo(protoMsg.content)
      "WARNING" -> ChatMessageWarning(protoMsg.content)
      "ERROR" -> ChatMessageError(protoMsg.content)
      else -> null
    }
  }
}
