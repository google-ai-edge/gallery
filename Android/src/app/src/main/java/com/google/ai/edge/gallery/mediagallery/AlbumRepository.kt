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

import android.content.Context
import com.google.ai.edge.gallery.morgenschiss.Album
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.MediaSearchApi
import com.google.ai.edge.gallery.morgenschiss.json
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Albums made from bubbles. The list is cached on the phone, so the start page shows it without
 * the server; members always come from the server (they are computed there).
 */
@Singleton
class AlbumRepository @Inject constructor(@ApplicationContext context: Context, private val api: MediaSearchApi) {
  private val prefs = context.getSharedPreferences("albums", Context.MODE_PRIVATE)
  private val _albums = MutableStateFlow(readCached())
  val albums: StateFlow<List<Album>> = _albums.asStateFlow()

  /** New files for folder albums: media id -> target relative path. Moving needs the user. */
  private val _pending = MutableStateFlow(readPending())
  val pendingMoves: StateFlow<Map<Long, String>> = _pending.asStateFlow()

  private fun readCached(): List<Album> =
    runCatching { json.decodeFromString(ListSerializer(Album.serializer()), prefs.getString("list", "[]")!!) }.getOrDefault(emptyList())

  private fun readPending(): Map<Long, String> =
    runCatching { json.decodeFromString(MapSerializer(Long.serializer(), String.serializer()), prefs.getString("pending", "{}")!!) }.getOrDefault(emptyMap())

  suspend fun refresh() {
    val r = api.albums()
    if (r is ApiResult.Ok) {
      _albums.value = r.value.albums
      prefs.edit().putString("list", json.encodeToString(ListSerializer(Album.serializer()), r.value.albums)).apply()
    }
  }

  /** Returns an error text or null. */
  suspend fun create(bubbleKey: String, name: String, folder: Boolean): Pair<Album?, String?> =
    when (val r = api.createAlbum(bubbleKey, name, folder)) {
      is ApiResult.Ok -> {
        refresh()
        r.value to null
      }
      is ApiResult.Failed -> null to if (r.code == "unknown_bubble") "Die Bubbles haben sich geändert, bitte neu laden." else "Das hat nicht geklappt (${r.code})."
      else -> null to "morgenschiss ist gerade nicht erreichbar."
    }

  suspend fun delete(id: String): Boolean {
    val ok = api.deleteAlbum(id) is ApiResult.Ok
    if (ok) refresh()
    return ok
  }

  /** Fingerprints of the album's members, best first; null when the server is away. */
  suspend fun members(id: String): List<String>? = (api.albumMembers(id) as? ApiResult.Ok)?.value?.members?.map { it.id }

  fun addPending(moves: Map<Long, String>) {
    if (moves.isEmpty()) return
    savePending(_pending.value + moves)
  }

  fun clearPending(ids: Collection<Long>) = savePending(_pending.value - ids.toSet())

  private fun savePending(map: Map<Long, String>) {
    _pending.value = map
    prefs.edit().putString("pending", json.encodeToString(MapSerializer(Long.serializer(), String.serializer()), map)).apply()
  }

  companion object {
    fun folderPath(album: Album): String = MediaActions.newFolderPath(album.name)
  }
}
