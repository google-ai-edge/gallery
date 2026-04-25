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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.openai.OpenAiServerService
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import kotlin.text.endsWith
import kotlin.text.lowercase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGGlobalMM"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalModelManager(
  viewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  onModelSelected: (Task, Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()
  val builtInModels = remember { mutableStateListOf<Model>() }
  val importedModels = remember { mutableStateListOf<Model>() }
  val taskCandidates = remember { mutableStateListOf<Task>() }
  var modelForTaskCandidate by remember { mutableStateOf<Model?>(null) }
  var showTaskSelectorBottomSheet by remember { mutableStateOf(false) }
  var showImportModelSheet by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  var showUnsupportedWebModelDialog by remember { mutableStateOf(false) }
  val selectedLocalModelFileUri = remember { mutableStateOf<Uri?>(null) }
  val selectedImportedModelInfo = remember { mutableStateOf<ImportedModel?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val modelItemExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf("All") }
  val filterOptions = listOf("All", "Downloaded", "Media", "Custom")

  val promoId = "gm4_banner"
  var showPromo by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    showPromo = !viewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)
  }

  val filePickerLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val fileName = getFileName(context = context, uri = uri)
          Log.d(TAG, "Selected file: $fileName")
          // Show warning for model file types other than .task and .litertlm.
          if (fileName != null && !fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) {
            showUnsupportedFileTypeDialog = true
          }
          // Show warning for web-only model (by checking if the file name has "-web" in it).
          else if (fileName != null && fileName.lowercase().contains("-web")) {
            showUnsupportedWebModelDialog = true
          } else {
            selectedLocalModelFileUri.value = uri
            showImportDialog = true
          }
        } ?: run { Log.d(TAG, "No file selected or URI is null.") }
      } else {
        Log.d(TAG, "File picking cancelled.")
      }
    }

  LaunchedEffect(uiState.modelImportingUpdateTrigger) {
    val allowlistModels = viewModel.allowlistModels
    val allowlistOrderMap = allowlistModels.withIndex().associate { it.value.name to it.index }

    val sortedModels =
      viewModel
        .getAllModels()
        // Filter to include only top-level models (those without a parent).
        .filter { it.parentModelName.isNullOrEmpty() }
        .sortedWith(
          compareBy<Model> { model ->
              // Sort by the index in allowlistModels. Models not in the allowlist come last.
              allowlistOrderMap[model.name] ?: Int.MAX_VALUE
            }
            .thenBy { model ->
              // If not in the allowlist, sort by their names.
              model.name
            }
        )
    builtInModels.clear()
    builtInModels.addAll(sortedModels.filter { !it.imported })
    importedModels.clear()
    importedModels.addAll(sortedModels.filter { it.imported })
  }

  // Calculate model variants by grouping models with a parentModelName.
  val modelVariants by
    remember(uiState.modelImportingUpdateTrigger) {
      derivedStateOf {
        val allModels = uiState.tasks.flatMap { it.models }
        allModels.filter { it.parentModelName != null }.groupBy { it.parentModelName!! }
      }
    }

  val filteredBuiltInModels by remember(searchQuery, selectedFilter, builtInModels, uiState.modelDownloadStatus) {
    derivedStateOf {
      builtInModels.filter { model ->
        val matchesSearch = searchQuery.isEmpty() || model.name.contains(searchQuery, ignoreCase = true) || 
                            model.info.contains(searchQuery, ignoreCase = true) == true
        val matchesFilter = when (selectedFilter) {
          "Downloaded" -> uiState.modelDownloadStatus[model.name]?.status == com.google.ai.edge.gallery.data.ModelDownloadStatusType.SUCCEEDED
          "Media" -> uiState.tasks.any { task -> 
            (task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_IMAGE || task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_AUDIO) && 
            task.models.any { it.name == model.name } 
          }
          "Custom" -> false // Built-in models are never custom/imported
          else -> true
        }
        matchesSearch && matchesFilter
      }
    }
  }

  val filteredImportedModels by remember(searchQuery, selectedFilter, importedModels, uiState.modelDownloadStatus) {
    derivedStateOf {
      importedModels.filter { model ->
        val matchesSearch = searchQuery.isEmpty() || model.name.contains(searchQuery, ignoreCase = true) || 
                            model.info.contains(searchQuery, ignoreCase = true) == true
        val matchesFilter = when (selectedFilter) {
          "Downloaded" -> true // Imported models are already downloaded
          "Media" -> uiState.tasks.any { task -> 
            (task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_IMAGE || task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_AUDIO) && 
            task.models.any { it.name == model.name } 
          }
          "Custom" -> true // All imported models are custom
          else -> true
        }
        matchesSearch && matchesFilter
      }
    }
  }

  val defaultExpandedModelName by remember(uiState.selectedModel, uiState.modelInitializationStatus) {
    derivedStateOf {
      val selectedModel = uiState.selectedModel
      when {
        selectedModel.name.isNotEmpty() &&
          selectedModel.name != com.google.ai.edge.gallery.data.EMPTY_MODEL.name -> selectedModel.name
        else ->
          uiState.modelInitializationStatus.entries
            .firstOrNull { it.value.status == ModelInitializationStatusType.INITIALIZED }
            ?.key
      }
    }
  }

  val handleClickModel: (Model) -> Unit = { model ->
    val uiState = viewModel.uiState.value
    val isInitialized = uiState.modelInitializationStatus[model.name]?.status == com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType.INITIALIZED
    val tasks = uiState.tasks
    val tasksForModel = tasks.filter { task -> task.models.any { it.name == model.name } }
    if (tasksForModel.isNotEmpty()) {
      if (isInitialized) {
        viewModel.cleanupModel(context, tasksForModel[0], model)
      } else {
        viewModel.selectModel(model)
        viewModel.initializeModel(context, tasksForModel[0], model)
      }
    }
  }

  // No BackHandler needed since this is now rendered as a tab, not a dialog.

  Scaffold(
    modifier = modifier,
    topBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface)
          .padding(horizontal = 20.dp, vertical = 12.dp)
          .padding(top = 8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          Column {
            Text(
              text = "Models",
              style = MaterialTheme.typography.headlineLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Download, import, and load native\nmodels into memory.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Button(
            onClick = { showImportModelSheet = true },
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Import", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    },
  ) { innerPadding ->
    Box() {
      LazyColumn(
        modifier =
          Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = innerPadding.calculateTopPadding()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding =
          PaddingValues(top = 16.dp, bottom = innerPadding.calculateBottomPadding() + 80.dp),
      ) {
        item(key = "filter_chips") {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
          ) {
            filterOptions.forEach { option ->
              val isSelected = selectedFilter == option
              androidx.compose.material3.FilterChip(
                selected = isSelected,
                onClick = { selectedFilter = option },
                label = { Text(option, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (isSelected) {
                  { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = if (!isSelected) {
                  androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                  )
                } else null,
              )
            }
          }
        }

        items(filteredBuiltInModels) { model ->
          val expanded =
            modelItemExpandedStates.getOrDefault(model.name, model.name == defaultExpandedModelName)
          ModelItem(
            model = model,
            modelVariants = modelVariants.getOrDefault(model.name, listOf()),
            task = null,
            modelManagerViewModel = viewModel,
            onModelClicked = handleClickModel,
            onBenchmarkClicked = onBenchmarkClicked,
            expanded = expanded,
            showBenchmarkButton = false,
            onExpanded = { modelItemExpandedStates[model.name] = it },
          )
        }

        // Imported models.
        if (filteredImportedModels.isNotEmpty()) {
          item(key = "imported_models_label") {
            Text(
              stringResource(R.string.model_list_imported_models_title),
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.padding(horizontal = 16.dp).padding(top = 32.dp, bottom = 8.dp),
            )
          }
        }
        items(filteredImportedModels) { model ->
          ModelItem(
            model = model,
            task = null,
            modelManagerViewModel = viewModel,
            onModelClicked = handleClickModel,
            onBenchmarkClicked = onBenchmarkClicked,
            expanded =
              modelItemExpandedStates.getOrDefault(
                model.name,
                model.name == defaultExpandedModelName,
              ),
            showBenchmarkButton = false,
            onExpanded = { modelItemExpandedStates[model.name] = it },
          )
        }
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(alignment = Alignment.BottomCenter).padding(bottom = 32.dp),
      )

      // Gradient overlay at the bottom.
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(innerPadding.calculateBottomPadding())
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
              )
            )
            .align(Alignment.BottomCenter)
      )
    }
  }

  if (showTaskSelectorBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showTaskSelectorBottomSheet = false },
      sheetState = sheetState,
    ) {
      Column(
        modifier = Modifier.padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.model_manager_select_task_title),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp).padding(start = 16.dp),
        )
        for (task in taskCandidates) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  val model = modelForTaskCandidate
                  if (model != null) {
                    onModelSelected(task, model)
                  }
                  scope.launch {
                    sheetState.hide()
                    showTaskSelectorBottomSheet = false
                  }
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
          ) {
            Text(
              task.label,
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.titleMedium,
            )
            TaskIcon(task = task, width = 40.dp)
          }
        }
      }
    }
  }

  // Import model bottom sheet.
  var showRemoteImportDialog by remember { mutableStateOf(false) }
  if (showImportModelSheet) {
    ModalBottomSheet(onDismissRequest = { showImportModelSheet = false }, sheetState = sheetState) {
      Text(
        "Import model",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
      )
      val cbImportFromLocalFile = stringResource(R.string.cd_import_model_from_local_file_button)
      Box(
        modifier =
          Modifier.clickable {
              scope.launch {
                // Give it sometime to show the click effect.
                delay(200)
                showImportModelSheet = false

                // Show file picker.
                val intent =
                  Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    // Single select.
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                  }
                filePickerLauncher.launch(intent)
              }
            }
            .semantics {
              role = Role.Button
              contentDescription = cbImportFromLocalFile
            }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
          Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null)
          Text("From local model file", modifier = Modifier.clearAndSetSemantics {})
        }
      }
      // From remote URL option
      Box(
        modifier =
          Modifier.clickable {
              scope.launch {
                delay(200)
                showImportModelSheet = false
                showRemoteImportDialog = true
              }
            }
            .semantics {
              role = Role.Button
              contentDescription = "Import model from remote URL"
            }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
          Icon(
            Icons.Outlined.Link,
            contentDescription = null,
          )
          Text("From remote URL", modifier = Modifier.clearAndSetSemantics {})
        }
      }
    }
  }

  // Import dialog
  if (showImportDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedImportedModelInfo.value = info
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }

  // Remote URL import dialog
  if (showRemoteImportDialog) {
    ModelRemoteImportDialog(
      onDismiss = { showRemoteImportDialog = false },
      onDone = { info, url ->
        // For URL-based imports, we register the model metadata directly
        // The actual download will happen through the normal download flow
        viewModel.addImportedLlmModel(info = info)
        showRemoteImportDialog = false
        scope.launch { snackbarHostState.showSnackbar("Model registered from URL") }
      },
    )
  }

  // Importing in progress dialog.
  if (showImportingDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      selectedImportedModelInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            viewModel.addImportedLlmModel(info = it)
            showImportingDialog = false

            // Show a snack bar for successful import.
            scope.launch { snackbarHostState.showSnackbar("Model imported successfully") }
          },
        )
      }
    }
  }

  // Alert dialog for unsupported file type.
  if (showUnsupportedFileTypeDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      onDismissRequest = { showUnsupportedFileTypeDialog = false },
      title = { Text("Unsupported file type") },
      text = { Text("Only \".task\" or \".litertlm\" file type is supported.") },
      confirmButton = {
        Button(onClick = { showUnsupportedFileTypeDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // Alert dialog for unsupported web model.
  if (showUnsupportedWebModelDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      onDismissRequest = { showUnsupportedWebModelDialog = false },
      title = { Text("Unsupported model type") },
      text = { Text("Looks like the model is a web-only model and is not supported by the app.") },
      confirmButton = {
        Button(onClick = { showUnsupportedWebModelDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

// Helper function to get the file name from a URI
private fun getFileName(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          return cursor.getString(nameIndex)
        }
      }
    }
  } else if (uri.scheme == "file") {
    return uri.lastPathSegment
  }
  return null
}

@Composable
fun OpenAiServerPanel() {
  val context = LocalContext.current
  val isRunning by OpenAiServerService.isRunning.collectAsState()
  val localUrl by OpenAiServerService.localUrl.collectAsState()
  val publicUrl by OpenAiServerService.publicUrl.collectAsState()
  val clipboardManager = LocalClipboardManager.current
  var useTunnel by remember { mutableStateOf(false) }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(bottom = 16.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text("OpenAI API Server", style = MaterialTheme.typography.titleMedium)
        Text(
          if (isRunning) "Status: Running" else "Status: Stopped",
          style = MaterialTheme.typography.bodySmall,
          color =
            if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = isRunning,
        onCheckedChange = { checked ->
          if (checked) {
            OpenAiServerService.startService(context, useTunnel)
          } else {
            OpenAiServerService.stopService(context)
          }
        },
      )
    }

    if (isRunning) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Local URL: $localUrl", style = MaterialTheme.typography.bodySmall)
        Button(
          onClick = { localUrl?.let { clipboardManager.setText(AnnotatedString(it)) } },
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          modifier = Modifier.height(32.dp),
        ) {
          Text("Copy", style = MaterialTheme.typography.labelSmall)
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("Internet Access (Tunnel)", style = MaterialTheme.typography.bodyMedium)
          if (publicUrl != null) {
            Text("Public URL: $publicUrl", style = MaterialTheme.typography.bodySmall)
          }
        }
        Switch(
          checked = useTunnel,
          onCheckedChange = { checked ->
            useTunnel = checked
            // Restart service with tunnel option
            OpenAiServerService.startService(context, useTunnel)
          },
        )
      }

      if (publicUrl != null) {
        Button(
          onClick = { publicUrl?.let { clipboardManager.setText(AnnotatedString(it)) } },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Copy Public URL")
        }
      }
    }
  }
}

