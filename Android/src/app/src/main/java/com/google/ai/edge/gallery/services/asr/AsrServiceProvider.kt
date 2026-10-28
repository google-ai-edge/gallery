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

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGAsrServiceProvider"

/**
 * Router for the [AsrService] engines registered with [AsrEngineKey].
 *
 * Resolves and prepares the [AsrService] for a given [AsrEngineType] or [AsrSessionSettings],
 * walking the [AsrEngineType.fallback] chain when a preferred engine is not ready. Engine types
 * without a registered engine (for example ones stripped from a build) route to the
 * [AsrEngineType.SYSTEM_DEFAULT] engine, which must always be registered.
 *
 * Concrete [AsrService] instances are created lazily via [Provider] on first lookup so unselected
 * engines do not eagerly initialize platform or SDK clients at startup.
 */
@Singleton
open class AsrServiceProvider
@Inject
constructor(
  private val engineProviders: Map<AsrEngineType, @JvmSuppressWildcards Provider<AsrService>>,
  private val asrSettingsRepository: AsrSettingsRepository,
) {
  private val instantiatedEngines = AtomicReference<Map<AsrEngineType, AsrService>>(emptyMap())

  init {
    require(AsrEngineType.SYSTEM_DEFAULT in engineProviders) {
      "An AsrService must be registered for ${AsrEngineType.SYSTEM_DEFAULT}"
    }
  }

  private val systemEngine: AsrService
    get() =
      requireNotNull(getOrCreateEngine(AsrEngineType.SYSTEM_DEFAULT)) {
        "An AsrService must be registered for ${AsrEngineType.SYSTEM_DEFAULT}"
      }

  private val mutex = Mutex()

  private val _activeEngineType = MutableStateFlow<AsrEngineType?>(null)

  /**
   * Engine that last served the user's selection in [AsrSettingsRepository], as resolved by
   * [resolveActiveService] or [prepareActiveService] without an explicit engine or settings, or
   * `null` before the first resolution. Differs from [AsrSettingsRepository.selectedEngineType]
   * when the selected engine is not registered, not ready, or failed to prepare and a fallback
   * engine is used instead.
   */
  val activeEngineType: StateFlow<AsrEngineType?> = _activeEngineType.asStateFlow()

  private fun getOrCreateEngine(engineType: AsrEngineType): AsrService? {
    instantiatedEngines.get()[engineType]?.let {
      return it
    }
    val provider = engineProviders[engineType] ?: return null
    val created = provider.get()
    while (true) {
      val current = instantiatedEngines.get()
      current[engineType]?.let {
        return it
      }
      if (instantiatedEngines.compareAndSet(current, current + (engineType to created))) {
        return created
      }
    }
  }

  /**
   * Returns the [AsrService] registered for [forEngine], or the [AsrEngineType.SYSTEM_DEFAULT]
   * engine if none is registered.
   */
  fun service(forEngine: AsrEngineType): AsrService =
    getOrCreateEngine(forEngine)
      ?: systemEngine.also {
        Log.w(TAG, "No ASR engine registered for $forEngine; using ${AsrEngineType.SYSTEM_DEFAULT}")
      }

  /**
   * Returns the [AsrService] registered for [forEngine], or `null` if none is registered.
   *
   * Unlike [service], this never falls back to the [AsrEngineType.SYSTEM_DEFAULT] engine, for
   * callers that must report an unusable engine instead of silently using another one.
   */
  fun serviceOrNull(forEngine: AsrEngineType): AsrService? = getOrCreateEngine(forEngine)

  /**
   * Returns the [AsrService] for [engineType] only if it has already been instantiated, or `null`
   * otherwise. Unlike [serviceOrNull], this never creates an engine.
   */
  fun instantiatedServiceOrNull(engineType: AsrEngineType): AsrService? =
    instantiatedEngines.get()[engineType]

  /**
   * Prepares the [AsrService] registered for [engineType] with [config] and returns it, or returns
   * `null` if no engine is registered for [engineType].
   *
   * Unlike [prepareActiveService], this never walks the [AsrEngineType.fallback] chain or falls
   * back to the [AsrEngineType.SYSTEM_DEFAULT] engine.
   *
   * @throws AsrServiceError if preparing the engine fails.
   */
  suspend fun prepareServiceOrNull(
    engineType: AsrEngineType,
    config: AsrPreparationConfig,
  ): AsrService? = mutex.withLock {
    val engine = getOrCreateEngine(engineType) ?: return@withLock null
    engine.prepare(config)
    engine
  }

  /**
   * Resolves the active [AsrService] for [preferredEngine], or for the user's persisted preference
   * in [AsrSettingsRepository] when `null`.
   *
   * The first engine in the [AsrEngineType.fallback] chain is used if the selection is explicit or
   * the engine is ready; later engines are used only if they are ready. The last engine in the
   * chain is used unconditionally.
   */
  suspend fun resolveActiveService(preferredEngine: AsrEngineType? = null): AsrService =
    mutex.withLock {
      resolveActiveServiceLocked(preferredEngine).also {
        if (preferredEngine == null) _activeEngineType.value = it.engineType
      }
    }

  private suspend fun resolveActiveServiceLocked(preferredEngine: AsrEngineType?): AsrService {
    val isExplicitSelection =
      preferredEngine != null || asrSettingsRepository.hasUserExplicitAsrSelection()
    var engineType: AsrEngineType =
      preferredEngine ?: asrSettingsRepository.selectedEngineType.value
    var isFirstCandidate = true
    while (true) {
      val fallback = engineType.fallback ?: return service(engineType)
      val engine = getOrCreateEngine(engineType)
      if (engine == null) {
        Log.w(TAG, "No ASR engine registered for $engineType; trying $fallback")
      } else {
        val status = engine.checkStatus()
        if (
          (isFirstCandidate && isExplicitSelection) ||
            status is AsrServiceStatus.Ready ||
            status is AsrServiceStatus.Downloaded
        ) {
          return engine
        }
        Log.i(TAG, "$engineType ASR not ready ($status), trying $fallback")
      }
      engineType = fallback
      isFirstCandidate = false
    }
  }

  /**
   * Resolves and prepares the active [AsrService] using [settings] (or the current settings from
   * [AsrSettingsRepository] when `null`).
   *
   * If preparing a non-system engine throws an [AsrServiceError] and the user has not explicitly
   * selected that engine, falls back to the [AsrEngineType.SYSTEM_DEFAULT] engine so the caller
   * still receives a usable recognizer.
   */
  suspend fun prepareActiveService(settings: AsrSessionSettings? = null): AsrService =
    mutex.withLock {
      prepareActiveServiceLocked(settings).also {
        if (settings == null) _activeEngineType.value = it.engineType
      }
    }

  /**
   * Preloads and warms up the currently selected ASR service in the background if ASR is enabled.
   */
  fun preloadActiveService(scope: CoroutineScope) {
    scope.launch {
      try {
        val unused = prepareActiveService()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to preload ASR service", e)
      }
    }
  }

  private suspend fun prepareActiveServiceLocked(settings: AsrSessionSettings?): AsrService {
    val candidate = resolveActiveServiceLocked(settings?.engineType)
    val effectiveSettings =
      settings
        ?: asrSettingsRepository.currentSessionSettings(engineTypeOverride = candidate.engineType)
    val prepConfig = effectiveSettings.preparationConfig()
    return try {
      candidate.prepare(prepConfig)
      candidate
    } catch (e: AsrServiceError) {
      val isExplicitSelection =
        settings != null || asrSettingsRepository.hasUserExplicitAsrSelection()
      if (candidate === systemEngine || isExplicitSelection) {
        throw e
      }
      Log.w(
        TAG,
        "Failed to prepare ${candidate.engineType} (${e.message}); falling back to System Speech",
      )
      try {
        systemEngine.prepare(prepConfig)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to prepare System Speech", e)
      }
      systemEngine
    }
  }

  /**
   * Returns the [AsrRecordingOptions] corresponding to the user's current ASR settings in
   * [AsrSettingsRepository].
   */
  fun currentRecordingOptions(): AsrRecordingOptions =
    asrSettingsRepository.currentSessionSettings().recordingOptions()

  /** Gracefully stops recording on all instantiated engines. */
  suspend fun stopAll() {
    forEachEngine { it.stopRecording() }
  }

  /** Immediately cancels recording on all instantiated engines. */
  suspend fun cancelAll() {
    forEachEngine { it.cancelRecording() }
  }

  /** Releases resources on all instantiated engines, optionally retaining [except]. */
  fun releaseAll(except: AsrEngineType? = null) {
    while (true) {
      val current = instantiatedEngines.get()
      val retained = current.filterKeys { it == except }
      if (instantiatedEngines.compareAndSet(current, retained)) {
        val enginesToRelease = current.filterKeys { it != except }.values.distinct()
        for (engine in enginesToRelease) {
          try {
            engine.release()
          } catch (e: Exception) {
            Log.w(TAG, "Failed to release ASR engine ${engine.engineType}", e)
          }
        }
        return
      }
    }
  }

  /** Releases resources for the specified [engineType] and removes it from cache. */
  fun releaseEngine(engineType: AsrEngineType) {
    var removedEngine: AsrService? = null
    while (true) {
      val current = instantiatedEngines.get()
      val engine = current[engineType] ?: break
      if (instantiatedEngines.compareAndSet(current, current - engineType)) {
        removedEngine = engine
        break
      }
    }
    try {
      removedEngine?.release()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to release ASR engine $engineType", e)
    }
  }

  /** Releases the native ASR engine if it currently holds the deleted [model]. */
  fun onModelDeleted(model: Model) {
    val deletedEngineType =
      AsrModelRegistry.engineTypeForModelId(model.modelId)
        ?: AsrModelRegistry.resolveEngineType(model.name.ifEmpty { model.modelId })
    deletedEngineType?.let { engineType ->
      val loadedModel = instantiatedServiceOrNull(engineType)?.activeModel
      val holdsDeletedModel =
        loadedModel != null &&
          ((model.name.isNotEmpty() &&
            (loadedModel.name == model.name || loadedModel.modelId == model.name)) ||
            (model.modelId.isNotEmpty() && loadedModel.modelId == model.modelId))
      if (holdsDeletedModel) {
        releaseEngine(engineType)
      }
    }
  }

  private suspend fun forEachEngine(action: suspend (AsrService) -> Unit) {
    val activeEngines = instantiatedEngines.get().values.distinct()
    for (engine in activeEngines) {
      try {
        action(engine)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "ASR engine ${engine.engineType} failed", e)
      }
    }
  }

  companion object {
    /** Convenience factory for callers/tests providing pre-instantiated [AsrService] instances. */
    @JvmStatic
    @JvmName("createWithEngines")
    operator fun invoke(
      engines: Map<AsrEngineType, AsrService>,
      asrSettingsRepository: AsrSettingsRepository,
    ): AsrServiceProvider {
      val providers: Map<AsrEngineType, Provider<AsrService>> = engines.mapValues { (_, engine) ->
        Provider { engine }
      }
      return AsrServiceProvider(
        engineProviders = providers,
        asrSettingsRepository = asrSettingsRepository,
      )
    }
  }
}

/**
 * Resolves and prepares the active [AsrService] via [AsrServiceProvider.prepareActiveService],
 * supports optional cancellation check [shouldAbortAfterPrepare] after engine preparation, and
 * delegates concurrent amplitude and transcript collection to
 * [AsrService.collectRecordingWithAmplitude].
 *
 * Invokes [onRecordingCompleted] only when [shouldAbortAfterPrepare] returns `false` and recording
 * finishes normally.
 */
suspend fun AsrServiceProvider.runDictationSession(
  options: AsrRecordingOptions? = null,
  shouldAbortAfterPrepare: () -> Boolean = { false },
  onServiceReady: (AsrService) -> Unit = {},
  onAmplitudeChanged: (Float) -> Unit,
  onTranscriptChanged: (String) -> Unit,
  onRecordingCompleted: () -> Unit = {},
) {
  val service = prepareActiveService()
  if (shouldAbortAfterPrepare()) {
    return
  }
  onServiceReady(service)
  service.collectRecordingWithAmplitude(
    options = options ?: currentRecordingOptions(),
    onAmplitudeChanged = onAmplitudeChanged,
    onTranscriptChanged = onTranscriptChanged,
  )
  onRecordingCompleted()
}
