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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.asrRecordingErrorCause
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveEngineLocaleTagForLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toLocale
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.ASR_ERROR_NO_SPEECH
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.ASR_FINALIZE_TIMEOUT_MS
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.XrEarconType
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.data.supplementary.toSpeechEngineErrorCause
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrRecordingOptions
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceError
import com.google.ai.edge.gallery.services.asr.AsrServiceStatus
import com.google.ai.edge.gallery.services.asr.AsrSessionSettings
import com.google.ai.edge.gallery.services.asr.AsrUnavailabilityReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Returns the BCP-47 locales reported by the ASR engine or [AsrModelRegistry] for [modelId], or an
 * empty list when unrestricted (such as System Default ASR).
 */
internal fun XrLanguageTeacherViewModel.getSupportedLocaleTagsForAsrModel(
  modelId: String
): List<String> {
  val engineType = AsrModelRegistry.resolveEngineType(modelId)
  val engineTags =
    engineType
      ?.let { type ->
        asrServiceProvider
          .serviceOrNull(type)
          ?.availableModels
          ?.find { it.modelId == modelId }
          ?.supportedLanguages
      }
      .orEmpty()
  if (engineTags.isNotEmpty()) return engineTags
  // Allowlist models (for example Parakeet) are saved by name, which the engine may not report.
  AsrModelRegistry.model(modelId)?.let {
    return AsrModelRegistry.supportedLanguages(it)
  }
  return AsrModelRegistry.availableModels()
    .find { it.modelId == modelId }
    ?.supportedLanguages
    .orEmpty()
}

/**
 * Returns the locale tag to recognize [mainLang] with: the selected ASR engine's own tag for it
 * (such as `"cmn-Hans-CN"` for Chinese with ML Kit), so that recognition uses the same locale as
 * the engine's language pack, or the language's default tag when the engine doesn't list it.
 */
internal fun XrLanguageTeacherViewModel.resolveHostAsrLanguageTag(
  mainLang: Language = mainLanguage.value
): String {
  val supportedTags = getSupportedLocaleTagsForAsrModel(selectedAsrModelID.value)
  return resolveEngineLocaleTagForLanguage(mainLang, supportedTags)
    ?: mainLang.toLocale().toLanguageTag().ifBlank { "en-US" }
}

/**
 * The [AsrService] of the selected engine, or `null` if the selected model has no engine yet. Never
 * falls back to another engine.
 */
internal fun XrLanguageTeacherViewModel.resolveSelectedAsrService(): AsrService? =
  activeAsrService ?: selectedAsrEngineType?.let { asrServiceProvider.serviceOrNull(it) }

/** The selected ASR engine once prepared, or why it couldn't start. */
@VisibleForTesting
internal data class AsrPreparation(
  val service: AsrService? = null,
  val errorCause: SpeechEngineErrorCause? = null,
)

/**
 * Checks and prepares the selected ASR engine to recognize [hostLanguageTag], with no fallback to
 * another engine.
 *
 * Fails with [SpeechEngineErrorCause.NOT_SUPPORTED] when the selected model has no engine yet (for
 * example a LiteRT-LM speech recognition model), or when the allowlist models are registered and
 * none of them is the selected model (the device hides it). Otherwise the selected model's
 * `SpeechModelState` decides, mapped by [toSpeechEngineErrorCause]. For engines that install a
 * language pack per language (ML Kit Basic), the `LanguageState` of [hostLanguageTag] must be
 * installed too. The system recognizer has no model check.
 */
