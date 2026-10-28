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

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaVoices
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveEngineLocaleTag
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveEngineLocaleTagForLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.spokenphrases.ResolvedSpokenPhrases
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.spokenphrases.XrLanguageTeacherSpokenPhrases
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.data.supplementary.SpeechModality
import com.google.ai.edge.gallery.data.supplementary.toSpeechEngineErrorCause
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.tts.PreStartRetries
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsQueueMode
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceError
import com.google.ai.edge.gallery.services.tts.TtsServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns the BCP-47 tags of the languages the TTS model [modelId] speaks, or an empty list when it
 * doesn't restrict its languages or they aren't known yet.
 *
 * Allowlist models report the languages of their allowlist entry. Other engines, such as the system
 * engine (which lists the voices installed on the device once it has initialized), and allowlist
 * models before the allowlist registers them, report what the engine lists.
 */
internal fun XrLanguageTeacherViewModel.getSupportedLocaleTagsForTtsModel(
  modelId: String
): List<String> {
  speechSelectionUseCase.ttsModelFor(modelId)?.let { model ->
    return speechSelectionUseCase.supportedLanguageCodes(model)
  }
  return ttsServiceProvider
    .serviceOrNull(forModelId = modelId)
    ?.availableModels
    ?.find { it.modelId == modelId }
    ?.supportedLanguages
    .orEmpty()
}

/** The HUD [SpeechEngine] for the TTS engine [modelId], or `null` if it has none. */
internal fun ttsSpeechEngineFor(modelId: String?): SpeechEngine? =
  when {
    modelId == TtsModelIdentifier.ML_KIT -> SpeechEngine.MLKIT_TTS
    modelId == TtsModelIdentifier.MEDIAPIPE_KOKORO_82M -> SpeechEngine.KOKORO_TTS
    modelId == TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M -> SpeechEngine.POCKET_TTS
    TtsModelIdentifier.isSystemEngine(modelId) -> SpeechEngine.PLATFORM_TTS
    else -> null
  }

/** Warms up the selected TTS engine and publishes its readiness. */
internal fun XrLanguageTeacherViewModel.prepareTtsEngine(keepReadyState: Boolean = false) {
  val initialModelId = selectedTtsModelID.value
  fun shouldShowWarmingState(): Boolean =
    !keepReadyState || uiState.value.ttsReadiness != SpeechEngineReadiness.READY
  if (shouldShowWarmingState()) {
    markTtsWarming(engine = ttsSpeechEngineFor(initialModelId))
  }
  val previousJob = prepareTtsJob
  prepareTtsJob = viewModelScope.launch {
    // Native preparation isn't cancellable, so wait for the previous one to finish.
    previousJob?.cancelAndJoin()
    // The TTS model, languages or persona changed, or a failed engine is retried: prepare again,
    // and synthesize the thinking fillers again with the new voice.
    preparedTtsKeys.clear()
    cancelFillerPresynthesis()
    presynthesizedFillers.clear()
    val modelId = selectedTtsModelID.value
    if (shouldShowWarmingState()) {
      updateUiState {
        it.copy(
          ttsEngine = ttsSpeechEngineFor(modelId) ?: it.ttsEngine,
          ttsReadiness = SpeechEngineReadiness.WARMING,
          ttsErrorCause = null,
        )
      }
    }
    try {
      val errorCause = prepareSelectedTtsEngine()
      if (errorCause == null) {
        updateUiState { it.copy(ttsReadiness = SpeechEngineReadiness.READY, ttsErrorCause = null) }
      } else {
        reportTtsEngineFailure(errorCause)
      }
      completePendingGreetingIfReady()
      // After the greeting starts, so that the fillers aren't synthesized only to be cancelled by
      // it.
      schedulePresynthesizeFillers()
    } catch (e: CancellationException) {
      // The engine can cancel an in-flight prepare() when released on background memory trim; only
      // propagate cancellation when this prepareTtsJob itself was cancelled.
      ensureActive()
      Log.w(TAG, "Preparing TTS engine '$modelId' was cancelled by the engine", e)
    }
  }
}

