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

package com.google.ai.edge.gallery.worker

import com.google.ai.edge.gallery.data.HuggingFaceUrls
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong

/**
 * When a download's connection counts as too slow: its throughput stayed below [minBytesPerSecond]
 * for [windowMs] while the file was streaming. Such a connection is closed and reopened, resuming
 * from the partial file, at most [maxReconnects] times per file, and only when the server resumes
 * downloads. After that, the download keeps going on its connection: slowness alone never stops a
 * download.
 */
data class SlowConnectionPolicy(
  val minBytesPerSecond: Long = SLOW_CONNECTION_MIN_BYTES_PER_SECOND,
  val windowMs: Long = SLOW_CONNECTION_WINDOW_MS,
  val maxReconnects: Int = SLOW_CONNECTION_MAX_RECONNECTS,
)

/** The throughput under which a connection counts as slow, if it stays there for a while. */
const val SLOW_CONNECTION_MIN_BYTES_PER_SECOND = 100L * 1024

/** How long the throughput must stay low for a connection to count as slow. */
const val SLOW_CONNECTION_WINDOW_MS = 30_000L

/** How many times a slow connection is reopened for each file before it is kept however slow. */
const val SLOW_CONNECTION_MAX_RECONNECTS = 3

/**
 * Tells whether a connection that started streaming at [startMs] is too slow, per [policy]: the
 * bytes read over the last [SlowConnectionPolicy.windowMs] (or more, when reads are far apart) are
 * fewer than [SlowConnectionPolicy.minBytesPerSecond] would give. Nothing is too slow until the
 * connection has streamed for a whole window.
 *
 * Times are in milliseconds from any fixed origin.
 */
class SlowConnectionDetector(private val policy: SlowConnectionPolicy, startMs: Long) {
  /**
   * The total bytes read at past times, as (time, bytes) pairs, oldest first. A sample is taken at
   * most every tenth of a window, so the list stays short however fast the connection is.
   */
  private val samples = ArrayDeque<Pair<Long, Long>>().apply { addLast(startMs to 0L) }
  private var totalBytes = 0L

  /** Records that [bytes] more bytes were read at [nowMs], and returns whether it's too slow. */
  fun onBytesRead(bytes: Long, nowMs: Long): Boolean {
    totalBytes += bytes
    if (nowMs - samples.last().first >= policy.windowMs / SAMPLES_PER_WINDOW) {
      samples.addLast(nowMs to totalBytes)
    }
    // Measure from the newest sample that is at least a window old.
    val windowStartMs = nowMs - policy.windowMs
    while (samples.size >= 2 && samples[1].first <= windowStartMs) {
      samples.removeFirst()
    }
    val (baseMs, baseBytes) = samples.first()
    val elapsedMs = nowMs - baseMs
    if (elapsedMs < policy.windowMs) {
      return false
    }
    return (totalBytes - baseBytes) * 1000 < policy.minBytesPerSecond * elapsedMs
  }

  private companion object {
    const val SAMPLES_PER_WINDOW = 10
  }
}

/**
 * When a download's connection counts as stalled compared to faster ones: a connection can be fast
 * enough for [SlowConnectionPolicy] and still far slower than the network allows, from its first
 * byte to its last, until it is reopened.
 *
 * After [graceMs] of streaming (TCP slow start, redirects), the connection's throughput over the
 * last [windowMs] (its window rate) is compared to a reference rate: the best window rate of an
 * earlier connection of the same file, or of another connection in this app process over the last
 * [ThroughputReferenceStore] TTL. The connection is reopened, resuming from the partial file, when:
 * - its window rate is below [ratio] times the reference rate (a relative reconnect); or
 * - there's no reference rate, its window rate is below [exploratoryFloorBytesPerSecond], and no
 *   exploratory reconnect was done for the file yet (an exploratory reconnect, to find out whether
 *   a new connection is faster).
 *
 * At most [maxReconnectsPerFile] relative and exploratory reconnects are done per file, apart from
 * the [SlowConnectionPolicy] ones. After that, the download keeps going on its connection: this
 * rule never stops a download.
 */
