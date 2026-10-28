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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.util.Log

/**
 * Standardized glasses earcons bundled under `assets/xr_earcons/` from the official Android XR
 * sound design guide (https://developer.android.com/design/ui/ai-glasses/guides/sound/earcons).
 */
enum class XrEarconType(val assetPath: String) {
  /** Greets the user when the glasses experience opens. */
  APP_OPEN("xr_earcons/app_open.ogg"),
  /** Signals that app-level speech capture has unmuted / started listening. */
  START_RECORDING("xr_earcons/unmute.ogg"),
  /** Signals that app-level speech capture has muted / finished recording and was sent. */
  STOP_RECORDING_SENT("xr_earcons/mute.ogg"),
  /** Signals an error or invalid gesture state (e.g. follow-up swipe with no active session). */
  ERROR("xr_earcons/announcement_alert.ogg"),
  /** Signals that the model is currently busy loading or processing a turn. */
  BUSY("xr_earcons/app_loading.ogg"),
}

/**
 * Plays standardized `.ogg` earcons on smart glasses using [SoundPool].
 *
 * Sounds are decoded asynchronously from `assets/xr_earcons/`; if [play] is called before a sample
 * finishes loading, the sound ID is queued in [pendingSoundId] and played as soon as
 * [SoundPool.OnLoadCompleteListener] reports success. All framework calls are guarded with
 * [runCatching] so JVM and Robolectric unit tests stay green without native audio decoding.
 */
class GlassesEarcons(private val hostContext: Context) {

  private val lock = Any()
  private var soundPool: SoundPool? = null
  private var boundDeviceId: Int = Int.MIN_VALUE
  private var isReleased = false
  private val assetPathToSoundId = mutableMapOf<String, Int>()
  private val loadedSoundIds = mutableSetOf<Int>()
  private var pendingSoundId: Int? = null

  /**
   * Preloads all standardized earcon assets into a [SoundPool] bound to [glassesContext] (or
   * [hostContext] when no projected device context is active).
   */
  fun preload(glassesContext: Context? = null) {
    synchronized(lock) {
      isReleased = false
      val unused = ensureSoundPoolLocked(glassesContext ?: hostContext)
    }
  }

  /**
   * Plays [type] on the glasses audio output, or queues it if async decoding is still in flight.
   * No-op after [release] until [preload] is called for the next session.
   */
  fun play(type: XrEarconType, glassesContext: Context? = null) {
    synchronized(lock) {
      if (isReleased) return
      val pool = ensureSoundPoolLocked(glassesContext ?: hostContext) ?: return
      val soundId = assetPathToSoundId[type.assetPath] ?: return
      if (soundId == 0) return
      if (soundId in loadedSoundIds) {
        playSample(pool, soundId)
      } else {
        pendingSoundId = soundId
      }
    }
  }

  /**
   * Releases the underlying [SoundPool], clears loaded sample state, and ignores subsequent [play]
   * calls until [preload] is called again.
   */
  fun release() {
    synchronized(lock) {
      isReleased = true
      releaseLocked()
    }
  }

  /**
   * Returns a [SoundPool] bound to [targetContext]'s virtual device ID, creating and loading a new
   * pool if none exists or if the target `deviceId` changed. Must be called under [lock].
   */
  private fun ensureSoundPoolLocked(targetContext: Context): SoundPool? {
    val targetDeviceId =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { targetContext.deviceId }.getOrDefault(0)
      } else {
        0
      }
    val existing = soundPool
    if (existing != null && boundDeviceId == targetDeviceId) {
      return existing
    }
    releaseLocked()

    return runCatching {
        val attributes =
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val builder =
          SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attributes).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
              val unused = setContext(targetContext)
            }
          }
        builder.build().also { pool ->
          soundPool = pool
          boundDeviceId = targetDeviceId
          pool.setOnLoadCompleteListener { loadedPool, sampleId, status ->
            synchronized(lock) {
              if (loadedPool !== soundPool) return@setOnLoadCompleteListener
              if (status != 0) {
                Log.w(TAG, "SoundPool failed to load sampleId=$sampleId (status=$status)")
                return@setOnLoadCompleteListener
              }
              loadedSoundIds.add(sampleId)
              if (pendingSoundId == sampleId) {
                pendingSoundId = null
                playSample(loadedPool, sampleId)
              }
            }
          }
          for (assetPath in XrEarconType.entries.map { it.assetPath }.distinct()) {
            runCatching {
                hostContext.assets.openFd(assetPath).use { afd ->
                  val id = pool.load(afd, DEFAULT_PRIORITY)
                  if (id != 0) {
                    assetPathToSoundId[assetPath] = id
                  }
                }
              }
              .onFailure { Log.w(TAG, "Unable to load earcon asset $assetPath", it) }
          }
        }
      }
      .onFailure { Log.w(TAG, "Failed to initialize SoundPool for earcons", it) }
      .getOrNull()
  }

  /** Plays a decoded [soundId] on [pool] at full volume without looping. */
  private fun playSample(pool: SoundPool, soundId: Int) {
    runCatching {
        val unused =
          pool.play(
            soundId,
            DEFAULT_VOLUME,
            DEFAULT_VOLUME,
            DEFAULT_PRIORITY,
            /* loop= */ 0,
            DEFAULT_RATE,
          )
      }
      .onFailure { Log.w(TAG, "Failed to play earcon sampleId=$soundId", it) }
  }

  /** Releases [soundPool] and resets all cached sample IDs. Must be called under [lock]. */
  private fun releaseLocked() {
    loadedSoundIds.clear()
    assetPathToSoundId.clear()
    pendingSoundId = null
    boundDeviceId = Int.MIN_VALUE
    runCatching { soundPool?.release() }
    soundPool = null
  }

  private companion object {
    const val TAG = "AGGlassesEarcons"
    const val MAX_STREAMS = 2
    const val DEFAULT_PRIORITY = 1
    const val DEFAULT_VOLUME = 1.0f
    const val DEFAULT_RATE = 1.0f
  }
}
