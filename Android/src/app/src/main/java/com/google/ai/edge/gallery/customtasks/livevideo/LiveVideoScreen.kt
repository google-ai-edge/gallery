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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private val CameraDarkBg = Color(0xFF1C1B1F)
private val CameraDarkSurface = Color(0xFF2D2B30)
private val CameraAccent = Color(0xFFE8DEF8)
private val CameraTextPrimary = Color(0xFFF4EFF4)
private val CameraTextSecondary = Color(0xFFCAC4D0)
private val CameraRedActive = Color(0xFFE53935)
private val CameraPillSelected = Color(0xFF4A4458)
private val CameraShutterRing = Color(0xFFF4EFF4)

@Composable
fun LiveVideoScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: LiveVideoViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerState.selectedModel
  val context = LocalContext.current

  var audioPermissionGranted by remember { mutableStateOf(false) }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      audioPermissionGranted = granted
    }

  LaunchedEffect(Unit) {
    viewModel.initCameraController(context)
    viewModel.initVoice(context)
    audioPermissionGranted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!audioPermissionGranted) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  LaunchedEffect(selectedModel) {
    selectedModel?.let {
      viewModel.setCurrentModel(it)
      viewModel.startGazeTracking(it)
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      viewModel.stopGazeTracking()
      viewModel.stopListening()
      viewModel.frameBuffer.clear()
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    TopBar(
      currentMode = uiState.mode,
      onModeSelected = { viewModel.setMode(it) },
      showGazeOverlay = uiState.showGazeOverlay,
      onToggleGazeOverlay = { viewModel.toggleGazeOverlay() },
      gazeEnabled = uiState.gazeEnabled,
      onToggleGazeEnabled = { viewModel.toggleGazeEnabled() },
      voiceEnabled = uiState.voiceEnabled,
      onToggleVoice = { viewModel.toggleVoice() },
      continuousMode = uiState.continuousMode,
      onToggleContinuous = { viewModel.toggleContinuousMode() },
      isSmartCamera = uiState.mode == VideoMode.SMART_CAMERA,
      modifier = Modifier.fillMaxWidth(),
    )

    val previewBitmap by viewModel.previewBitmap.collectAsState()

    Box(modifier = Modifier.fillMaxWidth().weight(0.55f).clipToBounds()) {
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          viewModel.onCameraFrame(bitmap)
          imageProxy.close()
        },
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        modifier = Modifier.fillMaxSize(),
        preferredSize = SL280_PX,
        renderPreview = false,
      )

      previewBitmap?.let { bmp ->
        val ib = bmp.asImageBitmap()
        val zoom = uiState.cameraState.targetZoom
        val cx = uiState.cameraState.frameCenterX
        val cy = uiState.cameraState.frameCenterY

        Canvas(modifier = Modifier.fillMaxSize()) {
          val srcW = (ib.width / zoom).toInt().coerceAtLeast(1)
          val srcH = (ib.height / zoom).toInt().coerceAtLeast(1)
          val srcX = ((cx * ib.width).toInt() - srcW / 2).coerceIn(0, ib.width - srcW)
          val srcY = ((cy * ib.height).toInt() - srcH / 2).coerceIn(0, ib.height - srcH)

          val scale = maxOf(size.width / srcW.toFloat(), size.height / srcH.toFloat())
          val dstW = (srcW * scale).toInt()
          val dstH = (srcH * scale).toInt()
          val dx = ((size.width - dstW) / 2f).toInt()
          val dy = ((size.height - dstH) / 2f).toInt()
          drawImage(
            image = ib,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset(dx, dy),
            dstSize = IntSize(dstW, dstH),
          )
        }
      }

      if (uiState.showGazeOverlay) {
        if (uiState.allCellScores.isNotEmpty()) {
          val scoresArray = remember(uiState.allCellScores) { uiState.allCellScores.toFloatArray() }
          GazeHeatmapOverlay(allScores = scoresArray, modifier = Modifier.fillMaxSize())
        }
        CellHighlightOverlay(highlights = uiState.cellHighlights, modifier = Modifier.fillMaxSize())
      }

      ScanningLineEffect(isActive = uiState.isProcessing, modifier = Modifier.fillMaxSize())

      RuleOfThirdsGrid(
        visible =
          uiState.mode == VideoMode.SMART_CAMERA &&
            uiState.smartCameraSubMode == SmartCameraSubMode.AUTO_ZOOM,
        modifier = Modifier.fillMaxSize(),
      )

      ZoomCrosshair(
        centerX = uiState.cameraState.frameCenterX,
        centerY = uiState.cameraState.frameCenterY,
        zoomLevel = uiState.cameraState.targetZoom,
        isActive = uiState.cameraState.autoZoomEnabled,
        modifier = Modifier.fillMaxSize(),
      )

      ShutterFlashEffect(trigger = uiState.captureTriggered, modifier = Modifier.fillMaxSize())

      ModeSwitchBanner(
        mode = uiState.detectedMode,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
      )

      StatsBadge(
        fps = uiState.effectiveFps,
        frameCount = uiState.frameCount,
        isProcessing = uiState.isProcessing,
        inferenceMs = uiState.inferenceLatencyMs,
        gazeMs = uiState.gazeLatencyMs,
        gazePreprocessMs = uiState.gazePreprocessMs,
        framesUsed = uiState.framesUsed,
        showGaze = uiState.showGazeOverlay,
        gazeEnabled = uiState.gazeEnabled,
        detectedMode =
          if (uiState.mode == VideoMode.SMART_CAMERA) uiState.cameraState.activeMode else null,
        zoomLevel =
          if (uiState.mode == VideoMode.SMART_CAMERA) uiState.cameraState.targetZoom else null,
        captureCount =
          if (uiState.mode == VideoMode.SMART_CAMERA) uiState.cameraState.captureCount else null,
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
      )

      if (uiState.lastAction.isNotEmpty()) {
        ActionIndicator(
          action = uiState.lastAction,
          modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
      }
    }

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .weight(0.45f)
          .background(
            if (uiState.mode == VideoMode.SMART_CAMERA) CameraDarkBg
            else MaterialTheme.colorScheme.surface
          )
    ) {
      when (uiState.mode) {
        VideoMode.FINGERS -> {
          CountingDisplay(
            count = uiState.lastResponse,
            isProcessing = uiState.isProcessing,
            latencyMs = uiState.inferenceLatencyMs,
            modifier = Modifier.fillMaxWidth().weight(1f),
          )
          InputBar(
            mode = uiState.mode,
            isProcessing = uiState.isProcessing,
            autoCaptionEnabled = uiState.autoCaptionEnabled,
            voiceEnabled = uiState.voiceEnabled,
            isListening = uiState.isListening,
            audioPermissionGranted = audioPermissionGranted,
            onSend = { selectedModel?.let { viewModel.runInference(it) } },
            onToggleAutoCaption = { viewModel.toggleAutoCaption(selectedModel) },
            onMicTap = {},
            modifier = Modifier.fillMaxWidth().padding(8.dp),
          )
        }
        VideoMode.SMART_CAMERA -> {
          SmartCameraControls(
            subMode = uiState.smartCameraSubMode,
            onSubModeSelected = { viewModel.setSmartCameraSubMode(it) },
            isActive = uiState.isProcessing,
            onStart = { condition ->
              selectedModel?.let { viewModel.startSmartCamera(it, condition) }
            },
            onStop = { viewModel.stopSmartCamera() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
          )
        }
        else -> {
          AnimatedVisibility(
            visible = uiState.lastResponse.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
          ) {
            ResponseCard(
              response = uiState.lastResponse,
              isProcessing = uiState.isProcessing,
              latencyMs = uiState.inferenceLatencyMs,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }

          val listState = rememberLazyListState()
          LaunchedEffect(uiState.chatHistory.size) {
            if (uiState.chatHistory.isNotEmpty()) {
              listState.animateScrollToItem(uiState.chatHistory.lastIndex)
            }
          }

          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(uiState.chatHistory) { entry -> ChatHistoryItem(entry) }
          }

          if (
            uiState.voiceEnabled &&
              (uiState.isListening || uiState.recognizedText.isNotEmpty() || uiState.isSpeaking)
          ) {
            VoiceStatusBar(
              isListening = uiState.isListening,
              isSpeaking = uiState.isSpeaking,
              recognizedText = uiState.recognizedText,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }

          InputBar(
            mode = uiState.mode,
            isProcessing = uiState.isProcessing,
            autoCaptionEnabled = uiState.autoCaptionEnabled,
            voiceEnabled = uiState.voiceEnabled,
            isListening = uiState.isListening,
            audioPermissionGranted = audioPermissionGranted,
            onSend = { query -> selectedModel?.let { viewModel.runInference(it, query) } },
            onToggleAutoCaption = { viewModel.toggleAutoCaption(selectedModel) },
            onMicTap = {
              if (uiState.isListening) viewModel.stopListening() else viewModel.startListening()
            },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
          )
        }
      }
    }
  }
}