data class RelativeStallPolicy(
  val graceMs: Long = RELATIVE_STALL_GRACE_MS,
  val windowMs: Long = RELATIVE_STALL_WINDOW_MS,
  val ratio: Double = RELATIVE_STALL_RATIO,
  val exploratoryFloorBytesPerSecond: Long = EXPLORATORY_RECONNECT_FLOOR_BYTES_PER_SECOND,
  val maxReconnectsPerFile: Int = MAX_RELATIVE_RECONNECTS_PER_FILE,
) {
  init {
    require(windowMs > 0L) { "The window must be positive: $windowMs" }
  }
}

/** How long the start of each connection is ignored (TCP slow start, redirects). */
const val RELATIVE_STALL_GRACE_MS = 3_000L

/** How long the window that a connection's throughput is measured over is. */
const val RELATIVE_STALL_WINDOW_MS = 15_000L

/** The fraction of the reference throughput under which a connection counts as stalled. */
const val RELATIVE_STALL_RATIO = 0.25

/**
 * The throughput under which a connection is reopened once when there's no reference to compare.
 */
const val EXPLORATORY_RECONNECT_FLOOR_BYTES_PER_SECOND = 5L * 1_000_000

/** How many relative and exploratory reconnects are done for each file at most. */
const val MAX_RELATIVE_RECONNECTS_PER_FILE = 3

/** How long a connection's throughput serves as a reference for the other connections. */
const val REFERENCE_THROUGHPUT_TTL_MS = 600_000L

/** Why a connection is reopened by the [RelativeStallPolicy] rule. */
enum class RelativeStallReconnect {
  /** The connection is much slower than the reference throughput. */
  RELATIVE,
  /** There's no reference throughput and the connection is slow: try whether another is faster. */
  EXPLORATORY,
}

/**
 * Returns why a connection whose window rate is [windowBytesPerSecond] should be reopened per
 * [policy], or `null` if it should keep going.
 *
 * @param referenceBytesPerSecond The reference throughput, from connections other than this one, if
 *   any.
 * @param reconnects The relative and exploratory reconnects already done for the file.
 * @param exploratoryReconnectDone Whether an exploratory reconnect was already done for the file.
 */
internal fun relativeStallReconnect(
  policy: RelativeStallPolicy,
  windowBytesPerSecond: Long,
  referenceBytesPerSecond: Long?,
  reconnects: Int,
  exploratoryReconnectDone: Boolean,
): RelativeStallReconnect? =
  when {
    reconnects >= policy.maxReconnectsPerFile -> null
    referenceBytesPerSecond != null ->
      RelativeStallReconnect.RELATIVE.takeIf {
        windowBytesPerSecond < policy.ratio * referenceBytesPerSecond
      }
    !exploratoryReconnectDone && windowBytesPerSecond < policy.exploratoryFloorBytesPerSecond ->
      RelativeStallReconnect.EXPLORATORY
    else -> null
  }

/**
 * The best window rates of the connections in this app process over the last [ttlMs], which serve
 * as reference throughputs for the [RelativeStallPolicy] rule. It is kept in memory only.
 *
 * Each connection keeps its best window rate, which is replaced by a later window rate that is
 * higher or once it is [ttlMs] old. So a rate older than [ttlMs] never counts, though a lower peak
 * that came after the best one can be forgotten.
 *
 * The rates are measured on the default network of a [NetworkGenerationTracker] generation. Once a
 * caller passes a newer generation, the rates of older ones are forgotten, and the rates of callers
 * still on an older generation are ignored, as they were measured on another network.
 *
 * It is thread-safe, as downloads run in parallel. Times are in milliseconds from any fixed origin,
 * the same for all callers.
 */
class ThroughputReferenceStore(private val ttlMs: Long = REFERENCE_THROUGHPUT_TTL_MS) {
  private class Entry(val bytesPerSecond: Long, val atMs: Long)

  private val lastConnectionId = AtomicLong()
  // Guarded by this.
  private val entries = HashMap<Long, Entry>()
  private var networkGeneration = 0L

  /** Returns a new id, unique in this store, to record the window rates of a connection. */
  fun newConnectionId(): Long = lastConnectionId.incrementAndGet()

