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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
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

/** Shape of the language dropdown menus, matching the Saved tab's language menu. */
private val LANGUAGE_MENU_SHAPE = RoundedCornerShape(16.dp)

/** Height of a language dropdown's title row: the title's line height. */
private val LABEL_ROW_HEIGHT = 24.dp

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
 *
 * The languages offered depend on the selected speech recognition and voice models; when they share
 * no language, the native language dropdown says so. Languages only the speech recognition model
 * supports ([unsupportedNativeLanguages]) are listed greyed out with a caption explaining that the
 * current TTS model does not support them. [nativeLanguageNotice] and [learnLanguageNotice], when
 * set, are shown in a light blue note under their dropdown, for example when a saved language
 * stopped working after a model change. [learnLanguagePlaceholder] and [nativeLanguagePlaceholder],
 * when set, leave that dropdown empty until the user picks a language ([onLearnLanguagePicked],
 * [onNativeLanguagePicked]), as in the first-time setup or after a saved language stopped working.
 * When [onChangeModels] is set, a "Missing a language? Change models" row under the language
 * dropdowns calls it to open the model selection screen.
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
  onChangeModels: (() -> Unit)? = null,
  unsupportedNativeLanguages: List<Language> = emptyList(),
  nativeLanguageNotice: String? = null,
  learnLanguageNotice: String? = null,
  learnLanguagePlaceholder: String? = null,
  onLearnLanguagePicked: () -> Unit = {},
  nativeLanguagePlaceholder: String? = null,
  onNativeLanguagePicked: () -> Unit = {},
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
  val unsupportedNative =
    remember(unsupportedNativeLanguages) {
      unsupportedNativeLanguages.map { language ->
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
      helperText = stringResource(R.string.xrteacher_native_language_helper),
      selectedLanguage = draft.mainLanguage,
      languages = nativeLanguages,
      packStatusOf = { languagePacks.statusOf(TeacherLanguageRole.NATIVE, it) },
      onLanguageSelected = {
        onNativeLanguagePicked()
        onLanguagesChosen(main = it, learn = draft.learnLanguage)
      },
      onDownloadLanguagePack = { onDownloadLanguagePack(TeacherLanguageRole.NATIVE, it) },
      placeholder = nativeLanguagePlaceholder,
      infoTooltip = stringResource(R.string.xrteacher_native_language_info_tooltip),
      infoContentDescription = stringResource(R.string.cd_xrteacher_native_language_info),
      unsupportedLanguages = unsupportedNative,
      unsupportedReason = stringResource(R.string.xrteacher_language_not_supported_by_tts),
    )
    when {
      nativeLanguageNotice != null ->
        LanguageNoticeBanner(
          message = nativeLanguageNotice,
          onChangeModels = onChangeModels,
          modifier = Modifier.padding(top = 8.dp),
        )
      availableNativeLanguages.isEmpty() ->
        // The selected speech models share no language, so no native language can be picked.
        Text(
          text = stringResource(R.string.xrteacher_no_shared_native_language),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    LanguageDropdownField(
      label = stringResource(R.string.xrteacher_language_to_learn),
      helperText = stringResource(R.string.xrteacher_learn_language_helper),
      selectedLanguage = draft.learnLanguage,
      languages = learnLanguages,
      packStatusOf = { languagePacks.statusOf(TeacherLanguageRole.LEARN, it) },
      onLanguageSelected = {
        onLearnLanguagePicked()
        onLanguagesChosen(main = draft.mainLanguage, learn = it)
      },
      onDownloadLanguagePack = { onDownloadLanguagePack(TeacherLanguageRole.LEARN, it) },
      infoTooltip = stringResource(R.string.xrteacher_learn_language_info_tooltip),
      infoContentDescription = stringResource(R.string.cd_xrteacher_learn_language_info),
      placeholder = learnLanguagePlaceholder,
    )
    if (learnLanguageNotice != null) {
      LanguageNoticeBanner(
        message = learnLanguageNotice,
        onChangeModels = onChangeModels,
        modifier = Modifier.padding(top = 8.dp),
      )
    } else if (onChangeModels != null && nativeLanguageNotice == null) {
      // A note already offers to switch models, so the helper line only shows without one.
      ChangeModelsHelperLine(onChangeModels = onChangeModels)
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.xrteacher_persona_section_title),
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
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

/**
 * "Missing a language? Change models" helper line right under the native language dropdown, styled
 * like an input's helper text, because the languages offered depend on the selected speech models.
 */
@Composable
private fun ChangeModelsHelperLine(onChangeModels: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = stringResource(R.string.xrteacher_missing_language),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Tight padding keeps it close to the dropdown; the button still has a 40dp tap height.
    TextButton(
      onClick = onChangeModels,
      contentPadding = PaddingValues(horizontal = 8.dp),
      modifier = Modifier.heightIn(min = 40.dp),
    ) {
      Text(
        text = stringResource(R.string.xrteacher_change_models),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
      )
    }
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
 * can't be selected until they're installed. [unsupportedLanguages] are listed last, greyed out and
 * not selectable, with [unsupportedReason] under each.
 */
@Composable
private fun LanguageDropdownField(
  label: String,
  helperText: String?,
  selectedLanguage: Language,
  languages: List<Pair<Language, String>>,
  packStatusOf: (Language) -> LanguagePackUiStatus,
  onLanguageSelected: (Language) -> Unit,
  onDownloadLanguagePack: (Language) -> Unit,
  infoTooltip: String? = null,
  infoContentDescription: String? = null,
  unsupportedLanguages: List<Pair<Language, String>> = emptyList(),
  unsupportedReason: String? = null,
  placeholder: String? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  // The menu is as wide as the dropdown field it opens from.
  val density = LocalDensity.current
  var fieldWidth by remember { mutableStateOf(0.dp) }
  val currentName =
    placeholder
      ?: languages.firstOrNull { it.first == selectedLanguage }?.second
      ?: localeTagToDisplayName(selectedLanguage.toLocale().toLanguageTag())
  // With a placeholder, nothing is selected yet, so there's no status to show.
  val selectedStatus =
    if (placeholder != null) LanguagePackUiStatus.Installed else packStatusOf(selectedLanguage)

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = if (helperText != null) 2.dp else 8.dp),
    ) {
      // Same style as the "Teacher persona" title.
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (infoTooltip != null && infoContentDescription != null) {
        InfoTooltipIconButton(
          tooltipText = infoTooltip,
          contentDescription = infoContentDescription,
          // Keeps its 48dp touch target, but only takes the title's line height in the layout, so
          // the helper text sits close to the title.
          modifier =
            Modifier.layout { measurable, constraints ->
              val placeable = measurable.measure(constraints.copy(minHeight = 0))
              val height = LABEL_ROW_HEIGHT.roundToPx()
              layout(placeable.width, height) {
                placeable.place(0, (height - placeable.height) / 2)
              }
            },
        )
      }
    }
    if (helperText != null) {
      Text(
        text = helperText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    Box(modifier = Modifier.fillMaxWidth()) {
      Surface(
        onClick = { expanded = !expanded },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier =
          Modifier.fillMaxWidth().height(52.dp).onGloballyPositioned { coordinates ->
            fieldWidth = with(density) { coordinates.size.width.toDp() }
          },
      ) {
        Row(
          modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = currentName,
              style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
              color =
                if (placeholder != null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
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
      // Styled like the Saved tab's language menu: a light rounded container with pill-shaped
      // options, the current one filled in primaryContainer.
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        offset = DpOffset(x = 0.dp, y = 4.dp),
        shape = LANGUAGE_MENU_SHAPE,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(fieldWidth).heightIn(max = 320.dp),
      ) {
        val disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        for ((language, name) in languages) {
          val isCurrent = placeholder == null && language == selectedLanguage
          val status = packStatusOf(language)
          val isSelectable = isCurrent || status == LanguagePackUiStatus.Installed
          DropdownMenuItem(
            modifier =
              Modifier.padding(horizontal = 4.dp)
                .clip(CircleShape)
                .background(
                  if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                .semantics { selected = isCurrent },
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
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        status == LanguagePackUiStatus.Installed ->
                          MaterialTheme.colorScheme.onSurfaceVariant
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
        // Languages the selected models can't use together, listed last and greyed out with why.
        for ((_, name) in unsupportedLanguages) {
          DropdownMenuItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = {
              Column {
                Text(text = name, color = disabledContentColor)
                if (unsupportedReason != null) {
                  Text(
                    text = unsupportedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = disabledContentColor,
                  )
                }
              }
            },
            onClick = {},
            enabled = false,
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
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
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