/**
 * Re-warms the selected neural TTS engine when it was evicted in the background by memory trim (or
 * when initial warm-up was interrupted by background eviction) and the screen resumes.
 */
internal fun XrLanguageTeacherViewModel.prepareTtsEngineIfEvicted() {
  val ttsModelId = selectedTtsModelID.value
  if (TtsModelIdentifier.isSystemEngine(ttsModelId)) return
  val service = ttsServiceProvider.serviceOrNull(forModelId = ttsModelId) ?: return
  val serviceStatus = service.status.value
  val readiness = uiState.value.ttsReadiness
  val isPrepareActive = prepareTtsJob?.isActive == true
  val wasEvictedAfterReady =
    readiness == SpeechEngineReadiness.READY &&
      !serviceStatus.isEngineLoaded &&
      serviceStatus !is TtsServiceStatus.Initializing
  val wasInterruptedWhileWarming = readiness == SpeechEngineReadiness.WARMING && !isPrepareActive
  if (wasEvictedAfterReady || wasInterruptedWhileWarming) {
    prepareTtsEngine(keepReadyState = true)
  }
}

/**
 * Checks and prepares the selected TTS engine for the native and learning languages, with no
 * fallback to another engine, and returns why it can't start, or `null` once it is ready.
 *
 * Fails with [SpeechEngineErrorCause.NOT_SUPPORTED] when no engine serves the selected model yet
 * (for example a LiteRT-LM text-to-speech model), or when the allowlist models are registered and
 * none of them is the selected model (the device hides it). Otherwise the selected model's
 * `SpeechModelState` and, for a model that installs each language as a separate pack, the
 * `LanguageState` of both languages decide, mapped by [toSpeechEngineErrorCause]. System engines
 * have no model check. Validates both languages against the engine's supported locales and
 * pre-warms both via [TtsServiceProvider.prepareService].
 */
internal suspend fun XrLanguageTeacherViewModel.prepareSelectedTtsEngine():
  SpeechEngineErrorCause? {
  val modelId = selectedTtsModelID.value
  val service = ttsServiceProvider.serviceOrNull(forModelId = modelId)
  if (service == null) {
    Log.w(TAG, "No TTS engine serves the selected model '$modelId'")
    return SpeechEngineErrorCause.NOT_SUPPORTED
  }
  val hostLang = mainLanguage.value
  val targetLang = learnLanguage.value
  val persona = selectedPersona.value
  return try {
    val isSystemEngine = TtsModelIdentifier.isSystemEngine(modelId)
    val model = if (isSystemEngine) null else speechSelectionUseCase.ttsModelFor(modelId)
    val modelCause =
      when {
        isSystemEngine -> null
        model != null -> speechSelectionUseCase.resolveState(model).toSpeechEngineErrorCause()
        speechSelectionUseCase.hasRegisteredModels(SpeechModality.TTS) ->
          SpeechEngineErrorCause.NOT_SUPPORTED
        else -> null
      }
    if (modelCause != null) {
      Log.w(TAG, "The TTS engine '$modelId' can't start: $modelCause")
      return modelCause
    }
    val supportedTags = getSupportedLocaleTagsForTtsModel(modelId)
    val resolvedHostTag = resolveEngineLocaleTagForLanguage(hostLang, supportedTags)
    val resolvedTargetTag = resolveEngineLocaleTagForLanguage(targetLang, supportedTags)
    if (resolvedHostTag == null || resolvedTargetTag == null) {
      Log.w(TAG, "The TTS engine '$modelId' has no voice for $hostLang or $targetLang")
      return SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED
    }
    // Prepare target language first and host (native) language last so stateful engines like
    // Platform TextToSpeech remain configured for the native language greeting, matching iOS.
    val requiredTags = listOf(resolvedTargetTag, resolvedHostTag).distinct()
    if (model != null && speechSelectionUseCase.installsLanguagePacksSeparately(model)) {
      for (tag in requiredTags) {
        val languageCause =
          speechSelectionUseCase.resolveLanguageState(model, tag).toSpeechEngineErrorCause()
        if (languageCause != null) {
          Log.w(TAG, "The TTS engine '$modelId' can't speak '$tag': $languageCause")
          return languageCause
        }
      }
    }
    for (tag in requiredTags) {
      val config =
        TtsConfig(
          modelId = modelId,
          languageCode = tag,
          voiceId = PersonaVoices.voiceIdFor(modelId, persona, tag),
        )
      val unused = withContext(ioDispatcher) { ttsServiceProvider.prepareService(config) }
      preparedTtsKeys.markPrepared(config)
    }
    null
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "TTS engine '$modelId' preparation failed", e)
    SpeechEngineErrorCause.OTHER
  }
}

