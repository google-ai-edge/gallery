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

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelSelectionConfig
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.SpeechLanguageOverlap
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.InfoTooltipIconButton
import com.google.ai.edge.gallery.ui.common.ModelDownloadGatesHost
import com.google.ai.edge.gallery.ui.common.ModelNotAvailableMemoryAlert
import com.google.ai.edge.gallery.ui.common.SpeechEngineErrorDialog
import com.google.ai.edge.gallery.ui.common.getTaskBgColor
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.modelitem.ModelRadioItem
import com.google.ai.edge.gallery.ui.common.rememberModelDownloadGates
import com.google.ai.edge.gallery.ui.common.rememberOpenSpeechServicesStorePage
import java.util.Locale

private val CONTENT_ANIMATION_OFFSET = 16.dp

/**
 * The model selection screen of a task with a [ModelSelectionConfig]: one collapsible section per
 * kind of model (for example main, speech recognition and text-to-speech models), each listing
 * radio model cards, and a summary sheet with Try it.
 *
 * Try it runs the download checks, downloads what's missing, then calls [onLaunch] with the main
 * model once everything is ready.
 *
 * @param expandTarget The section to expand when the task sent the user back to choose another
 *   model, consumed with [onExpandTargetHandled].
 */
@Composable
fun SectionedModelSelectionScreen(
  task: Task,
  config: ModelSelectionConfig,
  modelManagerViewModel: ModelManagerViewModel,
  enableAnimation: Boolean,
  navigateUp: () -> Unit,
  onLaunch: (Model) -> Unit,
  modifier: Modifier = Modifier,
  expandTarget: ModelSelectionTarget? = null,
  onExpandTargetHandled: () -> Unit = {},
  viewModel: TaskModelSelectionViewModel = hiltViewModel(),
) {
  val host = remember(modelManagerViewModel) { modelManagerViewModel.asModelSelectionHost() }
  LaunchedEffect(host, task, config) { viewModel.bind(host = host, task = task, config = config) }
  // Declared after the bind effect, so the sections exist when it runs.
  LaunchedEffect(expandTarget) {
    val target = expandTarget ?: return@LaunchedEffect
    viewModel.expandSection(target)
    onExpandTargetHandled()
  }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val gates = rememberModelDownloadGates(modelManagerViewModel = modelManagerViewModel)

  // Navigate up when there are no models left.
  val modelCount by remember {
    derivedStateOf {
      val trigger = task.updateTrigger.value
      if (trigger >= 0) {
        task.models.size
      } else {
        -1
      }
    }
  }
  LaunchedEffect(modelCount) {
    if (modelCount == 0) {
      navigateUp()
    }
  }

  // Handle system's edge swipe.
  BackHandler { navigateUp() }

  // Run the download checks Try it asked for, one after another.
  LaunchedEffect(uiState.gateRequest) {
    val request = uiState.gateRequest ?: return@LaunchedEffect
    gates.runAll(
      memoryCheckModel = request.memoryCheckModel,
      modelsToDownload = request.modelsToDownload,
      onPassed = { viewModel.onGatesPassed(request) },
      onAborted = { viewModel.onGatesAborted(request) },
    )
  }

  // When Hugging Face refuses a download the user started, prompt for the missing access and retry.
  val authErrorCandidates =
    uiState.sections.flatMap { it.candidates }.filter { it.downloadStatus?.authError != null }
  LaunchedEffect(authErrorCandidates.map { it.model.name to it.downloadStatus?.authError }) {
    for (candidate in authErrorCandidates) {
      val model = candidate.model
      val error = modelManagerViewModel.takeAuthErrorToPrompt(model) ?: continue
      gates.resolveAuthError(
        model = model,
        authError = error,
        onAccessible = { accessToken, afterSignIn ->
          model.downloadInfo.accessToken = accessToken?.takeIf { it.isNotEmpty() }
          modelManagerViewModel.onUserStartedDownload(model = model, afterSignIn = afterSignIn)
          viewModel.onTryItClicked()
        },
      )
      break
    }
  }

  // Open the task once every selected model is ready.
  LaunchedEffect(uiState.launchModel) {
    val launchModel = uiState.launchModel ?: return@LaunchedEffect
    viewModel.onLaunchHandled()
    onLaunch(launchModel)
  }

  SectionedModelSelectionContent(
    task = task,
    uiState = uiState,
    enableAnimation = enableAnimation,
    navigateUp = navigateUp,
    onSelectModel = viewModel::selectModel,
    onToggleSection = viewModel::toggleSection,
    onModelExpandedChange = viewModel::setModelExpanded,
    onToggleSummary = viewModel::toggleSummaryExpanded,
    onTryIt = viewModel::onTryItClicked,
    onCancelLaunch = viewModel::cancelLaunch,
    onScrolled = viewModel::onScrolled,
    modelManagerViewModel = modelManagerViewModel,
    modifier = modifier,
  )

  ModelDownloadGatesHost(gates = gates)

  val notReady = uiState.notReady
  if (notReady != null) {
    SpeechEngineErrorDialog(
      error = notReady.error,
      onDismiss = viewModel::dismissNotReadyModel,
      // The popup stays up, so the user can tap Retry after updating.
      onUpdate = rememberOpenSpeechServicesStorePage(),
      onRetry = viewModel::retryNotReadyModel,
      // Languages are picked in the task, not on this screen.
      onChooseLanguage = null,
      onChooseAnotherModel = viewModel::chooseAnotherModelForNotReady,
    )
  }
}

