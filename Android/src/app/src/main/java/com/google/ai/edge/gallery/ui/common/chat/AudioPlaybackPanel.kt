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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_DURATION_SEC
import com.google.ai.edge.gallery.ui.theme.customColors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGAudioPlaybackPanel"
private const val BAR_SPACE = 2
private const val BAR_WIDTH = 2
private const val MIN_BAR_COUNT = 16
private const val MAX_BAR_COUNT = 48

/**
 * A Composable that displays an audio playback panel, including play/stop controls, a waveform
 * visualization, and the duration of the audio clip.
 */
@Composable
fun AudioPlaybackPanel(
  audioData: ByteArray,
  sampleRate: Int,
  isRecording: Boolean,
  modifier: Modifier = Modifier,
  onDarkBg: Boolean = false,
) {
  val coroutineScope = rememberCoroutineScope()
  var isPlaying by remember { mutableStateOf(false) }
  val audioTrackState = remember { mutableStateOf<AudioTrack?>(null) }
  val durationInSeconds =
    remember(audioData) {
      // PCM 16-bit
      val bytesPerSample = 2
      val bytesPerFrame = bytesPerSample * 1 // mono
      val totalFrames = audioData.size.toDouble() / bytesPerFrame
      totalFrames / sampleRate
    }
  val barCount =
    remember(durationInSeconds) {
      val f = durationInSeconds / MAX_AUDIO_CLIP_DURATION_SEC
      ((MAX_BAR_COUNT - MIN_BAR_COUNT) * f + MIN_BAR_COUNT).toInt()
    }
  val amplitudeLevels =
    remember(audioData) { generateAmplitudeLevels(audioData = audioData, barCount = barCount) }
  var playbackProgress by remember { mutableFloatStateOf(0f) }

  // Reset when a new recording is started.
  LaunchedEffect(isRecording) {
    if (isRecording) {
      val audioTrack = audioTrackState.value
      audioTrack?.stop()
      isPlaying = false
      playbackProgress = 0f
    }
  }

  // Cleanup on Composable Disposal.
  DisposableEffect(Unit) {
    onDispose {
      val audioTrack = audioTrackState.value
      audioTrack?.stop()
      audioTrack?.release()
    }
  }

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    // Button to play/stop the clip.
    IconButton(
      onClick = {
        coroutineScope.launch {
          if (!isPlaying) {
            isPlaying = true
            playAudio(
              audioTrackState = audioTrackState,
              audioData = audioData,
              sampleRate = sampleRate,
              onProgress = { playbackProgress = it },
              onCompletion = {
                playbackProgress = 0f
                isPlaying = false
              },
            )
          } else {
            stopPlayAudio(audioTrackState = audioTrackState)
            playbackProgress = 0f
            isPlaying = false
          }
        }
      }
    ) {
      Icon(
        if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
        contentDescription =
          stringResource(
            if (isPlaying) R.string.cd_stop_playback_icon else R.string.cd_play_audio_icon
          ),
        tint = if (onDarkBg) Color.White else MaterialTheme.colorScheme.primary,
      )
    }

    // Visualization
    AmplitudeBarGraph(
      amplitudeLevels = amplitudeLevels,
      progress = playbackProgress,
      modifier =
        Modifier.width((barCount * BAR_WIDTH + (barCount - 1) * BAR_SPACE).dp).height(24.dp),
      onDarkBg = onDarkBg,
    )

    // Duration
    Text(
      "${"%.1f".format(durationInSeconds)}s",
      style = MaterialTheme.typography.labelLarge,
      color = if (onDarkBg) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 12.dp),
    )
  }
}

