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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AGAddOrEditSkill"
private const val DEFAULT_SCRIPT_NAME = "index.html"
private val TABS = listOf("Info", "Scripts")

private val CALL_JS_INSTRUCTIONS_TEMPLATE =
  """
  # Instructions

  Call the `run_js` tool with the following exact parameters:

  - data: A JSON string with the following fields:
    - [fieldName]: [Data type, e.g. String, Number, Array] - [short description].
    - ...
  """
    .trimIndent()

private val INPUT_DATA_PLACEHOLDER =
  """
  - [fieldName]: [Data type (String, Number, Array)] - [short description]
  """
    .trimIndent()

private val OUTPUT_DATA_PLACEHOLDER =
  """
  - [fieldName]: [Data type (String, Number, Array)] - [short description]
  """
    .trimIndent()

/** A ModalBottomSheet Composable for creating a new skill from manual input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditSkillBottomSheet(
  skillManagerViewModel: SkillManagerViewModel,
  skillIndex: Int,
  onDismiss: () -> Unit,
  onSuccess: () -> Unit,
) {
  val uiState by skillManagerViewModel.uiState.collectAsState()
  var cancelClicked by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var skill by remember { mutableStateOf(uiState.skills.getOrNull(skillIndex)?.skill) }
  var name by remember { mutableStateOf(skill?.name ?: "") }
  var description by remember { mutableStateOf(skill?.description ?: "") }
  var instructions by remember { mutableStateOf(skill?.instructions ?: "") }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf("") }
  var edited by remember { mutableStateOf(false) }
  var showDiscardDialog by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val curSkill = skill
  val viewingMode = true

  var llmPromptGeneratorRequirements by remember { mutableStateOf(skill?.description ?: "") }
  var llmPromptGeneratorInputData by remember { mutableStateOf(INPUT_DATA_PLACEHOLDER) }
  var llmPromptGeneratorOutputData by remember { mutableStateOf(OUTPUT_DATA_PLACEHOLDER) }

  var scriptsLoading by remember { mutableStateOf(false) }
  val scriptContents = remember { mutableStateMapOf<String, String>() }
  var selectedScript by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(skill) {
    val curSkill = skill
    if (curSkill != null) {
      Log.d(TAG, "Loading skill scripts...")
      scriptsLoading = true
      skillManagerViewModel.loadSkillScriptsContent(skill = curSkill) { loaded ->
        scriptContents.clear()
        scriptContents.putAll(loaded)
        Log.d(TAG, "Loaded scripts: ${scriptContents.keys.joinToString(",")}")
        scriptsLoading = false
        selectedScript =
          scriptContents.keys.firstOrNull { it == DEFAULT_SCRIPT_NAME }
            ?: scriptContents.keys.firstOrNull()
      }
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      Column(modifier = Modifier.padding(top = 16.dp).fillMaxSize().imePadding()) {
        // Title
        Text(
          // Viewing mode.
          if (viewingMode) {
            "View skill: ${curSkill?.name ?: ""}"
          }
          // Editing existing skill.
          else if (skillIndex <= uiState.skills.size - 1) {
            "Edit skill: ${curSkill?.name ?: ""}"
          }
          // Creating new skill.
          else {
            stringResource(R.string.add_skill_manual_input_sheet_title)
          },
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Tab Bar
        var selectedTabIndex by remember { mutableIntStateOf(0) }
        if (!viewingMode) {
          PrimaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            modifier = Modifier.padding(horizontal = 16.dp),
          ) {
            for (index in TABS.indices) {
              val title = TABS[index]
              Tab(
                selected = selectedTabIndex == index,
                onClick = { selectedTabIndex = index },
                text = { Text(title) },
              )
            }
          }
        } else {
          Spacer(modifier = Modifier.height(8.dp))
        }

        // Tab Content
        //
        // Disable over-scroll "stretch" effect.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
          Column(
            modifier =
              Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
          ) {
            when (selectedTabIndex) {
              // Info tab.
              0 -> {
                Column(modifier = Modifier.fillMaxHeight().padding(top = 16.dp)) {
                  // Name.
                  CursorTrackingTextField(
                    initialValue = name,
                    enabled = !viewingMode,
                    onValueChange = {
                      if (!edited) {
                        edited = name != it
                      }
                      name = it
                    },
                    labelResId = R.string.name,
                    supportingTextResId = R.string.skill_name_input_description,
                  )

                  Spacer(modifier = Modifier.height(28.dp))

                  // Description.
                  CursorTrackingTextField(
                    labelResId = R.string.description_required,
                    supportingTextResId = R.string.skill_description_input_description,
                    minLines = 3,
                    enabled = !viewingMode,
                    initialValue = description,
                    onValueChange = {
                      if (!edited) {
                        edited = description != it
                      }
                      description = it
                    },
                  )

                  Spacer(modifier = Modifier.height(28.dp))

                  // Instructions.
                  if (!viewingMode) {
                    Row(
                      horizontalArrangement = Arrangement.End,
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      // A chip to apply call-js template.
                      AssistChip(
                        onClick = {
                          edited = true
                          instructions = CALL_JS_INSTRUCTIONS_TEMPLATE
                        },
                        label = { Text(stringResource(R.string.use_call_js_template)) },
                        leadingIcon = {
                          Icon(
                            Icons.AutoMirrored.Outlined.ListAlt,
                            contentDescription = null,
                            Modifier.size(AssistChipDefaults.IconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        },
                      )
                    }
                  }
                  CursorTrackingTextField(
                    labelResId = R.string.instructions,
                    supportingTextResId = R.string.skill_instructions_input_description,
                    minLines = 6,
                    enabled = !viewingMode,
                    initialValue = instructions,
                    onValueChange = { newText ->
                      if (!edited) {
                        edited = instructions != newText
                      }
                      instructions = newText
                    },
                  )

                  Spacer(modifier = Modifier.height(16.dp))
                }
              }
              // Script tab.
              1 -> {
                if (scriptsLoading) {
                  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                  }
                } else {
                  ScriptsTabContent(
                    scope = scope,
                    scriptContents = scriptContents,
                    selectedScript = selectedScript,
                    onScriptSelected = { selectedScript = it },
                    onAddDefaultScript = {
                      scriptContents[DEFAULT_SCRIPT_NAME] = ""
                      selectedScript = DEFAULT_SCRIPT_NAME
                      edited = true
                    },
                    onScriptChanged = { name, content ->
                      if (!edited) {
                        edited = scriptContents[name] != content
                      }
                      scriptContents[name] = content
                    },
                    onScriptAdded = { scriptName ->
                      scriptContents[scriptName] = ""
                      selectedScript = scriptName
                      edited = true
                    },
                    onScriptDeleted = { scriptName ->
                      scriptContents.remove(key = scriptName)
                      if (selectedScript == scriptName) {
                        selectedScript = scriptContents.keys.firstOrNull()
                      }
                      skill?.let { curSkill ->
                        skillManagerViewModel.deleteSkillScript(
                          skill = curSkill,
                          scriptName = scriptName,
                        )
                      }
                      edited = true
                    },
                    modifier = Modifier.weight(1f),
                    curDescription = description,
                    requirements = llmPromptGeneratorRequirements,
                    onRequirementsChange = { llmPromptGeneratorRequirements = it },
                    inputData = llmPromptGeneratorInputData,
                    onInputDataChange = { llmPromptGeneratorInputData = it },
                    outputData = llmPromptGeneratorOutputData,
                    onOutputDataChange = { llmPromptGeneratorOutputData = it },
                    snackbarHostState = snackbarHostState,
                  )
                }
              }
            }
          }
        }

        // Action Buttons
        Row(
          modifier =
            Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
              .fillMaxWidth()
              .padding(vertical = 8.dp, horizontal = 16.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (viewingMode) {
            Button(
              onClick = {
                cancelClicked = true
                scope.launch {
                  sheetState.hide()
                  onDismiss()
                }
              }
            ) {
              Text(stringResource(R.string.ok))
            }
          } else {
            TextButton(
              onClick = {
                if (edited) {
                  showDiscardDialog = true
                } else {
                  cancelClicked = true
                  scope.launch {
                    sheetState.hide()
                    onDismiss()
                  }
                }
              }
            ) {
              Text(stringResource(R.string.cancel))
            }
          }
          if (!viewingMode) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                skillManagerViewModel.saveSkillEdit(
                  index = skillIndex,
                  name = name,
                  description = description,
                  instructions = instructions,
                  scriptsContent = scriptContents,
                  onError = { error ->
                    errorMessage = error
                    showErrorDialog = true
                  },
                  onSuccess = {
                    onDismiss()
                    onSuccess()
                  },
                )
              },
              enabled = name.isNotEmpty() && description.isNotEmpty() && edited,
            ) {
              Text(stringResource(R.string.save))
            }
          }
        }
      }
      SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = 40.dp))
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      onDismissRequest = { showErrorDialog = false },
      title = { Text(stringResource(R.string.failed_to_save)) },
      text = { Text(errorMessage) },
      confirmButton = {
        Button(onClick = { showErrorDialog = false }) { Text(stringResource(R.string.ok)) }
      },
    )
  }

  if (showDiscardDialog) {
    AlertDialog(
      onDismissRequest = { showDiscardDialog = false },
      title = { Text(stringResource(R.string.discard_changes_dialog_title)) },
      text = { Text(stringResource(R.string.discard_changes_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            cancelClicked = true
            scope.launch {
              sheetState.hide()
              onDismiss()
            }
            showDiscardDialog = false
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.errorTextColor,
              contentColor = Color.White,
            ),
        ) {
          Text(stringResource(R.string.discard))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDiscardDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

/** Composable for the "Scripts" tab content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptsTabContent(
  scope: CoroutineScope,
  scriptContents: Map<String, String>,
  selectedScript: String?,
  onScriptSelected: (String?) -> Unit,
  onAddDefaultScript: () -> Unit,
  onScriptChanged: (name: String, content: String) -> Unit,
  onScriptAdded: (scriptName: String) -> Unit,
  onScriptDeleted: (scriptName: String) -> Unit,
  curDescription: String,
  requirements: String,
  onRequirementsChange: (String) -> Unit,
  inputData: String,
  onInputDataChange: (String) -> Unit,
  outputData: String,
  onOutputDataChange: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  modifier: Modifier = Modifier,
) {
  val scripts = scriptContents.keys.toList()
  var scriptContent by remember { mutableStateOf(scriptContents[selectedScript] ?: "") }
  var showAddScriptDialog by remember { mutableStateOf(false) }
  var showDeleteConfirmation by remember { mutableStateOf(false) }
  var showGenerateLlmPromptBottomSheet by remember { mutableStateOf(false) }
  var newScriptName by remember { mutableStateOf("") }
  val clipboard = LocalClipboard.current

  LaunchedEffect(selectedScript, scriptContents.toMap()) {
    scriptContent = scriptContents[selectedScript] ?: ""
  }

  // Show empty state if there are no scripts.
  //
  // A button to add the first script.
  if (scriptContents.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      FilledTonalButton(onClick = onAddDefaultScript) {
        Text(stringResource(R.string.add_default_script))
      }
    }
  }
  // Show scripts tab content when there is at least one script.
  else {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
      // Dropdown and Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
          expanded = expanded,
          onExpandedChange = { expanded = !expanded },
          modifier = Modifier.weight(1f),
        ) {
          OutlinedTextField(
            value = selectedScript ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.select_script)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
              Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
          )
          ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (script in scripts) {
              DropdownMenuItem(
                text = { Text(script) },
                onClick = {
                  onScriptSelected(script)
                  expanded = false
                },
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Add Button
        IconButton(onClick = { showAddScriptDialog = true }) {
          Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cd_add_icon))
        }

        // Delete Button
        IconButton(onClick = { showDeleteConfirmation = true }) {
          Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete_icon))
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        // Button to help generate prompt for LLM.
        FilledTonalButton(
          onClick = { showGenerateLlmPromptBottomSheet = true },
          modifier = Modifier.height(32.dp),
          contentPadding = BUTTON_CONTENT_PADDING,
        ) {
          Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.generate_llm_prompt_button_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Button to paste from clipboard.
        FilledTonalButton(
          onClick = {
            scope.launch {
              val clipEntry = clipboard.getClipEntry()
              val pastedText = clipEntry?.clipData?.getItemAt(0)?.text?.toString()

              if (pastedText != null) {
                selectedScript?.let { curSelectedScript ->
                  onScriptChanged(curSelectedScript, pastedText)
                }
              }
            }
          },
          modifier = Modifier.height(32.dp),
          contentPadding = BUTTON_CONTENT_PADDING,
        ) {
          Icon(
            Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.paste_from_clipboard),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Content Editor
      CursorTrackingTextField(
        minLines = 16,
        initialValue = scriptContent,
        onValueChange = { newContent ->
          selectedScript?.let { curSelectedScript ->
            onScriptChanged(curSelectedScript, newContent)
          }
        },
        monoFont = true,
      )
    }
  }

  // Add Script Dialog
  if (showAddScriptDialog) {
    AlertDialog(
      onDismissRequest = { showAddScriptDialog = false },
      title = { Text(stringResource(R.string.add_script)) },
      text = {
        OutlinedTextField(
          value = newScriptName,
          onValueChange = { newScriptName = it },
          label = { Text(stringResource(R.string.script_name)) },
          isError = scriptContents.containsKey(newScriptName),
          supportingText = {
            if (scriptContents.containsKey(newScriptName)) {
              Text(
                stringResource(R.string.duplicated_script_name),
                color = MaterialTheme.colorScheme.error,
              )
            }
          },
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val trimmedName = newScriptName.trim()
            onScriptAdded(trimmedName)
            newScriptName = ""
            showAddScriptDialog = false
          },
          enabled = !scriptContents.containsKey(newScriptName) && newScriptName.isNotBlank(),
        ) {
          Text(stringResource(R.string.add))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddScriptDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  // Delete Confirmation Dialog
  if (showDeleteConfirmation) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text(stringResource(R.string.delete_script_dialog_title)) },
      text = { Text("Are you sure you want to delete '$selectedScript'?") },
      confirmButton = {
        Button(
          onClick = {
            selectedScript?.let { curSelectedScript -> onScriptDeleted(curSelectedScript) }
            showDeleteConfirmation = false
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.errorTextColor,
              contentColor = Color.White,
            ),
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmation = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  // Generate LLM Prompt Bottom Sheet
  if (showGenerateLlmPromptBottomSheet) {
    val promptCopiedMessage = stringResource(R.string.prompt_copied_message)
    GenerateLlmPromptBottomSheet(
      requirements = requirements,
      curDescription = curDescription,
      onRequirementsChange = onRequirementsChange,
      inputData = inputData,
      onInputDataChange = onInputDataChange,
      outputData = outputData,
      onOutputDataChange = onOutputDataChange,
      onDismiss = { showGenerateLlmPromptBottomSheet = false },
      onLlmPromptGenerated = {
        scope.launch {
          snackbarHostState.showSnackbar(
            message = promptCopiedMessage,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
          )
        }
      },
    )
  }
}
