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

package com.google.ai.edge.gallery.data.supplementary

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.di.ApplicationScope
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.AsrServiceStatus
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.errorprone.annotations.CanIgnoreReturnValue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "AGSpeechSelectionUseCase"

/** What a supplementary speech model can be the default engine for. */
enum class SpeechModality {
  /** Reading responses aloud (text-to-speech). */
  TTS,

  /** Speech input (speech recognition). */
  ASR,
}

/** The saved default engines, voice, and languages for read-aloud and speech input. */
data class SpeechSelection(
  val ttsEngineId: String = TtsModelIdentifier.ANDROID_NATIVE,
  val ttsVoiceId: String = "",
  val ttsLanguageCode: String = "",
  val asrEngineType: AsrEngineType = AsrEngineType.SYSTEM_DEFAULT,
  val asrModelId: String = "",
  val asrLanguageCode: String = "",
)

/**
 * How a supplementary model relates to the default engine of its [modality].
 *
 * @property isSelectable Whether the model can be made the default now: it is
 *   [SpeechModelState.Ready] and, if it installs languages separately, has at least one installed.
 * @property isDefault Whether the saved selection is this model and the model is selectable.
 * @property selectedVoiceId The saved voice (TTS only); empty when none is saved.
 * @property selectedLanguageCode The saved language for [modality]; empty for the engine default.
 */
data class SpeechDefaultChoice(
  val modality: SpeechModality,
  val isSelectable: Boolean,
  val isDefault: Boolean,
  val selectedVoiceId: String = "",
  val selectedLanguageCode: String = "",
)

/**
 * Why the saved default of [modality] can't be used right now. The saved selection is kept;
 * consumers show the notice and use the system engine instead.
 *
 * @property modelName The display name of the saved model, or its saved identifier when no
 *   allowlist model matches it.
 * @property state The state that keeps the saved model from being used.
 */
data class SpeechSelectionNotice(
  val modality: SpeechModality,
  val modelName: String,
  val state: SpeechModelState,
)

/**
 * The TTS engine, voice, and language read-aloud uses.
 *
 * @property engineId The engine to speak with: the saved engine, or the system engine when [notice]
 *   is set.
 * @property voiceId The voice to speak with.
 * @property languageCode The language to speak. For a system engine ([engineId]), the saved
 *   language, or empty for the device default. For any other engine, always a concrete language:
 *   the saved one, or [DEFAULT_TTS_LANGUAGE_CODE] when none is saved, so the engine never falls
 *   back to whatever language it last prepared.
 * @property notice Set when the saved engine can't be used and the system engine speaks instead.
 */
data class EffectiveTtsSelection(
  val engineId: String,
  val voiceId: String,
  val languageCode: String,
  val notice: SpeechSelectionNotice? = null,
)

/**
 * Applies the selection rules for supplementary speech models: a model can be the default engine if
 * and only if it is [SpeechModelState.Ready], automatic defaults are only picked among ready
 * models, and a saved default that can't be used is never replaced silently. Instead, consumers get
 * an [EffectiveTtsSelection] with a [SpeechSelectionNotice] and the saved choice is kept.
 *
 * This is also the one place that matches saved TTS engine identifiers and ASR selections to
 * allowlist models.
 */
