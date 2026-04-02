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

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.CallJsSkillResult
import com.google.ai.edge.gallery.common.CallJsSkillResultWebview
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyWebview
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A Composable function for a bottom sheet used to test a skill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillTesterBottomSheet(agentTools: AgentTools, skill: Skill, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // State variables for inputs, result, logs, and selected tab
  var inputData by remember { mutableStateOf("") }
  var customData by remember { mutableStateOf("") }
  var result by remember { mutableStateOf("") }
  var error by remember { mutableStateOf("") }
  var logs by remember { mutableStateOf("") }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var running by remember { mutableStateOf(false) }
  var resultImage by remember { mutableStateOf<Bitmap?>(null) }
  var resultWebview by remember { mutableStateOf<CallJsSkillResultWebview?>(null) }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      // Title: Skill's name
      Text(
        text = skill.name,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 8.dp),
      )

      // Input for "input data"
      OutlinedTextField(
        value = inputData,
        onValueChange = { inputData = it },
        label = { Text("Input Data") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      )

      // Input for "custom data"
      OutlinedTextField(
        value = customData,
        onValueChange = { customData = it },
        label = { Text("Custom Data") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      )

      // Tab Panel
      val tabs = listOf("Result", "Logs")
      PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = Color.Transparent) {
        tabs.forEachIndexed { index, title ->
          Tab(
            selected = selectedTabIndex == index,
            onClick = { selectedTabIndex = index },
            text = { Text(title) },
          )
        }
      }

      // Tab Content
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .weight(1f, fill = true)
            .padding(vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(8.dp)
      ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          when (selectedTabIndex) {
            // Result tab.
            0 -> {
              val text = error.ifEmpty { result }
              if (text.isNotEmpty()) {
                Text(
                  text = text,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.padding(bottom = 8.dp),
                  color =
                    if (error.isNotEmpty()) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                )
              }

              resultImage?.let { bitmap ->
                Image(
                  bitmap.asImageBitmap(),
                  contentDescription = null,
                  modifier = Modifier.fillMaxWidth(),
                  contentScale = ContentScale.Fit,
                )
              }

              resultWebview?.let { webview ->
                val url = webview.url
                if (url != null) {
                  val finalUrl =
                    agentTools.skillManagerViewModel.getJsSkillWebviewUrl(
                      skillName = skill.name,
                      url = url,
                    )
                  MessageBodyWebview(
                    message =
                      ChatMessageWebView(
                        url = finalUrl,
                        iframe = webview.iframe == true,
                        aspectRatio = 1.333f,
                      )
                  )
                }
              }
            }
            // Logs Tab
            1 -> Text(text = logs)
          }
        }
      }

      // A button to start test.
      Button(
        onClick = {
          running = true
          scope.launch(Dispatchers.Default) {
            try {
              result = ""
              error = ""
              resultImage = null
              resultWebview = null
              // TODO(before launch)
              val url =
                agentTools.skillManagerViewModel.getJsSkillUrl(
                  skillName = skill.name,
                  scriptName = "",
                )
              if (url == null) {
                error = "JS skill url not specified"
              } else {
                val action = CallJsAgentAction(url = url, data = inputData)
                agentTools.sendAgentAction(action)
                val curResult = action.result.await()

                // Extract possible image and webview.
                val moshi: Moshi = Moshi.Builder().build()
                val jsonAdapter: JsonAdapter<CallJsSkillResult> =
                  moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
                val resultJson = runCatching { jsonAdapter.fromJson(curResult) }.getOrNull()
                if (resultJson == null) {
                  result = curResult
                } else {
                  val imageBase64 = resultJson.image?.base64
                  val webview = resultJson.webview
                  result =
                    """{"result": "${resultJson.result ?: ""}", "error": "${resultJson.error ?: ""}"}"""
                  if (imageBase64 != null) {
                    decodeBase64ToBitmap(base64String = imageBase64)?.let { bitmap ->
                      resultImage = bitmap
                    }
                  }
                  if (webview != null) {
                    resultWebview = webview
                  }
                }
              }
            } catch (e: Exception) {
              error = e.message ?: "Unknown error"
            } finally {
              running = false
            }
          }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        enabled = !running,
      ) {
        Text(stringResource(if (running) R.string.running else R.string.run))
      }
    }
  }
}
