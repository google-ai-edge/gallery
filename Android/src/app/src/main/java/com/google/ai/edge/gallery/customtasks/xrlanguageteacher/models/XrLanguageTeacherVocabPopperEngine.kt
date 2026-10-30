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
) {
  /** Whether this target was popped and is playing its pop animation. */
  val isPopping: Boolean
    get() = popProgress != null
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

/** Floating `+1 <word>` banner spawned at a popped target's position. */
data class XrLanguageTeacherVocabPopperPopBanner(
  val id: Long,
  val word: String,
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
  val phase: XrLanguageTeacherVocabPopperPhase = XrLanguageTeacherVocabPopperPhase.PLAYING,
  val remainingMilliseconds: Long = 60_000L,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val targets: List<XrLanguageTeacherVocabPopperTarget> = emptyList(),
  val particles: List<XrLanguageTeacherVocabPopperParticle> = emptyList(),
  val popBanners: List<XrLanguageTeacherVocabPopperPopBanner> = emptyList(),
  val timeSinceLastSpawnMs: Long = 0L,
)

/** Result returned when one or more flying targets are popped by spoken vocabulary. */
data class XrLanguageTeacherVocabPopperPopEvent(
  /** Display words of the popped targets, in target order. */
  val poppedWords: List<String>,
  val newScore: Int,
  val isNewHighScore: Boolean,
)

/**
 * Pure, deterministic physics and spawning engine for `Vocab Popper`.
 *
 * Stickers launch from below the arena on parabolic arcs, bounce elastically off the side walls and
 * off each other, and pop (with radial burst particles and a floating `+1` banner) when the player
 * speaks their word. Not thread-safe: drive [step] and [processTranscript] from a single thread
 * (normally the main thread) and publish [state] snapshots to the UI.
 *
 * @param deck Vocabulary items to launch, drawn from a shuffled bag without repeating active words.
 * @param duration Round length.
 * @param difficulty Spawn cadence, simultaneous target cap, and gravity scale.
 * @param initialHighScore Persisted best score, used for
 *   [XrLanguageTeacherVocabPopperState.highScore].
 * @param randomSeed Seed of the xorshift64* generator; `0` selects a fixed non-zero seed.
 */
class XrLanguageTeacherVocabPopperEngine(
  val deck: List<XrLanguageTeacherVocabPopperDeckItem>,
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
      phase = XrLanguageTeacherVocabPopperPhase.PLAYING,
      remainingMilliseconds = duration.durationMilliseconds,
      score = 0,
      highScore = initialHighScore,
      isNewHighScore = false,
      timeSinceLastSpawnMs = difficulty.spawnIntervalMilliseconds,
    )
    private set

  private val random =
    XrLanguageTeacherGameRandom(if (randomSeed == 0L) DEFAULT_SEED else randomSeed)
  private var bag: MutableList<XrLanguageTeacherVocabPopperDeckItem> = mutableListOf()
  private var nextEntityId = 1L
  private val transcriptCursor = XrLanguageTeacherTranscriptCursor()

  /** Advances the game simulation by [deltaMilliseconds] (clamped to 100 ms per call). */
  fun step(deltaMilliseconds: Long) {
    if (state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING || deltaMilliseconds <= 0) return
    val clampedMs = min(deltaMilliseconds, MAX_STEP_MS)
    val dt = clampedMs / 1_000f

    val remaining = max(0L, state.remainingMilliseconds - clampedMs)
    if (remaining <= 0L) {
      state =
        state.copy(
          remainingMilliseconds = 0L,
          phase = XrLanguageTeacherVocabPopperPhase.FINISHED,
          targets = emptyList(),
        )
      return
    }

    // 1. Update flying targets and popping animations.
    val minX = SPRITE_RADIUS_X
    val maxX = 1f - SPRITE_RADIUS_X
    val updatedTargets = ArrayList<XrLanguageTeacherVocabPopperTarget>(state.targets.size)
    for (target in state.targets) {
      val popProgress = target.popProgress
      if (popProgress != null) {
        val nextProgress = popProgress + dt / POP_ANIMATION_DURATION_SECONDS
        if (nextProgress < 1f) {
          updatedTargets += target.copy(popProgress = nextProgress)
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
        if (!(y > DESPAWN_Y && vy > 0f)) {
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

    // 4. Spawn a new target if under the simultaneous cap.
    var timeSinceLastSpawnMs = state.timeSinceLastSpawnMs + clampedMs
    val activeFlyingCount = updatedTargets.count { !it.isPopping }
    if (
      deck.isNotEmpty() &&
        activeFlyingCount < difficulty.maxSimultaneousTargets &&
        (timeSinceLastSpawnMs >= difficulty.spawnIntervalMilliseconds || activeFlyingCount == 0)
    ) {
      timeSinceLastSpawnMs = 0L
      spawnTarget(updatedTargets)?.let { updatedTargets += it }
      resolveTargetCollisions(updatedTargets)
    }

    state =
      state.copy(
        remainingMilliseconds = remaining,
        targets = updatedTargets,
        particles = updatedParticles,
        popBanners = updatedBanners,
        timeSinceLastSpawnMs = timeSinceLastSpawnMs,
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
   * unmatched words cannot pop future targets.
   */
  fun consumePendingTranscript() {
    transcriptCursor.consumePending()
  }

  /**
   * Evaluates the cumulative ASR [transcript] against all currently flying (non-popping) targets
   * and pops every matched target, consuming the matched transcript tokens so they cannot pop later
   * targets. Returns `null` when nothing was popped.
   */
  fun processTranscript(transcript: String): XrLanguageTeacherVocabPopperPopEvent? {
    if (state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING) return null
    val unconsumedText = transcriptCursor.takeUnconsumedText(transcript)
    if (unconsumedText.isEmpty()) return null
    if (state.targets.none { !it.isPopping }) return null

    val transcriptForms = XrLanguageTeacherSpeechMatcher.speechMatchForms(unconsumedText)
    if (transcriptForms.isEmpty()) return null

    var score = state.score
    var highScore = state.highScore
    var isNewHighScore = state.isNewHighScore
    val poppedDisplayWords = mutableListOf<String>()
    val particles = state.particles.toMutableList()
    val banners = state.popBanners.toMutableList()
    val targets =
      state.targets.map { target ->
        if (
          !target.isPopping &&
            XrLanguageTeacherSpeechMatcher.findTargetWordSpoken(
              transcriptForms = transcriptForms,
              targetForms = target.item.targetMatchForms,
            )
        ) {
          score += 1
          if (score > highScore) {
            highScore = score
            isNewHighScore = true
          }
          poppedDisplayWords += target.item.displayWord
          spawnPopEffects(target, banners, particles)
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
        targets = targets,
        particles = particles,
        popBanners = banners,
      )
    return XrLanguageTeacherVocabPopperPopEvent(
      poppedWords = poppedDisplayWords,
      newScore = score,
      isNewHighScore = isNewHighScore,
    )
  }

  /** Replaces the active target list for deterministic unit testing of collision physics. */
  @VisibleForTesting
  fun setTargetsForTesting(targets: List<XrLanguageTeacherVocabPopperTarget>) {
    state = state.copy(targets = targets, timeSinceLastSpawnMs = 0L)
    val maxId = targets.maxOfOrNull { it.id }
    if (maxId != null && maxId >= nextEntityId) {
      nextEntityId = maxId + 1
    }
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
        if (targets[i].isPopping) continue
        for (j in (i + 1) until targets.size) {
          if (targets[j].isPopping) continue
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
    val startX = pickLaunchX(currentTargets)
    val apexX = random.nextFloat(0.24f, 0.76f)
    val apexY = random.nextFloat(0.18f, 0.36f)
    val gravity = BASE_GRAVITY * difficulty.gravityScale
    val vy = -sqrt(max(0.05f, 2f * gravity * (LAUNCH_Y - apexY)))
    val timeToApex = max(0.35f, -vy / gravity)
    val vx = (apexX - startX) / timeToApex
    val initialRotation = random.nextFloat(-14f, 14f)
    val angularVelocity = random.nextFloat(-28f, 28f)
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
    val nearLaunchTargets = currentTargets.filter { !it.isPopping && it.y > NEAR_LAUNCH_ZONE_Y }
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
    banners: MutableList<XrLanguageTeacherVocabPopperPopBanner>,
    particles: MutableList<XrLanguageTeacherVocabPopperParticle>,
  ) {
    banners +=
      XrLanguageTeacherVocabPopperPopBanner(
        id = allocateId(),
        word = target.item.displayWord,
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
    val activeWords = currentTargets.filter { !it.isPopping }.map { it.item.word }.toSet()
    if (bag.isEmpty()) {
      bag = random.shuffled(deck)
    }
    val nonDuplicateIndex = bag.indexOfFirst { it.word !in activeWords }
    return if (nonDuplicateIndex >= 0) bag.removeAt(nonDuplicateIndex) else bag.removeAt(0)
  }

  private fun allocateId(): Long = nextEntityId++

  companion object {
    /** Minimum number of saved vocabulary words required to play. */
    const val MINIMUM_VOCABULARY_COUNT = 3

    /**
     * Normalized horizontal collision radius of a flying sticker (`118x118dp` in a portrait arena).
     * Used only for separation and bounce physics; the sticker cutout shape itself is never
     * altered.
     */
    const val SPRITE_RADIUS_X = 0.145f

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
    private const val PARTICLE_LIFETIME_SECONDS = 0.48f
    private const val PARTICLE_GRAVITY = 0.55f
    private const val BANNER_LIFETIME_SECONDS = 0.85f
    private const val BANNER_RISE_SPEED = 0.12f
    private const val PARTICLES_PER_POP = 14
  }
}
