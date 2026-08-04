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

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject

internal const val SUPERTONIC_SHERPA_BACKEND = "sherpa-onnx-supertonic"
internal const val SUPERTONIC_SHERPA_PACKAGE_ID =
  "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
internal const val SUPERTONIC_SHERPA_SAMPLE_RATE = 44100
private const val SUPERTONIC_STORAGE_ROOT = "translation_tts/supertonic"
private const val SUPERTONIC_ARCHIVE_NAME = "$SUPERTONIC_SHERPA_PACKAGE_ID.tar.bz2"
private const val SUPERTONIC_ARCHIVE_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
    SUPERTONIC_ARCHIVE_NAME
private const val SUPERTONIC_ARCHIVE_SHA256 =
  "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
private const val INSTALL_MANIFEST = "install.json"
private const val MAX_ARCHIVE_ENTRIES = 100
private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L

private val SUPERTONIC_REQUIRED_HASHES =
  mapOf(
    "duration_predictor.int8.onnx" to
      "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
    "text_encoder.int8.onnx" to
      "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
    "vector_estimator.int8.onnx" to
      "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd",
    "vocoder.int8.onnx" to
      "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152",
    "tts.json" to "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
    "unicode_indexer.bin" to
      "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30",
    "voice.bin" to "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8",
    "LICENSE" to "0dfe0d0ba84416fe3879d9a34f4909d8d0137c78d1e95834177b0414ac096fa2",
    "README.md" to "a96c347945f7c8bc1673bea3525b1ac8d36fdde556e1e0a6a186052429caf863",
  )

internal data class SupertonicSherpaPackage(
  val rootDirectory: File,
  val durationPredictor: File,
  val textEncoder: File,
  val vectorEstimator: File,
  val vocoder: File,
  val ttsJson: File,
  val unicodeIndexer: File,
  val voiceStyle: File,
)

internal object SherpaSupertonicPackageInstaller {
  private val installMutex = Mutex()

  @Volatile private var cachedPackage: SupertonicSherpaPackage? = null

  fun packageDirectory(context: Context): File =
    context.filesDir.resolve(SUPERTONIC_STORAGE_ROOT).resolve(SUPERTONIC_SHERPA_PACKAGE_ID)

  fun findInstalled(context: Context): SupertonicSherpaPackage? {
    val root = packageDirectory(context).absoluteFile
    cachedPackage?.takeIf { it.rootDirectory.absoluteFile == root }?.let { return it }
    if (!root.isDirectory) return null
    return runCatching { validatePackage(root) }.getOrNull()?.also { cachedPackage = it }
  }

