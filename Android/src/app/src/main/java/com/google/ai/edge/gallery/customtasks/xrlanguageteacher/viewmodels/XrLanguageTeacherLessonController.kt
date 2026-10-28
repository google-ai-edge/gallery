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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.Pass2CardDecision
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.StreamingReplyState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherReply
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.decidePass2Card
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.findSavedEntry
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toEnglishName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.buildThinkingFillerSegment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelFillerPresynthesis
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.enqueueTtsSentence
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.finishStreamingTts
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.schedulePresynthesizeFillers
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.startStreamingTts
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Records [model] as the active model instance once it finishes initializing, adopting its newly
 * created conversation directly when the active teacher preferences match the saved ones, or
 * pre-warming a fresh conversation if preferences were modified in memory.
 */
fun XrLanguageTeacherViewModel.registerModelForSessionPrewarm(model: Model) {
  latestKnownModel = model
  if (model.instance == null) return
  if (
    _uiState.value.isSessionActive &&
      activeSessionModelName != null &&
      activeSessionModelName != model.name
  ) {
    resetLessonSessionInternal(model = model, prewarmSession = false)
  }
  if (activeSessionModelInstance !== model.instance) {
    val canAdoptInitializedConversation =
      !hasPendingPreferencePrewarm &&
        mainLanguage.value == savedMainLanguage.value &&
        learnLanguage.value == savedLearnLanguage.value
    if (canAdoptInitializedConversation) {
      val persona = selectedPersona.value
      val customTemplate = storageRepository.getCustomPersonaPromptTemplate(persona)
      val systemPromptText =
        systemPromptOverride.value
          ?: persona.buildSystemPrompt(
            mainLangName = mainLanguage.value.toEnglishName(fallback = "English"),
            learnLangName = learnLanguage.value.toEnglishName(fallback = "Spanish"),
            customTemplate = customTemplate,
          )
      hasPendingPreferencePrewarm = false
      isConversationInitialized = true
      activeConversationTurnCount = 0
      activeSessionModelName = model.name
      activeSessionModelInstance = model.instance
      activeSystemPromptText = systemPromptText
      schedulePresynthesizeFillers()
      return
    }
  }
  if (
    (!isConversationInitialized || hasPendingPreferencePrewarm) && !_uiState.value.isSessionActive
  ) {
    prewarmConversationSession(model = model)
  }
}

/**
 * Eagerly initializes a clean conversation (`resetConversation` + system instruction KV cache) on
 * [XrLanguageTeacherViewModel.inferenceDispatcher] when idle so Turn 1 never blocks on session
 * setup after the user speaks.
 */
fun XrLanguageTeacherViewModel.prewarmConversationSession(
  model: Model? = latestKnownModel,
  mainLang: Language = mainLanguage.value,
  learnLang: Language = learnLanguage.value,
  persona: CharacterPersona = selectedPersona.value,
) {
  val targetModel = model ?: return
  latestKnownModel = targetModel
  if (targetModel.instance == null) return
  val customTemplate = storageRepository.getCustomPersonaPromptTemplate(persona)
  val systemPromptText =
    systemPromptOverride.value
      ?: persona.buildSystemPrompt(
        mainLangName = mainLang.toEnglishName(fallback = "English"),
        learnLangName = learnLang.toEnglishName(fallback = "Spanish"),
        customTemplate = customTemplate,
      )
  activePrewarmJob?.cancel()
  cancelFillerPresynthesis()
  activePrewarmJob =
    viewModelScope
      .launch(inferenceDispatcher) {
        performConversationPrewarm(model = targetModel, systemPromptText = systemPromptText)
      }
      .also { job -> job.invokeOnCompletion { schedulePresynthesizeFillers() } }
}

/**
 * Freezes [bitmap] as the active camera frame for the lesson session (or clears it if `null`),
 * scaling oversized frames to `512x512` and pre-warming a clean conversation on
 * [XrLanguageTeacherViewModel.inferenceDispatcher] while the user speaks.
 */
