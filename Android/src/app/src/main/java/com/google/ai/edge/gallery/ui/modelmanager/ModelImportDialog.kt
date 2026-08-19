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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.isPixel10
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.huggingface.extractHfUrlInfo
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.importedModel
import com.google.ai.edge.gallery.proto.llmConfig
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import com.google.ai.edge.gallery.ui.common.ensureValidFileName
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.common.isHttpOrHttps
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGModelImportDialog"

private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    val accelerators = mutableListOf(Accelerator.CPU, Accelerator.NPU)
    accelerators.toList()
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 1f,
      sliderMax = 100f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_IMAGE, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_AUDIO, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_TINY_GARDEN, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_MOBILE_ACTIONS, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_SPECULATIVE_DECODING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )

@Composable
fun ModelImportDialog(
  uri: Uri,
  huggingFaceApiClient: HuggingFaceApiClient,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
  defaultValues: Map<ConfigKey, Any> = emptyMap(),
  accessToken: String? = null,
) {
  val context = LocalContext.current
  val info = remember { getFileSizeAndDisplayNameFromUri(context = context, uri = uri) }
  var fileSize by remember { mutableLongStateOf(info.first) }
  val fileName by remember { mutableStateOf(ensureValidFileName(info.second)) }

  // Indicates that the file size is still being fetched and we should disable the import button
  // until it's done.
  var isFetchingSize by remember { mutableStateOf(isHttpOrHttps(uri)) }

  LaunchedEffect(uri) {
    if (isHttpOrHttps(uri)) {
      isFetchingSize = true
      try {
        val downloadUrl = getDownloadUrl(uri)
        val size =
          fetchFileSize(
            urlStr = downloadUrl,
            huggingFaceAccessToken = accessToken,
            hfApiClient = huggingFaceApiClient,
          )
        if (size > 0L) {
          fileSize = size
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error fetching file size for $uri", e)
      } finally {
        isFetchingSize = false
      }
    }
  }

  val initialValues: Map<String, Any> = remember {
    mutableMapOf<String, Any>().apply {
      for (config in IMPORT_CONFIGS_LLM) {
        put(config.key.label, config.defaultValue)
      }
      put(ConfigKeys.NAME.label, fileName)
      // TODO: support other types.
      put(ConfigKeys.MODEL_TYPE.label, "LLM")

      for ((key, value) in defaultValues) {
        put(key.label, value)
      }
    }
  }
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }

  Dialog(onDismissRequest = onDismiss) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Default configs for users to set.
          ConfigEditorsPanel(configs = IMPORT_CONFIGS_LLM, values = values)
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Cancel button.
          TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) }

          // Import button
          Button(
            // Disable the import button while fetching file size for URI.
            enabled = !isFetchingSize,
            onClick = {
              val supportedAccelerators =
                (convertValueToTargetType(
                    value = values.get(ConfigKeys.COMPATIBLE_ACCELERATORS.label)!!,
                    valueType = ValueType.STRING,
                  )
                    as String)
                  .split(",")
              val defaultMaxTokens =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_MAX_TOKENS.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopk =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPK.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopp =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPP.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val defaultTemperature =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TEMPERATURE.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val supportImage =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_IMAGE.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportAudio =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_AUDIO.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportTinyGarden =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_TINY_GARDEN.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportMobileActions =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_MOBILE_ACTIONS.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportThinking =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_THINKING.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportSpeculativeDecoding =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_SPECULATIVE_DECODING.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val downloadUrl = getDownloadUrl(uri)
              val importedModel = importedModel {
                this.fileName = fileName
                this.fileSize = fileSize
                this.url = if (isHttpOrHttps(uri)) downloadUrl else ""
                this.llmConfig = llmConfig {
                  compatibleAccelerators += supportedAccelerators
                  this.defaultMaxTokens = defaultMaxTokens
                  this.defaultTopk = defaultTopk
                  this.defaultTopp = defaultTopp
                  this.defaultTemperature = defaultTemperature
                  this.supportImage = supportImage
                  this.supportAudio = supportAudio
                  this.supportMobileActions = supportMobileActions
                  this.supportThinking = supportThinking
                  this.supportTinyGarden = supportTinyGarden
                  this.supportSpeculativeDecoding = supportSpeculativeDecoding
                }
              }

              onDone(importedModel)
            },
          ) {
            if (isFetchingSize) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(R.string.import_action))
              }
            } else {
              Text(stringResource(R.string.import_action))
            }
          }
        }
      }
    }
  }
}

