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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.Bubble
import com.google.ai.edge.gallery.morgenschiss.MediaIdStore
import com.google.ai.edge.gallery.morgenschiss.MediaSearchApi
import com.google.ai.edge.gallery.morgenschiss.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bubble view: which level is shown (top or inside a bubble), which bubble has the focus and
 * which item the preview shows. Kept here so opening a photo and coming back changes nothing.
 */
@HiltViewModel
class BubbleViewModel
@Inject
constructor(private val api: MediaSearchApi, private val idStore: MediaIdStore, private val repository: MediaRepository) :
  ViewModel() {
  var state by mutableStateOf<Loadable<List<Bubble>>>(Loadable.Idle)
    private set

  /** fingerprint -> local item (first copy). */
  var items by mutableStateOf<Map<String, MediaItem>>(emptyMap())
    private set

  /** Keys of the bubbles entered, outermost first. */
  var path by mutableStateOf<List<String>>(emptyList())
    private set

  var focus by mutableIntStateOf(0)
  var sheetOpen by mutableStateOf(false)
  /** Index of the previewed member of the focused bubble. */
  var page by mutableIntStateOf(0)

  fun load() {
    if (state is Loadable.Loading) return
    state = Loadable.Loading
    viewModelScope.launch {
      val r = api.bubbles(Scope())
      if (r !is ApiResult.Ok) {
        state = Loadable.Error(errorText(r))
        return@launch
      }
      items = withContext(Dispatchers.IO) {
        val lib = repository.library.value
        idStore.all().values.mapNotNull { row -> lib.item(row.mediaId)?.let { row.fingerprint to it } }.toMap()
      }
      path = emptyList()
      focus = 0
      page = 0
      state = Loadable.Done(r.value.bubbles)
    }
  }

  /** Bubbles of the current level. */
  fun level(): List<Bubble> {
    var list = (state as? Loadable.Done)?.value ?: return emptyList()
    for (key in path) list = list.firstOrNull { it.key == key }?.children ?: return emptyList()
    return list
  }

  fun parent(): Bubble? {
    var list = (state as? Loadable.Done)?.value ?: return null
    var found: Bubble? = null
    for (key in path) {
      found = list.firstOrNull { it.key == key } ?: return null
      list = found.children
    }
    return found
  }

  fun focusOn(index: Int) {
    if (index == focus) return
    focus = index
    page = 0
  }

  fun enter(bubble: Bubble) {
    if (bubble.children.isEmpty()) return
    path = path + bubble.key
    focus = 0
    page = 0
  }

  /** Back one level; false when already at the top. */
  fun up(): Boolean {
    val key = path.lastOrNull() ?: return false
    path = path.dropLast(1)
    focus = level().indexOfFirst { it.key == key }.coerceAtLeast(0)
    page = 0
    return true
  }

  fun rename(bubble: Bubble, name: String) {
    viewModelScope.launch {
      if (api.nameBubble(bubble.key, name.trim()) is ApiResult.Ok) {
        val done = state as? Loadable.Done ?: return@launch
        state = Loadable.Done(done.value.map { renamed(it, bubble.key, name.trim().ifEmpty { null }) })
      }
    }
  }

  private fun renamed(b: Bubble, key: String, name: String?): Bubble =
    if (b.key == key) b.copy(name = name) else b.copy(children = b.children.map { renamed(it, key, name) })

  private fun errorText(r: ApiResult<*>): String =
    when (r) {
      is ApiResult.LoggedOut -> "Nicht angemeldet. In den Einstellungen bei morgenschiss anmelden."
      is ApiResult.NoAccess -> "Dein Konto hat keinen Zugriff auf die Mediensuche."
      is ApiResult.Unavailable -> "morgenschiss ist gerade nicht erreichbar."
      is ApiResult.Busy -> "Der Server ist ausgelastet, bitte gleich nochmal."
      else -> "Das hat nicht geklappt ($r)."
    }
}

/** Members of a bubble; a parent's members are those of its children, best match first. */
fun Bubble.allMembers(): List<com.google.ai.edge.gallery.morgenschiss.BubbleMember> =
  if (members.isNotEmpty() || children.isEmpty()) members else children.flatMap { it.allMembers() }.sortedByDescending { it.score }
