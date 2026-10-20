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

package com.google.ai.edge.gallery.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.SpeechEngineError
import com.google.ai.edge.gallery.data.SpeechEngineErrorAction
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.data.SpeechEngineKind

/**
 * Dialog shown when a speech engine couldn't start: "Couldn't start speech recognition" or
 * "Couldn't start voice", an explanation of [SpeechEngineError.cause], and the
 * [SpeechEngineError.actions] for it, the primary one on the right.
 *
 * Each action calls its callback; the caller decides whether that also dismisses the dialog. An
 * action whose callback is `null` isn't shown. With a single action, a Cancel button calls
 * [onDismiss].
 */
@Composable
fun SpeechEngineErrorDialog(
  error: SpeechEngineError,
  onDismiss: () -> Unit,
  onUpdate: () -> Unit,
  onRetry: (() -> Unit)?,
  onChooseLanguage: (() -> Unit)?,
  onChooseAnotherModel: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val actions =
    error.actions.mapNotNull { action ->
      val callback =
        when (action) {
          SpeechEngineErrorAction.UPDATE -> onUpdate
          SpeechEngineErrorAction.RETRY -> onRetry
          SpeechEngineErrorAction.CHOOSE_LANGUAGE -> onChooseLanguage
          SpeechEngineErrorAction.CHOOSE_ANOTHER_MODEL -> onChooseAnotherModel
        }
      callback?.let { action to it }
    }
  val primary = actions.firstOrNull()
  val secondary = actions.getOrNull(1)
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(speechEngineErrorTitleRes(error.kind))) },
    text = { Text(stringResource(speechEngineErrorMessageRes(error.cause))) },
    confirmButton = {
      if (primary != null) {
        TextButton(onClick = primary.second) {
          Text(stringResource(speechEngineErrorActionLabelRes(primary.first)))
        }
      }
    },
    dismissButton = {
      if (secondary != null) {
        TextButton(onClick = secondary.second) {
          Text(stringResource(speechEngineErrorActionLabelRes(secondary.first)))
        }
      } else {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
      }
    },
  )
}

/**
 * Returns a callback that opens the Play Store page of Speech Services by Google, for the
 * [SpeechEngineErrorAction.UPDATE] action of [SpeechEngineErrorDialog].
 */
@Composable
fun rememberOpenSpeechServicesStorePage(): () -> Unit {
  val uriHandler = LocalUriHandler.current
  return remember(uriHandler) {
    {
      uriHandler.openUri("https://play.google.com/store/apps/details?id=com.google.android.tts")
    }
  }
}

@StringRes
internal fun speechEngineErrorTitleRes(kind: SpeechEngineKind): Int =
  when (kind) {
    SpeechEngineKind.SPEECH_RECOGNITION -> R.string.speech_engine_error_asr_title
    SpeechEngineKind.VOICE -> R.string.speech_engine_error_tts_title
  }

@StringRes
internal fun speechEngineErrorMessageRes(cause: SpeechEngineErrorCause): Int =
  when (cause) {
    SpeechEngineErrorCause.SPEECH_SERVICES_OUTDATED -> R.string.speech_engine_error_outdated_message
    SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED -> R.string.speech_engine_error_language_message
    SpeechEngineErrorCause.NOT_SUPPORTED -> R.string.speech_engine_error_not_supported_message
    SpeechEngineErrorCause.OTHER -> R.string.speech_engine_error_other_message
  }

@StringRes
internal fun speechEngineErrorActionLabelRes(action: SpeechEngineErrorAction): Int =
  when (action) {
    SpeechEngineErrorAction.UPDATE -> R.string.speech_engine_error_update
    SpeechEngineErrorAction.RETRY -> R.string.retry
    SpeechEngineErrorAction.CHOOSE_LANGUAGE -> R.string.speech_engine_error_choose_language
    SpeechEngineErrorAction.CHOOSE_ANOTHER_MODEL ->
      R.string.speech_engine_error_choose_another_model
  }
