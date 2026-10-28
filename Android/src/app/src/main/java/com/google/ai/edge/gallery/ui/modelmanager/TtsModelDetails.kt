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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TtsVoice
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsVoicePreviewPhrases
import com.google.ai.edge.gallery.ui.common.InfoTooltipIconButton
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import java.util.Locale

private const val TAG = "AGTtsModelDetails"

/** Tabs displayed in [GlobalModelManager] when supplementary models are enabled. */
internal enum class ModelManagerTab {
  MAIN,
  SUPPLEMENTARY,
}

/** Callbacks of the Supplementary tab's filter row and model cards. */
internal class SupplementaryModelsActions(
  val onSelectFilter: (SupplementaryModelFilter) -> Unit = {},
  val onRetry: (Model) -> Unit = {},
  val onDownloadModel: (Model) -> Unit = {},
  val onSetDefault: (model: Model, isDefault: Boolean) -> Unit = { _, _ -> },
  val onSelectLanguage: (model: Model, languageCode: String) -> Unit = { _, _ -> },
  val onLanguagesExpanded: (Model) -> Unit = {},
  val onDownloadLanguage: (model: Model, languageCode: String) -> Unit = { _, _ -> },
  val onRetryLanguage: (model: Model, languageCode: String) -> Unit = { _, _ -> },
  val onSelectVoice: (voiceId: String) -> Unit = {},
  val onToggleVoicePreview:
    (model: Model, voiceId: String, previewText: String, languageCode: String) -> Unit =
    { _, _, _, _ ->
    },
  val onStopVoicePreview: () -> Unit = {},
)

/**
 * Returns the Supplementary tab's callbacks: availability, filter, default, and language choices go
 * to [supplementaryModelsViewModel]; downloads and voice previews still go to
 * [modelManagerViewModel], which owns them for the rest of the app.
 */
@Composable
internal fun rememberSupplementaryModelsActions(
  supplementaryModelsViewModel: SupplementaryModelsViewModel?,
  modelManagerViewModel: ModelManagerViewModel,
): SupplementaryModelsActions =
  remember(supplementaryModelsViewModel, modelManagerViewModel) {
    SupplementaryModelsActions(
      onSelectFilter = { filter -> supplementaryModelsViewModel?.selectFilter(filter) },
      onRetry = { model -> supplementaryModelsViewModel?.retry(model) },
      onDownloadModel = { model ->
        modelManagerViewModel.downloadModel(task = null, model = model)
      },
      onSetDefault = { model, isDefault ->
        modelManagerViewModel.stopVoicePreview()
        supplementaryModelsViewModel?.setDefault(model, isDefault)
      },
      onSelectLanguage = { model, languageCode ->
        supplementaryModelsViewModel?.selectLanguage(model, languageCode)
      },
      onLanguagesExpanded = { model -> supplementaryModelsViewModel?.onLanguagesExpanded(model) },
      onDownloadLanguage = { model, languageCode ->
        modelManagerViewModel.downloadLanguagePack(model, languageCode)
      },
      onRetryLanguage = { model, languageCode ->
        supplementaryModelsViewModel?.retryLanguage(model, languageCode)
      },
      onSelectVoice = { voiceId ->
        modelManagerViewModel.selectTtsVoice(voiceId, playPreview = false)
      },
      onToggleVoicePreview = { model, voiceId, previewText, languageCode ->
        modelManagerViewModel.toggleVoicePreview(model, voiceId, previewText, languageCode)
      },
      onStopVoicePreview = modelManagerViewModel::stopVoicePreview,
    )
  }

/**
 * Populates the Supplementary tab inside [GlobalModelManager]: the modality filter row, then one
 * card per model in [uiState], or a "No models" line when no model matches the filter.
 */