fun XrLanguageTeacherViewModel.captureSessionBitmap(bitmap: Bitmap?, model: Model? = null) {
  if (model != null) {
    latestKnownModel = model
  }
  _uiState.update { it.copy(capturedBitmap = bitmap) }
  if (bitmap == null) return
  val targetModel = model ?: latestKnownModel
  val persona = selectedPersona.value
  val mainLang = mainLanguage.value
  val learnLang = learnLanguage.value
  val customTemplate = storageRepository.getCustomPersonaPromptTemplate(persona)
  val systemPromptText =
    systemPromptOverride.value
      ?: persona.buildSystemPrompt(
        mainLangName = mainLang.toEnglishName(fallback = "English"),
        learnLangName = learnLang.toEnglishName(fallback = "Spanish"),
        customTemplate = customTemplate,
      )
  activePrewarmJob?.cancel()
  cancelFillerPresynthesis()
  activePrewarmJob =
    viewModelScope.launch(inferenceDispatcher) {
      val prepared = prepareFrameBitmap(bitmap)
      if (prepared != null && prepared !== bitmap) {
        _uiState.update { current ->
          if (current.capturedBitmap === bitmap) {
            current.copy(capturedBitmap = prepared)
          } else {
            current
          }
        }
      }
      if (targetModel != null) {
        performConversationPrewarm(model = targetModel, systemPromptText = systemPromptText)
      }
    }
  activePrewarmJob?.invokeOnCompletion { schedulePresynthesizeFillers() }
}

/**
 * Executes a 2-pass inference turn for the current AR Language Teacher lesson: Pass 1 streams the
 * spoken `<reply>`, then [launchPass2CardExtraction] extracts the vocabulary card from it.
 *
 * - **Turn 1** (`!uiState.value.isSessionActive` or conversation/model changed): reuses an already
 *   pre-warmed clean conversation (or calls `resetConversation` once if needed), sends
 *   [capturedBitmap] (or the previously frozen `uiState.value.capturedBitmap`) with the prompt, and
 *   activates the session (`isSessionActive = true`).
 * - **Turn N >= 2** (`uiState.value.isSessionActive`): reuses the active conversation without
 *   calling `resetConversation`, preserving conversation history and visual context across both
 *   LiteRT-LM and AICore Gemma.
 *
 * Pass 2 runs on an ephemeral conversation: LiteRT-LM enforces
 * `TeacherResponseSchema.pass2CardJsonSchema` via `ResponseFormat`.
 */
