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

package com.google.ai.edge.gallery.services.asr

import android.content.Context
import android.os.Environment
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import java.io.File

/** Canonical model identifiers for ASR engines. */
object AsrModelIdentifier {
  /** Android platform native speech recognizer (`android.speech.SpeechRecognizer`). */
  const val SYSTEM_DEFAULT = "android_system_asr"

  /** ML Kit on-device speech recognition in Basic mode. */
  const val MLKIT_BASIC = "mlkit_asr_basic"

  /** ML Kit on-device speech recognition in Advanced (AICore) mode. */
  const val MLKIT_ADVANCED = "mlkit_asr_advanced"

  /** On-device MediaPipe AudioTranscriber / LiteRT speech processor. */
  const val SPEECH_PROCESSOR = "speech_processor_asr"

  fun isSystemEngine(modelId: String?): Boolean = modelId == SYSTEM_DEFAULT
}

/**
 * Central catalog of ASR models and engines known to the app.
 *
 * Each concrete [AsrService] adapter exposes its own [AsrService.activeModel] and
 * [AsrService.availableModels] by delegating to the builders here. Keep this object as the single
 * source of truth for canonical model identifiers and baseline byte sizes.
 */
object AsrModelRegistry {

  /** Canonical display name for the ML Kit Basic ASR engine. */
  const val MLKIT_BASIC_MODEL_NAME = "ML Kit ASR (Basic)"

  /** Canonical display name for the ML Kit Advanced ASR engine. */
  const val MLKIT_ADVANCED_MODEL_NAME = "ML Kit ASR (Advanced)"

  /** Display name of the Parakeet ASR model. */
  const val PARAKEET_ASR_MODEL_NAME = "Parakeet TDT 0.6B ASR"

  /** Display name of the Gemma ASR model. */
  const val GEMMA_ASR_MODEL_NAME = "Gemma ASR"

  /** Display name of the SpeechProcessor engine when no specific model name is available. */
  const val SPEECH_PROCESSOR_DEFAULT_MODEL_NAME = "Parakeet TDT 0.6B ASR"

  /**
   * Approximate download size for the ML Kit Basic ASR feature (~45 MB), used for progress when the
   * reported size is unknown.
   */
  const val MLKIT_BASIC_MODEL_SIZE_BYTES = 47_185_920L

  /**
   * Approximate download size for the ML Kit Advanced ASR feature (~1.5 GB), used for progress when
   * the reported size is unknown.
   */
  const val MLKIT_ADVANCED_MODEL_SIZE_BYTES = 1_610_612_736L

  /** Baseline size (640.0 MB) of the sideloaded Parakeet `.litertlm` model. */
  const val SPEECH_PROCESSOR_DEFAULT_MODEL_SIZE_BYTES = 640_000_000L

  /**
   * File names accepted for the sideloaded LiteRT / MediaPipe AudioTranscriber model, in priority
   * order.
   */
  val MODEL_FILE_NAMES =
    listOf(
      "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm",
      "parakeet-tdt-0.6b-v3.litertlm",
    )

