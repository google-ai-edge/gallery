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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.proto.Language

/**
 * Asks the user to confirm removing or deleting the saved [word] from their vocabulary.
 *
 * Stateless: the caller decides when to show it. [onConfirm] is called when the user taps the
 * confirm button ([confirmResId]); [onDismiss] when they tap "Cancel" or dismiss the dialog.
 */
@Composable
fun UnsaveWordConfirmationDialog(
  word: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  @StringRes titleResId: Int = R.string.xrteacher_unsave_word_dialog_title,
  @StringRes bodyResId: Int = R.string.xrteacher_unsave_word_dialog_body,
  @StringRes confirmResId: Int = R.string.xrteacher_unsave_word_confirm,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(titleResId)) },
    text = { Text(stringResource(bodyResId, word)) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirmResId)) } },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.xrteacher_cancel)) }
    },
  )
}

/**
 * Shown when the user tries to play audio or start a study session for a [language] that the
 * selected text-to-speech model does not support, offering to open the audio settings side sheet to
 * switch TTS models.
 */
@Composable
fun TtsLanguageUnsupportedDialog(
  language: Language,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenAudioSettings: (() -> Unit)? = null,
) {
  val languageName = getLanguageName(language)
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = {
      Text(stringResource(R.string.xrteacher_tts_unsupported_language_title, languageName))
    },
    text = { Text(stringResource(R.string.xrteacher_tts_unsupported_language_body, languageName)) },
    confirmButton = {
      if (onOpenAudioSettings != null) {
        TextButton(
          onClick = {
            onDismiss()
            onOpenAudioSettings()
          }
        ) {
          Text(stringResource(R.string.xrteacher_tts_unsupported_open_settings))
        }
      } else {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
      }
    },
    dismissButton =
      if (onOpenAudioSettings != null) {
        { TextButton(onClick = onDismiss) { Text(stringResource(R.string.xrteacher_cancel)) } }
      } else {
        null
      },
  )
}