internal fun LazyListScope.supplementaryModelItems(
  uiState: SupplementaryModelsUiState,
  modelItemExpandedStates: MutableMap<String, Boolean>,
  viewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel?,
  actions: SupplementaryModelsActions,
) {
  item(key = "supplementary_filter_row") {
    SupplementaryFilterRow(selectedFilter = uiState.filter, onSelectFilter = actions.onSelectFilter)
  }
  if (uiState.cards.isEmpty()) {
    item(key = "supplementary_empty") { SupplementaryEmptyFilterMessage() }
  }
  items(uiState.cards, key = { it.model.name }) { card ->
    val model = card.model
    val expanded = modelItemExpandedStates.getOrDefault(model.name, true)
    val statusLine = card.statusLine
    ModelItem(
      model = model,
      task = null,
      modelManagerViewModel = viewModel,
      onModelClicked = {},
      onBenchmarkClicked = {},
      expanded = expanded,
      isBenchmarkSupported = false,
      showBenchmarkActionButton = false,
      canShowTryIt = false,
      onExpanded = { modelItemExpandedStates[model.name] = it },
      tosViewModel = tosViewModel,
      showBestOverallBadge = card.isBestOverall,
      usesDownloadStatus = card.usesTopLevelDownload,
      statusContent = statusLine?.let { line -> { SupplementaryStatusLineText(line) } },
      extraContent = {
        val voicePreviewUiState by viewModel.voicePreviewUiState.collectAsStateWithLifecycle()
        SupplementaryModelDetails(
          card = card,
          voicePreviewUiState = voicePreviewUiState,
          actions = actions,
        )
      },
    )
  }
}

/** The All / TTS / ASR filter chips at the top of the Supplementary tab. */
@Composable
internal fun SupplementaryFilterRow(
  selectedFilter: SupplementaryModelFilter,
  onSelectFilter: (SupplementaryModelFilter) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().semantics { isTraversalGroup = true },
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (filter in SupplementaryModelFilter.entries) {
      FilterChip(
        selected = filter == selectedFilter,
        onClick = { onSelectFilter(filter) },
        label = { Text(stringResource(filter.labelRes())) },
      )
    }
  }
}

@StringRes
private fun SupplementaryModelFilter.labelRes(): Int =
  when (this) {
    SupplementaryModelFilter.ALL -> R.string.all
    SupplementaryModelFilter.TTS -> R.string.model_tag_tts
    SupplementaryModelFilter.ASR -> R.string.model_tag_asr
  }

/** One line shown instead of model cards when no supplementary model matches the filter. */
@Composable
internal fun SupplementaryEmptyFilterMessage() {
  Text(
    text = stringResource(R.string.supplementary_filter_empty),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
  )
}

