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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import kotlin.math.roundToInt

/** Allowed bounds for the configurable values, shared by the sliders and the initial values. */
private const val MIN_WINDOW_DURATION_SEC = 1
private const val MAX_WINDOW_DURATION_SEC = 10
private const val MIN_FRAMES_PER_WINDOW = 1
private const val MIN_OVERLAP_DURATION_SEC = 0

/**
 * A one-tap starting point for the three sliders, named after what the user is searching for.
 *
 * Applying a preset only moves the sliders; it is not a mode that stays selected, so the user is
 * free to adjust any value afterwards without leaving the preset behind.
 *
 * [includeAudio] is part of the preset because the sound track is only worth the extra indexing
 * time for speech. It is still gated on the model actually supporting audio input.
 */
private enum class ProcessingPreset(
  val labelResId: Int,
  val windowDurationSec: Int,
  val framesPerWindow: Int,
  val overlapDurationSec: Int,
  val includeAudio: Boolean,
) {
  OBJECT(
    labelResId = R.string.videomomentfinder_config_preset_object,
    windowDurationSec = 2,
    framesPerWindow = 2,
    overlapDurationSec = 0,
    includeAudio = false,
  ),
  ACTION(
    labelResId = R.string.videomomentfinder_config_preset_action,
    windowDurationSec = 4,
    framesPerWindow = 4,
    overlapDurationSec = 0,
    includeAudio = false,
  ),
  SPEECH(
    labelResId = R.string.videomomentfinder_config_preset_speech,
    windowDurationSec = 6,
    framesPerWindow = 6,
    overlapDurationSec = 0,
    includeAudio = true,
  ),
}

/**
 * Dialog for choosing how a video is split up and sampled before it is indexed for search.
 *
 * Used both to adjust an already imported video and, with [confirmLabelResId] set to the start
 * label, to configure a newly picked video before its first indexing pass.
 *
 * [confirmEnabledWhenUnchanged] separates those two uses: adjusting an existing video should only
 * offer to reindex it once something actually changed, while starting a new one must be possible
 * with the defaults untouched.
 */
