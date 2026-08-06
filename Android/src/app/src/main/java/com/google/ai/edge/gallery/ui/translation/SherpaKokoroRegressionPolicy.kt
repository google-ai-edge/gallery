/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import java.io.IOException
import java.util.Locale

internal const val KOKORO_SHERPA_SCHEMA_VERSION = 2
internal const val KOKORO_SHERPA_BACKEND = "sherpa-onnx"
internal const val KOKORO_SHERPA_RUNTIME_VERSION = "1.13.4"
internal const val KOKORO_SHERPA_SAMPLE_RATE = 24000
internal const val KOKORO_SHERPA_PACKAGE_ID = "kokoro-multi-lang-v1_0"
internal const val KOKORO_SHERPA_ARCHIVE_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
    "$KOKORO_SHERPA_PACKAGE_ID.tar.bz2"
internal const val KOKORO_SHERPA_ARCHIVE_SHA256 =
  "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046"

internal val KOKORO_SHERPA_REQUIRED_ASSETS =
  setOf(
    "model.onnx",
    "voices.bin",
    "tokens.txt",
    "espeak-ng-data/phondata",
    "espeak-ng-data/phonindex",
    "espeak-ng-data/intonations",
    "espeak-ng-data/en_dict",
    "espeak-ng-data/es_dict",
    "espeak-ng-data/fr_dict",
    "espeak-ng-data/hi_dict",
    "espeak-ng-data/it_dict",
    "espeak-ng-data/pt_dict",
    "dict/jieba.dict.utf8",
    "dict/hmm_model.utf8",
    "dict/user.dict.utf8",
    "dict/idf.utf8",
    "dict/stop_words.utf8",
    "lexicon-us-en.txt",
    "lexicon-gb-en.txt",
    "lexicon-zh.txt",
    "phone-zh.fst",
    "date-zh.fst",
    "number-zh.fst",
  )

internal val KOKORO_SHERPA_TRUSTED_CORE_HASHES =
  mapOf(
    "model.onnx" to
      "c436dc6a842b62aba06af67e40bafcfb9c60ac3af895358f1974ad9a7f7c026b",
    "voices.bin" to
      "8a77c0d397026208d22211f37670b5b3b11e03f190756b25a1d24041fced82a9",
    "tokens.txt" to
      "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
  )

internal data class KokoroSherpaVoiceConfig(
  val languageTag: String,
  val espeakVoice: String,
  val speakerName: String,
  val speakerId: Int,
)

internal val KOKORO_SHERPA_VOICE_CONFIGS =
  listOf(
    KokoroSherpaVoiceConfig("en-us", "en", "af_alloy", 0),
    KokoroSherpaVoiceConfig("es", "es", "ef_dora", 28),
    KokoroSherpaVoiceConfig("fr-fr", "fr", "ff_siwis", 30),
    KokoroSherpaVoiceConfig("hi", "hi", "hf_beta", 32),
    KokoroSherpaVoiceConfig("it", "it", "if_sara", 35),
    KokoroSherpaVoiceConfig("pt-br", "pt-br", "pf_dora", 42),
  )

internal data class KokoroSherpaManifestMetadata(
  val schemaVersion: Int,
  val backend: String,
  val runtimeVersion: String,
  val sampleRate: Int,
  val packageId: String,
  val revision: String,
  val sourceUrl: String,
  val archiveSha256: String,
  val languages: Map<String, KokoroSherpaVoiceConfig>,
  val assetHashes: Map<String, String>,
)

internal object SherpaKokoroRegressionPolicy {
  fun validatePackageMetadata(
    metadata: KokoroSherpaManifestMetadata,
    actualAssetPaths: Set<String>,
  ) {
    if (metadata.schemaVersion != KOKORO_SHERPA_SCHEMA_VERSION) {
      throw IOException("Unsupported Sherpa Kokoro package schema.")
    }
    if (
      metadata.backend != KOKORO_SHERPA_BACKEND ||
        metadata.runtimeVersion != KOKORO_SHERPA_RUNTIME_VERSION ||
        metadata.sampleRate != KOKORO_SHERPA_SAMPLE_RATE
    ) {
      throw IOException("Sherpa Kokoro package runtime contract does not match.")
    }
    if (
      metadata.packageId != KOKORO_SHERPA_PACKAGE_ID ||
        metadata.revision != KOKORO_SHERPA_PACKAGE_ID ||
        metadata.sourceUrl != KOKORO_SHERPA_ARCHIVE_URL ||
        metadata.archiveSha256 != KOKORO_SHERPA_ARCHIVE_SHA256
    ) {
      throw IOException("Sherpa Kokoro package identity does not match.")
    }

    val expectedLanguages = KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag }
    if (metadata.languages.any { (tag, voice) -> expectedLanguages[tag] != voice }) {
      throw IOException("Sherpa Kokoro language and voice mapping does not match.")
    }
    if (metadata.assetHashes.keys != actualAssetPaths) {
      throw IOException("Sherpa Kokoro package asset list does not match install.json.")
    }
    if (!metadata.assetHashes.keys.containsAll(KOKORO_SHERPA_REQUIRED_ASSETS)) {
      throw IOException("Sherpa Kokoro package is missing required assets.")
    }
    KOKORO_SHERPA_TRUSTED_CORE_HASHES.forEach { (relativePath, trustedHash) ->
      if (metadata.assetHashes[relativePath] != trustedHash) {
        throw IOException("Sherpa Kokoro trusted asset metadata is invalid: $relativePath")
      }
    }
    metadata.assetHashes.forEach { (relativePath, hash) ->
      if (relativePath.isBlank() || !hash.matches(Regex("[0-9a-f]{64}"))) {
        throw IOException("Sherpa Kokoro asset metadata is invalid: $relativePath")
      }
    }
  }
}

internal object SherpaKokoroVoiceSelector {
  fun normalize(languageTag: String): String =
    when (languageTag.trim().lowercase(Locale.US).replace('_', '-')) {
      "en", "en-gb", "en-us" -> "en-us"
      "es", "es-es" -> "es"
      "fr", "fr-fr" -> "fr-fr"
      "hi", "hi-in" -> "hi"
      "it", "it-it" -> "it"
      "pt", "pt-br" -> "pt-br"
      else -> languageTag.trim().lowercase(Locale.US).replace('_', '-')
    }

  fun select(
    languageTag: String,
    voiceConfigs: Map<String, KokoroSherpaVoiceConfig> =
      KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag },
  ): KokoroSherpaVoiceConfig? = voiceConfigs[normalize(languageTag)]
}
