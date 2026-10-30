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
import com.google.ai.edge.gallery.data.Accelerator
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSpeechProcessorAsr"

internal class PrepareAttempt(
  val config: AsrPreparationConfig,
  val generation: Long,
  val deferred: Deferred<Unit>,
) {
  val waiters = AtomicInteger(1)
}

internal class RecordingSession(val scope: ProducerScope<String>) {
  @Volatile var isCancelled: Boolean = false
  @Volatile var isStopRequested: Boolean = false
  @Volatile var job: Job? = null
  val completion = CompletableDeferred<Unit>()
  val streamFinalized = AtomicBoolean(false)
  val isStopped: Boolean
    get() = isCancelled || isStopRequested
}

internal data class ModelFileFingerprint(val path: String, val lastModified: Long, val size: Long)

internal fun File.toModelFileFingerprint(): ModelFileFingerprint =
  ModelFileFingerprint(absolutePath, lastModified(), length())

internal data class EngineState(
  val cachedModel: AsrModelMetadata? = null,
  val audioTranscriber: AudioTranscribing? = null,
  val preparedConfig: AsrPreparationConfig? = null,
  val modelFingerprint: ModelFileFingerprint? = null,
  val inFlightPrepare: PrepareAttempt? = null,
  val prepareGeneration: Long = 0L,
  val activeSession: RecordingSession? = null,
)

internal data class TranscriptState(
  val finalTranscript: String = "",
  val interimTranscript: String = "",
) {
  fun combinedTranscript(): String =
    when {
      finalTranscript.isEmpty() -> interimTranscript
      interimTranscript.isEmpty() -> finalTranscript
      else -> "$finalTranscript $interimTranscript"
    }
}

/** Returns the downloaded or sideloaded model file, or `null` if none is found. */
fun SpeechProcessorAsrService.resolveModelFile(modelId: String? = null): File? =
  AsrModelRegistry.resolveModelFile(context, modelId)

/**
 * Returns the model ID that [SpeechProcessorAsrService.checkStatus] and
 * [SpeechProcessorAsrService.download] report on: the allowlist model selected in settings when it
 * is registered, otherwise the model ID of the last [SpeechProcessorAsrService.prepare] call.
 */
internal fun SpeechProcessorAsrService.statusModelId(): String? {
  val selected = selectedModelId()
  if (!selected.isNullOrEmpty() && AsrModelRegistry.model(selected) != null) {
    return selected
  }
  return engineState.get().preparedConfig?.modelId
}

internal fun SpeechProcessorAsrService.resolveEffectiveBackend(
  config: AsrPreparationConfig,
  modelMetadata: AsrModelMetadata,
): Accelerator {
  val supported = modelMetadata.supportedAccelerators
  return if (config.backend != null && config.backend in supported) {
    config.backend
  } else {
    modelMetadata.preferredBackend ?: supported.firstOrNull() ?: Accelerator.GPU
  }
}

internal fun SpeechProcessorAsrService.resolveCandidateBackends(
  config: AsrPreparationConfig,
  modelMetadata: AsrModelMetadata,
): List<Delegate> {
  val supported = modelMetadata.supportedAccelerators
  val effectiveBackend = resolveEffectiveBackend(config, modelMetadata)
  return when {
    effectiveBackend == Accelerator.CPU && Accelerator.CPU in supported -> listOf(Delegate.CPU)
    supported == listOf(Accelerator.GPU) -> listOf(Delegate.GPU)
    else -> listOf(Delegate.GPU, Delegate.CPU)
  }
}

/**
 * SpeechProcessor models auto-detect the spoken language, so two [AsrPreparationConfig] instances
 * that only differ in [AsrPreparationConfig.languageCode] or that resolve to the same effective
 * backend and model share the same native engine.
 */
internal fun SpeechProcessorAsrService.isSameEngineConfig(
  a: AsrPreparationConfig,
  b: AsrPreparationConfig,
  modelMetadata: AsrModelMetadata? = null,
): Boolean {
  val metadata = modelMetadata ?: engineState.get().cachedModel
  val backendMatches =
    if (metadata != null) {
      resolveEffectiveBackend(a, metadata) == resolveEffectiveBackend(b, metadata)
    } else {
      (a.backend ?: Accelerator.GPU) == (b.backend ?: Accelerator.GPU)
    }
  val modelMatches = resolveEffectiveModelId(a, metadata) == resolveEffectiveModelId(b, metadata)
  return backendMatches && modelMatches
}

internal fun SpeechProcessorAsrService.resolveEffectiveModelId(
  config: AsrPreparationConfig,
  metadata: AsrModelMetadata?,
): String {
  val id = config.modelId?.trim()
  if (!id.isNullOrEmpty() && id != AsrModelIdentifier.SPEECH_PROCESSOR) {
    val allowlistMatch = AsrModelRegistry.model(id)
    if (allowlistMatch != null) {
      return allowlistMatch.name.ifEmpty { allowlistMatch.modelId }
    }
    return id
  }
  return metadata?.let { it.name.ifEmpty { it.modelId } }
    ?: AsrModelRegistry.speechProcessorModel(context, null)?.let { it.name.ifEmpty { it.modelId } }
    ?: AsrModelIdentifier.SPEECH_PROCESSOR
}

