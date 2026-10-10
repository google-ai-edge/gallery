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

import android.app.PendingIntent
import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.ClassifyResponse
import com.google.ai.edge.gallery.morgenschiss.MapPoint
import com.google.ai.edge.gallery.morgenschiss.MediaIdStore
import com.google.ai.edge.gallery.morgenschiss.MediaSearchApi
import com.google.ai.edge.gallery.morgenschiss.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default labels; descriptive sentences classify better than single words. */
object DefaultLabels {
  val junk =
    listOf(
      "ein Screenshot vom Homescreen oder einer App",
      "ein Screenshot einer Website",
      "ein Meme oder lustiges Bild aus dem Internet",
      "ein Foto von einem Kassenbon oder Beleg",
      "ein Foto von einem Dokument oder Zettel",
      "ein verwackeltes oder unscharfes Foto",
    )

  /** Counterweights: without them every photo would land on some junk label. */
  val keep =
    listOf(
      "ein Foto von Menschen",
      "ein Foto von einer Landschaft oder Natur",
      "ein Foto von einem Tier",
      "ein Foto von Essen",
      "ein Foto von einer Feier oder einem Ausflug",
    )

  val categories =
    listOf(
      "ein Foto von meinem Hund",
      "ein Video, in dem ich Musik mache",
      "ein Foto von Essen",
      "ein Foto von einer Landschaft oder Natur",
      "ein Foto von Menschen",
      "ein Screenshot",
      "ein Foto von einem Dokument",
      "ein Foto von einer Reise oder einer Stadt",
    )
}

data class CleanupSettings(
  val junk: List<String> = DefaultLabels.junk,
  val keep: List<String> = DefaultLabels.keep,
  val minAgeMonths: Int = 12,
  /** Distance to the second best label; small = unsure, those stay out. */
  val minMargin: Double = 0.02,
)

data class CleanupCandidate(val item: MediaItem, val label: String, val score: Double)

sealed interface Loadable<out T> {
  data object Idle : Loadable<Nothing>

  data object Loading : Loadable<Nothing>

  data class Done<T>(val value: T) : Loadable<T>

  data class Error(val message: String) : Loadable<Nothing>
}

class MapCamera {
  var yaw by mutableFloatStateOf(DEFAULT_YAW)
  var pitch by mutableFloatStateOf(DEFAULT_PITCH)
  var zoom by mutableFloatStateOf(1f)
  var panX by mutableFloatStateOf(0f)
  var panY by mutableFloatStateOf(0f)
  /** Point index in the loaded map, or -1. */
  var selected by mutableIntStateOf(-1)

  fun reset() {
    yaw = DEFAULT_YAW
    pitch = DEFAULT_PITCH
    zoom = 1f
    panX = 0f
    panY = 0f
    selected = -1
  }

  companion object {
    const val DEFAULT_YAW = 0.6f
    const val DEFAULT_PITCH = -0.4f
  }
}

data class CategoryStat(val label: String, val count: Int, val byYear: Map<Int, Int>)

