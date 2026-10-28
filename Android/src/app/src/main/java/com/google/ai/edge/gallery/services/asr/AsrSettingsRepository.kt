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
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.AsrSettingsProto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGAsrSettingsRepository"

/**
 * Reactive repository for persisting and observing user ASR preferences.
 *
 * Exposes [StateFlow] properties backed by [DataStoreRepository] so changes made in the global
 * Settings dialog or feature screens propagate immediately to all active ViewModels.
 *
 * The persisted settings are loaded asynchronously on [scope]: the flows start at the defaults and
 * update once the load completes. Changes made before the load completes are applied on top of the
 * loaded settings, so they are neither lost nor overwritten. Every change updates the flows
 * synchronously and is persisted asynchronously on [scope].
 */
@Singleton
open class AsrSettingsRepository(
  private val dataStoreRepository: DataStoreRepository,
  private val scope: CoroutineScope,
) {
  @Inject
  constructor(
    dataStoreRepository: DataStoreRepository,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
  ) : this(dataStoreRepository, CoroutineScope(SupervisorJob() + ioDispatcher))

  private val lock = Any()
  private val persistMutex = Mutex()

  /** Settings currently in effect. Guarded by [lock]. */
  private var settings: AsrSettingsProto = AsrSettingsProto.getDefaultInstance()

  /**
   * Whether persisted settings have finished loading from [dataStoreRepository]. Guarded by [lock].
   */
  private var isLoaded = false

  /** Changes made before the persisted settings finished loading. Guarded by [lock]. */
  private val pendingChanges = mutableListOf<(AsrSettingsProto.Builder) -> Unit>()

  private val _selectedModelId = MutableStateFlow(modelIdOf(settings))
  /** [AsrModelIdentifier] of the currently selected ASR engine. */
  val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

  private val _selectedEngineType = MutableStateFlow(engineTypeOf(settings))
  /** Currently selected [AsrEngineType], derived from [selectedModelId]. */
  val selectedEngineType: StateFlow<AsrEngineType> = _selectedEngineType.asStateFlow()

  private val _selectedLanguageCode = MutableStateFlow(languageCodeOf(settings))
  /** Currently selected BCP-47 language code for ASR recognition. */
  val selectedLanguageCode: StateFlow<String> = _selectedLanguageCode.asStateFlow()

  private val _showInterimResults = MutableStateFlow(showInterimResultsOf(settings))
  /** Whether partial recognition hypotheses are emitted during live dictation. */
  val showInterimResults: StateFlow<Boolean> = _showInterimResults.asStateFlow()

  private val _isExplicitlySelected = MutableStateFlow(settings.isExplicitlySelected)
  /**
   * Emits `true` once the user has explicitly selected an ASR engine in Settings or a feature
   * picker, and `false` while the app is still using its default.
   */
  val isExplicitlySelectedFlow: StateFlow<Boolean> = _isExplicitlySelected.asStateFlow()

  init {
    scope.launch { load() }
  }

  /** Returns `true` if the user has explicitly chosen an ASR engine. */
  fun hasUserExplicitAsrSelection(): Boolean = _isExplicitlySelected.value

  /**
   * Updates the selected [AsrEngineType] and marks the selection as explicit.
   *
   * Resets the language to [AsrDefaults.LANGUAGE_CODE] for auto-detect engines, or switches to the
   * first language [engineType] supports if the current language is not supported.
   */
  fun setSelectedEngineType(engineType: AsrEngineType) {
    val metadata = AsrModelRegistry.metadataFor(engineType)
    val supportedLanguages = metadata.supportedLanguages
    update { builder ->
      builder.selectedModelId = AsrModelRegistry.modelId(engineType)
      builder.isExplicitlySelected = true
      when {
        metadata.isLanguageAutoDetected -> {
          builder.selectedLanguageCode = AsrDefaults.LANGUAGE_CODE
        }
        supportedLanguages.isNotEmpty() &&
          languageCodeOf(builder.selectedLanguageCode) !in supportedLanguages -> {
          builder.selectedLanguageCode = supportedLanguages.first()
        }
      }
    }
  }

  /**
   * Updates the selected supplementary ASR [model] and marks the selection as explicit.
   *
   * Delegates to [setSelectedEngineType] when [model] maps to a known [AsrEngineType]. If the
   * current language is not supported by [model], switches to the first language [model] supports.
   */
  fun setSelectedModel(model: Model) {
    val engineType = AsrModelRegistry.engineTypeForModelId(model.modelId)
    if (engineType != null) {
      setSelectedEngineType(engineType)
      return
    }
    val supportedLanguages = AsrModelRegistry.supportedLanguages(model)
    val identifier = model.name.ifEmpty { model.modelId }
    update { builder ->
      builder.selectedModelId = identifier
      builder.isExplicitlySelected = true
      if (
        supportedLanguages.isNotEmpty() &&
          languageCodeOf(builder.selectedLanguageCode) !in supportedLanguages
      ) {
        builder.selectedLanguageCode = supportedLanguages.first()
      }
    }
  }

  /**
   * Called when an ASR supplementary [model] is deleted.
   *
   * If the deleted model was the active ASR engine or model, falls back to
   * [AsrModelIdentifier.SYSTEM_DEFAULT] with `isExplicitlySelected = false`.
   */
  fun onModelDeleted(model: Model) {
    val currentId = _selectedModelId.value
    val modelEngineType = AsrModelRegistry.engineTypeForModelId(model.modelId)
    if (
      currentId == model.name ||
        (model.modelId.isNotEmpty() && currentId == model.modelId) ||
        (modelEngineType != null && currentId == AsrModelRegistry.modelId(modelEngineType))
    ) {
      update { builder ->
        builder.selectedModelId = AsrModelIdentifier.SYSTEM_DEFAULT
        builder.isExplicitlySelected = false
      }
    }
  }

  /**
   * Re-resolves [selectedEngineType] from the current [selectedModelId].
   *
   * Call after [AsrModelRegistry.registerAllowlistModels]: a saved allowlist ASR model resolves to
   * [AsrEngineType.SYSTEM_DEFAULT] until the allowlist that declares it has been registered.
   *
   * @return `true` if [selectedEngineType] changed.
   */
  fun refreshEngineType(): Boolean =
    synchronized(lock) {
      val engineType = engineTypeOf(settings)
      val changed = _selectedEngineType.value != engineType
      _selectedEngineType.value = engineType
      changed
    }

  /** Updates the persisted BCP-47 language code for ASR recognition. */
  fun setSelectedLanguageCode(languageCode: String) {
    update { builder -> builder.selectedLanguageCode = languageCode }
  }

  /** Updates whether partial recognition hypotheses are emitted while speaking. */
  fun setShowInterimResults(show: Boolean) {
    update { builder -> builder.showInterimResults = show }
  }

  /** Builds an [AsrSessionSettings] snapshot from the current repository state. */
  fun currentSessionSettings(
    engineTypeOverride: AsrEngineType? = null,
    languageCodeOverride: String? = null,
  ): AsrSessionSettings {
    val currentModelId = _selectedModelId.value
    val resolvedModelId =
      if (
        engineTypeOverride != null &&
          AsrModelRegistry.resolveEngineType(currentModelId) != engineTypeOverride
      ) {
        AsrModelRegistry.modelId(engineTypeOverride)
      } else {
        currentModelId
      }
    return AsrSessionSettings(
      modelId = resolvedModelId,
      languageCode = languageCodeOverride ?: _selectedLanguageCode.value,
      showInterimResults = _showInterimResults.value,
    )
  }

  private suspend fun load() {
    // `null` if the read fails, in which case the defaults are used.
    val stored: AsrSettingsProto? =
      try {
        dataStoreRepository.readAsrSettings()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load ASR settings; using defaults", e)
        null
      }
    val (loaded, hadPendingChanges) =
      synchronized(lock) {
        val changes = pendingChanges.toList()
        pendingChanges.clear()
        isLoaded = true
        settings =
          (stored ?: AsrSettingsProto.getDefaultInstance())
            .toBuilder()
            .apply {
              for (change in changes) {
                change(this)
              }
            }
            .build()
        publish(settings)
        settings to changes.isNotEmpty()
      }
    if (hadPendingChanges) {
      persist(loaded)
    }
  }

  private fun update(change: (AsrSettingsProto.Builder) -> Unit) {
    val (updated, loaded) =
      synchronized(lock) {
        if (!isLoaded) {
          pendingChanges.add(change)
        }
        settings = settings.toBuilder().apply(change).build()
        publish(settings)
        settings to isLoaded
      }
    // Changes made before the load completes are persisted by [load], on top of the stored values.
    if (loaded) {
      scope.launch { persist(updated) }
    }
  }

  private fun publish(proto: AsrSettingsProto) {
    _selectedModelId.value = modelIdOf(proto)
    _selectedEngineType.value = engineTypeOf(proto)
    _selectedLanguageCode.value = languageCodeOf(proto)
    _showInterimResults.value = showInterimResultsOf(proto)
    _isExplicitlySelected.value = proto.isExplicitlySelected
  }

  private suspend fun persist(proto: AsrSettingsProto) {
    persistMutex.withLock {
      // Skip stale snapshots so an older change can't overwrite a newer one.
      if (synchronized(lock) { settings } != proto) return
      try {
        dataStoreRepository.saveAsrSettings(proto)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist ASR settings", e)
      }
    }
  }

  private companion object {
    fun modelIdOf(proto: AsrSettingsProto): String =
      proto.selectedModelId.ifEmpty { AsrModelIdentifier.SYSTEM_DEFAULT }

    fun engineTypeOf(proto: AsrSettingsProto): AsrEngineType =
      AsrModelRegistry.resolveEngineType(modelIdOf(proto)) ?: AsrEngineType.SYSTEM_DEFAULT

    fun languageCodeOf(languageCode: String): String = languageCode.ifEmpty {
      AsrDefaults.LANGUAGE_CODE
    }

    fun languageCodeOf(proto: AsrSettingsProto): String = languageCodeOf(proto.selectedLanguageCode)

    fun showInterimResultsOf(proto: AsrSettingsProto): Boolean =
      if (proto.hasShowInterimResults()) proto.showInterimResults
      else AsrDefaults.SHOW_INTERIM_RESULTS
  }
}