fun XrLanguageTeacherViewModel.runLessonTurn(
  model: Model,
  spokenQuestion: String? = null,
  capturedBitmap: Bitmap? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  if (_uiState.value.isRunningInference) {
    _uiState.update { it.copy(isSubmittingToModel = false) }
    return
  }
  latestKnownModel = model
  val stateSnapshot = _uiState.value
  val isFirstTurn = !stateSnapshot.isSessionActive
  val initialBitmap = capturedBitmap ?: stateSnapshot.capturedBitmap
  val normalizedQuestion = spokenQuestion?.trim()?.takeIf { it.isNotEmpty() }
  val persona = selectedPersona.value
  val mainLang = mainLanguage.value
  val learnLang = learnLanguage.value
  val mainLangName = mainLang.toEnglishName(fallback = "English")
  val learnLangName = learnLang.toEnglishName(fallback = "Spanish")
  val customTemplate = storageRepository.getCustomPersonaPromptTemplate(persona)
  val systemPromptText =
    systemPromptOverride.value
      ?: persona.buildSystemPrompt(
        mainLangName = mainLangName,
        learnLangName = learnLangName,
        customTemplate = customTemplate,
      )
  val previousTeacherDialogue = stateSnapshot.teacherDialogue
  val previousChatTurns = stateSnapshot.chatTurns
  turnStartTeacherDialogue = if (isFirstTurn) "" else previousTeacherDialogue
  val turnEpoch = sessionEpoch.incrementAndGet()

  activePass2Job?.cancel()
  // The previous turn's Pass 2 results are dropped from here on, so its flag can't be cleared.
  isPass2InFlight = false
  // startStreamingTts below stops the previous playback and waits for it before this turn
  // speaks, so a separate stop here could only race this turn's first utterance.
  if (currentSessionStartTimestampMs == 0L) {
    currentSessionStartTimestampMs = System.currentTimeMillis()
  }
  if (normalizedQuestion != null) {
    appendChatTurn(isUser = true, text = normalizedQuestion)
  }

  _uiState.update {
    it.copy(
      isSessionActive = true,
      isRunningInference = true,
      isWaitingForFirstToken = true,
      isSubmittingToModel = true,
      overlayState = TeacherOverlayState.THINKING,
      capturedBitmap = initialBitmap,
      teacherDialogue = "",
      lastSpokenQuestion = normalizedQuestion ?: it.lastSpokenQuestion,
      errorMessage = null,
    )
  }
  onRunningStateChanged(true)

  val focalSubject = stateSnapshot.vocabCardData?.word

  // The engine is needed for this turn's speech now.
  cancelFillerPresynthesis()
  startStreamingTts(persona)

  // Speaks a short native-language filler right away, in XR and phone mode, while the model reads
  // the question and image. It always plays to the end, and the reply queues behind it.
  buildThinkingFillerSegment(nativeLanguage = mainLang, persona = persona)?.let { filler ->
    val unused = ttsChannel?.trySend(filler)
  }

  activeInferenceModel = model
  val prewarmJobToAwait = activePrewarmJob
  activeInferenceJob =
    viewModelScope.launch(inferenceDispatcher) {
      prewarmJobToAwait?.join()
      // Leaving or resetting cancels this job; never start native work for a cancelled turn.
      ensureActive()
      val effectiveBitmap = capturedBitmap ?: _uiState.value.capturedBitmap
      val canReuseWarmedConversation =
        isConversationInitialized &&
          !hasPendingPreferencePrewarm &&
          (!isFirstTurn || activeConversationTurnCount == 0) &&
          activeSessionModelName == model.name &&
          activeSessionModelInstance === model.instance &&
          activeSystemPromptText == systemPromptText
      val needsConversationReset = !canReuseWarmedConversation
      if (needsConversationReset) {
        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = true,
          supportAudio = false,
          systemInstruction = Contents.of(systemPromptText),
        )
        ensureActive()
        hasPendingPreferencePrewarm = false
        isConversationInitialized = true
        activeConversationTurnCount = 0
        activeSessionModelName = model.name
        activeSessionModelInstance = model.instance
        activeSystemPromptText = systemPromptText
      }
      activeConversationTurnCount++
      val turnPrompt =
        XrLanguageTeacherViewModel.buildPass1Prompt(
          persona = persona,
          mainLangName = mainLangName,
          learnLangName = learnLangName,
          spokenQuestion = normalizedQuestion,
          focalSubject = if (isFirstTurn) null else focalSubject,
          isFollowUp = !isFirstTurn,
          // A reused conversation already carries the earlier turns in the runtime's own history;
          // only replay them in the prompt after a reset so they aren't duplicated every turn.
          history = if (needsConversationReset) previousChatTurns else emptyList(),
        )

      val fullReplyBuilder = StringBuilder()
      var pendingSentenceLength = 0
      var sawReplyOpenTag = false
      var ttsFinishedForReply = false
      var sawReplyClose = false
      // Decided once the reply is complete: at `</reply>`, or at the end of Pass 1 without one.
      var cardDecision: Pass2CardDecision? = null
      var pass2Launched = false

      fun decideCard(parsed: TeacherReply): Pass2CardDecision {
        val sessionCards =
          if (isFirstTurn) {
            emptyList()
          } else {
            _uiState.value.vocabCards.ifEmpty { listOfNotNull(_uiState.value.vocabCardData) }
          }
        return decidePass2Card(parsed.replyWithTargetTags, sessionCards)
      }

      // Sends the rest of the reply to TTS and closes the turn's speech input.
      fun flushReplyToTts(streamingState: StreamingReplyState, parsed: TeacherReply) {
        val replyContent =
          if (pendingSentenceLength == 0) {
            parsed.replyWithTargetTags
          } else {
            streamingState.rawReplyContent
          }
        pendingSentenceLength =
          flushRemainingTtsSentence(
            replyContent = replyContent,
            pendingSentenceLength = pendingSentenceLength,
            hostLanguage = mainLang,
            targetLanguage = learnLang,
            targetWord = _uiState.value.vocabCardData?.word ?: focalSubject,
          )
        ttsFinishedForReply = true
      }
      val turnImages =
        if (
          (isFirstTurn || needsConversationReset) &&
            effectiveBitmap != null &&
            !effectiveBitmap.isRecycled
        ) {
          val preparedBitmap = prepareFrameBitmap(effectiveBitmap)
          if (preparedBitmap != null && !preparedBitmap.isRecycled) {
            if (preparedBitmap !== effectiveBitmap) {
              _uiState.update { it.copy(capturedBitmap = preparedBitmap) }
            }
            listOf(preparedBitmap)
          } else {
            emptyList()
          }
        } else {
          emptyList()
        }

      _uiState.update { it.copy(isSubmittingToModel = false) }
      ensureActive()
      model.runtimeHelper.runInferenceReportingErrors(
        model = model,
        input = turnPrompt,
        images = turnImages,
        // On AICore, opt into a multi-turn vision session so the system prompt and the first
        // turn's image carry across turns.
        extraContext =
          buildMap {
            if (model.isAiCore) {
              put(EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION, "true")
            }
          },
        resultListener = { partialResult, done, _ ->
          if (sessionEpoch.get() != turnEpoch) {
            return@runInferenceReportingErrors
          }
          if (partialResult.isNotEmpty()) {
            fullReplyBuilder.append(partialResult)
            val accumulated = fullReplyBuilder.toString()
            val streamingState =
              XrLanguageTeacherDialogueFormatter.extractStreamingReply(
                rawStream = accumulated,
                mainLanguage = mainLang,
                learnLanguage = learnLang,
              )

            _uiState.update { current ->
              current.copy(
                isWaitingForFirstToken = false,
                overlayState =
                  if (streamingState.replyWithTargetTags.isNotEmpty()) {
                    TeacherOverlayState.NONE
                  } else {
                    current.overlayState
                  },
                teacherDialogue =
                  if (streamingState.replyWithTargetTags.isNotEmpty()) {
                    streamingState.replyWithTargetTags
                  } else {
                    current.teacherDialogue
                  },
              )
            }

            if (!ttsFinishedForReply) {
              if (streamingState.hasReplyOpenTag && !sawReplyOpenTag) {
                // rawReplyContent now restarts after `<reply>`, so offsets measured against the
                // untagged preamble no longer apply.
                sawReplyOpenTag = true
                pendingSentenceLength = 0
              }
              val replySoFar = streamingState.rawReplyContent
              if (replySoFar.length > pendingSentenceLength) {
                val unconsumed = replySoFar.substring(pendingSentenceLength)
                val (completedSentences, remainingBuffer) =
                  XrLanguageTeacherDialogueFormatter.extractCompleteSentences(unconsumed)
                if (completedSentences.isNotEmpty()) {
                  pendingSentenceLength = replySoFar.length - remainingBuffer.length
                  for (sentence in completedSentences) {
                    enqueueTtsSentence(
                      sentenceText = sentence,
                      hostLanguage = mainLang,
                      targetLanguage = learnLang,
                      targetWord = focalSubject,
                    )
                  }
                }
              }
            }

            if (streamingState.isReplyComplete && !sawReplyClose) {
              // The reply is complete at `</reply>`: speak the rest of it now instead of waiting
              // for Pass 1 to end, and show the card placeholder if Pass 2 will extract a card.
              sawReplyClose = true
              val parsed =
                parseCompletedReply(rawResponse = accumulated.trim(), isFollowUp = !isFirstTurn)
              if (!ttsFinishedForReply) {
                flushReplyToTts(streamingState, parsed)
              }
              val decision = decideCard(parsed)
              cardDecision = decision
              if (decision == Pass2CardDecision.Extract) {
                markExtractingCardIfCurrent(turnEpoch)
              }
            }
          }

          if (done) {
            val rawResponse = fullReplyBuilder.toString().trim()
            val streamingState =
              XrLanguageTeacherDialogueFormatter.extractStreamingReply(
                rawStream = rawResponse,
                mainLanguage = mainLang,
                learnLanguage = learnLang,
              )
            val decision =
              cardDecision
                ?: decideCard(
                  parseCompletedReply(rawResponse = rawResponse, isFollowUp = !isFirstTurn)
                )
            val parsed =
              applyCompletedReply(
                rawResponse = rawResponse,
                isFollowUp = !isFirstTurn,
                willExtractCardAsync = decision == Pass2CardDecision.Extract,
                turnEpoch = turnEpoch,
              )
            if (!ttsFinishedForReply) {
              flushReplyToTts(streamingState, parsed)
            }
            when (decision) {
              Pass2CardDecision.Skip -> schedulePresynthesizeFillers()
              is Pass2CardDecision.ReuseSessionCard -> reselectSessionCard(decision.card)
              Pass2CardDecision.Extract -> {
                pass2Launched = true
                // Only this session's cards count as already taught, matching
                // `decidePass2Card`: a saved word still gets its card in a new session.
                val existingWords =
                  _uiState.value.vocabCards
                    .ifEmpty { listOfNotNull(_uiState.value.vocabCardData) }
                    .map { it.word }
                launchPass2CardExtraction(
                  model = model,
                  mainLangName = mainLangName,
                  learnLangName = learnLangName,
                  replyWithTargetTags = parsed.replyWithTargetTags,
                  turnEpoch = turnEpoch,
                  spokenQuestion = normalizedQuestion,
                  existingWords = existingWords,
                )
              }
            }
            onRunningStateChanged(false)
          }
        },
        cleanUpListener = {
          if (sessionEpoch.get() != turnEpoch) return@runInferenceReportingErrors
          _uiState.update {
            it.copy(
              isRunningInference = false,
              isSubmittingToModel = false,
              isWaitingForFirstToken = false,
              overlayState = TeacherOverlayState.NONE,
              // The placeholder shown at `</reply>` stays only if Pass 2 extracts the card.
              isExtractingCard = it.isExtractingCard && pass2Launched,
            )
          }
          onRunningStateChanged(false)
        },
        onError = { errorMsg ->
          if (sessionEpoch.get() != turnEpoch) return@runInferenceReportingErrors
          Log.w(TAG, "Lesson inference error: $errorMsg")
          activeConversationTurnCount = max(0, activeConversationTurnCount - 1)
          if (needsConversationReset) {
            isConversationInitialized = false
          }
          if (isFirstTurn) {
            currentSessionStartTimestampMs = 0L
            currentSessionImagePath = null
          }
          stopTtsPlayback()
          val displayError =
            errorMsg.trim().ifEmpty {
              storageRepository.getString(R.string.xrteacher_inference_error_fallback)
            }
          _uiState.update {
            it.copy(
              isSessionActive = if (isFirstTurn) false else it.isSessionActive,
              isRunningInference = false,
              isExtractingCard = false,
              isSubmittingToModel = false,
              isWaitingForFirstToken = false,
              overlayState = TeacherOverlayState.NONE,
              capturedBitmap = if (isFirstTurn) null else it.capturedBitmap,
              teacherDialogue =
                if (isFirstTurn) {
                  ""
                } else {
                  it.teacherDialogue.ifBlank { previousTeacherDialogue }
                },
              chatTurns = previousChatTurns,
              errorMessage = displayError,
            )
          }
          onRunningStateChanged(false)
        },
        coroutineScope = this,
      )
    }
}

