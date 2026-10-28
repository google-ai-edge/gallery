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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.InfoTooltipIconButton
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import java.util.Locale

/**
 * A collapsible list of the languages supported by a speech model, styled like
 * `OptionalComponentsPanel`. Model Manager cards and the Config side sheet's Audio tab both use it.
 *
 * Rows render the states already in [languages], so expanding the list is instant; [onExpanded]
 * asks for states that aren't known yet, and those rows show their own loading indicator until they
 * arrive. Languages the device doesn't offer stay in the list, greyed out with Retry.
 *
 * @param description Optional line under the header, such as how the model handles languages.
 * @param showTopDivider Whether a divider separates the panel from the content above it.
 * @param listContainerColor Background of the expanded list; pick one that stands out from the
 *   surface the panel sits on.
 * @param selectionRowStyle Whether the panel uses the style of the Config side sheet's Audio tab:
 *   the header title and description start at the edge with the caret after the title, and rows use
 *   [LanguageSelectionRow] instead of Model Manager's style.
 * @param showRadioButtons In the selection style, whether rows have leading radio buttons, for
 *   models that listen in one language at a time.
 */
@Composable
fun LanguagePacksPanel(
  modelName: String,
  languages: SupplementaryLanguagesUiState,
  onExpanded: () -> Unit,
  onDownloadLanguage: (languageCode: String) -> Unit,
  onRetryLanguage: (languageCode: String) -> Unit,
  onSelectLanguage: (languageCode: String) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  showTopDivider: Boolean = true,
  listContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
  selectionRowStyle: Boolean = false,
  showRadioButtons: Boolean = false,
) {
  var isExpanded by rememberSaveable(modelName) { mutableStateOf(false) }
  val currentOnExpanded by rememberUpdatedState(onExpanded)

  LaunchedEffect(isExpanded, modelName) {
    if (isExpanded) {
      currentOnExpanded()
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    if (showTopDivider) {
      HorizontalDivider(
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      if (selectionRowStyle) {
        // The Audio tab's header lines up with the other settings titles: the title starts at the
        // edge, followed by the caret.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .clickable { isExpanded = !isExpanded }
              .padding(vertical = 4.dp),
        ) {
          Text(
            text = stringResource(R.string.tts_language_packs_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Icon(
            imageVector = if (isExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
            contentDescription =
              stringResource(
                if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon
              ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
      } else {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .clickable { isExpanded = !isExpanded }
              .padding(vertical = 4.dp),
        ) {
          Icon(
            imageVector =
              if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
            contentDescription =
              stringResource(
                if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon
              ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.tts_language_packs_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (languages.showInfoTooltip) {
        InfoTooltipIconButton(
          tooltipText = stringResource(R.string.supported_languages_info_tooltip),
          contentDescription = stringResource(R.string.cd_supported_languages_info),
        )
      }
    }
    if (description != null) {
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier.padding(start = if (selectionRowStyle) 0.dp else HEADER_TEXT_START_INSET),
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
            .background(listContainerColor)
            .padding(LANGUAGE_LIST_PADDING)
      ) {
        for (row in languages.rows) {
          if (selectionRowStyle) {
            LanguageSelectionRow(
              row = row,
              showRadioButton = showRadioButtons,
              onDownload = { onDownloadLanguage(row.languageCode) },
              onSelect = { onSelectLanguage(row.languageCode) },
            )
          } else {
            LanguagePackRow(
              row = row,
              onDownload = { onDownloadLanguage(row.languageCode) },
              onRetry = { onRetryLanguage(row.languageCode) },
              onSelect = { onSelectLanguage(row.languageCode) },
            )
          }
        }
      }
    }
  }
}

/**
 * One language in [LanguagePacksPanel]'s selection style: an optional leading radio button, the
 * language's name and its code or status, and a trailing badge for its status. The selected row is
 * highlighted.
 *
 * The radio button picks the language and is only enabled once the language is installed. Installed
 * languages show a green check, languages that can be downloaded show a download button, stalled
 * downloads show a retry button, and languages the device doesn't offer say "Unavailable".
 */
@Composable
private fun LanguageSelectionRow(
  row: SupplementaryLanguageRowUiState,
  showRadioButton: Boolean,
  onDownload: () -> Unit,
  onSelect: () -> Unit,
) {
  val languageCode = row.languageCode
  val status = row.status
  val displayName = remember(languageCode) { Locale.forLanguageTag(languageCode).displayName }
  val isHighlighted = showRadioButton && row.isSelected
  val isGreyedOut = status == SupplementaryLanguageStatus.NotOffered
  val contentColor =
    if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = MIN_TOUCH_TARGET_SIZE)
        .clip(RoundedCornerShape(SELECTION_ROW_CORNER_RADIUS))
        .background(
          if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        )
        .then(
          if (showRadioButton && row.isSelectable) {
            Modifier.selectable(
              selected = row.isSelected,
              role = Role.RadioButton,
              onClick = onSelect,
            )
          } else {
            Modifier
          }
        )
        .padding(start = if (showRadioButton) 0.dp else SELECTION_ROW_TEXT_START_PADDING),
  ) {
    if (showRadioButton) {
      // The row handles the click so the whole row is the touch target.
      RadioButton(
        selected = row.isSelected,
        onClick = null,
        enabled = row.isSelectable,
        modifier = Modifier.padding(horizontal = SELECTION_ROW_RADIO_PADDING),
      )
    }
    Column(modifier = Modifier.weight(1f).alpha(if (isGreyedOut) GREYED_OUT_ALPHA else 1f)) {
      Text(
        text = displayName,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = contentColor,
      )
      Text(
        // An unavailable language says so at the end of the row, so this line shows its code.
        text =
          if (isGreyedOut || status == SupplementaryLanguageStatus.CheckFailed) languageCode
          else languageStatusText(status) ?: languageCode,
        style = MaterialTheme.typography.bodySmall,
        color =
          when {
            status == SupplementaryLanguageStatus.Failed -> MaterialTheme.colorScheme.error
            isHighlighted -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }

    when (status) {
      SupplementaryLanguageStatus.Installed -> LanguagePackTrailingSlot { DownloadedBadge() }
      SupplementaryLanguageStatus.CheckFailed,
      SupplementaryLanguageStatus.NotOffered ->
        Text(
          text = stringResource(R.string.model_unavailable_status),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp),
        )
      SupplementaryLanguageStatus.Bundled,
      SupplementaryLanguageStatus.Queued -> LanguagePackTrailingSlot {}
      SupplementaryLanguageStatus.Checking ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Checking,
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
      SupplementaryLanguageStatus.Downloadable,
      SupplementaryLanguageStatus.Failed ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Downloadable,
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
      is SupplementaryLanguageStatus.Downloading ->
        LanguagePackStatusControl(
          status =
            LanguagePackUiStatus.Downloading(
              status.receivedBytes,
              status.totalBytes,
              status.stalled,
            ),
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
    }
  }
}

/**
 * One language in [LanguagePacksPanel]: its name, then its code or status, and a trailing control
 * for its status.
 */
@Composable
private fun LanguagePackRow(
  row: SupplementaryLanguageRowUiState,
  onDownload: () -> Unit,
  onRetry: () -> Unit,
  onSelect: () -> Unit,
) {
  val languageCode = row.languageCode
  val status = row.status
  val displayName = remember(languageCode) { Locale.forLanguageTag(languageCode).displayName }
  val isGreyedOut = status == SupplementaryLanguageStatus.NotOffered
  val isCurrent = row.isSelected && row.isSelectable
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = MIN_TOUCH_TARGET_SIZE)
        .clip(RoundedCornerShape(SELECTION_ROW_CORNER_RADIUS))
        .background(
          if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        )
        .then(
          if (row.isSelectable) {
            Modifier.selectable(
              selected = row.isSelected,
              role = Role.RadioButton,
              onClick = onSelect,
            )
          } else {
            Modifier
          }
        )
        .padding(start = LANGUAGE_ROW_START_PADDING),
  ) {
    Column(modifier = Modifier.weight(1f).alpha(if (isGreyedOut) GREYED_OUT_ALPHA else 1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = displayName,
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
      Text(
        text = languageStatusText(status) ?: languageCode,
        style = MaterialTheme.typography.bodySmall,
        color =
          when {
            status == SupplementaryLanguageStatus.Failed ||
              status == SupplementaryLanguageStatus.CheckFailed -> MaterialTheme.colorScheme.error
            isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }

    when (status) {
      SupplementaryLanguageStatus.Installed ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Installed,
          languageDisplayName = displayName,
          onDownload = onDownload,
          showInstalledBadge = true,
        )
      SupplementaryLanguageStatus.CheckFailed,
      SupplementaryLanguageStatus.NotOffered ->
        LanguagePackTrailingSlot {
          IconButton(onClick = onRetry) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = stringResource(R.string.cd_language_pack_retry, displayName),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
      SupplementaryLanguageStatus.Bundled,
      SupplementaryLanguageStatus.Queued -> LanguagePackTrailingSlot {}
      SupplementaryLanguageStatus.Checking ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Checking,
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
      SupplementaryLanguageStatus.Downloadable ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Downloadable,
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
      SupplementaryLanguageStatus.Failed ->
        LanguagePackStatusControl(
          status = LanguagePackUiStatus.Failed,
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
      is SupplementaryLanguageStatus.Downloading ->
        LanguagePackStatusControl(
          status =
            LanguagePackUiStatus.Downloading(
              status.receivedBytes,
              status.totalBytes,
              status.stalled,
            ),
          languageDisplayName = displayName,
          onDownload = onDownload,
        )
    }
  }
}

/**
 * Returns the line shown under a language's name for [status], or `null` to show the language code.
 */
@Composable
private fun languageStatusText(status: SupplementaryLanguageStatus): String? =
  when (status) {
    SupplementaryLanguageStatus.Failed -> stringResource(R.string.tts_language_pack_download_failed)
    SupplementaryLanguageStatus.CheckFailed ->
      stringResource(R.string.supplementary_status_check_failed)
    SupplementaryLanguageStatus.NotOffered ->
      stringResource(R.string.supplementary_language_not_offered)
    SupplementaryLanguageStatus.Queued -> stringResource(R.string.supplementary_status_queued)
    is SupplementaryLanguageStatus.Downloading ->
      if (status.totalBytes > 0L) {
        stringResource(
          R.string.modelitem_optional_components_progress_format,
          status.receivedBytes.humanReadableSize(),
          status.totalBytes.humanReadableSize(),
        )
      } else {
        stringResource(R.string.supplementary_status_downloading_background)
      }
    SupplementaryLanguageStatus.Bundled,
    SupplementaryLanguageStatus.Checking,
    SupplementaryLanguageStatus.Installed,
    SupplementaryLanguageStatus.Downloadable -> null
  }

/** Minimum height of a language row, matching the Material minimum touch target. */
private val MIN_TOUCH_TARGET_SIZE = 48.dp

/** Start inset of the lines under the header, lining them up with the title after the caret. */
private val HEADER_TEXT_START_INSET = 28.dp

/** Inner padding of the language list container in [LanguagePacksPanel]. */
private val LANGUAGE_LIST_PADDING = 8.dp

/** Extra start inset of each [LanguagePackRow] inside the language list container. */
private val LANGUAGE_ROW_START_PADDING = 8.dp

/** Opacity of a language the device doesn't offer, matching Material's disabled content. */
private const val GREYED_OUT_ALPHA = 0.38f

/** Corner radius of a row's highlight in [LanguageSelectionRow]. */
private val SELECTION_ROW_CORNER_RADIUS = 12.dp

/** Space on each side of the radio button in [LanguageSelectionRow]. */
private val SELECTION_ROW_RADIO_PADDING = 4.dp

/** Start padding of the text in [LanguageSelectionRow] when there is no radio button. */
private val SELECTION_ROW_TEXT_START_PADDING = 12.dp
