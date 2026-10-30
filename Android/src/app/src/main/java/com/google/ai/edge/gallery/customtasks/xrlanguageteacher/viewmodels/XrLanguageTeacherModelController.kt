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
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.GenerableVocabCard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherChatTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherJsonResponse
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherResponseSchema
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.autoSaveVocabCardInXrMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.clearAsrTranscript
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.isAsrRecordingOrPending
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.schedulePresynthesizeFillers
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.aicore.EXTRA_CONTEXT_AICORE_GENERABLE_CLASS
import com.google.ai.edge.gallery.runtime.aicore.EXTRA_CONTEXT_EPHEMERAL_INFERENCE
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal suspend fun XrLanguageTeacherViewModel.performConversationPrewarm(
  model: Model,
  systemPromptText: String,
) {
  if (
    teacherModelSuspension.isReleasedForGame ||
      uiState.value.isRunningInference ||
      uiState.value.isSessionActive
  ) {
    return
  }
  if (model.instance == null) return
  if (
    isConversationInitialized &&
      !hasPendingPreferencePrewarm &&
      activeConversationTurnCount == 0 &&
      activeSessionModelName == model.name &&
      activeSessionModelInstance === model.instance &&
      activeSystemPromptText == systemPromptText
  ) {
    return
  }
  // Leaving or resetting cancels the prewarm; never create a conversation for a cancelled one.
  currentCoroutineContext().ensureActive()
  if (teacherModelSuspension.isReleasedForGame || model.instance == null) return
  model.runtimeHelper.resetConversation(
    model = model,
    supportImage = true,
    supportAudio = false,
    systemInstruction = Contents.of(systemPromptText),
  )
  hasPendingPreferencePrewarm = false
  isConversationInitialized = true
  activeConversationTurnCount = 0
  activeSessionModelName = model.name
  activeSessionModelInstance = model.instance
  activeSystemPromptText = systemPromptText
}

/**
 * Returns [bitmap] center-cropped to a square and scaled down to [targetSize], or [bitmap] itself
 * when it already fits. Records a new copy as the prepared copy of [bitmap], so that swapping the
 * captured frame for it keeps the frame's sticker cutout.
 */
internal fun XrLanguageTeacherViewModel.prepareFrameBitmap(
  bitmap: Bitmap,
  targetSize: Int = 512,
): Bitmap? {
  val prepared = scaleFrameBitmap(bitmap, targetSize)
  if (prepared != null && prepared !== bitmap) {
    lastPreparedFrameCopy = PreparedFrameCopy(original = bitmap, prepared = prepared)
  }
  return prepared
}

private fun scaleFrameBitmap(bitmap: Bitmap, targetSize: Int): Bitmap? {
  if (bitmap.isRecycled) return null
  return try {
    val minDim = minOf(bitmap.width, bitmap.height)
    if (bitmap.width == bitmap.height && minDim <= targetSize) {
      return if (bitmap.isRecycled) null else bitmap
    }
    val sx = (bitmap.width - minDim) / 2
    val sy = (bitmap.height - minDim) / 2
    val square = Bitmap.createBitmap(bitmap, sx, sy, minDim, minDim)
    if (minDim > targetSize) {
      val scaled = square.scale(targetSize, targetSize)
      if (square != bitmap && square != scaled) {
        square.recycle()
      }
      scaled
    } else {
      square
    }
  } catch (e: IllegalStateException) {
    Log.w(TAG, "Frame bitmap was recycled while preparing for inference", e)
    null
  } catch (e: IllegalArgumentException) {
    Log.w(TAG, "Invalid frame bitmap dimensions while preparing for inference", e)
    null
  }
}

