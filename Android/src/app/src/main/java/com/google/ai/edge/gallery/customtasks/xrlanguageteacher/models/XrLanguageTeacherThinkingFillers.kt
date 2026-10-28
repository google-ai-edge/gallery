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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import com.google.ai.edge.gallery.services.tts.TtsAudioStreamFormat
import kotlin.random.Random

/** Bytes per millisecond of the PCM that TTS engines stream. */
private val PCM_BYTES_PER_MS: Int =
  with(TtsAudioStreamFormat.STANDARD_24KHZ_MONO) {
    (sampleRate * channelCount * (bitDepth / 8) / 1000).toInt()
  }

/** How much audio each slice of a pre-synthesized filler written to the speaker holds. */
private const val FILLER_SLICE_MS = 100

/** The longest a thinking filler should play, so the reply queued behind it isn't delayed. */
internal const val MAX_THINKING_FILLER_DURATION_MS = 1_200L

/**
 * A thinking filler synthesized ahead of time.
 *
 * @property text The filler phrase.
 * @property pcm The phrase's audio as 24 kHz mono 16-bit PCM.
 */
@Suppress("KotlinArray")
class PresynthesizedFiller(val text: String, val pcm: ByteArray) {
  /** How long [pcm] plays. */
  val durationMs: Long
    get() = pcm.size.toLong() / PCM_BYTES_PER_MS

  /**
   * [pcm] in slices of [sliceMs] each, so that writing one to the speaker doesn't block for the
   * whole phrase.
   */
  fun pcmSlices(sliceMs: Int = FILLER_SLICE_MS): List<ByteArray> {
    val sliceBytes = sliceMs * PCM_BYTES_PER_MS
    return (pcm.indices step sliceBytes).map {
      pcm.copyOfRange(it, minOf(it + sliceBytes, pcm.size))
    }
  }
}

/** What the thinking fillers were synthesized with; a change makes them stale. */
internal data class FillerVoiceKey(
  val ttsModelId: String,
  val languageTag: String?,
  val voiceId: String?,
)

/**
 * Picks the thinking filler to speak from [fillers]: one of those that play for at most
 * [MAX_THINKING_FILLER_DURATION_MS], or the shortest when none does, at random and other than
 * [previousText] when there is a choice. Returns `null` when [fillers] is empty.
 */
internal fun selectPresynthesizedFiller(
  fillers: List<PresynthesizedFiller>,
  previousText: String?,
  random: Random = Random.Default,
): PresynthesizedFiller? {
  val shortEnough =
    fillers
      .filter { it.durationMs <= MAX_THINKING_FILLER_DURATION_MS }
      .ifEmpty { listOfNotNull(fillers.minByOrNull { it.durationMs }) }
  return shortEnough.filter { it.text != previousText }.ifEmpty { shortEnough }.randomOrNull(random)
}

/**
 * Picks a thinking filler phrase from [phrases] at random, other than [previousText] when there is
 * a choice, for when no filler was synthesized ahead of time. Returns `null` when [phrases] is
 * empty.
 */
internal fun selectLiveFiller(
  phrases: List<String>,
  previousText: String?,
  random: Random = Random.Default,
): String? = phrases.filter { it != previousText }.ifEmpty { phrases }.randomOrNull(random)

/**
 * The thinking fillers synthesized for one [FillerVoiceKey]; holds a single voice at a time. It
 * also remembers a voice whose engine can't synthesize ahead of time, so that it isn't tried again.
 */
internal class PresynthesizedFillerCache {
  private data class Entry(val key: FillerVoiceKey, val fillers: List<PresynthesizedFiller>)

  @Volatile private var entry: Entry? = null

  /** Returns the fillers synthesized for [key], or an empty list if there are none. */
  fun get(key: FillerVoiceKey): List<PresynthesizedFiller> =
    entry?.takeIf { it.key == key }?.fillers.orEmpty()

  /** Whether [key]'s engine can't synthesize fillers ahead of time ([markUnsupported]). */
  fun isUnsupported(key: FillerVoiceKey): Boolean =
    entry?.let { it.key == key && it.fillers.isEmpty() } == true

  /** Stores [fillers] for [key], replacing the fillers of any other key. */
  fun put(key: FillerVoiceKey, fillers: List<PresynthesizedFiller>) {
    entry = Entry(key, fillers)
  }

  /** Records that [key]'s engine can't synthesize fillers ahead of time. */
  fun markUnsupported(key: FillerVoiceKey) {
    entry = Entry(key, emptyList())
  }

  /** Drops all fillers, for example when the TTS model, native language or persona changes. */
  fun clear() {
    entry = null
  }
}