internal fun XrLanguageTeacherViewModel.flushRemainingTtsSentence(
  replyContent: String,
  pendingSentenceLength: Int,
  hostLanguage: Language,
  targetLanguage: Language,
  targetWord: String?,
): Int {
  val remaining =
    if (replyContent.length > pendingSentenceLength) {
      replyContent.substring(pendingSentenceLength).trim()
    } else {
      ""
    }
  val cleanRemaining = XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(remaining).trim()
  if (cleanRemaining.isNotEmpty()) {
    enqueueTtsSentence(
      sentenceText = remaining,
      hostLanguage = hostLanguage,
      targetLanguage = targetLanguage,
      targetWord = targetWord,
    )
  }
  finishStreamingTts()
  return replyContent.length
}

/** Parses a completed Pass 1 reply without changing any state. */
internal fun XrLanguageTeacherViewModel.parseCompletedReply(
  rawResponse: String,
  isFollowUp: Boolean,
): TeacherReply {
  val state = _uiState.value
  return XrLanguageTeacherDialogueFormatter.parseReply(
    rawResponse = rawResponse,
    mainLanguage = mainLanguage.value,
    learnLanguage = learnLanguage.value,
    fallbackTargetWord =
      if (isFollowUp) state.vocabCardData?.word ?: state.vocabCards.lastOrNull()?.word else null,
  )
}