@VisibleForTesting
internal suspend fun XrLanguageTeacherViewModel.prepareSelectedAsrEngine(
  hostLanguageTag: String
): AsrPreparation {
  val engineType = selectedAsrEngineType
  val service = engineType?.let { asrServiceProvider.serviceOrNull(it) }
  if (engineType == null || service == null) {
    Log.w(TAG, "No ASR engine for the selected model '${selectedAsrModelID.value}'")
    return AsrPreparation(errorCause = SpeechEngineErrorCause.NOT_SUPPORTED)
  }
  val installsLanguagePacks = AsrModelRegistry.metadataFor(engineType).installsLanguagePacks
  return try {
    val model = speechSelectionUseCase.asrModelFor(engineType, selectedAsrModelID.value)
    val engineCause =
      when {
        model != null -> speechSelectionUseCase.resolveState(model).toSpeechEngineErrorCause()
        !AsrModelRegistry.isSystemEngine(engineType) &&
          speechSelectionUseCase.hasRegisteredModels(SpeechModality.ASR) ->
          SpeechEngineErrorCause.NOT_SUPPORTED
        else -> null
      }
    val languageCause =
      if (engineCause == null && installsLanguagePacks) {
        if (model != null) {
          speechSelectionUseCase
            .resolveLanguageState(model, hostLanguageTag)
            .toSpeechEngineErrorCause()
        } else {
          when (service.checkLanguageStatus(hostLanguageTag)) {
            AsrServiceStatus.Ready -> null
            AsrServiceStatus.NotDownloaded -> SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED
            AsrServiceStatus.Unavailable ->
              if (
                service.unavailabilityReason ==
                  AsrUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING
              ) {
                SpeechEngineErrorCause.SPEECH_SERVICES_OUTDATED
              } else {
                SpeechEngineErrorCause.NOT_SUPPORTED
              }
            else -> SpeechEngineErrorCause.OTHER
          }
        }
      } else {
        null
      }
    val cause = engineCause ?: languageCause
    if (cause != null) {
      Log.w(TAG, "The $engineType ASR engine can't start for '$hostLanguageTag': $cause")
      return AsrPreparation(errorCause = cause)
    }
    val settings =
      AsrSessionSettings(
        modelId = selectedAsrModelID.value,
        languageCode = hostLanguageTag,
        showInterimResults = true,
      )
    val prepared =
      asrServiceProvider.prepareServiceOrNull(engineType, settings.preparationConfig())
        ?: return AsrPreparation(errorCause = SpeechEngineErrorCause.NOT_SUPPORTED)
    AsrPreparation(service = prepared)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "ASR preparation failed", e)
    AsrPreparation(errorCause = SpeechEngineErrorCause.OTHER)
  }
}

/**
 * Warms up the ASR and TTS engines. Each engine is the selected one or reports an error; neither
 * falls back to another engine.
 */
fun XrLanguageTeacherViewModel.prepareSpeechEngines() {
  prepareAsrEngine()
  prepareTtsEngine()
}

/** Warms up the selected ASR engine and publishes its readiness. */
internal fun XrLanguageTeacherViewModel.prepareAsrEngine() {
  val initialModelId = selectedAsrModelID.value
  markAsrWarming(engine = SpeechEngine.fromAsrModelId(initialModelId))
  val previousJob = prepareSpeechJob
  prepareSpeechJob = viewModelScope.launch {
    // Native preparation isn't cancellable, so wait for the previous one to finish.
    previousJob?.cancelAndJoin()
    val modelId = selectedAsrModelID.value
    updateUiState {
      it.copy(
        asrEngine = SpeechEngine.fromAsrModelId(modelId) ?: it.asrEngine,
        asrReadiness = SpeechEngineReadiness.WARMING,
        asrErrorCause = null,
      )
    }
    val preparation = prepareSelectedAsrEngine(resolveHostAsrLanguageTag())
    val service = preparation.service
    if (service != null) {
      activeAsrService = service
      updateUiState { it.copy(asrReadiness = SpeechEngineReadiness.READY, asrErrorCause = null) }
    } else {
      reportAsrEngineFailure(preparation.errorCause ?: SpeechEngineErrorCause.OTHER)
    }
    completePendingGreetingIfReady()
  }
}

/**
 * Marks the selected ASR engine as failed because of [cause] and pauses the lesson: any recording
 * ends and the teacher stops speaking. The screen then shows the speech engine error popup. Nothing
 * retries automatically.
 */
internal fun XrLanguageTeacherViewModel.reportAsrEngineFailure(cause: SpeechEngineErrorCause) {
  Log.w(TAG, "The selected ASR engine failed: $cause")
  updateUiState {
    it.copy(
      asrReadiness = SpeechEngineReadiness.ERROR,
      asrErrorCause = cause,
      isSpeechEngineErrorDismissed = false,
    )
  }
  pauseLessonForSpeechEngineFailure()
}