  /**
   * Records that connection [connectionId] got [bytesPerSecond] over a window ending at [nowMs], on
   * the default network of [networkGeneration].
   */
  @Synchronized
  fun record(connectionId: Long, bytesPerSecond: Long, nowMs: Long, networkGeneration: Long) {
    if (bytesPerSecond <= 0L || !isCurrent(networkGeneration)) {
      return
    }
    val entry = entries[connectionId]
    if (entry == null || bytesPerSecond >= entry.bytesPerSecond || nowMs - entry.atMs >= ttlMs) {
      entries[connectionId] = Entry(bytesPerSecond, nowMs)
    }
  }

  /**
   * Returns the best window rate recorded over the last [ttlMs], on the default network of
   * [networkGeneration], by a connection other than [excludedConnectionId], or `null` if there's
   * none.
   */
  @Synchronized
  fun bestBytesPerSecond(excludedConnectionId: Long, nowMs: Long, networkGeneration: Long): Long? {
    if (!isCurrent(networkGeneration)) {
      return null
    }
    entries.values.removeAll { nowMs - it.atMs >= ttlMs }
    // Only positive rates are recorded, so 0 means there's none.
    var bestBytesPerSecond = 0L
    for ((connectionId, entry) in entries) {
      if (connectionId != excludedConnectionId) {
        bestBytesPerSecond = maxOf(bestBytesPerSecond, entry.bytesPerSecond)
      }
    }
    return bestBytesPerSecond.takeIf { it > 0L }
  }

  /**
   * Returns whether [networkGeneration] is the store's generation, after forgetting the rates of
   * older generations if it is newer.
   */
  private fun isCurrent(networkGeneration: Long): Boolean {
    if (networkGeneration > this.networkGeneration) {
      entries.clear()
      this.networkGeneration = networkGeneration
    }
    return networkGeneration == this.networkGeneration
  }
}

/**
 * The [RelativeStallPolicy] state of one file's download, across its connections. It is used by one
 * download at a time, so it isn't thread-safe.
 */
class RelativeStallFileState {
  /** The relative and exploratory reconnects done for the file. */
  var reconnects = 0
    private set

  /** Whether an exploratory reconnect was done for the file. */
  var exploratoryReconnectDone = false
    private set

  /** The best window rate of the file's earlier connections, if any. */
  var earlierConnectionsBestBytesPerSecond: Long? = null
    private set

  private var currentConnectionBestBytesPerSecond: Long? = null

  /**
   * The [NetworkGenerationTracker] generation of the default network that the window rates were
   * measured on, or `null` before the first connection.
   */
  private var networkGeneration: Long? = null

  /**
   * Forgets the window rates measured on an older default network than [networkGeneration]'s, but
   * not the reconnects done.
   */
  fun onNetworkGeneration(networkGeneration: Long) {
    if (networkGeneration == this.networkGeneration) {
      return
    }
    this.networkGeneration = networkGeneration
    earlierConnectionsBestBytesPerSecond = null
    currentConnectionBestBytesPerSecond = null
  }

  /** Starts a new connection, after which the current one counts as an earlier connection. */
  fun startConnection() {
    earlierConnectionsBestBytesPerSecond =
      listOfNotNull(earlierConnectionsBestBytesPerSecond, currentConnectionBestBytesPerSecond)
        .maxOrNull()
    currentConnectionBestBytesPerSecond = null
  }

  /** Records that the current connection got [bytesPerSecond] over a window. */
  fun recordWindowRate(bytesPerSecond: Long) {
    if (bytesPerSecond <= 0L) {
      return
    }
    currentConnectionBestBytesPerSecond =
      maxOf(currentConnectionBestBytesPerSecond ?: 0L, bytesPerSecond)
  }

  /** Records that the current connection is reopened because of [reconnect]. */
  fun recordReconnect(reconnect: RelativeStallReconnect) {
    reconnects++
    if (reconnect == RelativeStallReconnect.EXPLORATORY) {
      exploratoryReconnectDone = true
    }
  }
}

/**
 * A connection reopened by the [RelativeStallPolicy] rule.
 *
 * @property reason Why the connection is reopened.
 * @property windowBytesPerSecond The connection's throughput over the last window.
 * @property referenceBytesPerSecond The throughput it was compared to, if any.
 * @property reconnects The relative and exploratory reconnects of the file, this one included.
 */
data class RelativeStallTrigger(
  val reason: RelativeStallReconnect,
  val windowBytesPerSecond: Long,
  val referenceBytesPerSecond: Long?,
  val reconnects: Int,
)

