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
  val task = modelManagerViewModel.getTaskById(id = taskId)!!
  var capturedFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

  Column(modifier = modifier.fillMaxSize()) {
    // Video Analysis Quick Start Panel
    VideoAnalysisQuickStart(
      onFramesCaptured = { frames: List<Bitmap> ->
        capturedFrames = frames
      },
      onAnalyzeFrames = { frames: List<Bitmap> ->
        if (frames.isNotEmpty()) {
          // Auto-send the frames and analysis prompt to the chat
          sendVideoAnalysisMessage(viewModel, modelManagerViewModel.uiState.value.selectedModel, frames, buildVideoAnalysisPrompt())
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
          viewModel.addMessage(model, message)
          when (message) {
            is ChatMessageText -> {
              viewModel.generateResponse(model = model, input = message.content, onError = {})
              firebaseAnalytics?.logEvent(
                "message_sent",
                bundleOf("message_length" to message.content.length, "task_id" to task.id)
              )
            }
          }
        }
      },
      onRunAgainClicked = { model, message ->
        when (message) {
          is ChatMessageText -> viewModel.runAgain(model, message, onError = {})
        }
      },
      onBenchmarkClicked = { _, _, _, _ -> },
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
  prompt: String
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
    onError = {}
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