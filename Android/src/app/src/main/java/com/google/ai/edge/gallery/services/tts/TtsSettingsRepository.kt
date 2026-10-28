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

package com.google.ai.edge.gallery.services.tts

import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.TtsVoice
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.TtsSettingsProto
import com.google.ai.edge.gallery.proto.ttsSettingsProto
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGTtsSettingsRepository"

/**
 * Snapshot of TTS settings and resolved engine/voice/language metadata for UI rendering.
 *
 * @property selectedModelId The saved TTS engine, even when it can't be used right now.
 * @property unavailableModelName Display name of the saved TTS model when it can't be used right
 *   now (for example it isn't ready or no engine serves it), so speech uses the Android system
 *   engine instead, or `null` otherwise. The saved choice is kept.
 * @property selectedVoiceId The voice speech uses: the saved one, or the system voice while
 *   [unavailableModelName] is set.
 * @property downloadedModels The TTS models that can be picked now (ready models).
 */
data class TtsSettingsUiState(
  val selectedModelId: String = TtsModelIdentifier.ANDROID_NATIVE,
  val selectedVoiceId: String = TtsSettingsRepository.DEFAULT_SYSTEM_VOICE,
  val selectedLanguageCode: String = "",
  val autoPlayTts: Boolean = false,
  val downloadedModels: List<Model> = emptyList(),
  val availableVoices: List<TtsVoice> = emptyList(),
  val voicesByModelName: Map<String, List<TtsVoice>> = emptyMap(),
  val languagesByModelName: Map<String, List<String>> = emptyMap(),
  val isVoiceListLoading: Boolean = false,
  val unavailableModelName: String? = null,
)

/**
 * Persists user-selected TTS preferences (engine ID, voice ID, language, auto-play toggle, and
 * whether the engine was explicitly selected by the user) in Proto DataStore via
 * [DataStoreRepository], and resolves two-tier voice/language metadata between live
 * [TtsServiceProvider.availableModels] and static allowlist [Model.ttsProfile] definitions.
 *
 * State updates are applied synchronously to the exposed [StateFlow]s and persisted asynchronously
 * on [persistScope].
 */
