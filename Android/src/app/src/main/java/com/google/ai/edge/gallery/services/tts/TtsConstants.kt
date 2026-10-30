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

package com.google.ai.edge.gallery.services.tts

import java.util.Locale

/**
 * Canonical model identifiers for TTS engines.
 *
 * Several identifiers resolve to the Android platform engine because the id is persisted in user
 * preferences: spellings written by earlier app versions have to keep working, so they are listed
 * here rather than hardcoded at the call sites that accept them.
 */
object TtsModelIdentifier {
  /** Android platform native Text-to-Speech. */
  const val ANDROID_SYSTEM = "android_system_tts"

  /** Identifier the Android platform engine is stored under in user preferences. */
  const val ANDROID_NATIVE = "androidNative"

  /** Snake-case spelling of the Android platform engine, accepted for compatibility. */
  const val ANDROID_NATIVE_TTS = "android_native_tts"

  /** ML Kit GenAI on-device neural Text-to-Speech. */
  const val ML_KIT = "mlkit_tts"

  /** Downloadable Kokoro 82M model run on-device by MediaPipe `TextToSpeech`. */
  const val MEDIAPIPE_KOKORO_82M = "mediapipe_kokoro_82m"

  /** Downloadable PocketTTS 100M model run on-device by MediaPipe `TextToSpeech`. */
  const val MEDIAPIPE_POCKET_TTS_100M = "mediapipe_pocket_tts_100m"

  /** Downloadable Phonon 31M model run on-device by MediaPipe `TextToSpeech`. */
  const val MEDIAPIPE_PHONON_31M = "mediapipe_phonon_31m"

  /** Backward-compatible legacy alias. */
  const val LEGACY_ANDROID_NATIVE: String = ANDROID_NATIVE

  /** Returns whether [id] is one of the identifiers of the Android platform engine. */
  fun isSystemEngine(id: String?): Boolean =
    id == ANDROID_SYSTEM || id == ANDROID_NATIVE || id == ANDROID_NATIVE_TTS
}

/** BCP-47 tag of the language engines use when the user has not selected one. */
const val DEFAULT_TTS_LANGUAGE_CODE = "en-US"

/**
 * Mapping between ML Kit Star voice codes (as exposed by the SDK's SpeechSynthesizer) and their
 * friendly Persona names (Zephyr, Puck, Charon, Kore, Fenrir, Leda, Orus, Aoede).
 */
val STAR_VOICE_TO_PERSONA: Map<String, String> =
  mapOf(
    "star02" to "Zephyr",
    "star04" to "Puck",
    "star05" to "Charon",
    "star08" to "Kore",
    "star11" to "Fenrir",
    "star12" to "Leda",
    "star13" to "Orus",
    "star16" to "Aoede",
  )

private val STAR_VOICE_CODES: List<String> = STAR_VOICE_TO_PERSONA.keys.toList()

/**
 * Returns whether [voiceName] contains any ML Kit Star voice code (`star02`..`star16`) without
 * allocating a map-key iterator per call.
 */
internal fun containsStarVoiceCode(voiceName: String): Boolean {
  if (!voiceName.contains("star", ignoreCase = true)) return false
  for (code in STAR_VOICE_CODES) {
    if (voiceName.contains(code, ignoreCase = true)) return true
  }
  return false
}

/**
 * Returns whether this language code is an Android testing pseudo-locale (`-XA`, `-XB`, or `-XC`).
 */
fun String.isPseudoLocale(): Boolean {
  val normalized = trim().replace('_', '-').uppercase(Locale.ROOT)
  return normalized.endsWith("-XA") || normalized.endsWith("-XB") || normalized.endsWith("-XC")
}
