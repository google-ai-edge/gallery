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
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerOptions
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.VoiceInfo
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "AGMlKitTtsEngine"

/** Abstraction over ML Kit's [SpeechSynthesizer] to facilitate unit testing and client sharing. */
interface MlKitSpeechSynthesizer : Closeable {
  suspend fun getSupportedVoices(): List<VoiceInfo>

  suspend fun getSupportedVoices(locale: Locale): List<VoiceInfo> = getSupportedVoices()

  suspend fun checkStatus(voice: VoiceInfo): Int

  fun download(voice: VoiceInfo): Flow<DownloadStatus>

  fun synthesize(request: SpeechSynthesizerRequest): Flow<SpeechSynthesizerResponse>

  fun stop()

  override fun close()
}

private class RealMlKitSpeechSynthesizer(private val delegate: SpeechSynthesizer) :
  MlKitSpeechSynthesizer {
  override suspend fun getSupportedVoices(): List<VoiceInfo> = delegate.getSupportedVoices()

  override suspend fun getSupportedVoices(locale: Locale): List<VoiceInfo> =
    delegate.getSupportedVoices(locale)

  override suspend fun checkStatus(voice: VoiceInfo): Int = delegate.checkStatus(voice)

  override fun download(voice: VoiceInfo): Flow<DownloadStatus> = delegate.download(voice)

  override fun synthesize(request: SpeechSynthesizerRequest): Flow<SpeechSynthesizerResponse> =
    delegate.synthesize(request)

  override fun stop() = delegate.stop()

  override fun close() = delegate.close()
}

/**
 * [TtsService] backed by ML Kit GenAI Text-to-Speech, served by Play Services.
 *
 * The engine renders audio itself, so [synthesize] returns [TtsOutput.DirectPlayback]. [prepare]
 * picks a voice for the requested locale and downloads its model if Play Services does not have it
 * yet, which is why preparation can take a long time and reports [TtsServiceStatus.Downloading].
 *
 * A single shared [SpeechSynthesizer] instance serves all locales via
 * [MlKitSpeechSynthesizer.getSupportedVoices] per locale and per-request [VoiceInfo], avoiding
 * multiple gRPC channels and Binder service connections across code-switched languages.
 *
 * Every [synthesize] flow runs on [synthesisDispatcher], which must execute one task at a time in
 * submission order. Callers collect queued segments in order, so each flow, and the ML Kit flow it
 * collects, is scheduled on that dispatcher in the same order, and the SDK hands requests to
 * `TextToSpeech.speak()` in that order. Without it, flows dispatched on a multi-threaded pool can
 * reach `speak()` out of order, and a late [TtsQueueMode.FLUSH] drops segments queued before it.
 */