/** The status line under a supplementary model's name. */
@Composable
internal fun SupplementaryStatusLineText(line: SupplementaryStatusLine) {
  val text =
    when (line) {
      SupplementaryStatusLine.Checking -> stringResource(R.string.supplementary_status_checking)
      is SupplementaryStatusLine.Message -> stringResource(line.textRes)
      is SupplementaryStatusLine.Downloading -> downloadingText(line)
      is SupplementaryStatusLine.LanguagesInstalled ->
        pluralStringResource(
          R.plurals.supplementary_status_languages_installed,
          line.total,
          line.installed,
          line.total,
        )
    }
  val isError = line is SupplementaryStatusLine.Message && line.isError
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (line is SupplementaryStatusLine.Checking || line is SupplementaryStatusLine.Downloading) {
      CircularProgressIndicator(
        modifier = Modifier.size(STATUS_SPINNER_SIZE),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun downloadingText(line: SupplementaryStatusLine.Downloading): String {
  if (line.totalBytes <= 0L) {
    return if (line.continuesInBackground) {
      stringResource(R.string.supplementary_status_downloading_background)
    } else {
      stringResource(R.string.supplementary_status_downloading)
    }
  }
  val percent = (line.receivedBytes * 100 / line.totalBytes).toInt().coerceIn(0, 100)
  return if (line.continuesInBackground) {
    stringResource(R.string.supplementary_status_downloading_background_percent, percent)
  } else {
    stringResource(R.string.model_status_downloading_percent, percent)
  }
}

/**
 * Speech-specific content of an expanded supplementary model card: the notice panel, the "Default
 * to this model" checkbox, voice profiles, and supported languages. Renders [card] only; every
 * decision about what to show is made in [SupplementaryModelsViewModel].
 */
@Composable
internal fun SupplementaryModelDetails(
  card: SupplementaryModelCardUiState,
  voicePreviewUiState: VoicePreviewUiState = VoicePreviewUiState(),
  actions: SupplementaryModelsActions = SupplementaryModelsActions(),
) {
  val model = card.model
  card.notice?.let { notice ->
    SupplementaryNoticePanel(
      notice = notice,
      onRetry = { actions.onRetry(model) },
      onDownload = { actions.onDownloadModel(model) },
    )
  }

  card.defaultToggle?.let { toggle ->
    when (toggle.usage) {
      SupplementaryDefaultUsage.READ_ALOUD ->
        DefaultEngineCheckboxRow(
          checked = toggle.isDefault,
          label = stringResource(R.string.tts_default_for_read_aloud),
          infoTooltip = stringResource(R.string.tts_default_for_read_aloud_info),
          infoContentDescription = stringResource(R.string.cd_tts_default_for_read_aloud_info),
          onCheckedChange = { checked -> actions.onSetDefault(model, checked) },
        )
      SupplementaryDefaultUsage.SPEECH_INPUT ->
        DefaultEngineCheckboxRow(
          checked = toggle.isDefault,
          label = stringResource(R.string.asr_default_for_speech_input),
          infoTooltip = stringResource(R.string.asr_default_for_speech_input_info),
          infoContentDescription = stringResource(R.string.cd_asr_default_for_speech_input_info),
          onCheckedChange = { checked -> actions.onSetDefault(model, checked) },
        )
    }
  }

  card.voices?.let { voices ->
    VoiceProfilesPanel(
      modelName = model.name,
      engineId = voices.engineId,
      voices = voices.voices,
      isActiveEngine = voices.canSelect,
      selectedVoiceId = voices.selectedVoiceId,
      voicePreviewUiState = voicePreviewUiState,
      onSelectVoice = actions.onSelectVoice,
      onToggleVoicePreview = { voiceId, previewText, languageCode ->
        actions.onToggleVoicePreview(model, voiceId, previewText, languageCode)
      },
      onStopVoicePreview = actions.onStopVoicePreview,
      speakableLanguageCodes = voices.speakableLanguageCodes,
      appliesToAllLanguages = voices.appliesToAllLanguages,
    )
  }

  card.languages?.let { languages ->
    LanguagePacksPanel(
      modelName = model.name,
      languages = languages,
      onExpanded = { actions.onLanguagesExpanded(model) },
      onDownloadLanguage = { code -> actions.onDownloadLanguage(model, code) },
      onRetryLanguage = { code -> actions.onRetryLanguage(model, code) },
      onSelectLanguage = { code -> actions.onSelectLanguage(model, code) },
    )
  }
}

/**
 * Explains why a supplementary model can't be used yet, styled like `ModelUnavailabilityPanel`,
 * with the notice's action button.
 */
@Composable
private fun SupplementaryNoticePanel(
  notice: SupplementaryCardNotice,
  onRetry: () -> Unit,
  onDownload: () -> Unit,
) {
  val context = LocalContext.current
  val action = notice.action
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(notice.messageRes),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f).padding(end = if (action != null) 12.dp else 0.dp),
    )
    if (action != null) {
      val labelRes =
        when (action) {
          is SupplementaryCardAction.OpenUrl -> action.labelRes
          SupplementaryCardAction.Retry -> R.string.retry
          SupplementaryCardAction.Download -> R.string.download
        }
      val onClick: () -> Unit = {
        when (action) {
          is SupplementaryCardAction.OpenUrl -> openUrl(context, action.url)
          SupplementaryCardAction.Retry -> onRetry()
          SupplementaryCardAction.Download -> onDownload()
        }
      }
      TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(
          text = stringResource(labelRes),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

/** Opens [url] in the app that handles it, such as the browser or the Play Store. */
private fun openUrl(context: Context, url: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
  } catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No app can open $url", e)
  }
}

/**
 * Tonal pill row whose checkbox makes this model the default engine for its modality, with an (i)
 * button that explains what the default is used for.
 *
 * The whole row except the (i) button toggles the checkbox.
 */
@Composable
private fun DefaultEngineCheckboxRow(
  checked: Boolean,
  label: String,
  infoTooltip: String,
  infoContentDescription: String,
  onCheckedChange: (checked: Boolean) -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.weight(1f)
          .heightIn(min = MIN_TOUCH_TARGET_SIZE)
          .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
          .padding(start = DEFAULT_ROW_START_PADDING, end = DEFAULT_ROW_END_PADDING),
    ) {
      // The row owns the click so the label is part of the touch target.
      Checkbox(checked = checked, onCheckedChange = null)
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = DEFAULT_ROW_LABEL_SPACING),
      )
    }
    InfoTooltipIconButton(tooltipText = infoTooltip, contentDescription = infoContentDescription)
  }
}

/**
 * A collapsible list of the voice profiles supported by a TTS model, styled like
 * [LanguagePacksPanel].
 */
