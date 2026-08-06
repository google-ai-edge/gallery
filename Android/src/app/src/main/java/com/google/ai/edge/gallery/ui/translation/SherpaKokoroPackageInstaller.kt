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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "AGTranslationTtsPackage"
private const val PACKAGE_STORAGE_ROOT = "translation_tts/kokoro-sherpa"
private const val LEGACY_PACKAGE_STORAGE_ROOT = "kokoro_tts"
private val LEGACY_PACKAGE_IDS =
  setOf("kokoro-int8-multi-lang-v1_0", "kokoro-int8-multi-lang-v1_1")
private const val INSTALL_MANIFEST_NAME = "install.json"
private val KOKORO_ARCHIVE =
  SherpaArchive(
    name = "$KOKORO_SHERPA_PACKAGE_ID.tar.bz2",
    url = KOKORO_SHERPA_ARCHIVE_URL,
    sha256 = KOKORO_SHERPA_ARCHIVE_SHA256,
    maxEntries = 5000,
  )

internal data class KokoroSherpaPackage(
  val rootDirectory: File,
  val installManifest: File,
  val modelFile: File,
  val voicesFile: File,
  val tokensFile: File,
  val espeakDataDirectory: File,
  val dictionaryDirectory: File,
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
        if (installedPackage != null) {
          return@withLock installedPackage
        }
        val installOutcome = if (finalRoot.exists()) "replace_invalid_v2" else "install_v2"
        Log.i(
          TAG,
          "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID " +
            "outcome=$installOutcome",
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
        val archiveFile = downloadDirectory.resolve(KOKORO_ARCHIVE.name)

        try {
          KOKORO_ARCHIVE.download(destination = archiveFile, onProgress = onProgress)
          installVerifiedArchiveLocked(
            context = context,
            archiveFile = archiveFile,
            onProgress = onProgress,
          )
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
        val deleted = !packageRoot.exists() || packageRoot.deleteRecursively()
        deleteLegacyPackageCaches(context)
        deleted
      }
    }

  internal suspend fun installFromVerifiedArchive(
    context: Context,
    archiveFile: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit = {},
  ): KokoroSherpaPackage =
    withContext(Dispatchers.IO) {
      installMutex.withLock {
        findInstalled(context)?.let { return@withLock it }
        installVerifiedArchiveLocked(
          context = context,
          archiveFile = archiveFile,
          onProgress = onProgress,
        )
      }
    }

  private fun installVerifiedArchiveLocked(
    context: Context,
    archiveFile: File,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ): KokoroSherpaPackage {
    val finalRoot = packageDirectory(context)
    KOKORO_ARCHIVE.install(archiveFile, finalRoot, onProgress) { candidateRoot ->
      KOKORO_SHERPA_REQUIRED_ASSETS.forEach { relativePath ->
        requireFile(candidateRoot.resolve(relativePath))
      }
      writeInstallManifest(
        packageRoot = candidateRoot,
        archiveSizeBytes = archiveFile.length(),
      )
      validateInstalledPackage(root = candidateRoot, verifyHashes = true)
    }
    return validateInstalledPackage(root = finalRoot, verifyHashes = false).also {
      cachedPackage = it
      deleteLegacyPackageCaches(context)
      Log.i(
        TAG,
        "backend=$KOKORO_SHERPA_BACKEND revision=$KOKORO_SHERPA_PACKAGE_ID outcome=installed",
      )
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
        val actualHash = actualAssets.getValue(relativePath).sha256()
        if (actualHash != expectedHash) {
          throw IOException("Sherpa Kokoro asset checksum mismatch: $relativePath")
        }
      }
    }

    return KokoroSherpaPackage(
      rootDirectory = root,
      installManifest = installManifest,
      modelFile = root.resolve("model.onnx"),
      voicesFile = root.resolve("voices.bin"),
      tokensFile = root.resolve("tokens.txt"),
      espeakDataDirectory = root.resolve("espeak-ng-data"),
      dictionaryDirectory = root.resolve("dict"),
      lexiconFiles =
        listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt").map(root::resolve),
      ruleFstFiles = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").map(root::resolve),
      voiceConfigs = KOKORO_SHERPA_VOICE_CONFIGS.associateBy { it.languageTag },
    )
  }

  private fun hashPackageAssets(packageRoot: File): Map<String, String> =
    packageRoot.walkTopDown()
      .filter { file -> file.isFile && file.name != INSTALL_MANIFEST_NAME }
      .associate { file -> file.relativeTo(packageRoot).invariantSeparatorsPath to file.sha256() }

  private fun deleteLegacyPackageCaches(context: Context) {
    buildList {
        add(context.filesDir.resolve(LEGACY_PACKAGE_STORAGE_ROOT))
        LEGACY_PACKAGE_IDS.forEach { packageId ->
          add(context.filesDir.resolve(PACKAGE_STORAGE_ROOT).resolve(packageId))
        }
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

}
