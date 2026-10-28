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

package com.google.ai.edge.gallery.ui.common.tts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.supplementary.EffectiveTtsSelection
import com.google.ai.edge.gallery.data.supplementary.EngineErrorReason
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionNotice
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.services.tts.DEFAULT_TTS_LANGUAGE_CODE
import com.google.ai.edge.gallery.services.tts.PreStartRetries
import com.google.ai.edge.gallery.services.tts.TextChunk
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsPlaybackEvent
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceError
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsWordTimestamp
import com.google.ai.edge.gallery.services.tts.startOnFirstFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGTtsPlaybackController"
private const val SAMPLE_RATE_HZ = 24000
private const val MIN_AUDIO_BUFFER_BYTES = 64 * 1024

/** 16-bit mono PCM bytes per sample frame. */
internal const val BYTES_PER_FRAME = 2

/** Maximum number of PCM chunks buffered for a prefetched sentence. */
internal const val PREFETCH_BUFFER_CAPACITY = 64

/** How long to wait before retrying a write into a full audio buffer. */
private const val WRITE_RETRY_DELAY_MS = 10L

@VisibleForTesting internal const val DRAIN_POLL_INTERVAL_MS = 20L

/**
 * Lookahead frames (70 ms at 24 kHz) applied once the AudioTrack playback head starts advancing on
 * full utterances, compensating for mixer/HAL output latency, poll cadence, and consonant onsets.
 */
@VisibleForTesting internal const val WORD_HIGHLIGHT_LEAD_FRAMES = 1680L

/** Gives up waiting for the audio buffer to drain if the playback head stops moving this long. */
@VisibleForTesting internal const val DRAIN_STALL_TIMEOUT_MS = 2000L

/** Failures in a row, with no sentence spoken in between, after which the engine is reported. */
internal const val MAX_CONSECUTIVE_ENGINE_FAILURES = 2

/** Lifecycle state of text-to-speech playback for the active message. */
enum class TtsPlaybackState {
  IDLE,
  PLAYING,
  PAUSED,
  ENDED,
  STOPPED,
}

/** Manages platform audio focus for speech playback. */
internal interface AudioFocusController {
  /** Requests transient speech audio focus that ducks other media. */
  fun requestFocus(onFocusLost: () -> Unit)

  /** Abandons previously requested audio focus. */
  fun abandonFocus()
}

internal object NoOpAudioFocusController : AudioFocusController {
  override fun requestFocus(onFocusLost: () -> Unit) {}

  override fun abandonFocus() {}
}

private class AndroidAudioFocusController(context: Context) : AudioFocusController {
  private val audioManager: AudioManager? =
    context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private var focusRequest: AudioFocusRequest? = null

  override fun requestFocus(onFocusLost: () -> Unit) {
    val manager = audioManager ?: return
    if (focusRequest == null) {
      focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          .setOnAudioFocusChangeListener { change ->
            when (change) {
              AudioManager.AUDIOFOCUS_LOSS,
              AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusLost()
            }
          }
          .build()
    }
    focusRequest?.let { manager.requestAudioFocus(it) }
  }

  override fun abandonFocus() {
    val manager = audioManager ?: return
    focusRequest?.let { manager.abandonAudioFocusRequest(it) }
  }
}

/**
 * Plays 16-bit mono PCM audio produced by [TtsOutput.AudioStream] engines.
 *
 * Implementations need not be thread-safe: [TtsPlaybackController] only calls them from its
 * single-threaded audio dispatcher.
 */
internal interface PcmAudioSink {
  /** Number of frames played so far, as an unsigned 32-bit counter. */
  val playbackHeadFrames: Long

  /** Number of frames written since the sink was created or last flushed. */
  val writtenFrames: Long

  fun play()

  fun pause()

  /** Discards audio that has been written but not played yet. Only valid while paused. */
  fun flush()

  /**
   * Writes up to [size] bytes of [data] starting at [offset] without blocking.
   *
   * @return the number of bytes written, which is 0 when the buffer is full, or a negative error
   *   code.
   */
  @SuppressLint("KotlinArray") fun write(data: ByteArray, offset: Int, size: Int): Int

  /** Retimes playback to [speed]. Ignored while the sink is not playing. */
  fun setSpeed(speed: Float)

  fun release()
}

/** [PcmAudioSink] backed by a streaming [AudioTrack]. */
private class AudioTrackSink : PcmAudioSink {
  private val track: AudioTrack =
    AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(SAMPLE_RATE_HZ)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(
        max(
          AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          ),
          MIN_AUDIO_BUFFER_BYTES,
        )
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
      .startOnFirstFrame()

  override var writtenFrames = 0L
    private set

  override val playbackHeadFrames: Long
    get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

  override fun play() {
    track.play()
  }

  override fun pause() {
    track.pause()
  }

  override fun flush() {
    track.flush()
    writtenFrames = 0L
  }

  override fun write(data: ByteArray, offset: Int, size: Int): Int {
    val written = track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    if (written > 0) {
      writtenFrames += written / BYTES_PER_FRAME
    }
    return written
  }

  override fun setSpeed(speed: Float) {
    // Setting playback parameters on a paused track can restart it, which would resume audio the
    // user just paused.
    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
    track.playbackParams = track.playbackParams.setSpeed(speed)
  }

  override fun release() {
    track.release()
  }
}

/**
 * Controls sentence-by-sentence text-to-speech synthesis, streaming audio playback, and
 * highlighting.
 *
 * Public methods must be called from the main thread. Playback state only changes on the main
 * thread, and the [PcmAudioSink] is only touched on a single-threaded audio dispatcher, so the two
 * never race.
 *
 * Speech uses the saved TTS engine only while it is ready ([SpeechSelectionUseCase]). Otherwise the
 * system voice speaks and [selectionNotice] says why; the saved choice is kept. Runtime failures of
 * the saved engine are recovered once where that can help, then reported so its model stops being
 * selectable:
 * - The sentence failed before any audio played, and the engine says to retry that failure
 *   ([TtsService.preStartRetryDelaysMs]): synthesize it again after each delay, for every sentence,
 *   without preparing again. Only then does the failure count below.
 * - The engine doesn't list the voice: the voice is checked again, so a voice that isn't installed
 *   shows as such.
 * - The engine's service failed (`TextToSpeech.ERROR_SERVICE`): recreate the engine's client and
 *   retry once per playback; after that it counts as any other failure.
 * - The speech service stopped or was updated: recreate the engine's client and retry once.
 * - Any other failure, [MAX_CONSECUTIVE_ENGINE_FAILURES] times in a row: report an engine error.
 *
 * Transient failures reported this way end on their own after a while or when a screen that reads
 * aloud resumes ([onReadAloudScreenResumed]), so the saved engine is tried again.
 */
