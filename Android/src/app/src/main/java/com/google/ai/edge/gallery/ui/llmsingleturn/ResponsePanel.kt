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

package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_LLM_PROMPT_LAB
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyBenchmarkLlm
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyLoading
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.PagerScrollState
import kotlinx.coroutines.launch

private val OPTIONS = listOf("Response", "Benchmark")
private val ICONS = listOf(Icons.Outlined.AutoAwesome, Icons.Outlined.Timer)
private const val TAG = "AGResponsePanel"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsePanel(
  model: Model,
  viewModel: LlmSingleTurnViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
) {
  val task = TASK_LLM_PROMPT_LAB
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val inProgress = uiState.inProgress
  val initializing = uiState.preparing
  val selectedPromptTemplateType = uiState.selectedPromptTemplateType
  val responseScrollState = rememberScrollState()
  var selectedOptionIndex by remember { mutableIntStateOf(0) }
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val pagerState =
    rememberPagerState(initialPage = task.models.indexOf(model), pageCount = { task.models.size })
  val accelerator = model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = "")

  // Select the "response" tab when prompt template changes.
  LaunchedEffect(selectedPromptTemplateType) { selectedOptionIndex = 0 }

  // Update selected model and clean up previous model when page is settled on a model page.
  LaunchedEffect(pagerState.settledPage) {
    val curSelectedModel = task.models[pagerState.settledPage]
    Log.d(
      TAG,
      "Pager settled on model '${curSelectedModel.name}' from '${model.name}'. Updating selected model.",
    )
    if (curSelectedModel.name != model.name) {
      modelManagerViewModel.cleanupModel(task = task, model = model)
    }
    modelManagerViewModel.selectModel(curSelectedModel)
  }

  // Trigger scroll sync.
  LaunchedEffect(pagerState) {
    snapshotFlow {
        PagerScrollState(
          page = pagerState.currentPage,
          offset = pagerState.currentPageOffsetFraction,
        )
      }
      .collect { scrollState -> modelManagerViewModel.pagerScrollState.value = scrollState }
  }

  // Scroll pager when selected model changes.
  LaunchedEffect(modelManagerUiState.selectedModel) {
    pagerState.animateScrollToPage(task.models.indexOf(model))
  }

  HorizontalPager(state = pagerState) { pageIndex ->
    val curPageModel = task.models[pageIndex]

    val response =
      uiState.responsesByModel[curPageModel.name]?.get(selectedPromptTemplateType.label) ?: ""
    val benchmark =
      uiState.benchmarkByModel[curPageModel.name]?.get(selectedPromptTemplateType.label)

    // Scroll to bottom when response changes.
    LaunchedEffect(response) {
      if (inProgress) {
        responseScrollState.animateScrollTo(responseScrollState.maxValue)
      }
    }

    if (initializing) {
      Box(
        contentAlignment = Alignment.TopStart,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
      ) {
        MessageBodyLoading()
      }
    } else {
      // Message when response is empty.
      if (response.isEmpty()) {
        Row(
          modifier = Modifier.fillMaxSize(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "Response will appear here",
            modifier = Modifier.alpha(0.5f),
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
      // Response markdown.
      else {
        Column(modifier = modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
          // Response/benchmark switch.
          Row(modifier = Modifier.fillMaxWidth()) {
            PrimaryTabRow(
              selectedTabIndex = selectedOptionIndex,
              containerColor = Color.Transparent,
            ) {
              OPTIONS.forEachIndexed { index, title ->
                Tab(
                  selected = selectedOptionIndex == index,
                  onClick = { selectedOptionIndex = index },
                  text = {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                      Icon(
                        ICONS[index],
                        contentDescription = "",
                        modifier = Modifier.size(16.dp).alpha(0.7f),
                      )
                      var curTitle = title
                      if (accelerator.isNotEmpty()) {
                        curTitle = "$curTitle on $accelerator"
                      }
                      val titleColor = MaterialTheme.colorScheme.primary
                      BasicText(
                        text = curTitle,
                        maxLines = 1,
                        color = { titleColor },
                        style = MaterialTheme.typography.bodyMedium,
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
          if (selectedOptionIndex == 0) {
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.weight(1f)) {
              Column(modifier = Modifier.fillMaxSize().verticalScroll(responseScrollState)) {
                MarkdownText(
                  text = response,
                  modifier = Modifier.padding(top = 8.dp, bottom = 40.dp),
                )
              }
              // Copy button.
              IconButton(
                onClick = {
                  scope.launch {
                    val clipData = ClipData.newPlainText("response", response)
                    val clipEntry = ClipEntry(clipData = clipData)
                    clipboard.setClipEntry(clipEntry = clipEntry)
                  }
                },
                colors =
                  IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                  ),
              ) {
                Icon(
                  Icons.Outlined.ContentCopy,
                  contentDescription = "",
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          } else if (selectedOptionIndex == 1) {
            if (benchmark != null) {
              MessageBodyBenchmarkLlm(message = benchmark, modifier = Modifier.fillMaxWidth())
            }
          }
        }
      }
    }
  }
}