/**
 * Pauses the lesson after a speech engine failure: ends any recording and stops the teacher's
 * speech. The lesson itself is kept, so it continues once the engine works again.
 */
internal fun XrLanguageTeacherViewModel.pauseLessonForSpeechEngineFailure() {
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  }
  updateUiState { it.copy(isSubmittingToModel = false) }
  if (uiState.value.isSpeakingTts) {
    stopTtsPlayback()
  }
  if (uiState.value.isXrMode) {
    xrGlassesManager.playEarcon(XrEarconType.ERROR)
  }
}

internal fun XrLanguageTeacherViewModel.joinTranscript(base: String, addition: String): String {
  val trimmedBase = base.trim()
  val trimmedAddition = addition.trim()
  return when {
    trimmedBase.isEmpty() -> trimmedAddition
    trimmedAddition.isEmpty() -> trimmedBase
    else -> "$trimmedBase $trimmedAddition"
  }
}

/**
 * Whether an ASR turn is recording, or has been requested and is still preparing the engine.
 *
 * [XrLanguageTeacherUiState.isRecordingAsr] only becomes `true` once the engine is ready and the
 * microphone is capturing, so stop and cancel paths must use this to also abort a pending start.
 */
internal val XrLanguageTeacherViewModel.isAsrRecordingOrPending: Boolean
  get() = uiState.value.isRecordingAsr || asrRecordingJob?.isActive == true

