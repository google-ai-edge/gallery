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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.speechEngineStatusDotColor

/**
 * Circular translucent settings button with a translate icon, shown below the bottom-right corner
 * of [TopTeacherHudBubble]. Opens the language and teacher preferences dialog.
 */
@Composable
fun TeacherSettingsButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  appearDisabled: Boolean = false,
) {
  val visuallyEnabled = enabled && !appearDisabled
  val contentTint = MaterialTheme.colorScheme.inverseOnSurface
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    color =
      MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (visuallyEnabled) 0.85f else 0.45f),
    border = BorderStroke(1.dp, contentTint.copy(alpha = if (visuallyEnabled) 0.14f else 0.07f)),
    shadowElevation = if (visuallyEnabled) 6.dp else 0.dp,
    modifier = modifier.size(44.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Default.Translate,
        contentDescription = stringResource(R.string.xrteacher_settings_button_desc),
        tint = if (visuallyEnabled) contentTint else contentTint.copy(alpha = 0.38f),
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

/**
 * A colored status dot plus the name of the speech engine currently serving:
 * - [SpeechEngineReadiness.READY]: [androidx.compose.material3.ColorScheme.primary].
 * - [SpeechEngineReadiness.WARMING]: pulsing [androidx.compose.material3.ColorScheme.tertiary].
 * - [SpeechEngineReadiness.ERROR]: [androidx.compose.material3.ColorScheme.error].
 */
@Composable
fun SpeechEngineStatusIndicator(
  label: String,
  readiness: SpeechEngineReadiness,
  @StringRes contentDescriptionRes: Int,
  modifier: Modifier = Modifier,
) {
  val baseDotColor = speechEngineStatusDotColor(readiness, MaterialTheme.colorScheme)
  val dotAlpha =
    if (readiness == SpeechEngineReadiness.WARMING) {
      val transition = rememberInfiniteTransition(label = "SpeechEngineWarmingDotTransition")
      val animatedAlpha by
        transition.animateFloat(
          initialValue = 0.35f,
          targetValue = 1.0f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 700, easing = LinearEasing),
              repeatMode = RepeatMode.Reverse,
            ),
          label = "SpeechEngineWarmingDotAlpha",
        )
      animatedAlpha
    } else {
      1.0f
    }
  val description = stringResource(contentDescriptionRes, label)
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
  ) {
    Box(modifier = Modifier.size(6.dp).background(baseDotColor.copy(alpha = dotAlpha), CircleShape))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.78f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Resolves the HUD label for [engine], wrapping it in the "warming up" or "error" phrasing when
 * applicable.
 */
@Composable
internal fun speechEngineLabel(engine: SpeechEngine, readiness: SpeechEngineReadiness): String {
  val engineName =
    when (engine) {
      SpeechEngine.MLKIT_ASR -> stringResource(R.string.xrteacher_engine_mlkit_asr)
      SpeechEngine.PARAKEET_ASR -> stringResource(R.string.xrteacher_engine_parakeet_asr)
      SpeechEngine.MLKIT_TTS -> stringResource(R.string.xrteacher_engine_mlkit_tts)
      SpeechEngine.SYSTEM_ASR -> stringResource(R.string.xrteacher_engine_asr_native)
      SpeechEngine.PLATFORM_TTS -> stringResource(R.string.xrteacher_engine_tts_native)
      SpeechEngine.KOKORO_TTS -> stringResource(R.string.xrteacher_engine_kokoro_tts)
      SpeechEngine.POCKET_TTS -> stringResource(R.string.xrteacher_engine_pocket_tts)
    }
  return when (readiness) {
    SpeechEngineReadiness.READY -> engineName
    SpeechEngineReadiness.WARMING -> stringResource(R.string.xrteacher_engine_warming, engineName)
    SpeechEngineReadiness.ERROR -> stringResource(R.string.xrteacher_engine_error, engineName)
  }
}