/** Stateless content of [SectionedModelSelectionScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionedModelSelectionContent(
  task: Task,
  uiState: TaskModelSelectionUiState,
  enableAnimation: Boolean,
  navigateUp: () -> Unit,
  onSelectModel: (sectionId: String, model: Model) -> Unit,
  onToggleSection: (sectionId: String) -> Unit,
  onModelExpandedChange: (modelName: String, expanded: Boolean) -> Unit,
  onToggleSummary: () -> Unit,
  onTryIt: () -> Unit,
  onCancelLaunch: () -> Unit,
  onScrolled: () -> Unit,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel? = null,
) {
  val headerAnimation = rememberTaskHeaderAnimation(enableAnimation = enableAnimation)
  val listProgress = headerAnimation.modelListProgress
  val listState = rememberLazyListState()
  var showMemoryNotAvailableDialog by remember { mutableStateOf(false) }

  // When a section is auto-expanded, scroll its header to the top.
  LaunchedEffect(uiState.scrollToSectionId) {
    val sectionId = uiState.scrollToSectionId ?: return@LaunchedEffect
    val index = sectionHeaderIndex(uiState.sections, sectionId)
    if (index >= 0) {
      listState.animateScrollToItem(index)
    }
    onScrolled()
  }

  val itemModifier = Modifier.graphicsLayer {
    alpha = listProgress
    translationY = (CONTENT_ANIMATION_OFFSET * (1 - listProgress)).toPx()
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      GalleryTopAppBar(
        title = task.label,
        leftAction = AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = navigateUp),
      )
    },
    bottomBar = {
      ModelSelectionSummarySheet(
        task = task,
        uiState = uiState,
        onToggleSummary = onToggleSummary,
        onTryIt = onTryIt,
        onCancelLaunch = onCancelLaunch,
      )
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().background(color = getTaskBgColor(task = task))) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = listState,
      ) {
        // Task header area.
        item(key = "taskHeader") {
          TaskHeader(
            task = task,
            modelCount = uiState.sections.sumOf { it.candidates.size },
            modelCountText = sectionModelCountsText(uiState.sections),
            animation = headerAnimation,
          )
        }

        for (sectionState in uiState.sections) {
          val sectionId = sectionState.section.id
          item(key = "section_header_$sectionId") {
            ModelSelectionSectionHeader(
              sectionState = sectionState,
              onToggle = { onToggleSection(sectionId) },
              modifier = itemModifier,
            )
          }
          if (sectionState.isExpanded) {
            if (sectionState.candidates.isEmpty()) {
              item(key = "section_empty_$sectionId") {
                Text(
                  stringResource(sectionState.section.emptyMessageResId),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = itemModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
              }
            } else {
              items(
                items = sectionState.candidates,
                key = { "section_model_${sectionId}_${it.model.name}" },
              ) { candidate ->
                ModelRadioItem(
                  model = candidate.model,
                  // Only main models can carry the task's "Best overall" badge.
                  task =
                    if (sectionState.section.target == ModelSelectionTarget.MAIN_MODEL) task
                    else null,
                  downloadStatus = candidate.downloadStatus,
                  selected = candidate.isSelected,
                  selectable = candidate.isSelectable,
                  expanded = candidate.isExpanded,
                  onSelected = { onSelectModel(sectionId, candidate.model) },
                  onExpandedChange = { onModelExpandedChange(candidate.model.name, it) },
                  supportedLanguages = candidate.supportedLanguages,
                  modifier = itemModifier,
                  isMemoryLow = candidate.isMemoryLow,
                  onMemoryLowClick = { showMemoryNotAvailableDialog = true },
                  modelManagerViewModel = modelManagerViewModel,
                )
              }
            }
          }
        }
      }
    }
  }

  if (showMemoryNotAvailableDialog) {
    ModelNotAvailableMemoryAlert(onDismissed = { showMemoryNotAvailableDialog = false })
  }
}

/** Index of the header of section [sectionId] in the list, or -1 if there is no such section. */
internal fun sectionHeaderIndex(
  sections: List<ModelSelectionSectionUiState>,
  sectionId: String,
): Int {
  // The task header comes first.
  var index = 1
  for (sectionState in sections) {
    if (sectionState.section.id == sectionId) {
      return index
    }
    index += 1
    if (sectionState.isExpanded) {
      index += maxOf(sectionState.candidates.size, 1)
    }
  }
  return -1
}

