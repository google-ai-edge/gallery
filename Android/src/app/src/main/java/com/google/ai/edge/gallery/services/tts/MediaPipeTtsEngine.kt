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
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.data.Model
import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val TAG = "AGMediaPipeTtsEngine"

/**
 * Minimal synthesis surface of MediaPipe [TextToSpeech] used by [MediaPipeTtsEngine], so tests can
 * substitute a fake without loading the native library.
 */
@VisibleForTesting
internal interface MediaPipeSynthesizer : AutoCloseable {
  /** IDs of the voices packaged in the loaded model. */
  fun listVoiceIds(): List<String>

  /** Streams mono float PCM chunks in `[-1, 1]` at 24 kHz for [text]. Blocks while collected. */
  fun synthesizeStream(text: String, options: SynthesisOptions): Flow<FloatArray>

  /**
   * Streams [AudioChunk]s (including word-level timestamps) at 24 kHz for [text]. Defaults to
   * wrapping [synthesizeStream] with empty word timestamps for test fakes.
   */
  fun synthesizeAudioChunks(text: String, options: SynthesisOptions): Flow<AudioChunk> =
    synthesizeStream(text, options).map { samples ->
      AudioChunk(samples = samples, sampleRateHz = 24000, isFinal = false)
    }

  /**
   * Refines word-level timestamps against the complete clause [samples] once all chunks of a
   * multi-chunk clause have been emitted. Defaults to `emptyList()` so test fakes without native
   * JNI preserve their per-chunk timestamps.
   */
  @Suppress("KotlinArray")
  fun computeWordTimestamps(
    text: String,
    samples: FloatArray,
    sampleRateHz: Int = 24000,
  ): List<WordTimestamp> = emptyList()

  /** Aborts any in-flight synthesis. */
  fun cancel()
}

private class TextToSpeechSynthesizer(private val tts: TextToSpeech) : MediaPipeSynthesizer {
  override fun listVoiceIds(): List<String> =
    tts.listVoices().map { it.voiceId }.filter { it.isNotEmpty() }

  override fun synthesizeStream(text: String, options: SynthesisOptions): Flow<FloatArray> =
    tts.synthesizeStream(text, options).map { it.samples }

  override fun synthesizeAudioChunks(text: String, options: SynthesisOptions): Flow<AudioChunk> =
    tts.synthesizeStream(text, options)

  override fun computeWordTimestamps(
    text: String,
    samples: FloatArray,
    sampleRateHz: Int,
  ): List<WordTimestamp> = TextToSpeech.computeWordTimestamps(text, samples, sampleRateHz)

  override fun cancel() = tts.cancel()

  override fun close() = tts.close()
}

/**
 * On-device neural [TtsService] for downloadable allowlist TTS models run by the MediaPipe Tasks
 * [TextToSpeech] task, such as Kokoro 82M, PocketTTS 100M, and Phonon 31M.
 *
 * Each served model is described by a [MediaPipeTtsModelSpec] and identified by its engine model
 * ID. The model file is the one Model Manager downloaded for the registered allowlist [Model]
 * ([TtsModelRegistry]), resolved through `Model.getPath`. One model is loaded at a time; preparing
 * another model releases the loaded one. Synthesis produces [TtsOutput.AudioStream] chunks of 24
 * kHz 16-bit mono little-endian PCM ([TtsAudioStreamFormat.STANDARD_24KHZ_MONO]).
 */
