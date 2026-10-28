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
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.supplementary.AiCoreVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGMlKitAsrService"

/**
 * [AsrService] implementation backed by ML Kit GenAI Speech Recognition (`MLKIT_BASIC` or
 * `MLKIT_ADVANCED`).
 *
 * [speechRecognizerOverride], [downloadFlowProvider], [recognitionFlowProvider], and
 * [languageDownloadFlowProvider] are test seams that replace the ML Kit client, the model download
 * stream, the recognition stream, and the `MLKIT_BASIC` language-pack download stream respectively.
 */
class MlKitAsrService(
  private val context: Context,
  override val engineType: AsrEngineType = AsrEngineType.MLKIT_BASIC,
  private val speechRecognizerOverride: SpeechRecognizer? = null,
  private val checkStatusProvider: (suspend () -> Int)? = null,
  private val downloadFlowProvider: (() -> Flow<DownloadStatus>)? = null,
  private val speechServicesVersionCodeProvider: () -> Long = {
    SpeechServicesVersion.versionCode(context)
  },
  private val aiCoreVersionCodeProvider: () -> Long = { AiCoreVersion.versionCode(context) },
  private val recognitionFlowProvider:
    ((SpeechRecognizerRequest) -> Flow<SpeechRecognizerResponse>)? =
    null,
  private val languageDownloadFlowProvider: ((String) -> Flow<DownloadStatus>)? = null,
) : AsrService {

  private data class ActiveRecognizerKey(val engineType: AsrEngineType, val localeTag: String)

  /** Baseline byte size used for download progress when the total size is unknown. */
  val modelSizeBytes: Long
    get() =
      if (engineType == AsrEngineType.MLKIT_ADVANCED) {
        ADVANCED_MODEL_SIZE_BYTES
      } else {
        BASIC_MODEL_SIZE_BYTES
      }

  override val activeModel: AsrModelMetadata
    get() =
      if (engineType == AsrEngineType.MLKIT_ADVANCED) {
        AsrModelRegistry.mlKitAdvancedModel()
      } else {
        AsrModelRegistry.mlKitBasicModel()
      }

  override val availableModels: List<AsrModelMetadata>
    get() = listOf(activeModel)

  private val _status = MutableStateFlow<AsrServiceStatus>(AsrServiceStatus.Unknown)
  override val status: StateFlow<AsrServiceStatus> = _status.asStateFlow()

  @Volatile private var _unavailabilityReason: AsrUnavailabilityReason? = null
  override val unavailabilityReason: AsrUnavailabilityReason?
    get() = _unavailabilityReason

  private val _currentAmplitude = MutableStateFlow(0f)
  override val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

  private val recognizerLock = Any()
  @Volatile private var preparedConfig: AsrPreparationConfig = AsrPreparationConfig()
  @Volatile private var cachedRecognizer: SpeechRecognizer? = null
  @Volatile private var activeRecognizerKey: ActiveRecognizerKey? = null

  /**
   * The ML Kit client, rebuilt whenever [preparedConfig] names a different language.
   *
   * [SpeechRecognizerOptions] fixes the recognition [Locale] at construction time, so a client
   * created for `en-US` keeps transcribing English even after the caller switches languages.
   */
  private val speechRecognizer: SpeechRecognizer
    get() {
      speechRecognizerOverride?.let {
        return it
      }
      var staleRecognizer: SpeechRecognizer? = null
      val result =
        synchronized(recognizerLock) {
          val languageTag = preparedConfig.languageCode
          val desiredKey = ActiveRecognizerKey(engineType, languageTag)
          val existing = cachedRecognizer
          if (existing != null && activeRecognizerKey == desiredKey) {
            return@synchronized existing
          }
          if (existing != null) {
            Log.d(TAG, "Locale changed to $languageTag; recreating the ML Kit recognizer")
            staleRecognizer = existing
          }
          val options = speechRecognizerOptions {
            locale = Locale.forLanguageTag(languageTag)
            preferredMode =
              if (engineType == AsrEngineType.MLKIT_ADVANCED) {
                SpeechRecognizerOptions.Mode.MODE_ADVANCED
              } else {
                SpeechRecognizerOptions.Mode.MODE_BASIC
              }
          }
          val created = SpeechRecognition.getClient(options)
          cachedRecognizer = created
          activeRecognizerKey = desiredKey
          created
        }
      staleRecognizer?.let { closeRecognizerInstance(it) }
      return result
    }

  private fun createBasicLanguageClient(languageCode: String): SpeechRecognizer =
    SpeechRecognition.getClient(
      speechRecognizerOptions {
        locale = Locale.forLanguageTag(languageCode)
        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
      }
    )

  private suspend inline fun <T> withBasicLanguageClient(
    languageCode: String,
    block: (SpeechRecognizer) -> T,
  ): T {
    val client = createBasicLanguageClient(languageCode)
    return try {
      block(client)
    } finally {
      closeRecognizerInstance(client)
    }
  }

  private fun closeQuietly(recognizer: SpeechRecognizer) {
    synchronized(recognizerLock) {
      if (cachedRecognizer === recognizer) {
        cachedRecognizer = null
        activeRecognizerKey = null
      }
    }
    closeRecognizerInstance(recognizer)
  }

  private fun closeRecognizerInstance(recognizer: SpeechRecognizer) {
    try {
      recognizer.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing speech recognizer", e)
    }
  }

  @Volatile private var recognitionJob: Job? = null
  @Volatile private var totalBytesToDownload: Long = 0L
  @Volatile private var isCancelled: Boolean = false

  private fun isSpeechServicesUpToDate(): Boolean =
    SpeechServicesVersion.isUpToDate(speechServicesVersionCodeProvider())

  private fun isAiCoreUpToDate(): Boolean = AiCoreVersion.isUpToDate(aiCoreVersionCodeProvider())

  private fun defaultUnavailableReason(): AsrUnavailabilityReason =
    AsrUnavailabilityReason.UNSUPPORTED_DEVICE

  private fun fallbackTotalBytes(): Long = totalBytesToDownload.takeIf { it > 0L } ?: modelSizeBytes

  private fun computeDownloadingStatus(
    downloadedBytes: Long,
    currentTotal: Long,
  ): Pair<AsrServiceStatus.Downloading, Long> {
    val downloaded = downloadedBytes.coerceAtLeast(0L)
    val baseTotal = if (currentTotal > 0L) currentTotal else modelSizeBytes
    val total = maxOf(baseTotal, downloaded)
    val progress = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else 0f
    return AsrServiceStatus.Downloading(progress, downloaded, total) to total
  }

  private fun mapFeatureStatusToAsrStatus(
    featureStatus: Int,
    downloadingBytes: Long,
    clearUnavailabilityReason: Boolean = true,
  ): AsrServiceStatus =
    when (featureStatus) {
      FeatureStatus.AVAILABLE -> {
        if (clearUnavailabilityReason) _unavailabilityReason = null
        AsrServiceStatus.Ready
      }
      FeatureStatus.DOWNLOADABLE -> {
        if (clearUnavailabilityReason) _unavailabilityReason = null
        AsrServiceStatus.NotDownloaded
      }
      FeatureStatus.DOWNLOADING -> {
        if (clearUnavailabilityReason) _unavailabilityReason = null
        AsrServiceStatus.Downloading(
          progress = 0f,
          bytesDownloaded = 0L,
          totalBytes = downloadingBytes,
        )
      }
      else -> {
        _unavailabilityReason = defaultUnavailableReason()
        AsrServiceStatus.Unavailable
      }
    }

  private fun mapFeatureStatusToAvailability(
    featureStatus: Int,
    downloadBytes: Long,
    unavailableReason: SpeechUnavailabilityReason,
  ): SpeechAvailability =
    when (featureStatus) {
      FeatureStatus.AVAILABLE -> SpeechAvailability.Available
      FeatureStatus.DOWNLOADABLE -> SpeechAvailability.NeedsDownload(downloadBytes)
      FeatureStatus.DOWNLOADING ->
        SpeechAvailability.Downloading(receivedBytes = 0L, totalBytes = downloadBytes)
      else -> SpeechAvailability.Unavailable(unavailableReason)
    }

  override suspend fun checkStatus(): AsrServiceStatus {
    Log.d(
      TAG,
      "checkStatus() called: engineType=$engineType, locale=${preparedConfig.languageCode}",
    )
    if (!isSpeechServicesUpToDate()) {
      Log.w(
        TAG,
        "Speech Services (${SpeechServicesVersion.PACKAGE_NAME}) is missing or older than " +
          "${SpeechServicesVersion.MIN_VERSION_CODE}",
      )
      _unavailabilityReason = AsrUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING
      _status.value = AsrServiceStatus.Unavailable
      return AsrServiceStatus.Unavailable
    }
    if (engineType == AsrEngineType.MLKIT_ADVANCED && !isAiCoreUpToDate()) {
      Log.w(
        TAG,
        "AICore (${AiCoreVersion.PACKAGE_NAME}) is missing or older than " +
          "${AiCoreVersion.MIN_VERSION_CODE}",
      )
      _unavailabilityReason = AsrUnavailabilityReason.AICORE_OUTDATED_OR_MISSING
      _status.value = AsrServiceStatus.Unavailable
      return AsrServiceStatus.Unavailable
    }
    if (
      _status.value == AsrServiceStatus.Ready &&
        speechRecognizerOverride == null &&
        checkStatusProvider == null
    ) {
      return AsrServiceStatus.Ready
    }
    return try {
      val featureStatus = checkStatusProvider?.invoke() ?: speechRecognizer.checkStatus()
      Log.d(TAG, "speechRecognizer.checkStatus() raw result: $featureStatus")
      val mapped = mapFeatureStatusToAsrStatus(featureStatus, fallbackTotalBytes())
      _status.value = mapped
      mapped
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error checking MLKit status for engineType=$engineType", e)
      _status.value = AsrServiceStatus.Failed
      AsrServiceStatus.Failed
    }
  }

  override suspend fun checkAvailability(modelId: String): SpeechAvailability {
    Log.d(TAG, "checkAvailability() called: engineType=$engineType")
    if (!isSpeechServicesUpToDate()) {
      Log.w(
        TAG,
        "Speech Services (${SpeechServicesVersion.PACKAGE_NAME}) is missing or older than " +
          "${SpeechServicesVersion.MIN_VERSION_CODE}",
      )
      return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED)
    }
    if (engineType == AsrEngineType.MLKIT_ADVANCED && !isAiCoreUpToDate()) {
      Log.w(
        TAG,
        "AICore (${AiCoreVersion.PACKAGE_NAME}) is missing or older than " +
          "${AiCoreVersion.MIN_VERSION_CODE}",
      )
      return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.AICORE_OUTDATED)
    }

    return try {
      val featureStatus = checkStatusProvider?.invoke() ?: speechRecognizer.checkStatus()
      Log.d(TAG, "speechRecognizer.checkStatus() raw result: $featureStatus")
      val unavailableReason =
        if (
          featureStatus == FeatureStatus.UNAVAILABLE && engineType == AsrEngineType.MLKIT_ADVANCED
        ) {
          SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW
        } else {
          SpeechUnavailabilityReason.DEVICE_NOT_SERVED
        }
      mapFeatureStatusToAvailability(featureStatus, fallbackTotalBytes(), unavailableReason)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Error probing MLKit availability for engineType=$engineType", e)
      SpeechAvailability.ProbeFailed(e)
    }
  }

  /**
   * Downloads the ML Kit model, emitting progress as [AsrServiceStatus.Downloading].
   *
   * When ML Kit reports a failure, [status] becomes [AsrServiceStatus.Failed] and the flow throws
   * the [GenAiException] so the caller can surface its message. Other failures emit
   * [AsrServiceStatus.Failed].
   */
  override fun download(): Flow<AsrServiceStatus> = flow {
    Log.d(TAG, "download() triggered for engineType=$engineType")
    totalBytesToDownload = modelSizeBytes
    val initialState = AsrServiceStatus.Downloading(0f, 0L, totalBytesToDownload)
    _status.value = initialState
    emit(initialState)

    val downloadFlow = downloadFlowProvider?.invoke() ?: speechRecognizer.download()
    downloadFlow
      .map { downloadStatus ->
        Log.d(TAG, "speechRecognizer.download() event: $downloadStatus")
        when (downloadStatus) {
          is DownloadStatus.DownloadStarted -> {
            totalBytesToDownload =
              downloadStatus.bytesToDownload.takeIf { it > 0L } ?: modelSizeBytes
            Log.d(TAG, "Download started, total bytes: $totalBytesToDownload")
            AsrServiceStatus.Downloading(0f, 0L, totalBytesToDownload)
          }
          is DownloadStatus.DownloadProgress -> {
            val (state, updatedTotal) =
              computeDownloadingStatus(downloadStatus.totalBytesDownloaded, totalBytesToDownload)
            totalBytesToDownload = updatedTotal
            Log.d(
              TAG,
              "Download progress: ${state.bytesDownloaded} / ${state.totalBytes} (${state.progress})",
            )
            state
          }
          is DownloadStatus.DownloadCompleted -> {
            Log.d(TAG, "Download completed successfully")
            AsrServiceStatus.Downloaded
          }
          is DownloadStatus.DownloadFailed -> {
            Log.e(TAG, "Download failed with ML Kit error code ${downloadStatus.e.errorCode}")
            // Thrown rather than mapped to Failed so callers can read the SDK message. The catch
            // below updates _status.
            throw downloadStatus.e
          }
        }
      }
      .catch { e ->
        if (e is CancellationException) {
          _status.value = AsrServiceStatus.NotDownloaded
          throw e
        }
        if (e is GenAiException) {
          _status.value = AsrServiceStatus.Failed
          throw e
        }
        Log.e(TAG, "Download exception for engineType=$engineType", e)
        emit(AsrServiceStatus.Failed)
      }
      .collect { state ->
        _status.value = state
        emit(state)
      }
  }

  override suspend fun checkLanguageStatus(languageCode: String): AsrServiceStatus {
    if (!isSpeechServicesUpToDate()) {
      _unavailabilityReason = AsrUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING
      return AsrServiceStatus.Unavailable
    }
    if (engineType != AsrEngineType.MLKIT_BASIC || speechRecognizerOverride != null) {
      return checkStatus()
    }
    return try {
      withBasicLanguageClient(languageCode) { client ->
        mapFeatureStatusToAsrStatus(
          featureStatus = client.checkStatus(),
          downloadingBytes = modelSizeBytes,
          clearUnavailabilityReason = false,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to query ML Kit Basic language pack '$languageCode'", e)
      AsrServiceStatus.Failed
    }
  }

  override suspend fun checkLanguageAvailability(
    modelId: String,
    languageCode: String,
  ): SpeechAvailability {
    if (!isSpeechServicesUpToDate()) {
      return SpeechAvailability.Unavailable(SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED)
    }
    if (engineType != AsrEngineType.MLKIT_BASIC || speechRecognizerOverride != null) {
      return checkAvailability(modelId)
    }
    return try {
      withBasicLanguageClient(languageCode) { client ->
        mapFeatureStatusToAvailability(
          featureStatus = client.checkStatus(),
          downloadBytes = BASIC_MODEL_SIZE_BYTES,
          unavailableReason = SpeechUnavailabilityReason.LOCALE_NOT_OFFERED,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to probe ML Kit Basic language pack '$languageCode'", e)
      SpeechAvailability.ProbeFailed(e)
    }
  }

  /**
   * Downloads the `MLKIT_BASIC` language pack for [languageCode], or the whole model via [download]
   * for other engines.
   *
   * Like [download], an ML Kit failure is thrown as its [GenAiException] so the caller can surface
   * its message; other failures emit [AsrServiceStatus.Failed].
   */
  override fun downloadLanguage(languageCode: String): Flow<AsrServiceStatus> = flow {
    if (engineType != AsrEngineType.MLKIT_BASIC) {
      download().collect { emit(it) }
      return@flow
    }
    val providedDownloadFlow = languageDownloadFlowProvider?.invoke(languageCode)
    // A client created here is owned by this download and closed when it ends.
    val ownedClient =
      if (providedDownloadFlow == null && speechRecognizerOverride == null) {
        createBasicLanguageClient(languageCode)
      } else {
        null
      }
    val languageDownloadFlow =
      providedDownloadFlow ?: checkNotNull(speechRecognizerOverride ?: ownedClient).download()
    var expectedTotal = modelSizeBytes
    emit(AsrServiceStatus.Downloading(0f, 0L, expectedTotal))
    try {
      languageDownloadFlow
        .map { downloadStatus ->
          when (downloadStatus) {
            is DownloadStatus.DownloadStarted -> {
              expectedTotal = downloadStatus.bytesToDownload.takeIf { it > 0L } ?: modelSizeBytes
              AsrServiceStatus.Downloading(0f, 0L, expectedTotal)
            }
            is DownloadStatus.DownloadProgress -> {
              val (state, updatedTotal) =
                computeDownloadingStatus(downloadStatus.totalBytesDownloaded, expectedTotal)
              expectedTotal = updatedTotal
              state
            }
            is DownloadStatus.DownloadCompleted -> AsrServiceStatus.Downloaded
            is DownloadStatus.DownloadFailed -> {
              Log.e(
                TAG,
                "ML Kit Basic language pack '$languageCode' download failed with error code " +
                  "${downloadStatus.e.errorCode}",
              )
              throw downloadStatus.e
            }
          }
        }
        .catch { e ->
          if (e is CancellationException || e is GenAiException) throw e
          Log.e(TAG, "Failed to download ML Kit Basic language pack '$languageCode'", e)
          emit(AsrServiceStatus.Failed)
        }
        .collect { emit(it) }
    } finally {
      ownedClient?.let { closeRecognizerInstance(it) }
    }
  }

  override suspend fun prepare(config: AsrPreparationConfig) {
    if (this.preparedConfig == config && _status.value == AsrServiceStatus.Ready) {
      return
    }
    this.preparedConfig = config
    try {
      _status.value = AsrServiceStatus.Initializing
      speechRecognizer.warmup()
      _status.value = AsrServiceStatus.Ready
    } catch (e: CancellationException) {
      _status.value = AsrServiceStatus.Downloaded
      throw e
    } catch (e: AsrServiceError) {
      _status.value = AsrServiceStatus.Failed
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Warmup/prepare failed", e)
      _status.value = AsrServiceStatus.Failed
      throw AsrServiceError.ModelInitializationFailed(e)
    }
  }

  override fun startRecording(options: AsrRecordingOptions): Flow<String> = callbackFlow {
    val externalAudioSource = options.externalAudioSource
    if (
      externalAudioSource == null &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      _status.value = AsrServiceStatus.Failed
      close(AsrServiceError.AudioPermissionDenied())
      return@callbackFlow
    }

    isCancelled = false
    _currentAmplitude.value = 0f
    _status.value = AsrServiceStatus.Recognizing

    val request = speechRecognizerRequest {
      audioSource =
        externalAudioSource?.invoke()?.let { AudioSource.fromPfd(it) } ?: AudioSource.fromMic()
    }

    val job = launch {
      recognitionJob = currentCoroutineContext().job
      // ML Kit runs the mic session in multi-segment mode: a FinalTextResponse is emitted every
      // time an utterance segment is closed (a pause), not when recording ends. Ending the
      // session here would truncate the user at their first breath, so segments are accumulated
      // and only CompletedResponse — emitted once stopRecognition() drains the stream — finishes
      // the session.
      val finalizedSegments = StringBuilder()
      var latestPartialText = ""
      var sessionTerminated = false
      try {
        val recognitionFlow =
          recognitionFlowProvider?.invoke(request) ?: speechRecognizer.startRecognition(request)
        recognitionFlow.collect { response ->
          if (isCancelled) return@collect
          when (response) {
            is SpeechRecognizerResponse.PartialTextResponse -> {
              latestPartialText = response.text.trim()
              formatPartialTranscript(
                  finalizedSegments = finalizedSegments,
                  partialText = response.text,
                  showInterimResults = options.showInterimResults,
                )
                ?.let {
                  val unused = trySend(it)
                }
            }
            is SpeechRecognizerResponse.FinalTextResponse -> {
              latestPartialText = ""
              appendFinalSegment(finalizedSegments = finalizedSegments, segmentText = response.text)
                ?.let {
                  val unused = trySend(it)
                }
            }
            is SpeechRecognizerResponse.CompletedResponse -> {
              sessionTerminated = true
              _status.value = AsrServiceStatus.Processing
              resolveCompletedTranscript(
                  finalizedSegments = finalizedSegments,
                  latestPartialText = latestPartialText,
                  isCancelled = isCancelled,
                )
                ?.let { finalText ->
                  val unused = trySend(finalText)
                }
              latestPartialText = ""
              _currentAmplitude.value = 0f
              _status.value = AsrServiceStatus.Ready
              channel.close()
            }
            is SpeechRecognizerResponse.AudioLevelResponse -> {
              _currentAmplitude.value = normalizeMlKitAudioLevel(response.audioLevel)
            }
            is SpeechRecognizerResponse.ErrorResponse -> {
              sessionTerminated = true
              Log.e(
                TAG,
                "MLKit speech recognition error code ${response.e.errorCode}: " +
                  "${response.e.message}",
              )
              _currentAmplitude.value = 0f
              val err = asrServiceErrorFor(response.e)
              _status.value = AsrServiceStatus.Failed
              runCatching { (speechRecognizerOverride ?: cachedRecognizer)?.stopRecognition() }
              channel.close(err)
            }
          }
        }
      } catch (e: CancellationException) {
        if (!sessionTerminated) {
          withContext(NonCancellable) {
            runCatching { (speechRecognizerOverride ?: cachedRecognizer)?.stopRecognition() }
          }
        }
        throw e
      } catch (e: Exception) {
        if (!isCancelled) {
          sessionTerminated = true
          Log.e(TAG, "Exception in recognition stream", e)
          _currentAmplitude.value = 0f
          val err =
            when (e) {
              is AsrServiceError -> e
              is GenAiException -> {
                Log.e(TAG, "MLKit speech recognition error code ${e.errorCode}")
                asrServiceErrorFor(e)
              }
              else -> AsrServiceError.Unknown(e.message ?: "Unknown ML Kit ASR error.", e)
            }
          _status.value = AsrServiceStatus.Failed
          runCatching { (speechRecognizerOverride ?: cachedRecognizer)?.stopRecognition() }
          channel.close(err)
        }
      } finally {
        recognitionJob = null
        _currentAmplitude.value = 0f
        channel.close()
      }
    }

    awaitClose {
      job.cancel()
      recognitionJob = null
      _currentAmplitude.value = 0f
      if (
        _status.value is AsrServiceStatus.Recognizing ||
          _status.value is AsrServiceStatus.Processing
      ) {
        _status.value = AsrServiceStatus.Ready
      }
    }
  }

  override suspend fun stopRecording() {
    if (!isCancelled && _status.value is AsrServiceStatus.Recognizing) {
      _status.value = AsrServiceStatus.Processing
    }
    try {
      // Read the backing field: going through the accessor would instantiate a client just to
      // close it when recognition never started.
      val active = speechRecognizerOverride ?: cachedRecognizer
      if (active != null) {
        active.stopRecognition()
      }
      if (!isCancelled) {
        withTimeoutOrNull(STOP_RECOGNITION_TIMEOUT_MS) { recognitionJob?.join() }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping recognition", e)
    } finally {
      recognitionJob?.cancel()
      recognitionJob = null
      _currentAmplitude.value = 0f
      if (
        _status.value is AsrServiceStatus.Recognizing ||
          _status.value is AsrServiceStatus.Processing
      ) {
        _status.value = AsrServiceStatus.Ready
      }
    }
  }

  override suspend fun cancelRecording() {
    isCancelled = true
    stopRecording()
  }

  override fun release() {
    isCancelled = true
    recognitionJob?.cancel()
    recognitionJob = null
    _currentAmplitude.value = 0f
    cachedRecognizer?.let { closeQuietly(it) }
    val current = _status.value
    if (
      current == AsrServiceStatus.Ready ||
        current == AsrServiceStatus.Initializing ||
        current == AsrServiceStatus.Recognizing ||
        current == AsrServiceStatus.Processing
    ) {
      _status.value = AsrServiceStatus.Downloaded
    }
  }

  companion object {
    private const val STOP_RECOGNITION_TIMEOUT_MS = 2000L
    const val AICORE_DEV_PREVIEW_GUIDE_URL =
      "https://developers.google.com/ml-kit/genai/aicore-dev-preview"

    /** Baseline model size (45.0 MB) for ML Kit Basic ASR. */
    const val BASIC_MODEL_SIZE_BYTES: Long = AsrModelRegistry.MLKIT_BASIC_MODEL_SIZE_BYTES

    /** Baseline model size (1.5 GB) for ML Kit Advanced ASR. */
    const val ADVANCED_MODEL_SIZE_BYTES: Long = AsrModelRegistry.MLKIT_ADVANCED_MODEL_SIZE_BYTES

    /**
     * Normalizes ML Kit's 0..100 raw audio level to a calibrated [0f, 1f] range. Subtracts ambient
     * baseline noise floor (~15) and scales over active speech range.
     */
    internal fun normalizeMlKitAudioLevel(audioLevel: Int): Float =
      AsrAmplitudeNormalizer.normalizeMlKitLevel(audioLevel)

    /**
     * Maps an ML Kit [GenAiException] from speech recognition to the [AsrServiceError] the UI
     * handles, keeping [e] as the cause.
     *
     * Codes without a typed equivalent map to [AsrServiceError.Unknown] with the code in its
     * message.
     */
    internal fun asrServiceErrorFor(e: GenAiException): AsrServiceError =
      when (e.errorCode) {
        GenAiException.ErrorCode.NOT_SUPPORTED -> AsrServiceError.ModelNotDownloaded(e)
        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> AsrServiceError.HardwareNotSupported(e)
        GenAiException.ErrorCode.BUSY,
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> AsrServiceError.RecognizerBusy(e)
        GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW -> AsrServiceError.AudioRecordingFailed(e)
        else ->
          AsrServiceError.Unknown(
            "ML Kit ASR error (code ${e.errorCode}): ${e.message ?: "no message"}",
            e,
          )
      }

    /** Builds the interim transcript snapshot from [finalizedSegments] + live [partialText]. */
    internal fun formatPartialTranscript(
      finalizedSegments: CharSequence,
      partialText: String,
      showInterimResults: Boolean,
    ): String? {
      if (!showInterimResults || partialText.isEmpty()) return null
      val prefix =
        if (finalizedSegments.isNotEmpty() && !finalizedSegments.endsWith(" ")) {
          "$finalizedSegments "
        } else {
          finalizedSegments.toString()
        }
      return prefix + partialText
    }

    /**
     * Appends a finalized segment to [finalizedSegments] and returns the updated cumulative text.
     */
    internal fun appendFinalSegment(
      finalizedSegments: StringBuilder,
      segmentText: String,
    ): String? {
      val segment = segmentText.trim()
      if (segment.isEmpty()) return null
      if (finalizedSegments.isNotEmpty() && !finalizedSegments.endsWith(" ")) {
        finalizedSegments.append(' ')
      }
      finalizedSegments.append(segment)
      return finalizedSegments.toString()
    }

    /**
     * Resolves the final transcript emitted on `CompletedResponse`, promoting any uncommitted
     * [latestPartialText] spoken right before stop unless [isCancelled] is true.
     */
    internal fun resolveCompletedTranscript(
      finalizedSegments: StringBuilder,
      latestPartialText: String,
      isCancelled: Boolean,
    ): String? {
      if (isCancelled) return null
      if (latestPartialText.isNotEmpty()) {
        val unused = appendFinalSegment(finalizedSegments, latestPartialText)
      }
      return finalizedSegments.toString().trim().takeIf { it.isNotEmpty() }
    }
  }
}
