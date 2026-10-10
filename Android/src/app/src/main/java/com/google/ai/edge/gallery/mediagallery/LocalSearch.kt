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
import android.graphics.ImageDecoder
import android.util.Log
import android.util.Size
import androidx.work.WorkManager
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.morgenschiss.DIMS
import com.google.ai.edge.gallery.morgenschiss.MediaIdStore
import com.google.ai.edge.gallery.morgenschiss.VectorStore
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.gallery.services.semanticretrieval.OnDeviceEmbedder
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LocalSearch"
private const val OLD_PERIODIC_INDEX = "local_index_periodic"

/** Measured: without this prompt the phone's query fits the Mac's vectors far worse (72 % vs 98 %). */
const val QUERY_PREFIX = "task: search result | query: "

/** The phone's image budget ends at 280 tokens (560 behaves the same). */
const val PHONE_VISION_TOKENS = 280

/** [complete] = at least 90 % of the files in scope have a vector on the phone. */
data class LocalHits(val items: List<MediaItem>, val times: Map<Long, Double>, val complete: Boolean)

/**
 * Search on the phone: the query is embedded here, compared with the vectors in [VectorStore]
 * (from the Mac's first indexing or the phone itself). No server involved.
 */
@Singleton
class LocalSearch
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val vectors: VectorStore,
  private val idStore: MediaIdStore,
) {
  private val lock = Mutex()

  /** The bundled allowlist has exactly this one model. */
  val model: Model? by lazy {
    runCatching {
        context.assets.open("model_allowlist.json").bufferedReader().use {
          Gson().fromJson(it.readText(), ModelAllowlist::class.java)
        }.models.firstOrNull()?.toModel()?.also { it.preProcess() }
      }
      .onFailure { Log.e(TAG, "bundled allowlist unreadable", it) }
      .getOrNull()
  }

  fun isModelReady(): Boolean = model?.let { File(it.getPath(context)).isFile } == true

  suspend fun vectorCount(): Int = withContext(Dispatchers.IO) { vectors.count() }

  /** The original app's 70-token index used other ids and is replaced by [VectorStore]. */
  fun cancelOldIndexing() {
    WorkManager.getInstance(context).cancelUniqueWork(OLD_PERIODIC_INDEX)
  }

  /** Not cached here: the store hands out a new one after the model was deleted or replaced. */
  private suspend fun embedder(): OnDeviceEmbedder? =
    lock.withLock {
      val m = model ?: return null
      if (!isModelReady()) return null
      withContext(Dispatchers.IO) {
          runCatching {
              GemmaEmbeddingModelStore.getOrCreateEmbedder(
                  modelPath = m.getPath(context),
                  accelerator = Accelerator.GPU,
                  context = context,
                  maxInputSequenceLength = 256,
                  visionTokenBudget = PHONE_VISION_TOKENS,
                )
                .also { it.initialize() }
            }
            .onFailure { Log.e(TAG, "search model unavailable", it) }
            .getOrNull()
        }
    }

  /** null = no model on the phone or nothing to search in yet. */
  suspend fun search(query: String, scope: List<MediaItem>): LocalHits? {
    if (vectorCount() == 0) return null
    val e = embedder() ?: return null
    val q = withContext(Dispatchers.Default) { e.generateTextEmbedding(QUERY_PREFIX + query) }?.takeIf { it.size == DIMS } ?: return null
    return rank(q, scope, seed = null)
  }

  /** Image to image: the stored vector of [item], or one computed here when it has none yet. */
  suspend fun similar(item: MediaItem, scope: List<MediaItem>): LocalHits? {
    val fp = withContext(Dispatchers.IO) { idStore.all()[item.id]?.fingerprint }
    val stored = fp?.let { withContext(Dispatchers.IO) { vectors.vectorOf(it) } }
    val v =
      stored
        ?: run {
          val e = embedder() ?: return null
          runCatching {
              val bitmap =
                withContext(Dispatchers.IO) {
                  if (item.isVideo) context.contentResolver.loadThumbnail(item.uri, Size(1024, 1024), null)
                  else
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, info, _ ->
                      val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                      d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                      d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
              withContext(Dispatchers.Default) { e.generateImageEmbedding(ImageUtils.encodeTga(bitmap)) }
            }
            .onFailure { Log.w(TAG, "local similar failed", it) }
            .getOrNull()
        }
        ?: return null
    if (v.size != DIMS || vectorCount() == 0) return null
    return rank(v, scope, seed = fp)
  }

  private suspend fun rank(v: FloatArray, scope: List<MediaItem>, seed: String?): LocalHits {
    val rows = withContext(Dispatchers.IO) { idStore.all() }
    val inScope = scope.associateBy { it.id }
    val byFp = HashMap<String, MutableList<MediaItem>>()
    for ((id, row) in rows) inScope[id]?.let { byFp.getOrPut(row.fingerprint) { ArrayList() } += it }
    val (hits, coverage) =
      withContext(Dispatchers.Default) { vectors.search(unit(v), byFp.keys, 1000).filter { it.fingerprint != seed } to vectors.coverage(byFp.keys) }
    // the original app hides weak matches the same way
    val cutoff = maxOf(0.40f, (hits.firstOrNull()?.score ?: 0f) - 0.20f)
    val times = HashMap<Long, Double>()
    val items =
      hits.filter { it.score >= cutoff }
        .flatMap { h -> byFp[h.fingerprint].orEmpty().onEach { if (h.t != null && it.isVideo) times[it.id] = h.t } }
        .distinctBy { it.id }
    return LocalHits(items, times, complete = coverage >= 0.9f)
  }

  private fun unit(v: FloatArray): FloatArray {
    var n = 0.0
    for (x in v) n += x * x
    val k = if (n > 0) (1 / sqrt(n)).toFloat() else 0f
    return FloatArray(v.size) { v[it] * k }
  }
}