internal fun SpeechProcessorAsrService.tryJoinInFlightPrepare(
  config: AsrPreparationConfig,
  modelMetadata: AsrModelMetadata? = null,
): PrepareAttempt? {
  val inFlight = engineState.get().inFlightPrepare ?: return null
  if (!isSameEngineConfig(inFlight.config, config, modelMetadata)) return null
  while (true) {
    val count = inFlight.waiters.get()
    if (count <= 0) return null
    if (inFlight.waiters.compareAndSet(count, count + 1)) {
      if (engineState.get().inFlightPrepare === inFlight) return inFlight
      val unused = inFlight.waiters.decrementAndGet()
      return null
    }
  }
}

internal fun SpeechProcessorAsrService.createPrepareDeferred(
  config: AsrPreparationConfig,
  modelFile: File,
  modelLastModified: Long,
  modelSize: Long,
  modelMetadata: AsrModelMetadata,
  staleBeforePrepare: AudioTranscribing?,
  currentGeneration: Long,
): Deferred<Unit> =
  serviceScope.async(start = CoroutineStart.LAZY) {
    try {
      if (engineState.get().prepareGeneration == currentGeneration) {
        _status.value = AsrServiceStatus.Initializing
      }
      staleBeforePrepare?.let {
        withContext(NonCancellable + engineDispatcher) { runCatching { it.close() } }
      }
      ensureActive()
      val transcriber = instantiateTranscriber(modelFile, modelMetadata, config, currentGeneration)
      val prev = engineState.getAndUpdate { cur ->
        if (!coroutineContext.isActive || cur.prepareGeneration != currentGeneration) {
          cur
        } else {
          cur.copy(
            audioTranscriber = transcriber,
            preparedConfig = config,
            modelFingerprint =
              ModelFileFingerprint(modelFile.absolutePath, modelLastModified, modelSize),
            cachedModel = modelMetadata,
          )
        }
      }
      if (!coroutineContext.isActive || prev.prepareGeneration != currentGeneration) {
        withContext(NonCancellable + engineDispatcher) { runCatching { transcriber.close() } }
        throw CancellationException("Preparation superseded or cancelled")
      }
      _status.value = AsrServiceStatus.Ready
      prev.audioTranscriber?.let {
        withContext(NonCancellable + engineDispatcher) { runCatching { it.close() } }
      }
    } catch (e: Exception) {
      if (e is CancellationException) {
        val cur = engineState.get()
        if (
          cur.prepareGeneration == currentGeneration &&
            cur.audioTranscriber == null &&
            _status.value is AsrServiceStatus.Initializing
        ) {
          _status.value = AsrServiceStatus.Unknown
        }
        throw e
      }
      if (engineState.get().prepareGeneration == currentGeneration) {
        _status.value = AsrServiceStatus.Failed
      }
      throw e as? AsrServiceError ?: AsrServiceError.ModelInitializationFailed(e)
    } finally {
      val unused = engineState.updateAndGet { cur ->
        if (cur.prepareGeneration == currentGeneration) cur.copy(inFlightPrepare = null) else cur
      }
    }
  }

internal suspend fun SpeechProcessorAsrService.awaitPrepareAttempt(attempt: PrepareAttempt) {
  try {
    attempt.deferred.await()
  } catch (e: CancellationException) {
    if (attempt.waiters.decrementAndGet() <= 0) {
      val cur = engineState.updateAndGet {
        if (it.inFlightPrepare === attempt) it.copy(inFlightPrepare = null) else it
      }
      if (
        cur.prepareGeneration == attempt.generation &&
          cur.audioTranscriber == null &&
          _status.value is AsrServiceStatus.Initializing
      ) {
        _status.value = AsrServiceStatus.Unknown
      }
      attempt.deferred.cancel()
    }
    throw e
  }
}

/**
 * Accumulates segments from [result] into the session's final and interim transcripts and returns
 * the combined transcript string.
 */
internal fun SpeechProcessorAsrService.handleTranscriptionResult(
  result: AudioTranscriberResult?,
  includeInterim: Boolean = true,
): String {
  val segments = result?.segments()
  val updated = transcriptState.updateAndGet { current ->
    var finalText = current.finalTranscript
    var interimText = current.interimTranscript
    if (segments != null) {
      val newFinalSegments = mutableListOf<String>()
      for (segment in segments) {
        val trimmed = segment.transcript().trim()
        if (trimmed.isEmpty()) continue
        when {
          segment.isFinal -> {
            newFinalSegments.add(trimmed)
            interimText = ""
          }
          includeInterim -> interimText = trimmed
        }
      }
      if (newFinalSegments.isNotEmpty()) {
        finalText = buildString {
          if (finalText.isNotEmpty()) {
            append(finalText)
            append(' ')
          }
          append(newFinalSegments.joinToString(" "))
        }
      }
    }
    if (!includeInterim) interimText = ""
    TranscriptState(finalTranscript = finalText, interimTranscript = interimText)
  }
  return if (!includeInterim) updated.finalTranscript else updated.combinedTranscript()
}