@Composable
private fun VoiceProfilesPanel(
  modelName: String,
  engineId: String,
  voices: List<TtsVoice>,
  isActiveEngine: Boolean,
  selectedVoiceId: String,
  voicePreviewUiState: VoicePreviewUiState,
  onSelectVoice: (voiceId: String) -> Unit,
  onToggleVoicePreview: (voiceId: String, previewText: String, languageCode: String) -> Unit,
  onStopVoicePreview: () -> Unit,
  speakableLanguageCodes: List<String>,
  appliesToAllLanguages: Boolean = false,
) {
  var isExpanded by rememberSaveable(modelName) { mutableStateOf(false) }
  var wasExpanded by rememberSaveable(modelName) { mutableStateOf(false) }
  val currentIsExpanded by rememberUpdatedState(isExpanded)
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val uiLocale = configuration.locales[0]
  val localizedPreviewSentence = stringResource(R.string.tts_voice_preview_text)
  val englishPreviewSentence =
    remember(context, configuration) {
      resolveLocalizedVoicePreviewText(
        context = context,
        languageCode = Locale.ENGLISH.toLanguageTag(),
        configuration = configuration,
      )
    }
  val previewLanguageCode =
    voicePreviewLanguageCode(
      uiLocale = uiLocale,
      isPreviewTranslated = localizedPreviewSentence != englishPreviewSentence,
      speakableLanguageCodes = speakableLanguageCodes,
    )
  val previewSentence =
    if (previewLanguageCode != null) localizedPreviewSentence else englishPreviewSentence

  LaunchedEffect(isExpanded) {
    if (wasExpanded && !isExpanded) {
      onStopVoicePreview()
    }
    wasExpanded = isExpanded
  }

  DisposableEffect(Unit) {
    onDispose {
      if (currentIsExpanded) {
        onStopVoicePreview()
      }
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    HorizontalDivider(
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { isExpanded = !isExpanded }
          .padding(vertical = 4.dp),
    ) {
      Icon(
        imageVector =
          if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
        contentDescription =
          stringResource(if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = stringResource(R.string.tts_voice_profiles_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(LANGUAGE_LIST_PADDING)
      ) {
        for (voice in voices) {
          val isSelected = voice.id == selectedVoiceId
          val isCurrent = isSelected && isActiveEngine
          val displayLabel = voice.displayName.ifEmpty { voice.id }
          val isThisVoiceActive =
            voicePreviewUiState.activeEngineId == engineId &&
              voicePreviewUiState.activeVoiceId == voice.id
          val rowPreviewStatus =
            if (isThisVoiceActive) voicePreviewUiState.status else VoicePreviewStatus.IDLE
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                )
                .then(
                  if (isActiveEngine) {
                    Modifier.selectable(
                      selected = isSelected,
                      role = Role.RadioButton,
                      onClick = { onSelectVoice(voice.id) },
                    )
                  } else {
                    Modifier
                  }
                )
                .padding(start = LANGUAGE_ROW_START_PADDING),
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = displayLabel,
                style =
                  MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                  ),
                color =
                  if (isCurrent) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
              )
            }
            VoicePreviewButton(
              voiceDisplayName = displayLabel,
              status = rowPreviewStatus,
              onClick = {
                val targetLanguageCode =
                  if (!appliesToAllLanguages && voice.locale.isNotBlank()) {
                    voice.locale
                  } else {
                    previewLanguageCode ?: DEFAULT_TTS_LANGUAGE_CODE
                  }
                val targetPreviewText =
                  TtsVoicePreviewPhrases.previewText(targetLanguageCode, previewSentence)
                onToggleVoicePreview(voice.id, targetPreviewText, targetLanguageCode)
              },
            )
          }
        }
      }
    }
  }
}

/** Minimum height of tappable rows, matching the Material minimum touch target. */
private val MIN_TOUCH_TARGET_SIZE = 48.dp

/** Space before the checkbox in [DefaultEngineCheckboxRow]. */
private val DEFAULT_ROW_START_PADDING = 12.dp

/** Space between the label and the (i) button in [DefaultEngineCheckboxRow]. */
private val DEFAULT_ROW_END_PADDING = 8.dp

/** Space between the checkbox and its label in [DefaultEngineCheckboxRow]. */
private val DEFAULT_ROW_LABEL_SPACING = 12.dp

/** Inner padding of the voice list container in [VoiceProfilesPanel]. */
private val LANGUAGE_LIST_PADDING = 8.dp

/** Extra start inset of each voice row inside the voice list container. */
private val LANGUAGE_ROW_START_PADDING = 8.dp

/** Size of the spinner in [SupplementaryStatusLineText]. */
private val STATUS_SPINNER_SIZE = 12.dp
