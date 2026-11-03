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

import java.util.Locale
import kotlin.math.max

/**
 * Award breakdown returned by [XrLanguageTeacherVocabGameScoreTracker.recordCorrectAnswer] when a
 * vocabulary sticker or obstacle is cleared.
 *
 * @property pointsAwarded Points awarded for this single clear (`30 * multiplier` on the first
 *   clear of a canonical vocabulary word in the round, or `10 * multiplier` on a repeat clear).
 * @property multiplier Streak multiplier (`1`, `2`, `3`, or `4`) applied to the base award.
 * @property streak Consecutive correct answers after recording this clear.
 * @property bestStreak Highest consecutive correct answer streak reached so far in the round.
 * @property isFirstClearOfWord Whether this clear introduced a new canonical vocabulary word for
 *   the current round and earned the
 *   [XrLanguageTeacherVocabGameScoreTracker.UNIQUE_WORD_BONUS_POINTS] bonus.
 * @property totalScore Updated composite round score after adding [pointsAwarded].
 * @property correctCount Updated total number of correct clears in the round.
 * @property uniqueWordCount Updated count of distinct canonical vocabulary words cleared in the
 *   round.
 */
data class XrLanguageTeacherVocabGameScoreAward(
  val pointsAwarded: Int,
  val multiplier: Int = 1,
  val streak: Int = 0,
  val bestStreak: Int = 0,
  val isFirstClearOfWord: Boolean,
  val totalScore: Int,
  val correctCount: Int,
  val uniqueWordCount: Int,
)

/**
 * Shared composite scoring and streak-multiplier tracker for XR Language Teacher study mini-games
 * (`Vocab Popper` and `Vocab Runner` / `Buddy Runner`).
 *
 * Rewards both practice volume ([POINTS_PER_CORRECT_ANSWER] per clear), vocabulary breadth
 * ([UNIQUE_WORD_BONUS_POINTS] the first time each canonical vocabulary word is cleared in a round),
 * and consecutive accuracy (`1x` for streaks `1..2`, `2x` for `3..5`, `3x` for `6..9`, and `4x` for
 * `10+`), so practicing a larger vocabulary deck scores substantially higher than repeatedly
 * farming a 4-word deck while streaks reward sustained accuracy.
 */
class XrLanguageTeacherVocabGameScoreTracker {
  private val _seenCanonicalWords = mutableSetOf<String>()

  /** Total number of correct clears in the current round. */
  var correctCount: Int = 0
    private set

  /** Composite score accumulated in the current round. */
  var totalScore: Int = 0
    private set

  /** Current consecutive correct-answer streak. */
  var streak: Int = 0
    private set

  /** Highest consecutive correct-answer streak reached in the current round. */
  var bestStreak: Int = 0
    private set

  /** Canonical normalized vocabulary keys cleared at least once in the current round. */
  val seenCanonicalWords: Set<String>
    get() = _seenCanonicalWords

  /** Number of distinct canonical vocabulary words cleared in the current round. */
  val uniqueWordCount: Int
    get() = _seenCanonicalWords.size

  /** Current streak multiplier (`1..4`) corresponding to [streak]. */
  val streakMultiplier: Int
    get() = multiplierForStreak(streak)

  /**
   * Records a correct answer for a deck item with [displayWord] and fallback [entryKey], returning
   * the points awarded and updated composite totals.
   *
   * Canonical word identity is computed via
   * [XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText] on [displayWord] (falling back to a
   * lowercased [entryKey] if normalization produces an empty string) so duplicate cards of the same
   * vocabulary word only earn the unique-word bonus once per round.
   */
  fun recordCorrectAnswer(
    displayWord: String,
    entryKey: String,
  ): XrLanguageTeacherVocabGameScoreAward {
    val normalized = XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(displayWord)
    val canonicalKey = normalized.ifEmpty { entryKey.lowercase(Locale.ROOT) }
    val isFirstClearOfWord = _seenCanonicalWords.add(canonicalKey)
    streak += 1
    bestStreak = max(bestStreak, streak)
    val multiplier = multiplierForStreak(streak)
    val basePoints =
      POINTS_PER_CORRECT_ANSWER + if (isFirstClearOfWord) UNIQUE_WORD_BONUS_POINTS else 0
    val pointsAwarded = basePoints * multiplier
    correctCount += 1
    totalScore += pointsAwarded
    return XrLanguageTeacherVocabGameScoreAward(
      pointsAwarded = pointsAwarded,
      multiplier = multiplier,
      streak = streak,
      bestStreak = bestStreak,
      isFirstClearOfWord = isFirstClearOfWord,
      totalScore = totalScore,
      correctCount = correctCount,
      uniqueWordCount = _seenCanonicalWords.size,
    )
  }

  /**
   * Resets [streak] to `0` after a mistake, missed wave, collision, or unpopped despawn while
   * preserving [totalScore], [correctCount], [uniqueWordCount], and [bestStreak].
   */
  fun resetStreak() {
    streak = 0
  }

  /** Resets all counters and the seen-word set for a new round. */
  fun reset() {
    correctCount = 0
    totalScore = 0
    streak = 0
    bestStreak = 0
    _seenCanonicalWords.clear()
  }

  companion object {
    /** Base points awarded for every correct answer (`+10`). */
    const val POINTS_PER_CORRECT_ANSWER = 10

    /**
     * Bonus points awarded the first time each canonical vocabulary word is cleared in a round
     * (`+20`, for `+30` total before the streak multiplier on a first clear).
     */
    const val UNIQUE_WORD_BONUS_POINTS = 20

    /** Maximum streak multiplier (`4x`, reached at streak `10+`). */
    const val MAX_STREAK_MULTIPLIER = 4

    /**
     * Returns the score multiplier (`1`, `2`, `3`, or `4`) for a consecutive correct-answer
     * [streak]:
     * - `streak < 3` -> `1x`
     * - `3..5` -> `2x`
     * - `6..9` -> `3x`
     * - `10+` -> `4x`
     */
    fun multiplierForStreak(streak: Int): Int =
      when {
        streak >= 10 -> 4
        streak >= 6 -> 3
        streak >= 3 -> 2
        else -> 1
      }
  }
}
