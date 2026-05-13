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

package com.google.ai.edge.gallery.server.export

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * Exports downloaded models to other Edge Gallery apps signed with the same certificate.
 *
 * Authority: `com.google.aiedge.gallery.models`
 *
 * URIs:
 * - `content://com.google.aiedge.gallery.models/list` — `query()` returns one row per
 *   downloaded model with columns [COL_NAME], [COL_NORMALIZED_NAME], [COL_VERSION],
 *   [COL_DOWNLOAD_FILE_NAME], [COL_SIZE_IN_BYTES], [COL_EXTRA_FILES_JSON].
 * - `content://com.google.aiedge.gallery.models/file/{normalizedName}/{version}/{fileName}` —
 *   `openFile()` returns a read-only [ParcelFileDescriptor] for the requested model file.
 *
 * Discovery strategy: read the cached model_allowlist.json the full app maintains in its
 * external files dir, materialise each entry via [com.google.ai.edge.gallery.data.AllowedModel.toModel],
 * and only surface those whose primary file is present on disk (i.e. download completed).
 */
class ModelExportProvider : ContentProvider() {

  companion object {
    private const val TAG = "AGModelExportProvider"
    const val AUTHORITY = "com.google.aiedge.gallery.models"

    const val COL_NAME = "name"
    const val COL_NORMALIZED_NAME = "normalized_name"
    const val COL_VERSION = "version"
    const val COL_DOWNLOAD_FILE_NAME = "download_file_name"
    const val COL_SIZE_IN_BYTES = "size_in_bytes"
    const val COL_EXTRA_FILES_JSON = "extra_files_json"

    private const val MATCH_LIST = 1
    private const val MATCH_FILE = 2

    /** Filename of the on-disk allowlist cache; mirrors ModelManagerViewModel. */
    private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
  }

  private val matcher =
    UriMatcher(UriMatcher.NO_MATCH).apply {
      addURI(AUTHORITY, "list", MATCH_LIST)
      // file/{normalizedName}/{version}/{fileName}
      addURI(AUTHORITY, "file/*/*/*", MATCH_FILE)
    }

  override fun onCreate(): Boolean = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    if (matcher.match(uri) != MATCH_LIST) return null

    val ctx = context ?: return null
    val cursor =
      MatrixCursor(
        arrayOf(
          COL_NAME,
          COL_NORMALIZED_NAME,
          COL_VERSION,
          COL_DOWNLOAD_FILE_NAME,
          COL_SIZE_IN_BYTES,
          COL_EXTRA_FILES_JSON,
        )
      )

    val allowlist = readAllowlist(ctx) ?: return cursor
    val gson = Gson()

    for (allowed in allowlist.models) {
      if (allowed.disabled == true) continue
      val model: Model =
        try {
          allowed.toModel()
        } catch (t: Throwable) {
          Log.w(TAG, "Failed to materialise model '${allowed.name}'", t)
          continue
        }
      val primaryPath = model.getPath(ctx)
      if (!File(primaryPath).exists()) continue

      val extraFiles = model.extraDataFiles.map { it.downloadFileName }
      cursor.addRow(
        arrayOf(
          model.name,
          model.normalizedName,
          model.version,
          model.downloadFileName,
          model.sizeInBytes,
          gson.toJson(extraFiles),
        )
      )
    }
    return cursor
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    if (matcher.match(uri) != MATCH_FILE) return null
    val ctx = context ?: return null

    // segments: ["file", normalizedName, version, fileName]
    val segments = uri.pathSegments
    if (segments.size < 4) return null
    val normalizedName = segments[1]
    val version = segments[2]
    val fileName = segments[3]

    val allowlist = readAllowlist(ctx) ?: return null
    val model =
      allowlist.models
        .asSequence()
        .map { runCatching { it.toModel() }.getOrNull() }
        .filterNotNull()
        .find { it.normalizedName == normalizedName && it.version == version }
        ?: run {
          Log.w(TAG, "No model in allowlist for $normalizedName/$version")
          return null
        }

    val absolutePath = model.getPath(ctx, fileName)
    val file = File(absolutePath)
    if (!file.exists() || !file.isFile) {
      Log.w(TAG, "Requested file not found: $absolutePath")
      return null
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun getType(uri: Uri): String? =
    when (matcher.match(uri)) {
      MATCH_LIST -> "vnd.android.cursor.dir/vnd.$AUTHORITY.model"
      MATCH_FILE -> "application/octet-stream"
      else -> null
    }

  // Read-only provider — these are no-ops.
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  private fun readAllowlist(ctx: android.content.Context): ModelAllowlist? {
    val file = File(ctx.getExternalFilesDir(null), MODEL_ALLOWLIST_FILENAME)
    if (!file.exists()) {
      Log.w(TAG, "Allowlist cache not found at ${file.absolutePath}")
      return null
    }
    return try {
      Gson().fromJson(file.readText(), ModelAllowlist::class.java)
    } catch (e: JsonSyntaxException) {
      Log.e(TAG, "Failed to parse allowlist cache", e)
      null
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to read allowlist cache", t)
      null
    }
  }
}
