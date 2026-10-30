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
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AGTeacherGameSounds"

/** The sound effects played by the `Vocab Popper` and `Vocab Runner` study games. */
interface XrLanguageTeacherGameSoundEffects {
  /** Whether the effects are muted; muted effects are not played. */
  var isMuted: Boolean

  /** Synthesizes the effects and warms up audio playback, so the first effect never stalls. */
  fun prepare()

  /**
   * Plays the bright ascending two-tone chime of a word cleared in `Vocab Popper` or `Vocab
   * Runner`.
   */
  fun playPop()

  /** Plays the short descending buzz of Buddy bumping into an obstacle in `Vocab Runner`. */
  fun playCollision()

  /** Stops and releases audio playback. [prepare] can set it up again. */
  fun close()

  /** Releases the effects for good, when their owner is destroyed; they can't be used after. */
  fun release()
}

/** Procedurally synthesized 16-bit mono PCM tones of the study game sound effects. */
internal object XrLanguageTeacherGameTones {
  /** Sample rate of the synthesized effects, in Hz. */
  const val SAMPLE_RATE_HZ = 24_000

  /** Renders the pop chime: 587.33 Hz then 880 Hz, 0.14 s, under a half-sine envelope. */
  fun renderPop(): ShortArray =
    renderChime(startHz = 587.33, endHz = 880.0, durationSeconds = 0.14, amplitude = 0.24)

  /** Renders the collision buzz: 220 Hz falling to 110 Hz over 0.22 s. */
  fun renderCollision(): ShortArray =
    renderSweep(startHz = 220.0, endHz = 110.0, durationSeconds = 0.22, amplitude = 0.26)

  /**
   * Renders [startHz] for the first half and [endHz] for the second half of [durationSeconds],
   * shaped by a `sin(pi * t)` envelope so it starts and ends silent.
   */
  fun renderChime(
    startHz: Double,
    endHz: Double,
    durationSeconds: Double,
    amplitude: Double,
  ): ShortArray {
    val frameCount = frameCountFor(durationSeconds)
    val midpoint = frameCount / 2
    val lastIndex = max(frameCount - 1, 1)
    var phase = 0.0
    return ShortArray(frameCount) { index ->
      val frequency = if (index < midpoint) startHz else endHz
      phase += 2.0 * PI * frequency / SAMPLE_RATE_HZ
      val envelope = sin(PI * index / lastIndex)
      toPcm16(sin(phase) * amplitude * envelope)
    }
  }

  /**
   * Renders a linear frequency sweep from [startHz] to [endHz] over [durationSeconds], fading
   * linearly to a quarter of [amplitude].
   */
  fun renderSweep(
    startHz: Double,
    endHz: Double,
    durationSeconds: Double,
    amplitude: Double,
  ): ShortArray {
    val frameCount = frameCountFor(durationSeconds)
    val lastIndex = max(frameCount - 1, 1)
    var phase = 0.0
    return ShortArray(frameCount) { index ->
      val t = index.toDouble() / lastIndex
      val frequency = startHz + (endHz - startHz) * t
      phase += 2.0 * PI * frequency / SAMPLE_RATE_HZ
      val envelope = 1.0 - t * 0.75
      toPcm16(sin(phase) * amplitude * envelope)
    }
  }

  private fun frameCountFor(durationSeconds: Double): Int =
    (durationSeconds * SAMPLE_RATE_HZ).roundToInt()

  private fun toPcm16(sample: Double): Short =
    (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort()
}

/**
 * Plays the study game sound effects through one static [AudioTrack] per effect, using game
 * sonification audio attributes so the effects mix with (and never duck or stop) the microphone
 * capture of the game's speech recognition.
 *
 * Tracks are created, replayed, and released on a single background thread taken from
 * [audioDispatcher], so the game loop on the main thread never blocks on audio.
 */
class XrLanguageTeacherGameSoundPlayer(
  audioDispatcher: CoroutineDispatcher,
  private val createTrack: (samples: ShortArray) -> AudioTrack = ::createStaticTrack,
) : XrLanguageTeacherGameSoundEffects {
  private val scope = CoroutineScope(SupervisorJob() + audioDispatcher.limitedParallelism(1))

  // Only touched on the single audio thread.
  private var popTrack: AudioTrack? = null
  private var collisionTrack: AudioTrack? = null
  private var didFailSetup = false

  @Volatile override var isMuted: Boolean = false

  override fun prepare() {
    scope.launch { ensureTracks() }
  }

  override fun playPop() = play { popTrack }

  override fun playCollision() = play { collisionTrack }

  override fun close() {
    scope.launch {
      for (track in listOfNotNull(popTrack, collisionTrack)) {
        releaseTrack(track)
      }
      popTrack = null
      collisionTrack = null
      didFailSetup = false
    }
  }

  override fun release() {
    close()
    scope.launch { scope.cancel() }
  }

  private fun play(track: () -> AudioTrack?) {
    if (isMuted) return
    scope.launch {
      ensureTracks()
      val audioTrack = track() ?: return@launch
      try {
        // A static track replays from its start after stop() and reloadStaticData().
        if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
          audioTrack.stop()
        }
        if (audioTrack.reloadStaticData() == AudioTrack.SUCCESS) {
          audioTrack.play()
        }
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Failed to play a game sound effect", e)
      }
    }
  }

  private fun ensureTracks() {
    if (didFailSetup || popTrack != null) return
    try {
      popTrack = createTrack(XrLanguageTeacherGameTones.renderPop())
      collisionTrack = createTrack(XrLanguageTeacherGameTones.renderCollision())
    } catch (e: Exception) {
      // Sound effects are optional; the games keep running silently.
      Log.w(TAG, "Game sound effects are unavailable", e)
      didFailSetup = true
      for (track in listOfNotNull(popTrack, collisionTrack)) {
        releaseTrack(track)
      }
      popTrack = null
      collisionTrack = null
    }
  }

  private fun releaseTrack(track: AudioTrack) {
    try {
      if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
        track.stop()
      }
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Failed to stop a game sound effect", e)
    }
    track.release()
  }
}

/** Creates a static game sonification track holding the 16-bit mono [samples]. */
private fun createStaticTrack(samples: ShortArray): AudioTrack {
  val track =
    AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(XrLanguageTeacherGameTones.SAMPLE_RATE_HZ)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
      .build()
  val written = track.write(samples, 0, samples.size)
  if (written != samples.size) {
    track.release()
    throw IllegalStateException("Wrote $written of ${samples.size} game sound samples")
  }
  return track
}
