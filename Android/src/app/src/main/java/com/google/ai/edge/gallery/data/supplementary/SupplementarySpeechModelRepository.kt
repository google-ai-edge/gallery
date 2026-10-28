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

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.di.ApplicationScope
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.TtsEngineType
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.errorprone.annotations.CanIgnoreReturnValue
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSupplementarySpeechModelRepository"

/**
 * Central repository managing availability, gating, and language pack states for supplementary
 * speech models (TTS and ASR). It also tracks the other supplementary models, such as segmentation
 * models, which have no gate, voices, or languages, and are ready once their file is downloaded.
 *
 * Runs all probing off the main thread on [ioDispatcher], deduplicates concurrent queries, caches
 * definite answers with a TTL, and emits batched updates to avoid main-thread lag.
 */
@Singleton
class SupplementarySpeechModelRepository
@VisibleForTesting
internal constructor(
  @ApplicationContext private val context: Context,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
  private val gates: Map<String, @JvmSuppressWildcards SpeechModelGate>,
  private val ttsServiceProvider: TtsServiceProvider,
  private val asrServiceProvider: AsrServiceProvider,
  private val repositoryScope: CoroutineScope,
  private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
  @Inject
  constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    gates: Map<String, @JvmSuppressWildcards SpeechModelGate>,
    ttsServiceProvider: TtsServiceProvider,
    asrServiceProvider: AsrServiceProvider,
    @ApplicationScope repositoryScope: CoroutineScope,
  ) : this(
    context = context,
    ioDispatcher = ioDispatcher,
    gates = gates,
    ttsServiceProvider = ttsServiceProvider,
    asrServiceProvider = asrServiceProvider,
    repositoryScope = repositoryScope,
    currentTimeMillis = { System.currentTimeMillis() },
  )

  private val _models = MutableStateFlow<List<Model>>(emptyList())

  /** Supplementary models registered by [registerModels], in allowlist order. */
  val models: StateFlow<List<Model>> = _models.asStateFlow()

  private val _modelStates = MutableStateFlow<Map<String, SpeechModelState>>(emptyMap())
  val modelStates: StateFlow<Map<String, SpeechModelState>> = _modelStates.asStateFlow()

  private val _languageStates =
    MutableStateFlow<Map<String, Map<String, LanguageState>>>(emptyMap())
  val languageStates: StateFlow<Map<String, Map<String, LanguageState>>> =
    _languageStates.asStateFlow()

  private val modelCache = ConcurrentHashMap<String, CachedState<SpeechModelState>>()
  private val languageCache = ConcurrentHashMap<Pair<String, String>, CachedState<LanguageState>>()

  private val inFlightModelQueries = ConcurrentHashMap<String, Deferred<SpeechModelState>>()
  private val inFlightLanguageQueries =
    ConcurrentHashMap<Pair<String, String>, Deferred<LanguageState>>()

  private val knownModels = ConcurrentHashMap<String, Model>()

  /**
   * Runtime failures reported by [onEngineError], by model name. They replace the gate's answer
   * until the user retries the model ([retryModel]), one of its downloads completes, Speech
   * Services is updated, or [clearTransientEngineError] clears them. Transient failures also expire
   * after [CACHE_TTL_MS]; see [expireTransientEngineError].
   */
  private val engineErrors = ConcurrentHashMap<String, EngineErrorRecord>()

  /** Evaluates pre-download gating conditions for [model]. */
  @CanIgnoreReturnValue
  suspend fun evaluateModel(model: Model, forceRefresh: Boolean = false): SpeechModelState =
    withContext(ioDispatcher) {
      knownModels[model.name] = model

      // An expired transient failure is checked again from scratch, without the cached answer.
      val errorExpired = expireTransientEngineError(model.name)
      when {
        forceRefresh -> {
          modelCache.remove(model.name)
          inFlightModelQueries.remove(model.name)?.cancel()
        }
        errorExpired -> modelCache.remove(model.name)
        else -> {
          val cached = modelCache[model.name]
          if (cached != null && cached.isValid(currentTimeMillis()) && cached.state.isDefinite()) {
            return@withContext engineErrors[model.name]?.error ?: cached.state
          }
        }
      }

      val deferred =
        inFlightModelQueries.computeIfAbsent(model.name) {
          repositoryScope.async {
            val currentJob = coroutineContext[Job]
            try {
              val gate = resolveGate(model)
              val state =
                if (gate != null) {
                  gate.checkGate(model)
                } else {
                  evaluateFallbackModel(model)
                }

              ensureActive()
              val finalState = resolveWithLanguages(model, state)
              if (finalState.isDefinite()) {
                modelCache[model.name] = CachedState(finalState, currentTimeMillis())
              } else {
                modelCache.remove(model.name)
              }
              var publishedState = finalState
              _modelStates.update { current ->
                val state = engineErrors[model.name]?.error ?: finalState
                publishedState = state
                current + (model.name to state)
              }
              publishedState
            } finally {
              if (currentJob != null) {
                inFlightModelQueries.remove(model.name, currentJob)
              } else {
                inFlightModelQueries.remove(model.name)
              }
            }
          }
        }

      deferred.await()
    }

  /** Refreshes availability status for a list of [languageCodes] for [model] in a single batch. */
  @CanIgnoreReturnValue
  suspend fun refreshLanguagePacks(
    model: Model,
    languageCodes: List<String>,
    forceRefresh: Boolean = false,
  ): Map<String, LanguageState> =
    withContext(ioDispatcher) {
      knownModels[model.name] = model

      val batchResults = mutableMapOf<String, LanguageState>()
      val pendingAwait = mutableListOf<Pair<String, Deferred<LanguageState>>>()

      for (code in languageCodes) {
        val key = model.name to code
        if (forceRefresh) {
          languageCache.remove(key)
          inFlightLanguageQueries.remove(key)?.cancel()
        } else {
          val cached = languageCache[key]
          if (cached != null && cached.isValid(currentTimeMillis()) && cached.state.isDefinite()) {
            batchResults[code] = cached.state
            continue
          }
        }

        val deferred =
          inFlightLanguageQueries.computeIfAbsent(key) {
            repositoryScope.async {
              val currentJob = coroutineContext[Job]
              try {
                val state = probeLanguageAvailability(model, code)
                ensureActive()
                if (state.isDefinite()) {
                  languageCache[key] = CachedState(state, currentTimeMillis())
                } else {
                  languageCache.remove(key)
                }
                state
              } finally {
                if (currentJob != null) {
                  inFlightLanguageQueries.remove(key, currentJob)
                } else {
                  inFlightLanguageQueries.remove(key)
                }
              }
            }
          }
        pendingAwait.add(code to deferred)
      }

      if (pendingAwait.isNotEmpty()) {
        val awaited = pendingAwait.map { it.second }.awaitAll()
        pendingAwait.forEachIndexed { index, (code, _) -> batchResults[code] = awaited[index] }
      }

      // Single emission per batch for language states. A query that started before a download must
      // not replace the download's progress with a stale answer.
      _languageStates.update { current ->
        val existing = current[model.name].orEmpty()
        val fresh = batchResults.filter { (code, state) ->
          !isStaleDuringDownload(existing[code], state)
        }
        current + (model.name to (existing + fresh))
      }

      // Re-evaluate model state to reflect newly discovered installed languages.
      val currentState = _modelStates.value[model.name]
      if (currentState != null) {
        val updatedModelState = resolveWithLanguages(model, currentState)
        if (updatedModelState != currentState) {
          modelCache[model.name] = CachedState(updatedModelState, currentTimeMillis())
          // A runtime failure reported meanwhile keeps showing until it is cleared.
          _modelStates.update { current ->
            if (engineErrors.containsKey(model.name)) {
              current
            } else {
              current + (model.name to updatedModelState)
            }
          }
        }
      }

      batchResults
    }

  /** Probes a single language pack availability on IO. */
  private suspend fun probeLanguageAvailability(model: Model, languageCode: String): LanguageState {
    return try {
      val probe: SpeechAvailabilityProbe? =
        when (model.modelType) {
          ModelType.TTS ->
            resolveTtsService(model) ?: ttsServiceProvider.serviceOrNull(TtsEngineType.ML_KIT)
          ModelType.ASR -> {
            val engineType = AsrModelRegistry.resolveEngineTypeForModel(model)
            asrServiceProvider.serviceOrNull(engineType)
          }
          else -> null
        }

      if (probe == null) {
        return LanguageState.CheckFailed(languageCode, "No engine in build")
      }

      when (val availability = probe.checkLanguageAvailability(model.name, languageCode)) {
        is SpeechAvailability.Available -> LanguageState.Installed(languageCode)
        is SpeechAvailability.NeedsDownload ->
          LanguageState.Downloadable(languageCode, availability.totalBytes)
        is SpeechAvailability.Downloading ->
          LanguageState.Downloading(
            languageCode,
            DownloadProgress(
              bytesDownloaded = availability.receivedBytes,
              totalBytes = availability.totalBytes,
            ),
            // A TTS voice download that the app runs reports its progress through
            // onLanguageDownloadProgress(), which this answer doesn't replace (see
            // isStaleDuringDownload()). So when this answer is shown, no download in the app is
            // receiving the voice's progress, and the user can request it again.
            stalled = model.modelType == ModelType.TTS,
          )
        is SpeechAvailability.Unavailable -> {
          when (availability.reason) {
            SpeechUnavailabilityReason.LOCALE_NOT_OFFERED,
            SpeechUnavailabilityReason.DEVICE_NOT_SERVED -> LanguageState.NotOffered(languageCode)
            SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED ->
              LanguageState.CheckFailed(languageCode, "Speech Services outdated")
            else -> LanguageState.CheckFailed(languageCode, "Language unavailable")
          }
        }
        is SpeechAvailability.ProbeFailed ->
          LanguageState.CheckFailed(
            languageCode,
            availability.cause.message ?: "Language check failed",
          )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Error probing language '$languageCode' for model '${model.name}'", e)
      LanguageState.CheckFailed(languageCode, e.message ?: "Check failed")
    }
  }

  /** Resolves model state when language packs are needed. */
  private fun resolveWithLanguages(model: Model, state: SpeechModelState): SpeechModelState {
    if (state is SpeechModelState.Ready || state is SpeechModelState.NeedsLanguagePack) {
      val modelLanguages = _languageStates.value[model.name].orEmpty()
      if (modelLanguages.isEmpty()) {
        return state
      }
      val languagesList = modelLanguages.values.toList()
      val hasInstalledLanguage = modelLanguages.values.any { it is LanguageState.Installed }

      return if (hasInstalledLanguage) {
        val voices = extractVoicesForModel(model)
        SpeechModelState.Ready(languages = languagesList, voices = voices)
      } else {
        SpeechModelState.NeedsLanguagePack(languages = languagesList)
      }
    }
    return state
  }

  private fun resolveGate(model: Model): SpeechModelGate? {
    val canonicalKey =
      when (model.modelType) {
        ModelType.TTS ->
          model.ttsProfile?.engineModelId?.takeIf { it.isNotEmpty() }
            ?: model.modelId.takeIf { it.isNotEmpty() }
            ?: if (model.backendSpec.runtimeType == RuntimeType.MLKIT_SPEECH) {
              TtsModelIdentifier.ML_KIT
            } else {
              null
            }
        ModelType.ASR ->
          model.modelId.takeIf { it.isNotEmpty() }
            ?: AsrModelRegistry.resolveEngineType(model.name)?.let(AsrModelRegistry::modelId)
            ?: when (model.name) {
              AsrModelRegistry.MLKIT_BASIC_MODEL_NAME -> AsrModelIdentifier.MLKIT_BASIC
              AsrModelRegistry.MLKIT_ADVANCED_MODEL_NAME -> AsrModelIdentifier.MLKIT_ADVANCED
              else -> null
            }
        else -> model.modelId.takeIf { it.isNotEmpty() }
      }
    if (canonicalKey != null) {
      gates[canonicalKey]?.let {
        return it
      }
    }
    return gates[model.name]
  }

  private fun resolveTtsService(model: Model): TtsService? =
    model.ttsProfile
      ?.engineModelId
      ?.takeIf { it.isNotEmpty() }
      ?.let { ttsServiceProvider.serviceOrNull(forModelId = it) }
      ?: model.modelId
        .takeIf { it.isNotEmpty() }
        ?.let { ttsServiceProvider.serviceOrNull(forModelId = it) }
      ?: ttsServiceProvider.serviceOrNull(forModelId = model.name)
      ?: when {
        model.backendSpec.runtimeType == RuntimeType.MLKIT_SPEECH ->
          ttsServiceProvider.serviceOrNull(TtsEngineType.ML_KIT)
        isSystemSpeechModel(model) -> ttsServiceProvider.serviceOrNull(TtsEngineType.ANDROID_NATIVE)
        else -> null
      }

  private fun isSystemSpeechModel(model: Model): Boolean {
    val candidateId =
      model.ttsProfile?.engineModelId?.takeIf { it.isNotEmpty() }
        ?: model.modelId.takeIf { it.isNotEmpty() }
        ?: model.name
    return TtsModelIdentifier.isSystemEngine(candidateId) ||
      candidateId == AsrModelIdentifier.SYSTEM_DEFAULT ||
      AsrModelRegistry.resolveEngineType(candidateId) == AsrEngineType.SYSTEM_DEFAULT ||
      (model.modelType == ModelType.TTS &&
        model.backendSpec.runtimeType == RuntimeType.UNKNOWN &&
        model.downloadInfo.url.isEmpty())
  }

  private fun extractVoicesForModel(model: Model): VoiceList {
    if (model.modelType == ModelType.TTS) {
      val availableModels = resolveTtsService(model)?.availableModels.orEmpty()
      val engineId = model.ttsEngineId
      val metadata =
        availableModels.firstOrNull {
          it.modelId == engineId ||
            it.modelId == model.name ||
            (model.modelId.isNotEmpty() && it.modelId == model.modelId)
        } ?: availableModels.firstOrNull()
      val voices = metadata?.availableVoices
      if (!voices.isNullOrEmpty()) {
        return VoiceList.Loaded(voices = voices, voiceLocales = metadata.voiceLocales)
      }
    }
    return VoiceList.Empty
  }

  /** Fallback evaluation for models without a dedicated gate. */
  private fun evaluateFallbackModel(model: Model): SpeechModelState {
    if (isSystemSpeechModel(model)) {
      val voices = extractVoicesForModel(model)
      return SpeechModelState.Ready(voices = voices)
    }

    // A TTS model that no engine in this build speaks can never be used, so it is never listed.
    if (model.modelType == ModelType.TTS) {
      val engineId = model.ttsEngineId
      if (ttsServiceProvider.serviceOrNull(forModelId = engineId) == null) {
        Log.w(TAG, "No TTS engine in this build serves '${model.name}' ('$engineId'); hiding it")
        return SpeechModelState.Hidden(HiddenReason.NO_ENGINE_IN_BUILD)
      }
    }

    // File-backed LiteRT models.
    val localFile = File(model.getPath(context))
    return if (localFile.exists() && localFile.length() > 0L) {
      SpeechModelState.Ready(voices = extractVoicesForModel(model))
    } else {
      SpeechModelState.Downloadable(totalBytes = model.downloadInfo.totalBytes.takeIf { it > 0L })
    }
  }

  /** Re-evaluates states on application resume when cached TTL expired. */
  fun refreshOnResume() {
    repositoryScope.launch {
      val now = currentTimeMillis()
      for ((_, model) in knownModels) {
        val cached = modelCache[model.name]
        if (cached == null || !cached.isValid(now) || expireTransientEngineError(model.name)) {
          evaluateModel(model, forceRefresh = true)
        }
      }
    }
  }

  /** Clears cache and re-evaluates all speech models when Speech Services is updated. */
  fun onSpeechServicesUpdated() {
    Log.i(TAG, "Speech Services package updated; invalidating cache")
    inFlightModelQueries.values.forEach { it.cancel() }
    inFlightModelQueries.clear()
    inFlightLanguageQueries.values.forEach { it.cancel() }
    inFlightLanguageQueries.clear()
    modelCache.clear()
    languageCache.clear()
    val previousLanguages = _languageStates.value.mapValues { it.value.keys.toList() }
    _languageStates.value = emptyMap()
    // Updating Speech Services restarts the engines it runs, so earlier runtime failures are stale.
    engineErrors.clear()
    repositoryScope.launch {
      for ((_, model) in knownModels) {
        evaluateModel(model, forceRefresh = true)
        val trackedLocales = previousLanguages[model.name]
        if (!trackedLocales.isNullOrEmpty()) {
          refreshLanguagePacks(model, trackedLocales, forceRefresh = true)
        }
      }
    }
  }

  /** Notifies repository that a model asset was downloaded. */
  fun onModelDownloadCompleted(modelName: String) {
    modelCache.remove(modelName)
    inFlightModelQueries.remove(modelName)?.cancel()
    // New assets replace whatever failed before.
    engineErrors.remove(modelName)
    val model = knownModels[modelName] ?: return
    repositoryScope.launch { evaluateModel(model, forceRefresh = true) }
  }

  /** Notifies repository that a language pack was downloaded. */
  fun onLanguageDownloadCompleted(modelName: String, languageCode: String) {
    val key = modelName to languageCode
    inFlightLanguageQueries.remove(key)?.cancel()
    languageCache[key] = CachedState(LanguageState.Installed(languageCode), currentTimeMillis())
    _languageStates.update { current ->
      val existing = current[modelName].orEmpty()
      current + (modelName to (existing + (languageCode to LanguageState.Installed(languageCode))))
    }
    modelCache.remove(modelName)
    // A newly installed voice may be what the engine failed on.
    engineErrors.remove(modelName)
    val model = knownModels[modelName] ?: return
    repositoryScope.launch { evaluateModel(model, forceRefresh = true) }
  }

  /**
   * Registers the supplementary [models] loaded from the allowlist, in allowlist order, and
   * pre-warms their availability and language pack states in the background so that screens read
   * them from the cache instead of waiting on Speech Services.
   */
  fun registerModels(models: List<Model>) {
    // Forget models that left the allowlist, so resume and update refreshes skip them.
    val names = models.map { it.name }.toSet()
    knownModels.keys.retainAll(names)
    modelCache.keys.retainAll(names)
    languageCache.keys.retainAll { (modelName, _) -> modelName in names }
    _modelStates.update { it.filterKeys { name -> name in names } }
    _languageStates.update { it.filterKeys { name -> name in names } }
    for (model in models) {
      knownModels[model.name] = model
    }
    _models.value = models.toList()
    prewarm(models)
  }

  /**
   * Evaluates [models] and probes the language packs of those that install languages separately, in
   * the background. Cached answers are reused, so calling this again is cheap.
   */
  fun prewarm(models: List<Model> = _models.value) {
    for (model in models) {
      repositoryScope.launch { prewarmModel(model) }
    }
  }

  private suspend fun prewarmModel(model: Model) {
    try {
      val state = evaluateModel(model)
      if (state.showsLanguagePacks() && installsLanguagePacksSeparately(model)) {
        refreshLanguagePacks(model, supportedLanguageCodes(model))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to pre-warm supplementary model '${model.name}'", e)
    }
  }

  /**
   * Re-checks [model] from scratch, ignoring cached answers, after the user taps Retry. The model
   * shows [SpeechModelState.Checking] until the new answer arrives.
   */
  fun retryModel(model: Model) {
    knownModels[model.name] = model
    modelCache.remove(model.name)
    engineErrors.remove(model.name)
    _modelStates.update { current -> current + (model.name to SpeechModelState.Checking) }
    repositoryScope.launch {
      try {
        val state = evaluateModel(model, forceRefresh = true)
        if (state.showsLanguagePacks() && installsLanguagePacksSeparately(model)) {
          refreshLanguagePacks(model, supportedLanguageCodes(model), forceRefresh = true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to re-check supplementary model '${model.name}'", e)
        _modelStates.update { current ->
          current + (model.name to SpeechModelState.CheckFailed(e.message ?: "Check failed"))
        }
      }
    }
  }

  /**
   * Re-checks the [languageCode] pack of [model], ignoring cached answers, after the user taps
   * Retry. The language shows [LanguageState.Checking] until the new answer arrives.
   */
  fun retryLanguage(model: Model, languageCode: String) {
    knownModels[model.name] = model
    languageCache.remove(model.name to languageCode)
    _languageStates.update { current ->
      val existing = current[model.name].orEmpty()
      current + (model.name to (existing + (languageCode to LanguageState.Checking(languageCode))))
    }
    repositoryScope.launch {
      try {
        refreshLanguagePacks(model, listOf(languageCode), forceRefresh = true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to re-check language '$languageCode' for '${model.name}'", e)
      }
    }
  }

  /**
   * Returns the BCP-47 tags of the languages [model] supports, from a fixed source: the allowlist
   * for TTS models and the engine's supported locales for ASR models, excluding Android testing
   * pseudo-locales (`-XA`, `-XB`, `-XC` such as `ar-XA`).
   */
  fun supportedLanguageCodes(model: Model): List<String> =
    when (model.modelType) {
      ModelType.TTS ->
        model.ttsProfile?.languages?.filterNot(::isPseudoLocaleTag)?.ifEmpty { null }
          ?: listOf(DEFAULT_TTS_LANGUAGE_CODE)
      ModelType.ASR -> AsrModelRegistry.supportedLanguages(model).filterNot(::isPseudoLocaleTag)
      else -> emptyList()
    }

  /**
   * Whether [model] installs each language as a separate pack that is probed and downloaded on its
   * own. When `false`, all of its languages come with the model itself.
   */
  fun installsLanguagePacksSeparately(model: Model): Boolean =
    when (model.modelType) {
      ModelType.TTS -> model.backendSpec.runtimeType.installsLanguagePacks
      ModelType.ASR -> AsrModelRegistry.installsLanguagePacks(model.modelId)
      else -> false
    }

  /**
   * Publishes a progress update for an in-flight download of [modelName]'s model asset. The
   * progress is never cached; [onModelDownloadCompleted] or [invalidateModel] replaces it with a
   * fresh answer when the download ends.
   */
  fun onModelDownloadProgress(modelName: String, state: SpeechModelState.Downloading) {
    modelCache.remove(modelName)
    _modelStates.update { current -> current + (modelName to state) }
  }

  /**
   * Shows [modelName]'s model download as [state] after Speech Services queued it to finish later
   * instead of failing it. Like progress, this is never cached: the next check replaces it.
   */
  fun onModelDownloadQueued(modelName: String, state: SpeechModelState.Queued) {
    modelCache.remove(modelName)
    _modelStates.update { current -> current + (modelName to state) }
  }

  /**
   * Records that [modelName]'s engine failed at runtime for [reason]. The model shows
   * [SpeechModelState.EngineError], which isn't selectable, until the user retries it
   * ([retryModel]) or Speech Services is updated. A transient failure also ends after
   * [CACHE_TTL_MS] or when a screen that reads aloud resumes ([clearTransientEngineError]).
   */
  fun onEngineError(modelName: String, reason: EngineErrorReason, recoverable: Boolean) {
    Log.w(TAG, "Engine of '$modelName' failed at runtime: $reason (recoverable=$recoverable)")
    val error = SpeechModelState.EngineError(reason, recoverable)
    val record = EngineErrorRecord(error, recordedAtMs = currentTimeMillis())
    engineErrors[modelName] = record
    _modelStates.update { current ->
      if (engineErrors[modelName] == record) current + (modelName to error) else current
    }
  }

  /**
   * Clears the transient runtime failure ([isTransient]) recorded for [modelName] by
   * [onEngineError], so the model leaves [SpeechModelState.EngineError] and the next playback tries
   * its engine again. Called when a screen that reads aloud resumes. The model shows its last
   * checked state again if that was [SpeechModelState.Ready] and hasn't expired, or is checked
   * again otherwise. Does nothing if no transient failure is recorded.
   */
  fun clearTransientEngineError(modelName: String) {
    val record = engineErrors[modelName]?.takeIf { it.error.reason.isTransient() } ?: return
    if (!engineErrors.remove(modelName, record)) return
    Log.i(TAG, "Clearing the runtime failure of '$modelName': ${record.error.reason}")
    if (leaveEngineError(modelName, record.error) == SpeechModelState.Checking) {
      modelCache.remove(modelName)
      val model = knownModels[modelName] ?: return
      repositoryScope.launch {
        try {
          evaluateModel(model)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to re-check supplementary model '$modelName'", e)
          _modelStates.update { current ->
            if (current[modelName] == SpeechModelState.Checking) {
              current + (modelName to SpeechModelState.CheckFailed(e.message ?: "Check failed"))
            } else {
              current
            }
          }
        }
      }
    }
  }

  /**
   * Removes the transient runtime failure recorded for [modelName] once it is older than
   * [CACHE_TTL_MS]. Returns whether a failure expired; the caller then checks the model again.
   */
  private fun expireTransientEngineError(modelName: String): Boolean {
    val record = engineErrors[modelName] ?: return false
    if (!record.error.reason.isTransient()) return false
    if (currentTimeMillis() - record.recordedAtMs < CACHE_TTL_MS) return false
    if (!engineErrors.remove(modelName, record)) return false
    Log.i(TAG, "The runtime failure of '$modelName' expired: ${record.error.reason}")
    modelCache.remove(modelName)
    val unused = leaveEngineError(modelName, record.error)
    return true
  }

  /**
   * Replaces [error] in [modelStates] after it was removed from [engineErrors]: with the cached
   * answer when that is a valid [SpeechModelState.Ready], otherwise with
   * [SpeechModelState.Checking]. Leaves the state alone if it is no longer [error] or a newer
   * failure was recorded meanwhile. Returns the state that replaced [error], or `null` if none did.
   */
  private fun leaveEngineError(
    modelName: String,
    error: SpeechModelState.EngineError,
  ): SpeechModelState? {
    val replacement =
      modelCache[modelName]
        ?.takeIf { it.isValid(currentTimeMillis()) }
        ?.state
        ?.takeIf { it is SpeechModelState.Ready } ?: SpeechModelState.Checking
    var left = false
    _modelStates.update { current ->
      left = current[modelName] == error && !engineErrors.containsKey(modelName)
      if (left) current + (modelName to replacement) else current
    }
    return replacement.takeIf { left }
  }

  /**
   * Publishes a progress update for an in-flight download of [modelName]'s [LanguageState.code]
   * pack. Only an [LanguageState.Installed] result is cached.
   */
  fun onLanguageDownloadProgress(modelName: String, state: LanguageState) {
    if (state is LanguageState.Installed) {
      onLanguageDownloadCompleted(modelName, state.code)
      return
    }
    languageCache.remove(modelName to state.code)
    _languageStates.update { current ->
      val existing = current[modelName].orEmpty()
      current + (modelName to (existing + (state.code to state)))
    }
  }

  /**
   * Drops the cached state of [modelName] and re-evaluates it, for example after its download
   * failed or its files were deleted.
   */
  fun invalidateModel(modelName: String) {
    modelCache.remove(modelName)
    val model = knownModels[modelName] ?: return
    repositoryScope.launch {
      try {
        evaluateModel(model, forceRefresh = true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to re-evaluate supplementary model '$modelName'", e)
      }
    }
  }

  fun getModelState(modelName: String): SpeechModelState? =
    _modelStates.value[modelName] ?: modelCache[modelName]?.state

  fun getLanguageState(modelName: String, languageCode: String): LanguageState? =
    _languageStates.value[modelName]?.get(languageCode)
      ?: languageCache[modelName to languageCode]?.state

  private data class CachedState<T>(val state: T, val timestamp: Long) {
    fun isValid(nowMs: Long, ttl: Long = CACHE_TTL_MS): Boolean = (nowMs - timestamp) < ttl
  }

  /** A runtime failure reported by [onEngineError] and when it was reported. */
  private data class EngineErrorRecord(
    val error: SpeechModelState.EngineError,
    val recordedAtMs: Long,
  )

  companion object {
    /**
     * How long a definite answer stays cached, and how long a transient runtime failure
     * ([isTransient]) keeps a model unusable before it is checked again.
     */
    const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
  }
}

/** Whether a runtime failure for this reason is transient and worth retrying later. */
private fun EngineErrorReason.isTransient(): Boolean =
  when (this) {
    EngineErrorReason.NOT_INSTALLED_AT_RUNTIME,
    EngineErrorReason.SYNTHESIS_FAILED -> true
    EngineErrorReason.INITIALIZATION_FAILED,
    EngineErrorReason.RECOGNITION_FAILED,
    EngineErrorReason.SERVICE_DISCONNECTED -> false
  }

private fun SpeechModelState.isDefinite(): Boolean =
  this !is SpeechModelState.CheckFailed && this !is SpeechModelState.Checking

private fun LanguageState.isDefinite(): Boolean =
  this !is LanguageState.CheckFailed && this !is LanguageState.Checking

/** Whether a model in this state lists its languages, so their packs are worth probing. */
private fun SpeechModelState.showsLanguagePacks(): Boolean =
  this is SpeechModelState.Ready ||
    this is SpeechModelState.NeedsLanguagePack ||
    this is SpeechModelState.Downloading ||
    this is SpeechModelState.Queued

/**
 * Whether [queried], from a status query, is older news than [current], a download in progress:
 * only a query that reports the pack installed or downloading may replace the download's progress,
 * and a stalled download from a query never replaces the progress of a download the app runs.
 */
private fun isStaleDuringDownload(current: LanguageState?, queried: LanguageState): Boolean =
  current is LanguageState.Downloading &&
    queried !is LanguageState.Installed &&
    (queried !is LanguageState.Downloading || (queried.stalled && !current.stalled))

private fun isPseudoLocaleTag(languageCode: String): Boolean {
  val normalized = languageCode.trim().replace('_', '-').uppercase()
  return normalized.endsWith("-XA") || normalized.endsWith("-XB") || normalized.endsWith("-XC")
}