/**
 * The per-kind model counts shown in the task header, for example "4 main models • 2 ASR models • 2
 * TTS models".
 */
@Composable
private fun sectionModelCountsText(sections: List<ModelSelectionSectionUiState>): String {
  val separator = stringResource(R.string.model_selection_header_count_separator)
  return sections
    .map {
      pluralStringResource(it.section.availableCountResId, it.candidates.size, it.candidates.size)
    }
    .joinToString(separator)
}

/**
 * Header of a section: a caret, the section title and, when the section has one, an info button
 * explaining what its model does. A collapsed header also shows the selected model's name.
 */
@Composable
private fun ModelSelectionSectionHeader(
  sectionState: ModelSelectionSectionUiState,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val stateDesc =
    stringResource(if (sectionState.isExpanded) R.string.cd_expanded else R.string.cd_collapsed)
  val title = stringResource(sectionState.section.titleResId)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth().padding(top = 8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.weight(1f)
          .clickable(role = Role.Button, onClick = onToggle)
          .semantics { stateDescription = stateDesc }
          .padding(vertical = 8.dp),
    ) {
      Icon(
        Icons.Rounded.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(if (sectionState.isExpanded) 0f else -90f),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      val selectedModel = sectionState.selectedModel
      if (!sectionState.isExpanded && selectedModel != null) {
        Text(
          selectedModel.displayName.ifEmpty { selectedModel.name },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.End,
          modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
        )
      }
    }
    val infoResId = sectionState.section.infoResId
    if (infoResId != null) {
      InfoTooltipIconButton(
        tooltipText = stringResource(infoResId),
        contentDescription = stringResource(R.string.cd_model_selection_section_info, title),
      )
    }
  }
}

