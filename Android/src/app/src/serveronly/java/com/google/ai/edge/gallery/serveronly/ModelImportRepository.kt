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

package com.google.ai.edge.gallery.serveronly

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.server.ImportedModelMeta
import com.google.ai.edge.gallery.server.ImportedModelStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

/**
 * Bridges the standalone server APK with the full Edge Gallery app's [ModelExportProvider].
 *
 * Workflow:
 * 1. [listAvailable] queries the provider for models the full app has already downloaded.
 * 2. [importModel] streams each file the user selects into the standalone APK's own private
 *    storage at `{externalFilesDir}/__imports/{normalizedName}/{version}/{fileName}`.
 * 3. [listImported] / [setDefaultModel] / [getDefaultModel] manage which imported model
 *    [com.google.ai.edge.gallery.server.LlmServerService] should auto-load on start.
 */
object ModelImportRepository {
  private const val TAG = "AGModelImportRepository"

  /** Authority of the full app's exported provider. Must match `ModelExportProvider.AUTHORITY`. */
  private const val PROVIDER_AUTHORITY = "com.google.aiedge.gallery.models"
  private const val PROVIDER_LIST_URI = "content://$PROVIDER_AUTHORITY/list"
  private const val PROVIDER_FILE_URI_FORMAT = "content://$PROVIDER_AUTHORITY/file/%s/%s/%s"

  // Cursor columns — kept in sync with ModelExportProvider.
  private const val COL_NAME = "name"
  private const val COL_NORMALIZED_NAME = "normalized_name"
  private const val COL_VERSION = "version"
  private const val COL_DOWNLOAD_FILE_NAME = "download_file_name"
  private const val COL_SIZE_IN_BYTES = "size_in_bytes"
  private const val COL_EXTRA_FILES_JSON = "extra_files_json"

  /** Description of one model offered by the remote provider. */
  data class RemoteModel(
    val name: String,
    val normalizedName: String,
    val version: String,
    val downloadFileName: String,
    val sizeInBytes: Long,
    val extraFileNames: List<String>,
  ) {
    /** Total bytes of primary + extra files (extra sizes unknown — primary only). */
    val totalEstimatedBytes: Long get() = sizeInBytes
  }

  /**
   * Queries the full app's provider for the list of available (downloaded) models.
   * Returns an empty list if the provider isn't installed or didn't return any rows.
   */
  fun listAvailable(context: Context): List<RemoteModel> {
    val resolver: ContentResolver = context.contentResolver
    val out = mutableListOf<RemoteModel>()
    try {
      resolver.query(Uri.parse(PROVIDER_LIST_URI), null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndexOrThrow(COL_NAME)
        val normalizedIdx = cursor.getColumnIndexOrThrow(COL_NORMALIZED_NAME)
        val versionIdx = cursor.getColumnIndexOrThrow(COL_VERSION)
        val downloadIdx = cursor.getColumnIndexOrThrow(COL_DOWNLOAD_FILE_NAME)
        val sizeIdx = cursor.getColumnIndexOrThrow(COL_SIZE_IN_BYTES)
        val extraIdx = cursor.getColumnIndex(COL_EXTRA_FILES_JSON)
        val gson = Gson()
        val listType = object : TypeToken<List<String>>() {}.type
        while (cursor.moveToNext()) {
          val extraJson = if (extraIdx >= 0) cursor.getString(extraIdx) else null
          val extras: List<String> =
            extraJson?.let {
              try {
                gson.fromJson<List<String>>(it, listType) ?: emptyList()
              } catch (_: Throwable) {
                emptyList()
              }
            } ?: emptyList()
          out.add(
            RemoteModel(
              name = cursor.getString(nameIdx),
              normalizedName = cursor.getString(normalizedIdx),
              version = cursor.getString(versionIdx),
              downloadFileName = cursor.getString(downloadIdx),
              sizeInBytes = cursor.getLong(sizeIdx),
              extraFileNames = extras,
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to query provider — is the full Edge Gallery app installed?", t)
    }
    return out
  }

  /**
   * Imports one model from the provider. For each file (primary + extras), opens an InputStream
   * via the provider and streams it into local storage. Reports byte-level progress via
   * [onProgress] in the range 0f..1f. Returns true on success.
   */
  fun importModel(
    context: Context,
    remote: RemoteModel,
    onProgress: (Float) -> Unit = {},
  ): Boolean {
    val externalDir = context.getExternalFilesDir(null) ?: return false
    val targetDir =
      File(externalDir, "$IMPORTS_DIR/${remote.normalizedName}/${remote.version}")
    if (!targetDir.exists() && !targetDir.mkdirs()) {
      Log.e(TAG, "Failed to create import directory: $targetDir")
      return false
    }

    val allFiles = listOf(remote.downloadFileName) + remote.extraFileNames
    val totalFiles = allFiles.size
    val resolver = context.contentResolver

    for ((index, fileName) in allFiles.withIndex()) {
      val uri = Uri.parse(PROVIDER_FILE_URI_FORMAT.format(remote.normalizedName, remote.version, fileName))
      val target = File(targetDir, fileName)

      try {
        resolver.openInputStream(uri).use { input ->
          if (input == null) {
            Log.e(TAG, "Provider returned null InputStream for $uri")
            return false
          }
          FileOutputStream(target).use { output ->
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            val sizeForFile = if (index == 0) remote.sizeInBytes else 0L
            while (true) {
              val n = input.read(buf)
              if (n <= 0) break
              output.write(buf, 0, n)
              copied += n
              if (sizeForFile > 0L) {
                val overall = (index.toFloat() + (copied.toFloat() / sizeForFile)) / totalFiles
                onProgress(overall.coerceIn(0f, 1f))
              }
            }
          }
        }
        onProgress(((index + 1).toFloat() / totalFiles).coerceIn(0f, 1f))
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to copy '$fileName' for model '${remote.name}'", t)
        return false
      }
    }

    val meta =
      ImportedModelMeta(
        name = remote.name,
        normalizedName = remote.normalizedName,
        version = remote.version,
        downloadFileName = remote.downloadFileName,
        extraFileNames = remote.extraFileNames,
      )
    val current = ImportedModelStore.readImported(context).filterNot { it.name == meta.name }
    ImportedModelStore.writeImported(context, current + meta)
    return true
  }

  /** Returns the persisted list of models the standalone APK has imported. */
  fun listImported(context: Context): List<ImportedModelMeta> =
    ImportedModelStore.readImported(context)

  /** Sets which imported model the server should auto-load on startup. */
  fun setDefaultModel(context: Context, name: String?) {
    val prefs =
      context.getSharedPreferences(ImportedModelStore.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().apply {
      if (name == null) remove(ImportedModelStore.PREF_DEFAULT_MODEL_NAME)
      else putString(ImportedModelStore.PREF_DEFAULT_MODEL_NAME, name)
      apply()
    }
  }

  fun getDefaultModel(context: Context): String? {
    val prefs =
      context.getSharedPreferences(ImportedModelStore.PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(ImportedModelStore.PREF_DEFAULT_MODEL_NAME, null)
  }
}
