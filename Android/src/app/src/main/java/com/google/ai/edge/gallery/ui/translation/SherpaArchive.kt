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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L

internal data class SherpaArchive(
  val name: String,
  val url: String,
  val sha256: String,
  val maxEntries: Int,
) {
  fun download(
    destination: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ) {
    val connection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "Google-AI-Edge-Gallery")
      }
    try {
      if (connection.responseCode !in 200..299) {
        throw IOException("Failed to download $name: HTTP ${connection.responseCode}")
      }

      val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
      var downloadedBytes = 0L
      var lastProgressMillis = 0L
      onProgress(TranslationTtsDownloadProgress(name, 0L, totalBytes, 0, 1))
      BufferedInputStream(connection.inputStream).use { input ->
        BufferedOutputStream(destination.outputStream()).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            downloadedBytes += count
            val now = System.currentTimeMillis()
            if (now - lastProgressMillis >= 250L) {
              lastProgressMillis = now
              onProgress(TranslationTtsDownloadProgress(name, downloadedBytes, totalBytes, 0, 1))
            }
          }
        }
      }

      if (downloadedBytes == 0L || (totalBytes > 0L && downloadedBytes != totalBytes)) {
        throw IOException("Incomplete download of $name.")
      }
      onProgress(
        TranslationTtsDownloadProgress(
          name,
          downloadedBytes,
          totalBytes.takeIf { it > 0L } ?: downloadedBytes,
          1,
          1,
          TranslationTtsInstallStage.VERIFYING,
        )
      )
      verify(destination)
    } finally {
      connection.disconnect()
    }
  }

  fun verify(file: File) {
    requireFile(file)
    if (file.sha256() != sha256) throw IOException("Checksum mismatch for $name.")
  }

  fun extract(file: File, destination: File) {
    val canonicalDestination = destination.canonicalFile
    var entryCount = 0
    var extractedBytes = 0L
    TarArchiveInputStream(
        BZip2CompressorInputStream(BufferedInputStream(file.inputStream()))
      )
      .use { input ->
        while (true) {
          val entry = input.nextEntry ?: break
          if (++entryCount > maxEntries) throw IOException("Too many entries in $name.")
          if (entry.isLink || entry.isSymbolicLink) {
            throw IOException("Links are not allowed in $name: ${entry.name}")
          }

          val output = destination.resolve(entry.name).canonicalFile
          if (
            output != canonicalDestination &&
              !output.path.startsWith(canonicalDestination.path + File.separator)
          ) {
            throw IOException("Unsafe entry in $name: ${entry.name}")
          }
          if (entry.isDirectory) {
            if (!output.isDirectory && !output.mkdirs()) {
              throw IOException("Unable to create extracted directory: $output")
            }
            continue
          }
          if (!entry.isFile) throw IOException("Unsupported entry in $name: ${entry.name}")

          output.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
              throw IOException("Unable to create extracted directory: $parent")
            }
          }
          BufferedOutputStream(output.outputStream()).use { fileOutput ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryBytes = 0L
            while (true) {
              val count = input.read(buffer)
              if (count == -1) break
              fileOutput.write(buffer, 0, count)
              entryBytes += count
              extractedBytes += count
              if (extractedBytes > MAX_EXTRACTED_BYTES) {
                throw IOException("$name exceeds the extraction size limit.")
              }
            }
            if (entry.size >= 0L && entryBytes != entry.size) {
              throw IOException("Incomplete entry in $name: ${entry.name}")
            }
          }
        }
      }
  }

  fun install(
    file: File,
    finalRoot: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit = {},
    prepare: (File) -> Unit,
  ) {
    fun report(stage: TranslationTtsInstallStage) {
      val size = file.length()
      onProgress(TranslationTtsDownloadProgress(name, size, size, 1, 1, stage))
    }

    report(TranslationTtsInstallStage.VERIFYING)
    verify(file)
    val parent = finalRoot.parentFile ?: throw IOException("$finalRoot has no parent directory.")
    if (!parent.isDirectory && !parent.mkdirs()) {
      throw IOException("Unable to create package directory: $parent")
    }
    val stagingRoot = parent.resolve(".${finalRoot.name}.staging")
    if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
      throw IOException("Unable to clear staging directory: $stagingRoot")
    }
    if (!stagingRoot.mkdirs()) throw IOException("Unable to create staging directory: $stagingRoot")

    try {
      report(TranslationTtsInstallStage.EXTRACTING)
      extract(file, stagingRoot)
      val candidate = stagingRoot.resolve(finalRoot.name)
      report(TranslationTtsInstallStage.VALIDATING)
      prepare(candidate)
      report(TranslationTtsInstallStage.FINALIZING)
      if (finalRoot.exists() && !finalRoot.deleteRecursively()) {
        throw IOException("Unable to replace package: $finalRoot")
      }
      if (!candidate.renameTo(finalRoot)) throw IOException("Unable to install package: $finalRoot")
    } finally {
      stagingRoot.deleteRecursively()
    }
  }
}

internal fun requireFile(file: File): File {
  if (!file.isFile || file.length() <= 0L) {
    throw IOException("Missing or empty file: ${file.absolutePath}")
  }
  return file
}

internal fun File.sha256(): String {
  val digest = MessageDigest.getInstance("SHA-256")
  inputStream().buffered().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val count = input.read(buffer)
      if (count == -1) break
      digest.update(buffer, 0, count)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