/**
 * Tells whether a connection that started streaming at [startMs] should be reopened per [policy].
 * It starts a new connection in [fileState], and records the connection's window rates in
 * [fileState] and [referenceStore].
 *
 * The window rate is measured from the first read after the grace period, so that nothing is
 * reopened before the connection has streamed for the grace period and a whole window.
 *
 * When the default network changes ([networkGeneration] returns a new generation), the rates
 * measured on the earlier network are forgotten and the connection is measured again from then on,
 * grace period included, so that a network change never reopens it right away. Its rates then count
 * for the new network, even in the rare case where its socket stays on the earlier one (e.g. a VPN
 * that came up after it connected).
 *
 * Times are in milliseconds from the same origin as [referenceStore]'s.
 *
 * @param networkGeneration Returns the current [NetworkGenerationTracker] generation.
 */
class RelativeStallDetector(
  private val policy: RelativeStallPolicy,
  private val fileState: RelativeStallFileState,
  private val referenceStore: ThroughputReferenceStore,
  private val networkGeneration: () -> Long,
  startMs: Long,
) {
  private val connectionId = referenceStore.newConnectionId()
  private var graceEndMs = startMs + policy.graceMs
  private var connectionNetworkGeneration = networkGeneration()

  /**
   * The total bytes read at past times after the grace period, as (time, bytes) pairs, oldest
   * first. A sample is taken at most every tenth of a window, so the list stays short.
   */
  private val samples = ArrayDeque<Pair<Long, Long>>()
  private var totalBytes = 0L

  init {
    fileState.onNetworkGeneration(connectionNetworkGeneration)
    fileState.startConnection()
  }

  /**
   * Records that [bytes] more bytes were read at [nowMs], and returns why the connection should be
   * reopened, counting the reconnect in the file's state, or `null` if it should keep going.
   */
  fun onBytesRead(bytes: Long, nowMs: Long): RelativeStallTrigger? {
    totalBytes += bytes
    val currentNetworkGeneration = networkGeneration()
    if (currentNetworkGeneration != connectionNetworkGeneration) {
      connectionNetworkGeneration = currentNetworkGeneration
      fileState.onNetworkGeneration(currentNetworkGeneration)
      samples.clear()
      graceEndMs = nowMs + policy.graceMs
    }
    if (nowMs < graceEndMs) {
      return null
    }
    // Evaluate only when a sample is taken, at most every tenth of a window, rather than on every
    // read, which keeps the reference store's lock off the download's hot path.
    if (
      samples.isNotEmpty() && nowMs - samples.last().first < policy.windowMs / SAMPLES_PER_WINDOW
    ) {
      return null
    }
    samples.addLast(nowMs to totalBytes)
    // Measure from the newest sample that is at least a window old.
    val windowStartMs = nowMs - policy.windowMs
    while (samples.size >= 2 && samples[1].first <= windowStartMs) {
      samples.removeFirst()
    }
    val (baseMs, baseBytes) = samples.first()
    val elapsedMs = nowMs - baseMs
    // The policy's window is positive, so this never divides by zero below.
    if (elapsedMs < policy.windowMs) {
      return null
    }
    val windowBytesPerSecond = (totalBytes - baseBytes) * 1000 / elapsedMs
    // Only other connections count as a reference: this one's own peak doesn't.
    val referenceBytesPerSecond =
      listOfNotNull(
          fileState.earlierConnectionsBestBytesPerSecond,
          referenceStore.bestBytesPerSecond(
            excludedConnectionId = connectionId,
            nowMs = nowMs,
            networkGeneration = connectionNetworkGeneration,
          ),
        )
        .maxOrNull()
    referenceStore.record(
      connectionId = connectionId,
      bytesPerSecond = windowBytesPerSecond,
      nowMs = nowMs,
      networkGeneration = connectionNetworkGeneration,
    )
    fileState.recordWindowRate(windowBytesPerSecond)
    val reason =
      relativeStallReconnect(
        policy = policy,
        windowBytesPerSecond = windowBytesPerSecond,
        referenceBytesPerSecond = referenceBytesPerSecond,
        reconnects = fileState.reconnects,
        exploratoryReconnectDone = fileState.exploratoryReconnectDone,
      ) ?: return null
    fileState.recordReconnect(reason)
    return RelativeStallTrigger(
      reason = reason,
      windowBytesPerSecond = windowBytesPerSecond,
      referenceBytesPerSecond = referenceBytesPerSecond,
      reconnects = fileState.reconnects,
    )
  }

  private companion object {
    const val SAMPLES_PER_WINDOW = 10
  }
}

