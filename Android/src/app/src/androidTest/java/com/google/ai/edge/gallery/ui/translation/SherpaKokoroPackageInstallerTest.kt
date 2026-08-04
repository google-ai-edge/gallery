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
import java.io.File
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
  fun installsPinnedPackageAtomicallyAndRemovesLegacyCaches() {
    runBlocking {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val archive =
        context.filesDir
          .resolve("translation_tts_installer_test")
          .resolve("kokoro-int8-multi-lang-v1_1.tar.bz2")
      assertTrue("Stage the pinned Kokoro archive at ${archive.absolutePath}", archive.isFile)

      val legacyRoots =
        buildList {
          add(context.filesDir.resolve("kokoro_tts"))
          context.getExternalFilesDir(null)?.let { externalFilesDir ->
            add(externalFilesDir.resolve("kokoro_tts"))
          }
        }
      legacyRoots.forEach { legacyRoot ->
        assertTrue(legacyRoot.isDirectory || legacyRoot.mkdirs())
        legacyRoot.resolve("legacy-package-sentinel.txt").writeText("obsolete")
      }

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
      assertEquals(setOf("en-us", "es", "fr-fr", "it"), installed.voiceConfigs.keys)
      assertTrue(legacyRoots.none(File::exists))

      val manifest = JSONObject(installed.installManifest.readText())
      assertEquals(2, manifest.getInt("schema_version"))
      assertEquals("sherpa-onnx", manifest.getString("backend"))
      assertEquals("1.13.4", manifest.getString("runtime_version"))
      assertEquals(24000, manifest.getInt("sample_rate_hz"))
      assertTrue(manifest.getJSONObject("assets").length() > 300)
      assertEquals(
        "fr",
        manifest
          .getJSONObject("languages")
          .getJSONObject("fr-fr")
          .getString("espeak_voice"),
      )

      val packageParent = installed.rootDirectory.parentFile ?: File("")
      assertFalse(
        packageParent.listFiles()?.any { file -> file.name.endsWith(".staging") } == true
      )
      assertEquals(
        installed.rootDirectory.canonicalPath,
        TranslationTtsModelRepository.findInstalled(context)?.rootDirectory?.canonicalPath,
      )
      assertEquals(
        installed.rootDirectory.canonicalPath,
        TranslationTtsModelRepository.ensureInstalled(context).rootDirectory.canonicalPath,
      )
    }
  }
}
