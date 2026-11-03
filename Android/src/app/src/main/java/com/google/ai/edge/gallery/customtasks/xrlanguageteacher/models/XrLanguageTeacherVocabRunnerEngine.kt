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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight vocabulary item with a sticker image used as an obstacle in `Vocab Runner`.
 *
 * @property entryKey Stable key of the saved vocabulary entry (used to look up its sticker image).
 * @property word Raw saved target-language word, e.g. `"la manzana"`.
 * @property displayWord Word shown on screen; defaults to the primary learn term of [word].
 * @property meaning Native-language meaning of [word], shown when no sticker is available.
 */
data class XrLanguageTeacherVocabRunnerDeckItem(
  val entryKey: String,
  val word: String,
  val meaning: String,
  val displayWord: String = XrLanguageTeacherSpeechMatcher.extractPrimaryLearnTerm(word),
) {
  /** Precomputed normalized match forms of [word]. */
  val targetMatchForms: Set<String> = XrLanguageTeacherSpeechMatcher.targetMatchForms(word)
}

/**
 * An approaching sticker obstacle along the runner track.
 *
 * @property x Position in normalized track units where Buddy stands at
 *   [XrLanguageTeacherVocabRunnerEngine.BUDDY_X] and `1.0` is the right edge of the screen.
 * @property bumpCount How many times Buddy bumped into this obstacle.
 * @property clearProgress Progress, in `[0, 1)`, of the obstacle's pop and fade out of Buddy's path
 *   once its word was spoken (or it broke), or `null` while it is still in the way.
 * @property didBreakOnMaxBumps Whether the obstacle broke after
 *   [XrLanguageTeacherVocabRunnerEngine.MAX_BUMPS_PER_OBSTACLE] bumps, for no points.
 */
data class XrLanguageTeacherVocabRunnerObstacle(
  val id: Long,
  val item: XrLanguageTeacherVocabRunnerDeckItem,
  val x: Float,
  val bumpCount: Int = 0,
  val clearProgress: Float? = null,
  val didBreakOnMaxBumps: Boolean = false,
) {
  /** Whether the obstacle is popping (or breaking) out of Buddy's path. */
  val isClearing: Boolean
    get() = clearProgress != null
}

/** Lifecycle phase of a `Vocab Runner` round. */
enum class XrLanguageTeacherVocabRunnerPhase {
  PLAYING,
  FINISHED,
}

/**
 * Snapshot of the `Vocab Runner` game state.
 *
 * @property remainingMilliseconds Time left in the 60-second round.
 * @property endReason Why the round ended, once [phase] reaches
 *   [XrLanguageTeacherVocabRunnerPhase.FINISHED].
 * @property lives Remaining lives in `Tap` mode.
 * @property maxLives Maximum lives at the start of a round in `Tap` mode.
 * @property correctCount Number of obstacles cleared so far in the round.
 * @property trackDistance Total scrolled distance in normalized track units, used to animate the
 *   track dashes and Buddy's stride.
 * @property buddyRecoilOffset Buddy's horizontal recoil (negative, in normalized track units) while
 *   the track knocks back after a bump.
 * @property isStumbled Whether Buddy is briefly stumbling after a collision.
 * @property choices Four multiple-choice word buttons displayed beneath the track in `Tap` mode.
 */
data class XrLanguageTeacherVocabRunnerState(
  val phase: XrLanguageTeacherVocabRunnerPhase = XrLanguageTeacherVocabRunnerPhase.PLAYING,
  val endReason: XrLanguageTeacherVocabGameEndReason =
    XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
  val remainingMilliseconds: Long = XrLanguageTeacherVocabRunnerEngine.ROUND_DURATION_MILLISECONDS,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val lives: Int = XrLanguageTeacherVocabRunnerEngine.INITIAL_LIVES,
  val maxLives: Int = XrLanguageTeacherVocabRunnerEngine.INITIAL_LIVES,
  val correctCount: Int = 0,
  val uniqueWordCount: Int = 0,
  val streak: Int = 0,
  val bestStreak: Int = 0,
  val streakMultiplier: Int = 1,
  val scrollSpeed: Float = XrLanguageTeacherVocabRunnerEngine.INITIAL_SCROLL_SPEED,
  val trackDistance: Float = 0f,
  val buddyRecoilOffset: Float = 0f,
  val isStumbled: Boolean = false,
  val obstacles: List<XrLanguageTeacherVocabRunnerObstacle> = emptyList(),
  val choices: List<XrLanguageTeacherVocabRunnerDeckItem> = emptyList(),
)

