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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingPageInfo
import java.util.UUID

/**
 * Feature ID used to record that the first-time introduction dialog has been completed. Changed
 * with the new introduction cards so that users who completed the earlier introduction see them
 * once.
 */
const val XR_LANGUAGE_TEACHER_ONBOARDING_FEATURE_ID = "xr_language_teacher_intro_v2"

/**
 * Feature ID used to record that the user has dismissed the tooltip on the camera screen that
 * points at the language and teacher settings button.
 */
const val XR_LANGUAGE_TEACHER_SETTINGS_TOOLTIP_FEATURE_ID = "xr_language_teacher_tooltip_tour_v1"

/**
 * The introduction cards of the first-time onboarding, in order. The preferences page follows them.
 * Cards show their looping animation, else their image, else a placeholder until one is available.
 *
 * Where to change the languages and teacher later isn't a card: a tooltip points at the settings
 * button the first time the camera screen opens after onboarding.
 */
internal val XLT_ONBOARDING_CARDS: List<OnboardingPageInfo> =
  listOf(
    OnboardingPageInfo(
      titleRes = R.string.xrteacher_onboarding_welcome_title,
      descriptionRes = R.string.xrteacher_onboarding_intro_welcome_body,
      imageAssetPath = "xr/onboarding1.png",
    ),
    OnboardingPageInfo(
      titleRes = R.string.xrteacher_onboarding_intro_point_tap_ask_title,
      descriptionRes = R.string.xrteacher_onboarding_intro_point_tap_ask_body,
      animationAssetPath = "xr/onboarding2.webp",
    ),
    OnboardingPageInfo(
      titleRes = R.string.xrteacher_onboarding_intro_review_title,
      descriptionRes = R.string.xrteacher_onboarding_intro_review_body,
      imageAssetPath = "xr/onboarding3.png",
    ),
  )

internal val ONBOARDING_PAGE_COUNT = XLT_ONBOARDING_CARDS.size + 1
internal val ONBOARDING_PREFERENCES_PAGE = ONBOARDING_PAGE_COUNT - 1

/**
 * Language and teacher preferences being edited in a dialog before they are applied.
 *
 * @property promptTemplateEdits Persona prompt templates saved in the prompt editor during this
 *   dialog, keyed by persona. They are only stored when the draft is applied.
 */
data class TeacherPreferencesDraft(
  val mainLanguage: Language,
  val learnLanguage: Language,
  val persona: CharacterPersona,
  val promptTemplateEdits: Map<CharacterPersona, String> = emptyMap(),
)

internal const val DRAFT_SAVER_FIXED_FIELD_COUNT = 3

internal val TeacherPreferencesDraftSaver: Saver<TeacherPreferencesDraft, Any> =
  listSaver<TeacherPreferencesDraft, Any>(
    save = { draft ->
      buildList<Any> {
        add(draft.mainLanguage.number)
        add(draft.learnLanguage.number)
        add(draft.persona.id)
        for ((persona, template) in draft.promptTemplateEdits) {
          add(persona.id)
          add(template)
        }
      }
    },
    restore = { saved ->
      TeacherPreferencesDraft(
        mainLanguage = Language.forNumber(saved[0] as Int) ?: Language.LANGUAGE_ENGLISH,
        learnLanguage = Language.forNumber(saved[1] as Int) ?: DEFAULT_LEARN_LANGUAGE,
        persona = CharacterPersona.fromId(saved[2] as String),
        promptTemplateEdits =
          saved.drop(DRAFT_SAVER_FIXED_FIELD_COUNT).chunked(2).associate { (id, template) ->
            CharacterPersona.fromId(id as String) to (template as String)
          },
      )
    },
  )

/**
 * Remembers the draft edited in a preferences dialog across configuration changes. The draft is
 * reset to [initialPreferences] whenever the stored preferences change.
 */
@Composable
internal fun rememberTeacherPreferencesDraft(
  initialPreferences: TeacherPreferencesDraft
): MutableState<TeacherPreferencesDraft> =
  rememberSaveable(initialPreferences, stateSaver = TeacherPreferencesDraftSaver) {
    mutableStateOf(initialPreferences)
  }

/**
 * Returns "the cup of coffee" in [language], used as the highlighted target phrase in the teacher
 * response example. Falls back to English for languages without a translation.
 *
 * These phrases are in the language being learned rather than the UI language, so they are not
 * string resources: they must stay the same in every app locale.
 */
