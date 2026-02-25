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

package com.google.ai.edge.gallery.ui.benchmark

import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
  initialModel: Model,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  viewModel: BenchmarkViewModel = hiltViewModel(),
  onBackClicked: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  var enableBackButton by remember { mutableStateOf(true) }
  var showRunBenchmarkConfirmationDialog by remember { mutableStateOf(false) }
  val downloadedLlmModelNames = remember {
    modelManagerViewModel.getAllDownloadedModels().filter { it.isLlm }.map { it.name }
  }
  var selectedModelName by remember { mutableStateOf(initialModel.name) }
  var selectedModel by
    remember(selectedModelName) {
      mutableStateOf(modelManagerViewModel.getModelByName(name = selectedModelName)!!)
    }
  val filteredResults = remember { mutableStateListOf<BenchmarkResultInfo>() }
  val configs =
    remember(selectedModel) {
      mutableStateListOf<Config>().apply {
        add(
          SegmentedButtonConfig(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = selectedModel.accelerators.getOrNull(0)?.label ?: Accelerator.CPU.label,
            options = selectedModel.accelerators.map { it.label },
            allowMultiple = false,
          )
        )
        add(
          NumberSliderConfig(
            key = ConfigKeys.PREFILL_TOKENS,
            sliderMin = 16f,
            sliderMax = 1024f,
            defaultValue = 256f,
            valueType = ValueType.INT,
          )
        )
        add(
          NumberSliderConfig(
            key = ConfigKeys.DECODE_TOKENS,
            sliderMin = 16f,
            sliderMax = 1024f,
            defaultValue = 256f,
            valueType = ValueType.INT,
          )
        )
        add(
          NumberSliderConfig(
            key = ConfigKeys.NUMBER_OF_RUNS,
            sliderMin = 1f,
            sliderMax = 10f,
            defaultValue = 3f,
            valueType = ValueType.INT,
          )
        )
      }
    }

  val values: SnapshotStateMap<String, Any> =
    remember(configs) {
      mutableStateMapOf<String, Any>().apply {
        for (config in configs) {
          put(config.key.label, config.defaultValue)
        }
      }
    }

  val sumOfPrefillAndDecodeTokens =
    getIntConfigValue(values = values, key = ConfigKeys.PREFILL_TOKENS) +
      getIntConfigValue(values = values, key = ConfigKeys.DECODE_TOKENS)
  val maxToken = selectedModel.llmMaxToken

  // Update filteredResults when selected model is changed.
  LaunchedEffect(selectedModelName, uiState.results) {
    filteredResults.clear()
    filteredResults.addAll(
      uiState.results.filter {
        it.benchmarkResult.llmResult?.baiscInfo?.modelName == selectedModelName
      }
    )
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Benchmark configs.
    Scaffold(
      topBar = {
        CenterAlignedTopAppBar(
          // Title icon and label.
          title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                stringResource(R.string.benchmark_model),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              BenchmarkModelPicker(
                selectedModelName = selectedModelName,
                modelNames = downloadedLlmModelNames,
                titleResId = R.string.select_downloaded_model,
                onSelected = { selectedModelName = it },
              )
            }
          },
          // The back button.
          navigationIcon = {
            IconButton(onClick = onBackClicked, enabled = enableBackButton) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_navigate_back_icon),
              )
            }
          },
          actions = { Spacer(modifier = Modifier.size(48.dp)) },
        )
      },
      modifier = Modifier.imePadding(),
    ) { innerPadding ->
      Box(
        modifier = Modifier.padding(innerPadding).fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
          // Config items.
          Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
          ) {
            ConfigEditorsPanel(configs = configs, values = values)

            // Info text on the limit of the sum of prefill and decode tokens.
            Text(
              stringResource(
                R.string.benchmark_tokens_limit_message,
                sumOfPrefillAndDecodeTokens,
                maxToken,
              ),
              style = MaterialTheme.typography.bodyMedium,
              color =
                if (sumOfPrefillAndDecodeTokens > maxToken)
                  MaterialTheme.customColors.warningTextColor
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // Buttons.
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
          ) {
            // View results.
            OutlinedButton(
              enabled = filteredResults.isNotEmpty(),
              onClick = {
                viewModel.setShowResultsViewer(showResultsViewer = true)
                firebaseAnalytics?.logEvent(
                  GalleryEvent.BUTTON_CLICKED.id,
                  Bundle().apply {
                    putString("event_type", "view_benchmark_results")
                    putString("model_id", selectedModelName)
                  },
                )
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.view_results))
            }
            // Run benchmark.
            Button(
              enabled = sumOfPrefillAndDecodeTokens <= maxToken,
              onClick = {
                modelManagerViewModel.getModelByName(name = selectedModelName)?.let { model ->
                  showRunBenchmarkConfirmationDialog = true
                }
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Rounded.BarChart, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.benchmark))
            }
          }
        }
      }
    }

    // Results viewer.
    AnimatedVisibility(
      visible = uiState.showResultsViewer,
      enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }) + fadeOut(),
    ) {
      BenchmarkResultsViewer(
        initialModelName = selectedModelName,
        modelManagerViewModel = modelManagerViewModel,
        viewModel = viewModel,
        onClose = { viewModel.setShowResultsViewer(showResultsViewer = false) },
      )
    }
  }

  // Confirmation dialog for running benchmark.
  if (showRunBenchmarkConfirmationDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.run_benchmark)) },
      text = { Text(stringResource(R.string.run_benchmark_confirmation_msg)) },
      onDismissRequest = { showRunBenchmarkConfirmationDialog = false },
      dismissButton = {
        OutlinedButton(
          onClick = { showRunBenchmarkConfirmationDialog = false },
          contentPadding = SMALL_BUTTON_CONTENT_PADDING,
        ) {
          Text(stringResource(R.string.cancel))
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.runBenchmark(
              model = selectedModel,
              accelerator = getStringConfigValue(values = values, key = ConfigKeys.ACCELERATOR),
              prefillTokens = getIntConfigValue(values = values, key = ConfigKeys.PREFILL_TOKENS),
              decodeTokens = getIntConfigValue(values = values, key = ConfigKeys.DECODE_TOKENS),
              runCount = getIntConfigValue(values = values, key = ConfigKeys.NUMBER_OF_RUNS),
            )
            firebaseAnalytics?.logEvent(
              GalleryEvent.BUTTON_CLICKED.id,
              Bundle().apply {
                putString("event_type", "run_benchmark")
                putString("model_id", selectedModelName)
              },
            )
            showRunBenchmarkConfirmationDialog = false
          },
          contentPadding = SMALL_BUTTON_CONTENT_PADDING,
        ) {
          Text(stringResource(R.string.continue_button_label))
        }
      },
    )
  }
}

private fun getStringConfigValue(values: Map<String, Any>, key: ConfigKey): String {
  return convertValueToTargetType(value = values.get(key.label) ?: "", valueType = ValueType.STRING)
    as String
}

private fun getIntConfigValue(values: Map<String, Any>, key: ConfigKey): Int {
  return convertValueToTargetType(value = values.get(key.label) ?: 0, valueType = ValueType.INT)
    as Int
}
