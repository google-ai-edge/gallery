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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyLoading
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyWarning
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMAScreen"

data class PromptTemplate(@StringRes val labelResId: Int, val prompt: String)

private val PROMPT_TEMPLATES =
  listOf(
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_on,
      prompt = "Turn on flashlight",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_off,
      prompt = "Turn off flashlight",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_contact,
      prompt =
        "Create contact John Doe with email address jd@example.com and phone number 123 456 7890.",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_send_email,
      prompt =
        "Send an email to jd@example.com with subject \"Meeting\" and body \"Hi John, let's meet at 3pm tomorrow.\"",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      prompt = "Create a calendar event at 2:30pm tomorrow for \"team meeting\"",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      prompt = "Show Googleplex on map",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      prompt = "Open WIFI settings",
    ),
  )

private data class SampleActionItem(@StringRes val labelResId: Int, val icon: ImageVector)

private val SAMPLE_ACTION_ITEMS =
  listOf(
    SampleActionItem(
      labelResId = R.string.prompt_template_label_flash_on_off,
      icon = Icons.Outlined.FlashlightOn,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_contact,
      icon = Icons.Outlined.PersonAdd,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_send_email,
      icon = Icons.Outlined.Email,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      icon = Icons.Outlined.CalendarMonth,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      icon = Icons.Outlined.Map,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      icon = Icons.Outlined.Wifi,
    ),
  )

private data class Tab(@StringRes val labelResId: Int, val icon: ImageVector)

private val TABS =
  listOf(
    Tab(
      labelResId = R.string.mobile_actions_tab_model_response,
      icon = Icons.AutoMirrored.Rounded.Article,
    ),
    Tab(labelResId = R.string.mobile_actions_tab_function_called, icon = Icons.Rounded.Functions),
  )

/**
 * A Composable function that displays the MobileActions screen.
 *
 * This screen allows users to interact with an AI model using voice or text input to perform
 * various actions on their device.
 */
