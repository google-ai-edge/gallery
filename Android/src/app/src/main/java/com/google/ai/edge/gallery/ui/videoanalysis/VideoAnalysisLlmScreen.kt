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

package com.google.ai.edge.gallery.ui.videoanalysis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun VideoAnalysisScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = hiltViewModel(),
) {
  VideoAnalysisViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.VIDEO_ANALYSIS,
    navigateUp = navigateUp,
    modifier = modifier,
  )
}

@Composable
fun VideoAnalysisViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val task = modelManagerViewModel.getTaskById(id = taskId)
  var capturedFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

  // Handle case when task is not yet loaded
  if (task == null) {
    // Trigger model allowlist loading if needed
    LaunchedEffect(Unit) {
      modelManagerViewModel.loadModelAllowlistWhenNeeded()
    }
    
    // Show loading state
    Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      CircularProgressIndicator()
    }
    return
  }

  Column(modifier = modifier.fillMaxSize()) {
    // Video Analysis Quick Start Panel
    VideoAnalysisQuickStart(
      onFramesCaptured = { frames: List<Bitmap> ->
        capturedFrames = frames
      },
      onAnalyzeFrames = { frames: List<Bitmap> ->
        val selectedModel = modelManagerViewModel.uiState.value.selectedModel
        if (frames.isNotEmpty() && selectedModel.name.isNotEmpty()) {
          // Auto-send the frames and analysis prompt to the chat
          sendVideoAnalysisMessage(
            viewModel = viewModel, 
            model = selectedModel, 
            frames = frames, 
            prompt = buildVideoAnalysisPrompt(),
            context = context,
            task = task,
            modelManagerViewModel = modelManagerViewModel
          )
        }
      },
      modifier = Modifier.padding(16.dp)
    )
    
    // Use the existing ChatView with video analysis capabilities
    ChatView(
      task = task,
      viewModel = viewModel,
      modelManagerViewModel = modelManagerViewModel,
      onSendMessage = { model, messages ->
        for (message in messages) {
          viewModel.addMessage(model = model, message = message)
        }

        var text = ""
        val images: MutableList<Bitmap> = mutableListOf()
        var chatMessageText: ChatMessageText? = null
        for (message in messages) {
          if (message is ChatMessageText) {
            chatMessageText = message
            text = message.content
          } else if (message is ChatMessageImage) {
            images.addAll(message.bitmaps)
          }
        }
        if (text.isNotEmpty() && chatMessageText != null) {
          modelManagerViewModel.addTextInputHistory(text)
          viewModel.generateResponse(
            model = model,
            input = text,
            images = images,
            onError = {
              viewModel.handleError(
                context = context,
                task = task,
                model = model,
                modelManagerViewModel = modelManagerViewModel,
                triggeredMessage = chatMessageText,
              )
            },
          )

          firebaseAnalytics?.logEvent(
            "generate_action",
            bundleOf("capability_name" to task.id, "model_id" to model.name),
          )
        }
      },
      onRunAgainClicked = { model, message ->
        if (message is ChatMessageText) {
          viewModel.runAgain(
            model = model,
            message = message,
            onError = {
              viewModel.handleError(
                context = context,
                task = task,
                model = model,
                modelManagerViewModel = modelManagerViewModel,
                triggeredMessage = message,
              )
            },
          )
        }
      },
      onBenchmarkClicked = { _, _, _, _ -> },
      onResetSessionClicked = { model -> viewModel.resetSession(task = task, model = model) },
      showStopButtonInInputWhenInProgress = true,
      onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
      navigateUp = navigateUp,
      modifier = Modifier.weight(1f)
    )
  }
}

/**
 * Sends video analysis message to the chat by adding images and prompt
 */
private fun sendVideoAnalysisMessage(
  viewModel: LlmChatViewModelBase,
  model: Model,
  frames: List<Bitmap>,
  prompt: String,
  context: android.content.Context,
  task: com.google.ai.edge.gallery.data.Task,
  modelManagerViewModel: ModelManagerViewModel
) {
  // Add captured images to chat
  if (frames.isNotEmpty()) {
    val imageMessage = ChatMessageImage(
      bitmaps = frames,
      imageBitMaps = frames.map { it.asImageBitmap() },
      side = ChatSide.USER
    )
    viewModel.addMessage(model, imageMessage)
  }
  
  // Add the analysis prompt and trigger generation
  val textMessage = ChatMessageText(
    content = prompt,
    side = ChatSide.USER
  )
  viewModel.addMessage(model, textMessage)
  
  // Generate response with images
  viewModel.generateResponse(
    model = model,
    input = prompt,
    images = frames,
    onError = {
      viewModel.handleError(
        context = context,
        task = task,
        model = model,
        modelManagerViewModel = modelManagerViewModel,
        triggeredMessage = textMessage,
      )
    }
  )
}

private fun buildVideoAnalysisPrompt(): String {
  return """
    Analyze the following sequence of video frames captured at 1 FPS intervals. 
    
    Please identify and describe the objects present in these frames and provide your response 
    in the following JSON format:
    
    {
      "detected_objects": [
        {
          "name": "object_name",
          "confidence": 0.95,
          "description": "detailed description of the object",
          "position": {
            "x": 0.3,
            "y": 0.4, 
            "width": 0.2,
            "height": 0.4
          }
        }
      ],
      "summary": "Overall summary of what was observed across the frames",
      "scene_description": "Description of the overall scene and context"
    }
    
    Focus on:
    1. Identifying distinct objects and their characteristics
    2. Tracking object movement or changes across frames
    3. Providing confidence scores for each detection
    4. Describing the overall scene context
    
    Please be thorough but concise in your descriptions.
  """.trimIndent()
}