/**
 * The summary sheet at the bottom of the screen and Try it, which turns into download progress with
 * a cancel button. Collapsed, it shows how many models of each kind are selected on one line.
 * Expanded, it shows one row per kind with its selected model.
 */
@Composable
private fun ModelSelectionSummarySheet(
  task: Task,
  uiState: TaskModelSelectionUiState,
  onToggleSummary: () -> Unit,
  onTryIt: () -> Unit,
  onCancelLaunch: () -> Unit,
) {
  val summaryStateDesc =
    stringResource(if (uiState.isSummaryExpanded) R.string.cd_expanded else R.string.cd_collapsed)
  Surface(
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shadowElevation = 8.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier =
        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .animateContentSize()
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggleSummary)
            .semantics { stateDescription = summaryStateDesc }
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
      ) {
        Text(
          if (uiState.isSummaryExpanded) {
            expandedSummaryTitle(uiState.sections)
          } else {
            collapsedSummaryText(uiState.sections)
          },
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        Icon(
          Icons.Rounded.ArrowDropDown,
          contentDescription =
            stringResource(
              if (uiState.isSummaryExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon
            ),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.rotate(if (uiState.isSummaryExpanded) 0f else 180f),
        )
      }
      if (uiState.isSummaryExpanded) {
        Column(
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier =
            Modifier.padding(top = 8.dp)
              .fillMaxWidth()
              .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
              )
              .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
          for (sectionState in uiState.sections) {
            SummarySectionRow(sectionState = sectionState)
          }
        }
      }
      uiState.speechLanguageOverlap?.let { overlap ->
        SpeechLanguageOverlapBanner(overlap = overlap, modifier = Modifier.padding(top = 12.dp))
      }
      Spacer(modifier = Modifier.height(12.dp))
      when (uiState.launchPhase) {
        ModelSelectionLaunchPhase.IDLE,
        ModelSelectionLaunchPhase.GATING ->
          TryItButton(task = task, enabled = uiState.canTryIt, onClick = onTryIt)
        ModelSelectionLaunchPhase.DOWNLOADING,
        ModelSelectionLaunchPhase.CHECKING ->
          LaunchProgressRow(
            label =
              stringResource(
                if (uiState.launchPhase == ModelSelectionLaunchPhase.DOWNLOADING) {
                  R.string.model_selection_downloading
                } else {
                  R.string.model_selection_checking
                }
              ),
            progress = uiState.downloadProgress,
            canCancel = uiState.launchPhase == ModelSelectionLaunchPhase.DOWNLOADING,
            task = task,
            onCancel = onCancelLaunch,
          )
      }
    }
  }
}

/** One line for the collapsed summary sheet, for example "Selected: 1 main, 1 ASR, and 0 TTS". */
@Composable
private fun collapsedSummaryText(sections: List<ModelSelectionSectionUiState>): String {
  val shortCounts = sections.map {
    pluralStringResource(it.section.selectedShortCountResId, it.selectedCount, it.selectedCount)
  }
  // Joins with the locale's own list punctuation and conjunction.
  val locale = LocalConfiguration.current.locales[0]
  val joinedCounts =
    remember(locale, shortCounts) { ListFormatter.getInstance(locale).format(shortCounts) }
  return stringResource(R.string.model_selection_summary_collapsed, joinedCounts)
}

/** Title of the expanded summary sheet, for example "2/3 models selected". */
@Composable
private fun expandedSummaryTitle(sections: List<ModelSelectionSectionUiState>): String {
  val completeCount = sections.count { it.selectedCount >= it.section.requiredCount }
  return pluralStringResource(
    R.plurals.model_selection_summary_expanded_title,
    sections.size,
    completeCount,
    sections.size,
  )
}

/**
 * A row of the expanded summary sheet: how many models of a kind are selected, a check once the
 * selection is complete, and the selected model's name or "None".
 */
