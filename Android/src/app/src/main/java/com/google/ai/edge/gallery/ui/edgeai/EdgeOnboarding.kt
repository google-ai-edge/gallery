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

package com.google.ai.edge.gallery.ui.edgeai

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.appFontFamily
import kotlinx.coroutines.delay

private const val PREFS_NAME = "edgeai-prefs"
private const val PREF_BOOTED = "edgeai-booted"

private val DOWNLOAD_LOG_LINES = listOf(
  "Fetching manifest...",
  "Resolving shards...",
  "Downloading weights...",
  "Verifying checksums...",
  "Warming up runtime...",
  "Model ready.",
)

fun markOnboarded(context: Context) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    .edit().putBoolean(PREF_BOOTED, true).apply()
}

fun isOnboarded(context: Context): Boolean =
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    .getBoolean(PREF_BOOTED, false)

@Composable
fun EdgeOnboarding(
  modelManagerViewModel: ModelManagerViewModel,
  onDone: () -> Unit,
) {
  val context = LocalContext.current
  val mmState by modelManagerViewModel.uiState.collectAsState()
  var step by remember { mutableIntStateOf(1) }

  // Get real LLM models from the allowlist
  val llmModels = remember(mmState.tasks) {
    mmState.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }?.models?.filter { it.isLlm }
      ?: emptyList()
  }
  var selectedModel by remember { mutableStateOf<Model?>(null) }

  // Auto-select first model when list loads
  LaunchedEffect(llmModels) {
    if (selectedModel == null && llmModels.isNotEmpty()) {
      selectedModel = llmModels.first()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(EdgeBg)
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    AnimatedContent(
      targetState = step,
      transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
      label = "OnboardingStep",
    ) { currentStep ->
      when (currentStep) {
        1 -> OnboardingStep1(onNext = { step = 2 })
        2 -> OnboardingStep2(
          models = llmModels,
          loadingModels = mmState.loadingModelAllowlist,
          selectedModel = selectedModel,
          onSelect = { selectedModel = it },
          onNext = { step = 3 },
        )
        3 -> OnboardingStep3(
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          mmState = mmState,
          onDone = {
            markOnboarded(context)
            onDone()
          },
        )
        else -> OnboardingStep1(onNext = { step = 2 })
      }
    }
  }
}

// ── Step 1: Welcome ──────────────────────────────────────────────────────────

@Composable
private fun OnboardingStep1(onNext: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 28.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(32.dp))

    EdgeMarkLogo(size = 64.dp)

    Spacer(Modifier.height(12.dp))

    Text(
      text = "edge · ai",
      color = EdgeTextMute,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      letterSpacing = 3.sp,
    )

    Spacer(Modifier.height(48.dp))

    Text(
      text = "Your model.\nYour device.\nYour data.",
      color = EdgeText,
      fontSize = 36.sp,
      fontFamily = appFontFamily,
      fontWeight = FontWeight.Bold,
      lineHeight = 44.sp,
      textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(20.dp))

    Text(
      text = "Run state-of-the-art language models entirely on your Android device. No internet required. No data ever leaves your phone.",
      color = EdgeTextDim,
      fontSize = 15.sp,
      fontFamily = appFontFamily,
      lineHeight = 22.sp,
      textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(40.dp))

    BulletList(
      items = listOf(
        Pair("Offline-first", "Full capability with zero connectivity"),
        Pair("On your hardware", "Uses your device's GPU & NPU directly"),
        Pair("Open models", "Built on open weights you can trust"),
      )
    )

    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(40.dp))

    PrimaryButton(text = "Get started →", onClick = onNext)
  }
}

// ── Step 2: Model pick (real models) ────────────────────────────────────────

@Composable
private fun OnboardingStep2(
  models: List<Model>,
  loadingModels: Boolean,
  selectedModel: Model?,
  onSelect: (Model) -> Unit,
  onNext: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp, vertical = 32.dp),
  ) {
    Text(
      text = "Pick a model",
      color = EdgeText,
      fontSize = 26.sp,
      fontFamily = appFontFamily,
      fontWeight = FontWeight.Bold,
    )

    Spacer(Modifier.height(6.dp))

    Text(
      text = "You can change this later.",
      color = EdgeTextDim,
      fontSize = 14.sp,
      fontFamily = appFontFamily,
    )

    Spacer(Modifier.height(24.dp))

    if (loadingModels) {
      Text(
        text = "Loading models…",
        color = EdgeTextMute,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
      )
    } else {
      models.forEach { model ->
        OnboardingModelRow(
          model = model,
          selected = model.name == selectedModel?.name,
          onClick = { onSelect(model) },
        )
        Spacer(Modifier.height(10.dp))
      }
    }

    Spacer(Modifier.height(24.dp))

    PrimaryButton(
      text = "Download & continue →",
      onClick = onNext,
    )
  }
}

