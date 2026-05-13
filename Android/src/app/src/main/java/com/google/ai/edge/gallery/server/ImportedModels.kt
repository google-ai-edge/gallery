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

package com.google.ai.edge.gallery.server

import android.content.Context
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Metadata describing a model that has been imported into the standalone server APK from the
 * full Edge Gallery app via [ModelExportProvider]. Persisted as JSON inside the app's private
 * `filesDir` so [LlmServerService] can rebuild a [Model] for headless inference at boot time.
 */
data class ImportedModelMeta(
  /** Display name (e.g. "Gemma-3n-E2B-it"). */
  val name: String,
  /** Filesystem-safe form of [name], used as a directory name. */
  val normalizedName: String,
  /** Model version string (typically a HuggingFace commit hash). */
  val version: String,
  /** Primary file (e.g. "model.bin" or "model.tflite"). */
  val downloadFileName: String,
  /** Names of any extra files copied alongside the primary file (tokenizer.json, etc.). */
  val extraFileNames: List<String> = emptyList(),
  /** Whether this is an LLM model. Currently always true since the server only serves LLMs. */
  val isLlm: Boolean = true,
)

/**
 * Constants forming the contract between [LlmServerService] (which auto-loads a default model
 * for the standalone server APK) and the serveronly flavor's `ModelImportRepository` (which
 * writes the imported-models manifest and the default-model preference).
 *
 * Defined here in the `main` sourceset so both flavors can read/write the same files.
 */
object ImportedModelStore {
  /** SharedPreferences file name used for server-related settings. */
  const val PREFS_NAME = "edge_gallery_server_prefs"

  /** Preferences key holding the name of the default model to auto-load. */
  const val PREF_DEFAULT_MODEL_NAME = "default_model_name"

  /** Preferences key holding the listening port for the embedded HTTP server. */
  const val PREF_SERVER_PORT = "server_port"

  /** JSON file inside `context.filesDir` that lists every model imported so far. */
  const val IMPORTED_MODELS_FILE = "imported_models.json"

  fun readImported(context: Context): List<ImportedModelMeta> {
    val file = File(context.filesDir, IMPORTED_MODELS_FILE)
    if (!file.exists()) return emptyList()
    return try {
      val type = object : TypeToken<List<ImportedModelMeta>>() {}.type
      Gson().fromJson<List<ImportedModelMeta>>(file.readText(), type) ?: emptyList()
    } catch (_: JsonSyntaxException) {
      emptyList()
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun writeImported(context: Context, models: List<ImportedModelMeta>) {
    val file = File(context.filesDir, IMPORTED_MODELS_FILE)
    file.writeText(Gson().toJson(models))
  }

  /**
   * Build a minimal [Model] that points at the on-disk path of an imported model so it can be
   * passed to `LlmChatModelHelper.initialize()`. Uses [Model.localFileRelativeDirPathOverride] so
   * `Model.getPath()` returns `{externalFilesDir}/__imports/{normalizedName}/{version}/{file}`.
   *
   * Note: no trailing separator on the override — `Model.getPath` already inserts one between
   * the override and the filename via `joinToString(File.separator)`.
   */
  fun toModel(meta: ImportedModelMeta): Model {
    val model =
      Model(
        name = meta.name,
        version = meta.version,
        downloadFileName = meta.downloadFileName,
        isLlm = meta.isLlm,
        runtimeType = RuntimeType.LITERT_LM,
        localFileRelativeDirPathOverride =
          listOf(IMPORTS_DIR, meta.normalizedName, meta.version).joinToString(File.separator),
      )
    model.preProcess()
    return model
  }
}