@Composable
private fun SummarySectionRow(sectionState: ModelSelectionSectionUiState) {
  val selectedModel = sectionState.selectedModel
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(
      pluralStringResource(
        sectionState.section.selectedCountResId,
        sectionState.selectedCount,
        sectionState.selectedCount,
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (sectionState.selectedCount >= sectionState.section.requiredCount) {
      Spacer(modifier = Modifier.width(8.dp))
      Icon(
        Icons.Rounded.CheckCircle,
        // The count text already says that a model is selected.
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
    }
    Text(
      selectedModel?.let { it.displayName.ifEmpty { it.name } }
        ?: stringResource(R.string.model_selection_summary_none),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f).padding(start = 12.dp),
    )
  }
}

/**
 * Banner above Try it about the languages of the selected speech models: an info banner listing the
 * shared languages when the models share at least one language, or an error banner when they share
 * no language (Try it is then disabled).
 */
@Composable
private fun SpeechLanguageOverlapBanner(
  overlap: SpeechLanguageOverlap,
  modifier: Modifier = Modifier,
) {
  val noOverlap = overlap.shared.isEmpty()
  val text =
    if (noOverlap) {
      AnnotatedString(stringResource(R.string.model_selection_no_shared_languages_banner))
    } else {
      val languageNames = languageNamesText(overlap.shared)
      val rawText =
        pluralStringResource(
          R.plurals.model_selection_shared_languages_banner,
          overlap.shared.size,
          overlap.shared.size,
          languageNames,
        )
      remember(rawText, languageNames) {
        buildAnnotatedString {
          append(rawText)
          val start = rawText.indexOf(languageNames)
          if (start >= 0 && languageNames.isNotEmpty()) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + languageNames.length)
          }
        }
      }
    }
  Surface(
    shape = RoundedCornerShape(12.dp),
    color =
      if (noOverlap) MaterialTheme.colorScheme.errorContainer
      else MaterialTheme.colorScheme.primaryContainer,
    contentColor =
      if (noOverlap) MaterialTheme.colorScheme.onErrorContainer
      else MaterialTheme.colorScheme.onPrimaryContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.Top,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Icon(
        if (noOverlap) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
        // The banner text says what it's about.
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(text, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/**
 * The names of the languages with BCP-47 tags [languageTags], joined with the locale's list
 * punctuation.
 */
@Composable
private fun languageNamesText(languageTags: List<String>): String {
  val locale = LocalConfiguration.current.locales.takeUnless { it.isEmpty }?.get(0) ?: Locale.ROOT
  return remember(languageTags, locale) {
    val names =
      languageTags
        .map { tag -> Locale.forLanguageTag(tag).getDisplayLanguage(locale).ifEmpty { tag } }
        .distinct()
    ListFormatter.getInstance(locale).format(names)
  }
}

@Composable
private fun TryItButton(task: Task, enabled: Boolean, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = getTaskBgGradientColors(task = task)[1],
        contentColor = Color.White,
      ),
    modifier = Modifier.fillMaxWidth().height(56.dp),
  ) {
    Icon(
      Icons.AutoMirrored.Rounded.ArrowForward,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(stringResource(R.string.try_it), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun LaunchProgressRow(
  label: String,
  progress: Float?,
  canCancel: Boolean,
  task: Task,
  onCancel: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .height(56.dp)
        .background(color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape)
        .padding(start = 20.dp, end = 4.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      val color = getTaskBgGradientColors(task = task)[1]
      if (progress != null) {
        LinearProgressIndicator(
          progress = { progress },
          color = color,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        LinearProgressIndicator(
          color = color,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    if (canCancel) {
      IconButton(onClick = onCancel) {
        Icon(
          Icons.Outlined.Close,
          contentDescription = stringResource(R.string.cd_stop_icon),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    } else {
      Spacer(modifier = Modifier.width(16.dp))
    }
  }
}
