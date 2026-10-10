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

package com.google.ai.edge.gallery.morgenschiss

import android.content.Context

/**
 * Brings the phone's [VectorStore] up to date with morgenschiss: the first run fetches
 * everything, later runs only what was written since (server time of the last run).
 */
object VectorSync {
  private const val PREFS = "vector_sync"
  private const val KEY_AT = "at"

  /** null = done; otherwise the failed call, for the worker's retry logic. */
  suspend fun pull(context: Context, api: MediaSearchApi, store: VectorStore, full: Boolean = false): ApiResult<*>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // an empty store (reinstall, cleared data) needs everything again
    val since = if (full) null else prefs.getString(KEY_AT, null)?.takeIf { store.count() > 0 }
    // videos sent again in this delta: their older scenes are gone on the server
    val resent = HashSet<String>()
    val sceneKeys = HashMap<String, MutableSet<String>>()
    var after: String? = null
    var at: String? = null
    while (true) {
      val page =
        when (val r = api.vectors(after, since)) {
          is ApiResult.Ok -> r.value
          else -> return r
        }
      if (at == null) at = page.at
      for (v in page.items) {
        if (v.kind == "scene") sceneKeys.getOrPut(v.id) { HashSet() } += v.key else if (since != null && v.kind == "video") resent += v.id
      }
      store.put(
        invalidate = false,
        rows = page.items.mapNotNull { v ->
          val q = runCatching { VectorStore.decode(v.q) }.getOrNull()?.takeIf { it.size == DIMS } ?: return@mapNotNull null
          VectorStore.Row(v.key, v.id, if (v.kind == "scene") v.t else null, v.kind, "server", v.tokens, q, v.s)
        }
      )
      after = page.next ?: break
    }
    for (fp in resent) store.dropScenesExcept(fp, sceneKeys[fp].orEmpty())
    store.invalidate()
    // a minute back: an entry stamped just before [at] but written after the read is fetched next time
    val next = at?.let { runCatching { java.time.Instant.parse(it).minusSeconds(60).toString() }.getOrNull() }
    if (next != null) prefs.edit().putString(KEY_AT, next).apply()
    return null
  }

  fun reset(context: Context, store: VectorStore) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    store.clear()
  }
}
