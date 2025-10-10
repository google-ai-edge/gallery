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

import android.content.ClipData
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.AudioAnimation
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Composable function for the main chat panel, displaying messages and handling user input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  selectedModel: Model,
  viewModel: ChatViewModel,
  innerPadding: PaddingValues,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, warmUpIterations: Int, benchmarkIterations: Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStreamEnd: (Int) -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onImageSelected: (bitmaps: List<Bitmap>, selectedBitmapIndex: Int) -> Unit = { _, _ -> },
  showStopButtonInInputWhenInProgress: Boolean = false,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val messages = uiState.messagesByModel[selectedModel.name] ?: listOf()
  val streamingMessage = uiState.streamingMessagesByModel[selectedModel.name]
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val imageCountToLastConfigChange =
    remember(messages) {
      var imageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageImage) {
          imageCount += message.bitmaps.size
        }
      }
      imageCount
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

  var showAudioRecorder by remember { mutableStateOf(false) }
  var curAmplitude by remember { mutableIntStateOf(0) }

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

  // Scroll to bottom when IME is toggled.
  LaunchedEffect(WindowInsets.ime.getBottom(density)) {
    scrollToBottom(listState = listState, animate = true)
  }

  // Scroll the content to the bottom when any of these changes.
  LaunchedEffect(
    messages.size,
    lastMessage.value,
    lastMessageContent.value,
    lastMessage.value?.latencyMs,
  ) {
    if (messages.isNotEmpty()) {
      val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.last()
      // Determines if an automatic scroll is necessary. It is true if:
      // 1. The last item is not yet fully visible
      // OR
      // 2. The scroll position is close to the bottom (within 90 pixels of the end offset. 90 is
      //    slightly taller than the "show stats" chip).
      val canScroll =
        lastVisibleItem.index < messages.size - 1 ||
          lastVisibleItem.offset + lastVisibleItem.size - listState.layoutInfo.viewportEndOffset <
            90
      // Only scroll if showingStatsByModel is not changed. In other words, when showingStatsByModel
      // changes we want the display to not scroll.
      if (uiState.showingStatsByModel === lastShowingStatsByModel.value && canScroll) {
        scrollToBottom(listState = listState, animate = true)
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

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    // Audio record animation.
    AnimatedVisibility(
      showAudioRecorder,
      enter =
        slideInVertically(
          animationSpec =
            spring(
              stiffness = Spring.StiffnessLow,
              visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        ) {
          it
        } + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)),
      exit = fadeOut(),
      modifier = Modifier.graphicsLayer { alpha = 0.8f },
    ) {
      AudioAnimation(bgColor = MaterialTheme.colorScheme.surface, amplitude = curAmplitude)
    }

    Column(
      modifier = modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()
    ) {
      Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
        val cdChatPanel = stringResource(R.string.cd_chat_panel)
        LazyColumn(
          modifier =
            Modifier.fillMaxSize().nestedScroll(nestedScrollConnection).semantics {
              contentDescription = cdChatPanel
            },
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
                  var messageBubbleModifier: Modifier = Modifier
                  // Use a rounded rectangle clip for multi-image image message.
                  if (message is ChatMessageImage && message.bitmaps.size > 1) {
                    messageBubbleModifier = messageBubbleModifier.clip(RoundedCornerShape(6.dp))
                  }
                  // For other messages, use a bubble shape to clip.
                  else {
                    messageBubbleModifier =
                      messageBubbleModifier.clip(
                        MessageBubbleShape(
                          radius = bubbleBorderRadius,
                          hardCornerAtLeftOrRight = hardCornerAtLeftOrRight,
                        )
                      )
                  }
                  messageBubbleModifier = messageBubbleModifier.background(backgroundColor)
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
                      is ChatMessageText ->
                        MessageBodyText(message = message, inProgress = uiState.inProgress)

                      // Image
                      is ChatMessageImage -> {
                        MessageBodyImage(message = message, onImageClicked = onImageSelected)
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
                        task.id.startsWith("llm_") &&
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
                            if (
                              viewModel.isShowingStats(model = selectedModel, message = message)
                            ) {
                              val llmBenchmarkResult = message.llmBenchmarkResult
                              val isLastMessage =
                                viewModel.getMessageIndex(
                                  model = selectedModel,
                                  message = message,
                                ) == messages.lastIndex
                              if (llmBenchmarkResult != null) {
                                viewModel.insertMessageAfter(
                                  model = selectedModel,
                                  anchorMessage = message,
                                  messageToAdd = llmBenchmarkResult,
                                )
                                // Scroll to bottom if showing the stats for the last message.
                                if (isLastMessage) {
                                  scope.launch {
                                    delay(100L)
                                    scrollToBottom(listState = listState, animate = true)
                                  }
                                }
                              }
                            }
                            // Remove the stats message.
                            else {
                              // `message` here is the one before the stats message to be removed.
                              val curMessageIndex =
                                viewModel.getMessageIndex(model = selectedModel, message = message)
                              val isLastMessage = curMessageIndex == messages.lastIndex - 1
                              viewModel.removeMessageAt(
                                model = selectedModel,
                                index = curMessageIndex + 1,
                              )
                              // Scroll to bottom if hiding the stats for the last message.
                              if (isLastMessage) {
                                scope.launch {
                                  delay(100L)
                                  scrollToBottom(listState = listState, animate = true)
                                }
                              }
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
        if (task.id == BuiltInTaskId.LLM_ASK_IMAGE && messages.isEmpty()) {
          Column(
            modifier =
              Modifier.padding(horizontal = 16.dp).fillMaxSize().semantics(
                mergeDescendants = true
              ) {
                liveRegion = LiveRegionMode.Polite
              },
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
        else if (task.id == BuiltInTaskId.LLM_ASK_AUDIO && messages.isEmpty()) {
          Column(
            modifier =
              Modifier.padding(horizontal = 16.dp).fillMaxSize().semantics(
                mergeDescendants = true
              ) {
                liveRegion = LiveRegionMode.Polite
              },
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

      MessageInputText(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        curMessage = curMessage,
        inProgress = uiState.inProgress,
        isResettingSession = uiState.isResettingSession,
        modelPreparing = uiState.preparing,
        imageCount = imageCountToLastConfigChange,
        audioClipMessageCount = audioClipMesssageCountToLastconfigChange,
        modelInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING,
        textFieldPlaceHolderRes = task.textInputPlaceHolderRes,
        onValueChanged = { curMessage = it },
        onSendMessage = {
          onSendMessage(selectedModel, it)
          curMessage = ""
          // Hide software keyboard.
          focusManager.clearFocus()
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
        onSetAudioRecorderVisible = { start ->
          showAudioRecorder = start
          if (!showAudioRecorder) {
            curAmplitude = 0
          }
        },
        onAmplitudeChanged = { curAmplitude = it },
        showPromptTemplatesInMenu = false,
        showImagePickerInMenu =
          selectedModel.llmSupportImage && task.id === BuiltInTaskId.LLM_ASK_IMAGE,
        showAudioItemsInMenu =
          selectedModel.llmSupportAudio && task.id === BuiltInTaskId.LLM_ASK_AUDIO,
        showStopButtonWhenInProgress = showStopButtonInInputWhenInProgress,
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
        Column(modifier = Modifier.padding(12.dp)) {
          // Header: title + copy whole button
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Message",
              style = MaterialTheme.typography.titleMedium
            )

            Text(
              text = "Copy whole",
              modifier = Modifier
                .clickable {
                  scope.launch {
                    val clipData = ClipData.newPlainText("message content", message.content)
                    val clipEntry = ClipEntry(clipData = clipData)
                    clipboard.setClipEntry(clipEntry = clipEntry)
                  }
                  showMessageLongPressedSheet = false
                  scope.launch { snackbarHostState.showSnackbar("Text copied to clipboard") }
                }
                .padding(8.dp),
              style = MaterialTheme.typography.labelLarge
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Selectable message content (scrollable)
          SelectionContainer {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
            ) {
              Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium
              )
              Spacer(modifier = Modifier.height(2.dp))
              Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.cd_copy_to_clipboard_icon),
                modifier = Modifier.size(18.dp),
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          // Helper text: how to copy partial selection using OS actions
          Text(
            text =
              "Long-press and drag to select part of the text, then use the system copy action to copy the selection. Use “Copy whole” to copy the entire message.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
          )
        }
      }
    }
  }

}

private suspend fun scrollToBottom(listState: LazyListState, animate: Boolean = false) {
  val itemCount = listState.layoutInfo.totalItemsCount
  if (itemCount > 0) {
    if (animate) {
      listState.animateScrollToItem(itemCount - 1, scrollOffset = 10000)
    } else {
      listState.scrollToItem(itemCount - 1, scrollOffset = 10000)
    }
  }
}
