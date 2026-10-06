/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.services.semanticretrieval

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.AudioPart
import com.google.mediapipe.tasks.core.EmbeddingProvider
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore
import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.google.mediapipe.tasks.retrieval.semanticretriever.SemanticRetriever
import com.google.mediapipe.tasks.retrieval.semanticretriever.SemanticRetrieverComponents
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGSemanticRetrievalService"

private fun EmbedPart.toTaskPart(defaultImageUri: Uri): Part =
  when (this) {
    is EmbedPart.Text -> TextPart(text)
    is EmbedPart.Image -> ImagePart(defaultImageUri, imageData)
    is EmbedPart.Audio -> AudioPart(toAudioData())
  }

data class SemanticRetrievalResult(
  val id: String,
  val embeddings: FloatArray,
  val metadata: Map<String, String>,
  val similarityScore: Float?,
)

/** In-memory [VectorStore] implementation for unit tests and JVM / fallback environments. */
private class InMemoryVectorStore : VectorStore {
  private val records = ConcurrentHashMap<String, RetrievalRecord>()

  val allIds: Set<String>
    get() = records.keys

  override fun getAllRecordIds(): List<String> = records.keys.toList()

  override fun deleteAll() {
    records.clear()
  }

  override fun upsert(records: List<RetrievalRecord>) {
    for (record in records) {
      this.records[record.id] = record
    }
  }

  override fun delete(ids: List<String>) {
    for (id in ids) {
      records.remove(id)
    }
  }

  override fun delete(metadataFilter: Map<String, String>) {
    if (metadataFilter.isEmpty()) return
    val idsToDelete = records.values.filter { matchesFilter(it, metadataFilter) }.map { it.id }
    delete(idsToDelete)
  }

  override fun search(queryEmbedding: FloatArray, topK: Int): List<RetrievalRecord> {
    return search(queryEmbedding, topK, metadataFilter = emptyMap())
  }

  override fun search(
    queryEmbedding: FloatArray,
    topK: Int,
    metadataFilter: Map<String, String>,
  ): List<RetrievalRecord> {
    return records.values
      .filter { record -> matchesFilter(record, metadataFilter) }
      .mapNotNull { record ->
        val emb = record.embeddings
        if (emb.isEmpty()) return@mapNotNull null
        val score = cosineSimilarity(queryEmbedding, emb)
        record to score
      }
      .sortedByDescending { it.second }
      .take(topK)
      .map { it.first }
  }

  override fun get(ids: List<String>): List<RetrievalRecord> {
    return ids.mapNotNull { records[it] }
  }

  override fun close() {
    records.clear()
  }

  /** Returns whether [record]'s metadata contains every entry in [filter]. */
  private fun matchesFilter(record: RetrievalRecord, filter: Map<String, String>): Boolean {
    if (filter.isEmpty()) return true
    val metadata = record.metadata ?: return false
    return filter.all { (key, value) -> metadata[key] == value }
  }

  private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
    if (v1.size != v2.size || v1.isEmpty()) return 0f
    var dot = 0f
    var norm1 = 0f
    var norm2 = 0f
    for (i in v1.indices) {
      dot += v1[i] * v2[i]
      norm1 += v1[i] * v1[i]
      norm2 += v2[i] * v2[i]
    }
    val denom = Math.sqrt(norm1.toDouble()) * Math.sqrt(norm2.toDouble())
    return if (denom > 0) (dot / denom).toFloat() else 0f
  }
}

