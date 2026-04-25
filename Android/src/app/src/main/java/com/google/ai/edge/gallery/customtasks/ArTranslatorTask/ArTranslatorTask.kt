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

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class ArTranslatorTask @Inject constructor(private val context: Context) : CustomTask {
  override val task: Task =
    Task(
      id = "ar_translator",
      label = "AR Translator",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.Translate,
      description = "AR Translator using Gemma 4 e2b model.",
      models = mutableListOf(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      supportImage = true,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = null,
      tools = emptyList(),
      enableConversationConstrainedDecoding = false,
      coroutineScope = coroutineScope,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    val modelManagerViewModel: ModelManagerViewModel = myData.modelManagerViewModel

    val viewModel: ArTranslatorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val entries by viewModel.dictionaryEntries.collectAsState()
    val mainLanguage by viewModel.mainLanguage.collectAsState()
    val learnLanguage by viewModel.learnLanguage.collectAsState()

    var isInferenceRunning by remember { mutableStateOf(false) }
    val screens = ArTranslatorScreenName.entries.map { it.value }
    val pagerState = rememberPagerState(pageCount = { screens.size })

    LaunchedEffect(uiState.currentScreen) {
      val targetPage = screens.indexOf(uiState.currentScreen)
      if (targetPage != -1 && targetPage != pagerState.currentPage) {
        pagerState.scrollToPage(targetPage)
      }
    }

    LaunchedEffect(pagerState.currentPage) {
      val currentScreen = screens[pagerState.currentPage]
      if (currentScreen != uiState.currentScreen) {
        viewModel.setScreen(currentScreen)
      }
    }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      bottomBar = {
        NavigationBar {
          NavigationBarItem(
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") },
            selected = uiState.currentScreen == "home",
            onClick = { viewModel.setScreen("home") },
            enabled = !isInferenceRunning,
          )
          NavigationBarItem(
            icon = { Icon(Icons.Filled.Search, contentDescription = "Camera") },
            label = { Text("Camera") },
            selected = uiState.currentScreen == "camera",
            onClick = { viewModel.setScreen("camera") },
            enabled = !isInferenceRunning,
          )
          NavigationBarItem(
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Vocabulary") },
            label = { Text("Vocabulary") },
            selected = uiState.currentScreen == "vocabulary",
            onClick = { viewModel.setScreen("vocabulary") },
            enabled = !isInferenceRunning,
          )
          NavigationBarItem(
            icon = { Icon(Icons.Filled.Star, contentDescription = "Study") },
            label = { Text("Study") },
            selected = uiState.currentScreen == "study",
            onClick = { viewModel.setScreen("study") },
            enabled = !isInferenceRunning,
          )
        }
      },
    ) { innerPadding ->
      Box(
        modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())
      ) {
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize(),
          userScrollEnabled = !isInferenceRunning,
        ) { page ->
          when (screens[page]) {
            "home" ->
              HomeScreen(
                currentMainLanguage = mainLanguage,
                currentLearnLanguage = learnLanguage,
                onMainLanguageSelected = { viewModel.setMainLanguage(it) },
                onLearnLanguageSelected = { viewModel.setLearnLanguage(it) },
                onNavigateToCamera = { viewModel.setScreen("camera") },
              )
            "camera" -> {
              ArTranslatorScreen(
                modelManagerViewModel = modelManagerViewModel,
                bottomPadding = myData.bottomPadding,
                onNavigateToDictionary = { viewModel.setScreen("vocabulary") },
                onSaveClick = { entry, imageBytes ->
                  viewModel.addDictionaryEntry(entry, imageBytes)
                },
                mainLanguage = mainLanguage,
                learnLanguage = learnLanguage,
                onRunningStateChanged = { isInferenceRunning = it },
              )
            }
            "vocabulary" -> {
              DictionaryScreen(
                entries = entries,
                onDelete = { word ->
                  val entry = entries.firstOrNull { it.word == word }
                  if (entry != null) {
                    viewModel.deleteDictionaryEntry(entry)
                  }
                },
              )
            }
            "study" -> StudyScreen(entries = entries)
          }
        }
      }
    }
  }

  companion object {
    private const val TAG = "AGArTranslatorTask"
  }
}

enum class ArTranslatorScreenName(val value: String) {
  HOME("home"),
  CAMERA("camera"),
  VOCABULARY("vocabulary"),
  STUDY("study"),
}