/**
 * Marks the selected TTS engine as failed because of [cause] and pauses the lesson. The screen then
 * shows the speech engine error popup. Nothing retries automatically.
 */
internal fun XrLanguageTeacherViewModel.reportTtsEngineFailure(cause: SpeechEngineErrorCause) {
  Log.w(TAG, "The selected TTS engine failed: $cause")
  updateUiState {
    it.copy(
      ttsReadiness = SpeechEngineReadiness.ERROR,
      ttsErrorCause = cause,
      isSpeechEngineErrorDismissed = false,
    )
  }
  pauseLessonForSpeechEngineFailure()
}

/**
 * Returns the spoken phrases (greeting, fillers, persona previews) for [nativeLanguage] and the
 * locale to speak them with, resolved against the active
 * [XrLanguageTeacherViewModel.selectedTtsModelID]'s supported locales (including ML Kit, Kokoro,
 * and Platform TTS). When that engine reports locales and none is in [nativeLanguage], the English
 * phrases are used with an `en-US` voice instead.
 */
internal fun XrLanguageTeacherViewModel.resolveNativeSpokenPhrases(
  nativeLanguage: Language = mainLanguage.value
): ResolvedSpokenPhrases {
  val modelId = selectedTtsModelID.value
  val supportedTags = getSupportedLocaleTagsForTtsModel(modelId)
  return XrLanguageTeacherSpokenPhrases.resolve(nativeLanguage, supportedTags)
}

/**
 * Resolves and prepares the [TtsService] for [config] without falling back to the platform engine
 * when [TtsConfig.modelId] is unregistered.
 *
 * Skips `prepare` when the engine is still loaded and was already prepared for [config]'s model,
 * language and voice ([XrLanguageTeacherViewModel.preparedTtsKeys]), unless [forceReprepare].
 */
internal suspend fun XrLanguageTeacherViewModel.prepareTtsServiceForConfig(
  config: TtsConfig,
  forceReprepare: Boolean = false,
): TtsService {
  val modelId = config.modelId.orEmpty()
  val service =
    ttsServiceProvider.serviceOrNull(forModelId = modelId)
      ?: throw TtsServiceError.ModelNotFound(modelId)
  when {
    forceReprepare -> preparedTtsKeys.invalidateModel(modelId)
    preparedTtsKeys.isPrepared(config, service.status.value.isEngineLoaded) -> return service
  }
  val prepared = ttsServiceProvider.prepareService(config)
  preparedTtsKeys.markPrepared(config)
  return prepared
}

/** selected engine always speaks: there is no fallback to another engine. */
internal fun XrLanguageTeacherViewModel.resolveSegmentTtsConfig(
  requestedLanguageCode: String,
  persona: CharacterPersona,
  queueMode: TtsQueueMode,
): TtsConfig {
  val modelId = selectedTtsModelID.value
  val supportedTags = getSupportedLocaleTagsForTtsModel(modelId)
  val resolvedTag =
    resolveEngineLocaleTag(requestedLanguageCode, supportedTags) ?: requestedLanguageCode
  return TtsConfig(
    modelId = modelId,
    languageCode = resolvedTag,
    voiceId = PersonaVoices.voiceIdFor(modelId, persona, resolvedTag),
    queueMode = queueMode,
  )
}

