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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.MODEL_INFO_ICON_SIZE
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.ProvideTrackableUriHandler
import com.google.ai.edge.gallery.ui.theme.customColors
import java.util.Locale

/**
 * A selectable model card for screens where the user picks one model per section.
 *
 * It reuses the regular model card's anatomy: [ModelNameAndStatus] (the "Best overall" badge, name,
 * status icon and size, download progress and failure message, and "Learn more"), the expand
 * chevron, the model description, and the AICore access or unavailability panels. It has no
 * Download, Try it or Delete buttons: downloads are started by the screen, and models are deleted
 * in the model manager. Selection and expansion are hoisted so the screen can drive them.
 *
 * @param task The task the model is offered for, used for the "Best overall" badge. `null` for
 *   supplementary models.
 * @param selectable Whether the model can be picked. Unselectable models always show the panel that
 *   explains why.
 */
@Composable
fun ModelRadioItem(
  model: Model,
  task: Task?,
  downloadStatus: ModelDownloadStatus?,
  selected: Boolean,
  selectable: Boolean,
  expanded: Boolean,
  onSelected: () -> Unit,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  isMemoryLow: Boolean = false,
  onMemoryLowClick: () -> Unit = {},
  supportedLanguages: List<String> = emptyList(),
) {
  val unavailability = downloadStatus?.unavailability
  val showUnavailabilityPanel =
    downloadStatus?.status == ModelDownloadStatusType.UNAVAILABLE && unavailability != null
  val showAICoreAccessPanel =
    model.autoDownloadsOnStartup && downloadStatus?.status == ModelDownloadStatusType.FAILED
  val interactionModifier =
    if (isMemoryLow && !selectable) {
      Modifier.clickable(onClick = onMemoryLowClick)
    } else {
      Modifier.selectable(
        selected = selected,
        enabled = selectable,
        role = Role.RadioButton,
        onClick = onSelected,
      )
    }

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(size = 12.dp))
        .background(color = MaterialTheme.customColors.taskCardBgColor)
        .then(interactionModifier)
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(
            selected = selected,
            // The whole card is the selectable element.
            onClick = null,
            enabled = selectable,
            modifier = Modifier.padding(end = 12.dp),
          )
          ModelNameAndStatus(
            model = model,
            task = task,
            downloadStatus = downloadStatus,
            isExpanded = expanded,
            modifier = Modifier.weight(1f),
            statusContent =
              if (isMemoryLow) {
                {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      Icons.Rounded.Error,
                      tint = MaterialTheme.customColors.modelInfoIconColor,
                      contentDescription = stringResource(R.string.cd_unavailable_icon),
                      modifier = Modifier.padding(end = 4.dp).size(MODEL_INFO_ICON_SIZE),
                    )
                    Text(
                      stringResource(R.string.not_available),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      style = MaterialTheme.typography.bodyMedium,
                      overflow = TextOverflow.Visible,
                    )
                  }
                }
              } else {
                null
              },
          )
        }
        // Button to expand/collapse the description at the right.
        IconButton(
          onClick = { onExpandedChange(!expanded) },
          modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp),
        ) {
          Icon(
            if (expanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
            contentDescription =
              stringResource(if (expanded) R.string.cd_collapse_icon else R.string.cd_expand_icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.6f),
          )
        }
      }

      if (downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS) {
        val progress = calculateDownloadProgress(downloadStatus = downloadStatus)
        if (progress > 0f) {
          LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
      }

      // Show the description when expanded.
      AnimatedVisibility(visible = expanded && model.info.isNotEmpty()) {
        // Track clicks on links embedded in the allowlist-provided model description.
        ProvideTrackableUriHandler {
          MarkdownText(
            model.info,
            smallFontSize = true,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }

      AnimatedVisibility(visible = expanded && supportedLanguages.isNotEmpty()) {
        SupportedLanguagesSection(
          modelName = model.name,
          supportedLanguages = supportedLanguages,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      // Explain why the model can't be used.
      val showPanel = expanded || !selectable
      when {
        showPanel && unavailability != null && showUnavailabilityPanel ->
          ModelUnavailabilityPanel(
            unavailability = unavailability,
            fallbackGuideUrl = model.learnMoreUrl,
          )
        showPanel && showAICoreAccessPanel -> AICoreAccessPanel()
      }
    }
  }
}

@Composable
private fun SupportedLanguagesSection(
  modelName: String,
  supportedLanguages: List<String>,
  modifier: Modifier = Modifier,
) {
  var isExpanded by rememberSaveable(modelName) { mutableStateOf(false) }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth()
          .clickable { isExpanded = !isExpanded }
          .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Text(
        text = stringResource(R.string.model_selection_supported_languages_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text =
          stringResource(R.string.model_selection_section_count_format, supportedLanguages.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.weight(1f))
      Icon(
        imageVector = Icons.Rounded.ArrowDropDown,
        contentDescription =
          stringResource(if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(if (isExpanded) 180f else 0f),
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
          modifier = Modifier.padding(horizontal = 14.dp),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          for (languageCode in supportedLanguages) {
            val displayName =
              remember(languageCode) {
                Locale.forLanguageTag(languageCode).displayName.ifEmpty { languageCode }
              }
            Text(
              text = displayName,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
          }
        }
      }
    }
  }
}
