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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.DownloadAndTryButton
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DownloadModelPanel(
  model: Model,
  task: Task?,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatus?,
  isExpanded: Boolean,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onTryItClicked: () -> Unit,
  onBenchmarkClicked: () -> Unit,
  modifier: Modifier = Modifier,
  showBenchmarkButton: Boolean = false,
) {
  val downloadSucceeded = downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  with(sharedTransitionScope) {
    Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (showBenchmarkButton && downloadSucceeded) {
        // Benchmark button.
        var buttonModifier: Modifier = Modifier.height(42.dp)
        if (isExpanded) {
          buttonModifier = buttonModifier.weight(1f)
        }
        Button(
          modifier =
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "benchmark_button"),
                animatedVisibilityScope = animatedVisibilityScope,
              )
              .then(buttonModifier),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
          contentPadding = PaddingValues(horizontal = 12.dp),
          onClick = onBenchmarkClicked,
        ) {
          val textColor = MaterialTheme.colorScheme.onSecondaryContainer
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Rounded.BarChart, contentDescription = null, tint = textColor)

            if (isExpanded) {
              Text(
                stringResource(R.string.benchmark),
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                autoSize =
                  TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp, stepSize = 1.sp),
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))
      }

      fun isDownloadButtonEnabled(downloadStatus: ModelDownloadStatus?, model: Model): Boolean {
        val downloadFailed = downloadStatus?.status == ModelDownloadStatusType.FAILED
        val isLitertLm = model.runtimeType == RuntimeType.LITERT_LM
        return !downloadFailed || isLitertLm
      }

      if (downloadStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED) {
        var showFulfillDialog by remember { mutableStateOf(false) }

        OutlinedButton(
          onClick = { showFulfillDialog = true },
          modifier = Modifier.height(42.dp),
          contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
          Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Import Custom", color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(8.dp))

        if (showFulfillDialog) {
          ModelFulfillmentDialog(
            model = model,
            onDismiss = { showFulfillDialog = false },
            onUrlPicked = { url ->
              showFulfillDialog = false
              modelManagerViewModel.downloadModel(task = task, model = model, customUrl = url)
            },
            onLocalUriPicked = { uri ->
              showFulfillDialog = false
              modelManagerViewModel.fulfillModelWithLocalUri(model, uri)
            }
          )
        }
      }

      DownloadAndTryButton(
        task = task,
        model = model,
        downloadStatus = downloadStatus,
        enabled = isDownloadButtonEnabled(downloadStatus, model),
        modelManagerViewModel = modelManagerViewModel,
        onClicked = onTryItClicked,
        compact = !isExpanded,
        modifier =
          Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = "download_button"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        modifierWhenExpanded = Modifier.weight(1f),
      )
    }
  }
}

@Composable
fun ModelFulfillmentDialog(
  model: Model,
  onDismiss: () -> Unit,
  onUrlPicked: (String) -> Unit,
  onLocalUriPicked: (Uri) -> Unit,
) {
  var url by remember { mutableStateOf("") }
  var errorMsg by remember { mutableStateOf("") }
  val context = LocalContext.current

  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri != null) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            val displayName = cursor.getString(nameIndex)
            if (displayName != model.downloadFileName) {
              Toast.makeText(context, "File name '$displayName' does not match expected model file '${model.downloadFileName}'", Toast.LENGTH_LONG).show()
            } else {
              onLocalUriPicked(uri)
              return@use
            }
          }
        }
        onDismiss()
      }
    }
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Import Custom Source") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Expected file name: ${model.downloadFileName}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
          value = url,
          onValueChange = { 
            url = it
            errorMsg = ""
          },
          label = { Text("Model URL") },
          isError = errorMsg.isNotEmpty(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        if (errorMsg.isNotEmpty()) {
           Text(errorMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        
        Text("OR", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
          onClick = { 
             filePickerLauncher.launch(arrayOf("*/*"))
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Select Local File")
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (url.isNotBlank() && android.webkit.URLUtil.isValidUrl(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
            val parsedUrl = Uri.parse(url)
            val fileName = parsedUrl.lastPathSegment ?: ""
            if (fileName == model.downloadFileName) {
               onUrlPicked(url)
            } else {
               errorMsg = "URL string must end with ${model.downloadFileName}. Found: '$fileName'"
            }
          } else {
            errorMsg = "Invalid HTTP/HTTPS URL"
          }
        }
      ) { Text("Set URL") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}