/**
 * How many times a TTS segment that fails before it plays any audio is tried again, unless its
 * engine has a retry policy for the failure ([TtsService.preStartRetryDelaysMs]).
 */
private const val TTS_SEGMENT_RETRY_COUNT = 1

/**
 * A retry of a TTS segment: when to synthesize it again, and whether the engine's retry policy
 * ([TtsService.preStartRetryDelaysMs]) asked for it, in which case the engine isn't prepared again.
 */
internal data class SegmentRetry(val delayMs: Long, val isEnginePolicy: Boolean)

/**
 * Counts the retries of one TTS segment: the engine's own retries for failures it has a policy for
 * ([PreStartRetries]), and [TTS_SEGMENT_RETRY_COUNT] immediate retries for other failures. A
 * segment whose engine retries run out is skipped.
 *
 * Every segment gets the engine's retries: there is no limit per turn, and stopping the turn ends
 * them.
 */
internal class SegmentRetries {
  private val preStartRetries = PreStartRetries()
  private var otherRetriesLeft = TTS_SEGMENT_RETRY_COUNT

  /**
   * Takes a retry for [failure] of the segment on [service], or returns `null` when the segment has
   * no retry left for it.
   */
  fun takeRetry(service: TtsService, failure: Exception): SegmentRetry? =
    when {
      preStartRetries.hasPolicy(service, failure) ->
        preStartRetries.nextDelayMs(service, failure)?.let {
          SegmentRetry(delayMs = it, isEnginePolicy = true)
        }
      otherRetriesLeft > 0 -> {
        otherRetriesLeft--
        SegmentRetry(delayMs = 0L, isEnginePolicy = false)
      }
      else -> null
    }

  /**
   * Like [takeRetry], for a segment whose synthesis failed to start at all. A failure without an
   * engine policy gets one immediate retry here that doesn't count against
   * [TTS_SEGMENT_RETRY_COUNT].
   */
  fun takeRetryOfFirstAttempt(service: TtsService, failure: Exception): SegmentRetry? =
    if (preStartRetries.hasPolicy(service, failure)) {
      takeRetry(service, failure)
    } else {
      SegmentRetry(delayMs = 0L, isEnginePolicy = false)
    }
}

/**
 * Starts synthesizing [text] again, after [retry]'s delay, because the previous attempt failed with
 * [failure] before playing any audio. Prepares the engine again first when it reports that it isn't
 * prepared, unless the engine's own retry policy asked for this retry: then the retry only
 * synthesizes again. Returns `null` when the retry can't start.
 *
 * Later segments queue behind the retry, so the order is kept. Cancelling the turn during the wait
 * cancels the retry.
 */
internal suspend fun XrLanguageTeacherViewModel.retrySegmentSynthesis(
  service: TtsService,
  prepareConfig: TtsConfig,
  synthesizeConfig: TtsConfig,
  text: String,
  failure: Exception,
  retry: SegmentRetry,
): TtsOutput? {
  val needsReprepare =
    !retry.isEnginePolicy &&
      (failure is TtsServiceError.EngineUnavailable || !service.status.value.isEngineLoaded)
  Log.w(
    TAG,
    "TTS segment failed before playing audio (model=${synthesizeConfig.modelId}," +
      " lang=${synthesizeConfig.languageCode}); retrying in ${retry.delayMs} ms" +
      " (reprepare=$needsReprepare)",
    failure,
  )
  return try {
    delay(retry.delayMs)
    val retryService =
      if (needsReprepare) {
        prepareTtsServiceForConfig(prepareConfig, forceReprepare = true)
      } else {
        service
      }
    // Preparing may finish after the turn was stopped; don't speak for a stopped turn.
    currentCoroutineContext().ensureActive()
    retryService.synthesize(text, synthesizeConfig)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "TTS segment retry failed (model=${synthesizeConfig.modelId}); skipping it", e)
    null
  }
}