fun XrLanguageTeacherViewModel.startAsrRecording(initialPrefix: String = "") {
  if (isAsrRecordingOrPending) return
  if (uiState.value.hasSpeechEngineError) {
    // The lesson is paused until the failed engine works again; show why instead of recording.
    reshowSpeechEngineError()
    if (uiState.value.isXrMode) {
      xrGlassesManager.playEarcon(XrEarconType.ERROR)
    }
    return
  }
  val epoch = asrSessionEpoch.incrementAndGet()
  isStoppingAsr = false
  // Stop the teacher's speech; the recording waits for the engine stop below.
  cancelFillerPresynthesis()
  stopTtsPlayback()
  val pendingTtsStop = ttsStopJob
  val initialConfirmed = initialPrefix.trim()
  updateUiState {
    it.copy(
      isSubmittingToModel = false,
      lastAsrError = "",
      liveAsrTranscript = initialConfirmed,
      confirmedTranscript = initialConfirmed,
      interimTranscript = "",
      audioLevel = 0f,
    )
  }

  asrRecordingJob?.cancel()
  asrAmplitudeJob?.cancel()
  asrRecordingJob = viewModelScope.launch {
    try {
      pendingTtsStop?.join()
      prepareSpeechJob?.join()
      if (asrSessionEpoch.get() != epoch || isStoppingAsr) {
        return@launch
      }
      if (uiState.value.asrReadiness == SpeechEngineReadiness.ERROR) {
        // The engine already failed; show the error again instead of retrying automatically.
        reportAsrEngineFailure(uiState.value.asrErrorCause ?: SpeechEngineErrorCause.OTHER)
        return@launch
      }
      val preparation = prepareSelectedAsrEngine(resolveHostAsrLanguageTag())
      val service = preparation.service
      if (service == null) {
        reportAsrEngineFailure(preparation.errorCause ?: SpeechEngineErrorCause.OTHER)
        return@launch
      }
      activeAsrService = service
      if (asrSessionEpoch.get() != epoch || isStoppingAsr) {
        updateUiState { it.copy(asrReadiness = SpeechEngineReadiness.READY, asrErrorCause = null) }
        return@launch
      }
      updateUiState {
        it.copy(
          isRecordingAsr = true,
          asrReadiness = SpeechEngineReadiness.READY,
          asrErrorCause = null,
        )
      }
      asrAmplitudeJob = viewModelScope.launch {
        service.currentAmplitude.collect { level ->
          if (asrSessionEpoch.get() == epoch && uiState.value.isRecordingAsr) {
            updateUiState { it.copy(audioLevel = level) }
          }
        }
      }
      startRecognitionSegment(
        epoch = epoch,
        segmentBaseTranscript = uiState.value.confirmedTranscript,
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e(TAG, "Failed to start ASR recording", e)
      reportAsrFailure(reason = "start failed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }
}

/**
 * Ends the current ASR attempt as failed and records [reason] in
 * [XrLanguageTeacherUiState.lastAsrError], which the XR status line shows as "Couldn't hear you,
 * tap to retry". In XR mode it also plays the error earcon, because the glasses are audio-only and
 * the user can't see the phone.
 */
/** Ends a recording in which nothing was heard without sending a turn to the model. */
fun XrLanguageTeacherViewModel.onNoSpeechHeard() {
  updateUiState { it.copy(isSubmittingToModel = false) }
  reportAsrFailure(reason = ASR_ERROR_NO_SPEECH)
}

internal fun XrLanguageTeacherViewModel.reportAsrFailure(reason: String) {
  updateUiState {
    it.copy(
      isRecordingAsr = false,
      interimTranscript = "",
      audioLevel = 0f,
      lastAsrError = reason,
      capturedBitmap = if (it.isSessionActive) it.capturedBitmap else null,
      errorMessage =
        if (!it.isSessionActive && reason != ASR_ERROR_NO_SPEECH) {
          storageRepository.getString(R.string.asr_error_unavailable)
        } else {
          it.errorMessage
        },
    )
  }
  if (uiState.value.isXrMode) {
    xrGlassesManager.playEarcon(XrEarconType.ERROR)
  }
}

internal suspend fun XrLanguageTeacherViewModel.startRecognitionSegment(
  epoch: Long,
  segmentBaseTranscript: String,
) {
  if (asrSessionEpoch.get() != epoch || !uiState.value.isRecordingAsr || isStoppingAsr) return
  val externalAudioSource: (() -> ParcelFileDescriptor?)? =
    if (uiState.value.isXrMode) {
      { xrGlassesManager.openGlassesMicStream() }
    } else {
      null
    }
  val service =
    resolveSelectedAsrService()
      ?: run {
        reportAsrEngineFailure(SpeechEngineErrorCause.NOT_SUPPORTED)
        return
      }
  var latestSegmentText = ""
  var segmentFailed = false
  // XR Language Teacher's HUD and continuous-dictation loop rely on live interim transcripts.
  service
    .startRecording(
      options =
        AsrRecordingOptions(showInterimResults = true, externalAudioSource = externalAudioSource)
    )
    .catch { e ->
      segmentFailed = true
      val error = e as? AsrServiceError ?: AsrServiceError.Unknown(e.message ?: "ASR error", e)
      if (asrSessionEpoch.get() != epoch || !uiState.value.isRecordingAsr) return@catch
      if (error is AsrServiceError.NoSpeechDetected && !isStoppingAsr) {
        Log.d(TAG, "Non-fatal ASR silence timeout; restarting segment for continuous dictation")
        val newConfirmed =
          if (latestSegmentText.isNotBlank()) {
            joinTranscript(segmentBaseTranscript, latestSegmentText)
          } else {
            uiState.value.confirmedTranscript
          }
        updateUiState {
          it.copy(
            confirmedTranscript = newConfirmed,
            liveAsrTranscript = newConfirmed,
            interimTranscript = "",
            audioLevel = 0f,
          )
        }
        scheduleNextRecognitionSegmentIfNeeded(epoch)
      } else {
        Log.w(TAG, "ASR recording error: $error")
        xrGlassesManager.stopGlassesMicStream()
        val engineType = selectedAsrEngineType
        val installsLanguagePacks =
          engineType?.let { AsrModelRegistry.metadataFor(it).installsLanguagePacks } == true
        val engineCause =
          asrRecordingErrorCause(error = error, installsLanguagePacks = installsLanguagePacks)
        if (engineCause != null) {
          reportAsrEngineFailure(engineCause)
        } else {
          reportAsrFailure(reason = error.toString())
        }
      }
    }
    .collect { segmentText ->
      if (asrSessionEpoch.get() != epoch || !uiState.value.isRecordingAsr) return@collect
      latestSegmentText = segmentText
      updateUiState { current ->
        val total = joinTranscript(segmentBaseTranscript, segmentText)
        val confirmed = current.confirmedTranscript
        val interim =
          if (
            confirmed.isNotEmpty() && total.startsWith(confirmed) && total.length > confirmed.length
          ) {
            total.substring(confirmed.length).trimStart()
          } else {
            segmentText.trim()
          }
        current.copy(liveAsrTranscript = total, interimTranscript = interim)
      }
    }

  if (!segmentFailed && asrSessionEpoch.get() == epoch && uiState.value.isRecordingAsr) {
    val total =
      if (latestSegmentText.isNotBlank()) {
        joinTranscript(segmentBaseTranscript, latestSegmentText)
      } else {
        uiState.value.confirmedTranscript
      }
    if (latestSegmentText.isNotBlank()) {
      updateUiState {
        it.copy(liveAsrTranscript = total, confirmedTranscript = total, interimTranscript = "")
      }
    }
    if (!isStoppingAsr && uiState.value.isRecordingAsr) {
      scheduleNextRecognitionSegmentIfNeeded(epoch)
    }
  }
}

internal fun XrLanguageTeacherViewModel.scheduleNextRecognitionSegmentIfNeeded(epoch: Long) {
  val service = resolveSelectedAsrService() ?: return
  asrRecordingJob = viewModelScope.launch {
    yield()
    if (
      asrSessionEpoch.get() != epoch ||
        isStoppingAsr ||
        !uiState.value.isRecordingAsr ||
        service.status.value is AsrServiceStatus.Recognizing
    ) {
      return@launch
    }
    try {
      startRecognitionSegment(
        epoch = epoch,
        segmentBaseTranscript = uiState.value.confirmedTranscript,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to restart ASR recognition segment", e)
      reportAsrFailure(reason = "restart failed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }
}

fun XrLanguageTeacherViewModel.stopAsrRecording(onResult: (String) -> Unit) {
  isStoppingAsr = true
  updateUiState { it.copy(isSubmittingToModel = true) }
  viewModelScope.launch {
    try {
      // Flush any already-captured glasses PCM frames into the ASR pipe before signaling EOF.
      xrGlassesManager.finishGlassesMicStream()
      activeAsrService?.stopRecording() ?: asrServiceProvider.stopAll()
      withTimeoutOrNull(ASR_FINALIZE_TIMEOUT_MS) { asrRecordingJob?.join() }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "Error stopping ASR recording", e)
    }
    asrSessionEpoch.incrementAndGet()
    asrRecordingJob?.cancel()
    asrRecordingJob = null
    asrAmplitudeJob?.cancel()
    asrAmplitudeJob = null
    val finalTranscript =
      uiState.value.liveAsrTranscript.ifBlank { uiState.value.confirmedTranscript }.trim()
    updateUiState {
      it.copy(
        isRecordingAsr = false,
        liveAsrTranscript = finalTranscript,
        confirmedTranscript = finalTranscript,
        interimTranscript = "",
        audioLevel = 0f,
        lastSpokenQuestion = finalTranscript.ifBlank { null },
      )
    }
    onResult(finalTranscript)
  }
}

fun XrLanguageTeacherViewModel.cancelAsrRecording() {
  isStoppingAsr = true
  asrSessionEpoch.incrementAndGet()
  asrRecordingJob?.cancel()
  asrRecordingJob = null
  asrAmplitudeJob?.cancel()
  asrAmplitudeJob = null
  updateUiState {
    it.copy(
      isRecordingAsr = false,
      isSubmittingToModel = false,
      liveAsrTranscript = "",
      confirmedTranscript = "",
      interimTranscript = "",
      audioLevel = 0f,
      capturedBitmap = if (it.isSessionActive) it.capturedBitmap else null,
    )
  }
  viewModelScope.launch {
    try {
      xrGlassesManager.stopGlassesMicStream()
      activeAsrService?.cancelRecording() ?: asrServiceProvider.cancelAll()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "Error cancelling ASR recording", e)
    }
  }
}

fun XrLanguageTeacherViewModel.clearAsrTranscript() {
  updateUiState {
    it.copy(
      liveAsrTranscript = "",
      confirmedTranscript = "",
      interimTranscript = "",
      audioLevel = 0f,
    )
  }
}