/**
 * Parses a completed Pass 1 reply and updates the active session's dialogue and chat turn history.
 * The turn's vocabulary card comes from Pass 2 or from a card already in the session.
 *
 * [turnEpoch] is the [XrLanguageTeacherViewModel.sessionEpoch] of the turn the response belongs to.
 * Once Stop or a reset moved [XrLanguageTeacherViewModel.sessionEpoch] on, `isExtractingCard` is
 * left as they set it.
 */
internal fun XrLanguageTeacherViewModel.applyCompletedReply(
  rawResponse: String,
  isFollowUp: Boolean = false,
  willExtractCardAsync: Boolean = false,
  turnEpoch: Long,
): TeacherReply {
  val parsed = parseCompletedReply(rawResponse, isFollowUp)
  if (parsed.cleanReply.isNotBlank()) {
    appendChatTurn(isUser = false, text = parsed.cleanReply)
  }
  _uiState.update { current ->
    val updatedIndex =
      current.currentVocabCardIndex.coerceIn(0, max(0, current.vocabCards.lastIndex))
    current.copy(
      isSessionActive = true,
      isRunningInference = false,
      // Set together with `isRunningInference = false`, so the turn never looks idle between
      // Pass 1 and Pass 2: leaving the screen then would close the engine Pass 2 starts on.
      isExtractingCard =
        if (sessionEpoch.get() == turnEpoch) {
          willExtractCardAsync
        } else {
          current.isExtractingCard
        },
      isSubmittingToModel = false,
      isWaitingForFirstToken = false,
      overlayState = TeacherOverlayState.NONE,
      teacherDialogue = parsed.replyWithTargetTags.ifBlank { current.teacherDialogue },
      vocabCardData = current.vocabCards.getOrNull(updatedIndex) ?: current.vocabCardData,
      currentVocabCardIndex = updatedIndex,
    )
  }
  saveCurrentLessonSession()
  return parsed
}