  /**
   * Returns the downloaded or sideloaded `.litertlm` model file, or `null` when no valid model file
   * is found.
   *
   * When [modelId] names an allowlist ASR model, only that registered model's downloaded file (from
   * [Model.getPath]) is returned, so a missing or not-yet-registered model never resolves to an
   * unrelated file. Otherwise (for `null` or [AsrModelIdentifier.SPEECH_PROCESSOR]), returns the
   * first downloaded registered allowlist model, then falls back to candidate sideload directories
   * covering app-specific external files directories, private files directories, shared download
   * folders, and standard staging locations.
   */
  fun resolveModelFile(context: Context? = null, modelId: String? = null): File? {
    if (!modelId.isNullOrEmpty() && modelId != AsrModelIdentifier.SPEECH_PROCESSOR) {
      if (context == null) return null
      val path = model(modelId)?.getPath(context)?.takeIf { it.isNotEmpty() } ?: return null
      return File(path).takeIf { it.isFile && it.length() > 0L }
    }
    if (context != null) {
      for (allowlistModel in registeredAllowlistModels) {
        val path = allowlistModel.getPath(context)
        if (path.isNotEmpty()) {
          val candidate = File(path)
          if (candidate.isFile && candidate.length() > 0L) {
            return candidate
          }
        }
      }
    }

    val externalDir = context?.getExternalFilesDir(null)
    val filesDir = context?.filesDir
    // Environment.getExternalStorageDirectory is deprecated in API 29+, retained as fallback for
    // rooted test devices
    @Suppress("DEPRECATION")
    val sdcardDir = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    val downloadsDir =
      runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
        .getOrNull() ?: sdcardDir?.let { File(it, "Download") }
    val candidateDirs =
      listOfNotNull(
        externalDir?.let { File(it, "speech_processor") },
        externalDir,
        externalDir?.let { File(it, "__imports") },
        filesDir?.let { File(it, "speech_processor") },
        filesDir,
        downloadsDir?.let { File(it, "speech_processor") },
        downloadsDir,
        sdcardDir?.let { File(it, "speech_processor") },
        sdcardDir,
        File("/data/local/tmp"),
      )

    // Check candidate directories for known model file names first
    for (dir in candidateDirs) {
      for (fileName in MODEL_FILE_NAMES) {
        val candidate = File(dir, fileName)
        if (candidate.isFile && candidate.length() > 0L) {
          return candidate
        }
      }
    }

    // Check speech_processor subdirectories for any non-empty .litertlm file
    for (dir in candidateDirs) {
      if (dir.name == "speech_processor") {
        val files = dir.listFiles()?.sortedBy { it.name } ?: continue
        for (file in files) {
          if (
            file.isFile && file.length() > 0L && file.name.endsWith(".litertlm", ignoreCase = true)
          ) {
            return file
          }
        }
      }
    }

    // Fallback: match any non-empty .litertlm file whose filename contains speech/audio/gemma
    for (dir in candidateDirs) {
      val files = dir.listFiles()?.sortedBy { it.name } ?: continue
      for (file in files) {
        if (
          file.isFile &&
            file.length() > 0L &&
            file.name.endsWith(".litertlm", ignoreCase = true) &&
            (file.name.contains("speech", ignoreCase = true) ||
              (file.name.contains("gemma", ignoreCase = true) &&
                file.name.contains("audio", ignoreCase = true)))
        ) {
          return file
        }
      }
    }
    return null
  }

  /** Checks whether a SpeechProcessor / AudioTranscriber model has been sideloaded on device. */
  fun isSpeechProcessorSideloaded(context: Context? = null): Boolean =
    resolveModelFile(context) != null

  /**
   * Returns the exact on-disk byte size of the sideloaded `.litertlm` model when present, or
   * [SPEECH_PROCESSOR_DEFAULT_MODEL_SIZE_BYTES] when nothing has been sideloaded yet.
   */
  fun resolveModelSizeBytes(context: Context? = null, modelId: String? = null): Long =
    resolveModelFile(context, modelId)?.length()?.takeIf { it > 0L }
      ?: SPEECH_PROCESSOR_DEFAULT_MODEL_SIZE_BYTES

  /**
   * Returns all ASR models available to the user on the current device:
   * - Always includes the built-in [AsrDefaults.SYSTEM_DEFAULT_MODEL_NAME] entry.
   * - Includes the ML Kit virtual entries ([MLKIT_BASIC_MODEL_NAME], [MLKIT_ADVANCED_MODEL_NAME]).
   * - Includes the sideloaded SpeechProcessor model when present.
   */
  fun availableModels(context: Context? = null): List<AsrModelMetadata> =
    listOfNotNull(
      systemDefaultModel(),
      mlKitBasicModel(),
      mlKitAdvancedModel(),
      speechProcessorModel(context),
    )

  /** Returns the [AsrModelMetadata] that canonically represents [engineType]. */
  fun metadataFor(engineType: AsrEngineType): AsrModelMetadata =
    when (engineType) {
      AsrEngineType.SYSTEM_DEFAULT -> systemDefaultModel()
      AsrEngineType.MLKIT_BASIC -> mlKitBasicModel()
      AsrEngineType.MLKIT_ADVANCED -> mlKitAdvancedModel()
      AsrEngineType.SPEECH_PROCESSOR -> defaultSpeechProcessorModel()
    }

  /** Returns whether [engineType] identifies the platform's native built-in speech recognizer. */
  fun isSystemEngine(engineType: AsrEngineType): Boolean =
    engineType == AsrEngineType.SYSTEM_DEFAULT