class MediaPipeTtsEngine
@VisibleForTesting
internal constructor(
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher,
  private val modelSpecs: List<MediaPipeTtsModelSpec>,
  private val synthesizerFactory: (modelPath: String, delegate: Delegate) -> MediaPipeSynthesizer,
) : TtsService {

  constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = DEFAULT_ENGINE_DISPATCHER,
  ) : this(
    context = context,
    ioDispatcher = ioDispatcher,
    modelSpecs = MODEL_SPECS,
    synthesizerFactory = { modelPath, delegate ->
      TextToSpeechSynthesizer(
        TextToSpeech.create(
          TextToSpeechOptions(
            baseOptions =
              BaseOptions.builder().setModelAssetPath(modelPath).setDelegate(delegate).build(),
            defaultVoiceId = null,
            defaultLocale = DEFAULT_TTS_LANGUAGE_CODE,
            cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY_NAME).absolutePath,
          )
        )
      )
    },
  )

  /**
   * The loaded model. [modelLastModified] and [modelSize] detect a model file that was deleted and
   * downloaded again to the same path.
   */
  private class PreparedModel(
    val spec: MediaPipeTtsModelSpec,
    val modelPath: String,
    val modelLastModified: Long,
    val modelSize: Long,
    val synthesizer: MediaPipeSynthesizer,
    val voiceIds: List<String>,
  ) {
    /**
     * The (voice, language) pairs, as [SynthesisOptions.voiceId] and [SynthesisOptions.locale],
     * that already synthesized [WARMUP_TEXT] with [synthesizer]; see [warmUpVoiceIfNeeded].
     */
    val warmedUpVoices: MutableSet<SynthesisOptions> = mutableSetOf()
  }

  private val specsById: Map<String, MediaPipeTtsModelSpec> = modelSpecs.associateBy {
    it.engineModelId
  }
  private val prepareMutex = Mutex()
  private val stateLock = Any()
  private val inFlightJobs = mutableSetOf<Job>()
  private val activeSynthesisJobs = mutableSetOf<Job>()
  /**
   * Launches [destroy]'s [release] undispatched so it synchronously cancels in-flight work and
   * claims (or queues on) [prepareMutex] before [destroy] returns, while keeping its own
   * interceptor distinct from [ioDispatcher] so [closeOnEngineThread] always dispatches `close()`
   * onto [ioDispatcher].
   */
  private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  @Volatile private var prepared: PreparedModel? = null
  @Volatile private var initializingSynthesizer: MediaPipeSynthesizer? = null
  @Volatile private var isWarmingUp: Boolean = false

  private val _status = MutableStateFlow<TtsServiceStatus>(TtsServiceStatus.NotDownloaded)
  override val status: StateFlow<TtsServiceStatus> = _status.asStateFlow()

  override val servedModelIds: Set<String> = specsById.keys

  /** The prepared model's weights and delegates live in this process. */
  override val holdsNativeWeights: Boolean = true

  /**
   * One entry per served model. Read before [prepare] runs (Model Manager, Settings and XR Language
   * Teacher list voices and languages from it), so it never requires a model to be loaded.
   *
   * Each voice is labeled with its allowlist `displayName`, else an app-localized label derived
   * from its ID ([MediaPipeTtsModels.deriveVoiceDisplayName]), else its ID; MediaPipe's own English
   * labels (such as `"Kokoro Heart (en-US)"`) are never shown. Its locale is the allowlist
   * `locale`, else the one its ID implies ([MediaPipeTtsModels.kokoroVoiceLocale]).
   */
  override val availableModels: List<TtsModelMetadata>
    get() {
      val loaded = prepared
      return modelSpecs.map { spec ->
        val model = TtsModelRegistry.model(spec.engineModelId)
        val profile = model?.ttsProfile
        val loadedForSpec = loaded?.takeIf { it.spec == spec }
        val voices =
          loadedForSpec?.voiceIds
            ?: profile?.voices?.map { it.id }.orEmpty().ifEmpty { spec.defaultVoiceIds }
        val profileVoices = profile?.voices.orEmpty().associateBy { it.id }
        val voiceLocales = buildMap {
          for (voiceId in voices) {
            val locale =
              profileVoices[voiceId]?.locale?.takeIf { it.isNotEmpty() }
                ?: spec.voiceLocale(voiceId)
            if (locale != null) {
              put(voiceId, locale)
            }
          }
        }
        val voiceDescriptions = voices.associateWith { voiceId ->
          profileVoices[voiceId]?.displayName?.takeIf { it.isNotBlank() && it != voiceId }
            ?: MediaPipeTtsModels.deriveVoiceDisplayName(context, voiceId, voiceLocales[voiceId])
            ?: voiceId
        }
        TtsModelMetadata(
          modelId = spec.engineModelId,
          displayName = model?.displayName?.ifEmpty { model.name } ?: spec.engineModelId,
          engineType = TtsEngineType.MEDIAPIPE,
          supportedLanguages = profile?.languages.orEmpty().ifEmpty { spec.defaultLanguages },
          availableVoices = voices,
          voiceDescriptions = voiceDescriptions,
          voiceLocales = voiceLocales,
        )
      }
    }

  override suspend fun checkModelAvailability(modelId: String): TtsModelAvailability {
    val available = withContext(ioDispatcher) { resolveModelFile(modelId) != null }
    if (!available) {
      _status.value = TtsServiceStatus.NotDownloaded
      return TtsModelAvailability.NotDownloaded
    }
    if (_status.value is TtsServiceStatus.NotDownloaded) {
      _status.value = TtsServiceStatus.Downloaded
    }
    return TtsModelAvailability.Downloaded
  }

  /** Model Manager downloads the allowlist file; this engine has nothing to download itself. */
  override fun downloadModel(modelId: String, expectedTotalBytes: Long): Flow<TtsDownloadProgress> =
    flow {
      if (resolveModelFile(modelId) == null) {
        throw TtsServiceError.UnsupportedOperation(
          "$modelId is downloaded from the model allowlist by Model Manager."
        )
      }
      emit(
        TtsDownloadProgress(downloadedBytes = expectedTotalBytes, totalBytes = expectedTotalBytes)
      )
    }

  /**
   * Releases [modelId] if it is loaded, or the loaded model when [modelId] is empty; Model Manager
   * deletes the downloaded file.
   */
  override suspend fun deleteModel(modelId: String) {
    val shouldRelease =
      synchronized(stateLock) { modelId.isEmpty() || prepared?.spec?.engineModelId == modelId }
    if (shouldRelease) {
      release()
    }
  }

  override suspend fun prepare(config: TtsConfig) {
    val callerJob = currentCoroutineContext()[Job]
    callerJob?.let { synchronized(stateLock) { inFlightJobs.add(it) } }
    try {
      prepareMutex.withLock {
        val spec = specFor(config.modelId)
        if (spec == null) {
          _status.value =
            TtsServiceStatus.Failed(TtsServiceError.ModelNotFound("${config.modelId}"))
          throw TtsServiceError.ModelNotFound("${config.modelId}")
        }
        val modelFile = withContext(ioDispatcher) { resolveModelFile(spec.engineModelId) }
        if (modelFile == null) {
          releasePrepared()
          _status.value = TtsServiceStatus.NotDownloaded
          throw TtsServiceError.ModelNotFound(spec.engineModelId)
        }
        val current = prepared
        if (
          current != null &&
            current.spec == spec &&
            current.modelPath == modelFile.absolutePath &&
            current.modelLastModified == modelFile.lastModified() &&
            current.modelSize == modelFile.length()
        ) {
          warmUpVoiceIfNeeded(current, config)
          if (_status.value !is TtsServiceStatus.Synthesizing) {
            _status.value = TtsServiceStatus.Ready
          }
          return
        }
        releasePrepared()
        _status.value = TtsServiceStatus.Initializing
        try {
          val delegate =
            preferredDelegate(
              model = TtsModelRegistry.model(spec.engineModelId),
              supportsGpu = spec.supportsGpu,
            )
          val loaded =
            withContext(ioDispatcher) {
              loadAndWarmUpModel(spec, modelFile, delegate, config).also {
                synchronized(stateLock) { prepared = it }
              }
            }
          warmUpVoiceIfNeeded(loaded, config)
          _status.value = TtsServiceStatus.Ready
        } catch (e: CancellationException) {
          // The model is already published if warm-up was cancelled, so close it on the engine
          // thread instead of keeping its native allocations alive behind a Downloaded status.
          withContext(NonCancellable) { releasePrepared() }
          _status.value = TtsServiceStatus.Downloaded
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to initialize ${spec.engineModelId} from ${modelFile.absolutePath}", e)
          _status.value = TtsServiceStatus.Failed(e)
          throw TtsServiceError.EngineUnavailable(e.message ?: e.javaClass.simpleName)
        }
      }
    } finally {
      callerJob?.let { synchronized(stateLock) { inFlightJobs.remove(it) } }
    }
  }

  override fun synthesize(text: String, config: TtsConfig?): TtsOutput {
    val sanitized = sanitizeTextForMediaPipe(text)
    if (sanitized.isEmpty()) {
      return TtsOutput.AudioStream(emptyFlow())
    }
    val loaded = prepared
    if (loaded == null) {
      return TtsOutput.AudioStream(
        flow {
          throw TtsServiceError.EngineUnavailable(
            "MediaPipeTtsEngine is not prepared; call prepare() before synthesize()."
          )
        }
      )
    }
    val options =
      resolveSynthesisOptions(
        config = config,
        knownVoiceIds = loaded.voiceIds,
        localeOverridableVoiceId = loaded.spec.localeOverridableVoiceId,
        passLocaleWithExplicitVoice = loaded.spec.passLocaleWithExplicitVoice,
        voiceLocale = loaded.spec.voiceLocale,
      )
    val clauses =
      if (loaded.spec.coalesceStaticShapeClauses) {
        coalesceClausesForStaticShape(sanitized)
      } else {
        listOf(sanitized)
      }
    val wordTimestamps = CopyOnWriteArrayList<TtsWordTimestamp>()
    val pcm =
      flow {
          val collectorJob = currentCoroutineContext()[Job]
          synchronized(stateLock) {
            if (prepared !== loaded) {
              throw TtsServiceError.EngineUnavailable(
                "MediaPipeTtsEngine was released before synthesis started."
              )
            }
            collectorJob?.let {
              inFlightJobs.add(it)
              activeSynthesisJobs.add(it)
            }
          }
          try {
            prepareMutex.withLock {
              if (prepared !== loaded) {
                throw TtsServiceError.EngineUnavailable(
                  "MediaPipeTtsEngine was released before synthesis started."
                )
              }
              wordTimestamps.clear()
              var cumulativeEmittedFrames = 0L
              // Yield after claiming prepareMutex in FIFO order so an UNDISPATCHED collector on
              // ioDispatcher returns control to its caller (e.g. PcmAudioStreamPlayer.playStream)
              // before entering the blocking JNI synthesis loop.
              yield()
              for ((clauseIndex, clause) in clauses.withIndex()) {
                currentCoroutineContext().ensureActive()
                val clauseStartFrame = cumulativeEmittedFrames
                val clauseWordStartIndex = wordTimestamps.size
                var clauseHeadTrimFrames = 0L
                var isFirstChunkOfClause = true
                var clauseSampleRateHz = 24000
                val clauseTrimmedChunks = mutableListOf<FloatArray>()
                loaded.synthesizer.synthesizeAudioChunks(clause, options).collect { chunk ->
                  val samples = chunk.samples
                  if (samples.isEmpty()) return@collect
                  val trimRange =
                    trimClauseJoinSilenceRange(
                      samples = samples,
                      trimHead = clauseIndex > 0 && isFirstChunkOfClause,
                      trimTail = clauseIndex < clauses.lastIndex && chunk.isFinal,
                    )
                  if (isFirstChunkOfClause) {
                    clauseHeadTrimFrames = trimRange.first.toLong()
                  }
                  isFirstChunkOfClause = false
                  val trimmed =
                    when {
                      trimRange.first == 0 && trimRange.last + 1 == samples.size -> samples
                      trimRange.isEmpty() -> FloatArray(0)
                      else -> samples.copyOfRange(trimRange.first, trimRange.last + 1)
                    }
                  if (trimmed.isNotEmpty()) {
                    if (chunk.sampleRateHz > 0) {
                      clauseSampleRateHz = chunk.sampleRateHz
                    }
                    clauseTrimmedChunks.add(trimmed)
                    val chunkRate = clauseSampleRateHz.toLong()
                    val clauseMaxEndFrame = cumulativeEmittedFrames + trimmed.size.toLong()
                    val refinedClauseWords =
                      if (chunk.isFinal && clauseTrimmedChunks.size > 1) {
                        val totalClauseSamples = clauseTrimmedChunks.sumOf { it.size }
                        val accumulatedSamples = FloatArray(totalClauseSamples)
                        var copyOffset = 0
                        for (part in clauseTrimmedChunks) {
                          part.copyInto(accumulatedSamples, destinationOffset = copyOffset)
                          copyOffset += part.size
                        }
                        loaded.synthesizer
                          .computeWordTimestamps(
                            text = clause,
                            samples = accumulatedSamples,
                            sampleRateHz = clauseSampleRateHz,
                          )
                          .map { wt ->
                            val rawStart =
                              (wt.startSec * chunkRate.toFloat()).toLong().coerceAtLeast(0L)
                            val rawEnd =
                              (wt.endSec * chunkRate.toFloat())
                                .toLong()
                                .coerceAtLeast(rawStart + 1L)
                            val absStart =
                              (clauseStartFrame + rawStart).coerceAtMost(clauseMaxEndFrame)
                            val absEnd =
                              (clauseStartFrame + rawEnd).coerceIn(absStart, clauseMaxEndFrame)
                            TtsWordTimestamp(
                              word = wt.word,
                              startFrame = absStart,
                              endFrame = absEnd,
                            )
                          }
                      } else {
                        emptyList()
                      }
                    if (refinedClauseWords.isNotEmpty()) {
                      if (clauseWordStartIndex < wordTimestamps.size) {
                        wordTimestamps.subList(clauseWordStartIndex, wordTimestamps.size).clear()
                      }
                      wordTimestamps.addAll(refinedClauseWords)
                    } else if (chunk.wordTimestamps.isNotEmpty()) {
                      val chunkWords =
                        chunk.wordTimestamps.map { wt ->
                          val rawStart =
                            ((wt.startSec * chunkRate.toFloat()).toLong() - clauseHeadTrimFrames)
                              .coerceAtLeast(0L)
                          val rawEnd =
                            ((wt.endSec * chunkRate.toFloat()).toLong() - clauseHeadTrimFrames)
                              .coerceAtLeast(rawStart + 1L)
                          val absStart =
                            (clauseStartFrame + rawStart).coerceAtMost(clauseMaxEndFrame)
                          val absEnd =
                            (clauseStartFrame + rawEnd).coerceIn(absStart, clauseMaxEndFrame)
                          TtsWordTimestamp(word = wt.word, startFrame = absStart, endFrame = absEnd)
                        }
                      wordTimestamps.addAll(chunkWords)
                    }
                    cumulativeEmittedFrames += trimmed.size.toLong()
                    emit(floatSamplesToPcm16(trimmed))
                    // Yield after emitting so downstream AudioTrack consumers can drain the chunk
                    // immediately before the next blocking JNI read begins.
                    yield()
                  }
                }
              }
            }
          } finally {
            collectorJob?.let {
              synchronized(stateLock) {
                inFlightJobs.remove(it)
                activeSynthesisJobs.remove(it)
              }
            }
          }
        }
        .flowOn(ioDispatcher)
        .onStart { _status.value = TtsServiceStatus.Synthesizing }
        .onCompletion {
          if (prepared === loaded && _status.value is TtsServiceStatus.Synthesizing) {
            _status.value = TtsServiceStatus.Ready
          }
        }
    return TtsOutput.AudioStream(flow = pcm, wordTimestamps = wordTimestamps)
  }

  /** Pausing is handled by the caller that plays the [TtsOutput.AudioStream]. */
  override fun pause() {}

  /** Resuming is handled by the caller that plays the [TtsOutput.AudioStream]. */
  override fun resume() {}

  private suspend fun warmUp(model: PreparedModel, options: SynthesisOptions) {
    try {
      executeWarmUpSynthesis(model, options)
    } catch (e: CancellationException) {
      model.synthesizer.cancel()
      closeOnEngineThread(model.synthesizer)
      throw e
    } catch (e: Exception) {
      model.synthesizer.cancel()
      closeOnEngineThread(model.synthesizer)
      throw e
    }
  }

  /**
   * Synthesizes [WARMUP_TEXT] with the voice and language that [config] resolves to, unless [model]
   * already did. Must be called while holding [prepareMutex].
   *
   * The native engine creates a session for each voice and language the first time it synthesizes
   * with them, which delays that first utterance. Warming up here moves that cost into [prepare],
   * which callers run ahead of time (read-aloud screens preload, XR Language Teacher prepares both
   * of its languages on entry). Pairs are remembered per loaded model, so preparing again costs
   * nothing. A failed warm-up is only logged and is tried again by the next [prepare]; synthesis
   * reports real failures.
   */
  private suspend fun warmUpVoiceIfNeeded(model: PreparedModel, config: TtsConfig) {
    val options =
      resolveSynthesisOptions(
        config = config,
        knownVoiceIds = model.voiceIds,
        localeOverridableVoiceId = model.spec.localeOverridableVoiceId,
        passLocaleWithExplicitVoice = model.spec.passLocaleWithExplicitVoice,
        voiceLocale = model.spec.voiceLocale,
      )

    if (options in model.warmedUpVoices) return
    try {
      executeWarmUpSynthesis(model, options)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(
        TAG,
        "Warm-up of voice=${options.voiceId} locale=${options.locale} failed; " +
          "the next prepare() will retry",
        e,
      )
    }
  }

  private suspend fun executeWarmUpSynthesis(model: PreparedModel, options: SynthesisOptions) {
    synchronized(stateLock) { isWarmingUp = true }
    try {
      currentCoroutineContext().ensureActive()
      val warmupDuration =
        withContext(ioDispatcher) {
          measureTime {
            val stream = model.synthesizer.synthesizeStream(model.spec.warmUpText, options)
            if (model.spec.warmUpFirstChunkOnly) {
              stream.take(1).collect()
            } else {
              stream.collect()
            }
          }
        }
      model.warmedUpVoices += options
      Log.d(
        TAG,
        "Warmed up voice=${options.voiceId} locale=${options.locale} of" +
          " ${model.spec.engineModelId}: ${warmupDuration.inWholeMilliseconds}ms",
      )
    } finally {
      synchronized(stateLock) { isWarmingUp = false }
    }
  }

  /**
   * Stops active user utterance synthesis without aborting an in-flight background [prepare]
   * warm-up. Explicit lifecycle teardown ([release], [destroy]) cancels warm-up as well.
   */
  override fun stop() {
    val synthesisJobsSnapshot: List<Job>
    synchronized(stateLock) {
      if (!isWarmingUp) {
        prepared?.synthesizer?.cancel()
      }
      synthesisJobsSnapshot = activeSynthesisJobs.toList()
    }
    for (job in synthesisJobsSnapshot) {
      job.cancel()
    }
  }

  override fun cancel() {
    stop()
  }

  override suspend fun release() {
    val selfJob = currentCoroutineContext()[Job]
    val jobsSnapshot: List<Job>
    synchronized(stateLock) {
      initializingSynthesizer?.cancel()
      prepared?.synthesizer?.cancel()
      jobsSnapshot = inFlightJobs.toList()
    }
    for (job in jobsSnapshot) {
      if (job !== selfJob) {
        job.cancel()
      }
    }
    prepareMutex.withLock {
      for (job in jobsSnapshot) {
        if (job !== selfJob) {
          job.join()
        }
      }
      releasePrepared()
      _status.value = TtsServiceStatus.NotDownloaded
    }
  }

  override fun destroy() {
    _status.value = TtsServiceStatus.NotDownloaded
    teardownScope.launch(start = CoroutineStart.UNDISPATCHED) { release() }
  }

  /**
   * Closes [synthesizer] on [ioDispatcher], even if the caller is cancelled, so the native GPU
   * context is always released on the thread that created it.
   */
  private suspend fun closeOnEngineThread(synthesizer: MediaPipeSynthesizer) {
    withContext(NonCancellable + ioDispatcher) {
      try {
        synthesizer.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close the MediaPipe TextToSpeech task", e)
      }
    }
  }

  /**
   * Returns the served model for [modelId], or the currently prepared (else first) served model
   * when [modelId] is `null` or empty.
   */
  private fun specFor(modelId: String?): MediaPipeTtsModelSpec? =
    if (modelId.isNullOrEmpty()) prepared?.spec ?: modelSpecs.firstOrNull() else specsById[modelId]

  private suspend fun releasePrepared() {
    val loaded: PreparedModel?
    synchronized(stateLock) {
      loaded = prepared
      prepared = null
    }
    if (loaded == null) return
    loaded.synthesizer.cancel()
    closeOnEngineThread(loaded.synthesizer)
  }

  /**
   * Loads and warms up [modelFile], falling back from GPU to CPU when GPU initialization or warm-up
   * fails.
   */
  private suspend fun loadAndWarmUpModel(
    spec: MediaPipeTtsModelSpec,
    modelFile: File,
    preferredDelegate: Delegate,
    config: TtsConfig,
  ): PreparedModel {
    val delegatesToTry =
      if (preferredDelegate == Delegate.GPU) listOf(Delegate.GPU, Delegate.CPU)
      else listOf(Delegate.CPU)
    var lastException: Exception? = null
    for ((index, candidateDelegate) in delegatesToTry.withIndex()) {
      val hasFallback = index < delegatesToTry.lastIndex
      var candidateModel: PreparedModel? = null
      try {
        val (model, loadDuration) =
          measureTimedValue { createPreparedModel(spec, modelFile, candidateDelegate) }
        candidateModel = model
        synchronized(stateLock) { initializingSynthesizer = model.synthesizer }
        try {
          if (hasFallback) {
            val warmupOptions =
              resolveSynthesisOptions(
                config = config,
                knownVoiceIds = model.voiceIds,
                localeOverridableVoiceId = spec.localeOverridableVoiceId,
                passLocaleWithExplicitVoice = spec.passLocaleWithExplicitVoice,
                voiceLocale = spec.voiceLocale,
              )
            warmUp(model = model, options = warmupOptions)
            model.warmedUpVoices += warmupOptions
          }
        } finally {
          synchronized(stateLock) { initializingSynthesizer = null }
        }
        Log.d(
          TAG,
          "Prepared ${spec.engineModelId} on $candidateDelegate: loadModel=${loadDuration.inWholeMilliseconds}ms",
        )
        return model
      } catch (e: CancellationException) {
        candidateModel?.let { closeOnEngineThread(it.synthesizer) }
        throw e
      } catch (e: Exception) {
        candidateModel?.let { closeOnEngineThread(it.synthesizer) }
        lastException = e
        if (hasFallback) {
          Log.w(TAG, "GPU initialization failed for ${spec.engineModelId}; falling back to CPU", e)
        }
      }
    }
    throw lastException ?: IllegalStateException("Failed to load ${spec.engineModelId}")
  }

  private fun createPreparedModel(
    spec: MediaPipeTtsModelSpec,
    modelFile: File,
    delegate: Delegate,
  ): PreparedModel {
    val modelPath = modelFile.absolutePath
    val synthesizer = synthesizerFactory(modelPath, delegate)
    val voiceIds =
      try {
        // Drops native entries that aren't voices for the model, such as `zh-t2s` on Kokoro, and
        // preserves the canonical default voice order (`af_heart` first).
        val listed = synthesizer.listVoiceIds().filter(spec.voiceIdFilter)
        MediaPipeTtsModels.orderVoiceIds(listed, spec.defaultVoiceIds)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to list the voices of ${spec.engineModelId}", e)
        spec.defaultVoiceIds
      }
    return PreparedModel(
      spec = spec,
      modelPath = modelPath,
      modelLastModified = modelFile.lastModified(),
      modelSize = modelFile.length(),
      synthesizer = synthesizer,
      voiceIds = voiceIds,
    )
  }

  /** Returns the downloaded model file of the registered allowlist model [modelId], if any. */
  private fun resolveModelFile(modelId: String): File? {
    val spec = specFor(modelId) ?: return null
    val model = TtsModelRegistry.model(spec.engineModelId) ?: return null
    val path = model.getPath(context).takeIf { it.isNotEmpty() } ?: return null
    return File(path).takeIf { it.isFile && it.length() > 0L }
  }

  companion object {
    private const val CACHE_DIRECTORY_NAME = "mediapipe_tts_cache"
    @VisibleForTesting internal const val ENGINE_THREAD_NAME = "MediaPipeTtsEngine"

    /**
     * Dedicated single-OS-thread dispatcher for MediaPipe [TextToSpeech] native calls.
     *
     * Creation, warm-up, synthesis, and `close()` all run on the same OS thread because the GPU
     * context is bound to the thread that created it.
     */
    private val DEFAULT_ENGINE_DISPATCHER: CoroutineDispatcher by lazy {
      createSingleThreadDispatcher()
    }

    @VisibleForTesting
    internal fun createSingleThreadDispatcher(): ExecutorCoroutineDispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, ENGINE_THREAD_NAME).apply { isDaemon = true }
        }
        .asCoroutineDispatcher()

    /**
     * Short warm-up text synthesized inside [prepare]: on initial cold GPU load to trigger shader
     * compilation and verify that GPU inference succeeds before committing to `Delegate.GPU`, and
     * once for each new voice and language (see [warmUpVoiceIfNeeded]).
     */
    @VisibleForTesting internal const val WARMUP_TEXT = MediaPipeTtsModels.WARMUP_TEXT

    /** Leading silence kept at the start of an inner clause (5 ms at 24 kHz). */
    @VisibleForTesting internal const val CLAUSE_JOIN_MARGIN_SAMPLES = 120

    /** Trailing silence kept at the end of a non-final clause (200 ms at 24 kHz). */
    @VisibleForTesting internal const val CLAUSE_PUNCTUATION_PAUSE_SAMPLES = 4800

    @VisibleForTesting
    internal fun coalesceClausesForStaticShape(text: String): List<String> =
      coalesceClausesForStaticShapeImpl(text)

    @VisibleForTesting
    internal fun sanitizeTextForMediaPipe(text: String): String = sanitizeTextForMediaPipeImpl(text)

    @VisibleForTesting
    @Suppress("KotlinArray")
    internal fun trimClauseJoinSilenceRange(
      samples: FloatArray,
      trimHead: Boolean,
      trimTail: Boolean,
    ): IntRange = trimClauseJoinSilenceRangeImpl(samples, trimHead, trimTail)

    @VisibleForTesting
    @Suppress("KotlinArray")
    internal fun trimClauseJoinSilence(
      samples: FloatArray,
      trimHead: Boolean,
      trimTail: Boolean,
    ): FloatArray = trimClauseJoinSilenceImpl(samples, trimHead, trimTail)

    @VisibleForTesting internal val KOKORO_82M_SPEC = MediaPipeTtsModels.KOKORO_82M_SPEC

    @VisibleForTesting internal val POCKET_TTS_100M_SPEC = MediaPipeTtsModels.POCKET_TTS_100M_SPEC

    @VisibleForTesting internal val PHONON_31M_SPEC = MediaPipeTtsModels.PHONON_31M_SPEC

    @VisibleForTesting
    internal val MODEL_SPECS: List<MediaPipeTtsModelSpec> = MediaPipeTtsModels.MODEL_SPECS

    @VisibleForTesting
    internal fun preferredDelegate(model: Model?, supportsGpu: Boolean = true): Delegate =
      preferredDelegateImpl(model, supportsGpu)

    @VisibleForTesting
    internal fun resolveSynthesisOptions(
      config: TtsConfig?,
      knownVoiceIds: List<String>,
      localeOverridableVoiceId: String?,
      passLocaleWithExplicitVoice: Boolean = false,
      voiceLocale: (String) -> String?,
    ): SynthesisOptions =
      resolveSynthesisOptionsImpl(
        config = config,
        knownVoiceIds = knownVoiceIds,
        localeOverridableVoiceId = localeOverridableVoiceId,
        passLocaleWithExplicitVoice = passLocaleWithExplicitVoice,
        voiceLocale = voiceLocale,
      )

    @VisibleForTesting
    internal fun isCompatibleLocale(voiceLocale: String, requestedLocale: String): Boolean =
      isCompatibleLocaleImpl(voiceLocale, requestedLocale)

    @VisibleForTesting
    @Suppress("KotlinArray")
    internal fun floatSamplesToPcm16(samples: FloatArray): ByteArray =
      floatSamplesToPcm16Impl(samples)
  }
}
