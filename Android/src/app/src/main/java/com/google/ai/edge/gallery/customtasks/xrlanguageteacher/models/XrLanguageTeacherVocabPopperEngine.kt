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

import androidx.annotation.VisibleForTesting
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Round duration options for `Vocab Popper`. */
enum class XrLanguageTeacherVocabPopperDuration(val seconds: Int) {
  SECONDS_30(30),
  SECONDS_60(60),
  SECONDS_90(90);

  /** Round duration in milliseconds. */
  val durationMilliseconds: Long
    get() = seconds * 1_000L
}

/**
 * Difficulty options for `Vocab Popper`.
 *
 * @property maxSimultaneousTargets Maximum number of stickers flying at the same time.
 * @property spawnIntervalMilliseconds Minimum delay between two sticker launches.
 * @property gravityScale Multiplier applied to the base gravity, so harder rounds fall faster.
 */
enum class XrLanguageTeacherVocabPopperDifficulty(
  val maxSimultaneousTargets: Int,
  val spawnIntervalMilliseconds: Long,
  val gravityScale: Float,
) {
  EASY(maxSimultaneousTargets = 2, spawnIntervalMilliseconds = 2_000L, gravityScale = 0.72f),
  NORMAL(maxSimultaneousTargets = 3, spawnIntervalMilliseconds = 1_550L, gravityScale = 0.88f),
  HARD(maxSimultaneousTargets = 4, spawnIntervalMilliseconds = 1_150L, gravityScale = 1.08f),
}

/** Input and gameplay mode shared by XR Language Teacher vocabulary study games. */
enum class XrLanguageTeacherVocabGameMode {
  /** Non-ASR mode: listen or read and tap the matching vocabulary sticker or choice. */
  TAP,
  /** ASR mode: speak the vocabulary word into the microphone. */
  SPEAK,
}

/** Reason a vocabulary study game round ended. */
enum class XrLanguageTeacherVocabGameEndReason {
  /** The countdown timer reached zero. */
  TIME_EXPIRED,
  /** The player lost all three lives in Tap mode. */
  OUT_OF_LIVES,
}

/**
 * Lightweight vocabulary item passed into [XrLanguageTeacherVocabPopperEngine].
 *
 * @property entryKey Stable key of the saved vocabulary entry (used to look up its sticker image).
 * @property word Raw saved target-language word, e.g. `"林檎 (りんご)"`.
 * @property displayWord Word shown on screen; defaults to the primary learn term of [word].
 * @property meaning Native-language meaning of [word].
 */
data class XrLanguageTeacherVocabPopperDeckItem(
  val entryKey: String,
  val word: String,
  val meaning: String,
  val displayWord: String = XrLanguageTeacherSpeechMatcher.extractPrimaryLearnTerm(word),
) {
  /** Precomputed normalized match forms of [word]. */
  val targetMatchForms: Set<String> = XrLanguageTeacherSpeechMatcher.targetMatchForms(word)
}

/**
 * A flying vocabulary sticker in normalized `[0, 1]` arena coordinates (`y` grows downward).
 *
 * @property popProgress `null` while flying, or the `[0, 1)` progress of the pop animation.
 * @property dismissProgress `null` while active, or the `[0, 1)` progress of the fade-out animation
 *   when a wave sticker is dismissed.
 */
data class XrLanguageTeacherVocabPopperTarget(
  val id: Long,
  val item: XrLanguageTeacherVocabPopperDeckItem,
  val x: Float,
  val y: Float,
  val vx: Float,
  val vy: Float,
  val gravity: Float,
  val rotationDegrees: Float,
  val angularVelocityDegrees: Float,
  val colorIndex: Int,
  val popProgress: Float? = null,
  val dismissProgress: Float? = null,
) {
  /** Whether this target was popped and is playing its pop animation. */
  val isPopping: Boolean
    get() = popProgress != null

  /** Whether this target is fading out after the wave was resolved. */
  val isDismissing: Boolean
    get() = dismissProgress != null

  /** Whether this target is still flying and can be popped or tapped. */
  val isInteractive: Boolean
    get() = !isPopping && !isDismissing
}

/** Radial burst particle spawned when a target is popped. */
data class XrLanguageTeacherVocabPopperParticle(
  val id: Long,
  val x: Float,
  val y: Float,
  val vx: Float,
  val vy: Float,
  /** Particle radius in density-independent pixels. */
  val radiusDp: Float,
  val colorIndex: Int,
  val lifeProgress: Float,
)

/** Floating `+<points> <word>` banner spawned at a popped target's position. */
data class XrLanguageTeacherVocabPopperPopBanner(
  val id: Long,
  val word: String,
  val pointsAwarded: Int =
    XrLanguageTeacherVocabGameScoreTracker.POINTS_PER_CORRECT_ANSWER +
      XrLanguageTeacherVocabGameScoreTracker.UNIQUE_WORD_BONUS_POINTS,
  val multiplier: Int = 1,
  val x: Float,
  val y: Float,
  val progress: Float,
)

/** Lifecycle phase of a `Vocab Popper` round. */
enum class XrLanguageTeacherVocabPopperPhase {
  PLAYING,
  FINISHED,
}