class MlKitTtsEngine(
  private val context: Context,
  private val clientFactory: ((SpeechSynthesizerOptions) -> MlKitSpeechSynthesizer)? = null,
  private val sbgVersionCodeProvider: () -> Long = { SpeechServicesVersion.versionCode(context) },
  internal val clockMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
  internal val pcmPlayer:
    (suspend (pcmBytes: ByteArray, startOffset: Int, shouldStop: () -> Boolean) -> Unit)? =
    null,
  internal val synthesisDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : TtsService {

  private val _status = MutableStateFlow<TtsServiceStatus>(TtsServiceStatus.Ready)
  override val status: StateFlow<TtsServiceStatus> = _status.asStateFlow()

  internal fun setEngineStatus(newStatus: TtsServiceStatus) {
    _status.value = newStatus
  }

  internal val warmedLocales = ConcurrentHashMap<Locale, WarmedLocaleVoices>()

  /** The (locale, voice name) pairs whose warm-up utterance finished; see [warmUpVoice]. */
  internal data class WarmedVoice(val locale: Locale, val voiceName: String)

  internal val warmedUpVoices: MutableSet<WarmedVoice> = ConcurrentHashMap.newKeySet()
  @Volatile internal var synthesizer: MlKitSpeechSynthesizer? = null
  @Volatile internal var synthesizerCreatedAtMs = 0L
  @Volatile internal var currentLocale: Locale = Locale.US
  @Volatile private var supportedVoices: List<VoiceInfo> = emptyList()
  private val warmMutex = Mutex()
  internal val voiceWarmUpMutex = Mutex()

  /** Voice downloads in flight; only the last one to end publishes its outcome as [status]. */
  private val activeLanguageDownloads = AtomicInteger(0)
  internal val lifecycleGeneration = AtomicInteger(0)
  internal val activeFlowGeneration = AtomicInteger(0)

  /** How many times [stop] was called, so that [synthesize] doesn't retry a stopped utterance. */
  internal val stopCount = AtomicInteger(0)

  @Volatile internal var isPaused = false
  @Volatile internal var currentText: String? = null
  @Volatile internal var onPauseCallback: (() -> Unit)? = null
  @Volatile internal var resumeSynthesis: (() -> Unit)? = null
  internal val activeCompletions = ConcurrentHashMap<Int, () -> Unit>()

  fun setSupportedVoices(voices: List<VoiceInfo>) {
    supportedVoices = voices
  }

  internal fun resolveSupportedLocale(languageCode: String): Locale =
    MlKitTtsModels.resolveSupportedLocale(languageCode)

  fun isClientWarmed(locale: Locale): Boolean =
    warmedLocales.containsKey(MlKitTtsModels.normalizeLocale(locale))

  val warmedLocaleCount: Int
    get() = warmedLocales.size

  private fun buildSynthesizerOptions(locale: Locale): SpeechSynthesizerOptions =
    SpeechSynthesizerOptions.Builder(locale).setVoiceFallbackEnabled(false).build()

  internal fun acquireSynthesizer(locale: Locale): MlKitSpeechSynthesizer {
    return synthesizer
      ?: synchronized(this) {
        synthesizer
          ?: run {
            val options = buildSynthesizerOptions(locale)
            (clientFactory?.invoke(options)
                ?: RealMlKitSpeechSynthesizer(SpeechSynthesis.getClient(options)))
              .also {
                synthesizerCreatedAtMs = clockMsProvider()
                synthesizer = it
              }
          }
      }
  }

  internal fun findVoice(voices: List<VoiceInfo>, requestedVoiceName: String): VoiceInfo? =
    MlKitTtsModels.findVoice(voices, requestedVoiceName)

  companion object {
    const val AICORE_DEV_PREVIEW_GUIDE_URL =
      "https://developers.google.com/ml-kit/genai/aicore-dev-preview"
    internal const val PCM_SAMPLE_RATE_HZ = 24000
    internal const val PCM_BYTES_PER_SAMPLE = 2

    /** Upper bound on the [isSupportedOnDevice] voice query, which runs during allowlist load. */
    internal const val DEVICE_SUPPORT_QUERY_TIMEOUT_MS = 3_000L

    /**
     * Text of the utterance [prepare] speaks to warm up a voice. A single space plays no audio but
     * still makes the TTS engine load the voice.
     */
    internal const val VOICE_WARM_UP_TEXT = " "

    /**
     * Delays before each retry of a warm-up utterance that failed with `ERROR_NOT_INSTALLED_YET`.
     * About 5 s in total, after which [prepare] still succeeds but leaves the voice cold, so the
     * next [prepare] warms it up again.
     */
    internal val VOICE_WARM_UP_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L, 1_500L, 2_000L)

    /** Upper bound on a single warm-up utterance. */
    internal const val VOICE_WARM_UP_TIMEOUT_MS = 5_000L

    /**
     * How long a voice download may report nothing before [downloadLanguage] stops waiting for it
     * and fails with [TtsServiceError.DownloadStalled].
     */
    internal const val DOWNLOAD_STALL_TIMEOUT_MS = 10_000L

    /**
     * Wait between retries of an utterance that failed with [TextToSpeech.ERROR_SYNTHESIS] before
     * playing any audio, and maximum number of retries per utterance.
     */
    internal const val SYNTHESIS_ERROR_RETRY_DELAY_MS = 150L
    internal const val SYNTHESIS_ERROR_MAX_RETRIES = 3

    /**
     * Additional locales offered only on a subset of device model manifests in ML Kit GenAI
     * Text-to-Speech.
     */
    internal val PREVIEW_EXTRA_LOCALES: Set<String>
      get() = MlKitTtsModels.PREVIEW_EXTRA_LOCALES

    /** Superset of locales in ML Kit GenAI Text-to-Speech. */
    val SUPPORTED_LOCALES: List<String>
      get() = MlKitTtsModels.SUPPORTED_LOCALES

    internal fun resolveSupportedLocale(languageCode: String): Locale =
      MlKitTtsModels.resolveSupportedLocale(languageCode)

    internal fun findVoice(voices: List<VoiceInfo>, requestedVoiceName: String): VoiceInfo? =
      MlKitTtsModels.findVoice(voices, requestedVoiceName)

    internal fun getDisplayNameForVoice(rawName: String): String =
      MlKitTtsModels.getDisplayNameForVoice(rawName)
  }

  override val availableModels: List<TtsModelMetadata>
    get() =
      MlKitTtsModels.buildModelMetadata(
        context = context,
        supportedVoices = supportedVoices,
        activeVoice = warmedLocales[currentLocale]?.voice,
      )

  override val servedModelIds: Set<String> = setOf(TtsModelIdentifier.ML_KIT)

  /** Speech Services plays the audio and the SDK takes no speaking rate. */
  override val supportsSpeedControl: Boolean = false

  /** The voice models run in Speech Services, not in this process. */
  override val holdsNativeWeights: Boolean = false

  /**
   * No caller-side retries: [synthesize] already retries pre-start `ERROR_SYNTHESIS` failures via
   * [retrySynthesisErrorBeforeStart].
   */
  override fun preStartRetryDelaysMs(error: TtsServiceError): List<Long> = emptyList()

  private val deviceSupportMutex = Mutex()
  @Volatile private var cachedDeviceSupport: Boolean? = null

  /**
   * Reports whether this device is in the population Speech Services serves ML Kit voices to.
   *
   * Speech Services that is missing or older than [SpeechServicesVersion.MIN_VERSION_CODE] counts
   * as supported, because [checkModelAvailability] then offers the update. Once it is up to date,
   * an empty [SpeechSynthesizer] `getSupportedVoices()` result for [DEFAULT_TTS_LANGUAGE_CODE]
   * means the device is not served. Query failures and timeouts count as supported. The first
   * answer is cached for the life of the process.
   */
  override suspend fun isSupportedOnDevice(): Boolean {
    cachedDeviceSupport?.let {
      return it
    }
    return deviceSupportMutex.withLock {
      cachedDeviceSupport?.let {
        return@withLock it
      }
      queryDeviceSupport()?.also { cachedDeviceSupport = it } ?: true
    }
  }

  private suspend fun queryDeviceSupport(): Boolean? {
    if (!SpeechServicesVersion.isUpToDate(sbgVersionCodeProvider())) {
      return true
    }
    val locale = MlKitTtsModels.normalizeLocale(Locale.forLanguageTag(DEFAULT_TTS_LANGUAGE_CODE))
    return try {
      val voices =
        withTimeout(DEVICE_SUPPORT_QUERY_TIMEOUT_MS) {
          val synthClient = acquireSynthesizer(locale)
          val baseVoices = synthClient.getSupportedVoices(locale)
          if (baseVoices.isEmpty()) {
            closeAllClients()
          }
          baseVoices
        }
      if (voices.isEmpty()) {
        Log.i(TAG, "Speech Services reports no ML Kit voices for $locale; device is unsupported")
      }
      voices.isNotEmpty()
    } catch (e: TimeoutCancellationException) {
      Log.w(
        TAG,
        "Timed out querying ML Kit voices for $locale; failing open without caching result",
        e,
      )
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(
        TAG,
        "Failed to query ML Kit voices for $locale; failing open without caching result",
        e,
      )
      null
    }
  }

  override suspend fun checkAvailability(modelId: String): SpeechAvailability {
    if (!SpeechServicesVersion.isUpToDate(sbgVersionCodeProvider())) {
      Log.w(
        TAG,
        "checkAvailability: Speech Services is missing or older than " +
          SpeechServicesVersion.MIN_VERSION_CODE,
      )
      return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED)
    }

    val locale = MlKitTtsModels.normalizeLocale(Locale.forLanguageTag(DEFAULT_TTS_LANGUAGE_CODE))
    return try {
      val synthClient = acquireSynthesizer(locale)
      val voices = synthClient.getSupportedVoices(locale)
      if (voices.isEmpty()) {
        Log.i(TAG, "checkAvailability: Speech Services reports no ML Kit voices for $locale")
        return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.NOT_OPTED_IN)
      }
      when (val status = synthClient.checkStatus(voices.first())) {
        FeatureStatus.AVAILABLE -> SpeechAvailability.Available
        FeatureStatus.DOWNLOADABLE -> SpeechAvailability.NeedsDownload()
        FeatureStatus.DOWNLOADING -> SpeechAvailability.Downloading(0L, 0L)
        FeatureStatus.UNAVAILABLE -> {
          Log.w(TAG, "checkAvailability: checkStatus returned UNAVAILABLE for $locale")
          SpeechAvailability.Unavailable(SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW)
        }
        else -> {
          Log.d(TAG, "checkAvailability: checkStatus returned $status for $locale")
          SpeechAvailability.NeedsDownload()
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "checkAvailability: probe failed for $locale", e)
      SpeechAvailability.ProbeFailed(e)
    }
  }

  override suspend fun checkLanguageAvailability(
    modelId: String,
    languageCode: String,
  ): SpeechAvailability {
    if (!SpeechServicesVersion.isUpToDate(sbgVersionCodeProvider())) {
      Log.w(
        TAG,
        "checkLanguageAvailability: Speech Services is missing or older than " +
          SpeechServicesVersion.MIN_VERSION_CODE,
      )
      return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED)
    }

    val locale = resolveSupportedLocale(languageCode)
    return try {
      val synthClient = acquireSynthesizer(locale)
      val voices = synthClient.getSupportedVoices(locale)
      if (voices.isEmpty()) {
        Log.i(TAG, "checkLanguageAvailability: no voices offered for $locale")
        return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.LOCALE_NOT_OFFERED)
      }
      when (val status = synthClient.checkStatus(voices.first())) {
        FeatureStatus.AVAILABLE -> SpeechAvailability.Available
        FeatureStatus.DOWNLOADABLE -> SpeechAvailability.NeedsDownload()
        FeatureStatus.DOWNLOADING -> SpeechAvailability.Downloading(0L, 0L)
        FeatureStatus.UNAVAILABLE -> {
          Log.w(TAG, "checkLanguageAvailability: status UNAVAILABLE for $locale")
          SpeechAvailability.Unavailable(SpeechUnavailabilityReason.LOCALE_NOT_OFFERED)
        }
        else -> {
          Log.d(TAG, "checkLanguageAvailability: status $status for $locale")
          SpeechAvailability.NeedsDownload()
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "checkLanguageAvailability: probe failed for $locale", e)
      SpeechAvailability.ProbeFailed(e)
    }
  }

  /** Reports whether the engine is usable on this device. */
  override suspend fun checkModelAvailability(modelId: String): TtsModelAvailability =
    when (val availability = checkLanguageAvailability(modelId, DEFAULT_TTS_LANGUAGE_CODE)) {
      is SpeechAvailability.Available -> TtsModelAvailability.Downloaded
      is SpeechAvailability.NeedsDownload,
      is SpeechAvailability.Downloading -> TtsModelAvailability.NotDownloaded
      is SpeechAvailability.Unavailable -> {
        if (availability.reason == SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED) {
          TtsModelAvailability.Unavailable(
            reason = TtsUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING,
            guideUrl = SpeechServicesVersion.PLAY_STORE_URL,
          )
        } else {
          TtsModelAvailability.Unavailable(reason = TtsUnavailabilityReason.UNSUPPORTED_MODEL)
        }
      }
      is SpeechAvailability.ProbeFailed ->
        TtsModelAvailability.Unavailable(reason = TtsUnavailabilityReason.UNSUPPORTED_MODEL)
    }

  /** Downloads the voice pack for the currently prepared locale. */
  override fun downloadModel(modelId: String, expectedTotalBytes: Long): Flow<TtsDownloadProgress> =
    downloadLanguage(modelId, currentLocale.toLanguageTag(), expectedTotalBytes)

  /**
   * Downloads the voice for [languageCode] and warms that locale when the download finishes.
   *
   * Downloads don't hold the warm-up lock, so voices for different locales download in parallel and
   * [prepare] doesn't wait behind a download.
   *
   * The flow fails with [TtsServiceError.DownloadStalled] once the download reports nothing for
   * [DOWNLOAD_STALL_TIMEOUT_MS]. That only stops waiting: Speech Services keeps the download.
   */
  override fun downloadLanguage(
    modelId: String,
    languageCode: String,
    expectedTotalBytes: Long,
  ): Flow<TtsDownloadProgress> = flow {
    val normLocale = resolveSupportedLocale(languageCode)
    val initialTotalBytes = expectedTotalBytes.coerceAtLeast(0L)
    emit(TtsDownloadProgress(downloadedBytes = 0L, totalBytes = initialTotalBytes))

    val startGeneration = lifecycleGeneration.get()
    val existing = warmedLocales[normLocale]
    val synthClient = acquireSynthesizer(normLocale)
    var outcome: TtsServiceStatus? = null
    activeLanguageDownloads.incrementAndGet()
    try {
      val queriedVoices =
        try {
          synthClient.getSupportedVoices(normLocale)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed to query supported voices for $normLocale via gRPC", e)
          emptyList()
        }
      val voices = queriedVoices.ifEmpty { existing?.supportedVoices.orEmpty() }
      if (voices.isEmpty()) {
        throw TtsServiceError.EngineUnavailable(
          "No supported ML Kit voices available for locale $normLocale"
        )
      }
      if (supportedVoices.isEmpty()) {
        supportedVoices = voices
      }
      val selected =
        existing?.voice
          ?: voices.firstOrNull()
          ?: throw TtsServiceError.EngineUnavailable(
            "No supported ML Kit voices available for locale $normLocale"
          )

      val statusVal = synthClient.checkStatus(selected)
      when (statusVal) {
        FeatureStatus.DOWNLOADABLE,
        FeatureStatus.DOWNLOADING -> {
          _status.value = TtsServiceStatus.Downloading
          synthClient
            .download(selected)
            .toDownloadProgress(initialTotalBytes)
            .failWhenStalled(normLocale)
            .collect { progress ->
              emit(
                TtsDownloadProgress(
                  downloadedBytes = progress.downloadedBytes,
                  totalBytes = progress.totalBytes,
                )
              )
            }
        }
        FeatureStatus.AVAILABLE -> {
          emit(
            TtsDownloadProgress(downloadedBytes = initialTotalBytes, totalBytes = initialTotalBytes)
          )
        }
        else -> {
          throw TtsServiceError.EngineUnavailable(
            "ML Kit TTS feature status is unavailable ($statusVal) for locale $normLocale"
          )
        }
      }

      if (lifecycleGeneration.get() != startGeneration) {
        throw CancellationException("MlKitTtsEngine was destroyed during downloadLanguage()")
      }
      // prepare() may have warmed this locale with a specific voice while the download ran. Keep
      // that voice instead of replacing it with the one picked here.
      warmedLocales.putIfAbsent(
        normLocale,
        WarmedLocaleVoices(voice = selected, supportedVoices = voices),
      )
      outcome = TtsServiceStatus.Ready
    } catch (e: Exception) {
      if (e !is CancellationException) {
        outcome = TtsServiceStatus.Failed(e)
      }
      throw e
    } finally {
      // While another download runs, or an utterance plays, they own the engine status.
      val remainingDownloads = activeLanguageDownloads.decrementAndGet()
      if (outcome != null && remainingDownloads == 0 && activeCompletions.isEmpty()) {
        _status.value = outcome
      }
    }
  }

  override suspend fun deleteModel(modelId: String) {
    stop()
    closeAllClients()
    _status.value = TtsServiceStatus.Downloaded
  }

  private suspend fun warmClientForLocale(
    locale: Locale,
    requestedVoiceId: String? = null,
  ): WarmedLocaleVoices {
    val normLocale = MlKitTtsModels.normalizeLocale(locale)
    warmedLocales[normLocale]?.let { existing ->
      if (requestedVoiceId != null && existing.voice.name != requestedVoiceId) {
        findVoice(existing.supportedVoices, requestedVoiceId)?.let { existing.voice = it }
      }
      return existing
    }

    return warmMutex.withLock {
      warmedLocales[normLocale]?.let { existing ->
        if (requestedVoiceId != null && existing.voice.name != requestedVoiceId) {
          findVoice(existing.supportedVoices, requestedVoiceId)?.let { existing.voice = it }
        }
        return@withLock existing
      }

      val startGeneration = lifecycleGeneration.get()
      val synthClient = acquireSynthesizer(normLocale)
      val voices = synthClient.getSupportedVoices(normLocale)
      if (voices.isEmpty()) {
        throw TtsServiceError.EngineUnavailable(
          "No supported ML Kit voices available for locale ${normLocale.toLanguageTag()}"
        )
      }
      if (supportedVoices.isEmpty()) {
        supportedVoices = voices
      }
      var voiceToUse = voices.firstOrNull()
      if (requestedVoiceId != null) {
        voiceToUse = findVoice(voices, requestedVoiceId) ?: voiceToUse
      }
      val selected =
        voiceToUse
          ?: throw TtsServiceError.EngineUnavailable(
            "No supported ML Kit voices available for locale ${normLocale.toLanguageTag()}"
          )

      val statusVal = synthClient.checkStatus(selected)
      when (statusVal) {
        FeatureStatus.DOWNLOADABLE,
        FeatureStatus.DOWNLOADING -> {
          _status.value = TtsServiceStatus.Downloading
          try {
            synthClient.download(selected).toDownloadProgress().collect {}
          } catch (e: CancellationException) {
            _status.value =
              if (warmedLocales.isNotEmpty()) TtsServiceStatus.Ready
              else TtsServiceStatus.Downloaded
            throw e
          } catch (e: Exception) {
            throw RuntimeException(
              "Failed to download ML Kit voice model for locale ${normLocale.toLanguageTag()}",
              e,
            )
          }
        }
        FeatureStatus.AVAILABLE -> {}
        else -> {
          throw TtsServiceError.EngineUnavailable(
            "ML Kit TTS voice '${selected.name}' is unavailable ($statusVal) for locale ${normLocale.toLanguageTag()}"
          )
        }
      }

      if (lifecycleGeneration.get() != startGeneration) {
        throw CancellationException("MlKitTtsEngine was destroyed during warmClientForLocale()")
      }

      val warmed = WarmedLocaleVoices(voice = selected, supportedVoices = voices)
      warmedLocales[normLocale] = warmed
      if (activeCompletions.isEmpty()) {
        _status.value = TtsServiceStatus.Ready
      }
      warmed
    }
  }

  /**
   * Picks a voice for [TtsConfig.languageCode] (downloading it if needed, see the class KDoc) and
   * then warms that voice up with [warmUpVoice], so that the voice is loaded before the first real
   * utterance. Both steps are cached, so preparing a locale and voice again returns right away.
   */
  override suspend fun prepare(config: TtsConfig) {
    val locale =
      config.languageCode?.let(::resolveSupportedLocale)
        ?: MlKitTtsModels.normalizeLocale(currentLocale)
    if (locale !in warmedLocales.keys && activeCompletions.isEmpty()) {
      _status.value = TtsServiceStatus.Initializing
    }
    try {
      val warmed = warmClientForLocale(locale, config.voiceId)
      warmUpVoice(locale, warmed.voice)
      currentLocale = locale
      if (activeCompletions.isEmpty()) {
        _status.value = TtsServiceStatus.Ready
      }
    } catch (e: CancellationException) {
      if (activeCompletions.isEmpty()) {
        _status.value =
          if (warmedLocales.isNotEmpty()) TtsServiceStatus.Ready else TtsServiceStatus.Downloaded
      }
      throw e
    } catch (e: Exception) {
      _status.value = TtsServiceStatus.Failed(e)
      Log.e(TAG, "Failed to prepare MlKitTtsEngine for locale $locale", e)
      throw e
    }
  }

  /**
   * Pre-warms synthesizers for the given [locales] concurrently.
   *
   * Useful when transitioning between known languages (e.g. host language and target language in
   * language learning) to avoid synthesis delay or code-switching pauses.
   */
  suspend fun prewarm(locales: List<Locale>, voiceId: String? = null) {
    coroutineScope {
      for (locale in locales) {
        launch {
          try {
            val unused = warmClientForLocale(locale, voiceId)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Failed to prewarm locale $locale", e)
          }
        }
      }
    }
  }

  override fun synthesize(text: String, config: TtsConfig?): TtsOutput =
    TtsOutput.DirectPlayback(
      synthesisAttempt(text, config).retrySynthesisErrorBeforeStart(this, text, config)
    )

  override fun pause() {
    if (!isPaused) {
      isPaused = true
      onPauseCallback?.invoke()
    }
    synthesizer?.stop()
    if (warmedLocales.isNotEmpty()) {
      _status.value = TtsServiceStatus.Ready
    }
  }

  override fun resume() {
    if (!isPaused) return
    isPaused = false
    _status.value = TtsServiceStatus.Synthesizing
    resumeSynthesis?.invoke()
  }

  override fun stop() {
    stopCount.incrementAndGet()
    isPaused = false
    currentText = null
    onPauseCallback = null
    resumeSynthesis = null
    synthesizer?.stop()
    val pendingCompletions = activeCompletions.values.toList()
    activeCompletions.clear()
    for (completion in pendingCompletions) {
      completion.invoke()
    }
    if (warmedLocales.isNotEmpty()) {
      _status.value = TtsServiceStatus.Ready
    }
  }

  override fun cancel() {
    stop()
  }

  override fun destroy() {
    stop()
    closeAllClients()
    supportedVoices = emptyList()
    // The voice model is still on device; only the client was released. See TtsServiceStatus.
    _status.value = TtsServiceStatus.Downloaded
  }

  private fun closeAllClients() {
    val unusedGen = lifecycleGeneration.incrementAndGet()
    warmedLocales.clear()
    warmedUpVoices.clear()
    val client = synchronized(this) { synthesizer.also { synthesizer = null } }
    if (client != null) {
      try {
        client.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close ML Kit SpeechSynthesizer", e)
      }
    }
  }
}