  suspend fun ensureInstalled(
    context: Context,
    onProgress: (TranslationTtsDownloadProgress) -> Unit = {},
  ): SupertonicSherpaPackage =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        findInstalled(context)?.let { return@withLock it }
        val finalRoot = packageDirectory(context)
        val parent = finalRoot.parentFile
          ?: throw IOException("Supertonic package has no parent directory.")
        if (!parent.isDirectory && !parent.mkdirs()) {
          throw IOException("Unable to create Supertonic package directory: $parent")
        }
        val downloadRoot = parent.resolve(".$SUPERTONIC_SHERPA_PACKAGE_ID.download")
        if (downloadRoot.exists() && !downloadRoot.deleteRecursively()) {
          throw IOException("Unable to clear stale Supertonic download.")
        }
        if (!downloadRoot.mkdirs()) throw IOException("Unable to create download directory.")
        val archive = downloadRoot.resolve(SUPERTONIC_ARCHIVE_NAME)
        try {
          downloadArchive(archive, onProgress)
          installVerifiedArchive(finalRoot = finalRoot, archive = archive)
        } finally {
          downloadRoot.deleteRecursively()
        }
      }
    }

  suspend fun deleteInstalled(context: Context): Boolean =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        cachedPackage = null
        val root = packageDirectory(context)
        !root.exists() || root.deleteRecursively()
      }
    }

  internal suspend fun installFromVerifiedArchive(
    context: Context,
    archiveFile: File,
  ): SupertonicSherpaPackage =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        installVerifiedArchive(packageDirectory(context), archiveFile)
      }
    }

  private fun installVerifiedArchive(
    finalRoot: File,
    archive: File,
  ): SupertonicSherpaPackage {
    requireFile(archive)
    val actualArchiveHash = sha256(archive)
    if (actualArchiveHash != SUPERTONIC_ARCHIVE_SHA256) {
      throw IOException("Supertonic archive checksum mismatch.")
    }
    val parent = finalRoot.parentFile
      ?: throw IOException("Supertonic package has no parent directory.")
    if (!parent.isDirectory && !parent.mkdirs()) {
      throw IOException("Unable to create Supertonic package directory.")
    }
    val stagingRoot = parent.resolve(".$SUPERTONIC_SHERPA_PACKAGE_ID.staging")
    if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
      throw IOException("Unable to clear stale Supertonic staging directory.")
    }
    if (!stagingRoot.mkdirs()) throw IOException("Unable to create staging directory.")

    try {
      extractArchive(archive, stagingRoot)
      val candidate = stagingRoot.resolve(SUPERTONIC_SHERPA_PACKAGE_ID)
      validateRequiredHashes(candidate)
      writeManifest(candidate)
      if (finalRoot.exists() && !finalRoot.deleteRecursively()) {
        throw IOException("Unable to replace existing Supertonic package.")
      }
      if (!candidate.renameTo(finalRoot)) {
        throw IOException("Unable to activate Supertonic package.")
      }
      return validatePackage(finalRoot).also { cachedPackage = it }
    } finally {
      stagingRoot.deleteRecursively()
    }
  }

  private fun downloadArchive(
    destination: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ) {
    val connection =
      (URL(SUPERTONIC_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "Google-AI-Edge-Gallery")
      }
    try {
      if (connection.responseCode !in 200..299) {
        throw IOException("Failed to download Supertonic: HTTP ${connection.responseCode}")
      }
      val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
      var downloadedBytes = 0L
      var lastProgressMillis = 0L
      onProgress(TranslationTtsDownloadProgress(SUPERTONIC_ARCHIVE_NAME, 0, totalBytes, 0, 1))
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
              onProgress(
                TranslationTtsDownloadProgress(
                  SUPERTONIC_ARCHIVE_NAME,
                  downloadedBytes,
                  totalBytes,
                  0,
                  1,
                )
              )
            }
          }
        }
      }
      if (downloadedBytes == 0L || (totalBytes > 0 && downloadedBytes != totalBytes)) {
        throw IOException("Incomplete Supertonic download.")
      }
      if (sha256(destination) != SUPERTONIC_ARCHIVE_SHA256) {
        throw IOException("Downloaded Supertonic archive failed checksum verification.")
      }
      onProgress(
        TranslationTtsDownloadProgress(
          SUPERTONIC_ARCHIVE_NAME,
          downloadedBytes,
          if (totalBytes > 0) totalBytes else downloadedBytes,
          1,
          1,
        )
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun extractArchive(archive: File, destination: File) {
    val canonicalDestination = destination.canonicalFile
    var entryCount = 0
    var extractedBytes = 0L
    TarArchiveInputStream(
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
      )
      .use { input ->
        while (true) {
          val entry = input.nextEntry ?: break
          entryCount++
          if (entryCount > MAX_ARCHIVE_ENTRIES) throw IOException("Too many archive entries.")
          if (entry.isLink || entry.isSymbolicLink) throw IOException("Archive links are blocked.")
          val output = destination.resolve(entry.name).canonicalFile
          if (
            output != canonicalDestination &&
              !output.path.startsWith(canonicalDestination.path + File.separator)
          ) {
            throw IOException("Unsafe Supertonic archive entry: ${entry.name}")
          }
          if (entry.isDirectory) {
            if (!output.isDirectory && !output.mkdirs()) {
              throw IOException("Unable to create extracted directory.")
            }
            continue
          }
          if (!entry.isFile) throw IOException("Unsupported archive entry.")
          output.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Unable to create extracted directory.") }
          BufferedOutputStream(output.outputStream()).use { fileOutput ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val count = input.read(buffer)
              if (count == -1) break
              extractedBytes += count
              if (extractedBytes > MAX_EXTRACTED_BYTES) {
                throw IOException("Supertonic archive exceeds extraction limit.")
              }
              fileOutput.write(buffer, 0, count)
            }
          }
        }
      }
  }

  private fun validateRequiredHashes(root: File) {
    SUPERTONIC_REQUIRED_HASHES.forEach { (relativePath, expectedHash) ->
      val file = root.resolve(relativePath)
      requireFile(file)
      if (sha256(file) != expectedHash) {
        throw IOException("Supertonic asset checksum mismatch: $relativePath")
      }
    }
  }

  private fun writeManifest(root: File) {
    root.resolve(INSTALL_MANIFEST).writeText(
      JSONObject()
        .put("schema_version", 1)
        .put("package_id", SUPERTONIC_SHERPA_PACKAGE_ID)
        .put("source_url", SUPERTONIC_ARCHIVE_URL)
        .put("archive_sha256", SUPERTONIC_ARCHIVE_SHA256)
        .toString(2)
    )
  }

  private fun validatePackage(root: File): SupertonicSherpaPackage {
    validateRequiredHashes(root)
    val manifestFile = root.resolve(INSTALL_MANIFEST)
    requireFile(manifestFile)
    val manifest = JSONObject(manifestFile.readText())
    if (
      manifest.optInt("schema_version") != 1 ||
        manifest.optString("package_id") != SUPERTONIC_SHERPA_PACKAGE_ID ||
        manifest.optString("archive_sha256") != SUPERTONIC_ARCHIVE_SHA256
    ) {
      throw IOException("Supertonic install manifest does not match the pinned package.")
    }
    return SupertonicSherpaPackage(
      rootDirectory = root,
      durationPredictor = root.resolve("duration_predictor.int8.onnx"),
      textEncoder = root.resolve("text_encoder.int8.onnx"),
      vectorEstimator = root.resolve("vector_estimator.int8.onnx"),
      vocoder = root.resolve("vocoder.int8.onnx"),
      ttsJson = root.resolve("tts.json"),
      unicodeIndexer = root.resolve("unicode_indexer.bin"),
      voiceStyle = root.resolve("voice.bin"),
    )
  }

  private fun requireFile(file: File) {
    if (!file.isFile || file.length() <= 0L) throw IOException("Missing file: ${file.name}")
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }
}
