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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.services.tts.startOnFirstFrame
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGPcmAudioStreamPlayer"
private const val SAMPLE_RATE_HZ = 24000
private const val MIN_AUDIO_BUFFER_BYTES = 4096 * 4
private const val BYTES_PER_FRAME = 2L
private const val DRAIN_SAFETY_MARGIN_MS = 250L

/**
 * Encapsulates low-level streaming [AudioTrack] lifecycle management, thread-safe chunk writes,
 * event-driven playback marker drain synchronization, and clean teardown outside ViewModels.
 */
class PcmAudioStreamPlayer
internal constructor(
  private val ioDispatcher: CoroutineDispatcher,
  private val createTrack: () -> AudioTrack,
) {
  @Inject
  constructor(
    @IoDispatcher ioDispatcher: CoroutineDispatcher
  ) : this(ioDispatcher, ::createStreamingTrack)

  private val mutex = Mutex()
  private var activeTrack: AudioTrack? = null
  private var cumulativeFramesWritten: Long = 0L

  /**
   * Streams 16-bit mono PCM chunks from [pcmFlow] to the hardware [AudioTrack] and suspends until
   * all frames written in this call finish playing out or cancellation occurs.
   *
   * @return `true` if at least one PCM byte was written to the audio track.
   */
  suspend fun playStream(pcmFlow: Flow<ByteArray>): Boolean {
    val (track, initialFrames) = getOrCreateTrackWithFrameOffset()
    var totalBytesWritten = 0L
    pcmFlow.collect { pcmChunk ->
      if (pcmChunk.isNotEmpty()) {
        val written = writeChunk(track, pcmChunk)
        check(written >= 0) { "AudioTrack.write failed with error code $written" }
        totalBytesWritten += written.toLong()
      }
    }
    if (totalBytesWritten <= 0L) {
      return false
    }
    awaitDrain(track = track, initialFrames = initialFrames, totalBytesWritten = totalBytesWritten)
    return true
  }

  /**
   * Flushes any trailing buffered frames on the active [AudioTrack] at the end of a multi-segment
   * stream without tearing down prematurely before the hardware mixer reaches the final frame.
   */
  suspend fun finishStream() {
    val (track, totalFrames) =
      mutex.withLock {
        val existing = activeTrack ?: return@withLock null to 0L
        existing to cumulativeFramesWritten
      }
    if (track == null || totalFrames <= 0L) return
    awaitDrain(track = track, initialFrames = 0L, totalBytesWritten = totalFrames * BYTES_PER_FRAME)
  }

  /**
   * Stops playback immediately and discards any buffered audio, but keeps the [AudioTrack] for the
   * next stream so that it doesn't pay the track creation cost again. Call [release] once the
   * player is no longer needed.
   */
  suspend fun stopAndFlush(): Unit =
    withContext(ioDispatcher) {
      mutex.withLock {
        val track = activeTrack ?: return@withLock
        // The playback head restarts at zero after flush(), so frame offsets restart too.
        cumulativeFramesWritten = 0L
        try {
          if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.setPlaybackPositionUpdateListener(null)
            track.pause()
            track.flush()
            track.stop()
          } else {
            activeTrack = null
            track.release()
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed stopping AudioTrack; releasing it", e)
          activeTrack = null
          runCatching { track.release() }
        }
      }
    }

  /** Stops playback immediately and releases any underlying native [AudioTrack]. */
  suspend fun release(): Unit =
    withContext(ioDispatcher) {
      val trackToRelease =
        mutex.withLock {
          val existing = activeTrack
          activeTrack = null
          cumulativeFramesWritten = 0L
          existing
        } ?: return@withContext
      try {
        if (trackToRelease.state == AudioTrack.STATE_INITIALIZED) {
          trackToRelease.setPlaybackPositionUpdateListener(null)
          trackToRelease.pause()
          trackToRelease.flush()
          trackToRelease.stop()
        }
        trackToRelease.release()
      } catch (e: Exception) {
        Log.w(TAG, "Failed releasing AudioTrack", e)
      }
    }

  private suspend fun getOrCreateTrackWithFrameOffset(): Pair<AudioTrack, Long> = mutex.withLock {
    activeTrack?.let { existing ->
      if (existing.state == AudioTrack.STATE_INITIALIZED) {
        if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
          existing.play()
        }
        return@withLock existing to cumulativeFramesWritten
      }
      runCatching { existing.release() }
    }
    val track = createTrack()
    track.play()
    activeTrack = track
    cumulativeFramesWritten = 0L
    track to 0L
  }

  private suspend fun writeChunk(track: AudioTrack, pcmChunk: ByteArray): Int = mutex.withLock {
    if (activeTrack === track && track.state == AudioTrack.STATE_INITIALIZED) {
      val written = track.write(pcmChunk, 0, pcmChunk.size)
      when {
        written > 0 -> cumulativeFramesWritten += written.toLong() / BYTES_PER_FRAME
        written < 0 -> {
          // The track is broken (for example ERROR_DEAD_OBJECT after the audio server restarted),
          // so drop it: the next stream creates a new one instead of reusing it.
          activeTrack = null
          cumulativeFramesWritten = 0L
          try {
            track.release()
          } catch (e: Exception) {
            Log.w(TAG, "Failed releasing a broken AudioTrack", e)
          }
        }
      }
      written
    } else {
      0
    }
  }

  /**
   * Awaits playback completion for [totalBytesWritten] bytes starting from [initialFrames] using
   * [AudioTrack.setNotificationMarkerPosition] and [AudioTrack.OnPlaybackPositionUpdateListener]
   * without fixed-interval polling, bounded by the exact remaining duration as a fallback for unit
   * test environments.
   *
   * Because [createStreamingTrack] configures [startOnFirstFrame], the hardware mixer starts
   * draining on the first written frame without calling [AudioTrack.stop] (which would reset
   * [AudioTrack.getPlaybackHeadPosition] to zero when the track is reused across segments).
   */
  private suspend fun awaitDrain(track: AudioTrack, initialFrames: Long, totalBytesWritten: Long) {
    val totalFramesWritten = totalBytesWritten / BYTES_PER_FRAME
    if (totalFramesWritten <= 0L) return
    if (track.state != AudioTrack.STATE_INITIALIZED) return

    val targetHead = initialFrames + totalFramesWritten
    val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    if (currentHead >= targetHead) return

    val remainingFrames = (targetHead - currentHead).coerceAtLeast(0L)
    val timeoutMs = ((remainingFrames * 1000L) / SAMPLE_RATE_HZ) + DRAIN_SAFETY_MARGIN_MS

    try {
      withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Unit> { cont ->
          val listener =
            object : AudioTrack.OnPlaybackPositionUpdateListener {
              override fun onMarkerReached(t: AudioTrack?) {
                t?.setPlaybackPositionUpdateListener(null)
                if (cont.isActive) {
                  cont.resume(Unit)
                }
              }

              override fun onPeriodicNotification(t: AudioTrack?) {}
            }
          cont.invokeOnCancellation {
            runCatching { track.setPlaybackPositionUpdateListener(null) }
          }
          val handler = Handler(Looper.getMainLooper())
          track.setPlaybackPositionUpdateListener(listener, handler)
          val status =
            track.setNotificationMarkerPosition(
              targetHead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
          val headAfterSet = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
          if (status != AudioTrack.SUCCESS || headAfterSet >= targetHead) {
            track.setPlaybackPositionUpdateListener(null)
            if (cont.isActive) {
              cont.resume(Unit)
            }
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Error awaiting AudioTrack marker drain", e)
    }
  }
}

/** Creates the streaming 24 kHz mono 16-bit [AudioTrack] that the player writes to. */
private fun createStreamingTrack(): AudioTrack {
  val minBufSize =
    AudioTrack.getMinBufferSize(
      SAMPLE_RATE_HZ,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
  val bufferSize = max(minBufSize, MIN_AUDIO_BUFFER_BYTES)
  return AudioTrack.Builder()
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    )
    .setAudioFormat(
      AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE_HZ)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()
    )
    .setBufferSizeInBytes(bufferSize)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()
    .startOnFirstFrame()
}
