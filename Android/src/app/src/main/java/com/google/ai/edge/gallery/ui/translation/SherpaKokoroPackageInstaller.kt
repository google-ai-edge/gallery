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
import android.util.Log
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

private const val TAG = "AGTranslationTtsPackage"
private const val PACKAGE_STORAGE_ROOT = "translation_tts/kokoro-sherpa"
private const val LEGACY_PACKAGE_STORAGE_ROOT = "kokoro_tts"
private const val PACKAGE_ARCHIVE_NAME = "$KOKORO_SHERPA_PACKAGE_ID.tar.bz2"
private const val INSTALL_MANIFEST_NAME = "install.json"
private const val MAX_ARCHIVE_ENTRIES = 5000
private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L

internal data class KokoroSherpaPackage(
  val rootDirectory: File,
  val installManifest: File,
  val modelFile: File,
  val voicesFile: File,
  val tokensFile: File,
  val espeakDataDirectory: File,
  val lexiconFiles: List<File>,
  val ruleFstFiles: List<File>,
  val voiceConfigs: Map<String, KokoroSherpaVoiceConfig>,
)

internal object SherpaKokoroPackageInstaller {
  private val installMutex = Mutex()

  @Volatile private var cachedPackage: KokoroSherpaPackage? = null

  fun packageDirectory(context: Context): File =
    context.filesDir.resolve(PACKAGE_STORAGE_ROOT).resolve(KOKORO_SHERPA_PACKAGE_ID)

  fun findInstalled(context: Context): KokoroSherpaPackage? {
    val expectedRoot = packageDirectory(context).absoluteFile
    cachedPackage
      ?.takeIf {
        it.rootDirectory.absoluteFile == expectedRoot && it.installManifest.isFile
      }
      ?.let {
        deleteLegacyPackageCaches(context)
        return it
      }
    if (!expectedRoot.isDirectory) return null

    return runCatching { validateInstalledPackage(root = expectedRoot, verifyHashes = true) }
      .onFailure {
        Log.w(
          TAG,
          "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
            "outcome=validation_failed",
        )
      }
      .getOrNull()
      ?.also { installedPackage ->
        cachedPackage = installedPackage
        deleteLegacyPackageCaches(context)
      }
  }

  suspend fun ensureInstalled(
    context: Context,
    onProgress: (TranslationTtsDownloadProgress) -> Unit = {},
  ): KokoroSherpaPackage =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        val finalRoot = packageDirectory(context)
        val installedPackage = findInstalled(context)
        val installAction =
          SherpaKokoroRegressionPolicy.installAction(
            validV2Installed = installedPackage != null,
            v2DirectoryPresent = finalRoot.exists(),
          )
        if (installAction == KokoroSherpaInstallAction.USE_VALID_V2) {
          return@withLock checkNotNull(installedPackage)
        }
        Log.i(
          TAG,
          "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
            "outcome=${installAction.name.lowercase()}",
        )

        val packageParent = finalRoot.parentFile
          ?: throw IOException("Sherpa Kokoro package root has no parent directory.")
        if (!packageParent.isDirectory && !packageParent.mkdirs()) {
          throw IOException("Unable to create Sherpa Kokoro package storage: $packageParent")
        }
        val downloadDirectory = packageParent.resolve(".$KOKORO_SHERPA_PACKAGE_ID.download")
        if (downloadDirectory.exists() && !downloadDirectory.deleteRecursively()) {
          throw IOException("Unable to clear stale Kokoro download: $downloadDirectory")
        }
        if (!downloadDirectory.mkdirs()) {
          throw IOException("Unable to create Kokoro download directory: $downloadDirectory")
        }
        val archiveFile = downloadDirectory.resolve(PACKAGE_ARCHIVE_NAME)

