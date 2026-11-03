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

package com.google.ai.edge.gallery.worker

import android.annotation.SuppressLint
import android.util.Log
import com.google.ai.edge.gallery.data.UNPACKED_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "AGDownloadZipExtractor"

/** Returns the total size of the files in [zipFile] once unzipped. */
internal fun computeZipUncompressedBytes(zipFile: File): Long =
  ZipFile(zipFile).use { zip ->
    // An entry whose size isn't known reports -1.
    zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0L) }
  }

/**
 * Extracts [originalFile] into [outputDir], deletes [originalFile], and records a `.unpacked`
 * marker file when [fileName] is an extra data zip file so a resumed download knows it was already
 * unpacked.
 */
@SuppressLint("ZipName")
internal suspend fun extractDownloadedZip(
  originalFile: File,
  outputDir: File,
  fileName: String,
  mainFileName: String?,
  unzippedDir: String?,
) {
  val destDir =
    when {
      fileName == mainFileName && !unzippedDir.isNullOrEmpty() -> File(outputDir, unzippedDir)
      fileName != mainFileName -> {
        val folderName = fileName.substringBeforeLast(".")
        var hasPrefix = false
        try {
          ZipInputStream(BufferedInputStream(FileInputStream(originalFile))).use { checkZipIn ->
            var checkEntry = checkZipIn.nextEntry
            while (checkEntry != null) {
              if (checkEntry.name.startsWith("$folderName/")) {
                hasPrefix = true
                break
              }
              checkEntry = checkZipIn.nextEntry
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to inspect zip entries for prefix: ${e.message}")
        }
        if (hasPrefix) outputDir else File(outputDir, folderName)
      }
      else -> outputDir
    }
  if (!destDir.exists()) {
    destDir.mkdirs()
  }

  val unzipBuffer = ByteArray(8192)
  ZipInputStream(BufferedInputStream(FileInputStream(originalFile))).use { zipIn ->
    var zipEntry: ZipEntry? = zipIn.nextEntry
    while (zipEntry != null) {
      currentCoroutineContext().ensureActive()
      val outFile = File(destDir, zipEntry.name)
      val outPath = outFile.canonicalPath
      val destPath = destDir.canonicalPath
      when {
        // Guard against Zip Slip: an entry must be inside the target dir.
        outPath.startsWith(destPath + File.separator) ->
          if (zipEntry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
              var len: Int
              while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                currentCoroutineContext().ensureActive()
                fos.write(unzipBuffer, 0, len)
              }
            }
          }
        // Only a dir entry can be the target dir itself (e.g. "./"), which already exists.
        outPath == destPath ->
          if (!zipEntry.isDirectory) {
            Log.w(TAG, "Skipping zip entry '${zipEntry.name}', which isn't a file name")
          }
        else -> throw SecurityException("Zip entry is outside of the target dir: ${zipEntry.name}")
      }

      zipIn.closeEntry()
      zipEntry = zipIn.nextEntry
    }
  }

  originalFile.delete()
  if (fileName != mainFileName) {
    try {
      File(outputDir, "$fileName.$UNPACKED_FILE_EXT").createNewFile()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to create unpacked marker for '$fileName'", e)
    }
  }
}
