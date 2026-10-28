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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageOptions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageRole
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayNameResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.localeTagToDisplayName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.responseExampleResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.subtitleResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.teacherResponseExamplePhrase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toLocale
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.InfoTooltipIconButton
import com.google.ai.edge.gallery.ui.modelmanager.LanguagePackStatusControl
import com.google.ai.edge.gallery.ui.modelmanager.LanguagePackUiStatus
import com.google.ai.edge.gallery.ui.modelmanager.languagePackStatusText

/** Size of the tonal action buttons next to the persona section title. */
private val PERSONA_ACTION_BUTTON_SIZE = 32.dp

/** Icon size inside the persona section's tonal action buttons. */
private val PERSONA_ACTION_ICON_SIZE = 18.dp

/**
 * Shared preferences form used by [XrLanguageTeacherOnboardingOverlay] and
 * [ConfigureTeacherPreferencesDialog]: native and learn language dropdowns, a 2x2 grid of teacher
 * persona cards, and a sample teacher reply in the chosen learn language. Next to the persona
 * section title, a speaker button plays the selected persona's voice preview in the draft native
 * language through [onPlayPersonaPreview], and an edit button opens [PersonaPromptEditorDialog] for
 * it. The speaker button is disabled until [isPersonaPreviewAvailable], unless a preview is loading
 * or playing, which the button stops.
 *
 * Prompt edits are kept in [TeacherPreferencesDraft.promptTemplateEdits], so they are discarded
 * along with the rest of the draft when the dialog is cancelled. [getPersonaPromptTemplate] returns
 * the currently stored template for personas without an edit.
 *
 * Languages whose packs aren't installed for the selected speech engines ([languagePacks]) show the
 * model manager's language pack download control and can't be selected until they're installed. The
 * control starts a download through [onDownloadLanguagePack], which also retries a failed one;
 * downloads can't be cancelled.
 */