/** Applies the card Pass 2 extracted for the reply [replyWithTargetTags]. */
internal fun XrLanguageTeacherViewModel.applyExtractedPass2Card(
  extractedCard: VocabCardData?,
  replyWithTargetTags: String,
) {
  val resolvedCard =
    extractedCard
      ?.let {
        XrLanguageTeacherDialogueFormatter.sanitizeVocabCard(
          it,
          replyWithTargets = replyWithTargetTags,
          mainLanguage = mainLanguage.value,
          learnLanguage = learnLanguage.value,
        )
      }
      ?.takeIf {
        it.word.isNotBlank() && (it.meaning.isNotBlank() || it.exampleTranslation.isNotBlank())
      }
  if (resolvedCard == null || resolvedCard.word.isBlank()) {
    updateUiState { it.copy(isExtractingCard = false) }
    schedulePresynthesizeFillers()
    return
  }
  var cardToSave: VocabCardData? = null
  updateUiState { current ->
    // The update can run again on contention, so it recomputes the result every time.
    cardToSave = null
    val baseCards =
      if (current.vocabCards.isNotEmpty()) {
        current.vocabCards
      } else {
        listOfNotNull(current.vocabCardData)
      }
    val existingCard = baseCards.firstOrNull {
      XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(it.word, resolvedCard.word)
    }
    if (existingCard != null) {
      // Re-surface the existing card so the student is not left card-less.
      val existingIndex = baseCards.indexOf(existingCard)
      current.copy(
        isExtractingCard = false,
        vocabCardData = existingCard,
        currentVocabCardIndex =
          if (existingIndex >= 0) existingIndex else current.currentVocabCardIndex,
        isCurrentCardSaved = isVocabCardSaved(existingCard),
      )
    } else {
      val updatedCards = baseCards + resolvedCard
      val lastTeacherIdx = current.chatTurns.indexOfLast { !it.isUser }
      val updatedTurns =
        if (lastTeacherIdx >= 0) {
          current.chatTurns.toMutableList().apply {
            this[lastTeacherIdx] = this[lastTeacherIdx].copy(vocabCard = resolvedCard)
          }
        } else {
          current.chatTurns
        }
      cardToSave = resolvedCard
      current.copy(
        isExtractingCard = false,
        vocabCardData = resolvedCard,
        vocabCards = updatedCards,
        currentVocabCardIndex = updatedCards.lastIndex,
        isCurrentCardSaved = isVocabCardSaved(resolvedCard),
        chatTurns = updatedTurns,
      )
    }
  }
  cardToSave?.let { card ->
    autoSaveVocabCardInXrMode(card)
    saveCurrentLessonSession()
  }
  // Pass 2 is over: synthesize the thinking fillers if the teacher is idle now.
  schedulePresynthesizeFillers()
}

internal fun XrLanguageTeacherViewModel.parsePass2CardOutput(
  rawPass2Text: String,
  replyWithTargets: String = "",
): VocabCardData? {
  return XrLanguageTeacherViewModel.parsePass2CardOutput(
    rawPass2Text = rawPass2Text,
    replyWithTargets = replyWithTargets,
    mainLanguage = mainLanguage.value,
    learnLanguage = learnLanguage.value,
  )
}

private fun buildPass2ExtraContext(pass2SchemaClassName: String?): Map<String, String> = buildMap {
  put(EXTRA_CONTEXT_EPHEMERAL_INFERENCE, "true")
  if (pass2SchemaClassName != null) {
    put(EXTRA_CONTEXT_AICORE_GENERABLE_CLASS, pass2SchemaClassName)
  }
  put(EXTRA_CONTEXT_MAX_OUTPUT_TOKENS, "256")
}

/**
 * Shows [card], a card already in this session, again for a turn whose reply only teaches words
 * that already have a card, without running Pass 2.
 */
internal fun XrLanguageTeacherViewModel.reselectSessionCard(card: VocabCardData) {
  updateUiState { current ->
    val cards = current.vocabCards.ifEmpty { listOfNotNull(current.vocabCardData) }
    val index = cards.indexOfFirst {
      XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(it.word, card.word)
    }
    if (index < 0) {
      current.copy(isExtractingCard = false)
    } else {
      current.copy(
        isExtractingCard = false,
        vocabCardData = cards[index],
        currentVocabCardIndex = index,
        isCurrentCardSaved = isVocabCardSaved(cards[index]),
      )
    }
  }
  schedulePresynthesizeFillers()
}

