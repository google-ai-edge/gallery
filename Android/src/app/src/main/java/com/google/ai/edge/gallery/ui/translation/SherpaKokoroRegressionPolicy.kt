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
internal const val KOKORO_SHERPA_PACKAGE_ID = "kokoro-int8-multi-lang-v1_1"
internal const val KOKORO_SHERPA_ARCHIVE_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
    "$KOKORO_SHERPA_PACKAGE_ID.tar.bz2"
internal const val KOKORO_SHERPA_ARCHIVE_SHA256 =
  "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6"

internal val KOKORO_SHERPA_REQUIRED_ASSETS =
  setOf(
    "model.int8.onnx",
    "voices.bin",
    "tokens.txt",
    "espeak-ng-data/phondata",
    "espeak-ng-data/phonindex",
    "espeak-ng-data/intonations",
    "espeak-ng-data/en_dict",
    "espeak-ng-data/es_dict",
    "espeak-ng-data/fr_dict",
    "espeak-ng-data/it_dict",
    "lexicon-us-en.txt",
    "lexicon-gb-en.txt",
    "lexicon-zh.txt",
    "phone-zh.fst",
    "date-zh.fst",
    "number-zh.fst",
  )

internal val KOKORO_SHERPA_TRUSTED_CORE_HASHES =
  mapOf(
    "model.int8.onnx" to
      "bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26",
    "voices.bin" to
      "e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c",
    "tokens.txt" to
      "931ab2df2400cd65d580a22402024c2347ced8ae9ea300e545144b1aacc48e14",
  )

internal data class KokoroSherpaVoiceConfig(
  val languageTag: String,
  val espeakVoice: String,
  val speakerName: String,
  val speakerId: Int,
)

internal val KOKORO_SHERPA_VOICE_CONFIGS =
  listOf(
    KokoroSherpaVoiceConfig("en-us", "en-us", "af_maple", 0),
    KokoroSherpaVoiceConfig("es", "es", "af_sol", 1),
    KokoroSherpaVoiceConfig("fr-fr", "fr", "zf_047", 30),
    KokoroSherpaVoiceConfig("it", "it", "bf_vale", 2),
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

internal enum class KokoroSherpaInstallAction {
  USE_VALID_V2,
  INSTALL_V2,
  REPLACE_INVALID_V2,
}

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
    if (metadata.languages != expectedLanguages) {
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

  fun installAction(
    validV2Installed: Boolean,
    v2DirectoryPresent: Boolean,
  ): KokoroSherpaInstallAction =
    when {
      validV2Installed -> KokoroSherpaInstallAction.USE_VALID_V2
      v2DirectoryPresent -> KokoroSherpaInstallAction.REPLACE_INVALID_V2
      else -> KokoroSherpaInstallAction.INSTALL_V2
    }
}

internal object SherpaKokoroVoiceSelector {
  fun normalize(languageTag: String): String =
    when (languageTag.trim().lowercase(Locale.US).replace('_', '-')) {
      "en", "en-gb", "en-us" -> "en-us"
      "es", "es-es" -> "es"
      "fr", "fr-fr" -> "fr-fr"
      "it", "it-it" -> "it"
      else -> languageTag.trim().lowercase(Locale.US).replace('_', '-')
    }

  fun select(
    languageTag: String,
    voiceConfigs: Map<String, KokoroSherpaVoiceConfig> =
      KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag },
  ): KokoroSherpaVoiceConfig? = voiceConfigs[normalize(languageTag)]
}