@Composable
private fun OnboardingModelRow(
  model: Model,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val displayName = model.displayName.ifEmpty { model.name }
  val abbrev = displayName.take(3).uppercase()
  val sizeGb = if (model.sizeInBytes > 0)
    "%.1f GB".format(model.sizeInBytes / 1_000_000_000.0) else "?"

  val borderColor = if (selected) EdgeAccent else EdgeBorderStrong
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(EdgeRadius))
      .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(EdgeRadius))
      .background(if (selected) EdgeAccentSoft else EdgeSurface)
      .clickable(onClick = onClick)
      .padding(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(EdgeSurface2)
          .border(1.dp, EdgeBorderStrong, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Text(text = abbrev, color = EdgeAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(text = displayName, color = EdgeText, fontSize = 15.sp, fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (model.info.isNotEmpty()) {
          Text(text = model.info.take(60), color = EdgeTextDim, fontSize = 12.sp, fontFamily = appFontFamily, maxLines = 1)
          Spacer(Modifier.height(4.dp))
        }
        Text(text = sizeGb, color = EdgeTextMute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      }
    }
  }
}

// ── Step 3: Download (real) ──────────────────────────────────────────────────

@Composable
private fun OnboardingStep3(
  model: Model?,
  modelManagerViewModel: ModelManagerViewModel,
  mmState: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState,
  onDone: () -> Unit,
) {
  val context = LocalContext.current
  val llmTask = mmState.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }
  val downloadStatus = model?.let { mmState.modelDownloadStatus[it.name] }
  val modelName = model?.displayName?.ifEmpty { model.name } ?: "Model"

  val progress = if (downloadStatus != null && downloadStatus.totalBytes > 0) {
    downloadStatus.receivedBytes.toFloat() / downloadStatus.totalBytes
  } else 0f

  val phase = when (downloadStatus?.status) {
    ModelDownloadStatusType.IN_PROGRESS -> "Downloading weights"
    ModelDownloadStatusType.UNZIPPING -> "Extracting model"
    ModelDownloadStatusType.SUCCEEDED -> "Model ready"
    ModelDownloadStatusType.FAILED -> "Download failed"
    else -> "Preparing…"
  }

  val logLines = DOWNLOAD_LOG_LINES.take(
    when (downloadStatus?.status) {
      ModelDownloadStatusType.IN_PROGRESS -> (progress * DOWNLOAD_LOG_LINES.size).toInt().coerceAtLeast(1)
      ModelDownloadStatusType.SUCCEEDED -> DOWNLOAD_LOG_LINES.size
      else -> 1
    }
  )

  // Start download when entering this step
  LaunchedEffect(model?.name) {
    if (model != null && llmTask != null) {
      val status = mmState.modelDownloadStatus[model.name]?.status
      if (status != ModelDownloadStatusType.SUCCEEDED && status != ModelDownloadStatusType.IN_PROGRESS) {
        modelManagerViewModel.downloadModel(llmTask, model)
      }
    }
  }

  // Advance when download completes
  LaunchedEffect(downloadStatus?.status) {
    if (downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
      delay(800)
      onDone()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 28.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(32.dp))

    EdgeMarkLogo(size = 56.dp)

    Spacer(Modifier.height(32.dp))

    Text(
      text = modelName,
      color = EdgeText,
      fontSize = 22.sp,
      fontFamily = appFontFamily,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(8.dp))

    Text(
      text = phase,
      color = EdgeAccent,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      letterSpacing = 1.sp,
    )

    Spacer(Modifier.height(32.dp))

    // Progress bar
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(EdgeSurface2),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(fraction = progress)
          .height(6.dp)
          .background(EdgeAccent),
      )
    }

    Spacer(Modifier.height(8.dp))

    Text(
      text = "${(progress * 100).toInt()}%",
      color = EdgeTextMute,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
    )

    Spacer(Modifier.height(32.dp))

    // Log lines
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(EdgeRadiusSm))
        .background(EdgeSurface)
        .border(1.dp, EdgeBorder, RoundedCornerShape(EdgeRadiusSm))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      logLines.forEach { line ->
        Text(
          text = "> $line",
          color = EdgeTextDim,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun BulletList(items: List<Pair<String, String>>) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    items.forEach { (title, subtitle) ->
      Row(verticalAlignment = Alignment.Top) {
        Box(
          modifier = Modifier
            .padding(top = 4.dp)
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(EdgeAccent),
        )
        Spacer(Modifier.width(14.dp))
        Column {
          Text(
            text = title,
            color = EdgeText,
            fontSize = 15.sp,
            fontFamily = appFontFamily,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = subtitle,
            color = EdgeTextDim,
            fontSize = 13.sp,
            fontFamily = appFontFamily,
          )
        }
      }
    }
  }
}

@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(52.dp)
      .clip(RoundedCornerShape(EdgeRadius))
      .background(EdgeAccent)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = Color.Black,
      fontSize = 16.sp,
      fontFamily = appFontFamily,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
fun TagChip(text: String) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(EdgeSurface2)
      .border(1.dp, EdgeBorderStrong, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(
      text = text,
      color = EdgeTextDim,
      fontSize = 10.sp,
      fontFamily = appFontFamily,
    )
  }
}
