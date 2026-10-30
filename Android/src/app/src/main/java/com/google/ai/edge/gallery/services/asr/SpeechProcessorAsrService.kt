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

import android.content.Context
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSpeechProcessorAsr"

/** Abstraction over [AudioTranscriber] to enable unit testing and lifecycle decoupling. */
@Suppress("KotlinArray")
internal interface AudioTranscribing : AutoCloseable {
  fun transcribe(audioSamples: FloatArray, endOfStream: Boolean = false): AudioTranscriberResult

  fun finishStream(): AudioTranscriberResult

  fun prewarm()
}

/** Default adapter routing calls to the MediaPipe [AudioTranscriber] instance. */
private class MediaPipeAudioTranscriberAdapter(private val transcriber: AudioTranscriber) :
  AudioTranscribing {
  override fun transcribe(audioSamples: FloatArray, endOfStream: Boolean): AudioTranscriberResult =
    transcriber.transcribe(audioSamples, endOfStream)

  override fun finishStream(): AudioTranscriberResult = transcriber.finishStream()

  override fun prewarm() = transcriber.prewarm()

  override fun close() = transcriber.close()
}

/**
 * [AsrService] implementation backed by the MediaPipe [AudioTranscriber] task.
 *
 * This is the Android counterpart of iOS `SpeechProcessorService`: both consume MediaPipe's
 * `AudioTranscriber` task to transcribe speech with on-device open-weight models (e.g. Parakeet TDT
 * 0.6B).
 */
