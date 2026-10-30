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
 * @property trackDistance Total scrolled distance in normalized track units, used to animate the
 *   track dashes and Buddy's stride.
 * @property buddyRecoilOffset Buddy's horizontal recoil (negative, in normalized track units) while
 *   the track knocks back after a bump.
 * @property isStumbled Whether Buddy is briefly stumbling after a collision.
 */
data class XrLanguageTeacherVocabRunnerState(
  val phase: XrLanguageTeacherVocabRunnerPhase = XrLanguageTeacherVocabRunnerPhase.PLAYING,
  val remainingMilliseconds: Long = XrLanguageTeacherVocabRunnerEngine.ROUND_DURATION_MILLISECONDS,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val scrollSpeed: Float = XrLanguageTeacherVocabRunnerEngine.INITIAL_SCROLL_SPEED,
  val trackDistance: Float = 0f,
  val buddyRecoilOffset: Float = 0f,
  val isStumbled: Boolean = false,
  val obstacles: List<XrLanguageTeacherVocabRunnerObstacle> = emptyList(),
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
  val newScore: Int,
  val isNewHighScore: Boolean,
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
 * Sticker obstacles scroll toward Buddy; speaking the nearest obstacle's word pops it out of
 * Buddy's path (`+1`) and speeds the track up. Bumping into an obstacle knocks the whole track back
 * so the player can try the word again; the third bump breaks the obstacle for no points. The round
 * ends after [ROUND_DURATION_MILLISECONDS]. Not thread-safe: drive [step] and [processTranscript]
 * from a single thread (normally the main thread) and publish [state] snapshots to the UI.
 *
 * @param deck Vocabulary items to use as obstacles, drawn from a shuffled bag avoiding back-to-back
 *   repeats.
 * @param initialHighScore Persisted best score, used for
 *   [XrLanguageTeacherVocabRunnerState.highScore].
 * @param randomSeed Seed of the xorshift64* generator; `0` selects a fixed non-zero seed.
 */
class XrLanguageTeacherVocabRunnerEngine(
  val deck: List<XrLanguageTeacherVocabRunnerDeckItem>,
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
  private val transcriptCursor = XrLanguageTeacherTranscriptCursor()

  init {
    state = state.copy(obstacles = withUpcomingObstacles(state.obstacles, score = 0))
  }

  /** Returns the nearest upcoming obstacle that is still in Buddy's path, if any. */
  val currentTargetObstacle: XrLanguageTeacherVocabRunnerObstacle?
    get() = state.obstacles.firstOrNull { !it.isClearing && it.x >= BUDDY_X - COLLISION_HALF_WIDTH }

  /** Advances the runner simulation by [deltaMilliseconds] (clamped to 100 ms per call). */
  fun step(deltaMilliseconds: Long): XrLanguageTeacherVocabRunnerStepEvents {
    if (state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING || deltaMilliseconds <= 0) {
      return XrLanguageTeacherVocabRunnerStepEvents()
    }
    val clampedMs = min(deltaMilliseconds, MAX_STEP_MS)
    val dt = clampedMs / 1_000f

    val remainingMilliseconds = max(0L, state.remainingMilliseconds - clampedMs)
    if (remainingMilliseconds <= 0L) {
      stumbleTimerSeconds = 0f
      knockbackVelocity = 0f
      state =
        state.copy(
          phase = XrLanguageTeacherVocabRunnerPhase.FINISHED,
          remainingMilliseconds = 0L,
          obstacles = emptyList(),
          isStumbled = false,
          buddyRecoilOffset = 0f,
        )
      return XrLanguageTeacherVocabRunnerStepEvents(didFinishRound = true)
    }

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
    val effectiveSpeed = state.scrollSpeed - knockbackVelocity
    val trackDistance = max(0f, state.trackDistance + effectiveSpeed * dt)

    var didCollide = false
    var missedWord: String? = null
    var didTriggerKnockback = false
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

      if (obstacle.x >= DESPAWN_X) {
        remaining += obstacle
      }
    }

    if (didTriggerKnockback) {
      knockbackVelocity = INITIAL_KNOCKBACK_SPEED
      buddyRecoilOffset = -MAX_BUDDY_RECOIL_OFFSET
    }

    state =
      state.copy(
        remainingMilliseconds = remainingMilliseconds,
        trackDistance = trackDistance,
        buddyRecoilOffset = buddyRecoilOffset,
        isStumbled = isStumbled,
        obstacles = withUpcomingObstacles(remaining, state.score),
      )
    return XrLanguageTeacherVocabRunnerStepEvents(didCollide = didCollide, missedWord = missedWord)
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
   * in Buddy's path (with `BUDDY_X - COLLISION_HALF_WIDTH <= x <= 1.08`): a match awards `+1`,
   * starts its pop and fade, ramps the scroll speed up, and consumes the matched transcript tokens.
   * Returns `null` when nothing matched.
   */
  fun processTranscript(transcript: String): XrLanguageTeacherVocabRunnerClearEvent? {
    if (state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING) return null
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
    val score = state.score + 1
    val isNewHighScore = state.isNewHighScore || score > state.highScore
    state =
      state.copy(
        score = score,
        highScore = max(state.highScore, score),
        isNewHighScore = isNewHighScore,
        scrollSpeed =
          min(MAX_SCROLL_SPEED, INITIAL_SCROLL_SPEED + score * SPEED_PER_OBSTACLE_CLEARED),
        obstacles = obstacles,
      )
    return XrLanguageTeacherVocabRunnerClearEvent(
      matchedWord = obstacle.item.displayWord,
      newScore = score,
      isNewHighScore = isNewHighScore,
    )
  }

  /**
   * Returns [obstacles] topped up with freshly drawn obstacles up to the off-screen spawn horizon,
   * spaced more tightly as [score] grows.
   */
  private fun withUpcomingObstacles(
    obstacles: List<XrLanguageTeacherVocabRunnerObstacle>,
    score: Int,
  ): List<XrLanguageTeacherVocabRunnerObstacle> {
    if (deck.isEmpty()) return obstacles
    val spacingReduction = score * SPACING_REDUCTION_PER_CLEAR
    val minSpacing = max(FLOOR_MIN_SPACING, INITIAL_MIN_SPACING - spacingReduction)
    val maxSpacing = max(FLOOR_MAX_SPACING, INITIAL_MAX_SPACING - spacingReduction)
    val result = obstacles.toMutableList()
    while (result.size < MAX_ACTIVE_OBSTACLES) {
      val lastX = result.lastOrNull()?.x
      if (lastX != null && lastX >= SPAWN_HORIZON_X) break
      val nextX =
        if (lastX != null) lastX + random.nextFloat(minSpacing, maxSpacing)
        else FIRST_OBSTACLE_START_X
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

    /** Length of a round. */
    const val ROUND_DURATION_MILLISECONDS = 60_000L

    /** Bumps after which an obstacle breaks for no points. */
    const val MAX_BUMPS_PER_OBSTACLE = 3

    /** Buddy's fixed horizontal position in normalized track units. */
    const val BUDDY_X = 0.20f

    /** Initial obstacle scroll speed in normalized track units per second. */
    const val INITIAL_SCROLL_SPEED = 0.27f

    /** Maximum obstacle scroll speed in normalized track units per second. */
    const val MAX_SCROLL_SPEED = 0.48f

    /** Scroll speed added per cleared obstacle. */
    const val SPEED_PER_OBSTACLE_CLEARED = 0.012f

    /** Half-width of the horizontal hit window around [BUDDY_X]. */
    const val COLLISION_HALF_WIDTH = 0.055f

    /** Smallest gap between consecutive obstacles at the start of a round. */
    const val INITIAL_MIN_SPACING = 0.64f

    /** Largest gap between consecutive obstacles at the start of a round. */
    const val INITIAL_MAX_SPACING = 0.84f

    /** Smallest gap between consecutive obstacles once the spacing stops tightening. */
    const val FLOOR_MIN_SPACING = 0.36f

    /** Largest gap between consecutive obstacles once the spacing stops tightening. */
    const val FLOOR_MAX_SPACING = 0.48f

    /** How much both gaps tighten per cleared obstacle. */
    const val SPACING_REDUCTION_PER_CLEAR = 0.016f

    /** New obstacles spawn until the last one is at least this far right, off screen. */
    const val SPAWN_HORIZON_X = 1.25f

    private const val DEFAULT_SEED = 0x2E4AB5CD2E6D12FDL // -0xD1B54A32D192ED03 as a signed Long.
    private const val MAX_STEP_MS = 100L
    private const val STUMBLE_DURATION_SECONDS = 0.45f
    private const val MAX_ACTIVE_OBSTACLES = 8
    private const val MAX_MATCHABLE_OBSTACLE_X = 1.08f
    private const val FIRST_OBSTACLE_START_X = 1.05f
    private const val DESPAWN_X = -0.18f
    private const val CLEAR_ANIMATION_SECONDS = 0.22f
    private const val INITIAL_KNOCKBACK_SPEED = 1.95f
    private const val KNOCKBACK_DECAY_PER_SECOND = 5.2f
    private const val MAX_BUDDY_RECOIL_OFFSET = 0.035f
  }
}