  /** Returns the canonical [AsrModelIdentifier] for [engineType]. */
  fun modelId(engineType: AsrEngineType): String =
    when (engineType) {
      AsrEngineType.SYSTEM_DEFAULT -> AsrModelIdentifier.SYSTEM_DEFAULT
      AsrEngineType.MLKIT_BASIC -> AsrModelIdentifier.MLKIT_BASIC
      AsrEngineType.MLKIT_ADVANCED -> AsrModelIdentifier.MLKIT_ADVANCED
      AsrEngineType.SPEECH_PROCESSOR -> AsrModelIdentifier.SPEECH_PROCESSOR
    }

  /** Returns whether [modelId] identifies an ASR engine that requires language pack downloads. */
  fun installsLanguagePacks(modelId: String?): Boolean =
    engineTypeForModelId(modelId)?.let { metadataFor(it).installsLanguagePacks } == true

  /**
   * Returns the [AsrEngineType] whose canonical [AsrModelIdentifier] equals [modelId], or `null` if
   * [modelId] is `null` or not a known identifier. Callers choose the default for `null`.
   */
  fun engineTypeForModelId(modelId: String?): AsrEngineType? =
    AsrEngineType.entries.firstOrNull { this.modelId(it) == modelId }

  /**
   * Resolves the [AsrEngineType] that serves [modelId]:
   * - Matches canonical [AsrModelIdentifier] constants via [engineTypeForModelId].
   * - Maps allowlist ASR models registered via [registerAllowlistModels] to
   *   [AsrEngineType.SPEECH_PROCESSOR].
   * - Returns `null` if [modelId] is `null`, empty, or unrecognized (including allowlist models
   *   that have not been registered yet; see [AsrSettingsRepository.refreshEngineType]).
   */
  fun resolveEngineType(modelId: String?): AsrEngineType? {
    if (modelId.isNullOrEmpty()) return null
    engineTypeForModelId(modelId)?.let {
      return it
    }
    if (model(modelId) != null) {
      return AsrEngineType.SPEECH_PROCESSOR
    }
    return null
  }

  /**
   * Resolves the [AsrEngineType] that backs a given supplementary [model], falling back to ML Kit
   * for older model entities.
   */
  fun resolveEngineTypeForModel(model: Model): AsrEngineType {
    return engineTypeForModelId(model.modelId)
      ?: resolveEngineType(model.name)
      ?: when (model.name) {
        MLKIT_ADVANCED_MODEL_NAME -> AsrEngineType.MLKIT_ADVANCED
        else -> AsrEngineType.MLKIT_BASIC
      }
  }

  /** Official BCP-47 locales supported by ML Kit GenAI Speech Recognition in `MODE_BASIC`. */
  val MLKIT_BASIC_SUPPORTED_LOCALES: List<String> =
    listOf(
      "en-US",
      "fr-FR",
      "it-IT",
      "de-DE",
      "es-ES",
      "hi-IN",
      "ja-JP",
      "pt-BR",
      "tr-TR",
      "pl-PL",
      "cmn-Hans-CN",
      "ko-KR",
      "cmn-Hant-TW",
      "ru-RU",
      "vi-VN",
    )

  /** Official BCP-47 locales supported by ML Kit GenAI Speech Recognition in `MODE_ADVANCED`. */
  val MLKIT_ADVANCED_SUPPORTED_LOCALES: List<String> =
    listOf(
      "en-US",
      "ko-KR",
      "es-ES",
      "fr-FR",
      "de-DE",
      "it-IT",
      "pt-PT",
      "cmn-Hans-CN",
      "cmn-Hant-TW",
      "ja-JP",
      "th-TH",
      "ru-RU",
      "nl-NL",
      "da-DK",
      "sv-SE",
      "pl-PL",
      "hi-IN",
      "vi-VN",
      "id-ID",
      "ar-SA",
      "tr-TR",
    )

  /**
   * Official BCP-47 locales supported by the Parakeet TDT 0.6B v3 ASR model (25 European
   * languages).
   */
  val PARAKEET_TDT_06B_SUPPORTED_LOCALES: List<String> =
    listOf(
      "bg-BG",
      "hr-HR",
      "cs-CZ",
      "da-DK",
      "nl-NL",
      "en-US",
      "et-EE",
      "fi-FI",
      "fr-FR",
      "de-DE",
      "el-GR",
      "hu-HU",
      "it-IT",
      "lv-LV",
      "lt-LT",
      "mt-MT",
      "pl-PL",
      "pt-PT",
      "ro-RO",
      "ru-RU",
      "sk-SK",
      "sl-SI",
      "es-ES",
      "sv-SE",
      "uk-UA",
    )