class SpeechProcessorAsrService
@VisibleForTesting
internal constructor(
  internal val context: Context,
  private val transcriberFactory:
    suspend (Context, AudioTranscriber.AudioTranscriberOptions) -> AudioTranscribing,
  audioRecordFactory: ((sampleRate: Int, bufferBytes: Int) -> AudioRecord)? = null,
  audioChunkSource: (() -> Flow<FloatArray>)? = null,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  engineDispatcher: CoroutineDispatcher? = null,
  internal val selectedModelId: () -> String? = { null },
) : AsrService {

  /**
   * Dedicated thread for this service's [AudioTranscriber] calls. The service is an app-scoped
   * singleton, so its daemon thread lives as long as the process; tests inject a test dispatcher
   * instead.
   */
  internal val engineDispatcher: CoroutineDispatcher =
    engineDispatcher
      ?: if (ioDispatcher === Dispatchers.IO) createSingleThreadDispatcher() else ioDispatcher

  override val managesOwnDownloads: Boolean = true

  /**
   * @param selectedModelId Returns the ASR model ID currently selected in settings, so that
   *   [checkStatus] and [download] report on the selected allowlist model before [prepare] runs.
   */
  constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    selectedModelId: () -> String? = { null },
  ) : this(
    context = context,
    transcriberFactory = { ctx, opts ->
      MediaPipeAudioTranscriberAdapter(AudioTranscriber.createFromOptions(ctx, opts))
    },
    audioRecordFactory = null,
    audioChunkSource = null,
    ioDispatcher = ioDispatcher,
    selectedModelId = selectedModelId,
  )

  override val engineType: AsrEngineType = AsrEngineType.SPEECH_PROCESSOR

  internal val _status = MutableStateFlow<AsrServiceStatus>(AsrServiceStatus.Unknown)
  override val status: StateFlow<AsrServiceStatus> = _status.asStateFlow()

  private val _currentAmplitude = MutableStateFlow(0f)
  override val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

  override val activeModel: AsrModelMetadata
    get() = engineState.get().cachedModel ?: AsrModelRegistry.defaultSpeechProcessorModel()

  override val availableModels: List<AsrModelMetadata>
    get() = listOf(activeModel)

  internal val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

  /**
   * Runs deferred native teardown. Kept separate from [serviceScope] so [release] cancelling the
   * service's children never cancels a pending close and leaks the native engine.
   */
  internal val teardownScope = CoroutineScope(SupervisorJob() + ioDispatcher)
  internal val engineState = AtomicReference(EngineState())
  internal val transcriptState = AtomicReference(TranscriptState())
  private val pcmAudioRecorder =
    PcmAudioRecorder(
      context = context,
      sampleRateHz = SAMPLE_RATE_HZ,
      chunkSamples = CHUNK_SAMPLES,
      audioRecordFactory = audioRecordFactory,
      audioChunkSource = audioChunkSource,
    )

  override suspend fun checkStatus(): AsrServiceStatus =
    withContext(ioDispatcher) {
      val modelId = statusModelId()
      val modelFile = resolveModelFile(modelId)
      val modelMetadata = AsrModelRegistry.speechProcessorModel(context, modelId)
      if (modelFile == null || modelMetadata == null) {
        releaseEngine(resetStatus = AsrServiceStatus.NotDownloaded)
        return@withContext AsrServiceStatus.NotDownloaded
      }
      val cur = engineState.get()
      val fingerprint = modelFile.toModelFileFingerprint()
      if (cur.audioTranscriber != null && cur.modelFingerprint != fingerprint) {
        releaseEngine(resetStatus = AsrServiceStatus.Downloaded)
      }
      val prev = engineState.getAndUpdate { it.copy(cachedModel = modelMetadata) }
      val mapped =
        if (prev.audioTranscriber != null) AsrServiceStatus.Ready else AsrServiceStatus.Downloaded
      _status.value = mapped
      mapped
    }

  override fun download(): Flow<AsrServiceStatus> = flow {
    val modelId = statusModelId()
    val modelMetadata =
      withContext(ioDispatcher) { AsrModelRegistry.speechProcessorModel(context, modelId) }
    if (modelMetadata == null) {
      Log.w(TAG, "No downloaded or sideloaded ASR model found for modelId=$modelId")
      withContext(ioDispatcher) { releaseEngine(resetStatus = AsrServiceStatus.NotDownloaded) }
      emit(AsrServiceStatus.NotDownloaded)
      throw AsrServiceError.ModelNotDownloaded()
    }
    val unused = engineState.updateAndGet { it.copy(cachedModel = modelMetadata) }
    _status.value = AsrServiceStatus.Downloaded
    emit(AsrServiceStatus.Downloaded)
  }

  override suspend fun prepare(config: AsrPreparationConfig) {
    tryJoinInFlightPrepare(config)?.let {
      return awaitPrepareAttempt(it)
    }

    val (modelFile, modelMetadata) =
      withContext(ioDispatcher) {
        resolveModelFile(config.modelId) to
          AsrModelRegistry.speechProcessorModel(context, config.modelId)
      }
    if (modelFile == null || modelMetadata == null) {
      Log.w(TAG, "Cannot prepare: no sideloaded model found")
      engineState
        .getAndUpdate { it.copy(inFlightPrepare = null) }
        .inFlightPrepare
        ?.deferred
        ?.cancel()
      withContext(ioDispatcher) { releaseEngine(resetStatus = AsrServiceStatus.NotDownloaded) }
      throw AsrServiceError.ModelNotDownloaded()
    }

    val modelLastModified = modelFile.lastModified()
    val modelSize = modelFile.length()

    while (true) {
      tryJoinInFlightPrepare(config, modelMetadata)?.let {
        return awaitPrepareAttempt(it)
      }
      val cur = engineState.get()
      val inFlight = cur.inFlightPrepare
      val effectiveBackend = resolveEffectiveBackend(config, modelMetadata)
      val preparedEffectiveBackend =
        cur.preparedConfig?.let { resolveEffectiveBackend(it, cur.cachedModel ?: modelMetadata) }

      val fingerprint = ModelFileFingerprint(modelFile.absolutePath, modelLastModified, modelSize)
      val isReusable =
        cur.audioTranscriber != null &&
          preparedEffectiveBackend == effectiveBackend &&
          cur.modelFingerprint == fingerprint &&
          _status.value !is AsrServiceStatus.Failed
      if (isReusable) {
        val next =
          cur.copy(inFlightPrepare = null, preparedConfig = config, cachedModel = modelMetadata)
        if (engineState.compareAndSet(cur, next)) {
          inFlight?.deferred?.cancel()
          _status.value = AsrServiceStatus.Ready
          return
        }
        continue
      }

      val nextGen = cur.prepareGeneration + 1L
      val deferred =
        createPrepareDeferred(
          config = config,
          modelFile = modelFile,
          modelLastModified = modelLastModified,
          modelSize = modelSize,
          modelMetadata = modelMetadata,
          staleBeforePrepare = cur.audioTranscriber,
          currentGeneration = nextGen,
        )
      val attempt = PrepareAttempt(config = config, generation = nextGen, deferred = deferred)
      val next =
        cur.copy(
          cachedModel = modelMetadata,
          audioTranscriber = null,
          preparedConfig = null,
          modelFingerprint = null,
          inFlightPrepare = attempt,
          prepareGeneration = nextGen,
        )
      if (engineState.compareAndSet(cur, next)) {
        inFlight?.deferred?.cancel()
        deferred.start()
        return awaitPrepareAttempt(attempt)
      }
      deferred.cancel()
    }
  }

  internal suspend fun instantiateTranscriber(
    modelFile: File,
    modelMetadata: AsrModelMetadata,
    config: AsrPreparationConfig,
    currentGeneration: Long,
  ): AudioTranscribing {
    for (delegate in resolveCandidateBackends(config, modelMetadata)) {
      currentCoroutineContext().ensureActive()
      if (engineState.get().prepareGeneration != currentGeneration) {
        throw CancellationException("Preparation superseded or cancelled")
      }
      try {
        val baseOptions =
          BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(delegate)
            .build()
        val cacheDir = File(context.cacheDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }
        val options =
          AudioTranscriber.AudioTranscriberOptions.builder()
            .setBaseOptions(baseOptions)
            .setSampleRateHz(SAMPLE_RATE_HZ)
            .setEnableInterimResults(true)
            .setModelArchitecture(AudioTranscriber.ModelArchitecture.AUTO)
            .setCacheDir(cacheDir.absolutePath)
            .build()
        return withContext(engineDispatcher) {
          val instance = transcriberFactory(context, options)
          if (
            !currentCoroutineContext().isActive ||
              engineState.get().prepareGeneration != currentGeneration
          ) {
            runCatching { instance.close() }
            throw CancellationException("Preparation superseded or cancelled")
          }
          try {
            warmUpTranscriber(instance)
          } catch (t: Throwable) {
            runCatching { instance.close() }
            throw t
          }
          instance
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize AudioTranscriber on $delegate", e)
      } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "Native AudioTranscriber JNI unavailable on $delegate", e)
      }
    }
    throw AsrServiceError.ModelInitializationFailed()
  }

  /** Warms up [transcriber] during [prepare] so the first recording starts quickly. */
  private fun warmUpTranscriber(transcriber: AudioTranscribing) {
    transcriber.prewarm()
    val unusedSilence = transcriber.transcribe(FloatArray(CHUNK_SAMPLES), endOfStream = false)
    val unusedFinal = transcriber.finishStream()
    transcriber.prewarm()
  }

  override fun startRecording(options: AsrRecordingOptions): Flow<String> = callbackFlow {
    if (options.externalAudioSource == null && !pcmAudioRecorder.hasRecordPermission()) {
      _status.value = AsrServiceStatus.Failed
      close(AsrServiceError.AudioPermissionDenied())
      return@callbackFlow
    }

    val session = RecordingSession(this)
    val previousSession =
      engineState
        .getAndUpdate { cur ->
          cur.activeSession?.isCancelled = true
          cur.copy(activeSession = session)
        }
        .activeSession
    if (previousSession != null) {
      previousSession.job?.cancel()
      withContext(ioDispatcher) { pcmAudioRecorder.release() }
      previousSession.scope.close()
      previousSession.job?.join()
    }

    val inFlight = engineState.get().inFlightPrepare?.deferred
    val sessionJob: Job
    try {
      try {
        when {
          inFlight != null -> inFlight.await()
          engineState.get().audioTranscriber == null || _status.value is AsrServiceStatus.Failed ->
            prepare(engineState.get().preparedConfig ?: AsrPreparationConfig())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (isActiveSession(session)) _status.value = AsrServiceStatus.Failed
        close(e as? AsrServiceError ?: AsrServiceError.ModelInitializationFailed(e))
        return@callbackFlow
      }

      if (session.isCancelled || !isActiveSession(session)) {
        channel.close()
        return@callbackFlow
      }

      val transcriber = engineState.get().audioTranscriber
      if (transcriber == null) {
        if (isActiveSession(session)) _status.value = AsrServiceStatus.Failed
        close(AsrServiceError.ModelInitializationFailed())
        return@callbackFlow
      }

      resetTranscriptState()
      _currentAmplitude.value = 0f
      if (session.isStopRequested) {
        _status.value = AsrServiceStatus.Processing
      } else {
        _status.value = AsrServiceStatus.Recognizing
      }

      // Publish the job before it starts so a concurrent release() always waits for it before
      // closing the native engine.
      sessionJob =
        serviceScope.launch(start = CoroutineStart.LAZY) {
          runRecordingSession(
            session = session,
            transcriber = transcriber,
            showInterim = options.showInterimResults,
            externalAudioSource = options.externalAudioSource,
          )
        }
      sessionJob.invokeOnCompletion { session.completion.complete(Unit) }
      session.job = sessionJob
      sessionJob.start()
    } finally {
      if (session.job == null) {
        session.completion.complete(Unit)
      }
    }

    awaitClose {
      session.isCancelled = true
      sessionJob.cancel()
      if (isActiveSession(session)) _currentAmplitude.value = 0f
      serviceScope.launch { pcmAudioRecorder.release() }
    }
  }

  private suspend fun CoroutineScope.runRecordingSession(
    session: RecordingSession,
    transcriber: AudioTranscribing,
    showInterim: Boolean,
    externalAudioSource: (() -> ParcelFileDescriptor?)? = null,
  ) {
    val audioChannel = Channel<FloatArray>(Channel.UNLIMITED)
    val hasError = AtomicBoolean(false)
    val audioReaderJob = launch {
      try {
        pcmAudioRecorder.streamAudioChunks(
          audioChannel = audioChannel,
          isStopped = { session.isStopped },
          onAmplitude = { _currentAmplitude.value = it },
          externalAudioSource = externalAudioSource,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (!session.isStopped) {
          hasError.set(true)
          if (isActiveSession(session)) _status.value = AsrServiceStatus.Failed
          session.scope.close(e as? AsrServiceError ?: AsrServiceError.AudioRecordingFailed(e))
        }
      }
    }

    var lastEmittedFinalText = ""
    try {
      for (samples in audioChannel) {
        if (session.isCancelled) break
        currentCoroutineContext().ensureActive()
        val result =
          withContext(engineDispatcher) { transcriber.transcribe(samples, endOfStream = false) }
        val text = handleTranscriptionResult(result, includeInterim = showInterim)
        if (
          text.isNotEmpty() &&
            !session.isStopRequested &&
            (showInterim || text != lastEmittedFinalText)
        ) {
          if (!showInterim) lastEmittedFinalText = text
          val unused = session.scope.trySend(text)
        }
      }
      audioReaderJob.cancel()
      pcmAudioRecorder.release()
      if (isActiveSession(session)) _currentAmplitude.value = 0f
      if (!session.isCancelled && !hasError.get()) {
        finishRecordingStream(session, transcriber, showInterim, lastEmittedFinalText)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      hasError.set(true)
      if (!session.isCancelled) {
        Log.e(TAG, "AudioTranscriber transcription failed", e)
        if (isActiveSession(session)) {
          _currentAmplitude.value = 0f
          _status.value = AsrServiceStatus.Failed
        }
        session.scope.close(AsrServiceError.Unknown(e.message ?: "Transcription error", e))
      }
    } finally {
      audioReaderJob.cancel()
      pcmAudioRecorder.release()
      if (isActiveSession(session)) _currentAmplitude.value = 0f
      if (session.isCancelled && session.streamFinalized.compareAndSet(false, true)) {
        withContext(NonCancellable) {
          finalizeCancelledStream(transcriber, session, restoreReadyStatus = !hasError.get())
        }
      }
      session.scope.channel.close()
    }
  }

  override suspend fun stopRecording() =
    withContext(ioDispatcher) {
      val session = engineState.get().activeSession ?: return@withContext
      if (session.isCancelled || session.streamFinalized.get()) return@withContext
      session.isStopRequested = true
      if (
        _status.value is AsrServiceStatus.Recognizing ||
          _status.value is AsrServiceStatus.Initializing ||
          _status.value is AsrServiceStatus.Ready
      ) {
        _status.value = AsrServiceStatus.Processing
      }
      pcmAudioRecorder.release()
      session.completion.await()
      session.job?.join()
    }

  override suspend fun cancelRecording() =
    withContext(ioDispatcher) {
      val wasActiveSession =
        _status.value is AsrServiceStatus.Recognizing ||
          _status.value is AsrServiceStatus.Processing
      val session = engineState.get().activeSession
      session?.isCancelled = true
      val job = session?.job
      session?.scope?.close()
      job?.cancel()
      pcmAudioRecorder.release()
      job?.join()
      resetTranscriptState()
      _currentAmplitude.value = 0f

      val shouldFinalizeDirectly =
        if (session != null && job != null) {
          wasActiveSession && session.streamFinalized.compareAndSet(false, true)
        } else {
          wasActiveSession
        }

      val transcriber = engineState.get().audioTranscriber ?: return@withContext
      when {
        shouldFinalizeDirectly -> finalizeCancelledStream(transcriber, session)
        (session == null || isActiveSession(session)) &&
          engineState.get().audioTranscriber === transcriber -> {
          _status.value = AsrServiceStatus.Ready
        }
      }
    }

  override fun release() {
    val previousStatus = _status.value
    val prev = engineState.getAndUpdate { cur ->
      cur.copy(
        activeSession = null,
        prepareGeneration = cur.prepareGeneration + 1L,
        inFlightPrepare = null,
      )
    }
    val activeJob = prev.activeSession?.job
    val prepareDeferred = prev.inFlightPrepare?.deferred
    prev.activeSession?.isCancelled = true
    prev.activeSession?.scope?.close()
    activeJob?.cancel()
    prepareDeferred?.cancel()
    serviceScope.coroutineContext.cancelChildren()
    pcmAudioRecorder.release()
    resetTranscriptState()
    val resetStatus =
      when {
        prev.cachedModel != null -> AsrServiceStatus.Downloaded
        previousStatus is AsrServiceStatus.Downloaded ||
          previousStatus is AsrServiceStatus.Ready ||
          previousStatus is AsrServiceStatus.Initializing ||
          previousStatus is AsrServiceStatus.Recognizing ||
          previousStatus is AsrServiceStatus.Processing -> AsrServiceStatus.Downloaded
        previousStatus is AsrServiceStatus.NotDownloaded -> AsrServiceStatus.NotDownloaded
        else -> AsrServiceStatus.Unknown
      }
    val transcriberToClose = detachEngine(resetStatus = resetStatus)
    if (transcriberToClose != null) {
      // release() is not suspending and may run on the main thread, so the close is queued on the
      // single engine thread (after any session or prepare still using it) instead of blocking.
      pendingCloseJob =
        scheduleClose(
          transcriber = transcriberToClose,
          activeJobToAwait = activeJob,
          prepareToAwait = prepareDeferred,
        )
    }
    _currentAmplitude.value = 0f
  }

  /** Completes when the most recently scheduled deferred close of the native engine has run. */
  @Volatile internal var pendingCloseJob: Job? = null

  companion object {
    /** 16 kHz sampling rate matching open-weight ASR models. */
    const val SAMPLE_RATE_HZ = 16000

    /** ~100 ms of audio per transcribe() call. */
    internal const val CHUNK_SAMPLES = 1600

    /** Subdirectory inside [Context.getCacheDir] for compiled AudioTranscriber delegate caches. */
    const val CACHE_DIRECTORY_NAME = "mediapipe_asr_cache"

    private const val ENGINE_THREAD_NAME = "SpeechProcessorAsrService"

    /**
     * Returns a dedicated single-thread [ExecutorCoroutineDispatcher] for [AudioTranscriber] calls.
     * Callers that own the returned dispatcher must [ExecutorCoroutineDispatcher.close] it when
     * done.
     */
    @VisibleForTesting
    internal fun createSingleThreadDispatcher(): ExecutorCoroutineDispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, ENGINE_THREAD_NAME).apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}
