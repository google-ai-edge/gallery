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

package com.google.ai.edge.gallery.ui.common.asr

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val IDLE_WAVE_LEVEL = 0.05f

/**
 * Animated vertical waveform bars that visualize the live microphone level during speech
 * recognition.
 *
 * The bars keep a gentle idle motion so the user can tell the recognizer is listening even in
 * silence, and grow with [audioLevel].
 *
 * @param audioLevel Normalized microphone level in `[0, 1]`; values outside the range are clamped.
 * @param barCount Number of bars to draw. The center bars are tallest.
 * @param barColor Color of each bar. Defaults to the theme's primary color.
 */
@Composable
fun AsrWaveformBars(
  audioLevel: Float,
  modifier: Modifier = Modifier,
  barCount: Int = 21,
  barColor: Color = MaterialTheme.colorScheme.primary,
) {
  val animatedLevel by
    animateFloatAsState(
      targetValue = audioLevel.coerceIn(0f, 1f),
      animationSpec =
        spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy),
      label = "WaveformAudioLevel",
    )
  val infiniteTransition = rememberInfiniteTransition(label = "WaveformIdleMotion")
  val phase by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = (2 * PI).toFloat(),
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 900, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "WaveformPhase",
    )

  Row(
    modifier = modifier.height(36.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val centerIndex = (barCount - 1) / 2f
    val baseLevel = animatedLevel.coerceAtLeast(IDLE_WAVE_LEVEL)
    for (i in 0 until barCount) {
      val distFromCenter = abs(i - centerIndex) / centerIndex.coerceAtLeast(1f)
      val envelope = (1f - 0.65f * distFromCenter * distFromCenter).coerceIn(0.25f, 1f)
      val wave = 0.35f + 0.65f * abs(sin(phase + i * 0.65f))
      val effectiveLevel = baseLevel * envelope * wave
      val barHeight = 4.dp + (22.dp * effectiveLevel.coerceIn(0f, 1f))

      Box(modifier = Modifier.width(3.dp).height(barHeight).background(barColor, CircleShape))
    }
  }
}

/** Colors used by [AsrRecordingPill]. Create with [asrRecordingPillColors]. */
@Immutable
data class AsrRecordingPillColors(
  val containerColor: Color,
  val waveformBarColor: Color,
  val cancelButtonContainerColor: Color,
  val cancelButtonIconTint: Color,
  val stopButtonContainerColor: Color,
  val stopButtonIconTint: Color,
  val sendButtonContainerColor: Color,
  val sendButtonIconTint: Color,
)

/** Returns [AsrRecordingPillColors], defaulting to the current Material color scheme. */
@Composable
fun asrRecordingPillColors(
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  waveformBarColor: Color = MaterialTheme.colorScheme.primary,
  cancelButtonContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
  cancelButtonIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  stopButtonContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
  stopButtonIconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
  sendButtonContainerColor: Color = MaterialTheme.colorScheme.primary,
  sendButtonIconTint: Color = MaterialTheme.colorScheme.onPrimary,
): AsrRecordingPillColors =
  AsrRecordingPillColors(
    containerColor = containerColor,
    waveformBarColor = waveformBarColor,
    cancelButtonContainerColor = cancelButtonContainerColor,
    cancelButtonIconTint = cancelButtonIconTint,
    stopButtonContainerColor = stopButtonContainerColor,
    stopButtonIconTint = stopButtonIconTint,
    sendButtonContainerColor = sendButtonContainerColor,
    sendButtonIconTint = sendButtonIconTint,
  )

/**
 * Pill-shaped speech recording bar shown while speech recognition is active.
 *
 * Layout, from start to end: a cancel (X) button, the live [AsrWaveformBars], an optional stop (■)
 * button, and a send (↑) button.
 *
 * Content descriptions are passed in so that each caller can describe the actions in its own
 * context (for example, "Send question").
 *
 * @param audioLevel Normalized microphone level in `[0, 1]`.
 * @param onCancel Called when the user discards the recording.
 * @param onSend Called when the user wants to finish the recording and send the transcript.
 * @param onStop Called when the user wants to finish the recording without sending it. When `null`,
 *   the stop button is not shown.
 * @param showGlow When `true`, replaces the drop shadow with a soft ambient glow tinted with
 *   [AsrRecordingPillColors.waveformBarColor].
 */
@Composable
fun AsrRecordingPill(
  audioLevel: Float,
  cancelContentDescription: String,
  sendContentDescription: String,
  onCancel: () -> Unit,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
  onStop: (() -> Unit)? = null,
  stopContentDescription: String? = null,
  colors: AsrRecordingPillColors = asrRecordingPillColors(),
  pillHeight: Dp = 64.dp,
  buttonSize: Dp = 46.dp,
  showGlow: Boolean = false,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    if (showGlow) {
      Box(
        modifier =
          Modifier.matchParentSize()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .blur(radius = 28.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .background(color = colors.waveformBarColor.copy(alpha = 0.5f), shape = CircleShape)
      )
    }

    Surface(
      modifier = Modifier.fillMaxWidth().height(pillHeight),
      shape = CircleShape,
      color = colors.containerColor,
      tonalElevation = 4.dp,
      shadowElevation = if (showGlow) 0.dp else 6.dp,
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PillIconButton(
          icon = Icons.Rounded.Close,
          contentDescription = cancelContentDescription,
          containerColor = colors.cancelButtonContainerColor,
          iconTint = colors.cancelButtonIconTint,
          size = buttonSize,
          onClick = onCancel,
        )

        AsrWaveformBars(
          audioLevel = audioLevel,
          barColor = colors.waveformBarColor,
          modifier = Modifier.weight(1f),
        )

        if (onStop != null) {
          PillIconButton(
            icon = Icons.Rounded.Stop,
            contentDescription = stopContentDescription,
            containerColor = colors.stopButtonContainerColor,
            iconTint = colors.stopButtonIconTint,
            size = buttonSize,
            onClick = onStop,
          )
        }

        PillIconButton(
          icon = Icons.Rounded.ArrowUpward,
          contentDescription = sendContentDescription,
          containerColor = colors.sendButtonContainerColor,
          iconTint = colors.sendButtonIconTint,
          size = buttonSize,
          onClick = onSend,
        )
      }
    }
  }
}

@Composable
private fun PillIconButton(
  icon: ImageVector,
  contentDescription: String?,
  containerColor: Color,
  iconTint: Color,
  size: Dp,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = containerColor,
    modifier = Modifier.size(size),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = iconTint,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}
