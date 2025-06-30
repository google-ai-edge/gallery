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

package com.google.ai.edge.gallery.ui.common.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_DURATION_SEC
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.ui.theme.customColors
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AGAudioRecorderPanel"

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

/**
 * A Composable that provides an audio recording panel. It allows users to record audio clips,
 * displays the recording duration and a live amplitude visualization, and provides options to play
 * back the recorded clip or send it.
 */
@Composable
fun AudioRecorderPanel(onSendAudioClip: (ByteArray) -> Unit) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var isRecording by remember { mutableStateOf(false) }
  val elapsedMs = remember { mutableLongStateOf(0L) }
  val audioRecordState = remember { mutableStateOf<AudioRecord?>(null) }
  val audioStream = remember { ByteArrayOutputStream() }
  val recordedBytes = remember { mutableStateOf<ByteArray?>(null) }
  var currentAmplitude by remember { mutableIntStateOf(0) }

  val elapsedSeconds by remember {
    derivedStateOf { "%.1f".format(elapsedMs.value.toFloat() / 1000f) }
  }

  // Cleanup on Composable Disposal.
  DisposableEffect(Unit) { onDispose { audioRecordState.value?.release() } }

  Column(modifier = Modifier.padding(bottom = 12.dp)) {
    // Title bar.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      // Logo and state.
      Row(
        modifier = Modifier.padding(start = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          painterResource(R.drawable.logo),
          modifier = Modifier.size(20.dp),
          contentDescription = "",
          tint = Color.Unspecified,
        )
        Text(
          "Record audio clip (up to $MAX_AUDIO_CLIP_DURATION_SEC seconds)",
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }

    // Recorded clip.
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(40.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      val curRecordedBytes = recordedBytes.value
      if (curRecordedBytes == null) {
        // Info message when there is no recorded clip and the recording has not started yet.
        if (!isRecording) {
          Text(
            "Tap the record button to start",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Visualization for clip being recorded.
        else {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier =
                Modifier.size(8.dp)
                  .background(MaterialTheme.customColors.recordButtonBgColor, CircleShape)
            )
            Text("$elapsedSeconds s")
          }
        }
      }
      // Controls for recorded clip.
      else {
        Row {
          // Clip player.
          AudioPlaybackPanel(
            audioData = curRecordedBytes,
            sampleRate = SAMPLE_RATE,
            isRecording = isRecording,
          )

          // Button to send the clip
          IconButton(onClick = { onSendAudioClip(curRecordedBytes) }) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = "")
          }
        }
      }
    }

    // Buttons
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
      // Visualization of the current amplitude.
      if (isRecording) {
        // Normalize the amplitude (0-32767) to a fraction (0.0-1.0)
        // We use a power scale (exponent < 1) to make the pulse more visible for lower volumes.
        val normalizedAmplitude = (currentAmplitude.toFloat() / 32767f).pow(0.35f)
        // Define the min and max size of the circle
        val minSize = 38.dp
        val maxSize = 100.dp

        // Map the normalized amplitude to our size range
        val scale by
          remember(normalizedAmplitude) {
            derivedStateOf { (minSize + (maxSize - minSize) * normalizedAmplitude) / minSize }
          }
        Box(
          modifier =
            Modifier.size(minSize)
              .graphicsLayer(scaleX = scale, scaleY = scale, clip = false, alpha = 0.3f)
              .background(MaterialTheme.customColors.recordButtonBgColor, CircleShape)
        )
      }

      // Record/stop button.
      IconButton(
        onClick = {
          coroutineScope.launch {
            if (!isRecording) {
              isRecording = true
              recordedBytes.value = null
              startRecording(
                context = context,
                audioRecordState = audioRecordState,
                audioStream = audioStream,
                elapsedMs = elapsedMs,
                onAmplitudeChanged = { currentAmplitude = it },
                onMaxDurationReached = {
                  val curRecordedBytes =
                    stopRecording(audioRecordState = audioRecordState, audioStream = audioStream)
                  recordedBytes.value = curRecordedBytes
                  isRecording = false
                },
              )
            } else {
              val curRecordedBytes =
                stopRecording(audioRecordState = audioRecordState, audioStream = audioStream)
              recordedBytes.value = curRecordedBytes
              isRecording = false
            }
          }
        },
        modifier =
          Modifier.clip(CircleShape).background(MaterialTheme.customColors.recordButtonBgColor),
      ) {
        Icon(
          if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
          contentDescription = "",
          tint = Color.White,
        )
      }
    }
  }
}

// Permission is checked in parent composable.
@SuppressLint("MissingPermission")
private suspend fun startRecording(
  context: Context,
  audioRecordState: MutableState<AudioRecord?>,
  audioStream: ByteArrayOutputStream,
  elapsedMs: MutableLongState,
  onAmplitudeChanged: (Int) -> Unit,
  onMaxDurationReached: () -> Unit,
) {
  Log.d(TAG, "Start recording...")
  val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

  audioRecordState.value?.release()
  val recorder =
    AudioRecord(
      MediaRecorder.AudioSource.MIC,
      SAMPLE_RATE,
      CHANNEL_CONFIG,
      AUDIO_FORMAT,
      minBufferSize,
    )

  audioRecordState.value = recorder
  val buffer = ByteArray(minBufferSize)

  // The function will only return when the recording is done (when stopRecording is called).
  coroutineScope {
    launch(Dispatchers.IO) {
      recorder.startRecording()

      val startMs = System.currentTimeMillis()
      elapsedMs.value = 0L
      while (audioRecordState.value?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        val bytesRead = recorder.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
          val currentAmplitude = calculatePeakAmplitude(buffer = buffer, bytesRead = bytesRead)
          onAmplitudeChanged(currentAmplitude)
          audioStream.write(buffer, 0, bytesRead)
        }
        elapsedMs.value = System.currentTimeMillis() - startMs
        if (elapsedMs.value >= MAX_AUDIO_CLIP_DURATION_SEC * 1000) {
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
  Log.d(TAG, "Stopping recording...")

  val recorder = audioRecordState.value
  if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
    recorder.stop()
  }
  recorder?.release()
  audioRecordState.value = null

  val recordedBytes = audioStream.toByteArray()
  audioStream.reset()
  Log.d(TAG, "Stopped. Recorded ${recordedBytes.size} bytes.")

  return recordedBytes
}

private fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
  // Wrap the byte array in a ByteBuffer and set the order to little-endian
  val shortBuffer =
    ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

  var maxAmplitude = 0
  // Iterate through the short buffer to find the maximum absolute value
  while (shortBuffer.hasRemaining()) {
    val currentSample = abs(shortBuffer.get().toInt())
    if (currentSample > maxAmplitude) {
      maxAmplitude = currentSample
    }
  }
  return maxAmplitude
}
