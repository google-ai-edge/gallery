/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.MessageBubbleShape
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.bodyLargeNarrow
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val promptTemplateTypes: List<PromptTemplateType> = PromptTemplateType.entries
private val TAB_TITLES = PromptTemplateType.entries.map { it.label }
private val ICON_BUTTON_SIZE = 42.dp

const val FULL_PROMPT_SWITCH_KEY = "full_prompt"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTemplatesPanel(
  model: Model,
  viewModel: LlmSingleTurnViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  onSend: (fullPrompt: String) -> Unit,
  onStopButtonClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedPromptTemplateType = uiState.selectedPromptTemplateType
  val inProgress = uiState.inProgress
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var curTextInputContent by remember { mutableStateOf("") }
  val inputEditorValues: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf(FULL_PROMPT_SWITCH_KEY to false)
  }
  val fullPrompt by remember {
    derivedStateOf {
      uiState.selectedPromptTemplateType.genFullPrompt(curTextInputContent, inputEditorValues)
    }
  }
  val clipboard = LocalClipboard.current
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val interactionSource = remember { MutableInteractionSource() }
  val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]

  // Update input editor values when prompt template changes.
  LaunchedEffect(selectedPromptTemplateType) {
    for (config in selectedPromptTemplateType.config.inputEditors) {
      inputEditorValues[config.label] = config.defaultOption
    }
    expandedStates.clear()
  }

  var showExamplePromptBottomSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val bubbleBorderRadius = dimensionResource(R.dimen.chat_bubble_corner_radius)

  Column(modifier = modifier) {
    // Scrollable tab row for all prompt templates.
    PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex) {
      TAB_TITLES.forEachIndexed { index, title ->
        Tab(
          selected = selectedTabIndex == index,
          enabled = !inProgress,
          onClick = {
            // Clear input when tab changes.
            curTextInputContent = ""
            // Reset full prompt switch.
            inputEditorValues[FULL_PROMPT_SWITCH_KEY] = false

            selectedTabIndex = index
            viewModel.selectPromptTemplate(
              model = model,
              promptTemplateType = promptTemplateTypes[index],
            )
          },
          text = {
            Text(
              text = title,
              modifier = Modifier.alpha(if (inProgress) 0.5f else 1f),
              color =
                if (selectedTabIndex == index) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
        )
      }
    }

    // Content.
    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
      // Input editor row.
      if (selectedPromptTemplateType.config.inputEditors.isNotEmpty()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier =
            Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          // Input editors.
          for (inputEditor in selectedPromptTemplateType.config.inputEditors) {
            when (inputEditor.type) {
              PromptTemplateInputEditorType.SINGLE_SELECT ->
                SingleSelectButton(
                  config = inputEditor as PromptTemplateSingleSelectInputEditor,
                  onSelected = { option -> inputEditorValues[inputEditor.label] = option },
                )
            }
          }
        }
      }

      // Text input box.
      Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
        Column(
          modifier =
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clickable(
              interactionSource = interactionSource,
              indication = null, // Disable the ripple effect
            ) {
              // Request focus on the TextField when the Column is clicked
              focusRequester.requestFocus()
            }
        ) {
          if (inputEditorValues[FULL_PROMPT_SWITCH_KEY] as Boolean) {
            Text(
              fullPrompt,
              style = MaterialTheme.typography.bodyMedium,
              modifier =
                Modifier.fillMaxWidth()
                  .padding(16.dp)
                  .padding(bottom = 40.dp)
                  .clip(MessageBubbleShape(radius = bubbleBorderRadius))
                  .background(MaterialTheme.customColors.agentBubbleBgColor)
                  .padding(16.dp)
                  .focusRequester(focusRequester),
            )
          } else {
            TextField(
              value = curTextInputContent,
              onValueChange = { curTextInputContent = it },
              colors =
                TextFieldDefaults.colors(
                  unfocusedContainerColor = Color.Transparent,
                  focusedContainerColor = Color.Transparent,
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                  disabledIndicatorColor = Color.Transparent,
                  disabledContainerColor = Color.Transparent,
                ),
              textStyle = bodyLargeNarrow,
              placeholder = { Text("Enter content") },
              modifier =
                Modifier.padding(bottom = 40.dp).focusRequester(focusRequester).semantics {
                  contentDescription = "Content input field"
                },
            )
          }
        }

        // Text action row.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp),
        ) {
          // Full prompt switch.
          if (
            selectedPromptTemplateType != PromptTemplateType.FREE_FORM &&
              curTextInputContent.isNotEmpty()
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              modifier =
                Modifier.clip(CircleShape)
                  .background(
                    if (inputEditorValues[FULL_PROMPT_SWITCH_KEY] as Boolean)
                      MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.customColors.agentBubbleBgColor
                  )
                  .clickable {
                    inputEditorValues[FULL_PROMPT_SWITCH_KEY] =
                      !(inputEditorValues[FULL_PROMPT_SWITCH_KEY] as Boolean)
                  }
                  .height(40.dp)
                  .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                  )
                  .padding(horizontal = 12.dp),
            ) {
              if (inputEditorValues[FULL_PROMPT_SWITCH_KEY] as Boolean) {
                Icon(
                  imageVector = Icons.Rounded.Visibility,
                  contentDescription = "",
                  modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
              } else {
                Icon(
                  imageVector = Icons.Rounded.VisibilityOff,
                  contentDescription = "",
                  modifier = Modifier.size(FilterChipDefaults.IconSize).alpha(0.3f),
                )
              }
              Text("Preview prompt", style = MaterialTheme.typography.labelMedium)
            }
          }

          Spacer(modifier = Modifier.weight(1f))

          // Button to copy full prompt.
          if (curTextInputContent.isNotEmpty()) {
            OutlinedIconButton(
              onClick = {
                scope.launch {
                  val clipData = ClipData.newPlainText("prompt", fullPrompt)
                  val clipEntry = ClipEntry(clipData = clipData)
                  clipboard.setClipEntry(clipEntry = clipEntry)
                }
              },
              colors =
                IconButtonDefaults.iconButtonColors(
                  containerColor = MaterialTheme.customColors.agentBubbleBgColor,
                  disabledContainerColor =
                    MaterialTheme.customColors.agentBubbleBgColor.copy(alpha = 0.4f),
                  contentColor = MaterialTheme.colorScheme.onSurface,
                  disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                ),
              border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.surface),
              modifier = Modifier.size(ICON_BUTTON_SIZE),
            ) {
              Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "",
                modifier = Modifier.size(20.dp),
              )
            }
          }

          // Add example prompt button.
          OutlinedIconButton(
            enabled = !inProgress,
            onClick = { showExamplePromptBottomSheet = true },
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.customColors.agentBubbleBgColor,
                disabledContainerColor =
                  MaterialTheme.customColors.agentBubbleBgColor.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
              ),
            border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.surface),
            modifier = Modifier.size(ICON_BUTTON_SIZE),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = "", modifier = Modifier.size(20.dp))
          }

          val modelInitializing =
            modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
          if (inProgress && !modelInitializing && !uiState.preparing) {
            IconButton(
              onClick = { onStopButtonClicked(model) },
              colors =
                IconButtonDefaults.iconButtonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
              modifier = Modifier.size(ICON_BUTTON_SIZE),
            ) {
              Icon(
                Icons.Rounded.Stop,
                contentDescription = "",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          } else {
            // Send button
            OutlinedIconButton(
              enabled = !inProgress && curTextInputContent.isNotEmpty(),
              onClick = {
                focusManager.clearFocus()
                if (fullPrompt.text.isNotBlank()) {
                  onSend(fullPrompt.text)
                } else {
                  Toast.makeText(
                    context,
                    "Please enter a valid prompt",
                    Toast.LENGTH_SHORT
                  ).show()
                }
              },
              colors =
                IconButtonDefaults.iconButtonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  disabledContainerColor =
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                  contentColor = MaterialTheme.colorScheme.onSurface,
                  disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                ),
              border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.surface),
              modifier = Modifier.size(ICON_BUTTON_SIZE),
            ) {
              Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send prompt button",
                modifier = Modifier.size(20.dp).offset(x = 2.dp),
              )
            }
          }
        }
      }
    }
  }

  if (showExamplePromptBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showExamplePromptBottomSheet = false },
      sheetState = sheetState,
      modifier = Modifier.wrapContentHeight(),
    ) {
      Column(modifier = Modifier.padding(bottom = 16.dp)) {
        // Title
        Text(
          "Select an example",
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          style = MaterialTheme.typography.titleLarge,
        )

        // Examples
        for (prompt in selectedPromptTemplateType.examplePrompts) {
          var textLayoutResultState by remember { mutableStateOf<TextLayoutResult?>(null) }
          val hasOverflow =
            remember(textLayoutResultState) { textLayoutResultState?.hasVisualOverflow ?: false }
          val isExpanded = expandedStates[prompt] ?: false

          Column(
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  curTextInputContent = prompt
                  scope.launch {
                    // Give it sometime to show the click effect.
                    delay(200)
                    showExamplePromptBottomSheet = false
                  }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Outlined.Description, contentDescription = "")
              Text(
                prompt,
                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                onTextLayout = { textLayoutResultState = it },
              )
            }

            if (hasOverflow && !isExpanded) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
              ) {
                Box(
                  modifier =
                    Modifier.padding(end = 16.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                      .clickable { expandedStates[prompt] = true }
                      .padding(vertical = 1.dp, horizontal = 6.dp)
                ) {
                  Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = "",
                    modifier = Modifier.size(12.dp),
                  )
                }
              }
            } else if (isExpanded) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
              ) {
                Box(
                  modifier =
                    Modifier.padding(end = 16.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                      .clickable { expandedStates[prompt] = false }
                      .padding(vertical = 1.dp, horizontal = 6.dp)
                ) {
                  Icon(
                    Icons.Outlined.ExpandLess,
                    contentDescription = "",
                    modifier = Modifier.size(12.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
