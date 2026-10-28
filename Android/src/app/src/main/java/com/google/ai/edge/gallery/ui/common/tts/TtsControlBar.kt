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

package com.google.ai.edge.gallery.ui.common.tts

import android.icu.text.NumberFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SmallFilledTonalButton

// Matches SmallFilledTonalButton height (32.dp) so all controls align.
private val CONTROL_BUTTON_SIZE = 32.dp
private val CONTROL_ICON_SIZE = 18.dp
private val CONTROL_SPACING = 8.dp

private val CHIP_HEIGHT = 32.dp
private val CHIP_CORNER_RADIUS = 16.dp

// Horizontal padding of 8dp matches the speed chip and iOS TtsControlBarView.
private val JUMP_TO_SPOKEN_CONTENT_PADDING =
  PaddingValues(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 2.dp)

// Material 3 disabled content opacity.
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * Control bar for text-to-speech playback and navigation, backed by [controller].
 *
 * "Jump to spoken" re-enables auto-scroll, which brings the sentence being spoken back into view.
 * While the saved TTS model can't be used, a notice says that the system voice reads aloud instead.
 */
@Composable
fun TtsControlBar(controller: TtsPlaybackController, modifier: Modifier = Modifier) {
  val playbackState by controller.playbackState.collectAsStateWithLifecycle()
  val sentences by controller.sentences.collectAsStateWithLifecycle()
  val currentSentenceIndex by controller.currentSentenceIndex.collectAsStateWithLifecycle()
  val playbackSpeed by controller.playbackSpeed.collectAsStateWithLifecycle()
  val isAutoScrollEnabled by controller.isAutoScrollEnabled.collectAsStateWithLifecycle()
  val isSpeedControlSupported by controller.isSpeedControlSupported.collectAsStateWithLifecycle()
  val selectionNotice by controller.selectionNotice.collectAsStateWithLifecycle()

  TtsControlBar(
    isPlaying = playbackState == TtsPlaybackState.PLAYING,
    isSkipPrevEnabled = currentSentenceIndex > 0,
    isSkipNextEnabled = currentSentenceIndex + 1 < sentences.size,
    isJumpToSpokenEnabled = !isAutoScrollEnabled,
    playbackSpeed = if (isSpeedControlSupported) playbackSpeed else null,
    onPlayPauseClick = { controller.togglePlayPause() },
    onSkipPrevClick = { controller.skipPrev() },
    onSkipNextClick = { controller.skipNext() },
    onJumpToSpokenClick = { controller.jumpToSpoken() },
    onSpeedSelect = { controller.setSpeed(it) },
    onDismissClick = { controller.dismiss() },
    notice =
      selectionNotice?.let {
        stringResource(
          R.string.model_status_unavailable_using,
          it.modelName,
          stringResource(R.string.tts_android_system_builtin_name),
        )
      },
    modifier = modifier,
  )
}

/**
 * Control bar for text-to-speech playback and navigation.
 *
 * @param playbackSpeed the current speed multiplier, or `null` to hide the speed selector when the
 *   selected engine does not support changing speed.
 * @param notice a notice shown above the controls, for example that the saved voice can't be used
 *   and the system voice speaks instead, or `null` for none.
 */