@Singleton
open class TtsSettingsRepository
internal constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val ttsServiceProviderLazy: Lazy<TtsServiceProvider>?,
  private val persistScope: CoroutineScope,
) {

  /** Returns the active [TtsServiceProvider] if bound in this build, or null otherwise. */
  fun serviceProviderOrNull(): TtsServiceProvider? = ttsServiceProviderLazy?.get()

  /**
   * [TtsServiceProvider] is injected lazily so that injecting this repository (e.g. to read the
   * persisted selection) does not instantiate the TTS engines. It is optional because the engines
   * are only bound in builds that include `services/tts:tts_service`; without them, live engine
   * metadata is unavailable and only allowlist [Model.ttsProfile] data is used.
   */
  @Inject
  constructor(
    dataStoreRepository: DataStoreRepository,
    ttsServiceProviderLazy: Optional<Lazy<TtsServiceProvider>>,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
  ) : this(
    dataStoreRepository = dataStoreRepository,
    ttsServiceProviderLazy = ttsServiceProviderLazy.getOrNull(),
    persistScope = CoroutineScope(SupervisorJob() + ioDispatcher),
  )

  private val ttsServiceProvider: TtsServiceProvider?
    get() = ttsServiceProviderLazy?.get()

  private val liveModels: List<TtsModelMetadata>
    get() = ttsServiceProvider?.availableModels.orEmpty()

  private val persistMutex = Mutex()
  private val warmingLock = Any()
  private val warmingCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()
  private var releaseJob: Job? = null
  private val _warmingEngineIds = MutableStateFlow<Set<String>>(emptySet())
  /** Engine IDs currently undergoing background materialization (`prepare`) to populate voices. */
  val warmingEngineIds: StateFlow<Set<String>> = _warmingEngineIds.asStateFlow()

  // The Settings DataStore is already loaded into memory by `GalleryApplication.onCreate`, so this
  // synchronous read returns the cached value.
  private val initialProto: TtsSettingsProto =
    runCatching { dataStoreRepository.readTtsSettings() }.getOrNull()
      ?: TtsSettingsProto.getDefaultInstance()

  private val initialEngineId: String =
    initialProto.selectedEngineId.ifEmpty { TtsModelIdentifier.ANDROID_NATIVE }

  private val initialVoiceId: String =
    initialProto.selectedVoiceId.ifEmpty { defaultVoiceForEngine(initialEngineId) }

  private val _selectedTtsModelID = MutableStateFlow(initialEngineId)
  /** Currently selected TTS engine identifier (see [TtsModelIdentifier]). */
  val selectedTtsModelID: StateFlow<String> = _selectedTtsModelID.asStateFlow()

  private val _selectedTtsVoice = MutableStateFlow(initialVoiceId)
  /** Currently selected voice identifier for the active TTS engine. */
  val selectedTtsVoice: StateFlow<String> = _selectedTtsVoice.asStateFlow()

  private val _autoPlayTts = MutableStateFlow(initialProto.isAutoPlayEnabled)
  /** Whether assistant responses in AI Chat should automatically be read aloud. */
  val autoPlayTts: StateFlow<Boolean> = _autoPlayTts.asStateFlow()

  private val _isExplicitlySelected = MutableStateFlow(initialProto.isExplicitlySelected)
  /** Whether the user has explicitly chosen a TTS engine rather than relying on defaults. */
  val isExplicitlySelectedFlow: StateFlow<Boolean> = _isExplicitlySelected.asStateFlow()

  private val _selectedLanguageCode = MutableStateFlow(initialProto.selectedLanguageCode)
  /**
   * BCP-47 tag of the language selected for the active engine, or empty to use the engine default.
   * Cleared whenever the active engine changes, because languages are engine-specific.
   */
  val selectedLanguageCode: StateFlow<String> = _selectedLanguageCode.asStateFlow()

  /** Returns whether the user has explicitly selected a TTS engine. */
  fun hasUserExplicitTtsSelection(): Boolean = _isExplicitlySelected.value

  /** Returns whether [engineId] is currently initializing its underlying TTS engine. */
  fun isEngineInitializing(engineId: String): Boolean {
    if (engineId in _warmingEngineIds.value) {
      return true
    }
    return ttsServiceProvider?.serviceOrNull(forModelId = engineId)?.status?.value ==
      TtsServiceStatus.Initializing
  }

  /**
   * Warms [engineId] asynchronously on [persistScope] via [TtsService.prepare] so engines that
   * materialize voices at runtime populate their available speaker IDs without blocking the UI
   * thread.
   */
  fun warmEngine(engineId: String, onVoicesUpdated: () -> Unit = {}) {
    if (TtsModelIdentifier.isSystemEngine(engineId)) {
      return
    }
    val service = ttsServiceProvider?.serviceOrNull(forModelId = engineId) ?: return
    val shouldLaunch =
      synchronized(warmingLock) {
        val callbacks = warmingCallbacks.getOrPut(engineId) { mutableListOf() }
        callbacks.add(onVoicesUpdated)
        if (engineId in _warmingEngineIds.value) {
          false
        } else {
          _warmingEngineIds.update { it + engineId }
          true
        }
      }
    if (!shouldLaunch) {
      return
    }
    onVoicesUpdated()
    persistScope.launch {
      try {
        releaseJob?.join()
        var preparedVoice: String?
        var preparedLanguage: String?
        do {
          preparedVoice = _selectedTtsVoice.value
          preparedLanguage = _selectedLanguageCode.value.ifEmpty { null }
          service.prepare(
            TtsConfig(modelId = engineId, voiceId = preparedVoice, languageCode = preparedLanguage)
          )
        } while (
          _selectedTtsModelID.value == engineId &&
            (_selectedTtsVoice.value != preparedVoice ||
              _selectedLanguageCode.value.ifEmpty { null } != preparedLanguage)
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to warm TTS engine '$engineId'", e)
      } finally {
        val callbacks =
          synchronized(warmingLock) {
            _warmingEngineIds.update { it - engineId }
            warmingCallbacks.remove(engineId).orEmpty()
          }
        for (callback in callbacks) {
          callback()
        }
      }
    }
  }

  private fun scheduleReleaseInactiveEngines() {
    val provider = ttsServiceProvider ?: return
    releaseJob?.cancel()
    releaseJob = persistScope.launch {
      provider.releaseInactiveNeuralEngines(exceptModelId = _selectedTtsModelID.value)
    }
  }

  /**
   * Warms [engineId] via [warmEngine] when it has no selectable voices yet (see
   * [getAvailableVoicesForEngine]), so engines that only list their voices once loaded can offer
   * them. Once warming finishes, revalidates the selected voice for [model] if given.
   *
   * Preloading the engine read-aloud speaks with is not done here: `TtsPlaybackController` prepares
   * the effective engine, whichever it is.
   */
  fun warmEngineIfVoicesMissing(
    engineId: String,
    model: Model? = null,
    supplementaryModels: List<Model> = emptyList(),
    onVoicesUpdated: () -> Unit = {},
  ) {
    val voices = getAvailableVoicesForEngine(engineId, listOfNotNull(model) + supplementaryModels)
    if (voices.isNotEmpty()) {
      return
    }
    warmEngine(engineId) {
      if (model != null) {
        ensureValidVoiceForDownloadedModel(model)
      }
      onVoicesUpdated()
    }
  }

  /**
   * Resolves the selectable voices for [model], preferring live
   * [TtsServiceProvider.availableModels] metadata (e.g. after ML Kit runtime probing) and falling
   * back to [Model.ttsProfile]. Labels and locales come from the engine's
   * [TtsModelMetadata.voiceDescriptions] and [TtsModelMetadata.voiceLocales], else the allowlist
   * voice; a voice without a label is shown by its ID.
   */
  fun resolveVoices(model: Model): List<TtsVoice> {
    val profile = model.ttsProfile
    val engineId = profile?.engineModelId?.ifEmpty { model.name } ?: model.name
    val liveModel = liveModels.firstOrNull {
      it.modelId == engineId || (model.modelId.isNotEmpty() && it.modelId == model.modelId)
    }
    val liveVoices = liveModel?.availableVoices.orEmpty()
    val liveDescriptions = liveModel?.voiceDescriptions.orEmpty()
    val liveLocales = liveModel?.voiceLocales.orEmpty()
    if (liveVoices.isNotEmpty()) {
      val profileVoicesById = profile?.voices.orEmpty().associateBy { it.id }
      return liveVoices.map { voiceId ->
        val displayName =
          liveDescriptions[voiceId]?.takeIf { it.isNotBlank() && it != voiceId }
            ?: profileVoicesById[voiceId]?.displayName?.takeIf { it.isNotBlank() && it != voiceId }
            ?: voiceId
        val locale =
          liveLocales[voiceId]?.takeIf { it.isNotEmpty() }
            ?: profileVoicesById[voiceId]?.locale.orEmpty()
        TtsVoice(id = voiceId, displayName = displayName, locale = locale)
      }
    }
    return profile?.voices.orEmpty().map { voice ->
      voice.copy(displayName = voice.displayName.ifBlank { voice.id })
    }
  }

  /**
   * Resolves the supported BCP-47 language tags for [model], preferring live
   * [TtsServiceProvider.availableModels] metadata, falling back to [Model.ttsProfile], and
   * defaulting to [DEFAULT_TTS_LANGUAGE_CODE] for TTS models without an explicit
   * [Model.ttsProfile].
   */
  fun resolveLanguages(model: Model): List<String> {
    val profile = model.ttsProfile
    val engineId = profile?.engineModelId?.ifEmpty { model.name } ?: model.name
    val liveLanguages =
      liveModels
        .firstOrNull {
          it.modelId == engineId || (model.modelId.isNotEmpty() && it.modelId == model.modelId)
        }
        ?.supportedLanguages
        .orEmpty()
    if (liveLanguages.isNotEmpty()) {
      return liveLanguages
    }
    if (profile != null) {
      return profile.languages
    }
    return if (model.modelType == ModelType.TTS) listOf(DEFAULT_TTS_LANGUAGE_CODE) else emptyList()
  }

  /**
   * Returns the list of selectable [TtsVoice] options for [engineId], consulting matching
   * [supplementaryModels] and [TtsServiceProvider.availableModels].
   */
  fun getAvailableVoicesForEngine(
    engineId: String,
    supplementaryModels: List<Model> = emptyList(),
  ): List<TtsVoice> {
    val matchingModel = supplementaryModels.firstOrNull {
      it.ttsProfile?.engineModelId == engineId || it.name == engineId
    }
    if (matchingModel != null) {
      val resolved = resolveVoices(matchingModel)
      if (resolved.isNotEmpty()) {
        return resolved
      }
    }
    val liveModel = liveModels.firstOrNull { it.modelId == engineId }
    val liveVoices = liveModel?.availableVoices.orEmpty()
    val liveDescriptions = liveModel?.voiceDescriptions.orEmpty()
    val liveLocales = liveModel?.voiceLocales.orEmpty()
    if (liveVoices.isNotEmpty()) {
      return liveVoices.map { voiceId ->
        TtsVoice(
          id = voiceId,
          displayName = liveDescriptions[voiceId]?.takeIf { it.isNotBlank() } ?: voiceId,
          locale = liveLocales[voiceId].orEmpty(),
        )
      }
    }
    return when {
      TtsModelIdentifier.isSystemEngine(engineId) ->
        listOf(TtsVoice(id = DEFAULT_SYSTEM_VOICE, displayName = ""))
      engineId == TtsModelIdentifier.ML_KIT ->
        STAR_VOICE_TO_PERSONA.values.distinct().map { TtsVoice(id = it, displayName = it) }
      else -> emptyList()
    }
  }

  /**
   * Filters [voices] to those matching [languageCode].
   *
   * Excludes [DEFAULT_SYSTEM_VOICE] because the UI prepends `"Default (<language>)"` as the first
   * item. When [languageCode] is blank, returns all non-default voices. Voices without a locale or
   * single-locale snapshots of ML Kit's multi-locale Star personas remain available across
   * languages.
   */
  fun filterVoicesForLanguage(voices: List<TtsVoice>, languageCode: String): List<TtsVoice> =
    Companion.filterVoicesForLanguage(voices, languageCode)

  /**
   * Selects the active TTS engine [id], updating the voice to the engine's default only when the
   * currently selected voice is empty or not supported by the target engine.
   */
  fun setSelectedTtsModelID(
    id: String,
    isExplicitUserSelection: Boolean = true,
    model: Model? = null,
  ) {
    val engineChanged = _selectedTtsModelID.value != id
    _selectedTtsModelID.value = id
    _isExplicitlySelected.value = isExplicitUserSelection
    if (engineChanged) {
      _selectedLanguageCode.value = ""
      ttsServiceProvider?.stopAll()
      scheduleReleaseInactiveEngines()
    }

    val availableVoices =
      if (model != null) {
        resolveVoices(model)
      } else {
        getAvailableVoicesForEngine(id)
      }
    val currentVoice = _selectedTtsVoice.value
    when {
      availableVoices.isEmpty() ->
        if (engineChanged) {
          _selectedTtsVoice.value = defaultVoiceForEngine(id, model)
        }
      currentVoice.isEmpty() || availableVoices.none { it.id == currentVoice } ->
        _selectedTtsVoice.value = pickDefaultVoiceId(id, availableVoices, model)
    }
    persistToDataStore()
  }

  /** Sets the default TTS engine [id] if the user has not explicitly chosen an engine. */
  fun setDefaultTtsModelID(id: String, model: Model? = null) {
    if (!hasUserExplicitTtsSelection()) {
      setSelectedTtsModelID(id, isExplicitUserSelection = false, model = model)
    }
  }

  /** Updates the active voice identifier and stops any in-flight utterance if the voice changed. */
  fun setSelectedTtsVoice(voice: String) {
    if (_selectedTtsVoice.value != voice) {
      ttsServiceProvider?.stopAll()
      _selectedTtsVoice.value = voice
      persistToDataStore()
    }
  }

  /**
   * Selects [languageCode] (a BCP-47 tag, or empty for the engine default) for the active engine,
   * resets the voice if it does not belong to the new language, and stops any in-flight utterance
   * if the language changed.
   */
  fun setSelectedLanguageCode(
    languageCode: String,
    supplementaryModels: List<Model> = emptyList(),
  ) {
    val languageChanged = _selectedLanguageCode.value != languageCode
    _selectedLanguageCode.value = languageCode
    val currentVoice = _selectedTtsVoice.value
    val engineId = _selectedTtsModelID.value
    val allVoices =
      getAvailableVoicesForEngine(engineId, supplementaryModels).filter {
        it.id != DEFAULT_SYSTEM_VOICE && it.id.isNotBlank()
      }
    if (allVoices.isNotEmpty()) {
      val matchingVoices = filterVoicesForLanguage(allVoices, languageCode)
      val currentIsSentinel = currentVoice.isEmpty() || currentVoice == DEFAULT_SYSTEM_VOICE
      val currentVoiceHasMismatchedLocale =
        allVoices.any { it.id == currentVoice } && matchingVoices.none { it.id == currentVoice }
      if (currentIsSentinel || currentVoiceHasMismatchedLocale) {
        val matchingModel = supplementaryModels.firstOrNull {
          it.ttsProfile?.engineModelId == engineId || it.name == engineId
        }
        _selectedTtsVoice.value =
          if (matchingVoices.isNotEmpty() && !TtsModelIdentifier.isSystemEngine(engineId)) {
            pickDefaultVoiceId(engineId, matchingVoices, matchingModel)
          } else {
            DEFAULT_SYSTEM_VOICE
          }
      }
    }
    if (languageChanged || _selectedTtsVoice.value != currentVoice) {
      ttsServiceProvider?.stopAll()
      persistToDataStore()
    }
  }

  /** Updates whether AI Chat responses should automatically be spoken aloud. */
  fun setAutoPlayTts(autoPlay: Boolean) {
    if (_autoPlayTts.value != autoPlay) {
      _autoPlayTts.value = autoPlay
      persistToDataStore()
    }
  }

  /**
   * Called when a TTS supplementary [model] finishes downloading.
   *
   * Selects the newly downloaded engine and initializes its voice only if the current voice is
   * empty or not among the model's resolved voices, preserving any valid voice already chosen by
   * the user.
   */
  fun onModelDownloaded(model: Model) {
    val engineId = model.ttsProfile?.engineModelId ?: return
    val engineChanged = _selectedTtsModelID.value != engineId
    _selectedTtsModelID.value = engineId
    _isExplicitlySelected.value = true
    if (engineChanged) {
      _selectedLanguageCode.value = ""
      ttsServiceProvider?.stopAll()
      scheduleReleaseInactiveEngines()
    }
    ensureValidVoiceForDownloadedModel(model)
    persistToDataStore()
  }

  /**
   * Ensures that if [model] is currently selected and has resolved voices, [_selectedTtsVoice] is
   * set to a valid voice without overwriting an existing valid user selection.
   */
  fun ensureValidVoiceForDownloadedModel(model: Model) {
    val engineId =
      model.ttsProfile?.engineModelId?.ifEmpty { null }
        ?: model.name.takeIf { model.modelType == ModelType.TTS }
        ?: return
    if (_selectedTtsModelID.value != engineId) {
      return
    }
    val voices = resolveVoices(model)
    if (voices.isEmpty()) {
      return
    }
    val currentVoice = _selectedTtsVoice.value
    if (currentVoice.isEmpty() || voices.none { it.id == currentVoice }) {
      _selectedTtsVoice.value = pickDefaultVoiceId(engineId, voices, model)
      persistToDataStore()
    }
  }

  /**
   * Called when a TTS supplementary [model] is deleted.
   *
   * If the deleted model was the active TTS engine, falls back to
   * [TtsModelIdentifier.ANDROID_NATIVE] with `isExplicitUserSelection = false`.
   */
  fun onModelDeleted(model: Model) {
    val engineId =
      model.ttsProfile?.engineModelId?.ifEmpty { null }
        ?: model.name.takeIf { model.modelType == ModelType.TTS }
        ?: return
    if (_selectedTtsModelID.value == engineId || _selectedTtsModelID.value == model.name) {
      setSelectedTtsModelID(TtsModelIdentifier.ANDROID_NATIVE, isExplicitUserSelection = false)
    } else {
      scheduleReleaseInactiveEngines()
    }
  }

  internal fun pickDefaultVoiceId(
    engineId: String,
    availableVoices: List<TtsVoice>,
    model: Model? = null,
  ): String {
    val profileDefault = model?.ttsProfile?.defaultVoiceId
    if (!profileDefault.isNullOrEmpty() && availableVoices.any { it.id == profileDefault }) {
      return profileDefault
    }
    return when {
      engineId == TtsModelIdentifier.ML_KIT ->
        availableVoices.firstOrNull { it.id == DEFAULT_MLKIT_VOICE }?.id
          ?: availableVoices.first().id
      engineId == TtsModelIdentifier.MEDIAPIPE_KOKORO_82M ->
        availableVoices.firstOrNull { it.id == DEFAULT_KOKORO_VOICE }?.id
          ?: availableVoices.first().id
      engineId == TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M ->
        availableVoices.firstOrNull { it.id == DEFAULT_POCKET_TTS_VOICE }?.id
          ?: availableVoices.first().id
      engineId == TtsModelIdentifier.MEDIAPIPE_PHONON_31M ->
        availableVoices.firstOrNull { it.id == DEFAULT_PHONON_VOICE }?.id
          ?: availableVoices.first().id
      TtsModelIdentifier.isSystemEngine(engineId) -> DEFAULT_SYSTEM_VOICE
      else -> availableVoices.first().id
    }
  }

  internal fun defaultVoiceForEngine(engineId: String, model: Model? = null): String {
    val profileDefault = model?.ttsProfile?.defaultVoiceId?.takeIf { it.isNotEmpty() }
    if (profileDefault != null) {
      return profileDefault
    }
    return when (engineId) {
      TtsModelIdentifier.ML_KIT -> DEFAULT_MLKIT_VOICE
      TtsModelIdentifier.MEDIAPIPE_KOKORO_82M -> DEFAULT_KOKORO_VOICE
      TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M -> DEFAULT_POCKET_TTS_VOICE
      TtsModelIdentifier.MEDIAPIPE_PHONON_31M -> DEFAULT_PHONON_VOICE
      else -> DEFAULT_SYSTEM_VOICE
    }
  }

  /**
   * Persists the current settings off the calling thread. Each write snapshots the latest state
   * while holding [persistMutex], so the last write always stores the most recent values.
   */
  private fun persistToDataStore() {
    persistScope.launch {
      try {
        persistMutex.withLock {
          val settings = ttsSettingsProto {
            selectedEngineId = _selectedTtsModelID.value
            selectedVoiceId = _selectedTtsVoice.value
            isAutoPlayEnabled = _autoPlayTts.value
            isExplicitlySelected = _isExplicitlySelected.value
            selectedLanguageCode = _selectedLanguageCode.value
          }
          dataStoreRepository.saveTtsSettings(settings)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A failed write must not crash the app; the in-memory state stays authoritative.
        Log.e(TAG, "Failed to persist TTS settings to DataStore", e)
      }
    }
  }

  companion object {
    /**
     * Internal sentinel key persisted in DataStore for the system default voice. User-facing
     * display labels are resolved at the UI layer via `R.string.default_voice_label`.
     */
    const val DEFAULT_SYSTEM_VOICE = "System Default"
    const val DEFAULT_MLKIT_VOICE = "Puck"
    const val DEFAULT_KOKORO_VOICE = "af_heart"
    const val DEFAULT_POCKET_TTS_VOICE = "alba"
    const val DEFAULT_PHONON_VOICE = "freya"

    /**
     * Returns whether [voiceLocale] matches [languageCode] (exact case-insensitive BCP-47 match, or
     * primary language subtag match when one tag omits a region).
     */
    fun localesMatch(voiceLocale: String, languageCode: String): Boolean {
      val normVoice = voiceLocale.trim().replace('_', '-')
      val normLang = languageCode.trim().replace('_', '-')
      if (normVoice.isEmpty() || normLang.isEmpty()) return false
      if (normVoice.equals(normLang, ignoreCase = true)) return true
      if ('-' !in normVoice || '-' !in normLang) {
        return normVoice
          .substringBefore('-')
          .equals(normLang.substringBefore('-'), ignoreCase = true)
      }
      return false
    }

    /** Filters [voices] to those matching [languageCode], excluding [DEFAULT_SYSTEM_VOICE]. */
    fun filterVoicesForLanguage(voices: List<TtsVoice>, languageCode: String): List<TtsVoice> {
      val nonDefault = voices.filter { it.id != DEFAULT_SYSTEM_VOICE && it.id.isNotBlank() }
      if (languageCode.isBlank()) {
        return nonDefault
      }
      val allStarPersonasFromSingleLocale =
        nonDefault.isNotEmpty() &&
          nonDefault.all { it.id in STAR_VOICE_TO_PERSONA.values } &&
          nonDefault.map { it.locale }.distinct().size == 1
      if (allStarPersonasFromSingleLocale) {
        return nonDefault
      }
      return nonDefault.filter { voice ->
        voice.locale.isBlank() || localesMatch(voice.locale, languageCode)
      }
    }
  }
}
