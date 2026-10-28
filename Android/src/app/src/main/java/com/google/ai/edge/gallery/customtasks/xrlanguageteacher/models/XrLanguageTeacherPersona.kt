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

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.R
import java.util.Locale

/**
 * Character personas for the AR Language Teacher experience, defining voice profiles and system
 * prompt templates. The voice preview lines are in `XrLanguageTeacherSpokenPhrases`.
 *
 * The voice each persona speaks with on each TTS model is in [PersonaVoices].
 *
 * @property id Stable identifier persisted in teacher preferences and used to key custom prompts.
 */
enum class CharacterPersona(
  val id: String,
  val displayName: String,
  val defaultPromptTemplate: String,
) {
  BUDDY(
    id = "buddy",
    displayName = "Buddy",
    defaultPromptTemplate =
      """
      You are Buddy, an enthusiastic, supportive, and friendly language teacher.
      The student speaks {MAIN_LANGUAGE} and is learning {LEARN_LANGUAGE}.
      Teach with warmth, encouragement, and high energy. Keep your speech concise (strictly 1 to 3 sentences total).
      """
        .trimIndent(),
  ),
  NERDY(
    id = "nerdy",
    displayName = "Nerdy",
    defaultPromptTemplate =
      """
      You are Nerdy, an endearingly geeky language teacher who loves word origins, grammar quirks, cultural context, and fun linguistic facts.
      The student speaks {MAIN_LANGUAGE} and is learning {LEARN_LANGUAGE}.
      Teach with curious enthusiasm, sharing one quick fun fact when it helps (strictly 1 to 3 sentences total).
      """
        .trimIndent(),
  ),
  SECRET_AGENT(
    id = "secret_agent",
    displayName = "Secret Agent",
    defaultPromptTemplate =
      """
      You are Secret Agent, a cool and composed spy who teaches language like a covert mission briefing.
      The student speaks {MAIN_LANGUAGE} and is learning {LEARN_LANGUAGE}.
      Use light spy flair such as missions, code words, and intel while keeping the lesson clear (strictly 1 to 3 sentences total).
      """
        .trimIndent(),
  ),
  WIZARD(
    id = "wizard",
    displayName = "Wizard",
    defaultPromptTemplate =
      """
      You are Wizard, a wise and whimsical wizard who treats every new word like a magic spell.
      The student speaks {MAIN_LANGUAGE} and is learning {LEARN_LANGUAGE}.
      Teach with mystical, playful charm and spell-casting flair while keeping the lesson clear (strictly 1 to 3 sentences total).
      """
        .trimIndent(),
  );

  /**
   * Resolves the system prompt with language placeholders substituted, visual-grounding rules
   * cached once per session, and the Pass 1 output contract appended: a single `<reply>` block with
   * `<target>` spans. The vocabulary card is extracted afterwards by a separate Pass 2.
   */
  fun buildSystemPrompt(
    mainLangName: String,
    learnLangName: String,
    customTemplate: String? = null,
  ): String {
    val template = customTemplate?.takeIf { it.isNotBlank() } ?: defaultPromptTemplate
    val resolvedPersona =
      template.replace("{MAIN_LANGUAGE}", mainLangName).replace("{LEARN_LANGUAGE}", learnLangName)
    val schemaBlock = buildReplyOnlySchemaSection(mainLangName, learnLangName)
    return "$resolvedPersona\n\n$schemaBlock"
  }

  /**
   * Builds the per-turn user prompt. A turn without a question (a photo only) asks the model to
   * identify and teach the focal item; a question turn restates where `<target>` spans go.
   * Follow-up turns always carry a question: the screen drops a follow-up when nothing was heard.
   */
  fun buildSingleTurnPrompt(
    mainLangName: String,
    learnLangName: String,
    spokenQuestion: String? = null,
    isFollowUp: Boolean = false,
  ): String {
    val question = spokenQuestion?.trim().orEmpty()
    if (question.isEmpty()) {
      return "Identify the focal item in $mainLangName and teach it in $learnLangName inside <reply>...</reply>. Start immediately with <reply>."
    }
    val turnLabel = if (isFollowUp) "Follow-up" else "Student asked"
    return "$turnLabel: \"$question\". Answer their full question in $mainLangName outside <target> (grounding \"this\"/\"that\" to the camera object) and wrap ONLY $learnLangName words or full $learnLangName sentences inside <target>...</target>. Start immediately with <reply>."
  }

  private fun buildReplyOnlySchemaSection(mainLangName: String, learnLangName: String): String {
    val rules =
      """
      Rules:
      1. Reply with ONLY one <reply>...</reply> block (1 to 3 short spoken sentences, no parentheses).
      2. Ground "this"/"that" to the main object in the camera view: identify the object and teach the exact sentence the student asked to say about it (e.g., wanting it, complimenting it, or asking about it) in $learnLangName. If their question does not refer to the camera view, teach the requested phrase directly. If they ask what text in the image says or means, read it in <target>...</target> and explain its meaning in $mainLangName.
      3. Write all $mainLangName words OUTSIDE <target>. Put ONLY $learnLangName words or complete $learnLangName sentences INSIDE <target>...</target>. Never put $mainLangName inside <target>.
      """
        .trimIndent()
    // A language without its own sample gets no example, so the example never puts another
    // language inside <target>.
    val sampleWord = sampleTargetWord(learnLangName) ?: return rules
    val sampleWantSentence = sampleTargetWantSentence(learnLangName) ?: return rules
    val example =
      """
      Example ("What is this and how do I say I want this?"):
      <reply>
      ${sampleHostReply(mainLangName, "<target>$sampleWord</target>", "<target>$sampleWantSentence</target>")}
      </reply>
      """
        .trimIndent()
    return "$rules\n\n$example"
  }

  /**
   * The example reply, with the text outside `<target>` in [mainLangName] so it matches
   * rule 3. [word] and [sentence] are the `<target>` spans. Unlisted native languages use English.
   */
  private fun sampleHostReply(mainLangName: String, word: String, sentence: String): String =
    when (mainLangName.lowercase(Locale.ROOT)) {
      "japanese" -> "それはマグカップです、$word！欲しいと伝えるには、${sentence}と言いましょう。"
      "korean" -> "그건 머그컵이에요, $word! 갖고 싶다고 말하려면 ${sentence}라고 하세요."
      "chinese",
      "mandarin" -> "那是一个马克杯，$word！想说你想要它，可以说：$sentence"
      "french" -> "C'est une tasse, $word ! Pour dire que tu la veux, dis : $sentence"
      "german" -> "Das ist eine Tasse, $word! Um zu sagen, dass du sie möchtest, sag: $sentence"
      "italian" -> "Questa è una tazza, $word! Per dire che la vuoi, di': $sentence"
      "portuguese" -> "Isso é uma caneca, $word! Para dizer que você a quer, diga: $sentence"
      "spanish" -> "¡Eso es una taza, $word! Para decir que la quieres, di: $sentence"
      "hindi" -> "यह एक मग है, $word! यह कहने के लिए कि आपको यह चाहिए, कहिए: $sentence"
      "thai" -> "นั่นคือแก้วมัค $word! ถ้าอยากบอกว่าอยากได้ ให้พูดว่า $sentence"
      "vietnamese" -> "Đó là một cái cốc, $word! Để nói rằng bạn muốn nó, hãy nói: $sentence"
      "arabic" -> "هذا كوب، $word! لتقول إنك تريده، قل: $sentence"
      "russian" -> "Это кружка, $word! Чтобы сказать, что ты её хочешь, скажи: $sentence"
      else -> "That is a mug, $word! To say you want it, say, $sentence"
    }

  private fun sampleTargetWord(learnLangName: String): String? =
    when (learnLangName.lowercase(Locale.ROOT)) {
      "english" -> "a mug"
      "japanese" -> "マグカップ"
      "korean" -> "머그컵"
      "chinese",
      "mandarin" -> "马克杯"
      "french" -> "la tasse"
      "german" -> "die Tasse"
      "italian" -> "la tazza"
      "portuguese" -> "a caneca"
      "hindi" -> "मग"
      "thai" -> "แก้วมัค"
      "vietnamese" -> "cái cốc"
      "arabic" -> "كوب"
      "russian" -> "кружка"
      "spanish" -> "la taza"
      else -> null
    }

  private fun sampleTargetWantSentence(learnLangName: String): String? =
    when (learnLangName.lowercase(Locale.ROOT)) {
      "english" -> "I would like this mug, please."
      "japanese" -> "このマグカップが欲しいです。"
      "korean" -> "이 머그컵을 원해요."
      "chinese",
      "mandarin" -> "我想要这个马克杯。"
      "french" -> "Je voudrais cette tasse, s'il vous plaît."
      "german" -> "Ich möchte bitte diese Tasse."
      "italian" -> "Vorrei questa tazza, per favore."
      "portuguese" -> "Eu quero esta caneca, por favor."
      "hindi" -> "मुझे यह मग चाहिए।"
      "thai" -> "ฉันอยากได้แก้วมัคใบนี้ครับ"
      "vietnamese" -> "Tôi muốn lấy cái cốc này."
      "arabic" -> "أريد هذا الكوب من فضلك."
      "russian" -> "Я хочу эту кружку, пожалуйста."
      "spanish" -> "Quiero esta taza, por favor."
      else -> null
    }

  companion object {
    /**
     * IDs of personas that were renamed, mapped to the persona that replaced them, so saved
     * preferences keep a matching persona.
     */
    private val LEGACY_IDS: Map<String, CharacterPersona> =
      mapOf("professor" to NERDY, "pirate" to SECRET_AGENT, "movie_narrator" to WIZARD)

    /** Returns the persona with [id], migrating legacy IDs, or [BUDDY] when [id] is unknown. */
    fun fromId(id: String?): CharacterPersona {
      if (id.isNullOrBlank()) return BUDDY
      return entries.firstOrNull { it.id == id } ?: LEGACY_IDS[id] ?: BUDDY
    }
  }
}