@Composable
fun TtsControlBar(
  isPlaying: Boolean,
  isSkipPrevEnabled: Boolean,
  isSkipNextEnabled: Boolean,
  isJumpToSpokenEnabled: Boolean,
  playbackSpeed: Float?,
  onPlayPauseClick: () -> Unit,
  onSkipPrevClick: () -> Unit,
  onSkipNextClick: () -> Unit,
  onJumpToSpokenClick: () -> Unit,
  onSpeedSelect: (Float) -> Unit,
  onDismissClick: () -> Unit,
  modifier: Modifier = Modifier,
  notice: String? = null,
) {
  var showSpeedMenu by remember { mutableStateOf(false) }

  // Disabled skip buttons keep their fill so the bar stays visually stable; only the icon dims.
  val skipButtonColors =
    IconButtonDefaults.filledIconButtonColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      disabledContentColor =
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
    )

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column {
      if (notice != null) {
        Text(
          text = notice,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
      }
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 1. Play / Pause
        FilledTonalIconButton(
          onClick = onPlayPauseClick,
          modifier = Modifier.size(CONTROL_BUTTON_SIZE),
        ) {
          Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription =
              stringResource(if (isPlaying) R.string.tts_pause else R.string.tts_play),
            modifier = Modifier.size(CONTROL_ICON_SIZE),
          )
        }

        // 2. Prev
        FilledIconButton(
          onClick = onSkipPrevClick,
          enabled = isSkipPrevEnabled,
          modifier = Modifier.size(CONTROL_BUTTON_SIZE),
          colors = skipButtonColors,
        ) {
          Icon(
            imageVector = Icons.Default.SkipPrevious,
            contentDescription = stringResource(R.string.tts_prev_sentence),
            modifier = Modifier.size(CONTROL_ICON_SIZE),
          )
        }

        // 3. Next
        FilledIconButton(
          onClick = onSkipNextClick,
          enabled = isSkipNextEnabled,
          modifier = Modifier.size(CONTROL_BUTTON_SIZE),
          colors = skipButtonColors,
        ) {
          Icon(
            imageVector = Icons.Default.SkipNext,
            contentDescription = stringResource(R.string.tts_next_sentence),
            modifier = Modifier.size(CONTROL_ICON_SIZE),
          )
        }

        // 4. Jump to spoken (Modifier.weight(1f, fill = false) gracefully truncates on large fonts)
        SmallFilledTonalButton(
          onClick = onJumpToSpokenClick,
          label = stringResource(R.string.tts_jump_to_spoken),
          enabled = isJumpToSpokenEnabled,
          textStyle = MaterialTheme.typography.labelMedium,
          contentPadding = JUMP_TO_SPOKEN_CONTENT_PADDING,
          modifier = Modifier.weight(1f, fill = false),
        )

        // 5. Playback Speed Menu
        if (playbackSpeed != null) {
          val speedContentDescription = stringResource(R.string.tts_playback_speed)
          val formattedCurrentSpeed = formatSpeed(playbackSpeed)
          Box {
            Box(
              modifier =
                Modifier.height(CHIP_HEIGHT)
                  .clip(RoundedCornerShape(CHIP_CORNER_RADIUS))
                  .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                  .semantics {
                    contentDescription = speedContentDescription
                    stateDescription = formattedCurrentSpeed
                  }
                  .clickable(role = Role.Button) { showSpeedMenu = true }
                  .padding(horizontal = 8.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = formattedCurrentSpeed,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
              )
            }

            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
              SpeedMenuItems(
                currentSpeed = playbackSpeed,
                onSpeedSelect = { speed ->
                  onSpeedSelect(speed)
                  showSpeedMenu = false
                },
              )
            }
          }
        }

        // 6. Dismiss (✕)
        FilledIconButton(
          onClick = onDismissClick,
          modifier = Modifier.size(CONTROL_BUTTON_SIZE),
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = MaterialTheme.colorScheme.onSurface,
              contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.tts_dismiss),
            modifier = Modifier.size(CONTROL_ICON_SIZE),
          )
        }
      }
    }
  }
}

@Composable
private fun SpeedMenuItems(currentSpeed: Float, onSpeedSelect: (Float) -> Unit) {
  for (speed in TtsPlaybackController.SPEED_OPTIONS) {
    val isSelected = TtsPlaybackController.isSameSpeed(currentSpeed, speed)
    DropdownMenuItem(
      modifier = Modifier.semantics { selected = isSelected },
      text = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(formatSpeed(speed))
          if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      },
      onClick = { onSpeedSelect(speed) },
    )
  }
}

@Composable
private fun formatSpeed(speed: Float): String {
  val locale = LocalConfiguration.current.locales[0]
  val number =
    remember(speed, locale) {
      NumberFormat.getNumberInstance(locale)
        .apply {
          minimumFractionDigits = 1
          maximumFractionDigits = 2
        }
        .format(speed.toDouble())
    }
  return stringResource(R.string.tts_speed_value, number)
}
