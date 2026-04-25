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

package com.google.ai.edge.gallery.customtasks.ArTranslatorTask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.RotationalLoader
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGArTranslatorScreen"
private const val CAMERA_FEED_PREFERRED_SIZE = 640

@Composable
fun ArTranslatorScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  onNavigateToDictionary: () -> Unit,
  onSaveClick: (DictionaryEntry, ByteArray?) -> Unit,
  mainLanguage: Language,
  learnLanguage: Language,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel

  var identificationResult by remember { mutableStateOf("") }
  var isRunning by remember { mutableStateOf(false) }
  var shouldTriggerInference by remember { mutableStateOf(true) }
  var triggerRequestTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var parsedEntry by remember { mutableStateOf<DictionaryEntry?>(null) }

  var capturedImageBytes by remember { mutableStateOf<ByteArray?>(null) }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val isInitializing =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING

  val mainLangName =
    when (mainLanguage) {
      Language.LANGUAGE_ENGLISH -> "English"
      Language.LANGUAGE_CHINESE -> "Chinese"
      Language.LANGUAGE_FRENCH -> "French"
      Language.LANGUAGE_SPANISH -> "Spanish"
      Language.LANGUAGE_KOREAN -> "Korean"
      Language.LANGUAGE_HINDI -> "Hindi"
      Language.LANGUAGE_JAPANESE -> "Japanese"
      Language.LANGUAGE_GERMAN -> "German"
      Language.LANGUAGE_ITALIAN -> "Italian"
      Language.LANGUAGE_PORTUGUESE -> "Portuguese"
      Language.LANGUAGE_THAI -> "Thai"
      Language.LANGUAGE_VIETNAMESE -> "Vietnamese"
      Language.LANGUAGE_ARABIC -> "Arabic"
      Language.LANGUAGE_RUSSIAN -> "Russian"
      else -> "English"
    }

  val learnLangName =
    when (learnLanguage) {
      Language.LANGUAGE_ENGLISH -> "English"
      Language.LANGUAGE_CHINESE -> "Chinese"
      Language.LANGUAGE_FRENCH -> "French"
      Language.LANGUAGE_SPANISH -> "Spanish"
      Language.LANGUAGE_KOREAN -> "Korean"
      Language.LANGUAGE_HINDI -> "Hindi"
      Language.LANGUAGE_JAPANESE -> "Japanese"
      Language.LANGUAGE_GERMAN -> "German"
      Language.LANGUAGE_ITALIAN -> "Italian"
      Language.LANGUAGE_PORTUGUESE -> "Portuguese"
      Language.LANGUAGE_THAI -> "Thai"
      Language.LANGUAGE_VIETNAMESE -> "Vietnamese"
      Language.LANGUAGE_ARABIC -> "Arabic"
      Language.LANGUAGE_RUSSIAN -> "Russian"
      else -> "Chinese"
    }

  val prompt =
    """
    Identify the main object in this image.
    Output your response in this EXACT JSON format:
    [
      {
        "label": "<object name in $learnLangName>",
        "translation": "<translation in $mainLangName>",
        "definition": "<short definition in $mainLangName>"
      }
    ]
    """
      .trimIndent()

  DisposableEffect(Unit) {
    onDispose {
      Log.d(TAG, "Disposing ArTranslatorScreen: cleaning up active conversations")
      try {
        val instance = model.instance as? LlmModelInstance
        if (instance != null && instance.conversation.isAlive) {
          instance.conversation.close()
          Log.d(TAG, "Active conversation closed during teardown")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Teardown conversation failed", e)
      }
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    if (isInitializing) {
      Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize()) {
        Column(
          modifier = Modifier.align(Alignment.Center),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          RotationalLoader(size = 32.dp)
          Text(
            stringResource(R.string.aichat_initializing_title),
            style =
              MaterialTheme.typography.headlineLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
              ),
          )
          Text(
            stringResource(R.string.aichat_initializing_content),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      LiveCameraView(
        onBitmap = { bitmap, proxy ->
          val currentTime = System.currentTimeMillis()
          if (shouldTriggerInference && !isRunning && currentTime - triggerRequestTime > 2000) {
            shouldTriggerInference = false
            isRunning = true
            onRunningStateChanged(true)
            identificationResult = ""
            parsedEntry = null

            scope.launch(Dispatchers.Default) {
              try {
                val instance = model.instance as LlmModelInstance
                val engine = instance.engine

                if (instance.conversation.isAlive) {
                  Log.d(TAG, "Closing previous conversation before creating new one")
                  instance.conversation.close()
                }

                val conversationConfig =
                  ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
                  )
                Log.d(TAG, "Creating new conversation")
                val newConversation = engine.createConversation(conversationConfig)
                instance.conversation = newConversation

                val contents = mutableListOf<Content>()

                // Crop to center square
                // 1. Find the shortest side to determine the square size
                val minDim = if (bitmap.width < bitmap.height) bitmap.width else bitmap.height
                // 2. Calculate coordinates to center the square
                val sx = (bitmap.width - minDim) / 2
                val sy = (bitmap.height - minDim) / 2
                // 3. Create the new cropped bitmap
                val croppedBitmap = Bitmap.createBitmap(bitmap, sx, sy, minDim, minDim)

                val imageBytes = croppedBitmap.toPngByteArray()
                capturedImageBytes = imageBytes
                contents.add(Content.ImageBytes(imageBytes))

                if (croppedBitmap != bitmap) croppedBitmap.recycle()

                contents.add(Content.Text(prompt))

                Log.d(TAG, "Sending message with prompt: $prompt")
                try {
                  val startTime = System.currentTimeMillis()
                  val responseMessage = newConversation.sendMessage(Contents.of(contents))
                  identificationResult = responseMessage.toString()

                  val duration = System.currentTimeMillis() - startTime
                  Log.d(TAG, "Inference completed successfully in $duration ms")
                  Log.d(TAG, "Inference result: $identificationResult")

                  val entry = parseResponse(identificationResult, mainLanguage, learnLanguage)
                  parsedEntry = entry

                  isRunning = false
                  onRunningStateChanged(false)
                } catch (throwable: Throwable) {
                  Log.e(TAG, "Inference error", throwable)
                  identificationResult = "Error: ${throwable.message}"
                  isRunning = false
                  onRunningStateChanged(false)
                } finally {
                  try {
                    if (newConversation.isAlive) {
                      newConversation.close()
                      Log.d(TAG, "Active conversation closed securely")
                    }
                  } catch (e: Exception) {
                    Log.e(TAG, "Error closing conversation securely", e)
                  }
                }
              } catch (e: Exception) {
                identificationResult = "Error: ${e.message}"
                isRunning = false
                onRunningStateChanged(false)
              }
            }
          }
          proxy.close()
        },
        preferredSize = CAMERA_FEED_PREFERRED_SIZE,
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
      )

      if (identificationResult.isEmpty()) {
        // Aiming Guide Box
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.fillMaxWidth(0.9f)
              .aspectRatio(1f)
              .align(Alignment.Center)
              .border(
                width = 2.dp,
                color = Color(0xFF80B3FF), // Light-ish blue
                shape = RoundedCornerShape(16.dp),
              ),
        ) {
          Box(
            modifier =
              Modifier.background(
                  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                  shape = RoundedCornerShape(16.dp),
                )
                .padding(16.dp)
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              if (isRunning) {
                RotationalLoader(size = 48.dp)
              }
              val displayText =
                if (isRunning) {
                  stringResource(R.string.artranslator_identifying)
                } else {
                  stringResource(R.string.artranslator_waiting)
                }
              Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      }

      if (identificationResult.isNotEmpty()) {
        Box(
          modifier =
            Modifier.align(Alignment.Center)
              .padding(16.dp)
              .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
              )
              .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                RoundedCornerShape(16.dp),
              )
              .padding(16.dp)
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val bitmap =
              remember(capturedImageBytes) {
                capturedImageBytes?.let {
                  BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                }
              }
            if (bitmap != null) {
              Image(
                bitmap = bitmap,
                contentDescription = "Captured object",
                modifier = Modifier.height(200.dp).fillMaxWidth(),
              )
              Spacer(modifier = Modifier.height(8.dp))
            }
            val addedVocabMessage = stringResource(R.string.artranslator_added_to_vocab)
            val displayText =
              if (parsedEntry != null) {
                stringResource(
                  R.string.artranslator_result,
                  parsedEntry!!.word,
                  parsedEntry!!.translation,
                  parsedEntry!!.definition,
                )
              } else {
                identificationResult
              }
            Text(
              text = displayText,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (parsedEntry != null) {
                Button(
                  onClick = {
                    onSaveClick(parsedEntry!!, capturedImageBytes)
                    scope.launch { snackbarHostState.showSnackbar(addedVocabMessage) }
                  }
                ) {
                  Text(
                    stringResource(R.string.artranslator_add_to_vocab),
                    textAlign = TextAlign.Center,
                  )
                }
              }
              Button(
                onClick = {
                  identificationResult = ""
                  parsedEntry = null

                  capturedImageBytes = null
                  shouldTriggerInference = true
                  triggerRequestTime = System.currentTimeMillis()
                }
              ) {
                Text(
                  stringResource(R.string.artranslator_scan_another),
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
      }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
  }
}

private fun Bitmap.toPngByteArray(): ByteArray {
  val stream = ByteArrayOutputStream()
  this.compress(Bitmap.CompressFormat.PNG, 100, stream)
  return stream.toByteArray()
}

private fun parseResponse(
  response: String,
  mainLanguage: Language,
  learnLanguage: Language,
): DictionaryEntry? {
  try {
    val jsonRegex = """\[\s*\{[\s\S]*\}\s*\]""".toRegex()
    val match = jsonRegex.find(response)?.value ?: response
    val jsonArray = org.json.JSONArray(match)
    if (jsonArray.length() > 0) {
      val obj = jsonArray.getJSONObject(0)
      val word = obj.optString("label", "").trim()
      val translation = obj.optString("translation", "").trim()
      val definition = obj.optString("definition", "").trim()
      if (word.isNotEmpty() && translation.isNotEmpty() && definition.isNotEmpty()) {
        return DictionaryEntry.newBuilder()
          .setWord(word)
          .setTranslation(translation)
          .setDefinition(definition)
          .setMainLanguage(mainLanguage)
          .setLearnLanguage(learnLanguage)
          .build()
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "parseResponse error reading JSON", e)
  }
  return null
}
