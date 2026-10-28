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

/** What a lesson turn does about its vocabulary card once Pass 1 has written the reply. */
sealed interface Pass2CardDecision {
  /** The reply teaches no target-language word, so the turn has no card and skips Pass 2. */
  data object Skip : Pass2CardDecision

  /**
   * Every target-language word of the reply already has a card in this session, so the turn skips
   * Pass 2 and shows [card] again.
   */
  data class ReuseSessionCard(val card: VocabCardData) : Pass2CardDecision

  /** The reply teaches a new word, so Pass 2 extracts its card. */
  data object Extract : Pass2CardDecision
}

/**
 * Decides what a lesson turn does about its vocabulary card from the reply's `<target>` spans
 * ([replyWithTargetTags]) and the cards already shown in this session ([sessionCards]).
 *
 * Words saved in the dictionary don't count as session cards, so a saved word still gets its card
 * in a new session. When every span matches a session card, the card of the first span is shown.
 */
internal fun decidePass2Card(
  replyWithTargetTags: String,
  sessionCards: List<VocabCardData>,
): Pass2CardDecision {
  val spans = XrLanguageTeacherDialogueFormatter.extractAllTargetPhrases(replyWithTargetTags)
  if (spans.isEmpty()) return Pass2CardDecision.Skip
  val matchingCards = spans.map { span ->
    sessionCards.firstOrNull {
      XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(it.word, span)
    }
  }
  // A span without a session card teaches a new word.
  val firstMatch = matchingCards.first()
  if (firstMatch == null || matchingCards.any { it == null }) return Pass2CardDecision.Extract
  return Pass2CardDecision.ReuseSessionCard(firstMatch)
}