@Composable
fun ModelImportingDialog(
  uri: Uri,
  info: ImportedModel,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
) {
  var error by remember { mutableStateOf("") }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var progress by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    // Import.
    importModel(
      context = context,
      coroutineScope = coroutineScope,
      fileName = info.fileName,
      fileSize = info.fileSize,
      uri = uri,
      onDone = { onDone(info) },
      onProgress = { progress = it },
      onError = { error = it },
    )
  }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = onDismiss,
  ) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        // No error.
        if (error.isEmpty()) {
          // Progress bar.
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${info.fileName} (${info.fileSize.humanReadableSize()})",
              style = MaterialTheme.typography.labelSmall,
            )
            val animatedProgress = remember { Animatable(0f) }
            LinearProgressIndicator(
              progress = { animatedProgress.value },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            LaunchedEffect(progress) {
              animatedProgress.animateTo(progress, animationSpec = tween(150))
            }
          }
        }
        // Has error.
        else {
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Error,
              contentDescription = stringResource(R.string.cd_error),
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              error,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDismiss() }) { Text(stringResource(R.string.close)) }
          }
        }
      }
    }
  }
}

private fun importModel(
  context: Context,
  coroutineScope: CoroutineScope,
  fileName: String,
  fileSize: Long,
  uri: Uri,
  onDone: () -> Unit,
  onProgress: (Float) -> Unit,
  onError: (String) -> Unit,
) {
  // TODO: handle error.
  coroutineScope.launch(Dispatchers.IO) {
    // If it's a model from the web, we don't need to copy the file over.
    if (isHttpOrHttps(uri)) {
      Log.d(TAG, "importing web model from $uri. File name: $fileName. File size: $fileSize")
      // Simulate a quick progress animation to show the user it's being added
      // for (i in 1..10) {
      //   kotlinx.coroutines.delay(50)
      //   onProgress(i.toFloat() / 10f)
      // }
      Log.d(TAG, "import done for web model")
      onDone()
      return@launch
    }

    // Get the last component of the uri path as the imported file name.
    val decodedUri = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8.name())
    Log.d(TAG, "importing model from $decodedUri. File name: $fileName. File size: $fileSize")

    // Create <app_external_dir>/imports if not exist.
    val importsDir = File(context.getExternalFilesDir(null), IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    // Import by copying the file over.
    val outputFile = File(context.getExternalFilesDir(null), "$IMPORTS_DIR/$fileName")
    val outputStream = FileOutputStream(outputFile)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead: Int
    var lastSetProgressTs: Long = 0
    var importedBytes = 0L
    val inputStream = context.contentResolver.openInputStream(uri)
    try {
      if (inputStream != null) {
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          importedBytes += bytesRead

          // Report progress every 200 ms.
          val curTs = System.currentTimeMillis()
          if (curTs - lastSetProgressTs > 200) {
            Log.d(TAG, "importing progress: $importedBytes, $fileSize")
            lastSetProgressTs = curTs
            if (fileSize != 0L) {
              onProgress(importedBytes.toFloat() / fileSize.toFloat())
            }
          }
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      onError(e.message ?: context.getString(R.string.failed_to_import))
      return@launch
    } finally {
      inputStream?.close()
      outputStream.close()
    }
    Log.d(TAG, "import done")
    onProgress(1f)
    onDone()
  }
}

private fun getFileSizeAndDisplayNameFromUri(context: Context, uri: Uri): Pair<Long, String> {
  if (isHttpOrHttps(uri)) {
    return Pair(0L, uri.lastPathSegment ?: "")
  }
  val contentResolver = context.contentResolver
  var fileSize = 0L
  var displayName = ""

  try {
    contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
          fileSize = cursor.getLong(sizeIndex)

          val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
          displayName = cursor.getString(nameIndex)
        }
      }
  } catch (e: Exception) {
    e.printStackTrace()
    return Pair(0L, "")
  }

  return Pair(fileSize, displayName)
}