/** Cleanup, category statistics and the 3D map; all need morgenschiss. */
@HiltViewModel
class AnalysisViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val repository: MediaRepository,
  private val api: MediaSearchApi,
  private val idStore: MediaIdStore,
) : ViewModel() {
  private val prefs = context.getSharedPreferences("analysis", Context.MODE_PRIVATE)

  private val _cleanupSettings = MutableStateFlow(loadCleanup())
  val cleanupSettings: StateFlow<CleanupSettings> = _cleanupSettings.asStateFlow()
  private val _cleanup = MutableStateFlow<Loadable<List<CleanupCandidate>>>(Loadable.Idle)
  val cleanup: StateFlow<Loadable<List<CleanupCandidate>>> = _cleanup.asStateFlow()

  private val _categoryLabels = MutableStateFlow(loadList("categories", DefaultLabels.categories))
  val categoryLabels: StateFlow<List<String>> = _categoryLabels.asStateFlow()
  private val _categories = MutableStateFlow<Loadable<List<CategoryStat>>>(Loadable.Idle)
  val categories: StateFlow<Loadable<List<CategoryStat>>> = _categories.asStateFlow()

  /** Rotation, zoom and selection live here so they survive opening a photo and coming back. */
  val camera = MapCamera()

  private val _map = MutableStateFlow<Loadable<List<Pair<MapPoint, MediaItem?>>>>(Loadable.Idle)
  val map: StateFlow<Loadable<List<Pair<MapPoint, MediaItem?>>>> = _map.asStateFlow()

  private fun loadList(key: String, default: List<String>): List<String> =
    prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: default

  private fun loadCleanup() =
    CleanupSettings(
      junk = loadList("junk", DefaultLabels.junk),
      keep = loadList("keep", DefaultLabels.keep),
      minAgeMonths = prefs.getInt("minAgeMonths", 12),
      minMargin = prefs.getFloat("minMargin", 0.02f).toDouble(),
    )

  fun updateCleanup(s: CleanupSettings) {
    _cleanupSettings.value = s
    prefs.edit()
      .putString("junk", s.junk.joinToString("\n"))
      .putString("keep", s.keep.joinToString("\n"))
      .putInt("minAgeMonths", s.minAgeMonths)
      .putFloat("minMargin", s.minMargin.toFloat())
      .apply()
  }

  fun updateCategoryLabels(labels: List<String>) {
    _categoryLabels.value = labels
    prefs.edit().putString("categories", labels.joinToString("\n")).apply()
  }

  /** fingerprint -> local items (copies share a fingerprint). */
  private suspend fun localByFp(): Map<String, List<MediaItem>> =
    withContext(Dispatchers.IO) {
      val lib = repository.library.value
      idStore.all().values.groupBy({ it.fingerprint }, { lib.item(it.mediaId) }).mapValues { (_, v) -> v.filterNotNull() }
    }

  private fun errorText(r: ApiResult<*>): String =
    when (r) {
      is ApiResult.LoggedOut -> "Nicht angemeldet. In den Einstellungen bei morgenschiss anmelden."
      is ApiResult.NoAccess -> "Dein Konto hat keinen Zugriff auf die Mediensuche."
      is ApiResult.Unavailable -> "morgenschiss oder der Mac ist gerade nicht erreichbar."
      is ApiResult.Busy -> "Der Server ist ausgelastet, bitte gleich nochmal."
      else -> "Das hat nicht geklappt ()."
    }

  fun runCleanup() {
    val s = _cleanupSettings.value
    _cleanup.value = Loadable.Loading
    viewModelScope.launch {
      val labels = (s.junk + s.keep).distinct()
      val r = api.classify(labels, Scope())
      if (r !is ApiResult.Ok) {
        _cleanup.value = Loadable.Error(errorText(r))
        return@launch
      }
      val byFp = localByFp()
      val junk = s.junk.toSet()
      val cutoff = Calendar.getInstance().apply { add(Calendar.MONTH, -s.minAgeMonths) }.timeInMillis
      val out =
        r.value.items
          .filter { it.label in junk && it.margin >= s.minMargin }
          .flatMap { c -> byFp[c.id].orEmpty().map { CleanupCandidate(it, c.label, c.score) } }
          .filter { it.item.takenAt <= cutoff }
          .distinctBy { it.item.id }
          .sortedWith(compareBy({ it.label }, { it.item.takenAt }))
      _cleanup.value = Loadable.Done(out)
    }
  }

  /** Android shows its own confirmation; null when nothing is selected. */
  fun deleteRequest(items: List<MediaItem>): PendingIntent? =
    if (items.isEmpty()) null else MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })

  /** After the system dialog: drop the deleted files from the list and the server. */
  fun onDeleted(items: List<MediaItem>) {
    viewModelScope.launch {
      repository.reload()
      val stillThere = repository.library.value.items.map { it.id }.toSet()
      val gone = items.filter { it.id !in stillThere }
      val rows = withContext(Dispatchers.IO) { idStore.all() }
      val remainingFps = rows.values.filter { it.mediaId in stillThere }.map { it.fingerprint }.toSet()
      val fps = gone.mapNotNull { rows[it.id]?.fingerprint }.filter { it !in remainingFps }.distinct()
      // rows stay when the server call fails: the sync worker then removes them as deleted files
      val removed = fps.isEmpty() || api.remove(fps) is ApiResult.Ok
      if (removed) withContext(Dispatchers.IO) { idStore.delete(gone.map { it.id }) }
      val goneIds = gone.map { it.id }.toSet()
      (_cleanup.value as? Loadable.Done)?.let { d -> _cleanup.value = Loadable.Done(d.value.filter { it.item.id !in goneIds }) }
    }
  }

  fun runCategories() {
    val labels = _categoryLabels.value
    _categories.value = Loadable.Loading
    viewModelScope.launch {
      val r = api.classify(labels, Scope())
      if (r !is ApiResult.Ok) {
        _categories.value = Loadable.Error(errorText(r))
        return@launch
      }
      _categories.value = Loadable.Done(stats(labels, r.value, localByFp()))
    }
  }

  private fun stats(labels: List<String>, res: ClassifyResponse, byFp: Map<String, List<MediaItem>>): List<CategoryStat> {
    val cal = Calendar.getInstance()
    val years = HashMap<String, MutableMap<Int, Int>>()
    for (c in res.items) {
      val item = byFp[c.id]?.firstOrNull() ?: continue
      cal.timeInMillis = item.takenAt
      val m = years.getOrPut(c.label) { HashMap() }
      m.merge(cal.get(Calendar.YEAR), 1, Int::plus)
    }
    return labels.map { CategoryStat(it, res.counts[it] ?: 0, years[it].orEmpty().toSortedMap()) }.sortedByDescending { it.count }
  }

  fun loadMap() {
    if (_map.value is Loadable.Loading) return
    _map.value = Loadable.Loading
    viewModelScope.launch {
      val r = api.map(Scope())
      if (r !is ApiResult.Ok) {
        _map.value = Loadable.Error(errorText(r))
        return@launch
      }
      val byFp = localByFp()
      camera.selected = -1
      _map.value = Loadable.Done(r.value.points.map { it to byFp[it.id]?.firstOrNull() })
    }
  }
}