@Composable
fun MobileActionsScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  mobileActionsViewModel: MobileActionsViewModel = hiltViewModel(),
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  tools: List<MobileActionsTools>,
  onProcessingStarted: () -> Unit,
) {
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

  // Ask for audio recording permission.
  LaunchedEffect(Unit) {
    // Check permission.
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
      MainUi(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        tools = tools,
        bottomPadding = bottomPadding,
        viewModel = mobileActionsViewModel,
        curActions = curActions,
        setAppBarControlsDisabled = setAppBarControlsDisabled,
        onProcessingStarted = onProcessingStarted,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<MobileActionsTools>,
  bottomPadding: Dp,
  viewModel: MobileActionsViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  onProcessingStarted: () -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember { model.configValues }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  var curAmplitude by remember { mutableIntStateOf(0) }
  var clearInputTextTrigger by remember { mutableLongStateOf(0L) }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var doneGeneratingResponse by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val focusManager = LocalFocusManager.current
  val resources = LocalResources.current
  val taskColor = getTaskBgGradientColors(task = task)[1]

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  // Reset states on config changes.
  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      Log.d(TAG, "model config values changed.")
      modelManagerViewModel.setInitializationStatus(
        model = model,
        status = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED),
      )
      viewModel.reset()
    }
  }

  DisposableEffect(Unit) { onDispose { viewModel.cleanUp() } }

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
    val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

    val send: (String) -> Unit = { text ->
      scope.launch(Dispatchers.Main) {
        selectedTabIndex = 0
        clearInputTextTrigger = System.currentTimeMillis()
        focusManager.clearFocus()
      }

      onProcessingStarted()

      // Figure out the correct action from user prompt.
      doneGeneratingResponse = false
      viewModel.processUserPrompt(
        model = model,
        userPrompt = text,
        tools = tools,
        onProcessDone = {
          doneGeneratingResponse = true
          Log.d(TAG, "Actions count: ${curActions.size}")

          // Execute functions.
          if (curActions.isNotEmpty()) {
            val errors = mutableListOf<String>()
            for (action in curActions) {
              val curError = viewModel.performAction(action = action, context = context)
              if (curError.isEmpty()) {
                viewModel.addFunctionCallDetails(
                  details = genFormattedFunctionCall(action = action, resources = resources)
                )
              } else {
                errors.add(curError)
              }
            }
            if (errors.isNotEmpty()) {
              scope.launch {
                snackbarHostState.showSnackbar(
                  errors.joinToString(separator = "; "),
                  withDismissAction = true,
                  duration = SnackbarDuration.Long,
                )
              }
            }
          }
          // No function recognized.
          else {
            viewModel.setNoFunctionRecognized(value = true)

            // Show a snack bar for unrecognized command.
            scope.launch {
              snackbarHostState.showSnackbar(
                noFunctionCallSnackbarMessage,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
              )
            }
          }
        },
        onError = { error ->
          doneGeneratingResponse = true

          // Show error dialog for users to reset the engine.
          errorDialogContent = error
          showErrorDialog = true
        },
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(
              bottom =
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 8.dp
            )
            .imePadding()
      ) {
        // Message shown when no prompt has been processed yet.
        if (uiState.showWelcomeMessage) {
          Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                stringResource(R.string.mobile_actions_title),
                style = MaterialTheme.typography.headlineLarge,
                color = getTaskIconColor(task = task),
              )
              Text(
                stringResource(R.string.mobile_actions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = getTaskIconColor(task = task),
              )
              Column {
                Text(
                  stringResource(R.string.mobile_actions_supported_actions),
                  style = MaterialTheme.typography.labelLarge,
                  modifier =
                    Modifier.padding(top = 64.dp, bottom = 8.dp).graphicsLayer { alpha = 0.7f },
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (item in SAMPLE_ACTION_ITEMS) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      item.icon,
                      contentDescription = null,
                      modifier = Modifier.size(24.dp).padding(end = 8.dp),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      stringResource(item.labelResId),
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
        // Current user prompt and model response.
        else {
          // The current user prompt.
          Box(
            modifier =
              Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              uiState.userPrompt,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
          }

          // Loader when processing.
          if (uiState.processing) {
            Box(
              modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
              contentAlignment = Alignment.TopStart,
            ) {
              MessageBodyLoading()
            }
          }
          // Response.
          else {
            // Tab bar.
            Row(modifier = Modifier.fillMaxWidth()) {
              PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                indicator = {
                  TabRowDefaults.PrimaryIndicator(
                    modifier =
                      Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                    color = taskColor,
                    width = Dp.Unspecified,
                  )
                },
              ) {
                for ((index, tab) in TABS.withIndex()) {
                  val enabled = index == 0 || (index == 1 && !uiState.noFunctionRecognized)
                  Tab(
                    selected = selectedTabIndex == index,
                    enabled = enabled,
                    onClick = { selectedTabIndex = index },
                    modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.3f },
                    text = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        val titleColor =
                          if (selectedTabIndex == index) taskColor
                          else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(
                          tab.icon,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp).alpha(0.7f),
                          tint = titleColor,
                        )
                        BasicText(
                          text = stringResource(tab.labelResId),
                          maxLines = 1,
                          color = { titleColor },
                          style =
                            MaterialTheme.typography.bodyMedium.copy(
                              fontWeight = FontWeight.Medium
                            ),
                          autoSize =
                            TextAutoSize.StepBased(
                              minFontSize = 9.sp,
                              maxFontSize = 14.sp,
                              stepSize = 1.sp,
                            ),
                        )
                      }
                    },
                  )
                }
              }
            }

            // Content.
            Column(
              modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
              AnimatedContent(
                selectedTabIndex,
                transitionSpec = {
                  if (targetState > initialState) {
                    slideInHorizontally { 40 } + fadeIn() togetherWith
                      slideOutHorizontally { -40 } + fadeOut(animationSpec = tween(50))
                  } else {
                    slideInHorizontally { -40 } + fadeIn() togetherWith
                      slideOutHorizontally { 40 } + fadeOut(animationSpec = tween(50))
                  }
                },
                modifier = Modifier.weight(1f),
              ) {
                // Model response.
                if (selectedTabIndex == 0) {
                  Column(modifier = Modifier.fillMaxWidth()) {
                    val cdResponse = stringResource(R.string.cd_model_response_text)
                    MarkdownText(
                      text = uiState.modelResponse,
                      modifier =
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = cdResponse
                            // Only announce when message is complete.
                            if (doneGeneratingResponse) {
                              liveRegion = LiveRegionMode.Polite
                            }
                          }
                          .padding(16.dp),
                    )

                    if (uiState.noFunctionRecognized) {
                      MessageBodyWarning(
                        ChatMessageWarning(
                          content = stringResource(R.string.warning_no_function_call)
                        )
                      )
                    }
                  }
                }
                // Function called.
                else if (selectedTabIndex == 1) {
                  Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    for ((index, details) in uiState.functionCallDetails.withIndex()) {
                      MarkdownText(text = details, modifier = Modifier.padding(16.dp))

                      if (index != uiState.functionCallDetails.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                      }
                    }
                  }
                }
              }
            }
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // A list of prompt templates.
          Row(
            modifier =
              Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).graphicsLayer {
                alpha = if (uiState.processing) 0.5f else 1f
              },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Spacer(modifier = Modifier.width(12.dp))
            for (item in PROMPT_TEMPLATES) {
              Text(
                stringResource(item.labelResId),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                  Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !uiState.processing) { send(item.prompt) }
                    .background(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                      width = 1.dp,
                      color = MaterialTheme.colorScheme.outlineVariant,
                      shape = RoundedCornerShape(12.dp),
                    )
                    .padding(all = 12.dp),
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
          }

          // Text and voice Input.
          Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TextAndVoiceInput(
              task = task,
              processing = uiState.processing,
              holdToDictateViewModel = holdToDictateViewModel,
              onDone = { text -> send(text) },
              onAmplitudeChanged = { curAmplitude = it },
              clearTextTrigger = clearInputTextTrigger,
              modifier = Modifier.fillMaxWidth(),
            )
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

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = bottomPadding + 100.dp).align(Alignment.BottomCenter),
      )
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.error)) },
      text = { Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium) },
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
              modelManagerViewModel = modelManagerViewModel,
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

private fun genFormattedFunctionCall(action: Action, resources: Resources): String {
  val strFunctionName = action.functionCallDetails.functionName
  val functionNameLabel = resources.getString(R.string.function_name)
  var content = "**$functionNameLabel**:\n- $strFunctionName"
  if (action.functionCallDetails.parameters.isNotEmpty()) {
    val parametersLabel =
      resources.getQuantityString(R.plurals.parameter, action.functionCallDetails.parameters.size)
    val strParameters =
      action.functionCallDetails.parameters.joinToString("\n") { "- ${it.first}: \"${it.second}\"" }
    content += "\n\n**$parametersLabel**:\n$strParameters"
  }
  return content
}