@Singleton
class TtsPlaybackController
internal constructor(
  private val ttsServiceProvider: TtsServiceProvider?,
  private val ttsSettingsRepository: TtsSettingsRepository? = null,
  private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
  private val audioSinkFactory: () -> PcmAudioSink = { AudioTrackSink() },
  private val audioFocusController: AudioFocusController = NoOpAudioFocusController,
  private val speechSelectionUseCase: SpeechSelectionUseCase? = null,
) {
  /**
   * [ttsServiceProvider] is absent in builds without `services/tts:tts_service` (see
   * `TtsApiModule`), in which case [isAvailable] is `false`.
   */
  @Inject
  constructor(
    @ApplicationContext context: Context,
    ttsServiceProvider: Optional<TtsServiceProvider>,
    ttsSettingsRepository: TtsSettingsRepository,
    speechSelectionUseCase: SpeechSelectionUseCase,
  ) : this(
    ttsServiceProvider = ttsServiceProvider.getOrNull(),
    ttsSettingsRepository = ttsSettingsRepository,
    audioFocusController = AndroidAudioFocusController(context),
    speechSelectionUseCase = speechSelectionUseCase,
  )

  /**
   * Speaks with the saved engine as it is, without the selection rules of [SpeechSelectionUseCase],
   * so no notice is ever shown. For tests in other modules.
   */
  @VisibleForTesting
  constructor(
    context: Context,
    ttsServiceProvider: Optional<TtsServiceProvider>,
    ttsSettingsRepository: TtsSettingsRepository,
  ) : this(
    ttsServiceProvider = ttsServiceProvider.getOrNull(),
    ttsSettingsRepository = ttsSettingsRepository,
    audioFocusController = AndroidAudioFocusController(context),
  )

  companion object {
    /** Supported speech playback speed multipliers. */
    val SPEED_OPTIONS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Tolerance for comparing floating-point playback speeds. */
    const val SPEED_EPSILON: Float = 0.01f

    /** Returns whether [a] and [b] represent the same playback speed within [SPEED_EPSILON]. */
    fun isSameSpeed(a: Float, b: Float): Boolean = abs(a - b) < SPEED_EPSILON
  }

  /**
   * Process-wide scope owned by this `@Singleton` controller. Kept alive across [destroy] calls
   * (invoked when a screen-scoped [TtsPlaybackViewModel] clears) so the single-instance settings
   * collectors and subsequent screen playback sessions remain active for the process lifetime.
   */
  private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
  private var synthesisJob: Job? = null
  private var preloadJob: Job? = null

  /** Read-aloud screens open now; see [onReadAloudScreenOpened]. */
  private var openReadAloudScreenCount = 0

  /** Set by [preload] so a running preload checks the selection again before it finishes. */
  private var isPreloadRequested = false

  /** Bounded buffer holding the prefetched [TtsOutput.AudioStream] for sentence `index`. */
  private class PrefetchedSentence(
    val index: Int,
    val text: String,
    val modelId: String?,
    val voiceId: String?,
    val languageCode: String?,
    val speed: Float,
    val service: TtsService,
    val chunks: Channel<ByteArray>,
    val wordTimestamps: List<TtsWordTimestamp> = emptyList(),
    val producerJob: Job,
  )

  private var prefetchedSentence: PrefetchedSentence? = null
  private var waitingToPrefetchIndex: Int? = null
  @Volatile private var playbackRequestMark: TimeSource.Monotonic.ValueTimeMark? = null
  @Volatile private var lastSentenceDrainMark: TimeSource.Monotonic.ValueTimeMark? = null

  /** Only accessed on [audioDispatcher]. */
  private var activeSink: PcmAudioSink? = null

  private var activeService: TtsService? = null
  private var lastPreparedModelId: String? = null
  private var lastPreparedVoiceId: String? = null
  private var lastPreparedLanguageCode: String? = null

  /** Sentences in a row that failed on [lastFailedEngineId] with no sentence spoken in between. */
  private var consecutiveFailures = 0

  /** The engine the last failed sentence used, so failures of other engines aren't counted. */
  private var lastFailedEngineId: String? = null

  /**
   * The engine, voice, and language the current playback speaks with, resolved for its first
   * sentence, or `null` to resolve them again. Cleared when playback stops (which settings changes
   * also cause) and after a runtime failure is reported.
   */
  private var playbackSelection: EffectiveTtsSelection? = null

  /**
   * Messages whose playback the user (or app backgrounding) dismissed; [streamAutoPlay] will not
   * restart them unless the user explicitly starts them via [startPlayback].
   */
  private val dismissedMessageIds = mutableSetOf<String>()

  /**
   * Whether the active session was started by [streamAutoPlay] rather than [startPlayback], so a
   * subsequent message in the same assistant turn (e.g. after a tool/skill or thinking block) can
   * hand over cleanly instead of being permanently dismissed.
   */
  private var isAutoPlaySession = false

  private data class QueuedAutoPlayMessage(
    val messageId: String,
    val text: String,
    val isDone: Boolean,
    val capabilityName: String,
  )

  /** Follow-up message of an auto-play turn waiting for the current message to finish speaking. */
  private var queuedAutoPlayMessage: QueuedAutoPlayMessage? = null

  /**
   * The message currently being streamed via [streamAutoPlay], tracked from the first token even
   * before the first complete sentence sets [_activeMessageId], so [dismiss] (and
   * [onAppBackgrounded]) suppresses the rest of the reply if dismissed before any sentence
   * delimiter arrives.
   */
  private var streamingMessageId: String? = null

  /**
   * Set by [onAppBackgrounded] and cleared by [onReadAloudScreenResumed]. While set,
   * [streamAutoPlay] doesn't start speaking on its own.
   */
  private var isAppInBackground = false

  /**
   * Whether the current playback already recreated the engine's client after
   * `TextToSpeech.ERROR_SERVICE`. Reset when a new playback starts.
   */
  private var hasRecreatedClientForServiceErrorThisPlayback = false

  /**
   * Monotonically increasing generation counter incremented whenever playback starts a sentence,
   * stops, or finishes, so stale coroutine completions cannot advance [currentSentenceIndex] or
   * mutate playback state after a seek or interruption.
   */
  @Volatile private var playbackGeneration = 0L

  /**
   * Whether the active message reached [TtsPlaybackState.ENDED] by finishing its last available
   * sentence naturally, rather than aborting a sentence early. Only when `true` should
   * [streamAutoPlay] automatically resume into newly streamed sentences.
   */
  private var completedAllSentencesNaturally = false
  /** Whether a backing TTS engine provider is available in this build. */
  val isAvailable: Boolean
    get() = ttsServiceProvider != null

  private val _selectionNotice = MutableStateFlow<SpeechSelectionNotice?>(null)

  /**
   * Set while the saved TTS model can't be used, so the system voice reads aloud instead, or `null`
   * while the saved engine speaks. The saved choice is kept.
   */
  val selectionNotice: StateFlow<SpeechSelectionNotice?> = _selectionNotice.asStateFlow()

  private val _isSpeedControlSupported =
    MutableStateFlow(supportsSpeedControl(ttsSettingsRepository?.selectedTtsModelID?.value))

  /**
   * Whether the engine that speaks supports changing playback speed
   * ([TtsService.supportsSpeedControl]).
   */
  val isSpeedControlSupported: StateFlow<Boolean> = _isSpeedControlSupported.asStateFlow()

  private val _activeMessageId = MutableStateFlow<String?>(null)
  /** Identifier of the message currently loaded for playback or highlighting, if any. */
  val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

  private val _playbackState = MutableStateFlow(TtsPlaybackState.IDLE)
  /** Current [TtsPlaybackState] of the controller. */
  val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

  /** Convenience flag indicating whether [playbackState] is [TtsPlaybackState.PLAYING]. */
  val isPlaying: Boolean
    get() = _playbackState.value == TtsPlaybackState.PLAYING

  private val _sentences = MutableStateFlow<List<TextChunk>>(emptyList())
  /** Segmented speakable sentences for [activeMessageId]. */
  val sentences: StateFlow<List<TextChunk>> = _sentences.asStateFlow()

  private val _currentSentenceIndex = MutableStateFlow(0)
  /** Zero-based index into [sentences] of the sentence currently being spoken or paused. */
  val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

  private val _currentWordIndex = MutableStateFlow<Int?>(null)
  /**
   * Zero-based index of the word currently being spoken within [currentSentenceIndex], or `null`
   * when the active engine does not report word-level alignment.
   */
  val currentWordIndex: StateFlow<Int?> = _currentWordIndex.asStateFlow()

  private val _playbackSpeed = MutableStateFlow(1.0f)
  /** Requested playback rate multiplier. */
  val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

  private val _isExpanded = MutableStateFlow(false)
  /** Whether the floating TTS control banner should be shown. */
  val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

  private val _isAutoScrollEnabled = MutableStateFlow(true)
  /** Whether the chat view should automatically scroll to follow [currentSentenceIndex]. */
  val isAutoScrollEnabled: StateFlow<Boolean> = _isAutoScrollEnabled.asStateFlow()

  private val _inFlightTailText = MutableStateFlow<String?>(null)
  /** Trailing unpunctuated text of a streaming message not yet committed to [sentences]. */
  val inFlightTailText: StateFlow<String?> = _inFlightTailText.asStateFlow()

  private val _scrollTrigger = MutableStateFlow(0)
  /** Monotonic counter incremented when the user requests to jump to the active sentence. */
  val scrollTrigger: StateFlow<Int> = _scrollTrigger.asStateFlow()

  /** Request to scroll the chat viewport so [centerYInRoot] is vertically centered. */
  data class ScrollRequest(val seq: Long, val centerYInRoot: Float)

  private var nextScrollRequestSeq = 0L
  private val _scrollRequest = MutableStateFlow<ScrollRequest?>(null)
  /** Latest active-sentence root Y coordinate to center within the scrollable chat viewport. */
  val scrollRequest: StateFlow<ScrollRequest?> = _scrollRequest.asStateFlow()

  private val _playbackStarts =
    MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /**
   * Emits whenever read-aloud playback starts or resumes (via [startPlayback], [seekToSentence],
   * [togglePlayPause], [resume], or a new [streamAutoPlay] session), so active voice dictation can
   * cancel itself instead of transcribing the app's own speech.
   */
  val playbackStarts: SharedFlow<Unit> = _playbackStarts.asSharedFlow()

  private val onExternalStopAllListener: () -> Unit = {
    scope.launch(mainDispatcher) {
      if (
        _playbackState.value != TtsPlaybackState.IDLE &&
          _playbackState.value != TtsPlaybackState.STOPPED
      ) {
        stopLocalPlaybackOnly()
      }
    }
  }

  init {
    ttsSettingsRepository?.let { settings ->
      scope.launch {
        settings.selectedTtsModelID.collect { modelId ->
          _isSpeedControlSupported.value = supportsSpeedControl(modelId)
        }
      }
      var isInitialEmission = true
      scope.launch {
        combine(
            settings.selectedTtsModelID,
            settings.selectedTtsVoice,
            settings.selectedLanguageCode,
          ) { _, _, _ ->
          }
          .collect {
            if (!isInitialEmission) {
              stop()
            }
            isInitialEmission = false
            preload()
          }
      }
    }
    // Saved selections can only be checked once the allowlist registers the speech models.
    if (speechSelectionUseCase != null) {
      scope.launch {
        speechSelectionUseCase.hasRegisteredModelsFlow(SpeechModality.TTS).collect { hasModels ->
          if (hasModels) preload()
        }
      }
    }
    ttsServiceProvider?.registerOnStopAllListener(onExternalStopAllListener)
  }

  /**
   * Called when a screen that reads aloud opens. While at least one is open, the engine read-aloud
   * would speak with is kept prepared ([preload]).
   */
  @MainThread
  fun onReadAloudScreenOpened() {
    openReadAloudScreenCount += 1
    preload()
  }

  /** Called when a screen that reads aloud closes; balances [onReadAloudScreenOpened]. */
  @MainThread
  fun onReadAloudScreenClosed() {
    openReadAloudScreenCount = (openReadAloudScreenCount - 1).coerceAtLeast(0)
  }

  /**
   * Called when a screen that reads aloud resumes to the foreground. Clears a transient runtime
   * failure of the saved engine ([SpeechSelectionUseCase.onReadAloudScreenResumed]) so it is tried
   * again, and re-warms the selected neural engine if it was evicted while the app was in the
   * background.
   */
  @MainThread
  fun onReadAloudScreenResumed() {
    isAppInBackground = false
    speechSelectionUseCase?.onReadAloudScreenResumed()
    if (
      _playbackState.value != TtsPlaybackState.PLAYING &&
        _playbackState.value != TtsPlaybackState.PAUSED
    ) {
      playbackSelection = null
      _selectionNotice.value = null
    }
    preload()
  }

  /**
   * Called when the app leaves the foreground (Home, another app, or screen off) while a screen
   * that reads aloud is open. Stops playback and resets the control bar like [dismiss], and keeps
   * [streamAutoPlay] from speaking a message that is still arriving, so nothing is heard while the
   * app is in the background. The user can play the message again after returning.
   */
  @MainThread
  fun onAppBackgrounded() {
    isAppInBackground = true
    dismiss()
  }

  /**
   * Prepares the engine that read-aloud would speak with now, in the background, so that the first
   * sentence doesn't wait for it. Each engine does its own warm-up in [TtsService.prepare].
   *
   * Does nothing unless a read-aloud screen is open ([onReadAloudScreenOpened]) and nothing is
   * playing: playback prepares its own engine. Only the engine of the effective selection
   * ([SpeechSelectionUseCase.resolveEffectiveTtsSelection]) is prepared, and only once the
   * allowlist has registered the speech models, so a saved engine that isn't ready is never
   * prepared. System engines need no preparation. Without a [SpeechSelectionUseCase] (tests), the
   * saved engine is prepared as is.
   *
   * A request made while a preload runs is not dropped: the running preload checks the selection
   * again before it finishes.
   */
  private fun preload() {
    val provider = ttsServiceProvider ?: return
    if (openReadAloudScreenCount == 0) return
    isPreloadRequested = true
    if (preloadJob?.isActive == true) return
    preloadJob = scope.launch {
      // Runs on the main thread like preload(), so a request can't slip in between the last check
      // of isPreloadRequested and the end of the job.
      while (isPreloadRequested) {
        isPreloadRequested = false
        preloadOnce(provider)
      }
    }
  }

  private suspend fun preloadOnce(provider: TtsServiceProvider) {
    if (
      synthesisJob?.isActive == true ||
        _playbackState.value == TtsPlaybackState.PLAYING ||
        _playbackState.value == TtsPlaybackState.PAUSED
    ) {
      return
    }
    val config = resolvePreloadConfig() ?: return
    try {
      val service = provider.service(forConfig = config)
      if (isPrepared(service, config)) return
      withContext(ioDispatcher) { service.prepare(config) }
      markPrepared(config)
    } catch (e: CancellationException) {
      // The engine can cancel its own preparation, for example when it is released because another
      // engine was selected. Only stop if this preload itself was cancelled.
      currentCoroutineContext().ensureActive()
      Log.w(TAG, "Preloading TTS engine '${config.modelId}' was cancelled by the engine", e)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to preload TTS engine '${config.modelId}'", e)
    }
  }

  /**
   * The engine, voice, and language that read-aloud would speak with now, or `null` when there is
   * nothing to preload: a system engine, or a saved engine that can't be checked yet.
   */
  private suspend fun resolvePreloadConfig(): TtsConfig? {
    if (speechSelectionUseCase?.hasRegisteredModels(SpeechModality.TTS) == false) return null
    val config = resolveSelection().toTtsConfig()
    return config.takeUnless { TtsModelIdentifier.isSystemEngine(config.modelId) }
  }

  private fun EffectiveTtsSelection.toTtsConfig(): TtsConfig =
    TtsConfig(
      modelId = engineId,
      // Empty means the device default language, which SpeechSelectionUseCase only leaves for a
      // system engine (see EffectiveTtsSelection.languageCode).
      languageCode = languageCode.ifEmpty { null },
      voiceId = voiceId.ifEmpty { null },
      speed = _playbackSpeed.value,
    )

  /**
   * Whether [service] is ready and was last prepared with [config]'s model, voice, and language.
   */
  private fun isPrepared(service: TtsService, config: TtsConfig): Boolean {
    return service.status.value.isEngineLoaded &&
      lastPreparedModelId == config.modelId &&
      lastPreparedVoiceId == config.voiceId &&
      lastPreparedLanguageCode == config.languageCode
  }

  private fun markPrepared(config: TtsConfig) {
    lastPreparedModelId = config.modelId
    lastPreparedVoiceId = config.voiceId
    lastPreparedLanguageCode = config.languageCode
  }

  private fun supportsSpeedControl(modelId: String?): Boolean =
    if (modelId == null) true
    else ttsServiceProvider?.serviceOrNull(forModelId = modelId)?.supportsSpeedControl != false

  /** Enables or disables automatic scrolling to keep the active sentence in view. */
  fun setAutoScrollEnabled(enabled: Boolean) {
    _isAutoScrollEnabled.value = enabled
  }

  /**
   * Re-enables automatic scrolling and triggers an immediate scroll to center the active sentence.
   */
  fun jumpToSpoken() {
    _isAutoScrollEnabled.value = true
    _scrollTrigger.value += 1
  }

  /**
   * Emits a scroll request to vertically center the sentence at [centerYInRoot] in the viewport.
   */
  fun requestCenterInRootY(centerYInRoot: Float) {
    nextScrollRequestSeq += 1
    _scrollRequest.value = ScrollRequest(seq = nextScrollRequestSeq, centerYInRoot = centerYInRoot)
  }

  /**
   * Speaks [text] from the beginning, replacing anything currently playing.
   *
   * Fenced code blocks and tables are not spoken.
   *
   * @param includeIncompleteTail whether trailing text with no sentence-ending punctuation — a
   *   bullet, a heading — is spoken as a final sentence. Playing a complete message wants `true`;
   *   [streamAutoPlay] manages this itself while a response is still arriving.
   */
  fun startPlayback(
    messageId: String,
    text: String,
    includeIncompleteTail: Boolean = true,
    capabilityName: String = "llm_chat",
  ) {
    dismissedMessageIds.remove(messageId)
    val sentences = segmentSpeakableText(text, includeIncompleteTail).sentences
    restartWith(messageId, sentences)
  }

  /**
   * Speaks an assistant message that is still streaming in, extending the utterance as it grows.
   *
   * Unlike [startPlayback], repeated calls for the same [messageId] do not restart playback: the
   * text received so far is re-segmented in place, and if the voice has already caught up with the
   * model it continues into the sentences that arrived since. Text after the last complete sentence
   * is published as [inFlightTailText] rather than spoken, until [isDone] reports the message
   * complete and that fragment becomes a sentence of its own.
   *
   * When a single assistant turn emits multiple text messages (for example after a tool/skill call
   * or thinking block) while an auto-play session is active:
   * - If the active auto-play message has already finished playing ([TtsPlaybackState.ENDED]),
   *   playback switches immediately to the new message.
   * - Otherwise, the new message is queued and automatically starts as soon as the active message
   *   finishes its remaining sentences.
   * - Manually started sessions ([startPlayback]) are never hijacked by auto-play, and dismissing
   *   during an auto-play turn also dismisses any queued follow-up message.
   *
   * @param messageId stable id of the message being streamed.
   * @param text the whole message content accumulated so far, not just the newest token.
   * @param isDone whether the model has finished producing the message.
   */
  fun streamAutoPlay(
    messageId: String,
    text: String,
    isDone: Boolean,
    capabilityName: String = "llm_chat",
  ) {
    if (messageId in dismissedMessageIds) return
    if (isAppInBackground) {
      // Don't start speaking in the background, and don't pick this message up on return either.
      dismissedMessageIds.add(messageId)
      return
    }
    if (_activeMessageId.value != null && _activeMessageId.value != messageId) {
      if (!isAutoPlaySession) {
        dismissedMessageIds.add(messageId)
        return
      }
      if (_playbackState.value != TtsPlaybackState.ENDED) {
        queuedAutoPlayMessage =
          QueuedAutoPlayMessage(
            messageId = messageId,
            text = text,
            isDone = isDone,
            capabilityName = capabilityName,
          )
        return
      }
      queuedAutoPlayMessage = null
    }
    streamingMessageId = if (isDone) null else messageId
    val speakable = segmentSpeakableText(text, includeIncompleteTail = isDone)
    val chunks = speakable.sentences

    if (_activeMessageId.value != messageId) {
      if (chunks.isEmpty()) return
      restartWith(messageId, chunks, isAutoPlay = true)
    } else {
      _sentences.value = chunks
      val pendingPrefetchIndex = waitingToPrefetchIndex
      val currentService = activeService
      if (
        _playbackState.value == TtsPlaybackState.PLAYING &&
          pendingPrefetchIndex != null &&
          pendingPrefetchIndex < chunks.size &&
          currentService != null
      ) {
        waitingToPrefetchIndex = null
        startPrefetchIfEligible(pendingPrefetchIndex, currentService, buildCurrentConfig())
      }
      // The voice ran out of text before the model did; resume into what has since arrived.
      val nextIndex = _currentSentenceIndex.value + 1
      if (
        _playbackState.value == TtsPlaybackState.ENDED &&
          completedAllSentencesNaturally &&
          nextIndex < chunks.size
      ) {
        playSentence(nextIndex, notifyStart = false)
      }
    }

    _inFlightTailText.value = speakable.incompleteTail?.let { text.substring(it.start, it.end) }
  }

  /**
   * Replays [sentences] for the active message from sentence 0, resetting per-playback retry state.
   */
  private fun replayActiveMessage() {
    if (_activeMessageId.value != null && _sentences.value.isNotEmpty()) {
      hasRecreatedClientForServiceErrorThisPlayback = false
      playbackSelection = null
      playSentence(0)
    }
  }

  /** Toggles between [pause] and [resume], or replays from the first sentence if finished. */
  fun togglePlayPause() {
    when (_playbackState.value) {
      TtsPlaybackState.PLAYING -> pause()
      TtsPlaybackState.PAUSED -> resume()
      TtsPlaybackState.ENDED,
      TtsPlaybackState.STOPPED -> replayActiveMessage()
      TtsPlaybackState.IDLE -> {}
    }
  }

  /** Pauses playback and abandons audio focus. Does nothing unless playback is in progress. */
  fun pause() {
    if (_playbackState.value != TtsPlaybackState.PLAYING) return
    _playbackState.value = TtsPlaybackState.PAUSED
    onAudioThread { it.pause() }
    activeService?.pause()
    audioFocusController.abandonFocus()
  }

  /**
   * Resumes playback for the active message.
   *
   * Continues from the paused position when [TtsPlaybackState.PAUSED], restarts from sentence 0
   * when [TtsPlaybackState.ENDED] or [TtsPlaybackState.STOPPED], or starts at
   * [currentSentenceIndex] when [TtsPlaybackState.IDLE].
   */
  fun resume() {
    if (_activeMessageId.value == null) return
    when (_playbackState.value) {
      TtsPlaybackState.PAUSED -> {
        notifyPlaybackStarted()
        audioFocusController.requestFocus(onFocusLost = ::pause)
        _isExpanded.value = true
        _playbackState.value = TtsPlaybackState.PLAYING
        val speed = _playbackSpeed.value
        onAudioThread { sink ->
          sink.play()
          sink.setSpeed(speed)
        }
        activeService?.resume()
      }
      TtsPlaybackState.ENDED,
      TtsPlaybackState.STOPPED -> replayActiveMessage()
      TtsPlaybackState.IDLE -> playSentence(_currentSentenceIndex.value)
      TtsPlaybackState.PLAYING -> {}
    }
  }

  /** Skips forward to the next sentence if one exists. */
  fun skipNext() {
    val nextIndex = _currentSentenceIndex.value + 1
    if (nextIndex < _sentences.value.size) {
      seekToSentence(nextIndex)
    }
  }

  /** Skips backward to the previous sentence if one exists. */
  fun skipPrev() {
    val prevIndex = _currentSentenceIndex.value - 1
    if (prevIndex >= 0) {
      seekToSentence(prevIndex)
    }
  }

  /** Seeks playback to [index] within [sentences] and re-enables auto-scroll. */
  fun seekToSentence(index: Int) {
    if (index !in _sentences.value.indices) return
    if (prefetchedSentence?.index == index) {
      onAudioThread { sink ->
        sink.pause()
        sink.flush()
      }
    } else {
      discardPrefetch()
    }
    _isAutoScrollEnabled.value = true
    playSentence(index)
  }

  /**
   * Sets the playback rate, retiming the sentence that is already playing.
   *
   * Only the streaming engine is affected here: it hands back audio rendered at a fixed rate, so
   * the rate lives on the [PcmAudioSink]. Engines that play the audio themselves apply
   * [TtsConfig.speed] instead, and pick the new rate up on the next sentence.
   */
  fun setSpeed(speed: Float) {
    if (!isSameSpeed(_playbackSpeed.value, speed)) {
      discardPrefetch()
    }
    _playbackSpeed.value = speed
    onAudioThread { it.setSpeed(speed) }
  }

  /**
   * Stops playback, releases the active audio sink, and clears the active message state.
   *
   * Unlike [dismiss], this does not block [streamAutoPlay] from speaking a streaming message.
   */
  fun stop() {
    stopLocalPlaybackOnly()
    val provider = ttsServiceProvider
    if (provider != null) {
      provider.unregisterOnStopAllListener(onExternalStopAllListener)
      try {
        provider.stopAll()
      } finally {
        provider.registerOnStopAllListener(onExternalStopAllListener)
      }
    } else {
      activeService?.stop()
    }
  }

  private fun stopLocalPlaybackOnly() {
    playbackGeneration += 1
    completedAllSentencesNaturally = false
    isAutoPlaySession = false
    queuedAutoPlayMessage = null
    discardPrefetch()
    cancelCurrentSynthesis()
    scope.launch(audioDispatcher) { releaseActiveSink() }
    audioFocusController.abandonFocus()

    _activeMessageId.value = null
    streamingMessageId = null
    _playbackState.value = TtsPlaybackState.STOPPED
    _currentSentenceIndex.value = 0
    _currentWordIndex.value = null
    _sentences.value = emptyList()
    _inFlightTailText.value = null
    _isExpanded.value = false
    _isAutoScrollEnabled.value = true
    _selectionNotice.value = null
    playbackSelection = null
  }

  /** Stops playback at the user's request, and keeps [streamAutoPlay] from restarting it. */
  fun dismiss() {
    _activeMessageId.value?.let { dismissedMessageIds.add(it) }
    streamingMessageId?.let { dismissedMessageIds.add(it) }
    queuedAutoPlayMessage?.messageId?.let { dismissedMessageIds.add(it) }
    stop()
  }

  /**
   * Stops playback when the hosting [TtsPlaybackViewModel] is cleared, while keeping neural TTS
   * engines loaded and keeping [scope] alive across screens because this controller is a
   * process-wide `@Singleton`.
   *
   * Neural engines are released only via [TtsServiceProvider.releaseInactiveNeuralEngines] on model
   * deletion, engine switch, or background memory pressure ([TtsMemoryTrimCallbacks.onTrimMemory]).
   */
  fun destroy() {
    stop()
  }

  private fun notifyPlaybackStarted() {
    val unused = _playbackStarts.tryEmit(Unit)
  }

  /** Stops whatever is playing and speaks [chunks] for [messageId] from the first sentence. */
  private fun restartWith(messageId: String, chunks: List<TextChunk>, isAutoPlay: Boolean = false) {
    stop()
    if (chunks.isEmpty()) return

    isAutoPlaySession = isAutoPlay
    playbackRequestMark = TimeSource.Monotonic.markNow()
    lastSentenceDrainMark = null
    hasRecreatedClientForServiceErrorThisPlayback = false
    _activeMessageId.value = messageId
    _sentences.value = chunks
    playSentence(0)
  }

  private fun playSentence(
    index: Int,
    flushSink: Boolean = true,
    notifyStart: Boolean = flushSink,
  ) {
    val list = _sentences.value
    if (index !in list.indices) {
      finishPlayback()
      return
    }
    if (notifyStart) {
      notifyPlaybackStarted()
    }

    val keepPrefetch = prefetchedSentence?.index == index
    cancelCurrentSynthesis(keepPrefetch = keepPrefetch, flushSink = flushSink && !keepPrefetch)
    val generation = ++playbackGeneration
    completedAllSentencesNaturally = false
    audioFocusController.requestFocus(onFocusLost = ::pause)
    val selectedModelId =
      playbackSelection?.engineId
        ?: speechSelectionUseCase?.effectiveTtsSelection()?.engineId
        ?: ttsSettingsRepository?.selectedTtsModelID?.value
        ?: TtsModelIdentifier.ANDROID_NATIVE
    _currentSentenceIndex.value = index
    _currentWordIndex.value =
      if (_currentWordIndex.value != null || isWordHighlightModel(selectedModelId)) 0 else null
    _playbackState.value = TtsPlaybackState.PLAYING
    _isExpanded.value = true

    val chunk = list[index]
    synthesisJob = scope.launch(mainDispatcher) { speak(chunk, index, generation) }
  }

  private fun isWordHighlightModel(modelId: String?): Boolean =
    modelId == TtsModelIdentifier.MEDIAPIPE_KOKORO_82M ||
      modelId == TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M ||
      modelId == TtsModelIdentifier.MEDIAPIPE_PHONON_31M

  private fun finishPlayback(completedNaturally: Boolean = false) {
    playbackGeneration += 1
    completedAllSentencesNaturally = completedNaturally
    discardPrefetch()
    _currentWordIndex.value = null
    _playbackState.value = TtsPlaybackState.ENDED
    onAudioThread { it.pause() }
    audioFocusController.abandonFocus()
  }

  private fun buildCurrentConfig(): TtsConfig {
    val selectedModelId =
      ttsSettingsRepository?.selectedTtsModelID?.value ?: TtsModelIdentifier.ANDROID_NATIVE
    val selectedVoice = ttsSettingsRepository?.selectedTtsVoice?.value?.ifEmpty { null }
    val selectedLanguageCode = ttsSettingsRepository?.selectedLanguageCode?.value?.ifEmpty { null }
    return TtsConfig(
      modelId = selectedModelId,
      languageCode = selectedLanguageCode,
      voiceId = selectedVoice,
      speed = _playbackSpeed.value,
    )
  }

  /** Synthesizes and plays [chunk], then advances to the next sentence. Runs on the main thread. */
  private suspend fun speak(chunk: TextChunk, index: Int, generation: Long) {
    preloadJob?.join()
    if (generation != playbackGeneration) return
    val provider =
      ttsServiceProvider
        ?: run {
          _currentWordIndex.value = null
          _playbackState.value = TtsPlaybackState.IDLE
          _isExpanded.value = false
          audioFocusController.abandonFocus()
          return
        }
    var config: TtsConfig? = null
    var isPreparing: Boolean
    // Reconnecting after the speech service stopped runs at most once per sentence.
    var hasRecreatedClient = false
    // The engine-specific retries of this sentence ([TtsService.preStartRetryDelaysMs]).
    val preStartRetries = PreStartRetries()
    var sentenceService: TtsService? = null
    var playbackStarted: Boolean
    var hasRetriedEarlyDirectClose = false
    while (true) {
      isPreparing = false
      playbackStarted = false
      try {
        val selection = playbackSelection ?: resolveSelection().also { playbackSelection = it }
        if (generation != playbackGeneration) return
        _selectionNotice.value = selection.notice
        val sentenceConfig = selection.toTtsConfig()
        config = sentenceConfig

        val service = provider.service(forConfig = sentenceConfig)
        sentenceService = service
        _isSpeedControlSupported.value = service.supportsSpeedControl
        if (activeService != null && activeService !== service) {
          activeService?.stop()
        }
        activeService = service

        if (!isPrepared(service, sentenceConfig)) {
          // Any prefetch queued before the engine was released or reconfigured may be bound to a
          // closed synthesizer (or was cancelled mid-synthesis), so discard it even if already
          // buffered and re-synthesize on the freshly prepared engine.
          discardPrefetch()
          isPreparing = true
          withContext(ioDispatcher) { service.prepare(sentenceConfig) }
          isPreparing = false
          if (generation != playbackGeneration) return
          markPrepared(sentenceConfig)
        }

        // The user may have paused while the engine was preparing.
        awaitNotPaused()
        if (generation != playbackGeneration) return
        val effectiveConfig = sentenceConfig.copy(speed = _playbackSpeed.value)
        val prefetched = takeMatchingPrefetch(index, chunk.cleanSpokenText, effectiveConfig)
        if (prefetched != null) {
          try {
            withContext(audioDispatcher) {
              playPcmStream(
                pcm = prefetched.chunks.receiveAsFlow(),
                wordTimestamps = prefetched.wordTimestamps,
                sentenceIndex = index,
                generation = generation,
                onSynthesisFinished = {
                  prefetched.producerJob.join()
                  withContext(mainDispatcher) {
                    if (generation == playbackGeneration) {
                      startPrefetchIfEligible(index + 1, service, effectiveConfig)
                    }
                  }
                },
              )
            }
          } finally {
            prefetched.producerJob.cancel()
            prefetched.chunks.cancel()
          }
        } else {
          val output = service.synthesize(chunk.cleanSpokenText, effectiveConfig)
          when (output) {
            is TtsOutput.AudioStream ->
              withContext(audioDispatcher) {
                playPcmStream(
                  pcm = output.flow,
                  wordTimestamps = output.wordTimestamps,
                  sentenceIndex = index,
                  generation = generation,
                  onSynthesisFinished = {
                    withContext(mainDispatcher) {
                      if (generation == playbackGeneration) {
                        startPrefetchIfEligible(index + 1, service, effectiveConfig)
                      }
                    }
                  },
                )
              }
            is TtsOutput.DirectPlayback -> {
              _currentWordIndex.value = null
              val completed =
                output.statusFlow.flowOn(ioDispatcher).firstOrNull {
                  if (it == TtsPlaybackEvent.PLAYBACK_STARTED) {
                    playbackStarted = true
                    consecutiveFailures = 0
                  }
                  it == TtsPlaybackEvent.PLAYBACK_COMPLETED
                }
              if (generation != playbackGeneration) return
              if (completed == null) {
                if (!hasRetriedEarlyDirectClose) {
                  hasRetriedEarlyDirectClose = true
                  continue
                }
                finishPlayback(completedNaturally = false)
                return
              }
            }
          }
        }
        consecutiveFailures = 0
        break
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation != playbackGeneration) return
        // The engine's own retries for a failure before any audio played; these don't count as
        // failures unless they run out.
        val retryDelayMs =
          if (isPreparing || playbackStarted) {
            null
          } else {
            sentenceService?.let { preStartRetries.nextDelayMs(it, e) }
          }
        if (retryDelayMs != null) {
          Log.w(
            TAG,
            "TTS sentence $index failed before it started; retrying in $retryDelayMs ms",
            e,
          )
          delay(retryDelayMs)
          continue
        }
        when (e) {
          is TtsServiceError.VoiceNotInstalledYet -> {
            Log.e(TAG, "The voice for sentence $index isn't listed", e)
            // Prepare again before a future playback, so the engine warms the voice up again.
            forgetPreparedConfig()
            val failedConfig = config
            val engineId = failedConfig?.modelId
            if (engineId != null) {
              speechSelectionUseCase?.onTtsVoiceUnavailable(engineId, failedConfig.languageCode)
            }
            playbackSelection = null
            finishPlayback(completedNaturally = false)
            return
          }
          is TtsServiceError.ServiceDisconnected -> {
            val engineId = config?.modelId
            if (!hasRecreatedClient) {
              hasRecreatedClient = true
              Log.w(TAG, "The speech service stopped during sentence $index; reconnecting once", e)
              recreateActiveService()
              continue
            }
            Log.e(TAG, "The speech service stopped again during sentence $index", e)
            if (engineId != null) {
              speechSelectionUseCase?.reportTtsEngineError(
                engineId,
                EngineErrorReason.SERVICE_DISCONNECTED,
              )
            }
            playbackSelection = null
            finishPlayback(completedNaturally = false)
            return
          }
          else -> {
            if (
              e is TtsServiceError.SynthesisFailed &&
                e.errorCode == TextToSpeech.ERROR_SERVICE &&
                !hasRecreatedClientForServiceErrorThisPlayback
            ) {
              // Reconnect once per playback after a service error; a repeat in the same playback
              // counts as an ordinary synthesis failure.
              hasRecreatedClientForServiceErrorThisPlayback = true
              Log.w(TAG, "The TTS engine's service failed during sentence $index; reconnecting", e)
              recreateActiveService()
              continue
            }
            Log.e(TAG, "TTS synthesis failed for sentence $index", e)
            onEngineFailure(
              engineId = config?.modelId,
              reason =
                if (isPreparing) {
                  EngineErrorReason.INITIALIZATION_FAILED
                } else {
                  EngineErrorReason.SYNTHESIS_FAILED
                },
            )
            finishPlayback(completedNaturally = false)
            return
          }
        }
      }
    }

    // Hold the next sentence back if the user paused just as this one finished.
    awaitNotPaused()
    onSentenceFinished(index, generation)
  }

  /**
   * The engine, voice, and language to speak with: the saved ones while the saved engine is ready,
   * otherwise the system voice with a notice.
   */
  private suspend fun resolveSelection(): EffectiveTtsSelection {
    speechSelectionUseCase?.let {
      return it.resolveEffectiveTtsSelection()
    }
    val engineId =
      ttsSettingsRepository?.selectedTtsModelID?.value ?: TtsModelIdentifier.ANDROID_NATIVE
    val savedLang = ttsSettingsRepository?.selectedLanguageCode?.value.orEmpty()
    return EffectiveTtsSelection(
      engineId = engineId,
      voiceId = ttsSettingsRepository?.selectedTtsVoice?.value.orEmpty(),
      languageCode =
        if (TtsModelIdentifier.isSystemEngine(engineId)) savedLang
        else savedLang.ifEmpty { DEFAULT_TTS_LANGUAGE_CODE },
    )
  }

  /** Makes the next sentence call [TtsService.prepare] again, even for the same configuration. */
  private fun forgetPreparedConfig() {
    lastPreparedModelId = null
    lastPreparedVoiceId = null
    lastPreparedLanguageCode = null
  }

  /** Releases the active engine's client so that the next sentence prepares a new one. */
  private suspend fun recreateActiveService() {
    val service = activeService
    forgetPreparedConfig()
    if (service != null) {
      try {
        withContext(ioDispatcher) { service.destroy() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to release the TTS engine before reconnecting", e)
      }
    }
  }

  /**
   * Counts a failed sentence of [engineId], and reports the engine for [reason] once it failed
   * [MAX_CONSECUTIVE_ENGINE_FAILURES] times in a row.
   */
  private fun onEngineFailure(engineId: String?, reason: EngineErrorReason) {
    if (engineId != lastFailedEngineId) {
      lastFailedEngineId = engineId
      consecutiveFailures = 0
    }
    consecutiveFailures += 1
    if (consecutiveFailures < MAX_CONSECUTIVE_ENGINE_FAILURES || engineId == null) {
      return
    }
    consecutiveFailures = 0
    playbackSelection = null
    speechSelectionUseCase?.reportTtsEngineError(engineId, reason)
  }

  /**
   * Starts synthesizing sentence [nextIndex] into [prefetchedSentence] while sentence `nextIndex -
   * 1` finishes playing out on [audioDispatcher].
   */
  private fun startPrefetchIfEligible(nextIndex: Int, service: TtsService, config: TtsConfig) {
    if (_playbackState.value != TtsPlaybackState.PLAYING) return
    val list = _sentences.value
    if (nextIndex !in list.indices) {
      waitingToPrefetchIndex = nextIndex
      return
    }
    waitingToPrefetchIndex = null
    if (prefetchedSentence?.index == nextIndex) return
    discardPrefetch()

    val nextText = list[nextIndex].cleanSpokenText
    val nextOutput = service.synthesize(nextText, config)
    if (nextOutput !is TtsOutput.AudioStream) return

    val channel = Channel<ByteArray>(capacity = PREFETCH_BUFFER_CAPACITY)
    val job =
      scope.launch(ioDispatcher) {
        try {
          nextOutput.flow.collect { data ->
            if (data.isNotEmpty()) {
              channel.send(data)
            }
          }
          channel.close()
        } catch (e: CancellationException) {
          channel.close(e)
          throw e
        } catch (e: Exception) {
          channel.close(e)
        }
      }
    prefetchedSentence =
      PrefetchedSentence(
        index = nextIndex,
        text = nextText,
        modelId = config.modelId,
        voiceId = config.voiceId,
        languageCode = config.languageCode,
        speed = config.speed ?: _playbackSpeed.value,
        service = service,
        chunks = channel,
        wordTimestamps = nextOutput.wordTimestamps,
        producerJob = job,
      )
  }

  private fun takeMatchingPrefetch(
    index: Int,
    text: String,
    config: TtsConfig,
  ): PrefetchedSentence? {
    val current = prefetchedSentence ?: return null
    prefetchedSentence = null
    if (
      current.index == index &&
        current.text == text &&
        current.modelId == config.modelId &&
        current.voiceId == config.voiceId &&
        current.languageCode == config.languageCode &&
        isSameSpeed(current.speed, config.speed ?: _playbackSpeed.value)
    ) {
      return current
    }
    cancelPrefetch(current)
    return null
  }

  private fun discardPrefetch() {
    waitingToPrefetchIndex = null
    val current = prefetchedSentence ?: return
    prefetchedSentence = null
    cancelPrefetch(current)
  }

  private fun cancelPrefetch(prefetch: PrefetchedSentence) {
    prefetch.service.cancel()
    prefetch.producerJob.cancel()
    prefetch.chunks.cancel()
  }

  /**
   * Streams [pcm] into the active sink and returns once it has been played out. Runs on
   * [audioDispatcher].
   */
  private suspend fun playPcmStream(
    pcm: Flow<ByteArray>,
    wordTimestamps: List<TtsWordTimestamp> = emptyList(),
    sentenceIndex: Int = 0,
    generation: Long = playbackGeneration,
    onSynthesisFinished: suspend () -> Unit = {},
  ) = coroutineScope {
    awaitNotPaused()
    val sink = activeSink ?: audioSinkFactory().also { activeSink = it }
    sink.setSpeed(_playbackSpeed.value)
    val sentenceStartFrame = sink.writtenFrames

    var loggedFirstChunk = false
    var synthesisFinishedCalled = false
    var highWaterWordIndex = -1

    val chunkChannel = Channel<ByteArray>(capacity = PREFETCH_BUFFER_CAPACITY)
    val producerJob =
      launch(ioDispatcher) {
        try {
          pcm.collect { data ->
            if (data.isNotEmpty()) {
              chunkChannel.send(data)
            }
          }
          chunkChannel.close()
        } catch (e: CancellationException) {
          chunkChannel.close(e)
          throw e
        } catch (e: Throwable) {
          chunkChannel.close(e)
        }
      }

    try {
      var lastPolledHead = sink.playbackHeadFrames
      var pollStalledMs = 0L
      while (true) {
        val next =
          if (
            loggedFirstChunk &&
              (highWaterWordIndex >= 0 || wordTimestamps.isNotEmpty()) &&
              sink.playbackHeadFrames < sink.writtenFrames &&
              pollStalledMs < DRAIN_STALL_TIMEOUT_MS
          ) {
            awaitNotPaused()
            withTimeoutOrNull(DRAIN_POLL_INTERVAL_MS) { chunkChannel.receiveCatching() }
          } else {
            chunkChannel.receiveCatching()
          }
        if (next == null) {
          val head = sink.playbackHeadFrames
          highWaterWordIndex =
            updateActiveWordForFrame(
              wordTimestamps = wordTimestamps,
              elapsedSentenceFrames = (head - sentenceStartFrame).coerceAtLeast(0L),
              generation = generation,
              highWaterWordIndex = highWaterWordIndex,
              keepInitialZeroWhileEmpty = !producerJob.isCompleted,
            )
          pollStalledMs = if (head > lastPolledHead) 0L else pollStalledMs + DRAIN_POLL_INTERVAL_MS
          lastPolledHead = head
          continue
        }
        if (next.isClosed) {
          next.exceptionOrNull()?.let { throw it }
          break
        }
        val data = next.getOrThrow()
        var offset = 0
        while (offset < data.size) {
          currentCoroutineContext().ensureActive()
          awaitNotPaused()
          val written = sink.write(data, offset, data.size - offset)
          if (written < 0) {
            // The sink is unusable; let the next sentence start over with a fresh one.
            releaseActiveSink()
            throw IllegalStateException("Audio sink write failed with error $written")
          }
          if (!loggedFirstChunk) {
            sink.play()
            sink.setSpeed(_playbackSpeed.value)
            loggedFirstChunk = true
            val requestMark = playbackRequestMark
            val drainMark = lastSentenceDrainMark
            when {
              sentenceIndex == 0 && requestMark != null -> {
                Log.d(TAG, "TTFA=${requestMark.elapsedNow().inWholeMilliseconds}ms (sentence 0)")
                playbackRequestMark = null
              }
              sentenceIndex > 0 && drainMark != null -> {
                Log.d(
                  TAG,
                  "InterSentenceGap=${drainMark.elapsedNow().inWholeMilliseconds}ms (sentence $sentenceIndex)",
                )
                lastSentenceDrainMark = null
              }
            }
          }
          val headAfterWrite = sink.playbackHeadFrames
          if (headAfterWrite > lastPolledHead) {
            pollStalledMs = 0L
          }
          lastPolledHead = headAfterWrite
          highWaterWordIndex =
            updateActiveWordForFrame(
              wordTimestamps = wordTimestamps,
              elapsedSentenceFrames = (headAfterWrite - sentenceStartFrame).coerceAtLeast(0L),
              generation = generation,
              highWaterWordIndex = highWaterWordIndex,
              keepInitialZeroWhileEmpty = !producerJob.isCompleted,
            )
          if (written == 0) {
            if (!synthesisFinishedCalled && producerJob.isCompleted) {
              synthesisFinishedCalled = true
              onSynthesisFinished()
            }
            delay(WRITE_RETRY_DELAY_MS)
          } else {
            offset += written
          }
        }
      }
    } catch (e: Throwable) {
      producerJob.cancel()
      chunkChannel.cancel()
      throw e
    }

    val targetHead = sink.writtenFrames
    if (!synthesisFinishedCalled && sink.playbackHeadFrames < targetHead) {
      synthesisFinishedCalled = true
      onSynthesisFinished()
    }

    awaitPlaybackDrained(
      sink = sink,
      targetHead = targetHead,
      sentenceStartFrame = sentenceStartFrame,
      wordTimestamps = wordTimestamps,
      generation = generation,
      initialHighWaterWordIndex = highWaterWordIndex,
    )
    lastSentenceDrainMark = TimeSource.Monotonic.markNow()
  }

  /**
   * Updates [currentWordIndex] to the word span active at [elapsedSentenceFrames], or `null` when
   * [wordTimestamps] is empty, and returns the updated per-sentence high-water word index.
   */
  private fun updateActiveWordForFrame(
    wordTimestamps: List<TtsWordTimestamp>,
    elapsedSentenceFrames: Long,
    generation: Long,
    highWaterWordIndex: Int,
    keepInitialZeroWhileEmpty: Boolean = false,
  ): Int {
    if (generation != playbackGeneration) return highWaterWordIndex
    val timestamps = wordTimestamps.toList()
    if (timestamps.isEmpty()) {
      if (
        !keepInitialZeroWhileEmpty &&
          highWaterWordIndex < 0 &&
          _currentWordIndex.value != null &&
          generation == playbackGeneration
      ) {
        _currentWordIndex.value = null
      }
      return highWaterWordIndex
    }
    val effectiveFrames =
      if (
        elapsedSentenceFrames > 0L && timestamps.last().endFrame >= WORD_HIGHLIGHT_LEAD_FRAMES * 4
      ) {
        elapsedSentenceFrames + WORD_HIGHLIGHT_LEAD_FRAMES
      } else {
        elapsedSentenceFrames
      }
    var activeIdx = 0
    for (i in timestamps.indices) {
      if (effectiveFrames >= timestamps[i].startFrame) {
        activeIdx = i
      } else {
        break
      }
    }
    val nextIdx = maxOf(activeIdx, highWaterWordIndex.coerceIn(0, timestamps.lastIndex))
    if (generation == playbackGeneration && _currentWordIndex.value != nextIdx) {
      _currentWordIndex.value = nextIdx
    }
    return nextIdx
  }

  /** Waits until [sink] has played up to [targetHead], or its playback head stops advancing. */
  private suspend fun awaitPlaybackDrained(
    sink: PcmAudioSink,
    targetHead: Long,
    sentenceStartFrame: Long = 0L,
    wordTimestamps: List<TtsWordTimestamp> = emptyList(),
    generation: Long = playbackGeneration,
    initialHighWaterWordIndex: Int = -1,
  ) {
    var highWaterWordIndex = initialHighWaterWordIndex
    var lastHead = sink.playbackHeadFrames
    highWaterWordIndex =
      updateActiveWordForFrame(
        wordTimestamps = wordTimestamps,
        elapsedSentenceFrames = (lastHead - sentenceStartFrame).coerceAtLeast(0L),
        generation = generation,
        highWaterWordIndex = highWaterWordIndex,
      )
    var stalledMs = 0L
    while (lastHead < targetHead) {
      awaitNotPaused()
      delay(DRAIN_POLL_INTERVAL_MS)
      val head = sink.playbackHeadFrames
      highWaterWordIndex =
        updateActiveWordForFrame(
          wordTimestamps = wordTimestamps,
          elapsedSentenceFrames = (head - sentenceStartFrame).coerceAtLeast(0L),
          generation = generation,
          highWaterWordIndex = highWaterWordIndex,
        )
      stalledMs = if (head > lastHead) 0L else stalledMs + DRAIN_POLL_INTERVAL_MS
      lastHead = head
      if (stalledMs >= DRAIN_STALL_TIMEOUT_MS) {
        Log.w(TAG, "Audio playback stalled at frame $head of $targetHead")
        return
      }
    }
  }

  private suspend fun awaitNotPaused() {
    _playbackState.first { it != TtsPlaybackState.PAUSED }
  }

  private fun onSentenceFinished(finishedIndex: Int, generation: Long) {
    if (generation != playbackGeneration) return
    if (finishedIndex != _currentSentenceIndex.value) return
    if (_playbackState.value != TtsPlaybackState.PLAYING) return

    val nextIndex = finishedIndex + 1
    if (nextIndex < _sentences.value.size) {
      playSentence(nextIndex, flushSink = false)
    } else {
      finishPlayback(completedNaturally = true)
      if (isAutoPlaySession) {
        startQueuedAutoPlayMessageIfAvailable()
      }
    }
  }

  private fun startQueuedAutoPlayMessageIfAvailable() {
    val queued = queuedAutoPlayMessage ?: return
    queuedAutoPlayMessage = null
    streamAutoPlay(
      messageId = queued.messageId,
      text = queued.text,
      isDone = queued.isDone,
      capabilityName = queued.capabilityName,
    )
  }

  private fun cancelCurrentSynthesis(
    keepPrefetch: Boolean = false,
    flushSink: Boolean = !keepPrefetch,
  ) {
    synthesisJob?.cancel()
    synthesisJob = null
    if (!keepPrefetch) {
      discardPrefetch()
      val service = activeService
      if (service != null && TtsModelIdentifier.ML_KIT !in service.servedModelIds) {
        service.cancel()
      }
    }
    if (flushSink) {
      // Drop audio queued for the cancelled sentence. This runs after any in-flight write, since
      // the audio dispatcher is single-threaded.
      onAudioThread { sink ->
        sink.pause()
        sink.flush()
      }
    }
  }

  /** Runs [action] on the active sink, if any, on [audioDispatcher]. */
  private fun onAudioThread(action: (PcmAudioSink) -> Unit) {
    scope.launch(audioDispatcher) {
      val sink = activeSink ?: return@launch
      try {
        action(sink)
      } catch (e: RuntimeException) {
        Log.w(TAG, "Audio sink operation failed", e)
      }
    }
  }

  /** Must be called on [audioDispatcher]. */
  private fun releaseActiveSink() {
    activeSink?.release()
    activeSink = null
  }
}

/** Gives chat composables the injected, process-wide [TtsPlaybackController]. */
@HiltViewModel
class TtsPlaybackViewModel @Inject constructor(val controller: TtsPlaybackController) :
  ViewModel() {
  init {
    controller.onReadAloudScreenOpened()
  }

  override fun onCleared() {
    controller.onReadAloudScreenClosed()
    controller.destroy()
  }
}