@Composable
fun ProcessingConfigDialog(
  initialFramesPerWindow: Int = DEFAULT_FRAMES_PER_WINDOW,
  initialWindowDurationSec: Int = DEFAULT_WINDOW_DURATION_SEC,
  initialOverlapDurationSec: Int = DEFAULT_OVERLAP_DURATION_SEC,
  initialIncludeAudio: Boolean = DEFAULT_INCLUDE_AUDIO,
  supportsAudio: Boolean = false,
  confirmLabelResId: Int = R.string.videomomentfinder_config_apply,
  confirmEnabledWhenUnchanged: Boolean = false,
  onDismiss: () -> Unit,
  onApply:
    (
      framesPerWindow: Int, windowDurationSec: Int, overlapDurationSec: Int, includeAudio: Boolean,
    ) -> Unit,
) {
  // Resolve initial values with fallbacks to defaults if unconfigured, clamped to the same ranges
  // the sliders below enforce.
  val effectiveInitialWindowDuration =
    (if (initialWindowDurationSec > 0) initialWindowDurationSec else DEFAULT_WINDOW_DURATION_SEC)
      .coerceIn(MIN_WINDOW_DURATION_SEC, MAX_WINDOW_DURATION_SEC)
  val effectiveInitialFramesPerWindow =
    (if (initialFramesPerWindow > 0) initialFramesPerWindow else DEFAULT_FRAMES_PER_WINDOW)
      .coerceIn(MIN_FRAMES_PER_WINDOW, effectiveInitialWindowDuration)
  val effectiveInitialOverlapDuration =
    (if (initialOverlapDurationSec >= 0) {
        initialOverlapDurationSec
      } else {
        DEFAULT_OVERLAP_DURATION_SEC
      })
      .coerceIn(
        MIN_OVERLAP_DURATION_SEC,
        maxOf(MIN_OVERLAP_DURATION_SEC, effectiveInitialWindowDuration - 1),
      )
  // A model without audio support cannot honor the setting, so it starts off and stays off.
  val effectiveInitialIncludeAudio = initialIncludeAudio && supportsAudio

  // Selected slider and checkbox values.
  //
  // Deliberately not keyed on the initial values. The dialog is created fresh each time it opens,
  // so it always starts from the current project settings; keying would additionally reset the
  // user's half-finished edits whenever the project object changed underneath them, for instance
  // when an indexing pass reports progress.
  var windowDuration by remember { mutableIntStateOf(effectiveInitialWindowDuration) }
  var framesPerWindow by remember { mutableIntStateOf(effectiveInitialFramesPerWindow) }
  var overlapDuration by remember { mutableIntStateOf(effectiveInitialOverlapDuration) }
  var includeAudio by remember { mutableStateOf(effectiveInitialIncludeAudio) }

  val isModified =
    windowDuration != effectiveInitialWindowDuration ||
      framesPerWindow != effectiveInitialFramesPerWindow ||
      overlapDuration != effectiveInitialOverlapDuration ||
      includeAudio != effectiveInitialIncludeAudio

  // Upper bounds dynamically constrained by the selected chunk duration.
  val maxFramesPerChunk = windowDuration
  val maxOverlap = maxOf(0, windowDuration - 1)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.videomomentfinder_config_adjust_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = stringResource(R.string.videomomentfinder_config_adjust_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Preset buttons. These only seed the sliders below, so none of them stays selected.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (preset in ProcessingPreset.entries) {
            OutlinedButton(
              onClick = {
                logButtonClick(
                  "videomomentfinder_config_preset",
                  buttonId = preset.name.lowercase(),
                )
                windowDuration = preset.windowDurationSec
                framesPerWindow = preset.framesPerWindow
                overlapDuration = preset.overlapDurationSec
                includeAudio = preset.includeAudio && supportsAudio
              },
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
            ) {
              Text(
                text = stringResource(preset.labelResId),
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
        }

        ConfigSlider(
          labelResId = R.string.videomomentfinder_config_segment_duration,
          valueLabel =
            stringResource(R.string.videomomentfinder_config_seconds_value, windowDuration),
          value = windowDuration,
          valueRange = MIN_WINDOW_DURATION_SEC..MAX_WINDOW_DURATION_SEC,
          onValueChange = {
            windowDuration = it
            if (framesPerWindow > windowDuration) {
              framesPerWindow = windowDuration
            }
            if (overlapDuration >= windowDuration) {
              overlapDuration = (windowDuration - 1).coerceAtLeast(MIN_OVERLAP_DURATION_SEC)
            }
          },
        )

        ConfigSlider(
          labelResId = R.string.videomomentfinder_config_frames_per_segment,
          valueLabel =
            stringResource(R.string.videomomentfinder_config_number_value, framesPerWindow),
          value = framesPerWindow,
          valueRange = MIN_FRAMES_PER_WINDOW..maxOf(MIN_FRAMES_PER_WINDOW, maxFramesPerChunk),
          onValueChange = { framesPerWindow = it },
          enabled = maxFramesPerChunk > MIN_FRAMES_PER_WINDOW,
        )

        ConfigSlider(
          labelResId = R.string.videomomentfinder_config_segment_overlap_duration,
          valueLabel =
            stringResource(R.string.videomomentfinder_config_seconds_value, overlapDuration),
          value = overlapDuration,
          valueRange = MIN_OVERLAP_DURATION_SEC..maxOf(1, maxOverlap),
          onValueChange = { overlapDuration = it },
          enabled = maxOverlap > MIN_OVERLAP_DURATION_SEC,
        )

        // Shown even when the model cannot embed audio, so the capability is discoverable rather
        // than the row silently disappearing; it is disabled and unchecked in that case.
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .toggleable(
                value = includeAudio,
                enabled = supportsAudio,
                role = Role.Checkbox,
                onValueChange = { includeAudio = it },
              ),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = includeAudio, onCheckedChange = null, enabled = supportsAudio)
          Text(
            text = stringResource(R.string.videomomentfinder_config_process_sound_track),
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (supportsAudio) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          logButtonClick("videomomentfinder_config_apply") {
            putInt("frames_per_window", framesPerWindow)
            putInt("window_duration_sec", windowDuration)
            putInt("overlap_duration_sec", overlapDuration)
            putBoolean("include_audio", includeAudio)
          }
          onApply(framesPerWindow, windowDuration, overlapDuration, includeAudio)
        },
        enabled = confirmEnabledWhenUnchanged || isModified,
        contentPadding = SMALL_BUTTON_CONTENT_PADDING,
      ) {
        Text(stringResource(confirmLabelResId))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = {
          logButtonClick("videomomentfinder_config_cancel")
          onDismiss()
        },
        contentPadding = SMALL_BUTTON_CONTENT_PADDING,
      ) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

/**
 * A labelled integer slider with its current value shown at the end of the label row.
 *
 * [valueRange] is inclusive on both ends and the slider snaps to whole steps, so the value the user
 * sees is always the value the indexing pass receives.
 */
@Composable
internal fun ConfigSlider(
  labelResId: Int,
  valueLabel: String,
  value: Int,
  valueRange: IntRange,
  onValueChange: (Int) -> Unit,
  enabled: Boolean = true,
) {
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = stringResource(labelResId), style = MaterialTheme.typography.bodyMedium)
      Text(
        text = valueLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = value.toFloat().coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
      onValueChange = {
        onValueChange(it.roundToInt().coerceIn(valueRange.first, valueRange.last))
      },
      valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
      // One stop per whole value, minus the two endpoints the slider already provides.
      steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
      enabled = enabled,
    )
  }
}
