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
import android.content.ClipData
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TaskType
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.launch

enum class ChatInputType {
  TEXT,
  IMAGE,
}

/** Composable function for the main chat panel, displaying messages and handling user input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  selectedModel: Model,
  viewModel: ChatViewModel,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, warmUpIterations: Int, benchmarkIterations: Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStreamEnd: (Int) -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onImageSelected: (Bitmap) -> Unit = {},
  chatInputType: ChatInputType = ChatInputType.TEXT,
  showStopButtonInInputWhenInProgress: Boolean = false,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val messages = uiState.messagesByModel[selectedModel.name] ?: listOf()
  val streamingMessage = uiState.streamingMessagesByModel[selectedModel.name]
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val imageMessageCountToLastConfigChange =
    remember(messages) {
      var imageMessageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageImage) {
          imageMessageCount++
        }
      }
      imageMessageCount
    }
  val audioClipMesssageCountToLastconfigChange =
    remember(messages) {
      var audioClipMessageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageAudioClip) {
          audioClipMessageCount++
        }
      }
      audioClipMessageCount
    }

  var curMessage by remember { mutableStateOf("") } // Correct state
  val focusManager = LocalFocusManager.current

  // Remember the LazyListState to control scrolling
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  var showBenchmarkConfigsDialog by remember { mutableStateOf(false) }
  val benchmarkMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }

  var showMessageLongPressedSheet by remember { mutableStateOf(false) }
  val longPressedMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }

  var showErrorDialog by remember { mutableStateOf(false) }

  // Keep track of the last message and last message content.
  val lastMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }
  val lastMessageContent: MutableState<String> = remember { mutableStateOf("") }
  if (messages.isNotEmpty()) {
    val tmpLastMessage = messages.last()
    lastMessage.value = tmpLastMessage
    if (tmpLastMessage is ChatMessageText) {
      lastMessageContent.value = tmpLastMessage.content
    }
  }
  val lastShowingStatsByModel: MutableState<Map<String, MutableSet<ChatMessage>>> = remember {
    mutableStateOf(mapOf())
  }

  // Scroll the content to the bottom when any of these changes.
  LaunchedEffect(
    messages.size,
    lastMessage.value,
    lastMessageContent.value,
    WindowInsets.ime.getBottom(density),
  ) {
    // Only scroll if showingStatsByModel is not changed. In other words, when showingStatsByModel
    // changes we want the display to not scroll.
    if (messages.isNotEmpty()) {
      if (uiState.showingStatsByModel === lastShowingStatsByModel.value) {
        listState.animateScrollToItem(messages.lastIndex, scrollOffset = 10000)
      } else {
        // Scroll to bottom if the message to show stats is the last message.
        val curShowingStats =
          uiState.showingStatsByModel[selectedModel.name]?.toMutableSet() ?: mutableSetOf()
        val lastShowingStats = lastShowingStatsByModel.value[selectedModel.name] ?: mutableSetOf()
        curShowingStats.removeAll(lastShowingStats)
        if (curShowingStats.isNotEmpty()) {
          val index =
            viewModel.getMessageIndex(model = selectedModel, message = curShowingStats.first())
          if (index == messages.size - 2) {
            listState.animateScrollToItem(messages.lastIndex, scrollOffset = 10000)
          }
        }
      }
    }
    lastShowingStatsByModel.value = uiState.showingStatsByModel
  }

  val nestedScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If downward scroll, clear the focus from any currently focused composable.
        // This is useful for dismissing software keyboards or hiding text input fields
        // when the user starts scrolling down a list.
        if (available.y > 0) {
          focusManager.clearFocus()
        }
        // Let LazyColumn handle the scroll
        return Offset.Zero
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]

  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Column(modifier = modifier.imePadding()) {
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
        state = listState,
        verticalArrangement = Arrangement.Top,
      ) {
        items(messages) { message ->
          val imageHistoryCurIndex = remember { mutableIntStateOf(0) }
          var hAlign: Alignment.Horizontal = Alignment.End
          var backgroundColor: Color = MaterialTheme.customColors.userBubbleBgColor
          var hardCornerAtLeftOrRight = false
          var extraPaddingStart = 48.dp
          var extraPaddingEnd = 0.dp
          if (message.side == ChatSide.AGENT) {
            hAlign = Alignment.Start
            backgroundColor = MaterialTheme.customColors.agentBubbleBgColor
            hardCornerAtLeftOrRight = true
            extraPaddingStart = 0.dp
            extraPaddingEnd = 48.dp
          } else if (message.side == ChatSide.SYSTEM) {
            extraPaddingStart = 24.dp
            extraPaddingEnd = 24.dp
            if (message.type == ChatMessageType.PROMPT_TEMPLATES) {
              extraPaddingStart = 12.dp
              extraPaddingEnd = 12.dp
            }
          }
          if (message.type == ChatMessageType.IMAGE) {
            backgroundColor = Color.Transparent
          }
          val bubbleBorderRadius = dimensionResource(R.dimen.chat_bubble_corner_radius)

          Column(
            modifier =
              Modifier.fillMaxWidth()
                .padding(
                  start = 12.dp + extraPaddingStart,
                  end = 12.dp + extraPaddingEnd,
                  top = 6.dp,
                  bottom = 6.dp,
                ),
            horizontalAlignment = hAlign,
          ) messageColumn@{
            // Sender row.
            var agentName = stringResource(task.agentNameRes)
            if (message.accelerator.isNotEmpty()) {
              agentName = "$agentName on ${message.accelerator}"
            }
            MessageSender(
              message = message,
              agentName = agentName,
              imageHistoryCurIndex = imageHistoryCurIndex.intValue,
            )

            // Message body.
            when (message) {
              // Loading.
              is ChatMessageLoading -> MessageBodyLoading()

              // Info.
              is ChatMessageInfo -> MessageBodyInfo(message = message)

              // Warning
              is ChatMessageWarning -> MessageBodyWarning(message = message)

              // Config values change.
              is ChatMessageConfigValuesChange -> MessageBodyConfigUpdate(message = message)

              // Prompt templates.
              is ChatMessagePromptTemplates ->
                MessageBodyPromptTemplates(
                  message = message,
                  task = task,
                  onPromptClicked = { template ->
                    onSendMessage(
                      selectedModel,
                      listOf(ChatMessageText(content = template.prompt, side = ChatSide.USER)),
                    )
                  },
                )

              // Non-system messages.
              else -> {
                // The bubble shape around the message body.
                var messageBubbleModifier =
                  Modifier.clip(
                      MessageBubbleShape(
                        radius = bubbleBorderRadius,
                        hardCornerAtLeftOrRight = hardCornerAtLeftOrRight,
                      )
                    )
                    .background(backgroundColor)
                if (message is ChatMessageText) {
                  messageBubbleModifier =
                    messageBubbleModifier.pointerInput(Unit) {
                      detectTapGestures(
                        onLongPress = {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                          longPressedMessage.value = message
                          showMessageLongPressedSheet = true
                        }
                      )
                    }
                }
                Box(modifier = messageBubbleModifier) {
                  when (message) {
                    // Text
                    is ChatMessageText -> MessageBodyText(message = message)

                    // Image
                    is ChatMessageImage -> {
                      MessageBodyImage(
                        message = message,
                        modifier = Modifier.clickable { onImageSelected(message.bitmap) },
                      )
                    }

                    // Image with history (for image gen)
                    is ChatMessageImageWithHistory ->
                      MessageBodyImageWithHistory(
                        message = message,
                        imageHistoryCurIndex = imageHistoryCurIndex,
                      )

                    // Audio clip.
                    is ChatMessageAudioClip -> MessageBodyAudioClip(message = message)

                    // Classification result
                    is ChatMessageClassification ->
                      MessageBodyClassification(
                        message = message,
                        modifier =
                          Modifier.width(message.maxBarWidth ?: CLASSIFICATION_BAR_MAX_WIDTH),
                      )

                    // Benchmark result.
                    is ChatMessageBenchmarkResult -> MessageBodyBenchmark(message = message)

                    // Benchmark LLM result.
                    is ChatMessageBenchmarkLlmResult ->
                      MessageBodyBenchmarkLlm(
                        message = message,
                        modifier = Modifier.wrapContentWidth(),
                      )

                    else -> {}
                  }
                }

                if (message.side == ChatSide.AGENT) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    LatencyText(message = message)
                    // A button to show stats for the LLM message.
                    if (
                      task.type.id.startsWith("llm_") &&
                        message is ChatMessageText
                        // This means we only want to show the action button when the message is
                        // done
                        // generating, at which point the latency will be set.
                        &&
                        message.latencyMs >= 0
                    ) {
                      val showingStats =
                        viewModel.isShowingStats(model = selectedModel, message = message)
                      MessageActionButton(
                        label = if (showingStats) "Hide stats" else "Show stats",
                        icon = Icons.Outlined.Timer,
                        onClick = {
                          // Toggle showing stats.
                          viewModel.toggleShowingStats(selectedModel, message)

                          // Add the stats message after the LLM message.
                          if (viewModel.isShowingStats(model = selectedModel, message = message)) {
                            val llmBenchmarkResult = message.llmBenchmarkResult
                            if (llmBenchmarkResult != null) {
                              viewModel.insertMessageAfter(
                                model = selectedModel,
                                anchorMessage = message,
                                messageToAdd = llmBenchmarkResult,
                              )
                            }
                          }
                          // Remove the stats message.
                          else {
                            val curMessageIndex =
                              viewModel.getMessageIndex(model = selectedModel, message = message)
                            viewModel.removeMessageAt(
                              model = selectedModel,
                              index = curMessageIndex + 1,
                            )
                          }
                        },
                        enabled = !uiState.inProgress,
                      )
                    }
                  }
                } else if (message.side == ChatSide.USER) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    // Run again button.
                    if (selectedModel.showRunAgainButton) {
                      MessageActionButton(
                        label = stringResource(R.string.run_again),
                        icon = Icons.Rounded.Refresh,
                        onClick = { onRunAgainClicked(selectedModel, message) },
                        enabled = !uiState.inProgress,
                      )
                    }

                    // Benchmark button
                    if (selectedModel.showBenchmarkButton) {
                      MessageActionButton(
                        label = stringResource(R.string.benchmark),
                        icon = Icons.Outlined.Timer,
                        onClick = {
                          showBenchmarkConfigsDialog = true
                          benchmarkMessage.value = message
                        },
                        enabled = !uiState.inProgress,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(vertical = 4.dp))

      // Show an info message for ask image task to get users started.
      if (task.type == TaskType.LLM_ASK_IMAGE && messages.isEmpty()) {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp).fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          MessageBodyInfo(
            ChatMessageInfo(
              content =
                "To get started, click + below to add images (up to 10 in a single session) and type a prompt to ask a question about it."
            ),
            smallFontSize = false,
          )
        }
      }
      // Show an info message for ask image task to get users started.
      else if (task.type == TaskType.LLM_ASK_AUDIO && messages.isEmpty()) {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp).fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          MessageBodyInfo(
            ChatMessageInfo(
              content =
                "To get started, tap the + icon to add your audio clip. Limited to 1 clip up to 30 seconds long."
            ),
            smallFontSize = false,
          )
        }
      }
    }

    // Chat input
    when (chatInputType) {
      ChatInputType.TEXT -> {
        //        val isLlmTask = task.type == TaskType.LLM_CHAT
        //        val notLlmStartScreen = !(messages.size == 1 && messages[0] is
        // ChatMessagePromptTemplates)
        MessageInputText(
          modelManagerViewModel = modelManagerViewModel,
          curMessage = curMessage,
          inProgress = uiState.inProgress,
          isResettingSession = uiState.isResettingSession,
          modelPreparing = uiState.preparing,
          imageMessageCount = imageMessageCountToLastConfigChange,
          audioClipMessageCount = audioClipMesssageCountToLastconfigChange,
          modelInitializing =
            modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING,
          textFieldPlaceHolderRes = task.textInputPlaceHolderRes,
          onValueChanged = { curMessage = it },
          onSendMessage = {
            onSendMessage(selectedModel, it)
            curMessage = ""
          },
          onOpenPromptTemplatesClicked = {
            onSendMessage(
              selectedModel,
              listOf(
                ChatMessagePromptTemplates(
                  templates = selectedModel.llmPromptTemplates,
                  showMakeYourOwn = false,
                )
              ),
            )
          },
          onStopButtonClicked = onStopButtonClicked,
          //          showPromptTemplatesInMenu = isLlmTask && notLlmStartScreen,
          showPromptTemplatesInMenu = false,
          showImagePickerInMenu =
            selectedModel.llmSupportImage && task.type === TaskType.LLM_ASK_IMAGE,
          showAudioItemsInMenu =
            selectedModel.llmSupportAudio && task.type === TaskType.LLM_ASK_AUDIO,
          showStopButtonWhenInProgress = showStopButtonInInputWhenInProgress,
        )
      }

      ChatInputType.IMAGE ->
        MessageInputImage(
          disableButtons = uiState.inProgress,
          streamingMessage = streamingMessage,
          onImageSelected = { bitmap ->
            onSendMessage(
              selectedModel,
              listOf(
                ChatMessageImage(
                  bitmap = bitmap,
                  imageBitMap = bitmap.asImageBitmap(),
                  side = ChatSide.USER,
                )
              ),
            )
          },
          onStreamImage = { bitmap ->
            onStreamImageMessage(
              selectedModel,
              ChatMessageImage(
                bitmap = bitmap,
                imageBitMap = bitmap.asImageBitmap(),
                side = ChatSide.USER,
              ),
            )
          },
          onStreamEnd = onStreamEnd,
        )
    }
  }

  // Error dialog.
  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = { showErrorDialog = false },
    )
  }

  // Benchmark config dialog.
  if (showBenchmarkConfigsDialog) {
    BenchmarkConfigDialog(
      onDismissed = { showBenchmarkConfigsDialog = false },
      messageToBenchmark = benchmarkMessage.value,
      onBenchmarkClicked = { message, warmUpIterations, benchmarkIterations ->
        onBenchmarkClicked(selectedModel, message, warmUpIterations, benchmarkIterations)
      },
    )
  }

  // Sheet to show when a message is long-pressed.
  if (showMessageLongPressedSheet) {
    val message = longPressedMessage.value
    if (message != null && message is ChatMessageText) {
      val clipboard = LocalClipboard.current

      ModalBottomSheet(
        onDismissRequest = { showMessageLongPressedSheet = false },
        modifier = Modifier.wrapContentHeight(),
      ) {
        Column {
          // Copy text.
          Box(
            modifier =
              Modifier.fillMaxWidth().clickable {
                // Copy text.
                scope.launch {
                  val clipData = ClipData.newPlainText("message content", message.content)
                  val clipEntry = ClipEntry(clipData = clipData)
                  clipboard.setClipEntry(clipEntry = clipEntry)
                }

                // Hide sheet.
                showMessageLongPressedSheet = false

                // Show a snack bar.
                scope.launch { snackbarHostState.showSnackbar("Text copied to clipboard") }
              }
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
            ) {
              Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "",
                modifier = Modifier.size(18.dp),
              )
              Text("Copy text")
            }
          }
        }
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun ChatPanelPreview() {
//   GalleryTheme {
//     val context = LocalContext.current
//     val task = TASK_TEST1
//     ChatPanel(
//       modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
//       task = task,
//       selectedModel = TASK_TEST1.models[1],
//       viewModel = PreviewChatModel(context = context),
//       navigateUp = {},
//       onSendMessage = { _, _ -> },
//       onRunAgainClicked = { _, _ -> },
//       onBenchmarkClicked = { _, _, _, _ -> },
//     )
//   }
// }