/** Selects the active vocabulary card at [index] in the current lesson session's deck. */
fun XrLanguageTeacherViewModel.selectVocabCard(index: Int) {
  _uiState.update { current ->
    if (index !in current.vocabCards.indices) return@update current
    val selected = current.vocabCards[index]
    current.copy(
      currentVocabCardIndex = index,
      vocabCardData = selected,
      isCurrentCardSaved = isVocabCardSaved(selected),
    )
  }
}

/**
 * Returns whether [card] is already saved in [XrLanguageTeacherViewModel.dictionaryEntries] for the
 * active learn language.
 */
fun XrLanguageTeacherViewModel.isVocabCardSaved(card: VocabCardData?): Boolean =
  findSavedDictionaryEntry(card) != null

/**
 * Returns the [DictionaryEntry] in [XrLanguageTeacherViewModel.dictionaryEntries] that saves [card]
 * for the active learn language, or null when [card] is not saved.
 */
fun XrLanguageTeacherViewModel.findSavedDictionaryEntry(card: VocabCardData?): DictionaryEntry? {
  if (card == null) return null
  return dictionaryEntries.value.findSavedEntry(card.word, learnLanguage.value)
}

/**
 * Resets the current lesson session, returns to the live camera viewfinder, and eagerly warms a
 * clean Turn 1 conversation on [XrLanguageTeacherViewModel.inferenceDispatcher].
 *
 * Stops any in-flight model response, TTS playback, or ASR recording, clears the active session
 * state (`isSessionActive`, `capturedBitmap`, `vocabCardData`, `vocabCards`,
 * `currentVocabCardIndex`, `isCurrentCardSaved`, `teacherDialogue`, `chatTurns`,
 * `sessionGreeting`), and rotates [XrLanguageTeacherViewModel.currentSessionId].
 */
