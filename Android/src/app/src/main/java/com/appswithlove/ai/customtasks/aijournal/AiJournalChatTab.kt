package com.appswithlove.ai.customtasks.aijournal

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appswithlove.ai.R
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.core.content.ContextCompat
import com.appswithlove.ai.common.calculatePeakAmplitude
import com.appswithlove.ai.data.MAX_AUDIO_CLIP_DURATION_SEC
import com.appswithlove.ai.data.Model
import com.appswithlove.ai.data.SAMPLE_RATE
import com.appswithlove.ai.ui.common.AudioAnimation
import com.appswithlove.ai.ui.theme.customColors
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiJournalChatTab(
  model: Model,
  viewModel: AiJournalViewModel,
  bottomPadding: Dp,
) {
  val uiState by viewModel.uiState.collectAsState()
  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val sendButtonShape = remember {
    val rad = Math.toRadians(90.0).toFloat()
    val cos = kotlin.math.cos(rad)
    val sin = kotlin.math.sin(rad)
    MaterialShapes.Arrow.transformed { x, y -> androidx.graphics.shapes.TransformResult(x * cos - y * sin, x * sin + y * cos) }
  }.toShape()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // Audio recording state
  var isRecording by remember { mutableStateOf(false) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  val elapsedMs = remember { mutableLongStateOf(0L) }
  val audioRecordState = remember { mutableStateOf<AudioRecord?>(null) }
  val audioStream = remember { ByteArrayOutputStream() }
  val elapsedSeconds by remember {
    derivedStateOf { "%.1f".format(elapsedMs.longValue.toFloat() / 1000f) }
  }

  // Cleanup on disposal
  DisposableEffect(Unit) { onDispose { audioRecordState.value?.release() } }

  // Reset amplitude when not recording
  LaunchedEffect(isRecording) {
    if (!isRecording) curAmplitude = 0
  }

  fun startRecordingNow() {
    isRecording = true
    scope.launch {
      startRecording(
        context = context,
        audioRecordState = audioRecordState,
        audioStream = audioStream,
        elapsedMs = elapsedMs,
        onAmplitudeChanged = { curAmplitude = it },
        onMaxDurationReached = {
          val bytes = stopRecording(audioRecordState, audioStream)
          isRecording = false
          if (bytes.isNotEmpty()) viewModel.sendAudioMessage(bytes, model)
        },
      )
    }
  }

  val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) startRecordingNow()
  }

  fun onMicTap() {
    val hasPermission = ContextCompat.checkSelfPermission(
      context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) startRecordingNow()
    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
  }

  // Auto-scroll to bottom when new messages arrive
  LaunchedEffect(uiState.messages.size) {
    if (uiState.messages.isNotEmpty()) {
      listState.animateScrollToItem(uiState.messages.lastIndex)
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Audio animation background
    AnimatedVisibility(
      visible = isRecording,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      AudioAnimation(
        bgColor = MaterialTheme.colorScheme.surface,
        amplitude = curAmplitude,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = 0.8f },
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .imePadding()
        .padding(bottom = bottomPadding),
    ) {
      // Messages list
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item { Spacer(Modifier.height(8.dp)) }

        if (uiState.messages.isEmpty()) {
          item {
            Box(
              modifier = Modifier.fillParentMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = stringResource(R.string.aijournal_empty_title),
                  style = MaterialTheme.typography.headlineMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                  text = stringResource(R.string.aijournal_empty_subtitle),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
              }
            }
          }
        }

        items(uiState.messages, key = { it.id }) { message ->
          ChatBubble(message = message)
        }

        item { Spacer(Modifier.height(8.dp)) }
      }

      // Input area
      if (isRecording) {
        // Recording controls
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Cancel button
          IconButton(
            onClick = {
              stopRecording(audioRecordState, audioStream)
              isRecording = false
            },
            colors = IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.aijournal_recording_cancel), tint = MaterialTheme.colorScheme.onSurface)
          }

          // Recording indicator + elapsed time
          Row(
            modifier = Modifier
              .weight(1f)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .background(MaterialTheme.customColors.recordButtonBgColor, CircleShape)
              )
              Text("$elapsedSeconds s", style = MaterialTheme.typography.bodyMedium)
            }

            // Send button
            IconButton(
              onClick = {
                val bytes = stopRecording(audioRecordState, audioStream)
                isRecording = false
                if (bytes.isNotEmpty()) viewModel.sendAudioMessage(bytes, model)
              },
              colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
              ),
            ) {
              Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.aijournal_send_recording), tint = Color.White)
            }
          }
        }
      } else {
        // Text input + mic + send
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.aijournal_input_placeholder)) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
              onSend = {
                if (inputText.isNotBlank() && !uiState.isProcessing) {
                  viewModel.sendMessage(inputText.trim(), model)
                  inputText = ""
                }
              },
            ),
            trailingIcon = {
              IconButton(
                onClick = { onMicTap() },
                enabled = !uiState.isProcessing,
              ) {
                Icon(
                  imageVector = Icons.Filled.Mic,
                  contentDescription = stringResource(R.string.aijournal_voice_input),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
          )

          // Send button — arrow shape rotated to point right
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .aspectRatio(1f)
              .clip(sendButtonShape)
              .background(
                if (inputText.isNotBlank() && !uiState.isProcessing)
                  MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
              )
              .then(
                if (inputText.isNotBlank() && !uiState.isProcessing)
                  Modifier.combinedClickable(onClick = {
                    viewModel.sendMessage(inputText.trim(), model)
                    inputText = ""
                  })
                else Modifier
              ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Send,
              contentDescription = stringResource(R.string.aijournal_send),
              modifier = Modifier.size(20.dp),
              tint = if (inputText.isNotBlank() && !uiState.isProcessing)
                MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatBubble(message: ChatMessage) {
  val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
  val isVoice = message.inputType.startsWith("voice_")
  var showProcessLog by remember { mutableStateOf(false) }
  val hasProcessLog = !message.isUser && message.processLog != null

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .clip(
          RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (message.isUser) 16.dp else 4.dp,
            bottomEnd = if (message.isUser) 4.dp else 16.dp,
          )
        )
        .background(
          if (message.isUser) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant
        )
        .then(
          if (hasProcessLog) Modifier.combinedClickable(
            onClick = {},
            onLongClick = { showProcessLog = true },
          ) else Modifier
        )
        .padding(12.dp),
    ) {
      if (message.isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      } else {
        // Voice message badge
        if (isVoice && message.isUser) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Mic,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Text(
              text = message.audioDurationSec?.let { "%.1fs".format(it) } ?: stringResource(R.string.aijournal_voice_label),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val engine = when (message.inputType) {
              "voice_gemma" -> "Gemma"
              "voice_stt" -> "STT"
              else -> null
            }
            if (engine != null) {
              Text(
                text = engine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                modifier = Modifier
                  .background(
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                    RoundedCornerShape(4.dp),
                  )
                  .padding(horizontal = 4.dp, vertical = 1.dp),
              )
            }
          }
        } else {
          Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.isUser) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (hasProcessLog) {
          Text(
            text = stringResource(R.string.aijournal_long_press_for_details),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        Text(
          text = timeFormat.format(Date(message.timestamp)),
          style = MaterialTheme.typography.labelSmall,
          color = if (message.isUser)
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
          else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }

  // Process log bottom sheet
  if (showProcessLog && message.processLog != null) {
    ModalBottomSheet(
      onDismissRequest = { showProcessLog = false },
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      ProcessLogSheet(message.processLog)
    }
  }
}

@Composable
private fun ProcessLogSheet(json: String) {
  val sections = remember(json) {
    try {
      com.google.gson.Gson().fromJson(
        json,
        com.google.gson.reflect.TypeToken.getParameterized(
          List::class.java,
          Map::class.java,
        ).type,
      ) as? List<Map<String, String>> ?: emptyList()
    } catch (_: Exception) { emptyList() }
  }

  Column(
    modifier = Modifier
      .padding(horizontal = 20.dp)
      .padding(bottom = 32.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.aijournal_process_log_title), style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()

    for (section in sections) {
      val type = section["type"] ?: continue
      val content = section["content"] ?: continue

      when (type) {
        "system" -> ProcessSection(
          icon = Icons.Filled.Terminal,
          label = stringResource(R.string.aijournal_log_system_prompt),
          content = content,
          containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          maxLines = 4,
        )
        "input" -> ProcessSection(
          icon = Icons.Filled.SmartToy,
          label = stringResource(R.string.aijournal_log_user_input),
          content = content,
          containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        )
        "tool_call" -> ProcessSection(
          icon = Icons.Filled.Search,
          label = stringResource(R.string.aijournal_log_tool_call),
          content = content,
          containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        )
        "tool_result" -> ProcessSection(
          icon = null,
          label = stringResource(R.string.aijournal_log_tool_result),
          content = content,
          containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        )
        "thinking" -> ProcessSection(
          icon = Icons.Filled.Psychology,
          label = stringResource(R.string.aijournal_log_thinking),
          content = content,
          containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        )
        "response" -> ProcessSection(
          icon = Icons.Filled.SmartToy,
          label = stringResource(R.string.aijournal_log_response),
          content = content,
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ProcessSection(
  icon: androidx.compose.ui.graphics.vector.ImageVector?,
  label: String,
  content: String,
  containerColor: androidx.compose.ui.graphics.Color,
  maxLines: Int = Int.MAX_VALUE,
) {
  var expanded by remember { mutableStateOf(false) }
  val isTruncated = maxLines < Int.MAX_VALUE && !expanded

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(containerColor)
      .then(
        if (isTruncated) Modifier.combinedClickable(
          onClick = { expanded = true },
          onLongClick = {},
        ) else Modifier
      )
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = content,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = if (expanded) Int.MAX_VALUE else maxLines,
    )
    if (isTruncated) {
      Text(
        text = stringResource(R.string.aijournal_tap_to_expand),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

// --- Audio recording helpers (same logic as AudioRecorderPanel) ---

@SuppressLint("MissingPermission")
private suspend fun startRecording(
  context: android.content.Context,
  audioRecordState: MutableState<AudioRecord?>,
  audioStream: ByteArrayOutputStream,
  elapsedMs: MutableLongState,
  onAmplitudeChanged: (Int) -> Unit,
  onMaxDurationReached: () -> Unit,
) {
  val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
  audioRecordState.value?.release()
  val recorder = AudioRecord(
    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize,
  )
  audioRecordState.value = recorder
  val buffer = ByteArray(minBufferSize)

  coroutineScope {
    launch(Dispatchers.IO) {
      recorder.startRecording()
      val startMs = System.currentTimeMillis()
      elapsedMs.longValue = 0L
      while (audioRecordState.value?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        val bytesRead = recorder.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
          onAmplitudeChanged(calculatePeakAmplitude(buffer = buffer, bytesRead = bytesRead))
          audioStream.write(buffer, 0, bytesRead)
        }
        elapsedMs.longValue = System.currentTimeMillis() - startMs
        if (elapsedMs.longValue >= MAX_AUDIO_CLIP_DURATION_SEC * 1000) {
          onMaxDurationReached()
          break
        }
      }
    }
  }
}

private fun stopRecording(
  audioRecordState: MutableState<AudioRecord?>,
  audioStream: ByteArrayOutputStream,
): ByteArray {
  val recorder = audioRecordState.value
  if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
    recorder.stop()
  }
  recorder?.release()
  audioRecordState.value = null
  val bytes = audioStream.toByteArray()
  audioStream.reset()
  return bytes
}
