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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGSystemSpeechAsr"

/**
 * Controls how [SystemSpeechAsrService] configures Android [SpeechRecognizer] session lifetimes.
 */
enum class SystemSpeechSessionMode {
  /**
   * Continuous manual-stop dictation using
   * [RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS] up to the 5-minute
   * ([SystemSpeechAsrService.CONTINUOUS_SEGMENT_MAX_LENGTH_MS]) segment ceiling, automatically
   * rolling over into a fresh segment if the recognizer ends a segment or reports silence before
   * [AsrService.stopRecording] or [AsrService.cancelRecording].
   */
  CONTINUOUS_SEGMENTED_MIN_LENGTH,

  /**
   * Continuous manual-stop dictation using [RecognizerIntent.EXTRA_AUDIO_SOURCE] streamed from a
   * raw 16 kHz mono PCM16 [ParcelFileDescriptor] pipe until closed on [AsrService.stopRecording] or
   * [AsrService.cancelRecording].
   */
  CONTINUOUS_AUDIO_SOURCE_PIPE,

  /**
   * Single-utterance dictation using
   * [RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS] that finishes
   * automatically once silence is detected. Retained as an opt-in mode for callers desiring silence
   * auto-stop.
   */
  SILENCE_TIMEOUT;

  val isContinuous: Boolean
    get() = this != SILENCE_TIMEOUT
}

/**
 * [AsrService] implementation backed by Android's platform [SpeechRecognizer]
 * (`AsrEngineType.SYSTEM_DEFAULT`).
 *
 * Defaults to [SystemSpeechSessionMode.CONTINUOUS_SEGMENTED_MIN_LENGTH] so speaking pauses never
 * terminate dictation prematurely before [stopRecording] or [cancelRecording] is invoked, while
 * retaining [SystemSpeechSessionMode.CONTINUOUS_AUDIO_SOURCE_PIPE] and opt-in
 * [SystemSpeechSessionMode.SILENCE_TIMEOUT] for callers that need them.
 *
 * All [SpeechRecognizer] calls are dispatched onto [mainDispatcher] as required by the Android
 * framework.
 */