fun XrLanguageTeacherViewModel.resetLessonSession(
  model: Model? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  resetLessonSessionInternal(
    model = model,
    onRunningStateChanged = onRunningStateChanged,
    prewarmSession = true,
  )
}

/** Whether this ViewModel's lesson turn is still running native inference (Pass 1 or Pass 2). */
internal fun XrLanguageTeacherViewModel.isTurnInferring(): Boolean {
  val state = _uiState.value
  return activeInferenceJob?.isActive == true ||
    activePass2Job?.isActive == true ||
    state.isRunningInference ||
    state.isExtractingCard
}

/**
 * Cancels the active lesson turn: drops the turn's late callbacks by moving
 * [XrLanguageTeacherViewModel.sessionEpoch] on, cancels the turn, Pass 2 and prewarm jobs, and
 * stops native generation on [modelToStop]. Returns the cancelled jobs, which may still be inside a
 * blocking native call.
 */
internal fun XrLanguageTeacherViewModel.cancelTurnJobs(
  reason: String,
  modelToStop: Model?,
): List<Job> = cancelTurnJobsImpl(reason = reason, modelToStop = modelToStop)

/**
 * Cancels the in-flight lesson turn (Pass 1 or Pass 2) and stops TTS playback in place without
 * leaving the screen or closing the model engine.
 *
 * On Turn 1 (when no teacher turn has completed yet), resets [_uiState] back to the idle camera
 * viewfinder. On follow-up turns (Turn >= 2, when the session already has a completed teacher
 * turn), keeps the active session, frozen frame, teacher dialogue, vocabulary cards, and completed
 * chat turns intact.
 */
fun XrLanguageTeacherViewModel.cancelActiveLessonTurn(model: Model) {
  cancelActiveLessonTurnImpl(model)
}

internal fun XrLanguageTeacherViewModel.resetLessonSessionInternal(
  model: Model? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
  prewarmSession: Boolean = true,
) {
  resetLessonSessionInternalImpl(
    model = model,
    onRunningStateChanged = onRunningStateChanged,
    prewarmSession = prewarmSession,
  )
}