internal fun XrLanguageTeacherViewModel.launchPass2CardExtraction(
  model: Model,
  mainLangName: String,
  learnLangName: String,
  replyWithTargetTags: String,
  turnEpoch: Long,
  spokenQuestion: String?,
  existingWords: List<String>,
) {
  activePass2Job?.cancel()
  // Set before the job starts, so the screen stays busy without a gap from the end of Pass 1 until
  // the card arrives: leaving the screen closes the engine that Pass 2 runs on. A turn that has
  // moved on already cleared the flag, so it is left alone.
  markExtractingCardIfCurrent(turnEpoch)
  activePass2Job =
    viewModelScope.launch(inferenceDispatcher) {
      if (sessionEpoch.get() != turnEpoch) return@launch
      val pass2SchemaClassName =
        if (model.isAiCore) {
          GenerableVocabCard::class.java.name
        } else {
          null
        }
      val pass2JsonSchema =
        if (!model.isAiCore) {
          TeacherResponseSchema.pass2CardJsonSchema()
        } else {
          null
        }
      val pass2Prompt =
        XrLanguageTeacherViewModel.buildPass2CardExtractionPrompt(
          mainLangName = mainLangName,
          learnLangName = learnLangName,
          teacherReplyWithTags = replyWithTargetTags.trim(),
          spokenQuestion = spokenQuestion,
          existingWords = existingWords,
        )
      // Leaving or resetting cancels this job; never start Pass 2 for a cancelled turn.
      ensureActive()
      isPass2InFlight = true

      // Runs one Pass 2 attempt. If the constrained attempt returns no usable card, Pass 2 runs
      // once more without the schema before the turn ends without a card.
      fun runPass2Attempt(constrained: Boolean) {
        val pass2Builder = StringBuilder()

        fun finishAttempt(extractedCard: VocabCardData?) {
          // Leaving or starting another turn advances the epoch, which also stops the retry.
          if (sessionEpoch.get() != turnEpoch) return
          if (constrained && extractedCard == null) {
            Log.w(TAG, "Pass 2 returned no usable card; retrying once without the schema")
            runPass2Attempt(constrained = false)
            return
          }
          isPass2InFlight = false
          applyExtractedPass2Card(
            extractedCard = extractedCard,
            replyWithTargetTags = replyWithTargetTags,
          )
        }

        model.runtimeHelper.runInferenceReportingErrors(
          model = model,
          input = pass2Prompt,
          images = emptyList(),
          extraContext = buildPass2ExtraContext(if (constrained) pass2SchemaClassName else null),
          responseJsonSchema = if (constrained) pass2JsonSchema else null,
          resultListener = { partial, pass2Done, _ ->
            if (sessionEpoch.get() != turnEpoch) return@runInferenceReportingErrors
            pass2Builder.append(partial)
            if (pass2Done) {
              finishAttempt(
                extractedCard =
                  parsePass2CardOutput(
                    pass2Builder.toString(),
                    replyWithTargets = replyWithTargetTags,
                  )
              )
            }
          },
          cleanUpListener = {},
          onError = { err ->
            Log.w(TAG, "Pass 2 card extraction failed (constrained=$constrained): $err")
            finishAttempt(extractedCard = null)
          },
          coroutineScope = this,
        )
      }

      runPass2Attempt(constrained = true)
    }
}

internal fun XrLanguageTeacherViewModel.cancelTurnJobsImpl(
  reason: String,
  modelToStop: Model?,
): List<Job> {
  sessionEpoch.incrementAndGet()
  val jobs = listOfNotNull(activeInferenceJob, activePass2Job, activePrewarmJob)
  for (job in jobs) {
    job.cancel()
  }
  isPass2InFlight = false
  activeInferenceJob = null
  activePass2Job = null
  activePrewarmJob = null
  if (modelToStop != null) {
    // Cancelling the coroutines does not stop native generation; stop it explicitly.
    try {
      modelToStop.runtimeHelper.stopResponse(modelToStop)
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping model response ($reason)", e)
    }
  }
  return jobs
}

internal fun XrLanguageTeacherViewModel.cancelActiveLessonTurnImpl(model: Model) {
  if (!_uiState.value.isRunningInference && !_uiState.value.isExtractingCard) {
    return
  }
  sessionEpoch.incrementAndGet()
  activeInferenceJob?.cancel()
  activeInferenceJob = null
  activePass2Job?.cancel()
  activePass2Job = null
  isPass2InFlight = false
  isConversationInitialized = false
  try {
    model.runtimeHelper.stopResponse(model)
  } catch (e: Exception) {
    Log.w(TAG, "Error stopping model response (userStopped)", e)
  }
  stopTtsPlayback()
  _uiState.update { current ->
    val isTurn1BeforeReply =
      current.chatTurns.none { !it.isUser } &&
        turnStartTeacherDialogue.isBlank() &&
        current.vocabCards.isEmpty() &&
        current.vocabCardData == null &&
        (current.isRunningInference || current.teacherDialogue.isBlank())
    if (isTurn1BeforeReply) {
      currentSessionStartTimestampMs = 0L
      currentSessionImagePath = null
      activeConversationTurnCount = 0
      current.copy(
        isSessionActive = false,
        isRunningInference = false,
        isExtractingCard = false,
        isSubmittingToModel = false,
        isWaitingForFirstToken = false,
        overlayState = TeacherOverlayState.NONE,
        capturedBitmap = null,
        lastSpokenQuestion = null,
        confirmedTranscript = "",
        liveAsrTranscript = "",
        teacherDialogue = "",
        chatTurns = emptyList(),
      )
    } else {
      current.copy(
        isSessionActive = true,
        isRunningInference = false,
        isExtractingCard = false,
        isSubmittingToModel = false,
        isWaitingForFirstToken = false,
        overlayState = TeacherOverlayState.NONE,
        teacherDialogue =
          if (current.isRunningInference) {
            turnStartTeacherDialogue
          } else {
            current.teacherDialogue.ifBlank { turnStartTeacherDialogue }
          },
        chatTurns = current.chatTurns.dropLastWhile { it.isUser },
      )
    }
  }
  schedulePresynthesizeFillers()
}

