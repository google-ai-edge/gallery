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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaKokoroPackageInstallerTest {
  @Test
  fun installsPinnedPackageAtomically() {
    runBlocking {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val archive =
        context.filesDir
          .resolve("translation_tts_installer_test")
          .resolve("kokoro-multi-lang-v1_0.tar.bz2")
      assertTrue("Stage the pinned Kokoro archive at ${archive.absolutePath}", archive.isFile)

      val installed =
        SherpaKokoroPackageInstaller.installFromVerifiedArchive(
          context = context,
          archiveFile = archive,
        )

      assertEquals(
        SherpaKokoroPackageInstaller.packageDirectory(context).canonicalPath,
        installed.rootDirectory.canonicalPath,
      )
      assertTrue(installed.modelFile.isFile)
      assertTrue(installed.voicesFile.isFile)
      assertTrue(installed.tokensFile.isFile)
      assertTrue(installed.espeakDataDirectory.resolve("phondata").isFile)
      assertTrue(installed.dictionaryDirectory.resolve("jieba.dict.utf8").isFile)
      assertEquals(
        KOKORO_SHERPA_VOICE_CONFIGS.associateBy(KokoroSherpaVoiceConfig::languageTag),
        installed.voiceConfigs,
      )

      val manifest = JSONObject(installed.installManifest.readText())
      assertEquals(KOKORO_SHERPA_SCHEMA_VERSION, manifest.getInt("schema_version"))
      assertEquals(KOKORO_SHERPA_BACKEND, manifest.getString("backend"))
      assertEquals(KOKORO_SHERPA_RUNTIME_VERSION, manifest.getString("runtime_version"))
      assertEquals(KOKORO_SHERPA_SAMPLE_RATE, manifest.getInt("sample_rate_hz"))
      val manifestAssets = manifest.getJSONObject("assets")
      assertTrue(KOKORO_SHERPA_REQUIRED_ASSETS.all(manifestAssets::has))
      val manifestLanguages = manifest.getJSONObject("languages")
      KOKORO_SHERPA_VOICE_CONFIGS.forEach { voice ->
        assertEquals(
          voice.espeakVoice,
          manifestLanguages.getJSONObject(voice.languageTag).getString("espeak_voice"),
        )
      }

      val packageParent = checkNotNull(installed.rootDirectory.parentFile)
      assertFalse(
        packageParent.listFiles()?.any { file -> file.name.endsWith(".staging") } == true
      )
      assertEquals(
        installed.rootDirectory.canonicalPath,
        SherpaKokoroPackageInstaller.findInstalled(context)?.rootDirectory?.canonicalPath,
      )
      assertEquals(
        installed.rootDirectory.canonicalPath,
        SherpaKokoroPackageInstaller.ensureInstalled(context).rootDirectory.canonicalPath,
      )
    }
  }
}
