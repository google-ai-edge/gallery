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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.DownloadAndTryButton
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun VideoAnalysisPromptButton(
  chatViewModel: ChatViewModel,
  model: Model,
  capturedFrames: List<Bitmap>,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = {
      // Add captured images to chat
      if (capturedFrames.isNotEmpty()) {
        val imageMessage = ChatMessageImage(
          bitmaps = capturedFrames,
          imageBitMaps = capturedFrames.map { it.asImageBitmap() },
          side = ChatSide.USER
        )
        chatViewModel.addMessage(model, imageMessage)
      }
      
      // Add the analysis prompt
      val analysisPrompt = buildVideoAnalysisPrompt()
      val textMessage = ChatMessageText(
        content = analysisPrompt,
        side = ChatSide.USER
      )
      chatViewModel.addMessage(model, textMessage)
    },
    enabled = capturedFrames.isNotEmpty(),
    modifier = modifier
  ) {
    Icon(
      imageVector = Icons.Default.SmartToy,
      contentDescription = null,
      modifier = Modifier.size(18.dp)
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text("Analyze ${capturedFrames.size} Frames")
  }
}

@Composable
fun VideoAnalysisQuickStart(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onFramesCaptured: (List<Bitmap>) -> Unit,
  onAnalyzeFrames: (List<Bitmap>) -> Unit,
  modifier: Modifier = Modifier,
) {
  var capturedFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val downloadStatus by remember {
    derivedStateOf { modelManagerUiState.modelDownloadStatus[model.name] }
  }
  val isModelReady = downloadStatus?.status == com.google.ai.edge.gallery.data.ModelDownloadStatusType.SUCCEEDED ||
                     model.localFileRelativeDirPathOverride.isNotEmpty()
  
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer
    )
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = "Video Object Analysis",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer
      )
      
      Spacer(modifier = Modifier.height(8.dp))
      
      Text(
        text = "Capture 10 frames at 1 FPS intervals, then analyze with your selected VLM model",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer
      )
      
      Spacer(modifier = Modifier.height(16.dp))
      
      if (isModelReady) {
        // Show the capture and analyze workflow when model is ready
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          com.google.ai.edge.gallery.ui.common.chat.VideoFrameCaptureButton(
            onFramesCaptured = { frames ->
              capturedFrames = frames
              onFramesCaptured(frames)
            },
            enabled = true
          )
          
          // "Try it!" button that shows when frames are captured
          if (capturedFrames.isNotEmpty()) {
            Button(
              onClick = { onAnalyzeFrames(capturedFrames) },
              colors = ButtonDefaults.buttonColors(
                containerColor = com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors(task = task)[1]
              )
            ) {
              Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                stringResource(R.string.try_it),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
              )
            }
          } else {
            // Show placeholder for Try it button
            OutlinedButton(
              onClick = { },
              enabled = false
            ) {
              Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "",
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Capture frames first")
            }
          }
        }
        
        if (capturedFrames.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "✓ ${capturedFrames.size} frames captured and ready for analysis",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
          )
        }
      } else {
        // Show download button when model is not ready
        DownloadAndTryButton(
          task = task,
          model = model,
          downloadStatus = downloadStatus,
          enabled = true,
          modelManagerViewModel = modelManagerViewModel,
          onClicked = { },
          compact = false,
          canShowTryIt = false
        )
      }
    }
  }
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