internal fun XrLanguageTeacherViewModel.resetLessonSessionInternalImpl(
  model: Model? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
  prewarmSession: Boolean = true,
) {
  val targetModel = model ?: activeInferenceModel ?: latestKnownModel
  val unused = cancelTurnJobs(reason = "reset", modelToStop = targetModel)
  if (model != null) {
    latestKnownModel = model
  }
  activeInferenceModel = null
  isConversationInitialized = false
  activeConversationTurnCount = 0
  activeSystemPromptText = null
  activeSessionModelName = null
  activeSessionModelInstance = null
  turnStartTeacherDialogue = ""
  currentSessionIdInternal = UUID.randomUUID().toString()
  currentSessionStartTimestampMs = 0L
  currentSessionImagePath = null
  hudController.onSessionDismissed()
  stopTtsPlayback()
  if (isAsrRecordingOrPending) {
    cancelAsrRecording()
  } else {
    clearAsrTranscript()
  }
  _uiState.update {
    it.copy(
      isSessionActive = false,
      isRunningInference = false,
      isExtractingCard = false,
      isSubmittingToModel = false,
      isWaitingForFirstToken = false,
      overlayState = TeacherOverlayState.NONE,
      capturedBitmap = null,
      vocabCardData = null,
      vocabCards = emptyList(),
      currentVocabCardIndex = 0,
      isCurrentCardSaved = false,
      teacherDialogue = "",
      chatTurns = emptyList(),
      sessionGreetingLine = null,
      lastSpokenQuestion = null,
      errorMessage = null,
    )
  }
  onRunningStateChanged(false)
  if (prewarmSession && targetModel != null) {
    prewarmConversationSession(model = targetModel)
  }
}

/**
 * Runs [LlmModelHelper.runInference], reporting an exception thrown while it starts the inference
 * through [onError] instead of throwing it.
 *
 * `runInference` can throw when the runtime can't start the message, for example after leaving the
 * screen closed the engine. Reporting it through [onError] keeps it from failing the turn's
 * coroutine. Cancellation of the turn's [coroutineScope] is still thrown.
 */
internal fun LlmModelHelper.runInferenceReportingErrors(
  model: Model,
  input: String,
  resultListener: ResultListener,
  cleanUpListener: CleanUpListener,
  onError: (message: String) -> Unit,
  images: List<Bitmap>,
  coroutineScope: CoroutineScope,
  extraContext: Map<String, String>,
  responseJsonSchema: String? = null,
) {
  try {
    runInference(
      model = model,
      input = input,
      resultListener = resultListener,
      cleanUpListener = cleanUpListener,
      onError = onError,
      images = images,
      coroutineScope = coroutineScope,
      extraContext = extraContext,
      responseJsonSchema = responseJsonSchema,
    )
  } catch (e: Exception) {
    // A cancelled turn ends quietly; any other exception, including a `CancellationException`
    // the runtime throws while the turn is still active, is reported.
    if (e is CancellationException && !coroutineScope.isActive) throw e
    Log.w(TAG, "Failed to start the inference", e)
    onError(e.message.orEmpty())
  }
}

internal fun parsePass2CardOutputImpl(
  rawPass2Text: String,
  replyWithTargets: String = "",
  mainLanguage: Language = Language.LANGUAGE_ENGLISH,
  learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
): VocabCardData? {
  val cardMap = TeacherJsonResponse.parseCardOnly(rawPass2Text)
  if (cardMap.isEmpty()) {
    return null
  }
  if (hasFieldNamesAsValues(cardMap)) {
    Log.w(TAG, "Rejected a Pass 2 card that uses field names as values: ${rawPass2Text.take(300)}")
    return null
  }
  val word = cardMap["word"]?.trim().orEmpty()
  val meaning = cardMap["meaning"]?.trim().orEmpty()
  val example = cardMap["example"]?.trim().orEmpty()
  val exampleTranslation = cardMap["example_translation"]?.trim().orEmpty()
  val sanitized =
    XrLanguageTeacherDialogueFormatter.sanitizeVocabCard(
      VocabCardData(
        word = word,
        meaning = meaning,
        example = example,
        exampleTranslation = exampleTranslation,
      ),
      replyWithTargets = replyWithTargets,
      mainLanguage = mainLanguage,
      learnLanguage = learnLanguage,
    )
  return sanitized.takeIf {
    it.word.isNotBlank() && (it.meaning.isNotBlank() || it.exampleTranslation.isNotBlank())
  }
}

