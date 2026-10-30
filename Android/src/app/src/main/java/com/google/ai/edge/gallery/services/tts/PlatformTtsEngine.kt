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

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.google.ai.edge.gallery.R
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGPlatformTtsEngine"

/**
 * [TtsService] backed by the Android platform `TextToSpeech` engine.
 *
 * This is the fallback engine: it needs no model download and is available on every device. The
 * platform renders the audio itself, so [synthesize] returns [TtsOutput.DirectPlayback].
 *
 * The platform API reports progress through a single process-wide [UtteranceProgressListener], so
 * per-utterance callbacks are kept in [ConcurrentHashMap]s keyed by utterance id and evicted by
 * `cleanUp`. Every terminal path — completion, error, an interrupted utterance, and collector
 * cancellation — must run `cleanUp`, otherwise the callbacks leak and [status] stays
 * [TtsServiceStatus.Synthesizing] forever.
 */
class PlatformTtsEngine
internal constructor(
  private val context: Context,
  private val ttsFactory: ((Context, TextToSpeech.OnInitListener) -> TextToSpeech)? = null,
) : TtsService {

  constructor(context: Context) : this(context = context, ttsFactory = null)

  private val _status = MutableStateFlow<TtsServiceStatus>(TtsServiceStatus.Ready)
  override val status: StateFlow<TtsServiceStatus> = _status.asStateFlow()

  override val availableModels: List<TtsModelMetadata>
    get() {
      val snapshot =
        cachedCapabilities
          ?: run {
            refreshCachedVoicesAndLanguages()
            cachedCapabilities
          }
      val locales = snapshot?.languages.orEmpty()
      val voices =
        snapshot?.voices?.ifEmpty { listOf(TtsSettingsRepository.DEFAULT_SYSTEM_VOICE) }
          ?: listOf(TtsSettingsRepository.DEFAULT_SYSTEM_VOICE)
      return listOf(
        TtsModelMetadata(
          modelId = TtsModelIdentifier.ANDROID_NATIVE,
          displayName = "Android Native TTS",
          engineType = TtsEngineType.ANDROID_NATIVE,
          supportedLanguages = locales,
          availableVoices = voices,
          voiceDescriptions = snapshot?.voiceDescriptions.orEmpty(),
          voiceLocales = snapshot?.voiceLocales.orEmpty(),
        )
      )
    }

  override val servedModelIds: Set<String> =
    setOf(
      TtsModelIdentifier.ANDROID_SYSTEM,
      TtsModelIdentifier.ANDROID_NATIVE,
      TtsModelIdentifier.ANDROID_NATIVE_TTS,
      TtsModelIdentifier.LEGACY_ANDROID_NATIVE,
    )

  override suspend fun checkModelAvailability(modelId: String): TtsModelAvailability =
    TtsModelAvailability.Downloaded

  override fun downloadModel(modelId: String, expectedTotalBytes: Long): Flow<TtsDownloadProgress> =
    flowOf(
      TtsDownloadProgress(downloadedBytes = expectedTotalBytes, totalBytes = expectedTotalBytes)
    )

  override suspend fun deleteModel(modelId: String) {}

  private data class CachedCapabilities(
    val languages: List<String>,
    val voices: List<String>,
    val voiceDescriptions: Map<String, String>,
    val voiceLocales: Map<String, String>,
  )

  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var cachedCapabilities: CachedCapabilities? = null

  private fun refreshCachedVoicesAndLanguages() {
    val instance = tts ?: return
    try {
      val availableLanguages =
        instance.availableLanguages
          ?.mapNotNull { locale ->
            locale.toLanguageTag().takeIf { tag ->
              tag.isNotEmpty() &&
                !tag.equals(UNDETERMINED_LOCALE_TAG, ignoreCase = true) &&
                !tag.isPseudoLocale()
            }
          }
          .orEmpty()
      val selectableVoices =
        instance.voices
          .orEmpty()
          .filter(::isSelectableSystemVoice)
          .distinctBy { it.name }
          .sortedWith(
            compareBy(
              { it.locale?.toLanguageTag().orEmpty().lowercase(Locale.ROOT) },
              { it.name.lowercase(Locale.ROOT) },
            )
          )
      val voiceList = mutableListOf(TtsSettingsRepository.DEFAULT_SYSTEM_VOICE)
      voiceList.addAll(selectableVoices.map { it.name })
      val voiceLocales =
        selectableVoices
          .mapNotNull { voice ->
            val tag = voice.locale?.toLanguageTag().orEmpty()
            if (tag.isNotEmpty()) voice.name to tag else null
          }
          .toMap()
      val voiceDescriptions = buildSystemVoiceDescriptions(context, selectableVoices)
      val languages = (availableLanguages + voiceLocales.values).distinct()
      cachedCapabilities =
        CachedCapabilities(
          languages = languages,
          voices = voiceList,
          voiceDescriptions = voiceDescriptions,
          voiceLocales = voiceLocales,
        )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to cache platform TTS voices/languages", e)
    }
  }

  @Volatile private var isInitialized = false
  @Volatile private var isPrepared = false
  private val initMutex = Mutex()
  private val speakMutex = Mutex()
  private val initDeferred = CompletableDeferred<Boolean>()

  private val activeCallbacks = ConcurrentHashMap<String, (TtsPlaybackEvent) -> Unit>()
  private val activeErrors = ConcurrentHashMap<String, (Throwable) -> Unit>()
  private val activeCompletions = ConcurrentHashMap<String, () -> Unit>()

  @Volatile private var isPaused = false
  @Volatile private var currentText: String? = null
  @Volatile private var currentUtteranceId: String? = null

  private val globalListener =
    object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        utteranceId?.let { activeCallbacks[it]?.invoke(TtsPlaybackEvent.PLAYBACK_STARTED) }
      }

      override fun onDone(utteranceId: String?) {
        utteranceId?.let { id ->
          activeCallbacks[id]?.invoke(TtsPlaybackEvent.PLAYBACK_COMPLETED)
          activeCompletions[id]?.invoke()
          cleanUp(id)
        }
      }

      override fun onError(utteranceId: String?, errorCode: Int) {
        utteranceId?.let { id ->
          if (isPaused) return
          activeErrors[id]?.invoke(RuntimeException("TTS playback error: $errorCode"))
          cleanUp(id)
        }
      }

      @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
      override fun onError(utteranceId: String?) {
        utteranceId?.let { id ->
          if (isPaused) return
          activeErrors[id]?.invoke(RuntimeException("TTS playback error"))
          cleanUp(id)
        }
      }

      /**
       * Invoked when an utterance is stopped before it finished, either by `TextToSpeech.stop()` or
       * by a `QUEUE_FLUSH` from a newer utterance. Neither `onDone` nor `onError` fires in that
       * case, so this is the only chance to terminate the flow and release the callbacks.
       */
      override fun onStop(utteranceId: String?, interrupted: Boolean) {
        utteranceId?.let { id ->
          if (isPaused) {
            // Playback was paused mid-utterance. Keep callbackFlow open for resume().
            return
          }
          activeCompletions[id]?.invoke()
          cleanUp(id)
        }
      }
    }

  private fun createTextToSpeech(listener: TextToSpeech.OnInitListener): TextToSpeech =
    ttsFactory?.invoke(context.applicationContext, listener)
      ?: TextToSpeech(context.applicationContext, listener)

  init {
    try {
      createTextToSpeech { status ->
          if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            _status.value = TtsServiceStatus.Ready
            initDeferred.complete(true)
            refreshCachedVoicesAndLanguages()
          } else {
            initDeferred.complete(false)
          }
        }
        .also { instance ->
          tts = instance
          instance.setOnUtteranceProgressListener(globalListener)
        }
    } catch (e: Exception) {
      initDeferred.complete(false)
      Log.w(TAG, "Early platform TTS init failed: ${e.message}")
    }
  }

  /**
   * Releases the callbacks registered for [utteranceId] and restores [status] once no utterance is
   * outstanding.
   *
   * Idempotent: removing an absent key from a [ConcurrentHashMap] is a no-op, so it is safe for
   * several terminal paths to race here.
   */
  private fun cleanUp(utteranceId: String) {
    activeCallbacks.remove(utteranceId)
    activeErrors.remove(utteranceId)
    activeCompletions.remove(utteranceId)
    if (activeCallbacks.isEmpty()) {
      _status.value = TtsServiceStatus.Ready
    }
  }

  override suspend fun prepare(config: TtsConfig) = initMutex.withLock {
    if (isInitialized && tts != null) {
      speakMutex.withLock {
        if (activeCallbacks.isEmpty()) {
          applyConfig(config)
        }
      }
      isPrepared = true
      if (activeCallbacks.isEmpty()) {
        _status.value = TtsServiceStatus.Ready
      }
      return@withLock
    }

    _status.value = TtsServiceStatus.Initializing
    try {
      if (tts != null && !isInitialized) {
        val unused = withTimeoutOrNull(2000L) { initDeferred.await() }
      }
      if (isInitialized && tts != null) {
        speakMutex.withLock {
          if (activeCallbacks.isEmpty()) {
            applyConfig(config)
          }
        }
        isPrepared = true
        if (activeCallbacks.isEmpty()) {
          _status.value = TtsServiceStatus.Ready
        }
        return@withLock
      }

      tts?.shutdown()
      tts = null

      tts = suspendCancellableCoroutine { continuation ->
        var ttsInstance: TextToSpeech? = null
        var syncInitStatus: Int? = null
        val createdTts = createTextToSpeech { status ->
          val instance = ttsInstance
          if (instance == null) {
            syncInitStatus = status
            return@createTextToSpeech
          }
          if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            instance.setOnUtteranceProgressListener(globalListener)
            continuation.resume(instance)
          } else {
            continuation.resumeWithException(
              RuntimeException("Failed to initialize Android TextToSpeech")
            )
          }
        }
        ttsInstance = createdTts
        syncInitStatus?.let { status ->
          if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            createdTts.setOnUtteranceProgressListener(globalListener)
            continuation.resume(createdTts)
          } else {
            continuation.resumeWithException(
              RuntimeException("Failed to initialize Android TextToSpeech")
            )
          }
        }
        continuation.invokeOnCancellation { createdTts.shutdown() }
      }
      refreshCachedVoicesAndLanguages()
      speakMutex.withLock { applyConfig(config) }
      isPrepared = true
      _status.value = TtsServiceStatus.Ready
    } catch (e: CancellationException) {
      _status.value =
        if (isPrepared && tts != null) TtsServiceStatus.Ready else TtsServiceStatus.Downloaded
      throw e
    } catch (e: Exception) {
      isPrepared = false
      _status.value = TtsServiceStatus.Failed(e)
      Log.e(TAG, "Failed to prepare PlatformTtsEngine", e)
      throw e
    }
  }

  private fun applyConfig(config: TtsConfig) {
    val ttsInstance = tts ?: return
    config.languageCode?.let { langTag ->
      val locale = Locale.forLanguageTag(langTag)
      val result = ttsInstance.setLanguage(locale)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TAG, "Language $langTag is not supported or missing data")
      }
    }
    config.voiceId?.let { voiceName ->
      if (voiceName != TtsSettingsRepository.DEFAULT_SYSTEM_VOICE) {
        val voice = ttsInstance.voices?.find { it.name == voiceName && isSelectableSystemVoice(it) }
        if (voice != null) {
          ttsInstance.voice = voice
        }
      }
    }
    config.speed?.let { ttsInstance.setSpeechRate(it) }
    config.pitch?.let { ttsInstance.setPitch(it) }
  }

  override fun synthesize(text: String, config: TtsConfig?): TtsOutput {
    val ttsInstance = tts
    if (!isPrepared || ttsInstance == null) {
      val modelId = config?.modelId ?: TtsModelIdentifier.ANDROID_SYSTEM
      return TtsOutput.DirectPlayback(
        callbackFlow {
          close(
            TtsServiceError.EngineUnavailable(
              "Android platform TTS ($modelId) has not been prepared; call prepare() before" +
                " synthesize()"
            )
          )
        }
      )
    }

    val requestedQueueMode = config?.queueMode ?: TtsQueueMode.FLUSH
    val androidQueueMode =
      when (requestedQueueMode) {
        TtsQueueMode.FLUSH -> TextToSpeech.QUEUE_FLUSH
        TtsQueueMode.ADD -> TextToSpeech.QUEUE_ADD
      }

    val flow = callbackFlow {
      _status.value = TtsServiceStatus.Synthesizing
      currentText = text
      isPaused = false

      if (requestedQueueMode == TtsQueueMode.FLUSH) {
        val flushedIds = activeCompletions.keys.toList()
        for (id in flushedIds) {
          activeCompletions[id]?.invoke()
          cleanUp(id)
        }
      }

      val utteranceId = UUID.randomUUID().toString()
      currentUtteranceId = utteranceId

      var finishedOrStopped = false
      activeCallbacks[utteranceId] = { event ->
        val unused = trySend(event)
      }
      activeErrors[utteranceId] = { error ->
        finishedOrStopped = true
        close(error)
      }
      activeCompletions[utteranceId] = {
        finishedOrStopped = true
        close()
      }

      val params = Bundle()
      params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

      val result = speakMutex.withLock {
        config?.let { applyConfig(it) }
        ttsInstance.speak(text, androidQueueMode, params, utteranceId)
      }
      if (result == TextToSpeech.ERROR) {
        finishedOrStopped = true
        close(RuntimeException("Failed to start speaking"))
        cleanUp(utteranceId)
      }

      awaitClose {
        // The collector went away: release this utterance's callbacks and halt the engine only if
        // no other queued utterances remain active and stop()/completion has not already run.
        cleanUp(utteranceId)
        if (!isPaused && !finishedOrStopped && activeCallbacks.isEmpty()) {
          ttsInstance.stop()
        }
        if (currentUtteranceId == utteranceId) {
          currentUtteranceId = null
          currentText = null
          isPaused = false
        }
      }
    }

    return TtsOutput.DirectPlayback(flow)
  }

  override fun pause() {
    isPaused = true
    tts?.stop()
    if (isPrepared && tts != null) {
      _status.value = TtsServiceStatus.Ready
    }
  }

  override fun resume() {
    if (!isPaused) return
    isPaused = false
    val textToResume = currentText ?: return
    val ttsInstance = tts ?: return
    val oldId = currentUtteranceId
    val newId = UUID.randomUUID().toString()
    currentUtteranceId = newId

    if (oldId != null) {
      activeCallbacks.remove(oldId)?.let { activeCallbacks[newId] = it }
      activeErrors.remove(oldId)?.let { activeErrors[newId] = it }
      activeCompletions.remove(oldId)?.let { activeCompletions[newId] = it }
    }

    _status.value = TtsServiceStatus.Synthesizing
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, newId)
    val result = ttsInstance.speak(textToResume, TextToSpeech.QUEUE_FLUSH, params, newId)
    if (result == TextToSpeech.ERROR) {
      activeErrors[newId]?.invoke(RuntimeException("Failed to resume speaking"))
      cleanUp(newId)
    }
  }

  override fun stop() {
    isPaused = false
    currentText = null
    currentUtteranceId = null
    tts?.stop()
    val pendingCompletions = activeCompletions.values.toList()
    activeCallbacks.clear()
    activeErrors.clear()
    activeCompletions.clear()
    for (completion in pendingCompletions) {
      completion.invoke()
    }
    if (isPrepared && tts != null) {
      _status.value = TtsServiceStatus.Ready
    }
  }

  override fun cancel() {
    stop()
  }

  override fun destroy() {
    cancel()
    tts?.shutdown()
    tts = null
    cachedCapabilities = null
    isInitialized = false
    isPrepared = false
    _status.value = TtsServiceStatus.Downloaded
  }

  internal companion object {
    private const val FEATURE_LEGACY_SET_LANGUAGE_VOICE = "legacySetLanguageVoice"
    private const val LEGACY_LANGUAGE_VOICE_SUFFIX = "-language"
    private const val UNDETERMINED_LOCALE_TAG = "und"

    /**
     * Returns whether [voice] is an installed, offline, user-selectable platform system voice
     * (excluding network voices, uninstalled voice packs, synthetic `<locale>-language` aliases, ML
     * Kit / VoiceLM Star voices surfaced through Speech Services, and testing pseudo-locales).
     */
    internal fun isSelectableSystemVoice(voice: Voice): Boolean {
      if (voice.isNetworkConnectionRequired) return false
      val name = voice.name?.trim().orEmpty()
      if (name.isEmpty()) return false
      val features = voice.features.orEmpty()
      if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) return false
      if (FEATURE_LEGACY_SET_LANGUAGE_VOICE in features) return false
      if (name.endsWith(LEGACY_LANGUAGE_VOICE_SUFFIX, ignoreCase = true)) return false
      if (isMlKitOrVoiceLmInternalVoice(name)) return false
      val localeTag = voice.locale?.toLanguageTag().orEmpty()
      if (
        localeTag.isEmpty() ||
          localeTag.equals(UNDETERMINED_LOCALE_TAG, ignoreCase = true) ||
          localeTag.isPseudoLocale()
      ) {
        return false
      }
      return true
    }

    internal fun isMlKitOrVoiceLmInternalVoice(voiceName: String): Boolean =
      voiceName.contains("-x-star", ignoreCase = true) ||
        voiceName.contains("-x-gx", ignoreCase = true) ||
        containsStarVoiceCode(voiceName)

    /**
     * Extracts the short speaker code from a platform voice identifier such as `en-us-x-hol-local`
     * or `en-us-x-sfg#male` (`"hol"`, `"sfg"`), or returns `null` when the identifier does not
     * follow the `-x-<code>` naming scheme.
     */
    internal fun extractSystemVoiceSpeakerCode(voiceName: String): String? {
      val markerIndex = voiceName.indexOf("-x-", ignoreCase = true)
      if (markerIndex < 0) return null
      val afterMarker = voiceName.substring(markerIndex + 3)
      val code =
        afterMarker
          .substringBefore('#')
          .trim()
          .lowercase(Locale.ROOT)
          .removeSuffix("-local")
          .removeSuffix("-network")
      return code.takeIf { it.isNotEmpty() && '-' !in it }
    }

    /**
     * Formats localized display labels (e.g., `"Voice 1 (hol)"`) for [voices], numbering voices
     * sequentially starting at 1 within each BCP-47 locale in the iteration order of [voices].
     */
    internal fun buildSystemVoiceDescriptions(
      context: Context,
      voices: List<Voice>,
    ): Map<String, String> {
      val descriptions = LinkedHashMap<String, String>(voices.size)
      val groupedByLocale = voices.groupBy {
        it.locale?.toLanguageTag().orEmpty().lowercase(Locale.ROOT)
      }
      for ((_, localeVoices) in groupedByLocale) {
        for ((index, voice) in localeVoices.withIndex()) {
          val voiceNumber = index + 1
          val speakerCode = extractSystemVoiceSpeakerCode(voice.name)
          descriptions[voice.name] =
            if (speakerCode != null) {
              context.getString(
                R.string.tts_system_voice_with_code_format,
                voiceNumber,
                speakerCode,
              )
            } else {
              context.getString(R.string.tts_system_voice_numbered_format, voiceNumber)
            }
        }
      }
      return descriptions
    }
  }
}