// ─────────────────────────── Components ───────────────────────────

@Composable
private fun TopBar(
  currentMode: VideoMode,
  onModeSelected: (VideoMode) -> Unit,
  showGazeOverlay: Boolean,
  onToggleGazeOverlay: () -> Unit,
  gazeEnabled: Boolean,
  onToggleGazeEnabled: () -> Unit,
  voiceEnabled: Boolean,
  onToggleVoice: () -> Unit,
  continuousMode: Boolean,
  onToggleContinuous: () -> Unit,
  isSmartCamera: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
      VideoMode.entries.forEach { mode ->
        FilterChip(
          selected = mode == currentMode,
          onClick = { onModeSelected(mode) },
          label = { Text(mode.label, fontSize = 11.sp) },
        )
      }
    }

    Spacer(Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = gazeEnabled,
        onClick = onToggleGazeEnabled,
        label = { Text("GemmaGaze", fontSize = 11.sp) },
        leadingIcon = {
          Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
        },
      )
      if (gazeEnabled) {
        FilterChip(
          selected = showGazeOverlay,
          onClick = onToggleGazeOverlay,
          label = { Text("Overlay", fontSize = 11.sp) },
          leadingIcon = {
            Icon(
              if (showGazeOverlay) Icons.Default.Visibility else Icons.Default.VisibilityOff,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
        )
      }
      FilterChip(
        selected = voiceEnabled,
        onClick = onToggleVoice,
        label = { Text("Voice", fontSize = 11.sp) },
        leadingIcon = {
          Icon(
            if (voiceEnabled) Icons.Default.RecordVoiceOver else Icons.Default.MicOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
      )
      if (voiceEnabled) {
        FilterChip(
          selected = continuousMode,
          onClick = onToggleContinuous,
          label = { Text("Auto", fontSize = 11.sp) },
          leadingIcon = {
            Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
          },
        )
      }
    }
  }
}

@Composable
private fun ModeWheelBar(
  currentMode: VideoMode,
  onModeSelected: (VideoMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val modes = VideoMode.entries
  val listState = rememberLazyListState()

  LaunchedEffect(currentMode) {
    val index = modes.indexOf(currentMode)
    if (index >= 0) {
      listState.animateScrollToItem(index = maxOf(0, index - 1))
    }
  }

  Box(
    modifier = modifier.background(CameraDarkBg).padding(bottom = 12.dp, top = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    LazyRow(
      state = listState,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(horizontal = 24.dp),
    ) {
      itemsIndexed(modes.toList()) { _, mode ->
        val isSelected = mode == currentMode
        val bgColor by
          animateColorAsState(
            if (isSelected) CameraPillSelected else Color.Transparent,
            animationSpec = tween(200),
            label = "modeWheelBg",
          )
        val textColor by
          animateColorAsState(
            if (isSelected) CameraAccent else CameraTextSecondary,
            animationSpec = tween(200),
            label = "modeWheelText",
          )

        Box(
          modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
              .background(bgColor)
              .clickable { onModeSelected(mode) }
              .padding(horizontal = 16.dp, vertical = 8.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = mode.label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
          )
        }
      }
    }
  }
}

@Composable
private fun ShutterButton(isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val pressScale by
    animateFloatAsState(
      targetValue = if (isPressed) 0.9f else 1f,
      animationSpec = spring(stiffness = Spring.StiffnessMedium),
      label = "shutterPress",
    )

  val infiniteTransition = rememberInfiniteTransition(label = "shutterPulse")
  val pulseScale by
    infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 1.08f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(800, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "activePulse",
    )

  val innerColor by
    animateColorAsState(
      targetValue = if (isActive) CameraRedActive else CameraShutterRing,
      animationSpec = tween(300),
      label = "shutterInnerColor",
    )

  val finalScale = pressScale * if (isActive) pulseScale else 1f

  Box(
    modifier =
      modifier
        .size(72.dp)
        .scale(finalScale)
        .clip(CircleShape)
        .border(3.dp, CameraShutterRing, CircleShape)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .padding(6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier =
        Modifier.size(if (isActive) 32.dp else 58.dp)
          .clip(if (isActive) RoundedCornerShape(8.dp) else CircleShape)
          .background(innerColor)
    )
  }
}

@Composable
private fun StatsBadge(
  fps: Float,
  frameCount: Int,
  isProcessing: Boolean,
  inferenceMs: Long,
  gazeMs: Long,
  gazePreprocessMs: Long,
  framesUsed: Int,
  showGaze: Boolean,
  gazeEnabled: Boolean,
  detectedMode: String? = null,
  zoomLevel: Float? = null,
  captureCount: Int? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = Color.Black.copy(alpha = 0.7f),
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
      if (detectedMode != null) {
        Text(
          detectedMode.replaceFirstChar { it.uppercase() },
          color = Color(0xFFFF9800),
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
      }
      if (zoomLevel != null && zoomLevel > 1f) {
        Text("%.1fx zoom".format(zoomLevel), color = Color.Cyan, fontSize = 10.sp)
      }
      if (captureCount != null && captureCount > 0) {
        Text("$captureCount captured", color = Color.Green, fontSize = 10.sp)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier =
            Modifier.size(8.dp)
              .clip(CircleShape)
              .background(if (isProcessing) Color.Yellow else Color.Green)
        )
        Spacer(Modifier.width(6.dp))
        Text("%.1f FPS".format(fps), color = Color.White, fontSize = 11.sp)
      }
      if (showGaze && gazeMs > 0) {
        Text("Gaze track: ${gazeMs}ms", color = Color.Green, fontSize = 10.sp)
      }
      if (gazeEnabled && gazePreprocessMs > 0) {
        Text(
          "Gaze select: ${gazePreprocessMs}ms -> ${framesUsed} frames",
          color = Color(0xFF8BC34A),
          fontSize = 10.sp,
        )
      }
      if (inferenceMs > 0) {
        Text(
          "LLM: ${inferenceMs}ms" + if (framesUsed > 0) " (${framesUsed}f)" else "",
          color = Color.Cyan,
          fontSize = 10.sp,
        )
      }
      Text(
        "$frameCount buffered" + if (gazeEnabled) " | Gaze ON" else " | Gaze OFF",
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 10.sp,
      )
    }
  }
}

@Composable
private fun ActionIndicator(action: String, modifier: Modifier = Modifier) {
  val bgColor by
    animateColorAsState(
      if (action.startsWith("CAPTURED")) Color(0xFF4CAF50) else Color.Black.copy(alpha = 0.7f),
      label = "actionBg",
    )

  Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = bgColor) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val icon =
        when {
          action.startsWith("CAPTURED") -> Icons.Default.CameraAlt
          action.startsWith("Zoom") -> Icons.Default.ZoomIn
          action.startsWith("Mode") -> Icons.Default.Speed
          else -> Icons.Default.Speed
        }
      Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(action, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
private fun CellHighlightOverlay(
  highlights: List<Pair<Float, Float>>,
  modifier: Modifier = Modifier,
) {
  if (highlights.isEmpty()) return

  androidx.compose.foundation.Canvas(modifier = modifier) {
    val cellW = size.width / GemmaGazeInterpreter.CELLS_W
    val cellH = size.height / GemmaGazeInterpreter.CELLS_H

    highlights.forEach { (cx, cy) ->
      val left = (cx * GemmaGazeInterpreter.CELLS_W - 0.5f) * cellW
      val top = (cy * GemmaGazeInterpreter.CELLS_H - 0.5f) * cellH
      drawRect(
        color = Color.Green.copy(alpha = 0.25f),
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(cellW, cellH),
      )
      drawRect(
        color = Color.Green.copy(alpha = 0.8f),
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(cellW, cellH),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
      )
    }
  }
}

@Composable
private fun CountingDisplay(
  count: String,
  isProcessing: Boolean,
  latencyMs: Long,
  modifier: Modifier = Modifier,
) {
  val displayNum = count.trim().filter { it.isDigit() }.take(2).ifEmpty { "--" }

  var previousNum by remember { mutableStateOf(displayNum) }
  var pulseKey by remember { mutableStateOf(0) }
  LaunchedEffect(displayNum) {
    if (displayNum != previousNum) {
      pulseKey++
      previousNum = displayNum
    }
  }

  var targetScale by remember { mutableStateOf(1f) }
  LaunchedEffect(pulseKey) {
    if (pulseKey > 0) {
      targetScale = 1.18f
      kotlinx.coroutines.delay(80)
      targetScale = 1f
    }
  }
  val animatedScale by
    animateFloatAsState(
      targetValue = targetScale,
      animationSpec =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
      label = "countBounce",
    )

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        displayNum,
        fontSize = 120.sp,
        fontWeight = FontWeight.Bold,
        color =
          if (isProcessing) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
          else MaterialTheme.colorScheme.primary,
        modifier = Modifier.scale(animatedScale),
      )
      if (isProcessing) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
      } else if (latencyMs > 0) {
        Text(
          "${latencyMs}ms",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ResponseCard(
  response: String,
  isProcessing: Boolean,
  latencyMs: Long,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isProcessing) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        if (latencyMs > 0 && !isProcessing) {
          Text(
            "${latencyMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.width(8.dp))
        }
      }
      if (!isProcessing || response.isNotEmpty()) {
        Text(
          response,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 6,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ChatHistoryItem(entry: ChatEntry) {
  Column(modifier = Modifier.fillMaxWidth()) {
    if (entry.isUser) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Default.Mic,
          contentDescription = null,
          modifier = Modifier.size(12.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
          entry.query,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold,
        )
      }
    } else {
      Text(
        entry.query,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
      )
    }
    if (entry.response.isNotEmpty()) {
      Text(
        entry.response,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun VoiceStatusBar(
  isListening: Boolean,
  isSpeaking: Boolean,
  recognizedText: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color =
      when {
        isListening -> MaterialTheme.colorScheme.primaryContainer
        isSpeaking -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
      },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (isListening) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
        Text(
          if (recognizedText.isNotEmpty()) recognizedText else "Listening...",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      } else if (isSpeaking) {
        Icon(
          Icons.Default.VolumeUp,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
          "Speaking...",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.tertiary,
        )
      } else if (recognizedText.isNotEmpty()) {
        Text(
          "You: $recognizedText",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SmartCameraControls(
  subMode: SmartCameraSubMode,
  onSubModeSelected: (SmartCameraSubMode) -> Unit,
  isActive: Boolean,
  onStart: (String) -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var captureCondition by remember { mutableStateOf("everyone is smiling") }

  Column(
    modifier = modifier.padding(top = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    AnimatedVisibility(
      visible = subMode == SmartCameraSubMode.AUTO_CAPTURE,
      enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
      exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
    ) {
      OutlinedTextField(
        value = captureCondition,
        onValueChange = { captureCondition = it },
        placeholder = { Text("Capture when...", color = CameraTextSecondary) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedTextColor = CameraTextPrimary,
            unfocusedTextColor = CameraTextPrimary,
            cursorColor = CameraAccent,
            focusedBorderColor = CameraAccent,
            unfocusedBorderColor = CameraTextSecondary.copy(alpha = 0.4f),
            focusedContainerColor = CameraDarkSurface,
            unfocusedContainerColor = CameraDarkSurface,
          ),
      )
    }

    AnimatedVisibility(
      visible = subMode != SmartCameraSubMode.AUTO_CAPTURE,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(150)),
    ) {
      Text(
        text = subMode.description,
        color = CameraTextSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
      )
    }

    Spacer(Modifier.height(12.dp))

    ShutterButton(
      isActive = isActive,
      onClick = {
        if (isActive) {
          onStop()
        } else {
          when (subMode) {
            SmartCameraSubMode.AUTO_CAPTURE -> onStart(captureCondition)
            else -> onStart("")
          }
        }
      },
    )

    Spacer(Modifier.height(14.dp))

    SmartCameraSubModeWheel(currentSubMode = subMode, onSubModeSelected = onSubModeSelected)

    Spacer(Modifier.height(4.dp))
  }
}

@Composable
private fun SmartCameraSubModeWheel(
  currentSubMode: SmartCameraSubMode,
  onSubModeSelected: (SmartCameraSubMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SmartCameraSubMode.entries.forEach { sm ->
      val isSelected = sm == currentSubMode
      val bgColor by
        animateColorAsState(
          if (isSelected) CameraPillSelected else Color.Transparent,
          animationSpec = tween(200),
          label = "subModeBg",
        )
      val textColor by
        animateColorAsState(
          if (isSelected) CameraAccent else CameraTextSecondary,
          animationSpec = tween(200),
          label = "subModeText",
        )

      Box(
        modifier =
          Modifier.padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable { onSubModeSelected(sm) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = sm.label,
          color = textColor,
          fontSize = 12.sp,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
      }
    }
  }
}

@Composable
private fun InputBar(
  mode: VideoMode,
  isProcessing: Boolean,
  autoCaptionEnabled: Boolean,
  voiceEnabled: Boolean,
  isListening: Boolean,
  audioPermissionGranted: Boolean,
  onSend: (String) -> Unit,
  onToggleAutoCaption: () -> Unit,
  onMicTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (voiceEnabled && audioPermissionGranted) {
      IconButton(onClick = onMicTap, enabled = !isProcessing) {
        Icon(
          if (isListening) Icons.Default.Stop else Icons.Default.Mic,
          contentDescription = if (isListening) "Stop listening" else "Start listening",
          tint = if (isListening) Color.Red else MaterialTheme.colorScheme.primary,
        )
      }
    }

    when (mode) {
      VideoMode.CONVERSATION -> {
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onSend("") }, enabled = !isProcessing) {
          Icon(Icons.Default.Send, contentDescription = "Ask about what you see")
        }
      }
      VideoMode.CAPTION -> {
        AssistChip(
          onClick = onToggleAutoCaption,
          label = { Text(if (autoCaptionEnabled) "Auto: ON" else "Auto: OFF") },
          leadingIcon = {
            Icon(
              if (autoCaptionEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onSend("") }, enabled = !isProcessing) {
          Icon(Icons.Default.Send, contentDescription = "Caption now")
        }
      }
      VideoMode.FINGERS -> {
        AssistChip(
          onClick = onToggleAutoCaption,
          label = { Text(if (autoCaptionEnabled) "Streaming" else "Start") },
          leadingIcon = {
            Icon(
              if (autoCaptionEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onSend("") }, enabled = !isProcessing) {
          Icon(Icons.Default.CameraAlt, contentDescription = "Count now")
        }
      }
      VideoMode.SMART_CAMERA -> {}
    }
  }
}
