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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class IndexItem(
  val id: String,
  val kind: String,
  val mime: String,
  val size: Long,
  val takenAt: String? = null,
  val folder: String? = null,
  val name: String? = null,
  val frames: List<String>,
  /** Set for a scene of a video: one frame at this time. */
  val scene: SceneTime? = null,
)

@Serializable data class SceneTime(val t: Double)

@Serializable data class IndexRequest(val items: List<IndexItem>)

@Serializable data class IndexFailure(val id: String, val error: String, val t: Double? = null)

@Serializable data class IndexResponse(val indexed: List<String> = emptyList(), val failed: List<IndexFailure> = emptyList())

@Serializable data class IdsResponse(val ids: List<String> = emptyList(), val count: Int = 0)

/** [t]: for a video, where in it the best matching scene is. */
@Serializable data class Hit(val id: String, val score: Double, val t: Double? = null)

@Serializable data class HitsResponse(val results: List<Hit> = emptyList())

/** Optional narrowing shared by search, similar, classify and map. */
@Serializable data class Scope(val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable private data class SearchRequest(val query: String, val limit: Int, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable private data class SimilarRequest(val id: String, val limit: Int, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable private data class ClassifyRequest(val labels: List<String>, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable data class ClassifiedItem(val id: String, val label: String, val score: Double, val margin: Double)

@Serializable data class ClassifyResponse(val counts: Map<String, Int> = emptyMap(), val items: List<ClassifiedItem> = emptyList())

@Serializable private data class MapRequest(val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable data class MapPoint(val id: String, val x: Float, val y: Float, val z: Float, val kind: String? = null, val mime: String? = null, val size: Long? = null)

@Serializable data class MapResponse(val points: List<MapPoint> = emptyList(), val explained: List<Double> = emptyList())

@Serializable private data class RemoveRequest(val ids: List<String>)

@Serializable data class ApkVersion(val versionCode: Int, val versionName: String = "", val sizeBytes: Long = 0)

@Serializable data class TranscribeStart(val jobId: String, val durationSec: Double = 0.0)

@Serializable data class Sentence(val start: Double, val end: Double, val text: String)

@Serializable data class TranscribeStatus(
  val status: String,
  val progress: Double = 0.0,
  val durationSec: Double = 0.0,
  val text: String? = null,
  val sentences: List<Sentence> = emptyList(),
  val error: String? = null,
)

/** Typed wrappers for /api/mediasearch (Interface docs/mediasearch/api.md). */
@Singleton
class MediaSearchApi @Inject constructor(val client: MorgenschissClient) {
  private inline fun <reified T> ApiResult<JsonElement>.decode(): ApiResult<T> =
    when (this) {
      is ApiResult.Ok -> runCatching { ApiResult.Ok(json.decodeFromJsonElement<T>(value)) }.getOrElse { ApiResult.Failed(200, "invalid_json") }
      is ApiResult.LoggedOut -> this
      is ApiResult.NoAccess -> this
      is ApiResult.Unavailable -> this
      is ApiResult.Busy -> this
      is ApiResult.Failed -> this
    }

  suspend fun ids(): ApiResult<IdsResponse> = client.call("/api/mediasearch/ids", timeoutMs = 30_000).decode()

  /** A batch of 16 photos takes ~11 s on the Mac. */
  suspend fun index(items: List<IndexItem>): ApiResult<IndexResponse> =
    client.call("/api/mediasearch/index", json.encodeToString(IndexRequest(items)), timeoutMs = 120_000).decode()

  /** Short timeout: the app searches locally when the server is slow. */
  suspend fun search(query: String, scope: Scope, limit: Int = 300): ApiResult<HitsResponse> =
    client.call("/api/mediasearch/search", json.encodeToString(SearchRequest(query, limit, scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 3_000).decode()

  suspend fun similar(id: String, scope: Scope, limit: Int = 200): ApiResult<HitsResponse> =
    client.call("/api/mediasearch/similar", json.encodeToString(SimilarRequest(id, limit, scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 5_000).decode()

  suspend fun classify(labels: List<String>, scope: Scope): ApiResult<ClassifyResponse> =
    client.call("/api/mediasearch/classify", json.encodeToString(ClassifyRequest(labels, scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 60_000).decode()

  suspend fun map(scope: Scope): ApiResult<MapResponse> =
    client.call("/api/mediasearch/map", json.encodeToString(MapRequest(scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 60_000).decode()

  suspend fun remove(ids: List<String>): ApiResult<JsonElement> =
    client.call("/api/mediasearch/remove", json.encodeToString(RemoveRequest(ids)), timeoutMs = 30_000)

  suspend fun apkVersion(): ApiResult<ApkVersion> = client.call("/api/mediasearch/apk/version").decode()

  suspend fun transcribe(audio: ByteArray, contentType: String = "audio/mp4"): ApiResult<TranscribeStart> =
    client.upload("/api/mediasearch/transcribe", contentType, audio).decode()

  suspend fun transcribeStatus(jobId: String): ApiResult<TranscribeStatus> =
    client.call("/api/mediasearch/transcribe/$jobId").decode()
}