@Composable
fun TeacherPreferencesForm(
  draft: TeacherPreferencesDraft,
  availableLanguages: List<Language>,
  onDraftChange: (TeacherPreferencesDraft) -> Unit,
  getPersonaPromptTemplate: (CharacterPersona) -> String,
  onPlayPersonaPreview: (CharacterPersona, Language) -> Unit,
  modifier: Modifier = Modifier,
  availableNativeLanguages: List<Language> = availableLanguages,
  personaPreviewState: PersonaPreviewState = PersonaPreviewState.Idle,
  isPersonaPreviewAvailable: Boolean = true,
  languagePacks: TeacherLanguagePackUiState = TeacherLanguagePackUiState(),
  onDownloadLanguagePack: (TeacherLanguageRole, Language) -> Unit = { _, _ -> },
) {
  var editingPersona by remember { mutableStateOf<CharacterPersona?>(null) }
  val languageOptions =
    remember(availableNativeLanguages, availableLanguages) {
      TeacherLanguageOptions(native = availableNativeLanguages, learn = availableLanguages)
    }
  val nativeLanguages =
    remember(availableNativeLanguages) {
      availableNativeLanguages.map { language ->
        language to localeTagToDisplayName(language.toLocale().toLanguageTag())
      }
    }
  val learnLanguages =
    remember(languageOptions, draft.mainLanguage) {
      languageOptions.learnOptionsFor(draft.mainLanguage).map { language ->
        language to localeTagToDisplayName(language.toLocale().toLanguageTag())
      }
    }
  fun onLanguagesChosen(main: Language, learn: Language) {
    val resolved = languageOptions.resolveLanguages(TeacherLanguages(main = main, learn = learn))
    onDraftChange(draft.copy(mainLanguage = resolved.main, learnLanguage = resolved.learn))
  }
  // Keeps the draft valid when the selected models, and so the options, change.
  LaunchedEffect(languageOptions) {
    val saved = TeacherLanguages(main = draft.mainLanguage, learn = draft.learnLanguage)
    val resolved = languageOptions.resolveLanguages(saved)
    if (saved != resolved) {
      onLanguagesChosen(resolved.main, resolved.learn)
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    LanguageDropdownField(
      label = stringResource(R.string.xrteacher_native_language_label),
      selectedLanguage = draft.mainLanguage,
      languages = nativeLanguages,
      packStatusOf = { languagePacks.statusOf(TeacherLanguageRole.NATIVE, it) },
      onLanguageSelected = { onLanguagesChosen(main = it, learn = draft.learnLanguage) },
      onDownloadLanguagePack = { onDownloadLanguagePack(TeacherLanguageRole.NATIVE, it) },
      infoTooltip = stringResource(R.string.xrteacher_native_language_info_tooltip),
      infoContentDescription = stringResource(R.string.cd_xrteacher_native_language_info),
    )

    Spacer(modifier = Modifier.height(16.dp))

    LanguageDropdownField(
      label = stringResource(R.string.xrteacher_language_to_learn),
      selectedLanguage = draft.learnLanguage,
      languages = learnLanguages,
      packStatusOf = { languagePacks.statusOf(TeacherLanguageRole.LEARN, it) },
      onLanguageSelected = { onLanguagesChosen(main = draft.mainLanguage, learn = it) },
      onDownloadLanguagePack = { onDownloadLanguagePack(TeacherLanguageRole.LEARN, it) },
      infoTooltip = stringResource(R.string.xrteacher_learn_language_info_tooltip),
      infoContentDescription = stringResource(R.string.cd_xrteacher_learn_language_info),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.xrteacher_persona_section_title),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      val personaName = stringResource(draft.persona.displayNameResId())
      val isPreviewLoading = personaPreviewState == PersonaPreviewState.Loading(draft.persona)
      val isPreviewPlaying = personaPreviewState == PersonaPreviewState.Playing(draft.persona)
      // Tapping while the preview loads or plays stops it.
      val stopPreviewDescription = stringResource(R.string.cd_tts_stop_preview_voice, personaName)
      FilledTonalIconButton(
        onClick = { onPlayPersonaPreview(draft.persona, draft.mainLanguage) },
        modifier = Modifier.size(PERSONA_ACTION_BUTTON_SIZE),
        enabled = isPersonaPreviewAvailable || isPreviewLoading || isPreviewPlaying,
      ) {
        when {
          isPreviewLoading -> {
            CircularProgressIndicator(
              modifier =
                Modifier.size(PERSONA_ACTION_ICON_SIZE).semantics {
                  contentDescription = stopPreviewDescription
                },
              strokeWidth = 2.dp,
            )
          }
          isPreviewPlaying -> {
            Icon(
              imageVector = Icons.Filled.Stop,
              contentDescription = stopPreviewDescription,
              modifier = Modifier.size(PERSONA_ACTION_ICON_SIZE),
            )
          }
          else -> {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.VolumeUp,
              contentDescription =
                stringResource(R.string.xrteacher_play_persona_preview_desc, personaName),
              modifier = Modifier.size(PERSONA_ACTION_ICON_SIZE),
            )
          }
        }
      }
      FilledTonalIconButton(
        onClick = { editingPersona = draft.persona },
        modifier = Modifier.size(PERSONA_ACTION_BUTTON_SIZE),
      ) {
        Icon(
          imageVector = Icons.Default.Edit,
          contentDescription = stringResource(R.string.xrteacher_edit_prompt_desc, personaName),
          modifier = Modifier.size(PERSONA_ACTION_ICON_SIZE),
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      for (rowPersonas in CharacterPersona.entries.chunked(2)) {
        Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          for (persona in rowPersonas) {
            PersonaOptionCard(
              persona = persona,
              isSelected = persona == draft.persona,
              onSelect = { onDraftChange(draft.copy(persona = persona)) },
              modifier = Modifier.weight(1f).fillMaxHeight(),
            )
          }
        }
      }
    }

    // Teacher response example is hidden for now; uncomment to re-introduce.
    // Spacer(modifier = Modifier.height(20.dp))
    //
    // TeacherResponseExample(persona = draft.persona, learnLanguage = draft.learnLanguage)
  }

  val activeEditPersona = editingPersona
  if (activeEditPersona != null) {
    PersonaPromptEditorDialog(
      persona = activeEditPersona,
      initialPromptTemplate =
        draft.promptTemplateEdits[activeEditPersona] ?: getPersonaPromptTemplate(activeEditPersona),
      onSavePrompt = { newTemplate ->
        onDraftChange(
          draft.copy(
            promptTemplateEdits = draft.promptTemplateEdits + (activeEditPersona to newTemplate)
          )
        )
      },
      onDismiss = { editingPersona = null },
    )
  }
}

@Composable
internal fun DialogTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.headlineSmall,
    color = MaterialTheme.colorScheme.onSurface,
  )
}

/**
 * Pill-shaped dropdown field with a label above it, listing [languages] by display name.
 *
 * Each language that isn't installed ([packStatusOf]) shows its status and the language pack
 * download control, both in the list and, for the selected language, in the pill. Such languages
 * can't be selected until they're installed.
 */