/** Snapshot of the `Vocab Popper` arena state. */
data class XrLanguageTeacherVocabPopperState(
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.SPEAK,
  val phase: XrLanguageTeacherVocabPopperPhase = XrLanguageTeacherVocabPopperPhase.PLAYING,
  val endReason: XrLanguageTeacherVocabGameEndReason =
    XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
  val remainingMilliseconds: Long = 60_000L,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val lives: Int = XrLanguageTeacherVocabPopperEngine.INITIAL_LIVES,
  val maxLives: Int = XrLanguageTeacherVocabPopperEngine.INITIAL_LIVES,
  val correctCount: Int = 0,
  val uniqueWordCount: Int = 0,
  val streak: Int = 0,
  val bestStreak: Int = 0,
  val streakMultiplier: Int = 1,
  val currentTarget: XrLanguageTeacherVocabPopperDeckItem? = null,
  val targets: List<XrLanguageTeacherVocabPopperTarget> = emptyList(),
  val particles: List<XrLanguageTeacherVocabPopperParticle> = emptyList(),
  val popBanners: List<XrLanguageTeacherVocabPopperPopBanner> = emptyList(),
  val timeSinceLastSpawnMs: Long = 0L,
)

/** Frame boundary events emitted by [XrLanguageTeacherVocabPopperEngine.step]. */
data class XrLanguageTeacherVocabPopperStepEvents(
  val didStartNewWave: Boolean = false,
  val didFinishRound: Boolean = false,
)

/** Outcome returned when the player taps a flying sticker in `Tap` mode. */
data class XrLanguageTeacherVocabPopperTapOutcome(
  val isCorrect: Boolean,
  val tappedItem: XrLanguageTeacherVocabPopperDeckItem,
  val pointsAwarded: Int,
  val multiplier: Int,
  val streak: Int,
  val newScore: Int,
  val remainingLives: Int,
  val isNewHighScore: Boolean,
  val didStartNewWave: Boolean,
  val didFinishRound: Boolean,
)

/** Result returned when one or more flying targets are popped by spoken vocabulary. */
data class XrLanguageTeacherVocabPopperPopEvent(
  /** Display words of the popped targets, in target order. */
  val poppedWords: List<String>,
  val pointsAwarded: Int =
    XrLanguageTeacherVocabGameScoreTracker.POINTS_PER_CORRECT_ANSWER +
      XrLanguageTeacherVocabGameScoreTracker.UNIQUE_WORD_BONUS_POINTS,
  val multiplier: Int = 1,
  val streak: Int = 1,
  val newScore: Int,
  val isNewHighScore: Boolean,
)

/**
 * Pure, deterministic physics and spawning engine for `Vocab Popper`.
 *
 * Stickers launch from below the arena on parabolic arcs, bounce elastically off the side walls and
 * off each other, and pop (with radial burst particles and a floating `+1` banner) when the player
 * taps the prompted sticker ([XrLanguageTeacherVocabGameMode.TAP]) or speaks their word
 * ([XrLanguageTeacherVocabGameMode.SPEAK]). Not thread-safe: drive [step], [tap], and
 * [processTranscript] from a single thread (normally the main thread) and publish [state] snapshots
 * to the UI.
 *
 * @param deck Vocabulary items to launch, drawn from a shuffled bag without repeating active words.
 * @param mode Whether the player taps the prompted sticker or speaks any flying word.
 * @param duration Round length.
 * @param difficulty Spawn cadence, simultaneous target cap, and gravity scale.
 * @param initialHighScore Persisted best score, used for
 *   [XrLanguageTeacherVocabPopperState.highScore].
 * @param randomSeed Seed of the xorshift64* generator; `0` selects a fixed non-zero seed.
 */