class SystemSpeechAsrService(
  private val context: Context,
  private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val sessionMode: SystemSpeechSessionMode =
    SystemSpeechSessionMode.CONTINUOUS_SEGMENTED_MIN_LENGTH,
) : AsrService {

  override val engineType: AsrEngineType = AsrEngineType.SYSTEM_DEFAULT

  private class ActiveAudioStream(
    val readEnd: ParcelFileDescriptor,
    val writeEnd: ParcelFileDescriptor? = null,
    val audioRecord: AudioRecord? = null,
  ) {
    @Volatile var aborted: Boolean = false
    var pumpJob: Job? = null
  }

  private var speechRecognizer: SpeechRecognizer? = null
  private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
  private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
  private var preparedConfig: AsrPreparationConfig = AsrPreparationConfig()
  private val usesSegmentedSession = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  private val activeAudioStream = AtomicReference<ActiveAudioStream?>(null)

  @Volatile private var activeProducerScope: ProducerScope<String>? = null
  @Volatile private var activeFinishSession: (() -> Unit)? = null
  @Volatile private var activeSessionFinished: CompletableDeferred<Unit>? = null
  @Volatile private var isStopping: Boolean = false
  @Volatile private var isCancelled: Boolean = false

  private val _status = MutableStateFlow<AsrServiceStatus>(AsrServiceStatus.Unknown)
  override val status: StateFlow<AsrServiceStatus> = _status.asStateFlow()

  private val _currentAmplitude = MutableStateFlow(0f)
  override val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

  override val activeModel: AsrModelMetadata = AsrModelRegistry.systemDefaultModel()

  override val availableModels: List<AsrModelMetadata> = listOf(activeModel)

  override suspend fun checkStatus(): AsrServiceStatus =
    withContext(mainDispatcher) {
      val available = SpeechRecognizer.isRecognitionAvailable(context)
      val newStatus = if (available) AsrServiceStatus.Ready else AsrServiceStatus.Unavailable
      _status.value = newStatus
      newStatus
    }

  override suspend fun prepare(config: AsrPreparationConfig) {
    preparedConfig = config
    val currentStatus = checkStatus()
    if (currentStatus is AsrServiceStatus.Unavailable) {
      throw AsrServiceError.HardwareNotSupported()
    }
  }

  internal fun buildRecognizerIntent(
    options: AsrRecordingOptions,
    audioSourcePfd: ParcelFileDescriptor? = null,
  ): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.showInterimResults)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, preparedConfig.languageCode)

      when {
        usesSegmentedSession && audioSourcePfd != null -> {
          putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSourcePfd)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SOURCE_SAMPLE_RATE_HZ)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, AUDIO_SOURCE_CHANNEL_COUNT)
        }
        sessionMode == SystemSpeechSessionMode.SILENCE_TIMEOUT -> {
          putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            DEFAULT_COMPLETE_SILENCE_LENGTH_MS,
          )
          putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            DEFAULT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MS,
          )
          putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            DEFAULT_MINIMUM_SPEECH_LENGTH_MS,
          )
          if (usesSegmentedSession) {
            putExtra(
              RecognizerIntent.EXTRA_SEGMENTED_SESSION,
              RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
          }
        }
        else -> {
          putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            CONTINUOUS_SEGMENT_MAX_LENGTH_MS,
          )
          if (usesSegmentedSession) {
            putExtra(
              RecognizerIntent.EXTRA_SEGMENTED_SESSION,
              RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            )
          }
        }
      }
    }

  @SuppressLint("MissingPermission")
  private fun openAudioStreamIfConfigured(options: AsrRecordingOptions): ActiveAudioStream? {
    closeAudioStreamImmediately()
    if (!usesSegmentedSession) return null
    options.externalAudioSource?.invoke()?.let { externalReadPfd ->
      val stream = ActiveAudioStream(readEnd = externalReadPfd)
      activeAudioStream.set(stream)
      return stream
    }
    if (sessionMode != SystemSpeechSessionMode.CONTINUOUS_AUDIO_SOURCE_PIPE) {
      return null
    }
    val minBufferSize =
      AudioRecord.getMinBufferSize(
        AUDIO_SOURCE_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBufferSize <= 0) {
      Log.w(TAG, "Invalid AudioRecord min buffer size ($minBufferSize) for pipe mode")
      return null
    }
    var record: AudioRecord? = null
    var pipe: Array<ParcelFileDescriptor>? = null
    try {
      val audioRecord =
        AudioRecord.Builder()
          .setContext(context)
          .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(AUDIO_SOURCE_SAMPLE_RATE_HZ)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minBufferSize, AUDIO_PIPE_CHUNK_BYTES * 8))
          .build()
      record = audioRecord
      check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
        "AudioRecord failed to initialize for pipe mode"
      }
      pipe = ParcelFileDescriptor.createPipe()
      audioRecord.startRecording()
      check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        "AudioRecord failed to start recording (state=${audioRecord.recordingState})"
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to start internal PCM pipe capture; falling back to recognizer mic", e)
      runCatching { record?.release() }
      pipe?.forEach { fd -> runCatching { fd.close() } }
      return null
    }
    val startedRecord = record ?: return null
    val (readEnd, writeEnd) = pipe ?: return null
    val stream =
      ActiveAudioStream(readEnd = readEnd, writeEnd = writeEnd, audioRecord = startedRecord)
    activeAudioStream.set(stream)
    stream.pumpJob = ioScope.launch { pumpAudioRecordToPipe(stream) }
    return stream
  }

  private suspend fun pumpAudioRecordToPipe(stream: ActiveAudioStream) {
    val record = stream.audioRecord ?: return
    val writeEnd = stream.writeEnd ?: return
    val buffer = ByteArray(AUDIO_PIPE_CHUNK_BYTES)
    try {
      ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
        while (!stream.aborted && currentCoroutineContext().isActive) {
          val read = record.read(buffer, 0, buffer.size)
          if (read <= 0) {
            if (read < 0) {
              Log.w(TAG, "AudioRecord pipe read error: $read")
            }
            break
          }
          output.write(buffer, 0, read)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: IOException) {
      // Downstream SpeechRecognizer closed its read end; nothing further to write.
    } catch (e: Exception) {
      Log.w(TAG, "AudioRecord pipe pump error", e)
    } finally {
      runCatching { record.stop() }
      runCatching { record.release() }
      runCatching { writeEnd.close() }
    }
  }

  private suspend fun flushWriteSideOfAudioStream() {
    val stream = activeAudioStream.get() ?: return
    runCatching { stream.audioRecord?.stop() }
    withTimeoutOrNull(AUDIO_FLUSH_TIMEOUT_MS) { stream.pumpJob?.join() }
    stream.aborted = true
    stream.pumpJob?.cancel()
    runCatching { stream.writeEnd?.close() }
  }

  private fun closeAudioStreamImmediately(
    stream: ActiveAudioStream? = activeAudioStream.getAndSet(null)
  ) {
    stream ?: return
    stream.aborted = true
    val hadPumpJob = stream.pumpJob != null
    stream.pumpJob?.cancel()
    runCatching { stream.audioRecord?.stop() }
    if (!hadPumpJob) {
      runCatching { stream.audioRecord?.release() }
    }
    runCatching { stream.writeEnd?.close() }
    runCatching { stream.readEnd.close() }
  }

  override fun startRecording(options: AsrRecordingOptions): Flow<String> = callbackFlow {
    val hasPermission =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
      _status.value = AsrServiceStatus.Failed
      close(AsrServiceError.AudioPermissionDenied())
      return@callbackFlow
    }

    activeProducerScope = this
    val sessionFinished = CompletableDeferred<Unit>()
    activeSessionFinished = sessionFinished
    isStopping = false
    isCancelled = false
    val finalizedSegments = StringBuilder()
    var latestPartialSegment = ""
    var autoRestartCount = 0

    fun appendSegment(rawSegment: String): String {
      latestPartialSegment = ""
      val segment = rawSegment.trim()
      if (segment.isNotEmpty()) {
        if (finalizedSegments.isNotEmpty() && !finalizedSegments.endsWith(" ")) {
          finalizedSegments.append(' ')
        }
        finalizedSegments.append(segment)
      }
      return finalizedSegments.toString().trim()
    }

    /**
     * Promotes any trailing uncommitted partial hypothesis (when not cancelled), emits the
     * cumulative transcript, closes any active audio stream descriptors, and completes cleanly.
     */
    fun finishSession() {
      if (sessionFinished.isCompleted) return
      if (!isCancelled && latestPartialSegment.isNotEmpty()) {
        val unused = appendSegment(latestPartialSegment)
      }
      _currentAmplitude.value = 0f
      val cumulative = finalizedSegments.toString().trim()
      if (!isCancelled && cumulative.isNotEmpty()) {
        val unused = trySend(cumulative)
      }
      closeAudioStreamImmediately()
      _status.value = AsrServiceStatus.Ready
      sessionFinished.complete(Unit)
      close()
    }
    activeFinishSession = ::finishSession

    val needsAudioStream =
      usesSegmentedSession &&
        (options.externalAudioSource != null ||
          sessionMode == SystemSpeechSessionMode.CONTINUOUS_AUDIO_SOURCE_PIPE)
    val sessionAudioStream =
      if (needsAudioStream) {
        withContext(ioDispatcher) { openAudioStreamIfConfigured(options) }
      } else {
        closeAudioStreamImmediately()
        null
      }
    val hasAudioSourcePipe = sessionAudioStream != null
    val sessionIntent = buildRecognizerIntent(options, audioSourcePfd = sessionAudioStream?.readEnd)
    var sessionRecognizer: SpeechRecognizer? = null

    /**
     * Continues dictation across recognizer segment boundaries or natural speech pauses when
     * operating in continuous mode (or below API 33), up to [MAX_AUTO_RESTARTS].
     *
     * Returns `false` without restarting when the caller explicitly requested stop/cancel, when
     * running in [SystemSpeechSessionMode.SILENCE_TIMEOUT] on API 33+, or when [MAX_AUTO_RESTARTS]
     * is reached.
     */
    fun tryAutoRestart(): Boolean {
      if (isStopping || isCancelled || autoRestartCount >= MAX_AUTO_RESTARTS) {
        return false
      }
      if (!sessionMode.isContinuous && usesSegmentedSession) {
        return false
      }
      val recognizer = sessionRecognizer?.takeIf { speechRecognizer === it } ?: return false
      if (latestPartialSegment.isNotEmpty()) {
        val cumulative = appendSegment(latestPartialSegment)
        if (cumulative.isNotEmpty()) {
          val unused = trySend(cumulative)
        }
      }
      autoRestartCount++
      _currentAmplitude.value = 0f
      _status.value = AsrServiceStatus.Recognizing
      return runCatching { recognizer.startListening(sessionIntent) }
        .onFailure { Log.w(TAG, "Failed to restart SpeechRecognizer", it) }
        .isSuccess
    }

    withContext(mainDispatcher) {
      try {
        val existingRecognizer = speechRecognizer
        val wasReusedRecognizer = existingRecognizer != null
        var sawReadyForSpeech = false
        var retriedReusedBusyError = false

        val recognitionListener =
          object : RecognitionListener {
            private val listener: RecognitionListener = this

            override fun onReadyForSpeech(params: Bundle?) {
              sawReadyForSpeech = true
              if (!isStopping && !isCancelled) {
                _status.value = AsrServiceStatus.Recognizing
              }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
              _currentAmplitude.value = AsrAmplitudeNormalizer.normalizeSystemSpeechRmsDb(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
              if (isStopping || isCancelled) {
                _status.value = AsrServiceStatus.Processing
              }
              _currentAmplitude.value = 0f
            }

            override fun onError(error: Int) {
              val isSilenceError =
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                  error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
              if (isSilenceError && !hasAudioSourcePipe && tryAutoRestart()) {
                return
              }
              val isTransientBusyError =
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                  error == SpeechRecognizer.ERROR_CLIENT
              if (
                wasReusedRecognizer &&
                  !sawReadyForSpeech &&
                  !retriedReusedBusyError &&
                  !isStopping &&
                  !isCancelled &&
                  isTransientBusyError
              ) {
                retriedReusedBusyError = true
                val stale = sessionRecognizer
                speechRecognizer = null
                sessionRecognizer = null
                this@callbackFlow.launch(mainDispatcher) {
                  runCatching { stale?.destroy() }
                  delay(STARTUP_BUSY_RETRY_DELAY_MS)
                  if (isCancelled || activeProducerScope !== this@callbackFlow) {
                    return@launch
                  }
                  if (isStopping) {
                    finishSession()
                    return@launch
                  }
                  runCatching {
                      SpeechRecognizer.createSpeechRecognizer(context).also { fresh ->
                        fresh.setRecognitionListener(listener)
                        sessionRecognizer = fresh
                        speechRecognizer = fresh
                        _status.value = AsrServiceStatus.Recognizing
                        fresh.startListening(sessionIntent)
                      }
                    }
                    .onFailure { retryErr ->
                      Log.w(TAG, "Failed to retry SpeechRecognizer after busy state", retryErr)
                      _currentAmplitude.value = 0f
                      closeAudioStreamImmediately()
                      _status.value = AsrServiceStatus.Failed
                      sessionFinished.complete(Unit)
                      close(AsrServiceError.RecognizerBusy())
                    }
                }
                return
              }
              val stoppedCleanlyByCaller =
                isStopping &&
                  !isCancelled &&
                  (isSilenceError || error == SpeechRecognizer.ERROR_CLIENT)
              if (
                stoppedCleanlyByCaller ||
                  (isSilenceError &&
                    (finalizedSegments.isNotEmpty() ||
                      (!isCancelled && latestPartialSegment.isNotEmpty())))
              ) {
                finishSession()
                return
              }
              Log.w(TAG, "SpeechRecognizer error code: $error")
              val mappedError =
                when (error) {
                  SpeechRecognizer.ERROR_AUDIO -> AsrServiceError.AudioRecordingFailed()
                  SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    AsrServiceError.AudioPermissionDenied()
                  SpeechRecognizer.ERROR_NETWORK,
                  SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                  SpeechRecognizer.ERROR_SERVER -> AsrServiceError.NetworkError()
                  SpeechRecognizer.ERROR_NO_MATCH,
                  SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AsrServiceError.NoSpeechDetected()
                  SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                  SpeechRecognizer.ERROR_CLIENT -> AsrServiceError.RecognizerBusy()
                  else -> AsrServiceError.Unknown("SpeechRecognizer error ($error)")
                }
              _currentAmplitude.value = 0f
              closeAudioStreamImmediately()
              _status.value = AsrServiceStatus.Failed
              sessionFinished.complete(Unit)
              close(mappedError)
            }

            override fun onSegmentResults(segmentResults: Bundle) {
              val matches = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val cumulative = appendSegment(matches?.firstOrNull().orEmpty())
              if (cumulative.isNotEmpty()) {
                val unused = trySend(cumulative)
              }
            }

            override fun onEndOfSegmentedSession() {
              if (hasAudioSourcePipe || !tryAutoRestart()) {
                finishSession()
              }
            }

            override fun onResults(results: Bundle?) {
              val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val cumulative = appendSegment(matches?.firstOrNull().orEmpty())
              if (!hasAudioSourcePipe && tryAutoRestart()) {
                if (cumulative.isNotEmpty()) {
                  val unused = trySend(cumulative)
                }
              } else {
                finishSession()
              }
            }

            override fun onPartialResults(partialResults: Bundle?) {
              val stableText =
                partialResults
                  ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  ?.firstOrNull()
                  ?.trim()
                  .orEmpty()
              val unstableText =
                partialResults
                  ?.getStringArrayList(EXTRA_UNSTABLE_TEXT)
                  ?.firstOrNull()
                  ?.trim()
                  .orEmpty()
              val partialText =
                when {
                  stableText.isEmpty() -> unstableText
                  unstableText.isEmpty() -> stableText
                  else -> "$stableText $unstableText"
                }
              if (partialText.isEmpty()) return
              latestPartialSegment = partialText
              if (!options.showInterimResults) return
              val prefix =
                if (finalizedSegments.isNotEmpty() && !finalizedSegments.endsWith(" ")) {
                  "$finalizedSegments "
                } else {
                  finalizedSegments.toString()
                }
              val unused = trySend(prefix + partialText)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
          }

        val recognizer =
          (existingRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context)).apply {
            setRecognitionListener(recognitionListener)
          }
        sessionRecognizer = recognizer
        speechRecognizer = recognizer

        _status.value = AsrServiceStatus.Recognizing
        recognizer.startListening(sessionIntent)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start SystemSpeechAsrService", e)
        _currentAmplitude.value = 0f
        closeAudioStreamImmediately()
        _status.value = AsrServiceStatus.Failed
        sessionFinished.complete(Unit)
        close(AsrServiceError.Unknown(e.message ?: "Failed to start SpeechRecognizer", e))
      }
    }

    awaitClose {
      isCancelled = true
      sessionFinished.complete(Unit)
      closeAudioStreamImmediately()
      if (activeProducerScope === this) {
        activeProducerScope = null
        activeFinishSession = null
        activeSessionFinished = null
      }
      _currentAmplitude.value = 0f
      val recognizerToCancel = sessionRecognizer
      mainScope.launch {
        runCatching { recognizerToCancel?.cancel() }
        if (speechRecognizer === recognizerToCancel) {
          if (
            _status.value == AsrServiceStatus.Recognizing ||
              _status.value == AsrServiceStatus.Processing
          ) {
            _status.value = AsrServiceStatus.Ready
          }
        }
      }
    }
  }

  /**
   * Marks the session as stopping, flushes any upstream audio pipe, asks the recognizer to finish
   * the active session, and suspends up to [STOP_RECOGNITION_TIMEOUT_MS] for the final decoded
   * result.
   */
  override suspend fun stopRecording() {
    isStopping = true
    flushWriteSideOfAudioStream()
    val sessionFinished =
      withContext(mainDispatcher) {
        _currentAmplitude.value = 0f
        val session = activeProducerScope
        val deferred = activeSessionFinished
        if (session == null || deferred == null) {
          closeAudioStreamImmediately()
          if (
            _status.value == AsrServiceStatus.Recognizing ||
              _status.value == AsrServiceStatus.Processing
          ) {
            _status.value = AsrServiceStatus.Ready
          }
          return@withContext null
        }
        _status.value = AsrServiceStatus.Processing
        runCatching { speechRecognizer?.stopListening() }
          .onFailure {
            Log.w(TAG, "Failed to stop SpeechRecognizer", it)
            activeFinishSession?.invoke() ?: session.close()
            return@withContext null
          }
        deferred
      } ?: return

    val completedInTime = withTimeoutOrNull(STOP_RECOGNITION_TIMEOUT_MS) { sessionFinished.await() }
    if (completedInTime == null) {
      withContext(mainDispatcher) { activeFinishSession?.invoke() }
    }
  }

  override suspend fun cancelRecording() {
    withContext(mainDispatcher) {
      isCancelled = true
      _currentAmplitude.value = 0f
      closeAudioStreamImmediately()
      activeSessionFinished?.complete(Unit)
      activeSessionFinished = null
      activeFinishSession = null
      runCatching { speechRecognizer?.cancel() }
      activeProducerScope?.close()
      activeProducerScope = null
      _status.value = AsrServiceStatus.Ready
    }
  }

  override fun release() {
    isCancelled = true
    _currentAmplitude.value = 0f
    closeAudioStreamImmediately()
    ioScope.coroutineContext.cancelChildren()
    mainScope.coroutineContext.cancelChildren()
    activeSessionFinished?.complete(Unit)
    activeSessionFinished = null
    activeFinishSession = null
    activeProducerScope?.close()
    activeProducerScope = null
    mainScope.launch {
      runCatching { speechRecognizer?.destroy() }
      speechRecognizer = null
      _status.value = AsrServiceStatus.Ready
    }
  }

  companion object {
    internal const val STOP_RECOGNITION_TIMEOUT_MS = 2_000L
    internal const val AUDIO_FLUSH_TIMEOUT_MS = 1_000L
    internal const val STARTUP_BUSY_RETRY_DELAY_MS = 120L
    internal const val EXTRA_UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"

    /**
     * 5-minute ceiling per continuous segmented session segment on API 33+, passed as an [Int]
     * extra.
     */
    internal const val CONTINUOUS_SEGMENT_MAX_LENGTH_MS = 300_000

    /** Audio pipe parameters matching Android [SpeechRecognizer] PCM16 expectations. */
    internal const val AUDIO_SOURCE_SAMPLE_RATE_HZ = 16_000
    internal const val AUDIO_SOURCE_CHANNEL_COUNT = 1
    internal const val AUDIO_PIPE_CHUNK_BYTES = 640

    /**
     * Maximum number of automatic recognizer segment restarts per session before finishing cleanly.
     */
    internal const val MAX_AUTO_RESTARTS = 20

    /**
     * Silence detection thresholds used only when [SystemSpeechSessionMode.SILENCE_TIMEOUT] is
     * selected.
     */
    internal const val DEFAULT_COMPLETE_SILENCE_LENGTH_MS = 3_000
    internal const val DEFAULT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MS = 2_000
    internal const val DEFAULT_MINIMUM_SPEECH_LENGTH_MS = 3_000
  }
}
