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
import com.google.ai.edge.gallery.morgenschiss.Album
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.ApkVersion
import com.google.ai.edge.gallery.morgenschiss.Hit
import com.google.ai.edge.gallery.morgenschiss.SpokenHit
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
  /** Search only in the bubbles closest to the query (server side). */
  val onlyBubbles: Boolean = false,
  /** Passages said in videos that match, with where they start. */
  val spoken: List<Spoken> = emptyList(),
)

data class Spoken(val item: MediaItem, val t: Double, val text: String)

@HiltViewModel
class GalleryViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val repository: MediaRepository,
  private val api: MediaSearchApi,
  private val idStore: MediaIdStore,
  val localSearch: LocalSearch,
  private val albumRepo: AlbumRepository,
) : ViewModel() {
  val pendingMoves: StateFlow<Map<Long, PendingMove>> = albumRepo.pendingMoves

  /**
   * Pending moves that still make sense: same file (fingerprint) and still where it was when it
   * matched. A file the user moved elsewhere in the meantime is dropped from the list.
   */
  suspend fun validMoves(pending: Map<Long, PendingMove>, lib: MediaLibrary): Map<MediaItem, String> {
    val rows = withContext(Dispatchers.IO) { idStore.all() }
    val stale = ArrayList<Long>()
    val out = HashMap<MediaItem, String>()
    for ((id, move) in pending) {
      val item = lib.item(id)
      if (item == null || rows[id]?.fingerprint != move.fingerprint || item.relativePath != move.fromPath) stale += id else out[item] = move.path
    }
    if (stale.isNotEmpty()) albumRepo.clearPending(stale)
    return out
  }

  private val _albums = MutableStateFlow<List<Pair<Album, MediaItem?>>>(emptyList())
  /** Albums with a local cover (first preview that is on the phone). */
  val albums: StateFlow<List<Pair<Album, MediaItem?>>> = _albums.asStateFlow()

  init {
    viewModelScope.launch {
      kotlinx.coroutines.flow.combine(albumRepo.albums, repository.library) { a, lib -> a to lib }.collect { (list, lib) ->
        val byFp = withContext(Dispatchers.IO) { idStore.all().values.associate { it.fingerprint to it.mediaId } }
        _albums.value = list.map { album -> album to album.previews.firstNotNullOfOrNull { fp -> byFp[fp]?.let { lib.item(it) } } }
      }
    }
  }

  fun refreshAlbums() {
    viewModelScope.launch { albumRepo.refresh() }
  }

  /** Local items of an album, best first; null when the server is away. */
  suspend fun albumItems(id: String): List<MediaItem>? {
    val fps = albumRepo.members(id) ?: return null
    val byFp = withContext(Dispatchers.IO) { idStore.all().values.groupBy({ it.fingerprint }, { it.mediaId }) }
    val lib = repository.library.value
    return fps.flatMap { fp -> byFp[fp].orEmpty().mapNotNull { lib.item(it) } }.distinctBy { it.id }
  }

  suspend fun deleteAlbum(id: String): Boolean = albumRepo.delete(id)

  suspend fun createAlbum(bubbleKey: String, name: String, folder: Boolean) = albumRepo.create(bubbleKey, name, folder)

  fun clearPending(ids: Collection<Long>) = albumRepo.clearPending(ids)
  val library: StateFlow<MediaLibrary> = repository.library
  val session: StateFlow<Session?> = api.client.session

  private val _search = MutableStateFlow(SearchState())
  val search: StateFlow<SearchState> = _search.asStateFlow()
  private var searchJob: Job? = null

  private val _update = MutableStateFlow<ApkVersion?>(null)
  /** Set when morgenschiss has a newer build than the installed one. */
  val update: StateFlow<ApkVersion?> = _update.asStateFlow()

  /** A list handed to the viewer from elsewhere (e.g. a bubble), with optional video start times. */
  var customList: List<MediaItem> = emptyList()
  var customTimes: Map<Long, Double> = emptyMap()

  fun reloadLibrary() {
    viewModelScope.launch { repository.reload() }
  }

  fun onPermissionGranted() {
    repository.start()
    refreshAlbums()
    localSearch.cancelOldIndexing()
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

  fun setOnlyBubbles(on: Boolean, bucketId: Long?) {
    _search.value = _search.value.copy(onlyBubbles = on)
    if (_search.value.query.isNotBlank()) search(_search.value.query, bucketId)
  }

  fun clearSearch() {
    searchJob?.cancel()
    _search.value = SearchState(everywhere = _search.value.everywhere, onlyBubbles = _search.value.onlyBubbles)
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
        val q = query.trim()
        // the bubble filter still lives on the server (moves to the phone with the bubbles)
        val local = if (_search.value.onlyBubbles) null else localSearch.search(q, scopeItems)
        if (local != null) {
          _search.value =
            _search.value.copy(
              loading = false,
              results = local.items,
              times = local.times,
              spoken = emptyList(),
              source = SearchSource.LOCAL,
              message = if (local.items.isEmpty()) "Nichts gefunden." else null,
            )
          // what was said in videos is still searched by morgenschiss; it joins when it arrives
          if (api.client.isLoggedIn) {
            val r = api.search(q, Scope(folder = folder), limit = 1)
            if (r is ApiResult.Ok && r.value.spoken.isNotEmpty()) {
              val spoken = spokenOf(r.value.spoken, scopeItems)
              _search.value = _search.value.copy(spoken = spoken, message = if (local.items.isEmpty() && spoken.isEmpty()) "Nichts gefunden." else null)
            }
          }
          return@launch
        }
        val r = api.search(q, Scope(folder = folder), onlyBubbles = _search.value.onlyBubbles)
        _search.value =
          when (r) {
            is ApiResult.Ok -> serverResults(r.value.results, scopeItems, r.value.spoken)
            else -> localResults(q, scopeItems, r)
          }
      }
  }

  /** Image to image, always across the whole library. */
  fun similar(item: MediaItem) {
    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        _search.value = SearchState(query = "Ähnlich wie ${item.name}", everywhere = true, loading = true)
        val local = localSearch.similar(item, library.value.items)
        _search.value =
          if (local != null) {
            _search.value.copy(loading = false, results = local.items, times = local.times, source = SearchSource.LOCAL, message = null)
          } else {
            val fp = withContext(Dispatchers.IO) { idStore.all()[item.id]?.fingerprint }
            val r = if (fp != null) api.similar(fp, Scope()) else null
            if (r is ApiResult.Ok) serverResults(r.value.results, library.value.items).copy(query = _search.value.query)
            else _search.value.copy(loading = false, results = emptyList(), times = emptyMap(), source = SearchSource.LOCAL, message = noLocalReason())
          }
      }
  }

  private suspend fun serverResults(hits: List<Hit>, scopeItems: List<MediaItem>, spokenHits: List<SpokenHit> = emptyList()): SearchState {
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
    val spoken = spokenOf(spokenHits, scopeItems)
    return _search.value.copy(
      loading = false,
      results = items,
      times = times,
      spoken = spoken,
      source = SearchSource.SERVER,
      message = if (items.isEmpty() && spoken.isEmpty()) "Nichts gefunden." else null,
    )
  }

  private suspend fun spokenOf(hits: List<SpokenHit>, scopeItems: List<MediaItem>): List<Spoken> {
    val byFp = withContext(Dispatchers.IO) { idStore.all().values.groupBy({ it.fingerprint }, { it.mediaId }) }
    val inScope = scopeItems.associateBy { it.id }
    return hits.mapNotNull { h -> byFp[h.id]?.firstNotNullOfOrNull { inScope[it] }?.let { Spoken(it, h.t, h.text) } }
  }

  private fun noLocalReason(): String =
    when {
      !localSearch.isModelReady() -> "Das Suchmodell fehlt noch auf dem Handy (Einstellungen)."
      localSearch.vectorCount() == 0 -> "Noch keine Suchdaten auf dem Handy, sie kommen mit dem nächsten Abgleich im WLAN."
      else -> "Suche auf dem Handy fehlgeschlagen."
    }

  private suspend fun localResults(query: String, scopeItems: List<MediaItem>, r: ApiResult<*>): SearchState {
    // with the bubble filter the server was asked first; without it the phone already failed
    if (_search.value.onlyBubbles) {
      localSearch.search(query, scopeItems)?.let {
        return _search.value.copy(loading = false, results = it.items, times = it.times, spoken = emptyList(), source = SearchSource.LOCAL, message = "Ohne Bubble-Filter gesucht (${reason(r)}).")
      }
    }
    val byName = scopeItems.filter { it.name.contains(query, ignoreCase = true) }
    return _search.value.copy(
      loading = false,
      results = byName,
      times = emptyMap(),
      spoken = emptyList(),
      source = SearchSource.NAME_ONLY,
      message = "Nur Dateinamen durchsucht. ${noLocalReason()}",
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
