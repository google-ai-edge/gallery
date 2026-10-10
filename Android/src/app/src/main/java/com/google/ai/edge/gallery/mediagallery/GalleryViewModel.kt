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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.ApkVersion
import com.google.ai.edge.gallery.morgenschiss.Hit
import com.google.ai.edge.gallery.morgenschiss.MediaIdStore
import com.google.ai.edge.gallery.morgenschiss.MediaSearchApi
import com.google.ai.edge.gallery.morgenschiss.MediaSyncWorker
import com.google.ai.edge.gallery.morgenschiss.Scope
import com.google.ai.edge.gallery.morgenschiss.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the shown results came from; the UI says so when it is not the server. */
enum class SearchSource { SERVER, LOCAL, NAME_ONLY }

data class SearchState(
  val query: String = "",
  val everywhere: Boolean = false,
  val loading: Boolean = false,
  /** null = no active search, show the folder. */
  val results: List<MediaItem>? = null,
  val source: SearchSource = SearchSource.SERVER,
  val message: String? = null,
  /** Videos found by a scene: media id -> seconds into the video. */
  val times: Map<Long, Double> = emptyMap(),
)

@HiltViewModel
class GalleryViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val repository: MediaRepository,
  private val api: MediaSearchApi,
  private val idStore: MediaIdStore,
  val localSearch: LocalSearch,
) : ViewModel() {
  val library: StateFlow<MediaLibrary> = repository.library
  val session: StateFlow<Session?> = api.client.session

  private val _search = MutableStateFlow(SearchState())
  val search: StateFlow<SearchState> = _search.asStateFlow()
  private var searchJob: Job? = null

  private val _update = MutableStateFlow<ApkVersion?>(null)
  /** Set when morgenschiss has a newer build than the installed one. */
  val update: StateFlow<ApkVersion?> = _update.asStateFlow()

  /** A list handed to the viewer from elsewhere (e.g. a bubble). */
  var customList: List<MediaItem> = emptyList()

  fun reloadLibrary() {
    viewModelScope.launch { repository.reload() }
  }

  fun onPermissionGranted() {
    repository.start()
    localSearch.scheduleIndexing()
    if (api.client.isLoggedIn) {
      MediaSyncWorker.schedulePeriodic(context)
      MediaSyncWorker.runNow(context)
      checkForUpdate()
    }
  }

  // --- Login -------------------------------------------------------------------------------

  suspend fun login(user: String, password: String): String? {
    val error = api.client.login(user, password)
    if (error == null) {
      MediaSyncWorker.schedulePeriodic(context)
      MediaSyncWorker.runNow(context)
      checkForUpdate()
    }
    return error
  }

  fun logout() {
    MediaSyncWorker.cancelAll(context)
    api.client.logout()
  }

  fun syncNow() = MediaSyncWorker.runNow(context)

  fun checkForUpdate() {
    viewModelScope.launch {
      val r = api.apkVersion()
      _update.value = (r as? ApiResult.Ok)?.value?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }
  }

  // --- Search ------------------------------------------------------------------------------

  fun setEverywhere(on: Boolean, bucketId: Long?) {
    _search.value = _search.value.copy(everywhere = on)
    if (_search.value.query.isNotBlank()) search(_search.value.query, bucketId)
  }

  fun clearSearch() {
    searchJob?.cancel()
    _search.value = SearchState(everywhere = _search.value.everywhere)
  }

  /** Debounced: typing fast sends one request. */
  fun search(query: String, bucketId: Long?) {
    _search.value = _search.value.copy(query = query)
    searchJob?.cancel()
    if (query.isBlank()) {
      _search.value = _search.value.copy(results = null, loading = false, message = null)
      return
    }
    searchJob =
      viewModelScope.launch {
        delay(350)
        _search.value = _search.value.copy(loading = true)
        val everywhere = _search.value.everywhere || bucketId == null
        val scopeItems = library.value.itemsIn(if (everywhere) null else bucketId)
        val folder = if (everywhere) null else scopeItems.firstOrNull()?.folder
        val r = api.search(query.trim(), Scope(folder = folder))
        _search.value =
          when (r) {
            is ApiResult.Ok -> serverResults(r.value.results, scopeItems)
            else -> localResults(query.trim(), scopeItems, r)
          }
      }
  }

  /** Image to image, always across the whole library. */
  fun similar(item: MediaItem) {
    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        _search.value = SearchState(query = "Ähnlich wie ${item.name}", everywhere = true, loading = true)
        val fp = withContext(Dispatchers.IO) { idStore.all()[item.id]?.fingerprint }
        val r = if (fp != null) api.similar(fp, Scope()) else null
        _search.value =
          if (r is ApiResult.Ok) {
            serverResults(r.value.results, library.value.items).copy(query = _search.value.query)
          } else {
            val local = localSearch.similar(item, library.value.items)
            _search.value.copy(
              loading = false,
              results = local ?: emptyList(),
              times = emptyMap(),
              source = SearchSource.LOCAL,
              message = if (local == null) "Ähnliche Bilder gehen gerade nicht: morgenschiss nicht erreichbar und kein lokaler Index." else "Lokal gesucht.",
            )
          }
      }
  }

  private suspend fun serverResults(hits: List<Hit>, scopeItems: List<MediaItem>): SearchState {
    val byFp = withContext(Dispatchers.IO) { idStore.all().values.groupBy({ it.fingerprint }, { it.mediaId }) }
    val inScope = scopeItems.associateBy { it.id }
    // the original app hides weak matches the same way
    val cutoff = maxOf(0.40, (hits.firstOrNull()?.score ?: 0.0) - 0.20)
    val times = HashMap<Long, Double>()
    val items =
      hits.filter { it.score >= cutoff }
        .flatMap { h ->
          byFp[h.id].orEmpty().mapNotNull { inScope[it] }.onEach { item -> if (h.t != null && item.isVideo) times[item.id] = h.t }
        }
        .distinctBy { it.id }
    return _search.value.copy(
      loading = false,
      results = items,
      times = times,
      source = SearchSource.SERVER,
      message = if (items.isEmpty()) "Nichts gefunden." else null,
    )
  }

  private suspend fun localResults(query: String, scopeItems: List<MediaItem>, r: ApiResult<*>): SearchState {
    val local = localSearch.search(query, scopeItems)
    if (local != null) {
      return _search.value.copy(loading = false, results = local, times = emptyMap(), source = SearchSource.LOCAL, message = "Lokal gesucht (${reason(r)}).")
    }
    val byName = scopeItems.filter { it.name.contains(query, ignoreCase = true) }
    return _search.value.copy(
      loading = false,
      results = byName,
      times = emptyMap(),
      source = SearchSource.NAME_ONLY,
      message = "Nur Dateinamen durchsucht (${reason(r)}, kein lokaler Index).",
    )
  }

  private fun reason(r: ApiResult<*>): String =
    when (r) {
      is ApiResult.LoggedOut -> "nicht angemeldet"
      is ApiResult.NoAccess -> "kein Zugriff auf die Suche"
      is ApiResult.Unavailable -> if (r.reason.startsWith("mac")) "Mac nicht da" else "morgenschiss nicht erreichbar"
      is ApiResult.Busy -> "Server ausgelastet"
      else -> "Serverfehler"
    }
}