/** Frame events emitted by [XrLanguageTeacherVocabRunnerEngine.step]. */
data class XrLanguageTeacherVocabRunnerStepEvents(
  /** Buddy bumped into an obstacle this frame. */
  val didCollide: Boolean = false,
  /** Display word of the obstacle that broke after its last bump this frame, if any. */
  val missedWord: String? = null,
  /** The 60-second round ended this frame. */
  val didFinishRound: Boolean = false,
)

/** Result returned when the player speaks the upcoming obstacle's word to clear it. */
data class XrLanguageTeacherVocabRunnerClearEvent(
  val matchedWord: String,
  val pointsAwarded: Int =
    XrLanguageTeacherVocabGameScoreTracker.POINTS_PER_CORRECT_ANSWER +
      XrLanguageTeacherVocabGameScoreTracker.UNIQUE_WORD_BONUS_POINTS,
  val multiplier: Int = 1,
  val streak: Int = 1,
  val newScore: Int,
  val isNewHighScore: Boolean,
)

/** Result returned when the player taps one of the multiple-choice buttons in `Tap` mode. */
data class XrLanguageTeacherVocabRunnerChoiceOutcome(
  val selectedItem: XrLanguageTeacherVocabRunnerDeckItem,
  val correctItem: XrLanguageTeacherVocabRunnerDeckItem,
  val isCorrect: Boolean,
  val pointsAwarded: Int,
  val multiplier: Int,
  val streak: Int,
  val newScore: Int,
  val isNewHighScore: Boolean,
  val remainingLives: Int,
  val didFinishRound: Boolean,
)

/**
 * Render-ready pose of the Buddy mascot derived from a [XrLanguageTeacherVocabRunnerState].
 *
 * Offsets are in density-independent pixels and match the iOS Buddy runner drawing.
 *
 * @property bobOffsetDp Upward running bob.
 * @property tiltDegrees Body rotation: leaning back while stumbling, and swaying with the stride
 *   while running.
 * @property leftFootOffsetDp Horizontal offset of the left foot from its rest position.
 * @property rightFootOffsetDp Horizontal offset of the right foot from its rest position.
 * @property showsOpenMouth Whether Buddy shows a round "o" mouth (stumbling) instead of a smile.
 */
data class XrLanguageTeacherVocabRunnerBuddyPose(
  val bobOffsetDp: Float,
  val tiltDegrees: Float,
  val leftFootOffsetDp: Float,
  val rightFootOffsetDp: Float,
  val showsOpenMouth: Boolean,
)

/** Returns Buddy's animation pose for this state. */
fun XrLanguageTeacherVocabRunnerState.buddyPose(): XrLanguageTeacherVocabRunnerBuddyPose {
  val stridePhase = trackDistance * BUDDY_STRIDE_FREQUENCY
  val strideSin = sin(stridePhase)
  return XrLanguageTeacherVocabRunnerBuddyPose(
    bobOffsetDp = abs(strideSin) * BUDDY_BOB_DP,
    tiltDegrees =
      if (isStumbled) BUDDY_STUMBLE_TILT_DEGREES else strideSin * BUDDY_RUN_TILT_DEGREES,
    leftFootOffsetDp = strideSin * BUDDY_FOOT_SWING_DP,
    rightFootOffsetDp = -strideSin * BUDDY_FOOT_SWING_DP,
    showsOpenMouth = isStumbled,
  )
}

private const val BUDDY_STRIDE_FREQUENCY = 28f
private const val BUDDY_BOB_DP = 5f
private const val BUDDY_STUMBLE_TILT_DEGREES = -18f
private const val BUDDY_RUN_TILT_DEGREES = 4f
private const val BUDDY_FOOT_SWING_DP = 7f

/**
 * Pure, deterministic 60-second runner physics and obstacle engine for `Vocab Runner`.
 *
 * Sticker obstacles scroll toward Buddy; speaking the nearest obstacle's word (`Speak` mode) or
 * tapping the matching multiple-choice button (`Tap` mode) pops it out of Buddy's path (`+1`) and
 * speeds the track up. In `Speak` mode, bumping into an obstacle knocks the whole track back so the
 * player can try the word again, and the third bump breaks the obstacle for no points. In `Tap`
 * mode, a wrong button tap or an obstacle reaching Buddy deducts 1 life (ending the round early on
 * the 3rd mistake). The round ends after [ROUND_DURATION_MILLISECONDS] or when lives reach `0`. Not
 * thread-safe: drive [step], [selectChoice], and [processTranscript] from a single thread (normally
 * the main thread) and publish [state] snapshots to the UI.
 *
 * @param deck Vocabulary items to use as obstacles, drawn from a shuffled bag avoiding back-to-back
 *   repeats.
 * @param mode Gameplay input mode ([XrLanguageTeacherVocabGameMode.SPEAK] vs
 *   [XrLanguageTeacherVocabGameMode.TAP]).
 * @param initialHighScore Persisted best score, used for
 *   [XrLanguageTeacherVocabRunnerState.highScore].
 * @param randomSeed Seed of the xorshift64* generator; `0` selects a fixed non-zero seed.
 */