internal fun teacherResponseExamplePhrase(language: Language): String =
  when (language) {
    Language.LANGUAGE_SPANISH -> "la taza de café"
    Language.LANGUAGE_FRENCH -> "la tasse de café"
    Language.LANGUAGE_GERMAN -> "die Tasse Kaffee"
    Language.LANGUAGE_ITALIAN -> "la tazza di caffè"
    Language.LANGUAGE_PORTUGUESE -> "a xícara de café"
    Language.LANGUAGE_CHINESE -> "咖啡杯"
    Language.LANGUAGE_JAPANESE -> "コーヒーカップ"
    Language.LANGUAGE_KOREAN -> "커피잔"
    Language.LANGUAGE_HINDI -> "कॉफ़ी का कप"
    Language.LANGUAGE_THAI -> "แก้วกาแฟ"
    Language.LANGUAGE_VIETNAMESE -> "tách cà phê"
    Language.LANGUAGE_ARABIC -> "فنجان القهوة"
    Language.LANGUAGE_RUSSIAN -> "чашка кофе"
    else -> "the cup of coffee"
  }

/** Returns the string resource ID of the sample reply shown for this persona. */
@StringRes
internal fun CharacterPersona.responseExampleResId(): Int =
  when (this) {
    CharacterPersona.BUDDY -> R.string.xrteacher_response_example_buddy
    CharacterPersona.NERDY -> R.string.xrteacher_response_example_nerdy
    CharacterPersona.SECRET_AGENT -> R.string.xrteacher_response_example_secret_agent
    CharacterPersona.WIZARD -> R.string.xrteacher_response_example_wizard
  }

internal const val MESSAGE_TYPE_TEXT = "TEXT"
internal const val MESSAGE_TYPE_IMAGE = "IMAGE"
internal const val MESSAGE_TYPE_AR_VOCAB = "AR_VOCAB"

/** A speech engine that the teacher HUD reports on. */
enum class SpeechEngine {
  /** ML Kit GenAI Speech Recognition (Basic or Advanced). */
  MLKIT_ASR,
  /** Platform ASR (`android.speech.SpeechRecognizer`). */
  SYSTEM_ASR,
  /** Parakeet TDT 0.6B run on-device by MediaPipe `AudioTranscriber`. */
  PARAKEET_ASR,
  /** ML Kit GenAI Text-to-Speech. */
  MLKIT_TTS,
  /** Platform TTS (`android.speech.tts.TextToSpeech`). */
  PLATFORM_TTS,
  /** Kokoro 82M run on-device by MediaPipe `TextToSpeech`. */
  KOKORO_TTS,
  /** PocketTTS 100M run on-device by MediaPipe `TextToSpeech`. */
  POCKET_TTS;

  companion object {
    /** Maps an ASR model ID to its corresponding HUD [SpeechEngine] category. */
    fun fromAsrModelId(modelId: String?): SpeechEngine? {
      if (modelId.isNullOrBlank()) return null
      if (AsrModelIdentifier.isSystemEngine(modelId)) return SYSTEM_ASR
      return when (AsrModelRegistry.resolveEngineType(modelId)) {
        AsrEngineType.SPEECH_PROCESSOR -> PARAKEET_ASR
        AsrEngineType.MLKIT_BASIC,
        AsrEngineType.MLKIT_ADVANCED -> MLKIT_ASR
        AsrEngineType.SYSTEM_DEFAULT -> SYSTEM_ASR
        else -> null
      }
    }
  }
}

/** How far along a [SpeechEngine] is, for the HUD status dot. */
enum class SpeechEngineReadiness {
  /** The selected engine is initialized and usable. */
  READY,
  /** The selected engine is downloading or warming up. */
  WARMING,
  /**
   * The selected engine couldn't start or failed. XR Language Teacher never falls back to another
   * engine.
   */
  ERROR,
}

/** Overlay state for the AR Language Teacher camera session. */
enum class TeacherOverlayState {
  NONE,
  INITIALIZING,
  IDENTIFYING,
  THINKING,
}

/** A single user or teacher turn in the AR Language Teacher conversation history. */
data class TeacherChatTurn(
  val id: String = UUID.randomUUID().toString(),
  val isUser: Boolean,
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val vocabCard: VocabCardData? = null,
)