/** Adapter delegating embedding extraction across diverse content types to [OnDeviceEmbedder]. */
private class DelegatingEmbeddingProvider(private val embedder: OnDeviceEmbedder?) :
  EmbeddingProvider {
  private val pendingMultimodalParts = ThreadLocal<List<EmbedPart>?>()

  fun <T> withMultimodalParts(parts: List<EmbedPart>, block: () -> T): T {
    pendingMultimodalParts.set(parts)
    return try {
      block()
    } finally {
      pendingMultimodalParts.remove()
    }
  }

  override fun embedContent(content: List<Any>): FloatArray? {
    val activeEmbedder = checkNotNull(embedder) { "On-device embedder is not configured." }
    check(activeEmbedder.isAvailable()) { "On-device embedder is not available on device." }
    pendingMultimodalParts.get()?.let { parts ->
      return activeEmbedder.generateMultimodalEmbedding(parts)
    }
    val embedParts = content.mapNotNull { item ->
      when (item) {
        is EmbedPart -> item
        is String -> EmbedPart.Text(item)
        is ByteArray -> EmbedPart.Image(item)
        is AudioData -> audioDataToEmbedPart(item)
        else -> null
      }
    }
    if (embedParts.isEmpty()) return null
    if (embedParts.size == 1) {
      when (val single = embedParts.first()) {
        is EmbedPart.Text -> return activeEmbedder.generateTextEmbedding(single.text)
        is EmbedPart.Image -> return activeEmbedder.generateImageEmbedding(single.imageData)
        is EmbedPart.Audio -> {}
      }
    }
    if (embedParts.all { it is EmbedPart.Image }) {
      val images = embedParts.map { (it as EmbedPart.Image).imageData }
      return activeEmbedder.generateImagesEmbedding(images)
    }
    return activeEmbedder.generateMultimodalEmbedding(embedParts)
  }

  private fun audioDataToEmbedPart(audio: AudioData): EmbedPart.Audio? {
    val floatSamples = audio.buffer
    if (floatSamples.isEmpty()) return null
    val pcm =
      ShortArray(floatSamples.size) { i ->
        (floatSamples[i] * Short.MAX_VALUE.toFloat())
          .roundToInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
          .toShort()
      }
    return EmbedPart.Audio(pcm, audio.format.sampleRate.roundToInt())
  }
}

/**
 * Service providing vector storage and embedding retrieval powered by MediaPipe
 * [SemanticRetriever].
 */
