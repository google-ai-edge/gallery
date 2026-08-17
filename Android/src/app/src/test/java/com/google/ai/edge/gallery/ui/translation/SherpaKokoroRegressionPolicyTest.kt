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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SherpaKokoroRegressionPolicyTest {
  @Test
  fun packageValidationAcceptsPinnedContract() {
    val metadata = validMetadata()

    SherpaKokoroRegressionPolicy.validatePackageMetadata(
      metadata = metadata,
      actualAssetPaths = metadata.assetHashes.keys,
    )
  }

  @Test
  fun packageValidationRejectsInvalidContracts() {
    val metadata = validMetadata()
    val voice = metadata.languages.values.first()
    val invalidContracts =
      listOf(
        metadata.copy(revision = "different-revision"),
        metadata.copy(
          languages = metadata.languages + (voice.languageTag to voice.copy(speakerId = -1))
        ),
      )

    invalidContracts.forEach { invalidContract ->
      assertThrows(IOException::class.java) {
        SherpaKokoroRegressionPolicy.validatePackageMetadata(
          metadata = invalidContract,
          actualAssetPaths = invalidContract.assetHashes.keys,
        )
      }
    }

    listOf(
        metadata.assetHashes.keys - metadata.assetHashes.keys.first(),
        metadata.assetHashes.keys + "unexpected-asset",
      )
      .forEach { invalidAssetPaths ->
        assertThrows(IOException::class.java) {
          SherpaKokoroRegressionPolicy.validatePackageMetadata(
            metadata = metadata,
            actualAssetPaths = invalidAssetPaths,
          )
        }
      }
  }

  @Test
  fun selectsEveryConfiguredTranslationVoice() {
    TranslationLanguage.entries.forEach { language ->
      assertEquals(
        language.ttsLanguageTag,
        SherpaKokoroVoiceSelector.select(language.ttsLanguageTag)?.languageTag,
      )
    }
  }

  private fun validMetadata(): KokoroSherpaManifestMetadata {
    val assetHashes =
      KOKORO_SHERPA_REQUIRED_ASSETS.associateWith { relativePath ->
        KOKORO_SHERPA_TRUSTED_CORE_HASHES[relativePath] ?: "0".repeat(64)
      }
    return KokoroSherpaManifestMetadata(
      schemaVersion = KOKORO_SHERPA_SCHEMA_VERSION,
      backend = KOKORO_SHERPA_BACKEND,
      runtimeVersion = KOKORO_SHERPA_RUNTIME_VERSION,
      sampleRate = KOKORO_SHERPA_SAMPLE_RATE,
      packageId = KOKORO_SHERPA_PACKAGE_ID,
      revision = KOKORO_SHERPA_PACKAGE_ID,
      sourceUrl = KOKORO_SHERPA_ARCHIVE_URL,
      archiveSha256 = KOKORO_SHERPA_ARCHIVE_SHA256,
      languages = KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag },
      assetHashes = assetHashes,
    )
  }
}