@Singleton
open class SpeechSelectionUseCase
@Inject
constructor(
  private val repository: SupplementarySpeechModelRepository,
  private val ttsSettingsRepository: TtsSettingsRepository,
  private val asrSettingsRepository: AsrSettingsRepository,
  private val asrServiceProvider: AsrServiceProvider,
  @ApplicationScope private val scope: CoroutineScope,
) {

  /**
   * The supplementary speech models the allowlist registered, of every engine. Empty until the
   * allowlist loads, so saved selections can't be checked before it emits a non-empty list.
   */
  open fun hasRegisteredModelsFlow(modality: SpeechModality): Flow<Boolean> {
    val targetType = if (modality == SpeechModality.ASR) ModelType.ASR else ModelType.TTS
    return repository.models
      .map { models -> models.any { it.modelType == targetType } }
      .distinctUntilChanged()
  }

  /** The saved selection, updated whenever a setting changes. */
  val selection: Flow<SpeechSelection> =
    combine(
      combine(
        ttsSettingsRepository.selectedTtsModelID,
        ttsSettingsRepository.selectedTtsVoice,
        ttsSettingsRepository.selectedLanguageCode,
      ) { engineId, voiceId, languageCode ->
        SpeechSelection(
          ttsEngineId = engineId,
          ttsVoiceId = voiceId,
          ttsLanguageCode = languageCode,
        )
      },
      asrSettingsRepository.selectedEngineType,
      asrSettingsRepository.selectedModelId,
      asrSettingsRepository.selectedLanguageCode,
    ) { ttsSelection, asrEngineType, asrModelId, asrLanguageCode ->
      ttsSelection.copy(
        asrEngineType = asrEngineType,
        asrModelId = asrModelId,
        asrLanguageCode = asrLanguageCode,
      )
    }

  /** The saved selection right now. */
  fun currentSelection(): SpeechSelection =
    SpeechSelection(
      ttsEngineId = ttsSettingsRepository.selectedTtsModelID.value,
      ttsVoiceId = ttsSettingsRepository.selectedTtsVoice.value,
      ttsLanguageCode = ttsSettingsRepository.selectedLanguageCode.value,
      asrEngineType = asrSettingsRepository.selectedEngineType.value,
      asrModelId = asrSettingsRepository.selectedModelId.value,
      asrLanguageCode = asrSettingsRepository.selectedLanguageCode.value,
    )

  /**
   * What [model] can be the default for, or `null` when it can't be a default, such as an ASR model
   * whose engine this build doesn't know.
   */
  fun modalityOf(model: Model): SpeechModality? =
    when (model.modelType) {
      ModelType.TTS -> SpeechModality.TTS
      ModelType.ASR -> SpeechModality.ASR.takeIf { asrEngineTypeOf(model) != null }
      else -> null
    }

  /**
   * Whether [model] can be made the default engine: it can be a default ([modalityOf]), its [state]
   * is [SpeechModelState.Ready], and, if it installs languages separately, at least one of its
   * [languageStates] (or of the languages in [state]) is installed.
   */
  fun isSelectable(
    model: Model,
    state: SpeechModelState? = repository.getModelState(model.name),
    languageStates: Map<String, LanguageState> =
      repository.languageStates.value[model.name].orEmpty(),
  ): Boolean {
    if (state !is SpeechModelState.Ready || modalityOf(model) == null) {
      return false
    }
    if (!repository.installsLanguagePacksSeparately(model)) {
      return true
    }
    return languageStates.values.any { it is LanguageState.Installed } ||
      state.languages.any { it is LanguageState.Installed }
  }

  /** Whether [selection] saves [model] as the default engine of its modality. */
  fun isSavedDefault(model: Model, selection: SpeechSelection = currentSelection()): Boolean =
    when (model.modelType) {
      ModelType.TTS -> selection.ttsEngineId == ttsEngineIdOf(model)
      ModelType.ASR -> {
        val cardEngineType = AsrModelRegistry.engineTypeForModelId(model.modelId)
        if (cardEngineType != null) {
          selection.asrEngineType == cardEngineType &&
            (selection.asrModelId == AsrModelRegistry.modelId(cardEngineType) ||
              selection.asrModelId == model.modelId ||
              selection.asrModelId == model.name)
        } else {
          selection.asrModelId == model.name ||
            (model.modelId.isNotEmpty() && selection.asrModelId == model.modelId)
        }
      }
      else -> false
    }

  /**
   * How [model], in [state] and with [languageStates], relates to the default engine of its
   * modality under [selection], or `null` when it can't be a default.
   */
  fun defaultChoiceFor(
    model: Model,
    state: SpeechModelState?,
    languageStates: Map<String, LanguageState>,
    selection: SpeechSelection,
  ): SpeechDefaultChoice? {
    val modality = modalityOf(model) ?: return null
    val isSelectable = isSelectable(model, state, languageStates)
    return SpeechDefaultChoice(
      modality = modality,
      isSelectable = isSelectable,
      isDefault = isSelectable && isSavedDefault(model, selection),
      selectedVoiceId = if (modality == SpeechModality.TTS) selection.ttsVoiceId else "",
      selectedLanguageCode =
        when (modality) {
          SpeechModality.TTS -> selection.ttsLanguageCode
          SpeechModality.ASR -> selection.asrLanguageCode
        },
    )
  }

  /**
   * The registered models of [modality], downloaded or not, in allowlist order. Unlike
   * [selectableModels], it includes models the user can still download.
   */
  fun registeredModels(modality: SpeechModality): List<Model> =
    repository.models.value.filter { modalityOf(it) == modality }

  /** The registered models of [modality] that can be made the default now, in allowlist order. */
  fun selectableModels(modality: SpeechModality): List<Model> =
    repository.models.value.filter { modalityOf(it) == modality && isSelectable(it) }

  /**
   * Makes [model] the default engine of its modality when [isDefault] is `true`, or switches its
   * modality back to the system engine when `false` and [model] is the saved default.
   *
   * @return whether the selection changed. A model that isn't selectable ([isSelectable]) is never
   *   made the default.
   */
  @CanIgnoreReturnValue
  fun setDefault(model: Model, isDefault: Boolean): Boolean {
    val modality = modalityOf(model)
    if (modality == null) {
      Log.w(TAG, "Model '${model.name}' can't be a default engine")
      return false
    }
    if (!isDefault) {
      if (!isSavedDefault(model)) {
        return false
      }
      when (modality) {
        SpeechModality.TTS ->
          ttsSettingsRepository.setSelectedTtsModelID(
            id = TtsModelIdentifier.ANDROID_NATIVE,
            isExplicitUserSelection = true,
          )
        SpeechModality.ASR -> {
          asrSettingsRepository.setSelectedEngineType(AsrEngineType.SYSTEM_DEFAULT)
          switchActiveAsrEngine()
        }
      }
      return true
    }
    if (!isSelectable(model)) {
      Log.w(
        TAG,
        "Not making '${model.name}' the default: its state is " +
          "${repository.getModelState(model.name)?.javaClass?.simpleName}",
      )
      return false
    }
    when (modality) {
      SpeechModality.TTS -> {
        val engineId = ttsEngineIdOf(model)
        ttsSettingsRepository.setSelectedTtsModelID(
          id = engineId,
          isExplicitUserSelection = true,
          model = model,
        )
        ttsSettingsRepository.warmEngineIfVoicesMissing(
          engineId = engineId,
          model = model,
          supplementaryModels = repository.models.value,
        )
      }
      SpeechModality.ASR -> {
        val engineType = AsrModelRegistry.engineTypeForModelId(model.modelId)
        if (engineType != null) {
          asrSettingsRepository.setSelectedEngineType(engineType)
        } else {
          asrSettingsRepository.setSelectedModel(model)
        }
        switchActiveAsrEngine()
      }
    }
    return true
  }

  /**
   * Picks [languageCode] for [model] while it is the default engine. Only installed languages of a
   * model that installs languages separately can be picked.
   *
   * @return whether the language was picked.
   */
  @CanIgnoreReturnValue
  fun selectLanguage(model: Model, languageCode: String): Boolean {
    val modality = modalityOf(model)
    if (modality == null || !isSelectable(model) || !isSavedDefault(model)) {
      Log.w(TAG, "Can't pick a language for '${model.name}': it isn't the default engine")
      return false
    }
    if (
      repository.installsLanguagePacksSeparately(model) &&
        repository.getLanguageState(model.name, languageCode) !is LanguageState.Installed
    ) {
      Log.w(TAG, "Can't pick '$languageCode' for '${model.name}': it isn't installed")
      return false
    }
    when (modality) {
      SpeechModality.TTS ->
        ttsSettingsRepository.setSelectedLanguageCode(languageCode, repository.models.value)
      SpeechModality.ASR -> {
        asrSettingsRepository.setSelectedLanguageCode(languageCode)
        prepareActiveAsrEngineInBackground()
      }
    }
    return true
  }

  /** The registered TTS model saved under [engineId], or `null` if none matches. */
  fun ttsModelFor(engineId: String): Model? =
    repository.models.value.firstOrNull {
      it.modelType == ModelType.TTS && (ttsEngineIdOf(it) == engineId || it.name == engineId)
    }

  /**
   * The registered ASR model saved as [engineType] and [modelId], or `null` if none matches, for
   * example for the system recognizer.
   */
  fun asrModelFor(engineType: AsrEngineType?, modelId: String): Model? {
    if (engineType == null || AsrModelRegistry.isSystemEngine(engineType)) {
      return null
    }
    val selection = SpeechSelection(asrEngineType = engineType, asrModelId = modelId)
    return repository.models.value.firstOrNull {
      it.modelType == ModelType.ASR && isSavedDefault(it, selection)
    }
  }

  /** Whether any model of [modality] is registered, i.e. the allowlist has loaded them. */
  open fun hasRegisteredModels(modality: SpeechModality): Boolean =
    repository.models.value.any { modalityOf(it) == modality }

  /**
   * The BCP-47 tags of the languages [model] supports, from the allowlist (see
   * [SupplementarySpeechModelRepository.supportedLanguageCodes]).
   */
  fun supportedLanguageCodes(model: Model): List<String> = repository.supportedLanguageCodes(model)

  /** Whether [model] installs each language as a separate pack. */
  fun installsLanguagePacksSeparately(model: Model): Boolean =
    repository.installsLanguagePacksSeparately(model)

  /**
   * The TTS engine read-aloud uses for [selection], from the states known now. A saved model that
   * isn't ready, or that installs languages separately and doesn't have the language read-aloud
   * speaks ([spokenTtsLanguageCode]), yields the system engine with a notice. A saved model whose
   * first check hasn't finished, or any saved model before the allowlist registered the TTS models,
   * is used as saved; see [resolveEffectiveTtsSelection] to wait for the check.
   */
  fun effectiveTtsSelection(
    selection: SpeechSelection = currentSelection()
  ): EffectiveTtsSelection {
    if (TtsModelIdentifier.isSystemEngine(selection.ttsEngineId)) {
      return EffectiveTtsSelection(
        engineId = selection.ttsEngineId,
        voiceId = selection.ttsVoiceId,
        languageCode = selection.ttsLanguageCode,
      )
    }
    val saved =
      EffectiveTtsSelection(
        engineId = selection.ttsEngineId,
        voiceId = selection.ttsVoiceId,
        languageCode = spokenTtsLanguageCode(selection),
      )
    val model = ttsModelFor(selection.ttsEngineId)
    if (model == null) {
      if (!hasRegisteredModels(SpeechModality.TTS)) {
        return saved
      }
      return systemFallback(
        selection,
        SpeechSelectionNotice(
          modality = SpeechModality.TTS,
          modelName = selection.ttsEngineId,
          state = SpeechModelState.Hidden(HiddenReason.NO_ENGINE_IN_BUILD),
        ),
      )
    }
    val state = repository.getModelState(model.name)
    if (
      state == null ||
        state == SpeechModelState.Checking ||
        isAwaitingLanguageChecks(model, state) ||
        isSelectable(model, state)
    ) {
      val missingLanguage = missingSpokenLanguage(model, selection)
      if (state !is SpeechModelState.Ready || missingLanguage == null) {
        return saved
      }
      // The engine is ready but can't speak the language read-aloud uses.
      return systemFallback(
        selection,
        SpeechSelectionNotice(
          modality = SpeechModality.TTS,
          modelName = model.displayName.ifEmpty { model.name },
          state = SpeechModelState.NeedsLanguagePack(listOf(missingLanguage)),
        ),
      )
    }
    return systemFallback(
      selection,
      SpeechSelectionNotice(
        modality = SpeechModality.TTS,
        modelName = model.displayName.ifEmpty { model.name },
        state = state,
      ),
    )
  }

  /**
   * The language a non-system engine speaks for [selection]: the saved one, or
   * [DEFAULT_TTS_LANGUAGE_CODE].
   */
  private fun spokenTtsLanguageCode(selection: SpeechSelection): String =
    selection.ttsLanguageCode.ifEmpty { DEFAULT_TTS_LANGUAGE_CODE }

  /**
   * The state of the language read-aloud speaks with [model] when [model] installs languages
   * separately and that language is known not to be installed, or `null` otherwise (installed,
   * bundled with the model, or its check is pending or failed).
   */
  private fun missingSpokenLanguage(model: Model, selection: SpeechSelection): LanguageState? {
    if (!repository.installsLanguagePacksSeparately(model)) {
      return null
    }
    val languageState =
      repository.getLanguageState(model.name, spokenTtsLanguageCode(selection)) ?: return null
    return when (languageState) {
      // A failed or pending check doesn't tell whether the language is there.
      is LanguageState.Installed,
      is LanguageState.Checking,
      is LanguageState.CheckFailed -> null
      else -> languageState
    }
  }

  /**
   * Whether [model] is ready but installs languages separately and none of its language packs has
   * been checked yet, so whether it can speak isn't known.
   */
  private fun isAwaitingLanguageChecks(model: Model, state: SpeechModelState): Boolean =
    state is SpeechModelState.Ready &&
      state.languages.isEmpty() &&
      repository.installsLanguagePacksSeparately(model) &&
      repository.languageStates.value[model.name].isNullOrEmpty()

  /**
   * Like [effectiveTtsSelection], but first waits for the saved model's check and, for a model that
   * installs languages separately, its language packs (cached answers return right away), so the
   * result never uses a model that turns out not to be ready.
   */
  open suspend fun resolveEffectiveTtsSelection(): EffectiveTtsSelection {
    val selection = currentSelection()
    ttsModelFor(selection.ttsEngineId)?.let { model ->
      try {
        val state = repository.evaluateModel(model)
        // Whether a model that installs languages separately can speak depends on its packs.
        if (state is SpeechModelState.Ready && repository.installsLanguagePacksSeparately(model)) {
          val unused =
            repository.refreshLanguagePacks(
              model,
              (repository.supportedLanguageCodes(model) + spokenTtsLanguageCode(selection))
                .distinct(),
            )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check the saved TTS model '${model.name}'", e)
      }
    }
    val effective = effectiveTtsSelection(selection)
    // A check that failed before the first answer leaves no state, which is never usable.
    if (effective.notice == null && !TtsModelIdentifier.isSystemEngine(effective.engineId)) {
      val model = ttsModelFor(selection.ttsEngineId)
      if (model != null && repository.getModelState(model.name) == null) {
        return systemFallback(
          selection,
          SpeechSelectionNotice(
            modality = SpeechModality.TTS,
            modelName = model.displayName.ifEmpty { model.name },
            state = SpeechModelState.CheckFailed("No state"),
          ),
        )
      }
    }
    return effective
  }

  private fun systemFallback(
    selection: SpeechSelection,
    notice: SpeechSelectionNotice,
  ): EffectiveTtsSelection =
    EffectiveTtsSelection(
      engineId = TtsModelIdentifier.ANDROID_NATIVE,
      voiceId = TtsSettingsRepository.DEFAULT_SYSTEM_VOICE,
      languageCode = selection.ttsLanguageCode,
      notice = notice,
    )

  /**
   * The ASR engines that can be picked now: the system recognizer, each engine whose allowlist
   * model is selectable, and the speech processor while a sideloaded model makes it usable.
   */
  suspend fun selectableAsrEngineTypes(): Set<AsrEngineType> = buildSet {
    add(AsrEngineType.SYSTEM_DEFAULT)
    for (model in repository.models.value) {
      if (model.modelType != ModelType.ASR) {
        continue
      }
      val engineType = asrEngineTypeOf(model) ?: continue
      val state =
        try {
          repository.evaluateModel(model)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to check ASR model '${model.name}'", e)
          continue
        }
      if (isSelectable(model, state)) {
        add(engineType)
      }
    }
    // Add standalone engines (like SPEECH_PROCESSOR with a sideloaded model)
    for (engineType in AsrEngineType.entries) {
      val metadata = AsrModelRegistry.metadataFor(engineType)
      if (AsrModelRegistry.isSystemEngine(engineType) || metadata.requiresRepositoryModel) continue
      if (engineType !in this) {
        val service = asrServiceProvider.serviceOrNull(engineType) ?: continue
        if (isStandaloneEngineUsable(service)) {
          add(engineType)
        }
      }
    }
  }

  private suspend fun isStandaloneEngineUsable(service: AsrService): Boolean {
    return try {
      when (service.checkStatus()) {
        AsrServiceStatus.Ready,
        AsrServiceStatus.Downloaded,
        AsrServiceStatus.Initializing,
        AsrServiceStatus.Recognizing,
        AsrServiceStatus.Processing -> true
        else -> false
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to check the speech processor", e)
      false
    }
  }

  /**
   * Picks the read-aloud default when the user hasn't chosen one: the first ready TTS model, in
   * allowlist order, that ships all of its languages with the model. Models that install languages
   * separately are never picked, because the voice for the user's language may not be installed.
   * Every such ready model is also warmed up to load its voice list.
   *
   * @param onVoicesUpdated Called when warming up a model changes the voices it lists.
   * @return the model picked as the default, or `null` if none was.
   */
  @CanIgnoreReturnValue
  suspend fun autoPickDefaultTts(onVoicesUpdated: () -> Unit = {}): Model? {
    var picked: Model? = null
    for (model in repository.models.value) {
      if (model.modelType != ModelType.TTS || repository.installsLanguagePacksSeparately(model)) {
        continue
      }
      val engineId = model.ttsProfile?.engineModelId
      if (engineId.isNullOrEmpty()) {
        continue
      }
      val state =
        try {
          repository.evaluateModel(model)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to check TTS model '${model.name}'", e)
          continue
        }
      if (!isSelectable(model, state)) {
        continue
      }
      if (picked == null && !ttsSettingsRepository.hasUserExplicitTtsSelection()) {
        ttsSettingsRepository.setDefaultTtsModelID(engineId, model = model)
        picked = model
        Log.i(TAG, "Picked '${model.name}' as the read-aloud default")
      }
      ttsSettingsRepository.warmEngineIfVoicesMissing(
        engineId,
        model,
        onVoicesUpdated = onVoicesUpdated,
      )
    }
    return picked
  }

  /**
   * Records that the engine saved as TTS [engineId] failed at runtime for [reason], so its model
   * shows [SpeechModelState.EngineError] and stops being selectable until the user retries it. A
   * transient failure (such as [EngineErrorReason.SYNTHESIS_FAILED]) also ends after
   * [SupplementarySpeechModelRepository.CACHE_TTL_MS] or when a screen that reads aloud resumes
   * ([onReadAloudScreenResumed]).
   */
  fun reportTtsEngineError(
    engineId: String,
    reason: EngineErrorReason,
    recoverable: Boolean = true,
  ) {
    val model = ttsModelFor(engineId) ?: return
    repository.onEngineError(model.name, reason, recoverable)
  }

  /**
   * Called when a screen that reads aloud resumes. Clears a transient runtime failure of the saved
   * TTS engine reported by [reportTtsEngineError] or [onTtsVoiceUnavailable], so the next playback
   * tries that engine again instead of the system voice.
   */
  fun onReadAloudScreenResumed() {
    val model = ttsModelFor(currentSelection().ttsEngineId) ?: return
    repository.clearTransientEngineError(model.name)
  }

  /**
   * Handles a TTS voice for [languageCode] that is not available: re-checks the voice so a missing
   * voice shows as not installed (and a model left without any installed language needs a language
   * pack), and otherwise reports [EngineErrorReason.NOT_INSTALLED_AT_RUNTIME].
   */
  suspend fun onTtsVoiceUnavailable(engineId: String, languageCode: String?) {
    val model = ttsModelFor(engineId) ?: return
    val code = languageCode?.ifEmpty { null } ?: DEFAULT_TTS_LANGUAGE_CODE
    val state =
      try {
        if (repository.installsLanguagePacksSeparately(model)) {
          repository.refreshLanguagePacks(model, listOf(code), forceRefresh = true)[code]
        } else {
          null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check the '$code' voice of '${model.name}'", e)
        null
      }
    if (state == null || state is LanguageState.Installed) {
      repository.onEngineError(model.name, EngineErrorReason.NOT_INSTALLED_AT_RUNTIME, true)
    } else {
      Log.i(TAG, "The '$code' voice of '${model.name}' isn't installed: $state")
    }
  }

  /** The state of [model], waiting for its check if needed (cached answers return right away). */
  suspend fun resolveState(model: Model): SpeechModelState = repository.evaluateModel(model)

  /**
   * The state of [model]'s [languageCode] pack, waiting for its check if needed (cached answers
   * return right away). A model that ships its languages with the model reports them installed.
   */
  suspend fun resolveLanguageState(model: Model, languageCode: String): LanguageState {
    if (!repository.installsLanguagePacksSeparately(model)) {
      return LanguageState.Installed(languageCode)
    }
    return repository.refreshLanguagePacks(model, listOf(languageCode))[languageCode]
      ?: LanguageState.CheckFailed(languageCode, "No state")
  }

  private fun switchActiveAsrEngine() {
    asrServiceProvider.releaseAll(except = asrSettingsRepository.selectedEngineType.value)
    prepareActiveAsrEngineInBackground()
  }

  private fun prepareActiveAsrEngineInBackground() {
    scope.launch {
      try {
        val unused = asrServiceProvider.prepareActiveService()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to prepare the selected ASR engine", e)
      }
    }
  }

  companion object {
    /** The engine identifier TTS settings save when [model] is the read-aloud default. */
    fun ttsEngineIdOf(model: Model): String = model.ttsEngineId

    /**
     * The ASR engine that runs [model], or `null` if this build has none: the ML Kit engines for
     * their models, and the speech processor for LiteRT-LM models.
     */
    fun asrEngineTypeOf(model: Model): AsrEngineType? =
      AsrModelRegistry.engineTypeForModelId(model.modelId)
        ?: AsrEngineType.SPEECH_PROCESSOR.takeIf {
          model.backendSpec.runtimeType == RuntimeType.LITERT_LM
        }
  }
}