  /** Official BCP-47 locales supported by the multilingual Gemma ASR model. */
  val MULTILINGUAL_ASR_SUPPORTED_LOCALES: List<String> =
    listOf(
      "en-US",
      "ko-KR",
      "es-ES",
      "fr-FR",
      "de-DE",
      "it-IT",
      "pt-PT",
      "cmn-Hans-CN",
      "cmn-Hant-TW",
      "ja-JP",
      "th-TH",
      "ru-RU",
      "nl-NL",
      "da-DK",
      "sv-SE",
      "pl-PL",
      "hi-IN",
      "vi-VN",
      "id-ID",
      "ar-SA",
      "tr-TR",
    )

  private val allowlistLock = Any()
  private var allowlistAsrModels: List<Model> = emptyList()

  /**
   * Allowlist-declared languages (`asrConfig.languages`) of every registered ASR model, keyed by
   * [Model.modelId] and [Model.name]. Covers system-managed models such as ML Kit ASR, which are
   * not in [registeredAllowlistModels].
   */
  private var allowlistAsrLanguages: Map<String, List<String>> = emptyMap()

  /**
   * Supplementary ASR models registered from the active allowlist (`modelType == ModelType.ASR`).
   */
  val registeredAllowlistModels: List<Model>
    get() = synchronized(allowlistLock) { allowlistAsrModels }

  /**
   * Registers the supplementary ASR models from the loaded allowlist so their metadata and
   * preferred hardware accelerators can be resolved by name or model ID, and so the languages each
   * entry declares replace the built-in language lists.
   */
  fun registerAllowlistModels(models: List<Model>) {
    synchronized(allowlistLock) {
      allowlistAsrModels = models.filter {
        it.modelType == ModelType.ASR && it.backendSpec.runtimeType == RuntimeType.LITERT_LM
      }
      allowlistAsrLanguages = buildMap {
        for (model in models) {
          if (model.modelType != ModelType.ASR) continue
          val languages = model.asrProfile?.languages.orEmpty()
          if (languages.isEmpty()) continue
          if (model.modelId.isNotEmpty()) put(model.modelId, languages)
          if (model.name.isNotEmpty()) put(model.name, languages)
        }
      }
    }
  }

  /**
   * Returns the allowlist-declared languages registered for [modelIdOrName], or an empty list if
   * none are registered.
   */
  private fun allowlistLanguages(modelIdOrName: String): List<String> =
    synchronized(allowlistLock) { allowlistAsrLanguages[modelIdOrName].orEmpty() }

  /**
   * Returns the registered allowlist ASR [Model] matching [named] by [Model.name] or
   * [Model.modelId], or `null` if none is registered.
   */
  fun model(named: String): Model? =
    synchronized(allowlistLock) {
      allowlistAsrModels.firstOrNull {
        it.name == named || (it.modelId.isNotEmpty() && it.modelId == named)
      }
    }

  /**
   * Returns the supported BCP-47 language codes for [engineType]: the languages the registered
   * allowlist entry declares (`asrConfig.languages`), or the built-in list when it declares none.
   */
  fun supportedLanguages(engineType: AsrEngineType): List<String> =
    when (engineType) {
      AsrEngineType.SYSTEM_DEFAULT -> emptyList()
      AsrEngineType.MLKIT_BASIC ->
        allowlistLanguages(AsrModelIdentifier.MLKIT_BASIC).ifEmpty { MLKIT_BASIC_SUPPORTED_LOCALES }
      AsrEngineType.MLKIT_ADVANCED ->
        allowlistLanguages(AsrModelIdentifier.MLKIT_ADVANCED).ifEmpty {
          MLKIT_ADVANCED_SUPPORTED_LOCALES
        }
      AsrEngineType.SPEECH_PROCESSOR -> listOf(AsrDefaults.LANGUAGE_CODE)
    }

