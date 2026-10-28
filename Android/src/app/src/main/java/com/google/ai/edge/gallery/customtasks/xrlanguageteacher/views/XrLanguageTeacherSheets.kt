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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayNameResId
import com.google.ai.edge.gallery.proto.Language

/** Returns the Material icon representing the given [CharacterPersona]. */
fun CharacterPersona.iconVector(): ImageVector {
  return when (this) {
    CharacterPersona.BUDDY -> Icons.Default.Face
    CharacterPersona.NERDY -> Icons.Default.School
    CharacterPersona.SECRET_AGENT -> Icons.Default.Fingerprint
    CharacterPersona.WIZARD -> Icons.Default.AutoFixHigh
  }
}

/**
 * Remembers the asynchronously loaded [ImageBitmap] thumbnail for [word] and [learnLanguage] via
 * [loadVocabImageBitmap].
 */
@Composable
fun rememberVocabThumbnailBitmap(
  word: String,
  learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap?,
): ImageBitmap? {
  var bitmap by remember(word, learnLanguage) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(word, learnLanguage, loadVocabImageBitmap) {
    bitmap = loadVocabImageBitmap(word, learnLanguage)
  }
  return bitmap
}

/**
 * Renders an [AnnotatedString] where `<target>` annotations trigger [onTargetPhraseClick], avoiding
 * deprecated `ClickableText`.
 */
@Composable
fun AnnotatedTargetClickableText(
  text: AnnotatedString,
  style: TextStyle,
  onTargetPhraseClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
  var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
  Text(
    text = text,
    style = style,
    onTextLayout = { result ->
      layoutResult = result
      onTextLayout?.invoke(result)
    },
    modifier =
      modifier.pointerInput(text) {
        detectTapGestures { tapOffset ->
          val layout = layoutResult ?: return@detectTapGestures
          if (text.isEmpty()) return@detectTapGestures
          val charOffset = layout.getOffsetForPosition(tapOffset)
          val safeOffset = charOffset.coerceIn(0, text.length - 1)
          val targetHit =
            text
              .getStringAnnotations(
                tag = XrLanguageTeacherDialogueFormatter.TTS_TARGET_ANNOTATION_TAG,
                start = safeOffset,
                end = safeOffset + 1,
              )
              .firstOrNull()
          if (targetHit != null) {
            onTargetPhraseClick(targetHit.item)
          }
        }
      },
  )
}

/**
 * Dialog allowing the user to customize or reset the system prompt template for a
 * [CharacterPersona]. "Reset to default" only restores the default text in the editor; nothing is
 * passed to [onSavePrompt] until the user taps "Save".
 */
@Suppress("DEPRECATION")
@Composable
fun PersonaPromptEditorDialog(
  persona: CharacterPersona,
  initialPromptTemplate: String,
  onSavePrompt: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var promptText by
    remember(persona, initialPromptTemplate) { mutableStateOf(initialPromptTemplate) }
  val personaName = stringResource(persona.displayNameResId())

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.xrteacher_prompt_editor_title, personaName),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = stringResource(R.string.xrteacher_prompt_editor_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
          value = promptText,
          onValueChange = { promptText = it },
          modifier = Modifier.fillMaxWidth().height(200.dp),
          textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { promptText = persona.defaultPromptTemplate }) {
          Text(stringResource(R.string.xrteacher_reset_default))
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSavePrompt(promptText)
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.xrteacher_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.xrteacher_cancel)) }
    },
  )
}
