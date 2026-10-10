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

@Serializable data class IdsResponse(val ids: List<String> = emptyList(), val count: Int = 0, val speech: Map<String, Int> = emptyMap())

/** [t]: for a video, where in it the best matching scene is. */
@Serializable data class VectorItem(val key: String, val id: String, val kind: String, val t: Double? = null, val tokens: Int = 0, val q: String, val s: Float)

@Serializable data class VectorsPage(val items: List<VectorItem> = emptyList(), val next: String? = null, val total: Int = 0, val at: String? = null)

@Serializable data class Hit(val id: String, val score: Double, val t: Double? = null)

/** A passage said in a video: [t] is where it starts. */
@Serializable data class SpokenHit(val id: String, val t: Double, val text: String, val score: Double)

@Serializable data class HitsResponse(val results: List<Hit> = emptyList(), val spoken: List<SpokenHit> = emptyList())

/** Optional narrowing shared by search, similar, classify and map. */
@Serializable data class Scope(val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable private data class SearchRequest(val query: String, val limit: Int, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null, val onlyMatchingBubbles: Boolean? = null)

@Serializable private data class SimilarRequest(val id: String, val limit: Int, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable private data class ClassifyRequest(val labels: List<String>, val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable data class ClassifiedItem(val id: String, val label: String, val score: Double, val margin: Double)

@Serializable data class ClassifyResponse(val counts: Map<String, Int> = emptyMap(), val items: List<ClassifiedItem> = emptyList())

@Serializable private data class MapRequest(val folder: String? = null, val folderPrefix: String? = null, val ids: List<String>? = null)

@Serializable data class MapPoint(val id: String, val x: Float, val y: Float, val z: Float, val kind: String? = null, val mime: String? = null, val size: Long? = null)

@Serializable data class MapResponse(val points: List<MapPoint> = emptyList(), val explained: List<Double> = emptyList())

@Serializable private data class RemoveRequest(val ids: List<String>)

@Serializable data class BubbleMember(val id: String, val score: Double, val x: Float, val y: Float, val z: Float)

@Serializable data class Bubble(
  val key: String,
  val name: String? = null,
  val suggested: String? = null,
  val tags: List<String> = emptyList(),
  val size: Int,
  val videos: Int = 0,
  val x: Float,
  val y: Float,
  val z: Float,
  val r: Float,
  val previews: List<String> = emptyList(),
  val members: List<BubbleMember> = emptyList(),
  val children: List<Bubble> = emptyList(),
) {
  val title: String
    get() = name ?: suggested ?: "Ohne Namen"
}

@Serializable data class BubblesResponse(val count: Int = 0, val named: Boolean = false, val bubbles: List<Bubble> = emptyList())

@Serializable private data class NameRequest(val key: String, val name: String)

@Serializable data class Album(
  val id: String,
  val name: String,
  val mode: String,
  val count: Int = 0,
  val previews: List<String> = emptyList(),
) {
  val isFolder: Boolean
    get() = mode == "folder"
}

@Serializable data class AlbumsResponse(val albums: List<Album> = emptyList())

@Serializable data class AlbumMember(val id: String, val score: Double)

@Serializable data class AlbumMembers(val id: String, val name: String, val mode: String, val members: List<AlbumMember> = emptyList())

@Serializable data class AlbumMatch(val album: String, val name: String, val mode: String)

@Serializable data class MatchResponse(val matches: Map<String, AlbumMatch> = emptyMap())

@Serializable private data class CreateAlbumRequest(val key: String, val name: String, val mode: String)

@Serializable private data class IdRequest(val id: String)

@Serializable private data class IdsRequest(val ids: List<String>)

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
  /** Only with an id: whether the server stored the speech. */
  val saved: Boolean? = null,
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

  /** One page of the user's vectors (2000 are ~3 MB of JSON). */
  suspend fun vectors(after: String?, since: String?, limit: Int = 2000): ApiResult<VectorsPage> {
    val q = buildList {
      add("limit=$limit")
      if (after != null) add("after=" + java.net.URLEncoder.encode(after, "UTF-8"))
      if (since != null) add("since=" + java.net.URLEncoder.encode(since, "UTF-8"))
    }
    return client.call("/api/mediasearch/vectors?" + q.joinToString("&"), timeoutMs = 60_000).decode()
  }

  /** A batch of 16 photos takes ~11 s on the Mac. */
  suspend fun index(items: List<IndexItem>): ApiResult<IndexResponse> =
    client.call("/api/mediasearch/index", json.encodeToString(IndexRequest(items)), timeoutMs = 120_000).decode()

  /** Short timeout: the app searches locally when the server is slow. */
  /** With [onlyBubbles] the server may compute the bubbles first, so that gets more time. */
  suspend fun search(query: String, scope: Scope, limit: Int = 300, onlyBubbles: Boolean = false): ApiResult<HitsResponse> =
    client.call(
      "/api/mediasearch/search",
      json.encodeToString(SearchRequest(query, limit, scope.folder, scope.folderPrefix, scope.ids, onlyBubbles.takeIf { it })),
      timeoutMs = if (onlyBubbles) 30_000 else 3_000,
    ).decode()

  suspend fun similar(id: String, scope: Scope, limit: Int = 200): ApiResult<HitsResponse> =
    client.call("/api/mediasearch/similar", json.encodeToString(SimilarRequest(id, limit, scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 5_000).decode()

  suspend fun classify(labels: List<String>, scope: Scope): ApiResult<ClassifyResponse> =
    client.call("/api/mediasearch/classify", json.encodeToString(ClassifyRequest(labels, scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 60_000).decode()

  suspend fun map(scope: Scope): ApiResult<MapResponse> =
    client.call("/api/mediasearch/map", json.encodeToString(MapRequest(scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 60_000).decode()

  /** The first computation takes a few seconds on the Pi. */
  suspend fun bubbles(scope: Scope): ApiResult<BubblesResponse> =
    client.call("/api/mediasearch/bubbles", json.encodeToString(MapRequest(scope.folder, scope.folderPrefix, scope.ids)), timeoutMs = 90_000).decode()

  suspend fun albums(): ApiResult<AlbumsResponse> = client.call("/api/mediasearch/albums", timeoutMs = 30_000).decode()

  suspend fun createAlbum(bubbleKey: String, name: String, folder: Boolean): ApiResult<Album> =
    client.call("/api/mediasearch/albums/create", json.encodeToString(CreateAlbumRequest(bubbleKey, name, if (folder) "folder" else "album"))).decode()

  suspend fun albumMembers(id: String): ApiResult<AlbumMembers> =
    client.call("/api/mediasearch/albums/members", json.encodeToString(IdRequest(id)), timeoutMs = 30_000).decode()

  suspend fun matchAlbums(ids: List<String>): ApiResult<MatchResponse> =
    client.call("/api/mediasearch/albums/match", json.encodeToString(IdsRequest(ids)), timeoutMs = 30_000).decode()

  suspend fun deleteAlbum(id: String): ApiResult<JsonElement> = client.call("/api/mediasearch/albums/delete", json.encodeToString(IdRequest(id)))

  suspend fun nameBubble(key: String, name: String): ApiResult<JsonElement> =
    client.call("/api/mediasearch/bubbles/name", json.encodeToString(NameRequest(key, name)))

  suspend fun remove(ids: List<String>): ApiResult<JsonElement> =
    client.call("/api/mediasearch/remove", json.encodeToString(RemoveRequest(ids)), timeoutMs = 30_000)

  suspend fun apkVersion(): ApiResult<ApkVersion> = client.call("/api/mediasearch/apk/version").decode()

  /** With [videoId] the server keeps what is said, so the search finds it. */
  suspend fun transcribe(audio: ByteArray, videoId: String? = null, contentType: String = "audio/mp4"): ApiResult<TranscribeStart> =
    client.upload("/api/mediasearch/transcribe" + (videoId?.let { "?id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), contentType, audio).decode()

  suspend fun transcribeStatus(jobId: String): ApiResult<TranscribeStatus> =
    client.call("/api/mediasearch/transcribe/$jobId").decode()
}