@Composable
private fun AmplitudeBarGraph(
  amplitudeLevels: List<Float>,
  progress: Float,
  modifier: Modifier = Modifier,
  onDarkBg: Boolean = false,
) {
  val barColor = MaterialTheme.customColors.waveFormBgColor
  val progressColor = if (onDarkBg) Color.White else MaterialTheme.colorScheme.primary

  Canvas(modifier = modifier) {
    val barCount = amplitudeLevels.size
    val barWidth = (size.width - BAR_SPACE.dp.toPx() * (barCount - 1)) / barCount
    val cornerRadius = CornerRadius(x = barWidth, y = barWidth)

    // Use drawIntoCanvas for advanced blend mode operations
    drawIntoCanvas { canvas ->

      // 1. Save the current state of the canvas onto a temporary, offscreen layer
      canvas.saveLayer(size.toRect(), androidx.compose.ui.graphics.Paint())

      // 2. Draw the bars in grey.
      amplitudeLevels.forEachIndexed { index, level ->
        val barHeight = (level * size.height).coerceAtLeast(1.5f)
        val left = index * (barWidth + BAR_SPACE.dp.toPx())
        drawRoundRect(
          color = barColor,
          topLeft = Offset(x = left, y = size.height / 2 - barHeight / 2),
          size = Size(barWidth, barHeight),
          cornerRadius = cornerRadius,
        )
      }

      // 3. Draw the progress rectangle using BlendMode.SrcIn to only draw where the bars already
      // exists.
      val progressWidth = size.width * progress
      drawRect(
        color = progressColor,
        topLeft = Offset.Zero,
        size = Size(progressWidth, size.height),
        blendMode = BlendMode.SrcIn,
      )

      // 4. Restore the layer, merging it onto the main canvas
      canvas.restore()
    }
  }
}

private suspend fun playAudio(
  audioTrackState: MutableState<AudioTrack?>,
  audioData: ByteArray,
  sampleRate: Int,
  onProgress: (Float) -> Unit,
  onCompletion: () -> Unit,
) {
  Log.d(TAG, "Start playing audio...")

  try {
    withContext(Dispatchers.IO) {
      var lastProgressUpdateMs = 0L
      audioTrackState.value?.release()
      val audioTrack =
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .build()
          )
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(sampleRate)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build()
          )
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(audioData.size)
          .build()

      val bytesPerFrame = 2 // For PCM 16-bit Mono
      val totalFrames = audioData.size / bytesPerFrame

      audioTrackState.value = audioTrack
      audioTrack.write(audioData, 0, audioData.size)
      audioTrack.play()

      // Coroutine to monitor progress
      while (isActive && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
        val currentFrames = audioTrack.playbackHeadPosition
        if (currentFrames >= totalFrames) {
          break // Exit loop when playback is done
        }
        val progress = currentFrames.toFloat() / totalFrames
        val curMs = System.currentTimeMillis()
        if (curMs - lastProgressUpdateMs > 30) {
          onProgress(progress)
          lastProgressUpdateMs = curMs
        }
      }

      if (isActive) {
        audioTrackState.value?.stop()
      }
    }
  } catch (e: Exception) {
    // Ignore
  } finally {
    onProgress(1f)
    onCompletion()
  }
}

private fun stopPlayAudio(audioTrackState: MutableState<AudioTrack?>) {
  Log.d(TAG, "Stopping playing audio...")

  val audioTrack = audioTrackState.value
  audioTrack?.stop()
  audioTrack?.release()
  audioTrackState.value = null
}

/**
 * Processes a raw PCM 16-bit audio byte array to generate a list of normalized amplitude levels for
 * visualization.
 */
private fun generateAmplitudeLevels(audioData: ByteArray, barCount: Int): List<Float> {
  if (audioData.isEmpty()) {
    return List(barCount) { 0f }
  }

  // 1. Parse bytes into 16-bit short samples (PCM 16-bit)
  val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
  val samples = ShortArray(shortBuffer.remaining())
  shortBuffer.get(samples)

  if (samples.isEmpty()) {
    return List(barCount) { 0f }
  }

  // 2. Determine the size of each chunk
  val chunkSize = samples.size / barCount
  val amplitudeLevels = mutableListOf<Float>()

  // 3. Get the max value for each chunk
  for (i in 0 until barCount) {
    val chunkStart = i * chunkSize
    val chunkEnd = (chunkStart + chunkSize).coerceAtMost(samples.size)

    var maxAmplitudeInChunk = 0.0

    for (j in chunkStart until chunkEnd) {
      val sampleAbs = kotlin.math.abs(samples[j].toDouble())
      if (sampleAbs > maxAmplitudeInChunk) {
        maxAmplitudeInChunk = sampleAbs
      }
    }

    // 4. Normalize the value (0 to 1)
    // Short.MAX_VALUE is 32767.0, a good reference for max amplitude
    val normalizedRms = (maxAmplitudeInChunk / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    amplitudeLevels.add(normalizedRms)
  }

  // Normalize the resulting levels so that the max value becomes 0.9.
  val maxVal = amplitudeLevels.max()
  if (maxVal == 0f) {
    return amplitudeLevels
  }
  val scaleFactor = 0.9f / maxVal
  return amplitudeLevels.map { it * scaleFactor }
}