/**
 * Deserialized representation of a persisted or active AR Language Teacher lesson session for the
 * Session history side sheet.
 */
data class LessonHistorySession(
  val sessionId: String,
  val title: String,
  val timestampMs: Long,
  val persona: CharacterPersona = CharacterPersona.BUDDY,
  val greeting: String = "",
  val imageFilePath: String? = null,
  val inMemoryBitmap: Bitmap? = null,
  val vocabWord: String? = null,
  val vocabMeaning: String? = null,
  /** The full primary vocabulary card, or null when the session has none. */
  val primaryVocabCard: VocabCardData? = null,
  /** The main language the session was recorded in, or null for legacy sessions. */
  val mainLanguage: Language? = null,
  /** The learn language the session was recorded in, or null for legacy sessions. */
  val learnLanguage: Language? = null,
  val turns: List<TeacherChatTurn> = emptyList(),
  val isActiveSession: Boolean = false,
)

/**
 * Resolves the display title for a lesson session: the primary vocabulary word ([vocabWord]), else
 * the first teacher reply in [turns] collapsed onto one line, else [untitledFallback].
 *
 * Turn texts are stored as plain text (target-language tags are already stripped), so they are used
 * as-is apart from whitespace collapsing.
 */
internal fun resolveLessonSessionTitle(
  turns: List<TeacherChatTurn>,
  vocabWord: String?,
  untitledFallback: String,
): String {
  val primaryVocabWord = vocabWord?.trim()?.takeIf { it.isNotEmpty() }
  val firstTeacherReply =
    turns
      .firstOrNull { !it.isUser && it.text.isNotBlank() }
      ?.text
      ?.trim()
      ?.replace(WHITESPACE_RUN_REGEX, " ")
  return primaryVocabWord ?: firstTeacherReply ?: untitledFallback
}

private val WHITESPACE_RUN_REGEX = Regex("\\s+")

/**
 * Deserializes a persisted [ChatSessionProto] into a [LessonHistorySession] for the Session history
 * side sheet.
 */