@Composable
private fun LanguageDropdownField(
  label: String,
  selectedLanguage: Language,
  languages: List<Pair<Language, String>>,
  packStatusOf: (Language) -> LanguagePackUiStatus,
  onLanguageSelected: (Language) -> Unit,
  onDownloadLanguagePack: (Language) -> Unit,
  infoTooltip: String? = null,
  infoContentDescription: String? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val currentName =
    languages.firstOrNull { it.first == selectedLanguage }?.second
      ?: localeTagToDisplayName(selectedLanguage.toLocale().toLanguageTag())
  val selectedStatus = packStatusOf(selectedLanguage)

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = if (infoTooltip != null) 0.dp else 8.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (infoTooltip != null && infoContentDescription != null) {
        InfoTooltipIconButton(
          tooltipText = infoTooltip,
          contentDescription = infoContentDescription,
        )
      }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
      Surface(
        onClick = { expanded = !expanded },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth().height(52.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = currentName,
              style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            LanguagePackStatusLine(status = selectedStatus)
          }
          LanguagePackStatusControl(
            status = selectedStatus,
            languageDisplayName = currentName,
            onDownload = { onDownloadLanguagePack(selectedLanguage) },
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.heightIn(max = 320.dp),
      ) {
        val disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        for ((language, name) in languages) {
          val isCurrent = language == selectedLanguage
          val status = packStatusOf(language)
          val isSelectable = isCurrent || status == LanguagePackUiStatus.Installed
          DropdownMenuItem(
            modifier =
              Modifier.background(
                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
              ),
            text = {
              Column {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = name,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                      when {
                        isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
                        status == LanguagePackUiStatus.Installed ->
                          MaterialTheme.colorScheme.onSurface
                        else -> disabledContentColor
                      },
                  )
                }
                LanguagePackStatusLine(
                  status = status,
                  isCurrent = isCurrent,
                  isSelectable = isSelectable,
                )
              }
            },
            trailingIcon = {
              LanguagePackStatusControl(
                status = status,
                languageDisplayName = name,
                onDownload = { onDownloadLanguagePack(language) },
                showInstalledBadge = true,
              )
            },
            onClick = { onLanguageSelected(language) },
            // A language that isn't installed can't be picked until its download finishes.
            enabled = isSelectable,
          )
        }
      }
    }
  }
}

/**
 * The line under a language name that explains why it can't be used yet: still being checked, not
 * installed, downloading (with progress when known), download failed, or unavailable on this
 * device. Shows nothing for an installed language.
 */
@Composable
private fun LanguagePackStatusLine(
  status: LanguagePackUiStatus,
  isCurrent: Boolean = false,
  isSelectable: Boolean = true,
) {
  val text =
    languagePackStatusText(status)
      ?: when (status) {
        LanguagePackUiStatus.Downloadable ->
          stringResource(R.string.xrteacher_language_pack_not_installed)
        is LanguagePackUiStatus.Downloading ->
          stringResource(R.string.xrteacher_language_pack_downloading)
        LanguagePackUiStatus.Unavailable ->
          stringResource(R.string.xrteacher_language_pack_unavailable)
        LanguagePackUiStatus.Checking -> stringResource(R.string.supplementary_status_checking)
        LanguagePackUiStatus.Failed,
        LanguagePackUiStatus.Installed -> null
      }
  if (text == null) return
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color =
      when {
        status == LanguagePackUiStatus.Failed -> MaterialTheme.colorScheme.error
        isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
        isSelectable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
      },
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** Selectable persona card with a centered icon, the persona name, and a short description. */
@Composable
private fun PersonaOptionCard(
  persona: CharacterPersona,
  isSelected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onSelect,
    shape = RoundedCornerShape(16.dp),
    color =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceBright,
    contentColor =
      if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
      else MaterialTheme.colorScheme.onSurface,
    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = modifier.semantics { selected = isSelected },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = persona.iconVector(),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
      Text(
        text = stringResource(persona.displayNameResId()),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = stringResource(persona.subtitleResId()),
        style = MaterialTheme.typography.bodySmall,
        color =
          if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Callout with a sample reply from [persona] that highlights the phrase in [learnLanguage]. */
@Composable
private fun TeacherResponseExample(persona: CharacterPersona, learnLanguage: Language) {
  val exampleText =
    stringResource(
      persona.responseExampleResId(),
      getLanguageName(learnLanguage),
      "<target>${teacherResponseExamplePhrase(learnLanguage)}</target>",
    )
  val highlightColor = MaterialTheme.colorScheme.primary
  val annotatedExample =
    remember(exampleText, highlightColor) {
      XrLanguageTeacherDialogueFormatter.formatAnnotatedString(
        exampleText,
        targetColor = highlightColor,
        pillBackgroundColor = Color.Transparent,
      )
    }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = stringResource(R.string.xrteacher_response_example_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = annotatedExample,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