class XrLanguageTeacherVocabPopperEngine(
  val deck: List<XrLanguageTeacherVocabPopperDeckItem>,
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.SPEAK,
  val duration: XrLanguageTeacherVocabPopperDuration =
    XrLanguageTeacherVocabPopperDuration.SECONDS_60,
  val difficulty: XrLanguageTeacherVocabPopperDifficulty =
    XrLanguageTeacherVocabPopperDifficulty.NORMAL,
  initialHighScore: Int = 0,
  randomSeed: Long = System.nanoTime(),
) {
  /** Current arena snapshot. A new immutable instance is published on every mutation. */
  var state: XrLanguageTeacherVocabPopperState =
    XrLanguageTeacherVocabPopperState(
      mode = mode,
      phase = XrLanguageTeacherVocabPopperPhase.PLAYING,
      endReason = XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
      remainingMilliseconds = duration.durationMilliseconds,
      score = 0,
      highScore = initialHighScore,
      isNewHighScore = false,
      lives = INITIAL_LIVES,
      maxLives = INITIAL_LIVES,
      correctCount = 0,
      uniqueWordCount = 0,
      streak = 0,
      bestStreak = 0,
      streakMultiplier = 1,
      currentTarget = null,
      timeSinceLastSpawnMs = difficulty.spawnIntervalMilliseconds,
    )
    private set

  private val random =
    XrLanguageTeacherGameRandom(if (randomSeed == 0L) DEFAULT_SEED else randomSeed)
  private var bag: MutableList<XrLanguageTeacherVocabPopperDeckItem> = mutableListOf()
  private var nextEntityId = 1L
  private val transcriptCursor = XrLanguageTeacherTranscriptCursor()
  private var lastWaveTargetEntryKey: String? = null
  private var waveCooldownRemainingMs: Long = 0L
  private var isCurrentTapWaveResolved = false
  private val scoreTracker = XrLanguageTeacherVocabGameScoreTracker()

  init {
    if (mode == XrLanguageTeacherVocabGameMode.TAP && deck.isNotEmpty()) {
      spawnNextWave()
    }
  }

  /**
   * Continuous speed and difficulty progression factor in `[0, 1]` driven by elapsed round
   * fraction, total correct clears, and current consecutive clears.
   */
  val speedProgress: Float
    get() {
      val totalDurationMs = max(1L, duration.durationMilliseconds)
      val elapsedMs = max(0L, totalDurationMs - state.remainingMilliseconds)
      val elapsedFraction = elapsedMs.toFloat() / totalDurationMs.toFloat()
      val correctComponent = min(1.0f, state.correctCount.toFloat() / 14.0f)
      val streakComponent = min(1.0f, state.streak.toFloat() / 8.0f)
      return (0.50f * elapsedFraction + 0.55f * correctComponent + 0.20f * streakComponent)
        .coerceIn(0f, 1f)
    }

  /**
   * Current gravity scale in `Tap` mode (`0.85` up to `2.45`), cutting sticker hang time roughly in
   * half as [speedProgress] advances.
   */
  val currentTapGravityScale: Float
    get() = 0.85f + speedProgress * (2.45f - 0.85f)

  /** Current pause between cleared waves in `Tap` mode (`300ms` down to `40ms`). */
  val currentInterWaveDelayMilliseconds: Long
    get() = (300.0 - speedProgress.toDouble() * 260.0).roundToLong()

  /** Current gravity scale in `Speak` mode (`1.0x` up to `2.4x` `difficulty.gravityScale`). */
  val currentSpeakGravityScale: Float
    get() = difficulty.gravityScale * (1.0f + 1.4f * speedProgress)

  /**
   * Current spawn interval in `Speak` mode, shrinking down to `42%` of the base interval (floored
   * at `500ms`) as [speedProgress] advances.
   */
  val currentSpeakSpawnIntervalMilliseconds: Long
    get() =
      max(
        500L,
        (difficulty.spawnIntervalMilliseconds.toFloat() * (1.0f - 0.58f * speedProgress))
          .roundToLong(),
      )

  /**
   * Current maximum simultaneous flying targets in `Speak` mode (`+1` at `speedProgress >= 0.35`,
   * `+2` at `speedProgress >= 0.70`).
   */
  val currentSpeakMaxSimultaneousTargets: Int
    get() {
      val bonus =
        when {
          speedProgress >= 0.70f -> 2
          speedProgress >= 0.35f -> 1
          else -> 0
        }
      return difficulty.maxSimultaneousTargets + bonus
    }

  /** Advances the game simulation by [deltaMilliseconds] (clamped to 100 ms per call). */
  fun step(deltaMilliseconds: Long): XrLanguageTeacherVocabPopperStepEvents {
    if (state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING || deltaMilliseconds <= 0) {
      return XrLanguageTeacherVocabPopperStepEvents()
    }
    val clampedMs = min(deltaMilliseconds, MAX_STEP_MS)
    val dt = clampedMs / 1_000f

    val remaining = max(0L, state.remainingMilliseconds - clampedMs)
    if (remaining <= 0L) {
      state =
        state.copy(
          remainingMilliseconds = 0L,
          phase = XrLanguageTeacherVocabPopperPhase.FINISHED,
          endReason = XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
          targets = emptyList(),
        )
      return XrLanguageTeacherVocabPopperStepEvents(didStartNewWave = false, didFinishRound = true)
    }

    // 1. Update flying targets, popping animations, and dismissed wave distractors.
    val minX = SPRITE_RADIUS_X
    val maxX = 1f - SPRITE_RADIUS_X
    var didDespawnInteractiveTarget = false
    val updatedTargets = ArrayList<XrLanguageTeacherVocabPopperTarget>(state.targets.size)
    for (target in state.targets) {
      val popProgress = target.popProgress
      val dismissProgress = target.dismissProgress
      if (popProgress != null) {
        val nextProgress = popProgress + dt / POP_ANIMATION_DURATION_SECONDS
        if (nextProgress < 1f) {
          updatedTargets += target.copy(popProgress = nextProgress)
        }
      } else if (dismissProgress != null) {
        val nextProgress = dismissProgress + dt / DISMISS_ANIMATION_DURATION_SECONDS
        if (nextProgress < 1f) {
          updatedTargets +=
            target.copy(
              x = target.x + target.vx * dt,
              y = target.y + max(target.vy, 0.35f) * dt,
              dismissProgress = nextProgress,
            )
        }
      } else {
        var x = target.x + target.vx * dt
        val y = target.y + target.vy * dt
        val vy = target.vy + target.gravity * dt
        var vx = target.vx
        when {
          x < minX -> {
            x = minX
            if (vx < 0f) vx = -vx * COLLISION_RESTITUTION
          }
          x > maxX -> {
            x = maxX
            if (vx > 0f) vx = -vx * COLLISION_RESTITUTION
          }
        }
        if (y > DESPAWN_Y && vy > 0f) {
          didDespawnInteractiveTarget = true
        } else {
          updatedTargets +=
            target.copy(
              x = x,
              y = y,
              vx = vx,
              vy = vy,
              rotationDegrees = target.rotationDegrees + target.angularVelocityDegrees * dt,
            )
        }
      }
    }
    resolveTargetCollisions(updatedTargets)

    // 2. Update burst particles.
    val updatedParticles =
      state.particles.mapNotNull { particle ->
        val lifeProgress = particle.lifeProgress + dt / PARTICLE_LIFETIME_SECONDS
        if (lifeProgress < 1f) {
          particle.copy(
            x = particle.x + particle.vx * dt,
            y = particle.y + particle.vy * dt,
            vy = particle.vy + PARTICLE_GRAVITY * dt,
            lifeProgress = lifeProgress,
          )
        } else {
          null
        }
      }

    // 3. Update floating +1 banners.
    val updatedBanners =
      state.popBanners.mapNotNull { banner ->
        val progress = banner.progress + dt / BANNER_LIFETIME_SECONDS
        if (progress < 1f) {
          banner.copy(y = banner.y - BANNER_RISE_SPEED * dt, progress = progress)
        } else {
          null
        }
      }

    state =
      state.copy(
        remainingMilliseconds = remaining,
        targets = updatedTargets,
        particles = updatedParticles,
        popBanners = updatedBanners,
      )

    // 4. Spawn new targets according to the active gameplay mode.
    var didStartNewWave = false
    when (mode) {
      XrLanguageTeacherVocabGameMode.SPEAK -> {
        if (didDespawnInteractiveTarget) {
          scoreTracker.resetStreak()
          state = state.copy(streak = 0, streakMultiplier = 1)
        }
        var timeSinceLastSpawnMs = state.timeSinceLastSpawnMs + clampedMs
        val activeFlyingCount = state.targets.count { it.isInteractive }
        if (
          deck.isNotEmpty() &&
            activeFlyingCount < currentSpeakMaxSimultaneousTargets &&
            (timeSinceLastSpawnMs >= currentSpeakSpawnIntervalMilliseconds ||
              activeFlyingCount == 0)
        ) {
          timeSinceLastSpawnMs = 0L
          val targets = state.targets.toMutableList()
          spawnTarget(targets)?.let { targets += it }
          resolveTargetCollisions(targets)
          state = state.copy(targets = targets, timeSinceLastSpawnMs = timeSinceLastSpawnMs)
        } else {
          state = state.copy(timeSinceLastSpawnMs = timeSinceLastSpawnMs)
        }
      }
      XrLanguageTeacherVocabGameMode.TAP -> {
        val activeInteractiveCount = state.targets.count { it.isInteractive }
        if (activeInteractiveCount == 0) {
          if (!isCurrentTapWaveResolved && state.currentTarget != null) {
            scoreTracker.resetStreak()
            state = state.copy(streak = 0, streakMultiplier = 1)
            isCurrentTapWaveResolved = true
          }
          if (deck.isNotEmpty()) {
            if (waveCooldownRemainingMs > 0L) {
              waveCooldownRemainingMs = max(0L, waveCooldownRemainingMs - clampedMs)
            }
            if (waveCooldownRemainingMs <= 0L) {
              spawnNextWave()
              didStartNewWave = state.currentTarget != null
            }
          }
        }
      }
    }

    return XrLanguageTeacherVocabPopperStepEvents(
      didStartNewWave = didStartNewWave,
      didFinishRound = false,
    )
  }

  /**
   * Evaluates a touch at `([normalizedX], [normalizedY])` against active wave stickers in `Tap`
   * mode.
   *
   * Checks targets in reverse draw order so overlapping front-most stickers win, and returns `null`
   * with zero penalty when tapping empty background space.
   */
  fun tap(
    normalizedX: Float,
    normalizedY: Float,
    arenaAspectRatio: Float = VERTICAL_TO_HORIZONTAL_SCALE,
  ): XrLanguageTeacherVocabPopperTapOutcome? {
    val currentTarget = state.currentTarget
    if (
      mode != XrLanguageTeacherVocabGameMode.TAP ||
        state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING ||
        isCurrentTapWaveResolved ||
        currentTarget == null
    ) {
      return null
    }
    val scaleY = max(0.5f, arenaAspectRatio)
    val hitRadiusSquared = TAP_HIT_RADIUS_X * TAP_HIT_RADIUS_X

    var hitIndex: Int? = null
    for (index in state.targets.indices.reversed()) {
      val target = state.targets[index]
      if (!target.isInteractive) continue
      val dx = normalizedX - target.x
      val dy = (normalizedY - target.y) * scaleY
      if (dx * dx + dy * dy <= hitRadiusSquared) {
        hitIndex = index
        break
      }
    }
    val tappedIndex = hitIndex ?: return null

    val tappedTarget = state.targets[tappedIndex]
    val isCorrect =
      tappedTarget.item.entryKey == currentTarget.entryKey ||
        tappedTarget.item.targetMatchForms.intersect(currentTarget.targetMatchForms).isNotEmpty()

    isCurrentTapWaveResolved = true
    if (isCorrect) {
      val updatedTargets =
        state.targets.mapIndexed { idx, target ->
          when {
            idx == tappedIndex -> target.copy(popProgress = 0f)
            target.isInteractive -> target.copy(dismissProgress = 0f)
            else -> target
          }
        }
      val award =
        scoreTracker.recordCorrectAnswer(
          displayWord = tappedTarget.item.displayWord,
          entryKey = tappedTarget.item.entryKey,
        )
      val newScore = award.totalScore
      val newHighScore = max(state.highScore, newScore)
      val isNewHighScore = newScore > state.highScore || state.isNewHighScore
      val particles = state.particles.toMutableList()
      val banners = state.popBanners.toMutableList()
      spawnPopEffects(
        target = tappedTarget,
        pointsAwarded = award.pointsAwarded,
        multiplier = award.multiplier,
        banners = banners,
        particles = particles,
      )
      state =
        state.copy(
          score = newScore,
          highScore = newHighScore,
          isNewHighScore = isNewHighScore,
          correctCount = award.correctCount,
          uniqueWordCount = award.uniqueWordCount,
          streak = award.streak,
          bestStreak = award.bestStreak,
          streakMultiplier = award.multiplier,
          targets = updatedTargets,
          particles = particles,
          popBanners = banners,
        )
      waveCooldownRemainingMs = currentInterWaveDelayMilliseconds
      return XrLanguageTeacherVocabPopperTapOutcome(
        isCorrect = true,
        tappedItem = tappedTarget.item,
        pointsAwarded = award.pointsAwarded,
        multiplier = award.multiplier,
        streak = award.streak,
        newScore = state.score,
        remainingLives = state.lives,
        isNewHighScore = state.isNewHighScore,
        didStartNewWave = false,
        didFinishRound = false,
      )
    } else {
      scoreTracker.resetStreak()
      val nextLives = max(0, state.lives - 1)
      if (nextLives <= 0) {
        state =
          state.copy(
            phase = XrLanguageTeacherVocabPopperPhase.FINISHED,
            endReason = XrLanguageTeacherVocabGameEndReason.OUT_OF_LIVES,
            lives = 0,
            streak = 0,
            streakMultiplier = 1,
            targets = emptyList(),
          )
        return XrLanguageTeacherVocabPopperTapOutcome(
          isCorrect = false,
          tappedItem = tappedTarget.item,
          pointsAwarded = 0,
          multiplier = 1,
          streak = 0,
          newScore = state.score,
          remainingLives = 0,
          isNewHighScore = state.isNewHighScore,
          didStartNewWave = false,
          didFinishRound = true,
        )
      }
      val updatedTargets =
        state.targets.map { target ->
          if (target.isInteractive) target.copy(dismissProgress = 0f) else target
        }
      state =
        state.copy(lives = nextLives, streak = 0, streakMultiplier = 1, targets = updatedTargets)
      waveCooldownRemainingMs = currentInterWaveDelayMilliseconds
      return XrLanguageTeacherVocabPopperTapOutcome(
        isCorrect = false,
        tappedItem = tappedTarget.item,
        pointsAwarded = 0,
        multiplier = 1,
        streak = 0,
        newScore = state.score,
        remainingLives = state.lives,
        isNewHighScore = state.isNewHighScore,
        didStartNewWave = false,
        didFinishRound = false,
      )
    }
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
   * unmatched words cannot pop future targets.
   */
  fun consumePendingTranscript() {
    transcriptCursor.consumeSeen()
  }

  /**
   * Evaluates the cumulative ASR [transcript] against all currently flying (non-popping) targets
   * and pops every matched target, consuming the matched transcript tokens so they cannot pop later
   * targets. Returns `null` when nothing was popped or when the round is in
   * [XrLanguageTeacherVocabGameMode.TAP] mode.
   */
  fun processTranscript(transcript: String): XrLanguageTeacherVocabPopperPopEvent? {
    if (
      mode != XrLanguageTeacherVocabGameMode.SPEAK ||
        state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING
    ) {
      return null
    }
    val unconsumedText = transcriptCursor.takeUnconsumedText(transcript)
    if (unconsumedText.isEmpty()) return null
    if (state.targets.none { it.isInteractive }) return null

    val transcriptForms = XrLanguageTeacherSpeechMatcher.speechMatchForms(unconsumedText)
    if (transcriptForms.isEmpty()) return null

    var score = state.score
    var highScore = state.highScore
    var isNewHighScore = state.isNewHighScore
    var correctCount = state.correctCount
    var uniqueWordCount = state.uniqueWordCount
    var streak = state.streak
    var bestStreak = state.bestStreak
    var streakMultiplier = state.streakMultiplier
    var totalPointsAwarded = 0
    val poppedDisplayWords = mutableListOf<String>()
    val particles = state.particles.toMutableList()
    val banners = state.popBanners.toMutableList()
    val targets =
      state.targets.map { target ->
        if (
          target.isInteractive &&
            XrLanguageTeacherSpeechMatcher.findTargetWordSpoken(
              transcriptForms = transcriptForms,
              targetForms = target.item.targetMatchForms,
            )
        ) {
          val award =
            scoreTracker.recordCorrectAnswer(
              displayWord = target.item.displayWord,
              entryKey = target.item.entryKey,
            )
          correctCount = award.correctCount
          uniqueWordCount = award.uniqueWordCount
          streak = award.streak
          bestStreak = award.bestStreak
          streakMultiplier = award.multiplier
          score = award.totalScore
          totalPointsAwarded += award.pointsAwarded
          if (score > highScore) {
            highScore = score
            isNewHighScore = true
          }
          poppedDisplayWords += target.item.displayWord
          spawnPopEffects(
            target = target,
            pointsAwarded = award.pointsAwarded,
            multiplier = award.multiplier,
            banners = banners,
            particles = particles,
          )
          target.copy(popProgress = 0f)
        } else {
          target
        }
      }

    if (poppedDisplayWords.isEmpty()) return null
    transcriptCursor.consumeSeen()
    state =
      state.copy(
        score = score,
        highScore = highScore,
        isNewHighScore = isNewHighScore,
        correctCount = correctCount,
        uniqueWordCount = uniqueWordCount,
        streak = streak,
        bestStreak = bestStreak,
        streakMultiplier = streakMultiplier,
        targets = targets,
        particles = particles,
        popBanners = banners,
      )
    return XrLanguageTeacherVocabPopperPopEvent(
      poppedWords = poppedDisplayWords,
      pointsAwarded = totalPointsAwarded,
      multiplier = streakMultiplier,
      streak = streak,
      newScore = score,
      isNewHighScore = isNewHighScore,
    )
  }

  /**
   * Replaces the active target list for deterministic unit testing of collision and hit-testing
   * physics.
   */
  @VisibleForTesting
  fun setTargetsForTesting(
    targets: List<XrLanguageTeacherVocabPopperTarget>,
    currentTarget: XrLanguageTeacherVocabPopperDeckItem? = null,
  ) {
    state = state.copy(targets = targets, currentTarget = currentTarget ?: state.currentTarget)
    if (currentTarget != null) {
      isCurrentTapWaveResolved = false
    }
    val maxId = targets.maxOfOrNull { it.id }
    if (maxId != null && maxId >= nextEntityId) {
      nextEntityId = maxId + 1
    }
  }

  private fun spawnNextWave() {
    val targetItem = drawNextWaveTargetItem() ?: return
    isCurrentTapWaveResolved = false
    lastWaveTargetEntryKey = targetItem.entryKey

    val targetNormalizedDisplay =
      XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(targetItem.displayWord)
    val seenDisplayKeys = mutableSetOf(targetNormalizedDisplay)
    val eligibleDistractors = mutableListOf<XrLanguageTeacherVocabPopperDeckItem>()
    for (candidate in random.shuffled(deck)) {
      if (candidate.entryKey == targetItem.entryKey) continue
      if (candidate.targetMatchForms.intersect(targetItem.targetMatchForms).isNotEmpty()) {
        continue
      }
      val normalizedDisplay =
        XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(candidate.displayWord)
      val dedupeKey = normalizedDisplay.ifEmpty { candidate.entryKey }
      if (!seenDisplayKeys.add(dedupeKey)) continue
      eligibleDistractors.add(candidate)
      if (eligibleDistractors.size >= MAX_WAVE_STICKERS - 1) {
        break
      }
    }

    val waveItems = random.shuffled(listOf(targetItem) + eligibleDistractors)
    val count = waveItems.size
    val progress = speedProgress
    val gravity = BASE_GRAVITY * currentTapGravityScale
    val staggerStepY = 0.025f - progress * (0.025f - 0.008f)
    val maxSpin = 24.0f + progress * (58.0f - 24.0f)
    val maxCrossLaneOffset = 0.08f + progress * (0.24f - 0.08f)
    val minApexY = 0.28f - progress * (0.28f - 0.22f)
    val maxApexY = 0.46f - progress * (0.46f - 0.38f)
    val staggeredOrder = random.shuffled((0 until count).toList())

    val targets = state.targets.toMutableList()
    for ((slotIndex, item) in waveItems.withIndex()) {
      val slotFraction = if (count > 1) slotIndex.toFloat() / (count - 1).toFloat() else 0.5f
      val baseSlotX = 0.16f + slotFraction * 0.68f
      val startX = (baseSlotX + random.nextFloat(-0.025f, 0.025f)).coerceIn(0.15f, 0.85f)
      val apexX =
        (baseSlotX + random.nextFloat(-maxCrossLaneOffset, maxCrossLaneOffset)).coerceIn(
          0.14f,
          0.86f,
        )
      val apexY = random.nextFloat(minApexY, maxApexY)
      val staggerRank = staggeredOrder[slotIndex]
      val startY = LAUNCH_Y + staggerRank.toFloat() * staggerStepY
      val vy = -sqrt(max(0.05f, 2.0f * gravity * (startY - apexY)))
      val timeToApex = max(0.22f, -vy / gravity)
      val vx = (apexX - startX) / timeToApex
      val initialRotation = random.nextFloat(-12.0f, 12.0f)
      val angularVelocity = random.nextFloat(-maxSpin, maxSpin)
      val colorIndex = (random.nextInt(PALETTE_COLOR_COUNT) + slotIndex) % PALETTE_COLOR_COUNT

      targets +=
        XrLanguageTeacherVocabPopperTarget(
          id = allocateId(),
          item = item,
          x = startX,
          y = startY,
          vx = vx,
          vy = vy,
          gravity = gravity,
          rotationDegrees = initialRotation,
          angularVelocityDegrees = angularVelocity,
          colorIndex = colorIndex,
        )
    }
    resolveTargetCollisions(targets)
    state = state.copy(currentTarget = targetItem, targets = targets)
  }

  private fun drawNextWaveTargetItem(): XrLanguageTeacherVocabPopperDeckItem? {
    if (deck.isEmpty()) return null
    if (bag.isEmpty()) {
      bag = random.shuffled(deck)
    }
    val previousEntryKey = lastWaveTargetEntryKey
    if (previousEntryKey != null && deck.size > 1) {
      val nonRepeatIndex = bag.indexOfFirst { it.entryKey != previousEntryKey }
      if (nonRepeatIndex >= 0) {
        return bag.removeAt(nonRepeatIndex)
      }
    }
    return bag.removeAt(0)
  }

  /**
   * Separates overlapping flying targets and bounces them off each other like equal-mass bodies.
   * Vertical deltas are scaled by [VERTICAL_TO_HORIZONTAL_SCALE] so the collision bounds are round
   * on a portrait screen.
   */
  private fun resolveTargetCollisions(targets: MutableList<XrLanguageTeacherVocabPopperTarget>) {
    val minDistance = 2f * SPRITE_RADIUS_X
    val minDistanceSquared = minDistance * minDistance
    val minX = SPRITE_RADIUS_X
    val maxX = 1f - SPRITE_RADIUS_X
    val scaleY = VERTICAL_TO_HORIZONTAL_SCALE

    for (pass in 0 until 2) {
      for (i in targets.indices) {
        if (!targets[i].isInteractive) continue
        for (j in (i + 1) until targets.size) {
          if (!targets[j].isInteractive) continue
          var a = targets[i]
          var b = targets[j]

          val dx = b.x - a.x
          val dy = (b.y - a.y) * scaleY
          val distanceSquared = dx * dx + dy * dy
          if (distanceSquared >= minDistanceSquared) continue

          val distance = sqrt(distanceSquared)
          val nx = if (distance > 0.0001f) dx / distance else 1f
          val ny = if (distance > 0.0001f) dy / distance else 0f
          val push = (minDistance - distance) * 0.5f

          a = a.copy(x = (a.x - nx * push).coerceIn(minX, maxX), y = a.y - (ny * push) / scaleY)
          b = b.copy(x = (b.x + nx * push).coerceIn(minX, maxX), y = b.y + (ny * push) / scaleY)

          if (pass == 0) {
            val rvx = b.vx - a.vx
            val rvy = (b.vy - a.vy) * scaleY
            val approachSpeed = rvx * nx + rvy * ny
            if (approachSpeed < 0f) {
              val impulse = -(1f + COLLISION_RESTITUTION) * approachSpeed * 0.5f
              a = a.copy(vx = a.vx - impulse * nx, vy = a.vy - (impulse * ny) / scaleY)
              b = b.copy(vx = b.vx + impulse * nx, vy = b.vy + (impulse * ny) / scaleY)
            }
          }
          targets[i] = a
          targets[j] = b
        }
      }
    }
  }

  private fun spawnTarget(
    currentTargets: List<XrLanguageTeacherVocabPopperTarget>
  ): XrLanguageTeacherVocabPopperTarget? {
    val item = drawNextDeckItem(currentTargets) ?: return null
    val id = allocateId()
    val progress = speedProgress
    val startX = pickLaunchX(currentTargets)
    val apexX = random.nextFloat(0.24f, 0.76f)
    val apexY = random.nextFloat(0.18f, 0.36f)
    val gravity = BASE_GRAVITY * currentSpeakGravityScale
    val vy = -sqrt(max(0.05f, 2f * gravity * (LAUNCH_Y - apexY)))
    val timeToApex = max(0.22f, -vy / gravity)
    val vx = (apexX - startX) / timeToApex
    val maxSpin = 28.0f + progress * (58.0f - 28.0f)
    val initialRotation = random.nextFloat(-14f, 14f)
    val angularVelocity = random.nextFloat(-maxSpin, maxSpin)
    val colorIndex = random.nextInt(PALETTE_COLOR_COUNT)

    return XrLanguageTeacherVocabPopperTarget(
      id = id,
      item = item,
      x = startX,
      y = LAUNCH_Y,
      vx = vx,
      vy = vy,
      gravity = gravity,
      rotationDegrees = initialRotation,
      angularVelocityDegrees = angularVelocity,
      colorIndex = colorIndex,
    )
  }

  /**
   * Picks a horizontal launch coordinate that avoids overlapping any target near the launch zone.
   */
  private fun pickLaunchX(currentTargets: List<XrLanguageTeacherVocabPopperTarget>): Float {
    val minDistance = 2f * SPRITE_RADIUS_X
    val nearLaunchTargets = currentTargets.filter { it.isInteractive && it.y > NEAR_LAUNCH_ZONE_Y }
    if (nearLaunchTargets.isEmpty()) {
      return random.nextFloat(MIN_LAUNCH_X, MAX_LAUNCH_X)
    }

    var bestCandidate = 0.5f
    var bestClearance = -1f
    repeat(LAUNCH_X_CANDIDATES) {
      val candidate = random.nextFloat(MIN_LAUNCH_X, MAX_LAUNCH_X)
      val clearance = nearLaunchTargets.minOf { abs(it.x - candidate) }
      if (clearance >= minDistance) {
        return candidate
      }
      if (clearance > bestClearance) {
        bestClearance = clearance
        bestCandidate = candidate
      }
    }
    return bestCandidate
  }

  private fun spawnPopEffects(
    target: XrLanguageTeacherVocabPopperTarget,
    pointsAwarded: Int,
    multiplier: Int,
    banners: MutableList<XrLanguageTeacherVocabPopperPopBanner>,
    particles: MutableList<XrLanguageTeacherVocabPopperParticle>,
  ) {
    banners +=
      XrLanguageTeacherVocabPopperPopBanner(
        id = allocateId(),
        word = target.item.displayWord,
        pointsAwarded = pointsAwarded,
        multiplier = multiplier,
        x = target.x,
        y = target.y,
        progress = 0f,
      )

    for (i in 0 until PARTICLES_PER_POP) {
      val baseAngle = (2f * PI.toFloat() * i) / PARTICLES_PER_POP
      val jitter = random.nextFloat(-0.18f, 0.18f)
      val angle = baseAngle + jitter
      val speed = random.nextFloat(0.22f, 0.58f)
      particles +=
        XrLanguageTeacherVocabPopperParticle(
          id = allocateId(),
          x = target.x,
          y = target.y,
          vx = cos(angle) * speed,
          vy = sin(angle) * speed - 0.12f,
          radiusDp = random.nextFloat(4.5f, 9.5f),
          colorIndex = (target.colorIndex + i) % PALETTE_COLOR_COUNT,
          lifeProgress = 0f,
        )
    }
  }

  private fun drawNextDeckItem(
    currentTargets: List<XrLanguageTeacherVocabPopperTarget>
  ): XrLanguageTeacherVocabPopperDeckItem? {
    if (deck.isEmpty()) return null
    val activeWords = currentTargets.filter { it.isInteractive }.map { it.item.word }.toSet()
    if (bag.isEmpty()) {
      bag = random.shuffled(deck)
    }
    val nonDuplicateIndex = bag.indexOfFirst { it.word !in activeWords }
    return if (nonDuplicateIndex >= 0) bag.removeAt(nonDuplicateIndex) else bag.removeAt(0)
  }

  private fun allocateId(): Long = nextEntityId++

  companion object {
    /** Minimum number of saved vocabulary words required to play Speak mode. */
    const val MINIMUM_VOCABULARY_COUNT = 3

    /** Minimum number of saved vocabulary words required to play Tap mode. */
    const val MINIMUM_TAP_VOCABULARY_COUNT = 4

    /** Number of lives in a Tap-mode round. */
    const val INITIAL_LIVES = 3

    /**
     * Maximum number of stickers launched per wave in Tap mode (1 target + up to 4 distractors).
     */
    const val MAX_WAVE_STICKERS = 5

    /**
     * Normalized horizontal collision radius of a flying sticker (`118x118dp` in a portrait arena).
     * Used only for separation and bounce physics; the sticker cutout shape itself is never
     * altered.
     */
    const val SPRITE_RADIUS_X = 0.145f

    /**
     * Generous normalized horizontal touch radius (`~61dp` radius on a `393dp`-wide arena) for
     * tapping moving stickers.
     */
    const val TAP_HIT_RADIUS_X = 0.155f

    /**
     * Aspect ratio scale converting normalized vertical deltas into horizontal-equivalent
     * normalized units so sprite collision bounds are isotropic on screen.
     */
    const val VERTICAL_TO_HORIZONTAL_SCALE = 1.8f

    /** Elastic coefficient of restitution for wall and sprite-to-sprite bounces. */
    const val COLLISION_RESTITUTION = 0.80f

    /**
     * Number of distinct palette colors referenced by
     * [XrLanguageTeacherVocabPopperTarget.colorIndex].
     */
    const val PALETTE_COLOR_COUNT = 6

    private const val DEFAULT_SEED = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val MAX_STEP_MS = 100L
    private const val BASE_GRAVITY = 0.42f
    private const val LAUNCH_Y = 1.06f
    private const val DESPAWN_Y = 1.18f
    private const val NEAR_LAUNCH_ZONE_Y = 0.78f
    private const val MIN_LAUNCH_X = 0.16f
    private const val MAX_LAUNCH_X = 0.84f
    private const val LAUNCH_X_CANDIDATES = 6
    private const val POP_ANIMATION_DURATION_SECONDS = 0.24f
    private const val DISMISS_ANIMATION_DURATION_SECONDS = 0.20f
    private const val PARTICLE_LIFETIME_SECONDS = 0.48f
    private const val PARTICLE_GRAVITY = 0.55f
    private const val BANNER_LIFETIME_SECONDS = 0.85f
    private const val BANNER_RISE_SPEED = 0.12f
    private const val PARTICLES_PER_POP = 14
  }
}
