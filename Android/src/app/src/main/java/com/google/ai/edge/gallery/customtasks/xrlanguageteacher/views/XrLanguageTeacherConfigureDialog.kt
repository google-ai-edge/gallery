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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageRole
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.rememberTeacherPreferencesDraft
import com.google.ai.edge.gallery.proto.Language

/**
 * Dialog opened from the camera view settings button to change the languages and teacher persona.
 * Edits, including persona prompt edits, are kept in a draft: "Cancel" discards them and "Apply"
 * passes them to [onApply].
 *
 * [languagePacks] and [onDownloadLanguagePack] drive the language pack download controls of
 * [TeacherPreferencesForm].
 */
@Composable
fun ConfigureTeacherPreferencesDialog(
  initialPreferences: TeacherPreferencesDraft,
  availableLanguages: List<Language>,
  availableNativeLanguages: List<Language> = availableLanguages,
  getPersonaPromptTemplate: (CharacterPersona) -> String,
  onPlayPersonaPreview: (CharacterPersona, Language) -> Unit,
  onApply: (TeacherPreferencesDraft) -> Unit,
  onDismiss: () -> Unit,
  personaPreviewState: PersonaPreviewState = PersonaPreviewState.Idle,
  isPersonaPreviewAvailable: Boolean = true,
  onStopPersonaPreview: () -> Unit = {},
  languagePacks: TeacherLanguagePackUiState = TeacherLanguagePackUiState(),
  onDownloadLanguagePack: (TeacherLanguageRole, Language) -> Unit = { _, _ -> },
) {
  var draft by rememberTeacherPreferencesDraft(initialPreferences)
  DisposableEffect(Unit) { onDispose { onStopPersonaPreview() } }

  Dialog(
    onDismissRequest = {
      onStopPersonaPreview()
      onDismiss()
    }
  ) {
    Card(
      shape = RoundedCornerShape(28.dp),
      modifier = Modifier.fillMaxWidth(),
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
          DialogTitle(text = stringResource(R.string.xrteacher_configure_preferences_title))
          Spacer(modifier = Modifier.height(20.dp))
          TeacherPreferencesForm(
            draft = draft,
            availableLanguages = availableLanguages,
            availableNativeLanguages = availableNativeLanguages,
            onDraftChange = { draft = it },
            getPersonaPromptTemplate = getPersonaPromptTemplate,
            onPlayPersonaPreview = onPlayPersonaPreview,
            personaPreviewState = personaPreviewState,
            isPersonaPreviewAvailable = isPersonaPreviewAvailable,
            languagePacks = languagePacks,
            onDownloadLanguagePack = onDownloadLanguagePack,
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = {
              onStopPersonaPreview()
              onDismiss()
            }
          ) {
            Text(text = stringResource(R.string.xrteacher_cancel), fontWeight = FontWeight.Medium)
          }
          Button(
            onClick = {
              onStopPersonaPreview()
              onApply(draft)
            },
            shape = RoundedCornerShape(24.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.xrteacher_apply), fontWeight = FontWeight.Medium)
          }
        }
      }
    }
  }
}
