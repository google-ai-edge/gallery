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

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.google.ai.edge.gallery.di.ApplicationScope
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AGTtsServiceProvider"

/**
 * Central factory and provider for resolving concrete [TtsService] implementations by engine type
 * or model identifier.
 *
 * Vends the underlying singleton [TtsService] engine for a requested [TtsEngineType] or
 * [TtsConfig], allowing callers to prepare and interact with the concrete engine directly.
 *
 * Engines are registered through Hilt map multibinding (see [TtsEngineKey]), so adding an engine
 * does not change this class. The Android platform engine ([TtsEngineType.ANDROID_NATIVE]) must be
 * registered: it is the only engine guaranteed to exist on every device.
 *
 * Routing by model ID is driven by [TtsConfig.modelId] and each engine's
 * [TtsService.servedModelIds]: see [TtsModelIdentifier] for the recognized identifiers. An
 * unregistered engine type or unrecognized identifier falls back to the platform engine; the
 * fallback is logged so a typo does not silently degrade to system speech.
 */
open class TtsServiceProvider(
  private val engines: Map<TtsEngineType, @JvmSuppressWildcards TtsService>
) {

  init {
    require(TtsEngineType.ANDROID_NATIVE in engines) {
      "The Android platform TTS engine (${TtsEngineType.ANDROID_NATIVE}) must be registered."
    }
    hasCreatedInstance.set(true)
  }

  private val platformEngine: TtsService = engines.getValue(TtsEngineType.ANDROID_NATIVE)

  /** Every registered engine once, even if it is registered under several engine types. */
  private val distinctEngines: List<TtsService> = engines.values.distinct()

  private val stopAllListeners = CopyOnWriteArrayList<() -> Unit>()

  /**
   * Registers [listener] to be notified whenever [stopAll] is called, allowing callers that manage
   * their own PCM [android.media.AudioTrack] (such as read-aloud controllers or voice previews) to
   * stop active playback when another feature stops all TTS output.
   */
  fun registerOnStopAllListener(listener: () -> Unit) {
    stopAllListeners.addIfAbsent(listener)
  }

  /** Unregisters a previously registered [stopAll] [listener]. */
  fun unregisterOnStopAllListener(listener: () -> Unit) {
    stopAllListeners.remove(listener)
  }

  private val enginesByModelId: Map<String, TtsService> = buildMap {
    for (engine in distinctEngines) {
      for (modelId in engine.servedModelIds) {
        val existing = put(modelId, engine)
        require(existing == null) { "TTS model ID '$modelId' is served by more than one engine." }
      }
    }
  }

  /** Lists all models exposed across all managed [TtsService] engines. */
  open val availableModels: List<TtsModelMetadata>
    get() = distinctEngines.flatMap { it.availableModels }.distinctBy { it.modelId }

  /** Returns the concrete [TtsService] instance corresponding to [forEngineType]. */
  open fun service(forEngineType: TtsEngineType): TtsService =
    engines[forEngineType]
      ?: platformEngine.also {
        Log.w(TAG, "No TTS engine registered for $forEngineType; using the platform engine.")
      }

  /** Returns the concrete [TtsService] instance that serves [forModelId]. */
  open fun service(forModelId: String?): TtsService {
    val modelId = forModelId ?: TtsModelIdentifier.ANDROID_NATIVE
    return enginesByModelId[modelId]
      ?: platformEngine.also {
        Log.w(
          TAG,
          "Unrecognized TTS modelId '$modelId'; falling back to the Android platform engine.",
        )
      }
  }

  /**
   * Returns the concrete [TtsService] instance registered for [forEngineType], or `null` if none is
   * registered. Unlike [service], this never falls back to the platform engine.
   */
  open fun serviceOrNull(forEngineType: TtsEngineType): TtsService? = engines[forEngineType]

  /**
   * Returns the concrete [TtsService] instance that serves [forModelId], or `null` if no engine
   * serves it. Unlike [service], this never falls back to the platform engine.
   */
  open fun serviceOrNull(forModelId: String): TtsService? = enginesByModelId[forModelId]

  /** Returns the concrete [TtsService] instance that serves [forConfig]. */
  open fun service(forConfig: TtsConfig): TtsService = service(forModelId = forConfig.modelId)

  /**
   * Resolves the concrete [TtsService] for [config], prepares it with [config], and returns the
   * prepared engine.
   */
  open suspend fun prepareService(
    config: TtsConfig,
    progressListener: ((Float) -> Unit)? = null,
  ): TtsService {
    val engine = service(forConfig = config)
    if (progressListener != null) {
      engine.prepare(config, progressListener)
    } else {
      engine.prepare(config)
    }
    return engine
  }

  /** Stops playback across all managed engines and notifies registered playback listeners. */
  open fun stopAll() {
    stopAllExcept(exceptEngine = null)
  }

  /**
   * Stops playback across all managed engines except [exceptEngine] and notifies registered
   * playback listeners.
   */
  open fun stopAllExcept(exceptEngine: TtsService?) {
    for (engine in distinctEngines) {
      if (engine !== exceptEngine) {
        engine.stop()
      }
    }
    for (listener in stopAllListeners) {
      listener()
    }
  }

  /** Cancels active synthesis across all managed engines. */
  open fun cancelAll() {
    for (engine in distinctEngines) {
      engine.cancel()
    }
  }

  /** Destroys all managed engines and releases their native resources. */
  open fun destroyAll() {
    for (engine in distinctEngines) {
      engine.destroy()
    }
  }

  /**
   * Releases native resources for neural (non-platform) TTS engines whose models are not currently
   * active.
   *
   * This is the single lifecycle hook for releasing neural TTS engines; screen/ViewModel teardown
   * (`TtsPlaybackController.destroy`) only stops playback so neural engines stay loaded across
   * screens. Call this hook on:
   * 1. Model deletion ([exceptModelId] = remaining selected model ID, or `null`).
   * 2. TTS engine switch ([exceptModelId] = newly selected model ID).
   * 3. Background memory pressure ([TtsMemoryTrimCallbacks.onTrimMemory], [exceptModelId] = `null`,
   *    [skipSynthesizing] = `true`, [onlyEnginesWithNativeWeights] = `true` below
   *    `TRIM_MEMORY_COMPLETE`).
   *
   * @param onlyEnginesWithNativeWeights when `true`, only releases engines whose
   *   [TtsService.holdsNativeWeights] is `true`. Engines whose models run in another process (ML
   *   Kit) free no memory here when released, and their next utterance would pay for a cold client.
   */
  open suspend fun releaseInactiveNeuralEngines(
    exceptModelId: String? = null,
    skipSynthesizing: Boolean = false,
    onlyEnginesWithNativeWeights: Boolean = false,
  ) {
    val activeEngine = exceptModelId?.let { enginesByModelId[it] }
    for (engine in distinctEngines) {
      if (engine === platformEngine || engine === activeEngine) continue
      if (onlyEnginesWithNativeWeights && !engine.holdsNativeWeights) continue
      if (skipSynthesizing && engine.status.value is TtsServiceStatus.Synthesizing) continue
      try {
        engine.release()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to release inactive TTS engine", e)
      }
    }
  }

  companion object {
    internal val hasCreatedInstance = AtomicBoolean(false)
  }
}