class XrLanguageTeacherVocabRunnerEngine(
  val deck: List<XrLanguageTeacherVocabRunnerDeckItem>,
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.SPEAK,
  initialHighScore: Int = 0,
  randomSeed: Long = System.nanoTime(),
) {
  /** Current game snapshot. A new immutable instance is published on every mutation. */
  var state: XrLanguageTeacherVocabRunnerState =
    XrLanguageTeacherVocabRunnerState(highScore = initialHighScore)
    private set

  private val random =
    XrLanguageTeacherGameRandom(if (randomSeed == 0L) DEFAULT_SEED else randomSeed)
  private var bag: MutableList<XrLanguageTeacherVocabRunnerDeckItem> = mutableListOf()
  private var nextObstacleId = 1L
  private var stumbleTimerSeconds = 0f
  private var knockbackVelocity = 0f
  private var choicesObstacleId: Long? = null
  private val transcriptCursor = XrLanguageTeacherTranscriptCursor()
  private val scoreTracker = XrLanguageTeacherVocabGameScoreTracker()

  init {
    state = state.copy(obstacles = withUpcomingObstacles(state.obstacles, correctCount = 0))
    if (mode == XrLanguageTeacherVocabGameMode.TAP) {
      refreshChoicesIfNeeded()
    }
  }

  /** Returns the nearest upcoming obstacle that is still in Buddy's path, if any. */
  val currentTargetObstacle: XrLanguageTeacherVocabRunnerObstacle?
    get() = currentTargetObstacleIndex?.let { state.obstacles[it] }

  private val currentTargetObstacleIndex: Int?
    get() =
      state.obstacles
        .indexOfFirst { !it.isClearing && it.x >= BUDDY_X - COLLISION_HALF_WIDTH }
        .takeIf { it >= 0 }

  private val tapDifficultyProgress: Float
    get() {
      val elapsedMs = max(0L, ROUND_DURATION_MILLISECONDS - state.remainingMilliseconds)
      val elapsedFraction = elapsedMs.toFloat() / ROUND_DURATION_MILLISECONDS.toFloat()
      val streakFraction = min(1f, state.correctCount.toFloat() / 18f)
      return min(1f, elapsedFraction * 0.45f + streakFraction * 0.70f)
    }

  private val currentTapScrollSpeed: Float
    get() =
      INITIAL_SCROLL_SPEED + tapDifficultyProgress * (MAX_TAP_SCROLL_SPEED - INITIAL_SCROLL_SPEED)

  /** Advances the runner simulation by [deltaMilliseconds] (clamped to 100 ms per call). */
  fun step(deltaMilliseconds: Long): XrLanguageTeacherVocabRunnerStepEvents {
    if (state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING || deltaMilliseconds <= 0) {
      return XrLanguageTeacherVocabRunnerStepEvents()
    }
    val clampedMs = min(deltaMilliseconds, MAX_STEP_MS)
    val dt = clampedMs / 1_000f

    val remainingMilliseconds = max(0L, state.remainingMilliseconds - clampedMs)
    if (remainingMilliseconds <= 0L) {
      finishRound(
        reason = XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
        remainingMilliseconds = 0L,
      )
      return XrLanguageTeacherVocabRunnerStepEvents(didFinishRound = true)
    }

    val scrollSpeed =
      if (mode == XrLanguageTeacherVocabGameMode.TAP) currentTapScrollSpeed else state.scrollSpeed

    var isStumbled = state.isStumbled
    if (stumbleTimerSeconds > 0f) {
      stumbleTimerSeconds = max(0f, stumbleTimerSeconds - dt)
      isStumbled = stumbleTimerSeconds > 0f
    }
    if (knockbackVelocity > 0f) {
      knockbackVelocity = max(0f, knockbackVelocity - KNOCKBACK_DECAY_PER_SECOND * dt)
    }
    var buddyRecoilOffset = -MAX_BUDDY_RECOIL_OFFSET * (knockbackVelocity / INITIAL_KNOCKBACK_SPEED)

    // A knockback scrolls the whole track (and every obstacle) back, keeping their spacing.
    val effectiveSpeed = scrollSpeed - knockbackVelocity
    val trackDistance = max(0f, state.trackDistance + effectiveSpeed * dt)

    var lives = state.lives
    var didCollide = false
    var missedWord: String? = null
    var didTriggerKnockback = false
    var didTriggerTapCollision = false
    val remaining = ArrayList<XrLanguageTeacherVocabRunnerObstacle>(state.obstacles.size)
    for (original in state.obstacles) {
      var obstacle = original.copy(x = original.x - effectiveSpeed * dt)

      val progress = obstacle.clearProgress
      if (progress != null) {
        val nextProgress = progress + dt / CLEAR_ANIMATION_SECONDS
        if (nextProgress < 1f && obstacle.x >= DESPAWN_X) {
          remaining += obstacle.copy(clearProgress = nextProgress)
        }
        continue
      }

      when (mode) {
        XrLanguageTeacherVocabGameMode.SPEAK -> {
          if (
            knockbackVelocity <= 0f &&
              !didTriggerKnockback &&
              obstacle.x <= BUDDY_X + COLLISION_HALF_WIDTH
          ) {
            val bumpCount = obstacle.bumpCount + 1
            stumbleTimerSeconds = STUMBLE_DURATION_SECONDS
            isStumbled = true
            didCollide = true
            consumePendingTranscript()
            obstacle =
              if (bumpCount >= MAX_BUMPS_PER_OBSTACLE) {
                missedWord = obstacle.item.displayWord
                obstacle.copy(bumpCount = bumpCount, didBreakOnMaxBumps = true, clearProgress = 0f)
              } else {
                didTriggerKnockback = true
                obstacle.copy(bumpCount = bumpCount)
              }
          }
        }
        XrLanguageTeacherVocabGameMode.TAP -> {
          if (!didTriggerTapCollision && obstacle.x <= BUDDY_X + COLLISION_HALF_WIDTH) {
            didTriggerTapCollision = true
            lives = max(0, lives - 1)
            stumbleTimerSeconds = STUMBLE_DURATION_SECONDS
            isStumbled = true
            didCollide = true
            missedWord = obstacle.item.displayWord
            obstacle =
              obstacle.copy(
                bumpCount = obstacle.bumpCount + 1,
                didBreakOnMaxBumps = true,
                clearProgress = 0f,
              )
          }
        }
      }

      if (obstacle.x >= DESPAWN_X) {
        remaining += obstacle
      }
    }

    if (mode == XrLanguageTeacherVocabGameMode.TAP) {
      if (didTriggerTapCollision) {
        scoreTracker.resetStreak()
      }
      state =
        state.copy(
          remainingMilliseconds = remainingMilliseconds,
          lives = lives,
          streak = if (didTriggerTapCollision) 0 else state.streak,
          streakMultiplier = if (didTriggerTapCollision) 1 else state.streakMultiplier,
          scrollSpeed = scrollSpeed,
          trackDistance = trackDistance,
          buddyRecoilOffset = buddyRecoilOffset,
          isStumbled = isStumbled,
          obstacles = remaining,
        )
      if (lives == 0) {
        finishRound(reason = XrLanguageTeacherVocabGameEndReason.OUT_OF_LIVES)
        return XrLanguageTeacherVocabRunnerStepEvents(
          didCollide = didCollide,
          missedWord = missedWord,
          didFinishRound = true,
        )
      }
      if (didTriggerTapCollision) {
        knockbackVelocity = INITIAL_KNOCKBACK_SPEED * 0.45f
        state = state.copy(buddyRecoilOffset = -MAX_BUDDY_RECOIL_OFFSET * 0.45f)
        pullInNextTapObstacleIfOffscreen()
      }
      state = state.copy(obstacles = withUpcomingObstacles(state.obstacles, state.correctCount))
      refreshChoicesIfNeeded()
      return XrLanguageTeacherVocabRunnerStepEvents(
        didCollide = didCollide,
        missedWord = missedWord,
      )
    }

    if (didTriggerKnockback) {
      knockbackVelocity = INITIAL_KNOCKBACK_SPEED
      buddyRecoilOffset = -MAX_BUDDY_RECOIL_OFFSET
    }
    if (didCollide) {
      scoreTracker.resetStreak()
    }

    state =
      state.copy(
        remainingMilliseconds = remainingMilliseconds,
        streak = if (didCollide) 0 else state.streak,
        streakMultiplier = if (didCollide) 1 else state.streakMultiplier,
        trackDistance = trackDistance,
        buddyRecoilOffset = buddyRecoilOffset,
        isStumbled = isStumbled,
        obstacles = withUpcomingObstacles(remaining, state.correctCount),
      )
    return XrLanguageTeacherVocabRunnerStepEvents(didCollide = didCollide, missedWord = missedWord)
  }

  /**
   * Evaluates a multiple-choice button tap against the nearest active upcoming obstacle in `Tap`
   * mode, clearing the obstacle and advancing choices on a match or deducting one life and
   * shattering the obstacle on a wrong choice.
   */
  fun selectChoice(entryKey: String): XrLanguageTeacherVocabRunnerChoiceOutcome? {
    if (
      mode != XrLanguageTeacherVocabGameMode.TAP ||
        state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING
    ) {
      return null
    }
    val targetIndex = currentTargetObstacleIndex ?: return null
    val selectedItem = state.choices.firstOrNull { it.entryKey == entryKey } ?: return null
    val correctItem = state.obstacles[targetIndex].item
    val isCorrect =
      selectedItem.entryKey == correctItem.entryKey ||
        selectedItem.targetMatchForms.intersect(correctItem.targetMatchForms).isNotEmpty()

    if (isCorrect) {
      val obstacles = state.obstacles.toMutableList()
      obstacles[targetIndex] = obstacles[targetIndex].copy(clearProgress = 0f)
      val award =
        scoreTracker.recordCorrectAnswer(
          displayWord = correctItem.displayWord,
          entryKey = correctItem.entryKey,
        )
      val score = award.totalScore
      val isNewHighScore = state.isNewHighScore || score > state.highScore
      state =
        state.copy(
          score = score,
          highScore = max(state.highScore, score),
          isNewHighScore = isNewHighScore,
          correctCount = award.correctCount,
          uniqueWordCount = award.uniqueWordCount,
          streak = award.streak,
          bestStreak = award.bestStreak,
          streakMultiplier = award.multiplier,
          obstacles = obstacles,
        )
      state = state.copy(scrollSpeed = currentTapScrollSpeed)
      pullInNextTapObstacleIfOffscreen()
      state = state.copy(obstacles = withUpcomingObstacles(state.obstacles, state.correctCount))
      refreshChoicesIfNeeded()
      return XrLanguageTeacherVocabRunnerChoiceOutcome(
        selectedItem = selectedItem,
        correctItem = correctItem,
        isCorrect = true,
        pointsAwarded = award.pointsAwarded,
        multiplier = award.multiplier,
        streak = award.streak,
        newScore = state.score,
        isNewHighScore = state.isNewHighScore,
        remainingLives = state.lives,
        didFinishRound = false,
      )
    }

    scoreTracker.resetStreak()
    val lives = max(0, state.lives - 1)
    stumbleTimerSeconds = STUMBLE_DURATION_SECONDS
    val obstacles = state.obstacles.toMutableList()
    obstacles[targetIndex] =
      obstacles[targetIndex].copy(didBreakOnMaxBumps = true, clearProgress = 0f)
    state =
      state.copy(
        lives = lives,
        streak = 0,
        streakMultiplier = 1,
        isStumbled = true,
        obstacles = obstacles,
      )

    if (lives == 0) {
      finishRound(reason = XrLanguageTeacherVocabGameEndReason.OUT_OF_LIVES)
      return XrLanguageTeacherVocabRunnerChoiceOutcome(
        selectedItem = selectedItem,
        correctItem = correctItem,
        isCorrect = false,
        pointsAwarded = 0,
        multiplier = 1,
        streak = 0,
        newScore = state.score,
        isNewHighScore = state.isNewHighScore,
        remainingLives = 0,
        didFinishRound = true,
      )
    }

    pullInNextTapObstacleIfOffscreen()
    state = state.copy(obstacles = withUpcomingObstacles(state.obstacles, state.correctCount))
    refreshChoicesIfNeeded()
    return XrLanguageTeacherVocabRunnerChoiceOutcome(
      selectedItem = selectedItem,
      correctItem = correctItem,
      isCorrect = false,
      pointsAwarded = 0,
      multiplier = 1,
      streak = 0,
      newScore = state.score,
      isNewHighScore = state.isNewHighScore,
      remainingLives = state.lives,
      didFinishRound = false,
    )
  }

  /**
   * Resets per-segment transcript consumption watermarks when the speech recognizer starts a new
   * utterance segment.
   */
  fun resetTranscript() {
    transcriptCursor.reset()
  }

  /**
   * Marks any unconsumed tokens in the current segment as consumed after a speaking pause so stale
   * unmatched words cannot clear future obstacles.
   */
  fun consumePendingTranscript() {
    transcriptCursor.consumePending()
  }

  /**
   * Evaluates the cumulative ASR [transcript] against the nearest visible upcoming obstacle still
   * in Buddy's path (with `BUDDY_X - COLLISION_HALF_WIDTH <= x <= 1.08`): a match awards composite
   * points (`+30` first clear, `+10` repeat, multiplied by the active streak tier), starts its pop
   * and fade, ramps the scroll speed up, and consumes the matched transcript tokens. Returns `null`
   * when nothing matched.
   */
  fun processTranscript(transcript: String): XrLanguageTeacherVocabRunnerClearEvent? {
    if (
      mode != XrLanguageTeacherVocabGameMode.SPEAK ||
        state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING
    ) {
      return null
    }
    val unconsumedText = transcriptCursor.takeUnconsumedText(transcript)
    if (unconsumedText.isEmpty()) return null

    val targetIndex =
      state.obstacles.indexOfFirst {
        !it.isClearing && it.x >= BUDDY_X - COLLISION_HALF_WIDTH && it.x <= MAX_MATCHABLE_OBSTACLE_X
      }
    if (targetIndex < 0) return null

    val obstacle = state.obstacles[targetIndex]
    val transcriptForms = XrLanguageTeacherSpeechMatcher.speechMatchForms(unconsumedText)
    if (
      !XrLanguageTeacherSpeechMatcher.findTargetWordSpoken(
        transcriptForms = transcriptForms,
        targetForms = obstacle.item.targetMatchForms,
      )
    ) {
      return null
    }

    transcriptCursor.consumeSeen()
    val obstacles = state.obstacles.toMutableList()
    obstacles[targetIndex] = obstacle.copy(clearProgress = 0f)
    val award =
      scoreTracker.recordCorrectAnswer(
        displayWord = obstacle.item.displayWord,
        entryKey = obstacle.item.entryKey,
      )
    val correctCount = award.correctCount
    val score = award.totalScore
    val isNewHighScore = state.isNewHighScore || score > state.highScore
    state =
      state.copy(
        score = score,
        highScore = max(state.highScore, score),
        isNewHighScore = isNewHighScore,
        correctCount = correctCount,
        uniqueWordCount = award.uniqueWordCount,
        streak = award.streak,
        bestStreak = award.bestStreak,
        streakMultiplier = award.multiplier,
        scrollSpeed =
          min(MAX_SCROLL_SPEED, INITIAL_SCROLL_SPEED + correctCount * SPEED_PER_OBSTACLE_CLEARED),
        obstacles = obstacles,
      )
    return XrLanguageTeacherVocabRunnerClearEvent(
      matchedWord = obstacle.item.displayWord,
      pointsAwarded = award.pointsAwarded,
      multiplier = award.multiplier,
      streak = award.streak,
      newScore = score,
      isNewHighScore = isNewHighScore,
    )
  }

  private fun finishRound(
    reason: XrLanguageTeacherVocabGameEndReason,
    remainingMilliseconds: Long = state.remainingMilliseconds,
  ) {
    stumbleTimerSeconds = 0f
    knockbackVelocity = 0f
    choicesObstacleId = null
    state =
      state.copy(
        phase = XrLanguageTeacherVocabRunnerPhase.FINISHED,
        endReason = reason,
        remainingMilliseconds = remainingMilliseconds,
        obstacles = emptyList(),
        choices = emptyList(),
        isStumbled = false,
        buddyRecoilOffset = 0f,
      )
  }

  /**
   * If the next active obstacle is still completely off-screen to the right after a fast choice or
   * break, pulls off-screen non-clearing obstacles smoothly to the right edge so the new target
   * sticker immediately enters the track without dead air or moving any already-visible obstacle.
   */
  private fun pullInNextTapObstacleIfOffscreen() {
    val nextIndex = currentTargetObstacleIndex ?: return
    val nextX = state.obstacles[nextIndex].x
    val maxClearingX = state.obstacles.filter { it.isClearing }.maxOfOrNull { it.x } ?: -1f
    val targetPullX = max(OFFSCREEN_PULL_IN_TARGET_X, maxClearingX + 0.34f)
    if (nextX <= max(OFFSCREEN_PULL_IN_THRESHOLD_X, targetPullX)) return
    val shift = nextX - targetPullX
    state =
      state.copy(
        obstacles =
          state.obstacles.map { obstacle ->
            if (!obstacle.isClearing) obstacle.copy(x = obstacle.x - shift) else obstacle
          }
      )
  }

  private fun refreshChoicesIfNeeded() {
    if (
      mode != XrLanguageTeacherVocabGameMode.TAP ||
        state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING
    ) {
      choicesObstacleId = null
      if (state.choices.isNotEmpty()) {
        state = state.copy(choices = emptyList())
      }
      return
    }
    val targetObstacle = currentTargetObstacle
    if (targetObstacle == null) {
      choicesObstacleId = null
      if (state.choices.isNotEmpty()) {
        state = state.copy(choices = emptyList())
      }
      return
    }
    if (choicesObstacleId == targetObstacle.id) return
    choicesObstacleId = targetObstacle.id

    val target = targetObstacle.item
    val shuffledCandidates = random.shuffled(deck)
    val distractors = ArrayList<XrLanguageTeacherVocabRunnerDeckItem>(CHOICE_BUTTON_COUNT - 1)
    val usedForms = target.targetMatchForms.toMutableSet()
    val usedDisplayWords =
      mutableSetOf(XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(target.displayWord))
    val neededDistractors = CHOICE_BUTTON_COUNT - 1

    for (candidate in shuffledCandidates) {
      if (distractors.size >= neededDistractors) break
      if (candidate.entryKey == target.entryKey) continue
      val normalizedDisplay =
        XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(candidate.displayWord)
      if (normalizedDisplay.isNotEmpty() && normalizedDisplay in usedDisplayWords) continue
      if (candidate.targetMatchForms.intersect(usedForms).isNotEmpty()) continue
      distractors += candidate
      usedForms += candidate.targetMatchForms
      if (normalizedDisplay.isNotEmpty()) usedDisplayWords += normalizedDisplay
    }

    if (distractors.size < neededDistractors) {
      for (candidate in shuffledCandidates) {
        if (distractors.size >= neededDistractors) break
        if (candidate.entryKey == target.entryKey) continue
        if (distractors.any { it.entryKey == candidate.entryKey }) continue
        if (candidate.targetMatchForms.intersect(usedForms).isNotEmpty()) continue
        distractors += candidate
        usedForms += candidate.targetMatchForms
      }
    }

    val options = random.shuffled(listOf(target) + distractors)
    state = state.copy(choices = options)
  }

  /**
   * Returns [obstacles] topped up with freshly drawn obstacles up to the off-screen spawn horizon,
   * spaced more tightly as [correctCount] (or Tap difficulty progress) grows.
   */
  private fun withUpcomingObstacles(
    obstacles: List<XrLanguageTeacherVocabRunnerObstacle>,
    correctCount: Int,
  ): List<XrLanguageTeacherVocabRunnerObstacle> {
    if (deck.isEmpty()) return obstacles
    val minSpacing: Float
    val maxSpacing: Float
    when (mode) {
      XrLanguageTeacherVocabGameMode.SPEAK -> {
        val spacingReduction = correctCount * SPACING_REDUCTION_PER_CLEAR
        minSpacing = max(FLOOR_MIN_SPACING, INITIAL_MIN_SPACING - spacingReduction)
        maxSpacing = max(FLOOR_MAX_SPACING, INITIAL_MAX_SPACING - spacingReduction)
      }
      XrLanguageTeacherVocabGameMode.TAP -> {
        val progress = tapDifficultyProgress
        minSpacing =
          TAP_INITIAL_MIN_SPACING - progress * (TAP_INITIAL_MIN_SPACING - TAP_FLOOR_MIN_SPACING)
        maxSpacing =
          TAP_INITIAL_MAX_SPACING - progress * (TAP_INITIAL_MAX_SPACING - TAP_FLOOR_MAX_SPACING)
      }
    }
    val initialStartX =
      if (mode == XrLanguageTeacherVocabGameMode.TAP) TAP_FIRST_OBSTACLE_START_X
      else FIRST_OBSTACLE_START_X
    val result = obstacles.toMutableList()
    while (result.size < MAX_ACTIVE_OBSTACLES) {
      val lastX = result.lastOrNull()?.x
      if (lastX != null && lastX >= SPAWN_HORIZON_X) break
      val nextX =
        if (lastX != null) lastX + random.nextFloat(minSpacing, maxSpacing) else initialStartX
      val item = drawNextDeckItem(recentWord = result.lastOrNull()?.item?.word) ?: break
      result += XrLanguageTeacherVocabRunnerObstacle(id = nextObstacleId++, item = item, x = nextX)
    }
    return result
  }

  private fun drawNextDeckItem(recentWord: String?): XrLanguageTeacherVocabRunnerDeckItem? {
    if (deck.isEmpty()) return null
    if (bag.isEmpty()) {
      bag = random.shuffled(deck)
    }
    if (deck.size > 1 && recentWord != null) {
      val nonRepeatIndex = bag.indexOfFirst { it.word != recentWord }
      if (nonRepeatIndex >= 0) return bag.removeAt(nonRepeatIndex)
    }
    return bag.removeAt(0)
  }

  companion object {
    /** Minimum number of saved vocabulary words with sticker images required to play. */
    const val MINIMUM_STICKER_VOCABULARY_COUNT = 3

    /** Minimum number of saved vocabulary words with sticker images required for `Tap` mode. */
    const val MINIMUM_TAP_STICKER_VOCABULARY_COUNT = 4

    /** Number of lives in `Tap` mode. */
    const val INITIAL_LIVES = 3

    /** Number of multiple-choice buttons displayed in `Tap` mode. */
    const val CHOICE_BUTTON_COUNT = 4

    /** Length of a round. */
    const val ROUND_DURATION_MILLISECONDS = 60_000L

    /** Bumps after which an obstacle breaks for no points. */
    const val MAX_BUMPS_PER_OBSTACLE = 3

    /** Buddy's fixed horizontal position in normalized track units. */
    const val BUDDY_X = 0.20f

    /** Initial obstacle scroll speed in normalized track units per second. */
    const val INITIAL_SCROLL_SPEED = 0.27f

    /** Maximum obstacle scroll speed in normalized track units per second (`Speak` mode). */
    const val MAX_SCROLL_SPEED = 0.48f

    /** Maximum obstacle scroll speed in normalized track units per second (`Tap` mode). */
    const val MAX_TAP_SCROLL_SPEED = 0.52f

    /** Scroll speed added per cleared obstacle (`Speak` mode). */
    const val SPEED_PER_OBSTACLE_CLEARED = 0.012f

    /** Half-width of the horizontal hit window around [BUDDY_X]. */
    const val COLLISION_HALF_WIDTH = 0.055f

    /** Smallest gap between consecutive obstacles at the start of a round (`Speak` mode). */
    const val INITIAL_MIN_SPACING = 0.64f

    /** Largest gap between consecutive obstacles at the start of a round (`Speak` mode). */
    const val INITIAL_MAX_SPACING = 0.84f

    /** Smallest gap between consecutive obstacles once spacing stops tightening (`Speak` mode). */
    const val FLOOR_MIN_SPACING = 0.36f

    /** Largest gap between consecutive obstacles once spacing stops tightening (`Speak` mode). */
    const val FLOOR_MAX_SPACING = 0.48f

    /** Smallest gap between consecutive obstacles at the start of a round (`Tap` mode). */
    const val TAP_INITIAL_MIN_SPACING = 0.68f

    /** Largest gap between consecutive obstacles at the start of a round (`Tap` mode). */
    const val TAP_INITIAL_MAX_SPACING = 0.86f

    /** Smallest gap between consecutive obstacles once spacing stops tightening (`Tap` mode). */
    const val TAP_FLOOR_MIN_SPACING = 0.34f

    /** Largest gap between consecutive obstacles once spacing stops tightening (`Tap` mode). */
    const val TAP_FLOOR_MAX_SPACING = 0.46f

    /** How much both gaps tighten per cleared obstacle (`Speak` mode). */
    const val SPACING_REDUCTION_PER_CLEAR = 0.016f

    /** New obstacles spawn until the last one is at least this far right, off screen. */
    const val SPAWN_HORIZON_X = 1.25f

    private const val DEFAULT_SEED = 0x2E4AB5CD2E6D12FDL // -0xD1B54A32D192ED03 as a signed Long.
    private const val MAX_STEP_MS = 100L
    private const val STUMBLE_DURATION_SECONDS = 0.45f
    private const val MAX_ACTIVE_OBSTACLES = 8
    private const val MAX_MATCHABLE_OBSTACLE_X = 1.08f
    private const val FIRST_OBSTACLE_START_X = 1.05f
    private const val TAP_FIRST_OBSTACLE_START_X = 0.86f
    private const val OFFSCREEN_PULL_IN_THRESHOLD_X = 1.14f
    private const val OFFSCREEN_PULL_IN_TARGET_X = 0.98f
    private const val DESPAWN_X = -0.18f
    private const val CLEAR_ANIMATION_SECONDS = 0.22f
    private const val INITIAL_KNOCKBACK_SPEED = 1.95f
    private const val KNOCKBACK_DECAY_PER_SECOND = 5.2f
    private const val MAX_BUDDY_RECOIL_OFFSET = 0.035f
  }
}
