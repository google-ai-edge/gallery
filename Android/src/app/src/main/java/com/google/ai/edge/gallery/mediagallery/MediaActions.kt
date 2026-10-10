/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaActions"

/**
 * Delete, move, favourite and share through MediaStore. Android asks the user itself for files
 * the app does not own; the work after that confirmation happens in [afterConsent].
 */
class MediaActions(
  private val context: Context,
  private val launch: (PendingIntent, after: suspend () -> Unit) -> Unit,
  private val onChanged: () -> Unit,
) {
  /** Into the system trash (Android keeps it 30 days), not gone for good. */
  fun trash(items: List<MediaItem>) {
    if (items.isEmpty()) return
    launch(MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)) { onChanged() }
  }

  fun favorite(items: List<MediaItem>, on: Boolean) {
    if (items.isEmpty()) return
    launch(MediaStore.createFavoriteRequest(context.contentResolver, items.map { it.uri }, on)) { onChanged() }
  }

  /**
   * Moves files to [relativePath] (e.g. "Pictures/Hund/"). The MediaStore id stays, so the sync
   * sees the new folder and sends the files again.
   */
  fun move(items: List<MediaItem>, relativePath: String) {
    if (items.isEmpty()) return
    val target = relativePath.trim('/') + "/"
    launch(MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })) {
      val failed = withContext(Dispatchers.IO) { moveNow(items, target) }
      if (failed > 0) Toast.makeText(context, "$failed von ${items.size} ließen sich nicht verschieben.", Toast.LENGTH_LONG).show()
      onChanged()
    }
  }

  /** Several files to different folders, with one confirmation for all. */
  fun moveEach(targets: Map<MediaItem, String>, after: suspend (List<MediaItem>) -> Unit = {}) {
    if (targets.isEmpty()) return
    launch(MediaStore.createWriteRequest(context.contentResolver, targets.keys.map { it.uri })) {
      val moved = withContext(Dispatchers.IO) { targets.filter { (item, path) -> moveNow(listOf(item), path.trim('/') + "/") == 0 }.keys.toList() }
      if (moved.size < targets.size) Toast.makeText(context, "${targets.size - moved.size} ließen sich nicht verschieben.", Toast.LENGTH_LONG).show()
      after(moved)
      onChanged()
    }
  }

  /** Returns how many files failed. */
  private fun moveNow(items: List<MediaItem>, target: String): Int =
    items.count { item ->
      runCatching {
          context.contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null) > 0
        }
        .onFailure { Log.w(TAG, "move failed: ${item.name}", it) }
        .getOrDefault(false)
        .not()
    }

  fun share(items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent =
      if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply { type = items[0].mime; putExtra(Intent.EXTRA_STREAM, items[0].uri) }
      } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
          type = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
          putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
      }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
  }

  /** Hands one item to an editor app (Google Fotos, Snapseed, ...). */
  fun edit(item: MediaItem) {
    val intent =
      Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(item.uri, item.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      }
    runCatching { context.startActivity(Intent.createChooser(intent, "Bearbeiten mit")) }
      .onFailure { Toast.makeText(context, "Keine App zum Bearbeiten gefunden.", Toast.LENGTH_SHORT).show() }
  }

  companion object {
    /** Target path for a new folder; MediaStore allows images and videos under Pictures/. */
    fun newFolderPath(name: String): String {
      val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
      return "Pictures/$clean/"
    }
  }
}

@Composable
fun rememberMediaActions(onChanged: () -> Unit): MediaActions {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val pending = remember { arrayOfNulls<suspend () -> Unit>(1) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
      val after = pending[0]
      pending[0] = null
      if (result.resultCode == Activity.RESULT_OK && after != null) scope.launch { after() }
    }
  return remember(context) {
    MediaActions(
      context = context,
      launch = { intent, after ->
        pending[0] = after
        launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
      },
      onChanged = onChanged,
    )
  }
}