/**
 * Application-level [ComponentCallbacks2] that evicts idle neural TTS engines when the app moves to
 * the background (`level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND`), so they do not retain
 * native model weights and GPU delegates while backgrounded. Below `TRIM_MEMORY_COMPLETE`, only
 * engines that hold weights in this process ([TtsService.holdsNativeWeights]) are evicted, so ML
 * Kit, whose models run in another process, keeps its client. At `TRIM_MEMORY_COMPLETE`, where the
 * process is next to be killed, every engine except the platform engine is released, including ML
 * Kit's binding to Speech Services. Note that on Android 14+ (API 34+), `TRIM_MEMORY_COMPLETE` is
 * deprecated and no longer dispatched by `ActivityManager` (background trims only deliver
 * `TRIM_MEMORY_UI_HIDDEN` or `TRIM_MEMORY_BACKGROUND`); because [MlKitTtsEngine] requires API 35+,
 * on supported devices `level < TRIM_MEMORY_COMPLETE` is practically always true during background
 * trims. Active screens re-prepare the selected engine through `prepareMutex` when resumed or when
 * speech is requested.
 */
@Singleton
class TtsMemoryTrimCallbacks
internal constructor(
  private val ttsServiceProvider: Optional<Provider<TtsServiceProvider>>,
  private val applicationScope: CoroutineScope,
  private val isTtsEnabled: () -> Boolean,
  private val isProviderCreated: () -> Boolean,
) : ComponentCallbacks2 {

  @Inject
  constructor(
    ttsServiceProvider: Optional<Provider<TtsServiceProvider>>,
    @ApplicationScope applicationScope: CoroutineScope,
  ) : this(
    ttsServiceProvider = ttsServiceProvider,
    applicationScope = applicationScope,
    isTtsEnabled = { true },
    isProviderCreated = { TtsServiceProvider.hasCreatedInstance.get() },
  )

  override fun onTrimMemory(level: Int) {
    if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
    if (!isTtsEnabled() || !isProviderCreated()) return
    val provider = ttsServiceProvider.getOrNull()?.get() ?: return
    // TRIM_MEMORY_COMPLETE is deprecated in API 35, and API 34 and later no longer send it, but the
    // app supports API 31 to 33, which still send it when the process is next to be killed.
    @Suppress("DEPRECATION")
    val isProcessNextToBeKilled = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    applicationScope.launch {
      provider.releaseInactiveNeuralEngines(
        exceptModelId = null,
        skipSynthesizing = true,
        // Keep engines without weights here (ML Kit) unless the process is about to be killed.
        onlyEnginesWithNativeWeights = !isProcessNextToBeKilled,
      )
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {}

  @Deprecated("Deprecated in ComponentCallbacks") override fun onLowMemory() {}
}
