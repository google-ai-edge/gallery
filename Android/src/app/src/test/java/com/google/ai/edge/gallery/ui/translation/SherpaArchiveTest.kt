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

import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaArchiveTest {
  @Test
  fun install_reportsEachStageInOrder() {
    val directory = Files.createTempDirectory("sherpa-archive-test").toFile()
    try {
      val archiveFile = directory.resolve("voice.tar.bz2")
      val contents = "model".toByteArray()
      BZip2CompressorOutputStream(archiveFile.outputStream()).use { compressed ->
        TarArchiveOutputStream(compressed).use { archive ->
          val entry = TarArchiveEntry("voice/model.bin").apply { size = contents.size.toLong() }
          archive.putArchiveEntry(entry)
          archive.write(contents)
          archive.closeArchiveEntry()
        }
      }

      val stages = mutableListOf<TranslationTtsInstallStage>()
      val finalRoot = directory.resolve("voice")
      SherpaArchive(
          name = archiveFile.name,
          url = "",
          sha256 = archiveFile.sha256(),
          maxEntries = 2,
        )
        .install(archiveFile, finalRoot, { stages += it.stage }) { candidate ->
          requireFile(candidate.resolve("model.bin"))
        }

      assertEquals(
        listOf(
          TranslationTtsInstallStage.VERIFYING,
          TranslationTtsInstallStage.EXTRACTING,
          TranslationTtsInstallStage.VALIDATING,
          TranslationTtsInstallStage.FINALIZING,
        ),
        stages,
      )
      assertTrue(finalRoot.resolve("model.bin").isFile)
    } finally {
      directory.deleteRecursively()
    }
  }
}
