/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.common.chat.LogMessageLevel
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.tool
import java.lang.Exception
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGAgentChatScreen"
private val chatViewJavascriptInterface = ChatWebViewJavascriptInterface()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  viewModel: LlmChatViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
  var showSkillManagerBottomSheet by remember { mutableStateOf(false) }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  var curSystemPrompt by remember { mutableStateOf(task.defaultSystemPrompt) }
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_AGENT_CHAT,
    navigateUp = navigateUp,
    onFirstToken = { model ->
      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onGenerateResponseDone = { model ->
      // Show any image produced by tools.
      agentTools.resultImageToShow?.let { resultImage ->
        resultImage.base64?.let { base64 ->
          decodeBase64ToBitmap(base64String = base64)?.let { bitmap ->
            viewModel.addMessage(
              model = model,
              message =
                ChatMessageImage(
                  bitmaps = listOf(bitmap),
                  imageBitMaps = listOf(bitmap.asImageBitmap()),
                  side = ChatSide.AGENT,
                  maxSize = (screenWidthDp.value * 0.8).toInt(),
                  latencyMs = -1.0f,
                  hideSenderLabel = true,
                ),
            )
          }
        }
        // Clean up.
        agentTools.resultImageToShow = null
      }

      // Show any webview produced by tools.
      agentTools.resultWebviewToShow?.let { webview ->
        val url = webview.url ?: ""
        val iframe = webview.iframe == true
        val aspectRatio = webview.aspectRatio ?: 1.333f
        viewModel.addMessage(
          model = model,
          message =
            ChatMessageWebView(
              url = url,
              iframe = iframe,
              aspectRatio = aspectRatio,
              hideSenderLabel = true,
            ),
        )
        // Clean up.
        agentTools.resultWebviewToShow = null
      }

      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onResetSessionClickedOverride = { task, model ->
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
      )
    },
    onSkillClicked = { showSkillManagerBottomSheet = true },
    showImagePicker = true,
    showAudioPicker = true,
    getActiveSkills = {
      skillManagerViewModel.getSelectedSkills().map { skill ->
        if (skill.builtIn) skill.name else "custom_skill"
      }
    },
    composableBelowMessageList = { model ->
      val actionChannel = agentTools.actionChannel
      val doneIcon = ImageVector.vectorResource(R.drawable.skill)
      // Use rememberUpdatedState to ensure that LaunchedEffect captures the
      // latest active model when the model is switched during an ongoing skill execution.
      val currentModel by androidx.compose.runtime.rememberUpdatedState(model)
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          Log.d(TAG, "Handling action: $action")
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
            }
            is CallJsAgentAction -> {
              val skillName =
                if (action.url.contains("/skills/")) {
                  action.url.substringAfter("/skills/").substringBefore("/")
                } else if (action.url.startsWith(LOCAL_URL_BASE + "/")) {
                  action.url.substringAfter(LOCAL_URL_BASE + "/").substringBefore("/")
                } else {
                  action.url
                }
              try {
                // Set up a safety net timeout so we NEVER hang the chat or tool execution
                launch {
                  delay(60000L) // 60 seconds max
                  if (!action.result.isCompleted) {
                    Log.e(TAG, "JS Execution timed out, completing with error.")
                    Log.d(
                      TAG,
                      "Analytics: skill_execution, skill_name=$skillName, success=false, error_type=timeout",
                    )
                    firebaseAnalytics?.logEvent(
                      GalleryEvent.SKILL_EXECUTION.id,
                      Bundle().apply {
                        putString("skill_name", skillName)
                        putBoolean("success", false)
                        putString("error_type", "timeout")
                      },
                    )
                    action.result.complete(
                      "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                    )
                  }
                }

                // Load url.
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  webViewRef?.loadUrl(action.url)
                }

                // Execute JS.
                Log.d(TAG, "Start to run js")
                chatViewJavascriptInterface.onResultListener = { result ->
                  Log.d(TAG, "Got result:\n$result")
                  action.result.complete(result)
                  val isSuccess = !result.contains("\"error\":")
                  val errorType = if (isSuccess) "" else "js_error"
                  Log.d(
                    TAG,
                    "Analytics: skill_execution, skill_name=$skillName, success=$isSuccess, error_type=$errorType",
                  )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.SKILL_EXECUTION.id,
                    Bundle().apply {
                      putString("skill_name", skillName)
                      putBoolean("success", isSuccess)
                      putString("error_type", errorType)
                    },
                  )
                }

                val safeData = JSONObject.quote(action.data)
                val safeSecret = JSONObject.quote(action.secret)
                val script =
                  """
                  (async function() {
                      var startTs = Date.now();
                      while(true) {
                        if (typeof ai_edge_gallery_get_result === 'function') {
                          break;
                        }
                        await new Promise(resolve=>{
                          setTimeout(resolve, 100)
                        });
                        if (Date.now() - startTs > 10000) {
                          break;
                        }
                      }
                      var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                      AiEdgeGallery.onResultReady(result);
                  })()
                  """
                    .trimIndent()
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                Log.d(
                  TAG,
                  "Analytics: skill_execution, skill_name=$skillName, success=false, error_type=exception",
                )
                firebaseAnalytics?.logEvent(
                  GalleryEvent.SKILL_EXECUTION.id,
                  Bundle().apply {
                    putString("skill_name", skillName)
                    putBoolean("success", false)
                    putString("error_type", "exception")
                  },
                )
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = "" // Reset input
              showAskInfoDialog = true
            }
          }
        }
      }

      GalleryWebView(
        modifier = Modifier.size(300.dp),
        onWebViewCreated = { webView ->
          webViewRef = webView
          webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
        },
        customWebViewClient = chatWebViewClient,
        onConsoleMessage = { consoleMessage ->
          consoleMessage?.let { curConsoleMessage ->
            // Create a LogMessage from the ConsoleMessage and add it to the progress panel.
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
                    ConsoleMessage.MessageLevel.WARNING -> LogMessageLevel.Warning
                    else -> LogMessageLevel.Info
                  },
                source = curConsoleMessage.sourceId(),
                lineNumber = curConsoleMessage.lineNumber(),
                message = curConsoleMessage.message(),
              )
            viewModel.addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = logMessage,
            )
            Log.d(
              TAG,
              "${curConsoleMessage.message()} " +
                "-- From line ${curConsoleMessage.lineNumber()} of ${curConsoleMessage.sourceId()}",
            )
          }
        },
      )
    },
    allowEditingSystemPrompt = true,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = { newPrompt ->
      curSystemPrompt = newPrompt
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
        onDone = { model ->
          viewModel.addMessage(
            model = model,
            message = ChatMessageInfo(content = systemPromptUpdatedMessage),
          )
        },
      )
    },
    emptyStateComposable = { model ->
      val uiState by viewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
      Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
          !WindowInsets.isImeVisible,
          enter = fadeIn(animationSpec = tween(200)),
          exit = fadeOut(animationSpec = tween(200)),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              modifier =
                Modifier.align(Alignment.Center)
                  .padding(horizontal = 48.dp)
                  .padding(bottom = 48.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                stringResource(R.string.introducing),
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                stringResource(R.string.agent_skills),
                style =
                  MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Medium,
                    brush =
                      Brush.linearGradient(colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))),
                  ),
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
              )
              Text(
                buildAnnotatedString {
                  append("Use specialized, high-order reasoning by loading different skills or ")
                  append(
                    buildTrackableUrlAnnotatedString(
                      url = "https://github.com/google-ai-edge/gallery/tree/main/skills",
                      linkText = "creating\u00A0your\u00A0own",
                    )
                  )
                  append(".\n\nTry tapping a sample prompt below to see Agent Skills in action!")
                },
                style =
                  MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }

        Row(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (promptChip in TRYOUT_CHIPS) {
            FilledTonalButton(
              enabled =
                modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED &&
                  !uiState.isResettingSession,
              onClick = {
                // Skill is selected, trigger sending the message.
                if (skillManagerViewModel.isSkillSelected(promptChip.skillName)) {
                  sendMessageTrigger =
                    SendMessageTrigger(
                      model = model,
                      messages =
                        listOf(ChatMessageText(content = promptChip.prompt, side = ChatSide.USER)),
                    )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.BUTTON_CLICKED.id,
                    Bundle().apply {
                      putString("event_type", "agent_skills_prompt_chip")
                      putString("button_id", promptChip.label)
                    },
                  )
                }
                // Skill is not selected, show alert dialog.
                else {
                  disabledSkillName = promptChip.skillName
                  showAlertForDisabledSkill = true
                }
              },
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(promptChip.icon, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(promptChip.label)
            }
          }
        }
      }
    },
    sendMessageTrigger = sendMessageTrigger,
  )

  if (showAskInfoDialog && currentAskInfoAction != null) {
    val action = currentAskInfoAction!!
    SecretEditorDialog(
      title = action.dialogTitle,
      fieldLabel = action.fieldLabel,
      value = askInfoInputValue,
      onValueChange = { askInfoInputValue = it },
      onDone = {
        action.result.complete(askInfoInputValue)
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
      onDismiss = {
        action.result.complete("")
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
    )
  }

  if (showSkillManagerBottomSheet) {
    SkillManagerBottomSheet(
      agentTools = agentTools,
      skillManagerViewModel = skillManagerViewModel,
      onDismiss = { selectedSkillsChanged ->
        // Hide sheet.
        showSkillManagerBottomSheet = false

        // Reset session when selected skills changed.
        if (selectedSkillsChanged) {
          Log.d(TAG, "Selected skill changed. Resetting conversation.")
          resetSessionWithCurrentSkills(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
          )
        }
      },
    )
  }

  if (showAlertForDisabledSkill) {
    AlertDialog(
      onDismissRequest = { showAlertForDisabledSkill = false },
      title = { Text("The \"$disabledSkillName\" skill is currently disabled") },
      text = { Text(stringResource(R.string.enable_skill_dialog_content)) },
      confirmButton = {
        Button(onClick = { showAlertForDisabledSkill = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

private fun updateProgressPanel(viewModel: LlmChatViewModel, model: Model, agentTools: AgentTools) {
  // Update status.
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
      lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
  ) {
    if (lastProgressPanelMessage.title.startsWith("Loading")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Loading", "Loaded"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Calling")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Calling", "Called"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Executing")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Executing", "Executed"),
          inProgress = false,
        )
      )
    }
  }
}

private fun resetSessionWithCurrentSkills(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  onDone: (Model) -> Unit = {},
) {
  val model = modelManagerViewModel.uiState.value.selectedModel
  val newSelectedSkills = skillManagerViewModel.getSelectedSkills()
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction =
      if (newSelectedSkills.isEmpty()) null
      else skillManagerViewModel.getSystemPrompt(curSystemPrompt),
    tools = listOf(tool(agentTools)),
    supportImage = true,
    supportAudio = true,
    onDone = { onDone(model) },
    enableConversationConstrainedDecoding = true,
  )
}

class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

class ChatWebViewClient(val context: Context) : BaseGalleryWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}