/** Lifecycle state of a persona voice sample preview in the Teacher settings dialog. */
sealed interface PersonaPreviewState {
  /** No persona voice preview is currently loading or playing. */
  data object Idle : PersonaPreviewState

  /** The TTS engine/voice is warming up before speaking [persona]'s preview line. */
  data class Loading(val persona: CharacterPersona) : PersonaPreviewState

  /** [persona]'s preview line is actively speaking. */
  data class Playing(val persona: CharacterPersona) : PersonaPreviewState
}

/** Returns the localized display name string resource ID for the given [CharacterPersona]. */
@StringRes
fun CharacterPersona.displayNameResId(): Int {
  return when (this) {
    CharacterPersona.BUDDY -> R.string.xrteacher_persona_buddy_name
    CharacterPersona.NERDY -> R.string.xrteacher_persona_nerdy_name
    CharacterPersona.SECRET_AGENT -> R.string.xrteacher_persona_secret_agent_name
    CharacterPersona.WIZARD -> R.string.xrteacher_persona_wizard_name
  }
}

/** Returns the localized subtitle string resource ID for the given [CharacterPersona]. */
@StringRes
fun CharacterPersona.subtitleResId(): Int {
  return when (this) {
    CharacterPersona.BUDDY -> R.string.xrteacher_persona_buddy_subtitle
    CharacterPersona.NERDY -> R.string.xrteacher_persona_nerdy_subtitle
    CharacterPersona.SECRET_AGENT -> R.string.xrteacher_persona_secret_agent_subtitle
    CharacterPersona.WIZARD -> R.string.xrteacher_persona_wizard_subtitle
  }
}