// Get the download url for the model.
private fun getDownloadUrl(uri: Uri): String {
  return if (uri.toString().contains("huggingface.co") && uri.toString().contains("/blob/")) {
    uri.toString().replaceFirst("/blob/", "/resolve/")
  } else {
    uri.toString()
  }
}

/**
 * Fetches the total file size of a remote model URL without downloading the full payload.
 *
 * For Hugging Face URLs, queries the Hugging Face REST API via [HuggingFaceApiClient]. For
 * non-Hugging Face HTTP URLs, probes the endpoint directly via HTTP Range GET.
 *
 * @param urlStr Direct remote file URL to inspect.
 * @param huggingFaceAccessToken Optional Bearer authentication token for Hugging Face.
 * @return Total file size in bytes, or `0L` if size could not be determined.
 */
private suspend fun fetchFileSize(
  urlStr: String,
  huggingFaceAccessToken: String? = null,
  hfApiClient: HuggingFaceApiClient,
): Long =
  withContext(Dispatchers.IO) {
    if (HuggingFaceApiClient.isHuggingFaceUrl(urlStr)) {
      return@withContext fetchHuggingFaceFileSize(
        urlStr = urlStr,
        huggingFaceAccessToken = huggingFaceAccessToken,
        hfApiClient = hfApiClient,
      )
    }
    return@withContext fetchHttpFileSize(urlStr)
  }

/** Fetches the file size using the Hugging Face API client. */
private suspend fun fetchHuggingFaceFileSize(
  urlStr: String,
  huggingFaceAccessToken: String? = null,
  hfApiClient: HuggingFaceApiClient,
): Long {
  val urlInfo = extractHfUrlInfo(urlStr)
  val modelId = urlInfo.modelId
  val fileName = urlInfo.fileName
  if (modelId != null && fileName != null) {
    try {
      val size =
        hfApiClient.getModelFileSize(modelId, fileName, accessToken = huggingFaceAccessToken)
      if (size != null && size > 0L) {
        return size
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "HuggingFaceApiClient lookup failed for $urlStr", e)
    }
  }
  return 0L
}

/** Fetches the file size from a generic, non-Hugging-Face HTTP URL via Range GET. */
private suspend fun fetchHttpFileSize(urlStr: String): Long {
  val url = runCatching { URL(urlStr) }.getOrNull() ?: return 0L
  val connection =
    runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return 0L
  connection.requestMethod = "GET"

  // Request only the first 1 byte (bytes=0-0) to inspect file headers without downloading the
  // entire payload.
  connection.setRequestProperty("Range", "bytes=0-0")

  try {
    connection.connect()

    val isResponseOk = connection.responseCode in 200..299
    if (isResponseOk) {
      // HTTP 206 Partial Content returns "Content-Range: bytes 0-0/<total_bytes>".
      val contentRange = connection.getHeaderField("Content-Range")
      if (contentRange != null) {
        val totalFromRange = contentRange.substringAfter("/").trim().toLongOrNull()
        if (totalFromRange != null && totalFromRange > 0L) {
          return totalFromRange
        }
      }
      // Fallback to Content-Length if the server returned HTTP 200 OK without byte ranges.
      val contentLength = connection.contentLengthLong
      if (contentLength > 0L) {
        return contentLength
      }
    }
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.w(TAG, "HTTP probe failed for $urlStr", e)
  } finally {
    connection.disconnect()
  }
  return 0L
}