open class SemanticRetrievalService(
  val databaseName: String = DEFAULT_DATABASE_NAME,
  private val context: Context? = null,
  private val customEmbedder: OnDeviceEmbedder? = null,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val customSemanticRetriever: SemanticRetriever? = null,
  private val customVectorStore: VectorStore? = null,
) : AutoCloseable {
  companion object {
    const val DEFAULT_DATABASE_NAME = "semantic_retrieval.db"
    private val inMemoryVectorStores = ConcurrentHashMap<String, InMemoryVectorStore>()

    /**
     * Column names used in MediaPipe's `SqliteVectorStore` (`rag_vector_store` table).
     *
     * Since MediaPipe's `SqliteMemoryStore::ToMemoryRecord` merges both SQLite column values and
     * parsed JSON user metadata into a single `Metadata` proto before
     * `SqliteVectorStore.toMetadataMap` builds an `ImmutableMap`, user metadata keys matching any
     * of these column names cause an `IllegalArgumentException: Multiple entries with same key`
     * crash unless escaped in the stored JSON.
     */
    val SQLITE_RESERVED_METADATA_KEYS =
      setOf(
        "ROWID",
        "text",
        "embeddings",
        "record_id",
        "content_type",
        "parent_id",
        "parent_text",
        "child_ids",
        "metadata",
      )

    const val USER_METADATA_KEY_PREFIX = "_user_"

    fun encodeMetadataForStore(metadata: Map<String, String>): Map<String, String> =
      metadata.mapKeys { (key, _) ->
        if (key in SQLITE_RESERVED_METADATA_KEYS || key.startsWith(USER_METADATA_KEY_PREFIX)) {
          "$USER_METADATA_KEY_PREFIX$key"
        } else {
          key
        }
      }

    fun decodeMetadataFromStore(metadata: Map<String, String>): Map<String, String> = buildMap {
      for ((key, value) in metadata) {
        if (
          !key.startsWith(USER_METADATA_KEY_PREFIX) && "$USER_METADATA_KEY_PREFIX$key" !in metadata
        ) {
          put(key, value)
        }
      }
      for ((key, value) in metadata) {
        if (key.startsWith(USER_METADATA_KEY_PREFIX)) {
          put(key.removePrefix(USER_METADATA_KEY_PREFIX), value)
        }
      }
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
      if (v1.size != v2.size || v1.isEmpty()) return 0f
      var dot = 0f
      var norm1 = 0f
      var norm2 = 0f
      for (i in v1.indices) {
        dot += v1[i] * v2[i]
        norm1 += v1[i] * v1[i]
        norm2 += v2[i] * v2[i]
      }
      val denom = Math.sqrt(norm1.toDouble()) * Math.sqrt(norm2.toDouble())
      return if (denom > 0) (dot / denom).toFloat() else 0f
    }
  }

  private val onDeviceEmbedder: OnDeviceEmbedder? = customEmbedder

  private val vectorStore: VectorStore by lazy {
    customVectorStore
      ?: run {
        context?.let {
          try {
            SqliteVectorStore(
              it,
              databaseName,
              GemmaEmbeddingModelStore.DEFAULT_EMBEDDING_DIMENSION,
            )
          } catch (t: Throwable) {
            Log.w(
              TAG,
              "SqliteVectorStore initialization failed; falling back to InMemoryVectorStore: ${t.message}",
            )
            inMemoryVectorStores.computeIfAbsent(databaseName) { InMemoryVectorStore() }
          }
        } ?: inMemoryVectorStores.computeIfAbsent(databaseName) { InMemoryVectorStore() }
      }
  }

  private val embeddingProvider: DelegatingEmbeddingProvider by lazy {
    DelegatingEmbeddingProvider(onDeviceEmbedder)
  }

  private val semanticRetriever: SemanticRetriever? by lazy {
    customSemanticRetriever
      ?: context?.let { ctx ->
        try {
          val components =
            SemanticRetrieverComponents().setVectorStore(vectorStore).addProvider(embeddingProvider)
          SemanticRetriever.createFromComponents(ctx, components)
        } catch (t: Throwable) {
          Log.w(TAG, "SemanticRetriever creation failed: ${t.message}")
          null
        }
      }
  }

  private val _indexedIds = MutableStateFlow<Set<String>>(emptySet())
  val indexedIds: StateFlow<Set<String>> = _indexedIds.asStateFlow()

  /** Guards the one-time load performed by [ensureIndexLoaded]. */
  private val loadMutex = Mutex()
  @Volatile private var indexLoaded = false
  @Volatile private var isClosed = false

  /**
   * Initializes the on-device embedder.
   *
   * @param onError invoked synchronously on the calling thread if initialization fails. When null,
   *   the failure is surfaced as a Toast instead. Anything thrown by [onError] propagates to the
   *   caller.
   */
  fun initializeEmbedder(onError: ((Throwable) -> Unit)? = null) {
    try {
      onDeviceEmbedder?.initialize()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to initialize on-device embedder", t)
      if (onError != null) {
        onError(t)
        return
      }
      context?.let { ctx ->
        Handler(Looper.getMainLooper()).post {
          Toast.makeText(
              ctx,
              "Failed to initialize on-device embedder: ${t.message}",
              Toast.LENGTH_SHORT,
            )
            .show()
        }
      }
    }
  }

  /**
   * Reads the indexed record IDs from the vector store, replacing [indexedIds].
   *
   * Safe to call repeatedly: callers use it to pick up rows written by another process (such as the
   * indexing worker). Use [ensureIndexLoaded] instead when you only need the IDs to be present.
   */
  suspend fun loadFromDatabase() {
    withContext(ioDispatcher) { loadIndexedIdsBlocking(replaceExisting = true) }
  }

  /**
   * Loads the indexed IDs once, if they have not been loaded yet.
   *
   * Every suspend entry point that consults [indexedIds] funnels through here, so callers observe a
   * populated set without the service having to touch the database from its constructor. The lock
   * keeps concurrent first calls from each running their own query.
   */
  private suspend fun ensureIndexLoaded() {
    if (indexLoaded) return
    loadMutex.withLock {
      if (indexLoaded) return
      withContext(ioDispatcher) { loadIndexedIdsBlocking() }
    }
  }

  /** Body of [loadFromDatabase]. Performs blocking disk I/O; call only from [ioDispatcher]. */
  private fun loadIndexedIdsBlocking(replaceExisting: Boolean = false) {
    if (isClosed) return
    // Marked up front so a failed or empty load does not make every later call retry the query.
    indexLoaded = true

    try {
      val ids = vectorStore.getAllRecordIds().toSet()
      if (replaceExisting) {
        _indexedIds.value = ids
      } else {
        _indexedIds.update { it + ids }
      }
      Log.d(TAG, "Loaded ${ids.size} vector records from database: $databaseName")
    } catch (e: Exception) {
      Log.d(TAG, "Table or database not initialized yet in $databaseName: ${e.message}")
    }
  }

  fun saveToDisk() {}

  fun addIndexedIds(ids: Collection<String>) {
    if (ids.isEmpty()) return
    _indexedIds.update { it + ids }
  }

  fun isRecordIndexed(id: String): Boolean = _indexedIds.value.contains(id)

  fun getIndexedRecordCount(): Int = _indexedIds.value.size

  suspend fun fetchRecordIdentifiers(): Set<String> {
    ensureIndexLoaded()
    return withContext(ioDispatcher) { _indexedIds.value }
  }

  suspend fun fetchRecord(id: String): SemanticRetrievalResult? {
    ensureIndexLoaded()
    return withContext(ioDispatcher) {
      if (id !in _indexedIds.value) return@withContext null
      val record = vectorStore.get(listOf(id)).firstOrNull() ?: return@withContext null
      SemanticRetrievalResult(
        id = record.id,
        embeddings = record.embeddings,
        metadata = decodeMetadataFromStore(record.metadata.orEmpty()),
        similarityScore = null,
      )
    }
  }

  suspend fun addRecord(
    id: String,
    imageData: ByteArray,
    metadata: Map<String, String> = emptyMap(),
  ): Unit = addRecord(id, listOf(imageData), metadata)

  suspend fun addRecord(
    id: String,
    imageDataList: List<ByteArray>,
    metadata: Map<String, String> = emptyMap(),
  ) {
    ensureIndexLoaded()
    withContext(ioDispatcher) {
      if (imageDataList.isEmpty()) return@withContext
      val taskParts: List<Part> = imageDataList.map { ImagePart(id.toUri(), it) }
      upsertTaskParts(id, taskParts, metadata) { generateEmbeddingForImages(imageDataList) }
    }
  }

  /**
   * Indexes [parts] as a single record under [id], embedding them in the order they are listed.
   *
   * Everything other than the embedding call matches the image-only [addRecord]: the stored record,
   * its metadata encoding, and [indexedIds] bookkeeping are modality agnostic.
   */
  suspend fun addMultimodalRecord(
    id: String,
    parts: List<EmbedPart>,
    metadata: Map<String, String> = emptyMap(),
  ) {
    ensureIndexLoaded()
    withContext(ioDispatcher) {
      if (parts.isEmpty()) return@withContext
      val taskParts: List<Part> = parts.map { it.toTaskPart(id.toUri()) }
      embeddingProvider.withMultimodalParts(parts) {
        upsertTaskParts(id, taskParts, metadata) { generateEmbeddingForParts(parts) }
      }
    }
  }

  private fun upsertTaskParts(
    id: String,
    taskParts: List<Part>,
    metadata: Map<String, String>,
    fallbackEmbedding: () -> FloatArray,
  ) {
    val encodedMetadata = encodeMetadataForStore(metadata)
    val retriever = semanticRetriever
    if (retriever != null) {
      retriever.insertContent(id, taskParts, encodedMetadata)
      _indexedIds.update { it + id }
      return
    }
    val embedding = fallbackEmbedding()
    val record =
      RetrievalRecord(
        id,
        taskParts,
        embedding,
        encodedMetadata,
        /* parentId= */ null,
        /* childIds= */ null,
      )
    vectorStore.upsert(listOf(record))
    _indexedIds.update { it + id }
  }

  /**
   * Whether the configured embedder's model can consume audio input, or null when that cannot be
   * determined because no embedder is configured or its model is not on the device yet.
   *
   * Callers must treat null as "unknown" rather than "no": reading it as a definite no would, for
   * example, make an index built with audio look stale purely because the model was unavailable.
   */
  fun supportsAudioEmbedding(): Boolean? {
    val embedder = onDeviceEmbedder ?: return null
    if (!embedder.isAvailable()) return null
    return embedder.supportsAudio()
  }

  suspend fun deleteRecord(id: String) {
    ensureIndexLoaded()
    withContext(ioDispatcher) {
      try {
        semanticRetriever?.delete(listOf(id))
      } catch (e: Exception) {
        // Fall back to vectorStore delete
      }
      vectorStore.delete(listOf(id))
      _indexedIds.update { it - id }
    }
  }

  suspend fun deleteAllRecords() {
    ensureIndexLoaded()
    withContext(ioDispatcher) {
      val ids = _indexedIds.value.toList()
      if (ids.isNotEmpty()) {
        try {
          semanticRetriever?.delete(ids)
        } catch (e: Exception) {
          // Fall back to vectorStore delete
        }
        vectorStore.delete(ids)
      } else {
        inMemoryVectorStores[databaseName]?.close()
      }
      _indexedIds.value = emptySet()
    }
  }

  /**
   * Deletes every record whose ID starts with [idPrefix] and returns how many were removed.
   *
   * Deleting by ID (rather than by metadata filter) keeps [indexedIds] exactly in sync, since the
   * underlying store's metadata-delete API does not report which rows it removed.
   */
  suspend fun deleteRecordsWithIdPrefix(idPrefix: String): Int {
    ensureIndexLoaded()
    return withContext(ioDispatcher) {
      val ids = _indexedIds.value.filter { it.startsWith(idPrefix) }
      if (ids.isEmpty()) return@withContext 0
      try {
        semanticRetriever?.delete(ids)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Not fatal: the vectorStore.delete below runs unconditionally and is the authoritative
        // removal, so a retriever-side failure only means its own bookkeeping may be stale.
        Log.w(TAG, "SemanticRetriever delete failed; relying on VectorStore delete", e)
      }
      vectorStore.delete(ids)
      _indexedIds.update { it - ids.toSet() }
      ids.size
    }
  }

  open suspend fun retrieveEntities(
    queryText: String,
    limit: Int = 1000,
  ): List<SemanticRetrievalResult> =
    retrieveEntitiesInternal(queryText, limit, metadataFilter = emptyMap())

  /**
   * Retrieves entities matching [queryText], restricted to records whose metadata contains every
   * entry in [metadataFilter].
   *
   * For example, passing `mapOf("parent_id" to videoId)` scopes the search to the records belonging
   * to a single video. The filter is applied inside the vector store, so [limit] applies to the
   * filtered candidate set rather than to the whole database.
   */
  open suspend fun retrieveEntities(
    queryText: String,
    metadataFilter: Map<String, String>,
    limit: Int = 1000,
  ): List<SemanticRetrievalResult> =
    if (metadataFilter.isEmpty()) {
      retrieveEntities(queryText, limit)
    } else {
      retrieveEntitiesInternal(queryText, limit, metadataFilter)
    }

  /**
   * Shared implementation behind both [retrieveEntities] overloads.
   *
   * An empty [metadataFilter] means "no filtering".
   */
  private suspend fun retrieveEntitiesInternal(
    queryText: String,
    limit: Int,
    metadataFilter: Map<String, String> = emptyMap(),
  ): List<SemanticRetrievalResult> {
    ensureIndexLoaded()
    return withContext(ioDispatcher) {
      val encodedFilter =
        if (metadataFilter.isEmpty()) emptyMap() else encodeMetadataForStore(metadataFilter)
      val retriever = semanticRetriever
      if (retriever != null) {
        try {
          val records =
            if (encodedFilter.isEmpty()) {
              retriever.retrieve(queryText, limit)
            } else {
              retriever.retrieve(queryText, limit, encodedFilter)
            }
          // A retrieve that completes without throwing is authoritative, including when it matches
          // nothing. Falling through to the fallback below would re-run the on-device text
          // embedding and a second store search on every zero-match query.
          if (records.isEmpty()) return@withContext emptyList<SemanticRetrievalResult>()

          val embeddingsMap = vectorStore.get(records.map { it.id() }).associateBy { it.id }
          return@withContext records
            .map { record ->
              SemanticRetrievalResult(
                id = record.id(),
                embeddings = embeddingsMap[record.id()]?.embeddings ?: FloatArray(0),
                metadata = decodeMetadataFromStore(record.metadata().orEmpty()),
                similarityScore = record.score().toFloat(),
              )
            }
            .distinctBy { it.id }
            .sortedByDescending { it.similarityScore ?: 0f }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(
            TAG,
            "SemanticRetriever retrieve failed; falling back to direct VectorStore search",
            e,
          )
        }
      }
      val queryVec = generateEmbeddingForText(queryText)
      val records =
        if (encodedFilter.isEmpty()) {
          vectorStore.search(queryVec, limit)
        } else {
          vectorStore.search(queryVec, limit, encodedFilter)
        }
      records
        .map { record ->
          val emb = record.embeddings
          SemanticRetrievalResult(
            id = record.id,
            embeddings = emb,
            metadata = decodeMetadataFromStore(record.metadata.orEmpty()),
            similarityScore = if (emb.isNotEmpty()) cosineSimilarity(queryVec, emb) else null,
          )
        }
        .distinctBy { it.id }
        .sortedByDescending { it.similarityScore ?: 0f }
    }
  }

  open suspend fun retrieveEntities(
    imageData: ByteArray,
    seedId: String? = null,
    limit: Int = 1000,
  ): List<SemanticRetrievalResult> {
    ensureIndexLoaded()
    return withContext(ioDispatcher) {
      val queryVec = generateEmbeddingForImage(imageData)
      val records = vectorStore.search(queryVec, limit)
      val results =
        records
          .map { record ->
            val emb = record.embeddings
            val score = if (emb.isNotEmpty()) cosineSimilarity(queryVec, emb) else null
            SemanticRetrievalResult(
              id = record.id,
              embeddings = emb,
              metadata = decodeMetadataFromStore(record.metadata.orEmpty()),
              similarityScore = score,
            )
          }
          .distinctBy { it.id }
          .sortedByDescending { it.similarityScore ?: 0f }
      if (seedId != null) {
        results.filter { it.id != seedId }
      } else {
        results
      }
    }
  }

  private fun generateEmbeddingForImage(imageData: ByteArray): FloatArray {
    return generateEmbeddingForImages(listOf(imageData))
  }

  private fun generateEmbeddingForImages(imageDataList: List<ByteArray>): FloatArray {
    val embedder = checkNotNull(onDeviceEmbedder) { "On-device embedder is not configured." }
    check(embedder.isAvailable()) { "On-device embedder is not available on device." }
    return checkNotNull(embedder.generateImagesEmbedding(imageDataList)) {
      "On-device embedder failed to generate image embedding"
    }
  }

  private fun generateEmbeddingForParts(parts: List<EmbedPart>): FloatArray {
    val embedder = checkNotNull(onDeviceEmbedder) { "On-device embedder is not configured." }
    check(embedder.isAvailable()) { "On-device embedder is not available on device." }
    return checkNotNull(embedder.generateMultimodalEmbedding(parts)) {
      "On-device embedder failed to generate multimodal embedding"
    }
  }

  private fun generateEmbeddingForText(text: String): FloatArray {
    val embedder = checkNotNull(onDeviceEmbedder) { "On-device embedder is not configured." }
    check(embedder.isAvailable()) { "On-device embedder is not available on device." }
    return checkNotNull(embedder.generateTextEmbedding(text)) {
      "On-device embedder failed to generate text embedding"
    }
  }

  override fun close() {
    if (isClosed) return
    isClosed = true
    try {
      semanticRetriever?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing SemanticRetriever", e)
    }
    try {
      vectorStore.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing VectorStore", e)
    }
  }
}
