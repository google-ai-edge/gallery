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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.customtasks.smartalbum.DefaultSemanticRetrievalServiceProvider
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumIndexingWorker
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumSource
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LocalSearch"
private const val PERIODIC_INDEX = "local_index_periodic"

/**
 * On-device fallback: the original app's EmbeddingGemma index (70 vision tokens, ids = MediaStore
 * _ID). Its vectors never mix with the server's; this only answers when morgenschiss cannot.
 */
@Singleton
class LocalSearch @Inject constructor(@ApplicationContext private val context: Context) {
  private val lock = Mutex()
  private var service: SemanticRetrievalService? = null

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

  /** Index in the background, only while charging and idle; resumes where it stopped. */
  fun scheduleIndexing() {
    val m = model ?: return
    if (!isModelReady()) return
    val request =
      PeriodicWorkRequestBuilder<SmartAlbumIndexingWorker>(12, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
        .setInputData(
          workDataOf(
            SmartAlbumIndexingWorker.KEY_MODEL_PATH to m.getPath(context),
            SmartAlbumIndexingWorker.KEY_SOURCE to SmartAlbumSource.USER_PHOTOS.rawValue,
            SmartAlbumIndexingWorker.KEY_ACCELERATOR to SmartAlbumViewModel.getDefaultAccelerator(m).label,
            SmartAlbumIndexingWorker.KEY_MAX_INPUT_SEQUENCE_LENGTH to SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
            SmartAlbumIndexingWorker.KEY_VISION_TOKEN_BUDGET to SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
          )
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_INDEX, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  private suspend fun service(): SemanticRetrievalService? =
    lock.withLock {
      service?.let { return it }
      val m = model ?: return null
      if (!isModelReady()) return null
      withContext(Dispatchers.IO) {
          runCatching {
              val s =
                GemmaEmbeddingModelStore.makeService(
                  context = context,
                  model = m,
                  databaseName = DefaultSemanticRetrievalServiceProvider.USER_DATABASE_NAME,
                  maxInputSequenceLength = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
                  visionTokenBudget = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
                )
              GemmaEmbeddingModelStore.getOrCreateEmbedder(
                  modelPath = m.getPath(context),
                  accelerator = GemmaEmbeddingModelStore.getDefaultAccelerator(m),
                  context = context,
                  maxInputSequenceLength = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
                  visionTokenBudget = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
                  visionAccelerator = m.backendSpec.visionAccelerator,
                  audioAccelerator = m.backendSpec.audioAccelerator,
                )
                .initialize()
              s
            }
            .onFailure { Log.e(TAG, "local index unavailable", it) }
            .getOrNull()
        }
        .also { service = it }
    }

  /** null = no usable local index (no model or nothing indexed yet). */
  suspend fun search(query: String, scope: List<MediaItem>): List<MediaItem>? {
    val s = service() ?: return null
    return runCatching { rank(s.retrieveEntities(query, 1000).map { it.id to (it.similarityScore ?: 0f) }, scope) }
      .onFailure { Log.w(TAG, "local search failed", it) }
      .getOrNull()
  }

  suspend fun similar(item: MediaItem, scope: List<MediaItem>): List<MediaItem>? {
    val s = service() ?: return null
    return runCatching {
        val bitmap =
          withContext(Dispatchers.IO) {
            if (item.isVideo) context.contentResolver.loadThumbnail(item.uri, Size(1024, 1024), null)
            else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, info, _ ->
              val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
              d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
              d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
          }
        val results = s.retrieveEntities(ImageUtils.encodeTga(bitmap), seedId = item.id.toString(), limit = 1000)
        rank(results.map { it.id to (it.similarityScore ?: 0f) }, scope)
      }
      .onFailure { Log.w(TAG, "local similar failed", it) }
      .getOrNull()
  }

  private fun rank(hits: List<Pair<String, Float>>, scope: List<MediaItem>): List<MediaItem>? {
    if (hits.isEmpty()) return null
    val byId = scope.associateBy { it.id.toString() }
    // same cutoff as the original app
    val cutoff = maxOf(0.40f, hits.first().second - 0.20f)
    return hits.filter { it.second >= cutoff }.mapNotNull { byId[it.first] }
  }
}