internal fun SpeechProcessorAsrService.resetTranscriptState() {
  transcriptState.set(TranscriptState())
}

internal fun SpeechProcessorAsrService.isActiveSession(session: RecordingSession): Boolean =
  engineState.get().activeSession === session

internal suspend fun SpeechProcessorAsrService.finishRecordingStream(
  session: RecordingSession,
  transcriber: AudioTranscribing,
  showInterim: Boolean,
  lastEmittedFinalText: String,
) {
  session.streamFinalized.set(true)
  try {
    if (isActiveSession(session)) _status.value = AsrServiceStatus.Processing
    val finalResult = withContext(engineDispatcher) { transcriber.finishStream() }
    val finalText = handleTranscriptionResult(finalResult, includeInterim = showInterim)
    if (isActiveSession(session) && engineState.get().audioTranscriber === transcriber) {
      _status.value = AsrServiceStatus.Ready
    }
    if (finalText.isNotEmpty() && (showInterim || finalText != lastEmittedFinalText)) {
      session.scope.send(finalText)
    }
    session.scope.channel.close()
  } catch (e: CancellationException) {
    if (isActiveSession(session) && engineState.get().audioTranscriber === transcriber) {
      _status.value = AsrServiceStatus.Ready
    }
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Failed to finish AudioTranscriber stream", e)
    if (isActiveSession(session)) _status.value = AsrServiceStatus.Failed
    session.scope.close(
      e as? AsrServiceError ?: AsrServiceError.Unknown(e.message ?: "Stream error", e)
    )
  } finally {
    withContext(NonCancellable) {
      withContext(engineDispatcher) {
        runCatching { transcriber.prewarm() }
          .onFailure { Log.w(TAG, "Failed to prewarm AudioTranscriber after stream", it) }
      }
    }
  }
}

internal suspend fun SpeechProcessorAsrService.finalizeCancelledStream(
  transcriber: AudioTranscribing,
  session: RecordingSession?,
  restoreReadyStatus: Boolean = true,
) {
  resetTranscriptState()
  withContext(NonCancellable + engineDispatcher) {
    // release() detaches the engine before scheduling its close; a detached transcriber may
    // already be closed, so never call into it again.
    if (engineState.get().audioTranscriber !== transcriber) return@withContext
    try {
      val unused = transcriber.finishStream()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to finish stream on cancellation", e)
    } finally {
      runCatching { transcriber.prewarm() }
        .onFailure { Log.w(TAG, "Failed to prewarm on cancellation", it) }
    }
  }
  if (
    restoreReadyStatus &&
      (session == null || isActiveSession(session)) &&
      engineState.get().audioTranscriber === transcriber
  ) {
    _status.value = AsrServiceStatus.Ready
  }
}

/**
 * Clears the prepared engine from [SpeechProcessorAsrService.engineState], publishes [resetStatus],
 * and returns the detached transcriber so the caller can close it on
 * [SpeechProcessorAsrService.engineDispatcher].
 */
internal fun SpeechProcessorAsrService.detachEngine(
  resetStatus: AsrServiceStatus
): AudioTranscribing? {
  val prev = engineState.getAndUpdate { cur ->
    cur.copy(
      audioTranscriber = null,
      preparedConfig = null,
      modelFingerprint = null,
      cachedModel = if (resetStatus is AsrServiceStatus.NotDownloaded) null else cur.cachedModel,
    )
  }
  _status.value = resetStatus
  return prev.audioTranscriber
}

/**
 * Closes [transcriber] on [SpeechProcessorAsrService.engineDispatcher] after [activeJobToAwait] and
 * [prepareToAwait] finish, so teardown never races a native call still running on the engine
 * thread.
 */
internal fun SpeechProcessorAsrService.scheduleClose(
  transcriber: AudioTranscribing,
  activeJobToAwait: Job? = null,
  prepareToAwait: Job? = null,
): Job =
  teardownScope.launch(engineDispatcher) {
    withContext(NonCancellable) {
      activeJobToAwait?.join()
      prepareToAwait?.join()
      closeTranscriber(transcriber)
    }
  }

internal fun SpeechProcessorAsrService.closeTranscriber(transcriber: AudioTranscribing) {
  runCatching { transcriber.close() }
    .onFailure { Log.w(TAG, "Failed to close AudioTranscriber", it) }
}

/**
 * Detaches the prepared engine and closes it on [SpeechProcessorAsrService.engineDispatcher]. Waits
 * for the close when no recording session is still using the transcriber; otherwise defers the
 * close until that session ends so a long recording does not block the caller.
 */
internal suspend fun SpeechProcessorAsrService.releaseEngine(
  resetStatus: AsrServiceStatus = AsrServiceStatus.Downloaded
) {
  val activeJob = engineState.get().activeSession?.job
  val transcriberToClose = detachEngine(resetStatus) ?: return
  if (activeJob != null && !activeJob.isCompleted) {
    pendingCloseJob = scheduleClose(transcriberToClose, activeJobToAwait = activeJob)
  } else {
    withContext(NonCancellable + engineDispatcher) { closeTranscriber(transcriberToClose) }
  }
}