internal fun ChatSessionProto.toLessonHistorySession(
  untitledFallback: String
): LessonHistorySession {
  val personaId = messagesList.firstNotNullOfOrNull { msg ->
    msg.personaId.takeIf { it.isNotBlank() } ?: msg.accelerator.takeIf { it.isNotBlank() }
  }
  val resolvedPersona = personaId?.let { CharacterPersona.fromId(it) } ?: CharacterPersona.BUDDY
  val imagePath =
    messagesList
      .firstOrNull { it.messageType == MESSAGE_TYPE_IMAGE }
      ?.imageFilePathsList
      ?.firstOrNull()

  var cursor = 0
  var greetingText = ""
  if (messagesList.isNotEmpty()) {
    val first = messagesList[0]
    val second = messagesList.getOrNull(1)
    val isExplicitGreeting =
      first.messageType == MESSAGE_TYPE_TEXT &&
        first.side == ChatSideProto.CHAT_SIDE_MODEL &&
        first.hideSenderLabel
    val isLegacyGreetingBeforeMedia =
      first.messageType == MESSAGE_TYPE_TEXT &&
        first.side == ChatSideProto.CHAT_SIDE_MODEL &&
        second != null &&
        (second.messageType == MESSAGE_TYPE_IMAGE || second.messageType == MESSAGE_TYPE_AR_VOCAB)
    val isLegacyGreetingBeforeModelTurn =
      first.messageType == MESSAGE_TYPE_TEXT &&
        first.side == ChatSideProto.CHAT_SIDE_MODEL &&
        second != null &&
        second.messageType == MESSAGE_TYPE_TEXT &&
        second.side == ChatSideProto.CHAT_SIDE_MODEL
    if (isExplicitGreeting || isLegacyGreetingBeforeMedia || isLegacyGreetingBeforeModelTurn) {
      greetingText = first.content
      cursor = 1
    }
  }

  if (cursor < messagesList.size && messagesList[cursor].messageType == MESSAGE_TYPE_IMAGE) {
    cursor++
  }

  var primaryVocabCard: VocabCardData? = null
  if (cursor < messagesList.size && messagesList[cursor].messageType == MESSAGE_TYPE_AR_VOCAB) {
    primaryVocabCard = messagesList[cursor].toHistoryVocabCard()
    cursor++
  }
  val parsedWord = primaryVocabCard?.word
  val parsedMeaning = primaryVocabCard?.meaning?.takeIf { it.isNotEmpty() }

  val turns = mutableListOf<TeacherChatTurn>()
  var turnIndex = 0
  while (cursor < messagesList.size) {
    val msg = messagesList[cursor]
    if (msg.messageType == MESSAGE_TYPE_TEXT) {
      val isUser = msg.side == ChatSideProto.CHAT_SIDE_USER
      var turnVocabCard: VocabCardData? = null
      val nextMsg = messagesList.getOrNull(cursor + 1)
      if (!isUser && nextMsg != null && nextMsg.messageType == MESSAGE_TYPE_AR_VOCAB) {
        turnVocabCard = nextMsg.toHistoryVocabCard()
        cursor += 2
      } else {
        cursor++
      }
      turns.add(
        TeacherChatTurn(
          id = "${sessionId}_turn_$turnIndex",
          isUser = isUser,
          text = msg.content,
          timestamp = timestampMs,
          vocabCard = turnVocabCard,
        )
      )
      turnIndex++
    } else {
      cursor++
    }
  }

  // Recompute the title from the content so sessions saved under an older title rule match new
  // ones; the stored title is only used when the session has no vocabulary card or teacher reply.
  val resolvedTitle =
    resolveLessonSessionTitle(
      turns = turns,
      vocabWord = parsedWord,
      untitledFallback = title.trim().ifEmpty { untitledFallback },
    )
  val firstStoredVocabCard =
    messagesList
      .firstOrNull { it.messageType == MESSAGE_TYPE_AR_VOCAB && it.hasVocabCard() }
      ?.vocabCard
  return LessonHistorySession(
    sessionId = sessionId,
    title = resolvedTitle,
    timestampMs = timestampMs,
    persona = resolvedPersona,
    greeting = greetingText,
    imageFilePath = imagePath,
    vocabWord = parsedWord,
    vocabMeaning = parsedMeaning,
    primaryVocabCard = primaryVocabCard,
    // A session's languages are fixed, so any stored card carries them. Unset proto enums read as
    // LANGUAGE_UNSPECIFIED, which is treated as unknown.
    mainLanguage = firstStoredVocabCard?.mainLanguage?.takeIf { it.isKnownLanguage() },
    learnLanguage = firstStoredVocabCard?.learnLanguage?.takeIf { it.isKnownLanguage() },
    turns = turns,
    isActiveSession = false,
  )
}

/** Whether this is a real language rather than an unset or unknown proto enum value. */
private fun Language.isKnownLanguage(): Boolean =
  this != Language.LANGUAGE_UNSPECIFIED && this != Language.UNRECOGNIZED

/**
 * Reads the vocabulary card from a [MESSAGE_TYPE_AR_VOCAB] message: the full `vocab_card` when it
 * has a word, otherwise the legacy `"word\nmeaning"` content. Text fields are trimmed. Returns null
 * when no word is found.
 */
private fun ChatMessageProto.toHistoryVocabCard(): VocabCardData? {
  if (hasVocabCard() && vocabCard.word.isNotBlank()) {
    return vocabCard.toVocabCardData()
  }
  val parts = content.split('\n', limit = 2)
  val word = parts.getOrNull(0)?.trim().orEmpty()
  val meaning = parts.getOrNull(1)?.trim().orEmpty()
  return if (word.isNotEmpty()) VocabCardData(word = word, meaning = meaning) else null
}

enum class XrLanguageTeacherScreenName(val value: String) {
  CAMERA("camera"),
  VOCABULARY("vocabulary"),
  STUDY("study"),
}

internal enum class TtsPlaybackSource {
  DIALOGUE,
  VOCAB_PHRASE,
  OTHER,
}

/** Playback state of a vocabulary word or example sentence that the user asked to hear. */
sealed interface PhrasePlaybackState {
  /** No vocabulary phrase is loading or playing. */
  data object Idle : PhrasePlaybackState

  /** The TTS engine is preparing or synthesizing [phrase], and no audio played yet. */
  data class Loading(val phrase: String) : PhrasePlaybackState

  /** [phrase] is speaking. */
  data class Playing(val phrase: String) : PhrasePlaybackState
}