/** Card field names, letters only (`example_translation` becomes `exampletranslation`). */
private val CARD_FIELD_NAMES =
  TeacherResponseSchema.CARD_FIELDS.map { name -> name.filter { it.isLetter() } }.toSet()

/** A value that starts with a card field name used as a label, such as `meaning:cup`. */
private val FIELD_NAME_LABEL_REGEX =
  Regex(
    "^\\s*(?:${TeacherResponseSchema.CARD_FIELDS.joinToString("|") { Regex.escape(it) }})" +
      "\\s*[:：]",
    RegexOption.IGNORE_CASE,
  )

/**
 * Returns `true` when at least two values in [cardMap] are card field names, either the whole value
 * (`{"word":"meaning","meaning":"example",...}`) or a label in front of the value
 * (`{"word":"meaning:cup","meaning":"meaning:cup","example":"example:C'est un gobelet",...}`).
 */
private fun hasFieldNamesAsValues(cardMap: Map<String, String>): Boolean =
  cardMap.values.count { value ->
    value.lowercase(Locale.ROOT).filter { it.isLetter() } in CARD_FIELD_NAMES ||
      FIELD_NAME_LABEL_REGEX.containsMatchIn(value)
  } >= 2

/**
 * Builds the compact text-only Pass 2 prompt to extract the structured vocabulary card from the
 * teacher's streamed reply text while TTS is playing. [existingWords] are the words already shown
 * as cards this session, which the model must not extract again.
 */
internal fun buildPass2CardExtractionPromptImpl(
  mainLangName: String,
  learnLangName: String,
  teacherReplyWithTags: String,
  spokenQuestion: String? = null,
  existingWords: List<String> = emptyList(),
): String {
  val cleanQuestion = spokenQuestion?.trim().orEmpty()
  val cleanExisting = existingWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  val instruction =
    """
    Extract only the core focal $learnLangName dictionary term or short set phrase/greeting (noun, verb, greeting, or short set phrase — e.g. isolate "青いマット" from "これは青いマットです。"; allow short set phrases and greetings as valid cards, but never put a full sentence, copula frame, or conversational frame like "To say that, you can say..." in "word") taught in this teacher reply into a JSON flashcard object with keys "word" (core $learnLangName term or short phrase with article if applicable), "meaning" ($mainLangName translation), "example" (complete $learnLangName sentence), and "example_translation" ($mainLangName translation):
    """
      .trimIndent()
  return buildString {
    append(instruction)
    if (cleanQuestion.isNotEmpty()) {
      append("\nStudent question: \"")
      append(cleanQuestion)
      append('"')
    }
    if (cleanExisting.isNotEmpty()) {
      append("\nWords already taught this session (do not re-extract): ")
      append(cleanExisting.joinToString(", "))
    }
    append('\n')
    append(teacherReplyWithTags)
  }
}

/**
 * Builds the Pass 1 streaming prompt instructing Gemma to emit only the spoken `<reply>` on every
 * turn. The vocabulary card is extracted afterwards by Pass 2.
 */
internal fun buildPass1PromptImpl(
  persona: CharacterPersona,
  mainLangName: String,
  learnLangName: String,
  spokenQuestion: String? = null,
  focalSubject: String? = null,
  isFollowUp: Boolean = false,
  history: List<TeacherChatTurn> = emptyList(),
): String {
  val basePrompt =
    persona.buildSingleTurnPrompt(
      mainLangName = mainLangName,
      learnLangName = learnLangName,
      spokenQuestion = spokenQuestion,
      isFollowUp = isFollowUp,
    )
  // The caller passes history only when the conversation was just recreated, which drops the
  // runtime's own multi-turn memory; otherwise the runtime already has the earlier turns.
  val recentTurns = history.takeLast(4)
  val historySection =
    if (recentTurns.isEmpty()) {
      ""
    } else {
      buildString {
        appendLine("Recent dialogue history:")
        for (turn in recentTurns) {
          val speaker = if (turn.isUser) "Student" else persona.displayName
          appendLine("$speaker: ${turn.text}")
        }
        appendLine()
      }
    }
  val focalSection =
    if (!focalSubject.isNullOrBlank() && isFollowUp) {
      "The active lesson subject is \"$focalSubject\".\n\n"
    } else {
      ""
    }
  return "$historySection$focalSection$basePrompt"
}