/** Returns the time in milliseconds since a fixed origin, for measuring durations. */
internal fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000

/** How a download response is written into the file's partial `.tmp` file. */
enum class ResumeAction {
  /** The response carries the rest of the file: append it to the partial file. */
  APPEND,
  /** The response carries the whole file: replace the partial file, if any. */
  OVERWRITE,
  /** The partial file can't be resumed: delete it and request the whole file again. */
  RESTART,
  /** The partial file already holds the whole file: keep it without downloading anything. */
  COMPLETE,
}

internal const val HTTP_RANGE_NOT_SATISFIABLE = 416

/** The start of a `Content-Range` header with a byte range, matched ignoring case. */
private const val CONTENT_RANGE_BYTES_PREFIX = "bytes "

/** The start of the `Content-Range` header of an HTTP 416 response, matched ignoring case. */
private const val CONTENT_RANGE_UNSATISFIED_PREFIX = "bytes */"

/**
 * Returns how to write the response to a download request into the file's partial `.tmp` file.
 *
 * @param responseCode The HTTP status code of the response.
 * @param contentRange The response's `Content-Range` header, if any.
 * @param partialBytes The size of the partial file, which is the first byte that was requested (no
 *   range is requested when it is 0).
 */
internal fun determineResumeAction(
  responseCode: Int,
  contentRange: String?,
  partialBytes: Long,
): ResumeAction {
  if (partialBytes <= 0L) {
    return ResumeAction.OVERWRITE
  }
  return when (responseCode) {
    HttpURLConnection.HTTP_PARTIAL ->
      if (contentRangeStart(contentRange) == partialBytes) ResumeAction.APPEND
      else ResumeAction.RESTART
    // An earlier run can stop after writing the last byte but before renaming the file.
    HTTP_RANGE_NOT_SATISFIABLE ->
      if (contentRangeTotal(contentRange) == partialBytes) ResumeAction.COMPLETE
      else ResumeAction.RESTART
    // HTTP 200 carries the whole file, and error responses are reported as failures.
    else -> ResumeAction.OVERWRITE
  }
}

/**
 * Returns the first byte of a `Content-Range: bytes <start>-<end>/<total>` header, or `null` if
 * there's no header or it can't be parsed.
 */
private fun contentRangeStart(contentRange: String?): Long? =
  contentRange
    ?.trim()
    // The range unit is case-insensitive (RFC 9110, section 14.1).
    ?.takeIf { it.startsWith(CONTENT_RANGE_BYTES_PREFIX, ignoreCase = true) }
    ?.drop(CONTENT_RANGE_BYTES_PREFIX.length)
    ?.substringBefore("-")
    ?.trim()
    ?.toLongOrNull()

/**
 * Returns the total file size in the `Content-Range` header of an HTTP 416 response, which has no
 * byte range, only an asterisk followed by the total size (e.g. `bytes *` + `/5000`). Returns
 * `null` if there's no header or it can't be parsed.
 */
private fun contentRangeTotal(contentRange: String?): Long? =
  contentRange
    ?.trim()
    ?.takeIf { it.startsWith(CONTENT_RANGE_UNSATISFIED_PREFIX, ignoreCase = true) }
    ?.drop(CONTENT_RANGE_UNSATISFIED_PREFIX.length)
    ?.trim()
    ?.toLongOrNull()

/**
 * Returns the `Authorization` header value for downloading [url], or `null` if no header should be
 * sent.
 *
 * The Hugging Face access token is only sent to Hugging Face URLs, so that it never leaks to other
 * hosts, e.g. the extra data files of a Hugging Face model that are hosted elsewhere.
 */
internal fun buildAuthorizationHeaderValue(accessToken: String?, url: String): String? {
  if (accessToken.isNullOrEmpty() || !HuggingFaceUrls.isHuggingFaceUrl(url)) {
    return null
  }
  return "Bearer $accessToken"
}
