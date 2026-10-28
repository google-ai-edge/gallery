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

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.RotationalLoader

/** The one-line XR pipeline status shown under the XR pill. */
enum class XrPipelineStatus(@StringRes val labelRes: Int) {
  READY(R.string.xrteacher_xr_status_ready),
  LISTENING(R.string.xrteacher_xr_status_listening),
  THINKING(R.string.xrteacher_xr_status_thinking),
  SPEAKING(R.string.xrteacher_xr_status_speaking),
  /** The last ASR session failed; the next tap starts a new one. */
  ASR_FAILED(R.string.xrteacher_xr_status_asr_failed),
}

/**
 * Maps the XR pipeline flags to a single [XrPipelineStatus].
 *
 * Order matters: "Thinking" wins over TTS while waiting for the first token, because the XR filler
 * phrase can play during that time.
 */
internal fun xrPipelineStatus(
  isRecording: Boolean,
  isSubmittingToModel: Boolean,
  isRunningInference: Boolean,
  isWaitingForFirstToken: Boolean,
  isSpeakingTts: Boolean,
  lastAsrError: String = "",
): XrPipelineStatus =
  when {
    isSubmittingToModel -> XrPipelineStatus.THINKING
    isRecording -> XrPipelineStatus.LISTENING
    isRunningInference && isWaitingForFirstToken -> XrPipelineStatus.THINKING
    isSpeakingTts -> XrPipelineStatus.SPEAKING
    isRunningInference -> XrPipelineStatus.THINKING
    lastAsrError.isNotBlank() -> XrPipelineStatus.ASR_FAILED
    else -> XrPipelineStatus.READY
  }

/**
 * HUD shown on the phone screen while in XR Glasses mode.
 *
 * Displays active state indicators (Listening, Thinking, Speaking, Idle), live speech transcript
 * when the user is dictating, active teacher dialogue when speaking, and a "Switch to phone"
 * button.
 */
@Composable
fun XrGlassesModeScreen(
  onSwitchToPhone: () -> Unit,
  modifier: Modifier = Modifier,
  onTapScreen: () -> Unit = {},
  glassesCameraBitmap: Bitmap? = null,
  isRecording: Boolean = false,
  liveTranscript: String = "",
  isRunningInference: Boolean = false,
  isSpeakingTts: Boolean = false,
  teacherDialogue: String = "",
  userQuestion: String = "",
  status: XrPipelineStatus = XrPipelineStatus.READY,
) {
  val scrimColor = MaterialTheme.colorScheme.inverseSurface
  val onScrimColor = MaterialTheme.colorScheme.inverseOnSurface
  val cardContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val accentColor = MaterialTheme.colorScheme.inversePrimary
  val imageBitmap =
    remember(glassesCameraBitmap) {
      glassesCameraBitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
    }
  Box(
    modifier = modifier.fillMaxSize().background(scrimColor),
    contentAlignment = Alignment.Center,
  ) {
    // Live glasses camera background if streaming
    if (imageBitmap != null) {
      Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      // Dark semi-transparent scrim to ensure HUD icons and text remain legible
      val scrimBrush =
        remember(scrimColor) {
          Brush.verticalGradient(
            colors =
              listOf(
                scrimColor.copy(alpha = 0.65f),
                scrimColor.copy(alpha = 0.35f),
                scrimColor.copy(alpha = 0.75f),
              )
          )
        }
      Box(modifier = Modifier.fillMaxSize().background(brush = scrimBrush))
    }

    // Top status pill badge + single-line pipeline status
    Box(
      modifier = Modifier.fillMaxSize().padding(top = 48.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          shape = CircleShape,
          color = cardContainerColor.copy(alpha = 0.85f),
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_eyeglasses),
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text =
                if (glassesCameraBitmap != null) {
                  stringResource(R.string.xrteacher_xr_camera_streaming)
                } else {
                  stringResource(R.string.xrteacher_xr_connected)
                },
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        if (glassesCameraBitmap != null) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = stringResource(status.labelRes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 24.dp),
          )
        }
      }
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 32.dp).clickable(onClick = onTapScreen),
    ) {
      // Dynamic status icon
      when {
        isRecording -> {
          Box(
            modifier =
              Modifier.size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                .border(2.dp, MaterialTheme.colorScheme.error, CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier =
                Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(36.dp),
              )
            }
          }
        }
        isRunningInference -> {
          Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            RotationalLoader(size = 64.dp)
          }
        }
        isSpeakingTts -> {
          Box(
            modifier =
              Modifier.size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                .border(2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.VolumeUp,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(40.dp),
            )
          }
        }
        else -> {
          Box(
            modifier =
              Modifier.size(80.dp)
                .clip(CircleShape)
                .background(cardContainerColor)
                .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_eyeglasses),
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(40.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Status title
      val title =
        when {
          isRecording -> stringResource(R.string.xrteacher_xr_listening_title)
          isRunningInference -> stringResource(R.string.xrteacher_thinking_header)
          isSpeakingTts -> stringResource(R.string.xrteacher_xr_speaking_title)
          else -> stringResource(R.string.xrteacher_xr_running_title)
        }
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = onScrimColor,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Status subtitle
      val subtitle =
        when {
          isRecording -> stringResource(R.string.xrteacher_xr_listening_subtitle)
          isRunningInference -> stringResource(R.string.xrteacher_generating_response_subtitle)
          isSpeakingTts -> stringResource(R.string.xrteacher_xr_speaking_subtitle)
          else -> stringResource(R.string.xrteacher_xr_tap_to_speak)
        }
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = onScrimColor.copy(alpha = 0.78f),
        textAlign = TextAlign.Center,
      )

      // Live transcript bubble if recording with text
      when {
        isRecording && liveTranscript.isNotBlank() -> {
          Spacer(modifier = Modifier.height(20.dp))
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardContainerColor)
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.xrteacher_quoted_text, liveTranscript),
              style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
            )
          }
        }
        isSpeakingTts && teacherDialogue.isNotBlank() -> {
          Spacer(modifier = Modifier.height(20.dp))
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardContainerColor)
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = teacherDialogue,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        isRunningInference && userQuestion.isNotBlank() -> {
          Spacer(modifier = Modifier.height(20.dp))
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardContainerColor)
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.xrteacher_quoted_text, userQuestion),
              style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(36.dp))

      Surface(
        onClick = onSwitchToPhone,
        shape = CircleShape,
        color = cardContainerColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.height(44.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 20.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.xrteacher_switch_to_phone),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = accentColor,
          )
        }
      }
    }
  }
}