        try {
          downloadArchive(destination = archiveFile, onProgress = onProgress)
          installVerifiedArchiveLocked(context = context, archiveFile = archiveFile)
        } finally {
          downloadDirectory.deleteRecursively()
        }
      }
    }

  suspend fun deleteInstalled(context: Context): Boolean =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        cachedPackage = null
        val packageRoot = packageDirectory(context)
        !packageRoot.exists() || packageRoot.deleteRecursively()
      }
    }

  internal suspend fun installFromVerifiedArchive(
    context: Context,
    archiveFile: File,
  ): KokoroSherpaPackage =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        findInstalled(context)?.let { return@withLock it }
        installVerifiedArchiveLocked(context = context, archiveFile = archiveFile)
      }
    }

  private fun installVerifiedArchiveLocked(
    context: Context,
    archiveFile: File,
  ): KokoroSherpaPackage {
    requireFile(archiveFile)
    val archiveHash = sha256(archiveFile)
    if (archiveHash != KOKORO_SHERPA_ARCHIVE_SHA256) {
      throw IOException(
        "Sherpa Kokoro archive checksum mismatch: expected=$KOKORO_SHERPA_ARCHIVE_SHA256, " +
          "actual=$archiveHash"
      )
    }

    val finalRoot = packageDirectory(context)
    val packageParent = finalRoot.parentFile
      ?: throw IOException("Sherpa Kokoro package root has no parent directory.")
    if (!packageParent.isDirectory && !packageParent.mkdirs()) {
      throw IOException("Unable to create Sherpa Kokoro package storage: $packageParent")
    }
    val stagingRoot = packageParent.resolve(".$KOKORO_SHERPA_PACKAGE_ID.staging")
    if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
      throw IOException("Unable to clear stale Kokoro staging directory: $stagingRoot")
    }
    if (!stagingRoot.mkdirs()) {
      throw IOException("Unable to create Kokoro staging directory: $stagingRoot")
    }

    try {
      extractArchive(archiveFile = archiveFile, destination = stagingRoot)
      val candidateRoot = stagingRoot.resolve(KOKORO_SHERPA_PACKAGE_ID)
      KOKORO_SHERPA_REQUIRED_ASSETS.forEach { relativePath ->
        requireFile(candidateRoot.resolve(relativePath))
      }
      writeInstallManifest(
        packageRoot = candidateRoot,
        archiveSizeBytes = archiveFile.length(),
      )
      validateInstalledPackage(root = candidateRoot, verifyHashes = true)

      if (finalRoot.exists() && !finalRoot.deleteRecursively()) {
        throw IOException("Unable to replace invalid Kokoro v2 package: $finalRoot")
      }
      if (!candidateRoot.renameTo(finalRoot)) {
        throw IOException("Unable to atomically install Kokoro v2 package: $finalRoot")
      }

      return validateInstalledPackage(root = finalRoot, verifyHashes = false).also {
        cachedPackage = it
        deleteLegacyPackageCaches(context)
        Log.i(
          TAG,
          "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID outcome=installed",
        )
      }
    } finally {
      stagingRoot.deleteRecursively()
    }
  }

  private fun downloadArchive(
    destination: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ) {
    val connection =
      (URL(KOKORO_SHERPA_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "Google-AI-Edge-Gallery")
      }
    val responseCode = connection.responseCode
    if (responseCode !in 200..299) {
      connection.disconnect()
      throw IOException("Failed to download Kokoro package: HTTP $responseCode")
    }

    try {
      val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
      var downloadedBytes = 0L
      var lastProgressMillis = 0L
      onProgress(
        TranslationTtsDownloadProgress(
          PACKAGE_ARCHIVE_NAME,
          0L,
          totalBytes,
          0,
          totalFiles = 1,
        )
      )
      BufferedInputStream(connection.inputStream).use { input ->
        BufferedOutputStream(destination.outputStream()).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val readCount = input.read(buffer)
            if (readCount == -1) break
            output.write(buffer, 0, readCount)
            downloadedBytes += readCount
            val now = System.currentTimeMillis()
            if (now - lastProgressMillis >= 250L) {
              lastProgressMillis = now
              onProgress(
                TranslationTtsDownloadProgress(
                  PACKAGE_ARCHIVE_NAME,
                  downloadedBytes,
                  totalBytes,
                  0,
                  totalFiles = 1,
                )
              )
            }
          }
        }
      }
      if (downloadedBytes == 0L || (totalBytes > 0L && downloadedBytes != totalBytes)) {
        throw IOException(
          "Incomplete Sherpa Kokoro download: downloaded=$downloadedBytes, expected=$totalBytes"
        )
      }
      if (sha256(destination) != KOKORO_SHERPA_ARCHIVE_SHA256) {
        throw IOException("Downloaded Sherpa Kokoro archive failed checksum verification.")
      }
      onProgress(
        TranslationTtsDownloadProgress(
          PACKAGE_ARCHIVE_NAME,
          downloadedBytes,
          if (totalBytes > 0L) totalBytes else downloadedBytes,
          completedFiles = 1,
          totalFiles = 1,
        )
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun extractArchive(archiveFile: File, destination: File) {
    val canonicalDestination = destination.canonicalFile
    var entryCount = 0
    var extractedBytes = 0L

    TarArchiveInputStream(
        BZip2CompressorInputStream(BufferedInputStream(archiveFile.inputStream()))
      )
      .use { archiveInput ->
        while (true) {
          val entry = archiveInput.nextEntry ?: break
          entryCount++
          if (entryCount > MAX_ARCHIVE_ENTRIES) {
            throw IOException("Sherpa Kokoro archive contains too many entries.")
          }
          if (entry.isLink || entry.isSymbolicLink) {
            throw IOException("Sherpa Kokoro archive contains an unsupported link: ${entry.name}")
          }

          val output = destination.resolve(entry.name).canonicalFile
          if (
            output != canonicalDestination &&
              !output.path.startsWith(canonicalDestination.path + File.separator)
          ) {
            throw IOException("Blocked unsafe Sherpa Kokoro archive entry: ${entry.name}")
          }
          if (entry.isDirectory) {
            if (!output.isDirectory && !output.mkdirs()) {
              throw IOException("Unable to create extracted directory: $output")
            }
            continue
          }
          if (!entry.isFile) {
            throw IOException("Unsupported Sherpa Kokoro archive entry: ${entry.name}")
          }
          output.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
              throw IOException("Unable to create extracted directory: $parent")
            }
          }
          BufferedOutputStream(output.outputStream()).use { fileOutput ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryBytes = 0L
            while (true) {
              val readCount = archiveInput.read(buffer)
              if (readCount == -1) break
              fileOutput.write(buffer, 0, readCount)
              entryBytes += readCount
              extractedBytes += readCount
              if (extractedBytes > MAX_EXTRACTED_BYTES) {
                throw IOException("Sherpa Kokoro archive exceeds the extraction size limit.")
              }
            }
            if (entry.size >= 0L && entryBytes != entry.size) {
              throw IOException(
                "Incomplete Sherpa Kokoro archive entry ${entry.name}: " +
                  "extracted=$entryBytes, expected=${entry.size}"
              )
            }
          }
        }
      }
  }

  private fun writeInstallManifest(packageRoot: File, archiveSizeBytes: Long) {
    val assetHashes = hashPackageAssets(packageRoot)
    KOKORO_SHERPA_TRUSTED_CORE_HASHES.forEach { (relativePath, expectedHash) ->
      if (assetHashes[relativePath] != expectedHash) {
        throw IOException("Trusted Kokoro asset checksum mismatch: $relativePath")
      }
    }

    val languages = JSONObject()
    KOKORO_SHERPA_VOICE_CONFIGS.forEach { config ->
      languages.put(
        config.languageTag,
        JSONObject()
          .put("espeak_voice", config.espeakVoice)
          .put("speaker_name", config.speakerName)
          .put("speaker_id", config.speakerId),
      )
    }
    val assets = JSONObject()
    assetHashes.toSortedMap().forEach { (relativePath, hash) -> assets.put(relativePath, hash) }
    val manifest =
      JSONObject()
        .put("schema_version", KOKORO_SHERPA_SCHEMA_VERSION)
        .put("backend", KOKORO_SHERPA_BACKEND)
        .put("runtime_version", KOKORO_SHERPA_RUNTIME_VERSION)
        .put("sample_rate_hz", KOKORO_SHERPA_SAMPLE_RATE)
        .put(
          "package",
          JSONObject()
            .put("id", KOKORO_SHERPA_PACKAGE_ID)
            .put("revision", KOKORO_SHERPA_PACKAGE_ID)
            .put("source_url", KOKORO_SHERPA_ARCHIVE_URL)
            .put("archive_sha256", KOKORO_SHERPA_ARCHIVE_SHA256)
            .put("archive_size_bytes", archiveSizeBytes),
        )
        .put("languages", languages)
        .put("assets", assets)

    packageRoot.resolve(INSTALL_MANIFEST_NAME).writeText(manifest.toString(2) + "\n")
  }

  private fun validateInstalledPackage(
    root: File,
    verifyHashes: Boolean,
  ): KokoroSherpaPackage {
    if (!root.isDirectory) throw IOException("Sherpa Kokoro package directory is missing: $root")
    val installManifest = requireFile(root.resolve(INSTALL_MANIFEST_NAME))
    val manifest = JSONObject(installManifest.readText())
    val packageJson = manifest.getJSONObject("package")
    val languagesJson = manifest.getJSONObject("languages")
    val languageConfigs = mutableMapOf<String, KokoroSherpaVoiceConfig>()
    val languageKeys = languagesJson.keys()
    while (languageKeys.hasNext()) {
      val languageTag = languageKeys.next()
      val language = languagesJson.getJSONObject(languageTag)
      languageConfigs[languageTag] =
        KokoroSherpaVoiceConfig(
          languageTag = languageTag,
          espeakVoice = language.getString("espeak_voice"),
          speakerName = language.getString("speaker_name"),
          speakerId = language.getInt("speaker_id"),
        )
    }

    val assetsJson = manifest.getJSONObject("assets")
    val expectedAssets = mutableMapOf<String, String>()
    val assetKeys = assetsJson.keys()
    while (assetKeys.hasNext()) {
      val relativePath = assetKeys.next()
      validateRelativePath(root = root, relativePath = relativePath)
      expectedAssets[relativePath] = assetsJson.getString(relativePath)
    }
    val actualAssets =
      root.walkTopDown()
        .filter { file -> file.isFile && file.name != INSTALL_MANIFEST_NAME }
        .associate { file -> file.relativeTo(root).invariantSeparatorsPath to file }
    SherpaKokoroRegressionPolicy.validatePackageMetadata(
      metadata =
        KokoroSherpaManifestMetadata(
          schemaVersion = manifest.getInt("schema_version"),
          backend = manifest.getString("backend"),
          runtimeVersion = manifest.getString("runtime_version"),
          sampleRate = manifest.getInt("sample_rate_hz"),
          packageId = packageJson.getString("id"),
          revision = packageJson.getString("revision"),
          sourceUrl = packageJson.getString("source_url"),
          archiveSha256 = packageJson.getString("archive_sha256"),
          languages = languageConfigs,
          assetHashes = expectedAssets,
        ),
      actualAssetPaths = actualAssets.keys,
    )
    KOKORO_SHERPA_REQUIRED_ASSETS.forEach { relativePath ->
      requireFile(actualAssets[relativePath] ?: root.resolve(relativePath))
    }
    if (verifyHashes) {
      expectedAssets.forEach { (relativePath, expectedHash) ->
        val actualHash = sha256(actualAssets.getValue(relativePath))
        if (actualHash != expectedHash) {
          throw IOException("Sherpa Kokoro asset checksum mismatch: $relativePath")
        }
      }
    }

    return KokoroSherpaPackage(
      rootDirectory = root,
      installManifest = installManifest,
      modelFile = root.resolve("model.int8.onnx"),
      voicesFile = root.resolve("voices.bin"),
      tokensFile = root.resolve("tokens.txt"),
      espeakDataDirectory = root.resolve("espeak-ng-data"),
      lexiconFiles =
        listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt").map(root::resolve),
      ruleFstFiles = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").map(root::resolve),
      voiceConfigs = KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag },
    )
  }

  private fun hashPackageAssets(packageRoot: File): Map<String, String> =
    packageRoot.walkTopDown()
      .filter { file -> file.isFile && file.name != INSTALL_MANIFEST_NAME }
      .associate { file -> file.relativeTo(packageRoot).invariantSeparatorsPath to sha256(file) }

  private fun deleteLegacyPackageCaches(context: Context) {
    buildList {
        add(context.filesDir.resolve(LEGACY_PACKAGE_STORAGE_ROOT))
        context.getExternalFilesDir(null)?.let { externalFilesDir ->
          add(externalFilesDir.resolve(LEGACY_PACKAGE_STORAGE_ROOT))
        }
      }
      .map { file -> file.absoluteFile }
      .distinctBy { file -> file.path }
      .filter { file -> file.exists() }
      .forEach { legacyRoot ->
        val deleted = runCatching { legacyRoot.deleteRecursively() }.getOrDefault(false)
        if (deleted) {
          Log.i(
            TAG,
            "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
              "outcome=legacy_cache_deleted",
          )
        } else {
          Log.w(
            TAG,
            "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
              "outcome=legacy_cache_delete_failed",
          )
        }
      }
  }

  private fun validateRelativePath(root: File, relativePath: String) {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = root.resolve(relativePath).canonicalFile
    if (
      relativePath.isBlank() ||
        relativePath.startsWith("/") ||
        (canonicalFile != canonicalRoot &&
          !canonicalFile.path.startsWith(canonicalRoot.path + File.separator))
    ) {
      throw IOException("Unsafe Sherpa Kokoro asset path: $relativePath")
    }
  }

  private fun requireFile(file: File): File {
    if (!file.isFile || file.length() <= 0L) {
      throw IOException("Sherpa Kokoro package asset is missing or empty: ${file.absolutePath}")
    }
    return file
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(file.inputStream()).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val readCount = input.read(buffer)
        if (readCount == -1) break
        digest.update(buffer, 0, readCount)
      }
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
      digest.digest().forEach { byte ->
        val value = byte.toInt() and 0xff
        append(hex[value ushr 4])
        append(hex[value and 0x0f])
      }
    }
  }
}