  /**
   * Returns the supported BCP-47 language codes for an allowlist ASR model identified by
   * [modelName] (or `modelId`), matching iOS `ASRModelRegistry.supportedLanguages(for:)`.
   *
   * Uses the languages the registered allowlist entry declares (`asrConfig.languages`) when there
   * are any, and otherwise falls back to matching the model name against the built-in lists, for
   * allowlists that predate `asrConfig` and for sideloaded models.
   *
   * The languages only drive UI (model cards and language pickers): `AudioTranscriber` takes no
   * language option, and multilingual models auto-detect the spoken language.
   */
  fun supportedLanguagesForModelName(modelName: String): List<String> =
    allowlistLanguages(modelName).ifEmpty {
      when {
        modelName.contains("parakeet", ignoreCase = true) -> PARAKEET_TDT_06B_SUPPORTED_LOCALES
        modelName.contains("multilingual", ignoreCase = true) -> MULTILINGUAL_ASR_SUPPORTED_LOCALES
        else -> listOf(AsrDefaults.LANGUAGE_CODE)
      }
    }

  /**
   * Returns the supported BCP-47 language codes for [model].
   *
   * Prefers the languages [model]'s allowlist entry declares ([Model.asrProfile]). Otherwise
   * resolves known [AsrEngineType] models via [supportedLanguages] and `.litertlm` allowlist ASR
   * models via [supportedLanguagesForModelName].
   */
  fun supportedLanguages(model: Model): List<String> {
    model.asrProfile
      ?.languages
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return it
      }
    val engineType = engineTypeForModelId(model.modelId)
    if (engineType != null) {
      return supportedLanguages(engineType)
    }
    if (model.backendSpec.runtimeType == RuntimeType.LITERT_LM) {
      val key = model.name.ifEmpty { model.modelId }
      return supportedLanguagesForModelName(key)
    }
    return emptyList()
  }

  /** Builds the [AsrModelMetadata] for [SystemSpeechAsrService]. */
  fun systemDefaultModel(): AsrModelMetadata =
    AsrModelMetadata(
      name = AsrDefaults.SYSTEM_DEFAULT_MODEL_NAME,
      modelId = AsrModelIdentifier.SYSTEM_DEFAULT,
      engineType = AsrEngineType.SYSTEM_DEFAULT,
      requiresRepositoryModel = false,
    )

  /** Builds the [AsrModelMetadata] for [MlKitAsrService] in [AsrEngineType.MLKIT_BASIC] mode. */
  fun mlKitBasicModel(): AsrModelMetadata =
    AsrModelMetadata(
      name = MLKIT_BASIC_MODEL_NAME,
      modelId = AsrModelIdentifier.MLKIT_BASIC,
      engineType = AsrEngineType.MLKIT_BASIC,
      installsLanguagePacks = true,
      hasFixedLanguageList = true,
      supportedLanguages = supportedLanguages(AsrEngineType.MLKIT_BASIC),
    )

  /** Builds the [AsrModelMetadata] for [MlKitAsrService] in [AsrEngineType.MLKIT_ADVANCED] mode. */
  fun mlKitAdvancedModel(): AsrModelMetadata =
    AsrModelMetadata(
      name = MLKIT_ADVANCED_MODEL_NAME,
      modelId = AsrModelIdentifier.MLKIT_ADVANCED,
      engineType = AsrEngineType.MLKIT_ADVANCED,
      isLanguageAutoDetected = true,
      hasFixedLanguageList = true,
      supportedLanguages = supportedLanguages(AsrEngineType.MLKIT_ADVANCED),
    )

  /**
   * Builds the default in-memory [AsrModelMetadata] for [SpeechProcessorAsrService] without
   * performing filesystem I/O.
   */
  fun defaultSpeechProcessorModel(): AsrModelMetadata =
    AsrModelMetadata(
      name = SPEECH_PROCESSOR_DEFAULT_MODEL_NAME,
      modelId = AsrModelIdentifier.SPEECH_PROCESSOR,
      engineType = AsrEngineType.SPEECH_PROCESSOR,
      isLanguageAutoDetected = true,
      requiresRepositoryModel = false,
      supportedLanguages = listOf(AsrDefaults.LANGUAGE_CODE),
      supportedAccelerators = listOf(Accelerator.GPU, Accelerator.CPU),
      preferredBackend = Accelerator.GPU,
    )

  /**
   * Builds the [AsrModelMetadata] for [SpeechProcessorAsrService] when a model is downloaded via
   * the allowlist or sideloaded on disk.
   */
  fun speechProcessorModel(context: Context? = null, modelId: String? = null): AsrModelMetadata? {
    val modelFile = resolveModelFile(context, modelId) ?: return null
    val matchedAllowlistModel =
      when {
        !modelId.isNullOrEmpty() && modelId != AsrModelIdentifier.SPEECH_PROCESSOR -> model(modelId)
        context != null ->
          registeredAllowlistModels.firstOrNull { candidate ->
            val path = candidate.getPath(context)
            path.isNotEmpty() && File(path).absolutePath == modelFile.absolutePath
          }
        else -> null
      }

    if (matchedAllowlistModel != null) {
      val resolvedName =
        matchedAllowlistModel.name.ifEmpty {
          matchedAllowlistModel.modelId.ifEmpty { modelFile.name }
        }
      val resolvedModelId =
        matchedAllowlistModel.name.ifEmpty {
          matchedAllowlistModel.modelId.ifEmpty { AsrModelIdentifier.SPEECH_PROCESSOR }
        }
      val isGemma =
        resolvedName.contains("gemma", ignoreCase = true) ||
          modelFile.name.contains("gemma", ignoreCase = true)
      val accelerators =
        matchedAllowlistModel.backendSpec.accelerators.ifEmpty {
          if (isGemma) listOf(Accelerator.GPU) else listOf(Accelerator.GPU, Accelerator.CPU)
        }
      return AsrModelMetadata(
        name = resolvedName,
        modelId = resolvedModelId,
        engineType = AsrEngineType.SPEECH_PROCESSOR,
        isLanguageAutoDetected = true,
        requiresRepositoryModel = false,
        supportedLanguages = supportedLanguages(matchedAllowlistModel),
        supportedAccelerators = accelerators,
        preferredBackend = AsrSessionSettings.preferredBackend(matchedAllowlistModel),
      )
    }

    val isGemma = modelFile.name.contains("gemma", ignoreCase = true)
    val displayName =
      when {
        modelFile.name.contains("parakeet", ignoreCase = true) -> PARAKEET_ASR_MODEL_NAME
        isGemma -> GEMMA_ASR_MODEL_NAME
        else -> modelFile.name
      }
    return AsrModelMetadata(
      name = displayName,
      modelId = AsrModelIdentifier.SPEECH_PROCESSOR,
      engineType = AsrEngineType.SPEECH_PROCESSOR,
      isLanguageAutoDetected = true,
      requiresRepositoryModel = false,
      supportedLanguages = supportedLanguagesForModelName(modelFile.name),
      supportedAccelerators =
        if (isGemma) listOf(Accelerator.GPU) else listOf(Accelerator.GPU, Accelerator.CPU),
      preferredBackend = Accelerator.GPU,
    )
  }

  /**
   * Returns the preferred default [Accelerator] for [modelId] when the caller has not specified an
   * explicit backend override.
   *
   * Resolves registered allowlist ASR models via [AsrSessionSettings.preferredBackend] first. When
   * [context] is `null`, avoids filesystem scanning and returns the default preferred backend
   * (`Accelerator.GPU` for [AsrModelIdentifier.SPEECH_PROCESSOR]); `SpeechProcessorAsrService`
   * resolves the exact on-disk model's `preferredBackend` and `supportedAccelerators` with its
   * application [Context] during `prepare()`.
   */
  fun preferredBackend(modelId: String, context: Context? = null): Accelerator {
    model(modelId)?.let {
      return AsrSessionSettings.preferredBackend(it)
    }
    return when {
      modelId == AsrModelIdentifier.SPEECH_PROCESSOR ->
        if (context != null) {
          speechProcessorModel(context)?.preferredBackend ?: Accelerator.GPU
        } else {
          Accelerator.GPU
        }
      else -> AsrDefaults.BACKEND
    }
  }

  /**
   * Returns the expected or actual model size in bytes for the given [engineType].
   *
   * Used by download progress bars and model management UI to display accurate byte totals even
   * before a download starts.
   */
  fun modelSizeBytes(engineType: AsrEngineType, context: Context? = null): Long =
    when (engineType) {
      AsrEngineType.SYSTEM_DEFAULT -> 0L
      AsrEngineType.MLKIT_BASIC -> MLKIT_BASIC_MODEL_SIZE_BYTES
      AsrEngineType.MLKIT_ADVANCED -> MLKIT_ADVANCED_MODEL_SIZE_BYTES
      AsrEngineType.SPEECH_PROCESSOR -> resolveModelSizeBytes(context)
    }
}
