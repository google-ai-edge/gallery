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
import org.junit.Assert.assertNull
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
  fun packageValidationRejectsWrongRevision() {
    val metadata = validMetadata().copy(revision = "different-revision")

    assertThrows(IOException::class.java) {
      SherpaKokoroRegressionPolicy.validatePackageMetadata(
        metadata = metadata,
        actualAssetPaths = metadata.assetHashes.keys,
      )
    }
  }

  @Test
  fun packageValidationRejectsMissingOrUntrackedAssets() {
    val metadata = validMetadata()

    assertThrows(IOException::class.java) {
      SherpaKokoroRegressionPolicy.validatePackageMetadata(
        metadata = metadata,
        actualAssetPaths = metadata.assetHashes.keys - "tokens.txt",
      )
    }
  }

  @Test
  fun packageValidationRejectsChangedVoiceMapping() {
    val metadata = validMetadata()
    val changedFrench = metadata.languages.getValue("fr-fr").copy(espeakVoice = "fr-fr")

    assertThrows(IOException::class.java) {
      SherpaKokoroRegressionPolicy.validatePackageMetadata(
        metadata = metadata.copy(languages = metadata.languages + ("fr-fr" to changedFrench)),
        actualAssetPaths = metadata.assetHashes.keys,
      )
    }
  }

  @Test
  fun installationUsesOnlyV2PackageState() {
    assertEquals(
      KokoroSherpaInstallAction.INSTALL_V2,
      SherpaKokoroRegressionPolicy.installAction(
        validV2Installed = false,
        v2DirectoryPresent = false,
      ),
    )
    assertEquals(
      KokoroSherpaInstallAction.REPLACE_INVALID_V2,
      SherpaKokoroRegressionPolicy.installAction(
        validV2Installed = false,
        v2DirectoryPresent = true,
      ),
    )
    assertEquals(
      KokoroSherpaInstallAction.USE_VALID_V2,
      SherpaKokoroRegressionPolicy.installAction(
        validV2Installed = true,
        v2DirectoryPresent = true,
      ),
    )
  }

  @Test
  fun languageAliasesSelectPinnedVoices() {
    assertEquals(
      KokoroSherpaVoiceConfig("en-us", "en-us", "af_maple", 0),
      SherpaKokoroVoiceSelector.select("en_GB"),
    )
    assertEquals(
      KokoroSherpaVoiceConfig("es", "es", "af_sol", 1),
      SherpaKokoroVoiceSelector.select("es-ES"),
    )
    assertEquals(
      KokoroSherpaVoiceConfig("fr-fr", "fr", "zf_047", 30),
      SherpaKokoroVoiceSelector.select("fr-FR"),
    )
    assertEquals(
      KokoroSherpaVoiceConfig("it", "it", "bf_vale", 2),
      SherpaKokoroVoiceSelector.select("it-IT"),
    )
    assertNull(SherpaKokoroVoiceSelector.select("de-DE"))
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
