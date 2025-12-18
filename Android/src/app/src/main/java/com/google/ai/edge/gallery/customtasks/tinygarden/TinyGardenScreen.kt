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
package com.google.ai.edge.gallery.customtasks.tinygarden

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.webkit.WebViewAssetLoader
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.common.io.BaseEncoding
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val TAG = "AGTinyGarden"
private const val ASSETS_BASE_URL = "https://appassets.androidplatform.net"

/** The main screen for the Tiny Garden game. */
@Composable
fun TinyGardenScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<TinyGardenTools>,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>,
  viewModel: TinyGardenViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  // Permission request when recording audio clips.
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  LaunchedEffect(Unit) {
    // Check permission
    when (PackageManager.PERMISSION_GRANTED) {
      // Already got permission. Call the lambda.
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }

      // Otherwise, ask for permission
      else -> {
        recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MainUi(
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          tools = tools,
          bottomPadding = bottomPadding,
          commandFlow = commandFlow,
          viewModel = viewModel,
          setAppBarControlsDisabled = setAppBarControlsDisabled,
          setTopBarVisible = setTopBarVisible,
        )

        // Resetting engine spinner.
        Column() {
          AnimatedVisibility(
            uiState.resettingEngine,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
          ) {
            Box(
              modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                CircularProgressIndicator(
                  trackColor = MaterialTheme.colorScheme.surfaceVariant,
                  strokeWidth = 3.dp,
                  modifier = Modifier.size(24.dp),
                )
                Text(
                  stringResource(R.string.resetting_engine),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  stringResource(R.string.reinitializing_description),
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(top = 8.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<TinyGardenTools>,
  bottomPadding: Dp,
  viewModel: TinyGardenViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember { model.configValues }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()
  var clearTextTrigger by remember { mutableLongStateOf(0L) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  var showConversationHistoryPanel by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val snackbarHostState = remember { SnackbarHostState() }
  var prevSeed by remember { mutableStateOf("") }
  var prevPlots by remember { mutableStateOf("") }
  var prevAction by remember { mutableStateOf("") }
  val resources = LocalResources.current
  val context = LocalContext.current

  val taskColor = getTaskBgGradientColors(task = task)[1]
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  // Close conversation history panel when pressing back button.
  BackHandler(enabled = showConversationHistoryPanel) { showConversationHistoryPanel = false }

  LaunchedEffect(showConversationHistoryPanel) { setTopBarVisible(!showConversationHistoryPanel) }

  LaunchedEffect(Unit) {
    // Run commands/functions generated by TinyGardenTools.
    commandFlow.collect { command ->
      // Format command and add to "chat" history.
      val functionName =
        when (command.item) {
          TinyGardenItem.SUNFLOWER.ordinal + 1,
          TinyGardenItem.DAISY.ordinal + 1,
          TinyGardenItem.ROSE.ordinal + 1,
          TinyGardenItem.SPECIAL.ordinal + 1 -> "plantSeed"

          TinyGardenItem.WATERING_CAN.ordinal + 1 -> "waterPlots"
          TinyGardenItem.SCYTHE.ordinal + 1 -> "harvestPlots"
          else -> ""
        }
      val strPlots = "[${command.plots.joinToString(",")}]"
      val functionParameter =
        when (command.item) {
          TinyGardenItem.SUNFLOWER.ordinal + 1 -> "- seed: \"sunflower\"\n- plots: $strPlots"
          TinyGardenItem.DAISY.ordinal + 1 -> "- seed: \"daisy\"\n- plots: $strPlots"
          TinyGardenItem.ROSE.ordinal + 1 -> "- seed: \"rose\"\n- plots: $strPlots"
          TinyGardenItem.SPECIAL.ordinal + 1 -> "- seed: \"special\"\n- plots: $strPlots"
          TinyGardenItem.WATERING_CAN.ordinal + 1 -> "- plots: $strPlots"
          TinyGardenItem.SCYTHE.ordinal + 1 -> "- plots: $strPlots"
          else -> ""
        }
      val numParameters =
        when (command.item) {
          TinyGardenItem.WATERING_CAN.ordinal + 1,
          TinyGardenItem.SCYTHE.ordinal + 1 -> 1
          else -> 2
        }
      val functionNameLabel = resources.getString(R.string.function_name)
      val parametersLabel = resources.getQuantityString(R.plurals.parameter, numParameters)
      viewModel.addMessage(
        message =
          ChatMessageText(
            content =
              "**$functionNameLabel**:\n- $functionName\n\n**$parametersLabel**:\n$functionParameter",
            side = ChatSide.AGENT,
          )
      )

      // Convert command into json that can be consumed by the game.
      val commandJson =
        """[{"item": ${command.item}, "plot":[${command.plots.joinToString(",")}]}]"""
      Log.d(TAG, "commandJson: $commandJson")

      // Call into the game webview.
      val jsScript = "tinyGarden.runCommands('$commandJson')"
      webViewRef
        ?.runCatching { evaluateJavascript(jsScript, null) }
        ?.onFailure { e -> Log.e(TAG, "$e") }

      // Save seed, plots, and action so that we can add them to system prompt when resetting
      // conversation.
      prevSeed =
        when (command.item) {
          TinyGardenItem.SUNFLOWER.ordinal + 1 -> TinyGardenItem.SUNFLOWER.label
          TinyGardenItem.DAISY.ordinal + 1 -> TinyGardenItem.DAISY.label
          TinyGardenItem.ROSE.ordinal + 1 -> TinyGardenItem.ROSE.label
          TinyGardenItem.SPECIAL.ordinal + 1 -> TinyGardenItem.SPECIAL.label
          else -> ""
        }
      prevPlots = command.plots.joinToString(",")
      prevAction =
        when (command.item) {
          TinyGardenItem.WATERING_CAN.ordinal + 1 -> TinyGardenItem.WATERING_CAN.label
          TinyGardenItem.SCYTHE.ordinal + 1 -> TinyGardenItem.SCYTHE.label
          else -> ""
        }
      Log.d(TAG, "prevSeed: '$prevSeed', prevPlots: '$prevPlots', prevAction: '$prevAction'")
    }
  }

  val noFunctionCallWarningMessage = stringResource(R.string.warning_no_function_call)
  val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

  // A function to process the input from the user.
  fun processInstructionText(text: String) {
    clearTextTrigger = System.currentTimeMillis()

    if (text.trim().isNotEmpty()) {
      // A special input to unlock all :)
      if (text.trim().sha256() == "XtNztQDSDvVpMRPOK+q9tZs43x/VD1teVs3CvWp7zkc=") {
        webViewRef
          ?.runCatching { evaluateJavascript("tinyGarden.unlockAll()", null) }
          ?.onFailure { e -> Log.e(TAG, "$e") }
      } else {
        // Run inference to get response command in json.
        viewModel.getCommand(
          model = model,
          instructionText = text,
          onDone = { response ->
            // Add a warning message if no function was recognized.
            if (uiState.messages.last().side != ChatSide.AGENT) {
              viewModel.addMessage(
                message = ChatMessageWarning(content = noFunctionCallWarningMessage)
              )
              // Show a snack bar for unrecognized command.
              scope.launch {
                snackbarHostState.showSnackbar(
                  noFunctionCallSnackbarMessage,
                  withDismissAction = true,
                )
              }
            }

            // Add the final response from the model.
            // viewModel.addMessage(
            //   message = ChatMessageText(content = response, side = ChatSide.AGENT)
            // )

            // Reset conversation every {numTurns} turns.
            val numTurnsToReset =
              convertValueToTargetType(
                value = model.configValues.getValue(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label),
                valueType = ValueType.INT,
              )
                as Int
            Log.d(TAG, "Target turn to reset: $numTurnsToReset")
            if (uiState.numTurns == numTurnsToReset) {
              Log.d(TAG, "!! This is the turn to reset conversation")
              viewModel.resetConversation(
                model = model,
                tools = tools,
                prevSeed = prevSeed,
                prevPlots = prevPlots,
                prevAction = prevAction,
              )
            }
          },
          onError = { error ->
            // Show error dialog for users to reset the engine.
            errorDialogContent = error
            showErrorDialog = true
          },
        )
      }
    }
  }

  // Reset states on config changes.
  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      var same = true
      var nonNumTurnsConfigChanged = false
      for (config in model.configs) {
        val key = config.key.label
        val oldValue =
          convertValueToTargetType(
            value = model.prevConfigValues.getValue(key),
            valueType = config.valueType,
          )
        val newValue =
          convertValueToTargetType(
            value = model.configValues.getValue(key),
            valueType = config.valueType,
          )
        if (oldValue != newValue) {
          same = false
          if (config.key != ConfigKeys.RESET_CONVERSATION_TURN_COUNT) {
            nonNumTurnsConfigChanged = true
          }
        }
      }

      if (!same) {
        Log.d(TAG, "model config values changed.")
        if (nonNumTurnsConfigChanged) {
          Log.d(TAG, "need to reset engine")
          viewModel.resetEngine(
            context = context,
            model = model,
            tools = tools,
            onError = {
              errorDialogContent = it
              showErrorDialog = true
            },
          )
        } else {
          Log.d(TAG, "need to reset conversation")
          viewModel.resetConversation(
            model = model,
            tools = tools,
            prevSeed = "",
            prevPlots = "",
            prevAction = "",
          )
        }
      }
    }
  }

  // Show a loading indicator before the model is initialized.
  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
  }
  // Main UI.
  else {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.padding(
            bottom =
              if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 12.dp
          )
      ) {
        // A webview to load the game which is written in javascript.
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
          AndroidView(
            modifier = Modifier.fillMaxHeight(),
            factory = { context ->
              // WebViewAssetLoader is used to load local assets (like HTML, CSS, JS)
              // from the application's assets directory into the WebView.
              val assetLoader =
                WebViewAssetLoader.Builder()
                  .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                  .build()

              WebView(context).apply {
                webViewRef = this

                // Needed to make "height:100%" work in body/html style.
                layoutParams =
                  ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                  )

                settings.apply {
                  javaScriptEnabled = true
                  domStorageEnabled = true
                  allowFileAccess = true
                  // Needed to play the audio in game without user interaction.
                  mediaPlaybackRequiresUserGesture = false
                }

                webViewClient =
                  object : WebViewClient() {
                    override fun shouldInterceptRequest(
                      view: WebView?,
                      request: WebResourceRequest,
                    ): WebResourceResponse? {
                      // Check if the URL should be handled by the asset loader
                      return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                      super.onPageFinished(view, url)
                      Log.d(TAG, "webview finished loading")

                      // Show help on first launch.
                      if (!viewModel.dataStoreRepository.getHasRunTinyGarden()) {
                        Log.d(TAG, "First time running Tiny Garden. Showing help screen...")
                        viewModel.dataStoreRepository.setHasRunTinyGarden(true)
                        scope.launch {
                          delay(1000)
                          webViewRef
                            ?.runCatching { evaluateJavascript("tinyGarden.showHelp()", null) }
                            ?.onFailure { e -> Log.e(TAG, "$e") }
                        }
                      }
                    }

                    override fun shouldOverrideUrlLoading(
                      view: WebView?,
                      request: WebResourceRequest?,
                    ): Boolean {
                      if (request == null) {
                        return false
                      }

                      val url = request.url.toString()

                      // Check if the URL should be loaded internally (e.g., local assets)
                      if (url.startsWith(ASSETS_BASE_URL)) {
                        // Return false to let the WebView load the URL internally
                        return false
                      }

                      // If it's an external URL, launch an Android Intent to open it
                      // in the system's default browser.
                      try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        view?.context?.startActivity(intent)
                      } catch (e: Exception) {
                        Log.e(TAG, "Could not open external URL: $url", e)
                      }

                      // Return true to signal that we have handled the URL loading and
                      // the WebView should NOT load it internally.
                      return true
                    }
                  }

                webChromeClient =
                  object : WebChromeClient() {
                    // Log console messages.
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                      Log.d(
                        TAG,
                        "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
                      )
                      return super.onConsoleMessage(consoleMessage)
                    }
                  }

                // Load page.
                //
                // http://appassets.androidplatform.net' is the recommended, reserved domain.
                var url = "$ASSETS_BASE_URL/assets/tinygarden/index.html"
                if (!viewModel.dataStoreRepository.getHasRunTinyGarden()) {
                  Log.d(TAG, "First time running Tiny Garden. Showing tutorial screen...")
                  viewModel.dataStoreRepository.setHasRunTinyGarden(true)
                  url = "$url?tutorial=1"
                }
                loadUrl(url)
              }
            },
          )

          SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = 12.dp))
        }

        // Text and voice input.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          TextAndVoiceInput(
            task = task,
            processing = uiState.processing,
            holdToDictateViewModel = holdToDictateViewModel,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
            onDone = { text -> processInstructionText(text = text) },
            onAmplitudeChanged = { curAmplitude = it },
            clearTextTrigger = clearTextTrigger,
            defaultTextInputMode = true,
          )
          Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (uiState.processing) {
              CircularProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
              )
            } else {
              IconButton(
                onClick = { showConversationHistoryPanel = true },
                modifier = Modifier.padding(end = 8.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.History,
                  contentDescription = stringResource(R.string.cd_more_options),
                )
              }
            }
          }
        }
      }

      // Show an overlay during speech recognition.
      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        exit =
          fadeOut(
            animationSpec =
              tween(durationMillis = 100, easing = FastOutSlowInEasing, delayMillis = 300)
          ),
      ) {
        VoiceRecognizerOverlay(
          task = task,
          viewModel = holdToDictateViewModel,
          curAmplitude = curAmplitude,
          bottomPadding = bottomPadding,
        )
      }

      // Conversation history panel.
      AnimatedVisibility(
        showConversationHistoryPanel,
        enter = slideInVertically { fullHeight -> fullHeight },
        exit = slideOutVertically { fullHeight -> fullHeight },
      ) {
        ConversationHistoryPanel(
          task = task,
          bottomPadding = bottomPadding,
          viewModel = viewModel,
          onDismiss = { showConversationHistoryPanel = false },
        )
      }
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.error)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium)
          Text(
            stringResource(R.string.reset_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.warningTextColor,
          )
        }
      },
      onDismissRequest = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      dismissButton = {
        TextButton(
          onClick = {
            showErrorDialog = false
            errorDialogContent = ""
          }
        ) {
          Text(stringResource(R.string.cancel))
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showErrorDialog = false
            errorDialogContent = ""

            viewModel.resetEngine(
              context = context,
              model = model,
              tools = tools,
              onError = {
                errorDialogContent = it
                showErrorDialog = true
              },
            )
          },
          colors = ButtonDefaults.buttonColors(containerColor = taskColor),
        ) {
          Text(stringResource(R.string.reset), color = Color.White)
        }
      },
    )
  }
}

/** Returns the SHA-256 hash of the given string as a base64 encoded string. */
private fun String.sha256(): String {
  val inputBytes = this.toByteArray()
  return try {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val digest = sha256.digest(inputBytes)
    BaseEncoding.base64().encode(digest)
  } catch (e: Exception) {
    e.printStackTrace()
    ""
  }
}
