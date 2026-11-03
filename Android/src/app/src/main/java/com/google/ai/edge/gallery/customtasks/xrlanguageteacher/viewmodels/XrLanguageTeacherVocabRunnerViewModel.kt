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

import androidx.compose.ui.graphics.ImageBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameEndReason
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerDeckItem
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Preference key of the best `Vocab Runner` (`Speak` mode) score. */
internal const val VOCAB_RUNNER_SPEAK_HIGH_SCORE_KEY =
  "xr_language_teacher_vocab_runner_speak_high_score_v2"

/** Alias of [VOCAB_RUNNER_SPEAK_HIGH_SCORE_KEY]. */
internal const val VOCAB_RUNNER_HIGH_SCORE_KEY = VOCAB_RUNNER_SPEAK_HIGH_SCORE_KEY

/** Preference key of the best `Vocab Runner` (`Tap` mode) score. */
internal const val VOCAB_RUNNER_TAP_HIGH_SCORE_KEY =
  "xr_language_teacher_vocab_runner_tap_high_score_v2"

/**
 * The game chrome state of a `Vocab Runner` round. It only changes on a clear, a countdown second,
 * a life or choice change, a microphone change, or a setting, so the chrome doesn't recompose on
 * every frame.
 */
data class XrLanguageTeacherVocabRunnerUiState(
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP,
  val phase: XrLanguageTeacherVocabRunnerPhase = XrLanguageTeacherVocabRunnerPhase.PLAYING,
  val endReason: XrLanguageTeacherVocabGameEndReason =
    XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
  /** The whole seconds left in the round, rounded up. */
  val remainingSeconds: Int = 0,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val lives: Int = XrLanguageTeacherVocabRunnerEngine.INITIAL_LIVES,
  val maxLives: Int = XrLanguageTeacherVocabRunnerEngine.INITIAL_LIVES,
  val correctCount: Int = 0,
  val uniqueWordCount: Int = 0,
  val streak: Int = 0,
  val streakMultiplier: Int = 1,
  val choices: List<XrLanguageTeacherVocabRunnerDeckItem> = emptyList(),
  val micState: XrLanguageTeacherGameMicState = XrLanguageTeacherGameMicState.STARTING,
  val isMuted: Boolean = false,
  /**
   * The word shown in the status bar for a moment: the word just cleared, or (when
   * [isCelebrationMissedWord]) the word of an obstacle that broke or was missed; `null` when there
   * is none.
   */
  val celebrationWord: String? = null,
  /**
   * Composite points awarded for [celebrationWord], or `null` when no clear celebration is active.
   */
  val celebrationPoints: Int? = null,
  /** Streak multiplier applied to [celebrationPoints] when a clear celebration is active. */
  val celebrationMultiplier: Int = 1,
  /** Whether [celebrationWord] is a missed word, shown as a "Word was" error hint. */
  val isCelebrationMissedWord: Boolean = false,
)

/** The arena state of a `Vocab Runner` run, published once per frame and read while drawing. */
data class XrLanguageTeacherVocabRunnerArenaState(
  /** The engine snapshot: Buddy's recoil and stumble, the track, and the scrolling obstacles. */
  val game: XrLanguageTeacherVocabRunnerState = XrLanguageTeacherVocabRunnerState(),
  /** The intensity, in `[0, 1]`, of the screen edge glow flashed by a clear or a bump. */
  val edgeGlowIntensity: Float = 0f,
  /** Whether the edge glow is the red glow of a bump rather than the green glow of a clear. */
  val isCollisionGlow: Boolean = false,
  /** The word sticker sprites prepared so far, keyed by [vocabGameEntryKey]. */
  val stickers: Map<String, ImageBitmap> = emptyMap(),
)

/**
 * Runs 60-second `Vocab Runner` rounds: Buddy runs along a track lined with stickers of saved
 * words, and each one pops out of the way when the player says its word in the learn language.
 * Bumping into a sticker knocks the track back to try again; the third bump breaks it for no
 * points.
 *
 * [startRun] sets up a round; the game screen then calls [startListening] once the microphone
 * permission is granted (or [onMicPermissionDenied]) and [stepFrame] on every frame, and
 * [endSession] when it closes. The round clock only runs once the microphone is ready (or known to
 * be unavailable). The ViewModel drives the [XrLanguageTeacherVocabRunnerEngine] with frames and
 * transcripts, plays the sound effects, and persists the best score.
 */
class XrLanguageTeacherVocabRunnerViewModel(
  dependencies: XrLanguageTeacherVocabGameDependencies,
  randomSeed: () -> Long = System::nanoTime,
) :
  XrLanguageTeacherVocabGameViewModel(
    dependencies = dependencies,
    speakHighScoreKey = VOCAB_RUNNER_HIGH_SCORE_KEY,
    tapHighScoreKey = VOCAB_RUNNER_TAP_HIGH_SCORE_KEY,
    randomSeed = randomSeed,
  ) {
  private val _uiState = MutableStateFlow(XrLanguageTeacherVocabRunnerUiState())
  val uiState: StateFlow<XrLanguageTeacherVocabRunnerUiState> = _uiState.asStateFlow()

  private val _arenaState = MutableStateFlow(XrLanguageTeacherVocabRunnerArenaState())
  val arenaState: StateFlow<XrLanguageTeacherVocabRunnerArenaState> = _arenaState.asStateFlow()

  private var engine: XrLanguageTeacherVocabRunnerEngine? = null
  private var deck: List<XrLanguageTeacherVocabRunnerDeckItem> = emptyList()

  override var mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP
    private set

  override val isPlaying: Boolean
    get() = _uiState.value.phase == XrLanguageTeacherVocabRunnerPhase.PLAYING

  override val micState: XrLanguageTeacherGameMicState
    get() = _uiState.value.micState

  /**
   * Sets up a round with up to [GAME_MAX_DECK_SIZE] words of [entries] (all saved for
   * [learnLanguage], with images) in [mode] and starts preparing their sticker sprites, publishing
   * each as it is ready. In `Speak` mode, listening starts with [startListening]; in `Tap` mode,
   * the round starts immediately without microphone access.
   */
  fun startRun(
    learnLanguage: Language,
    entries: List<DictionaryEntry>,
    mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP,
  ) {
    endSession()
    this.mode = mode
    deck =
      beginSession(learnLanguage, entries).map { entry ->
        XrLanguageTeacherVocabRunnerDeckItem(
          entryKey = entry.vocabGameEntryKey(),
          word = entry.word,
          meaning = entry.translation,
        )
      }
    val newEngine = createEngine()
    val initialMicState =
      if (mode == XrLanguageTeacherVocabGameMode.TAP) {
        XrLanguageTeacherGameMicState.STOPPED
      } else {
        XrLanguageTeacherGameMicState.STARTING
      }
    _uiState.value =
      chromeStateFor(newEngine.state, base = XrLanguageTeacherVocabRunnerUiState(mode = mode))
        .copy(isMuted = soundEffects.isMuted, micState = initialMicState)
    _arenaState.update {
      it.copy(game = newEngine.state, edgeGlowIntensity = 0f, isCollisionGlow = false)
    }
  }

  /** Evaluates a multiple-choice button selection in `Tap` mode. */
  fun selectChoice(entryKey: String) {
    val engine = engine ?: return
    if (
      mode != XrLanguageTeacherVocabGameMode.TAP ||
        engine.state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING
    ) {
      return
    }
    val outcome = engine.selectChoice(entryKey) ?: return
    val game = engine.state
    if (outcome.isCorrect) {
      soundEffects.playPop()
      persistHighScoreIfNeeded(game.highScore)
      _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isCollisionGlow = false) }
      _uiState.update { chromeStateFor(game, base = it) }
      celebrate(
        word = outcome.correctItem.displayWord,
        points = outcome.pointsAwarded,
        multiplier = outcome.multiplier,
        isMissedWord = false,
      )
    } else {
      emitCollisionFeedback()
      _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isCollisionGlow = true) }
      _uiState.update { chromeStateFor(game, base = it) }
      celebrate(outcome.correctItem.displayWord, isMissedWord = true)
      if (outcome.didFinishRound) {
        finishRound(game.highScore)
      }
    }
  }

  /**
   * Deterministically steps the runner simulation by [deltaMilliseconds] in `<= 100ms` ticks for
   * unit and Scuba snapshot setup.
   */
  fun stepSimulation(deltaMilliseconds: Long) {
    val engine = engine ?: return
    if (deltaMilliseconds <= 0L) return
    var remaining = deltaMilliseconds
    var glow = _arenaState.value.edgeGlowIntensity
    var isCollisionGlow = _arenaState.value.isCollisionGlow
    var didCollideAny = false
    var missedWordAny: String? = null
    var didFinishRoundAny = false
    while (remaining > 0L && engine.state.phase == XrLanguageTeacherVocabRunnerPhase.PLAYING) {
      val chunk = minOf(remaining, 100L)
      remaining -= chunk
      glow = decayEdgeGlow(glow, chunk)
      if (glow <= 0f) isCollisionGlow = false
      val stepEvents = engine.step(chunk)
      if (stepEvents.didCollide) {
        didCollideAny = true
        isCollisionGlow = true
        glow = 1f
      }
      if (stepEvents.missedWord != null) {
        missedWordAny = stepEvents.missedWord
      }
      if (stepEvents.didFinishRound) {
        didFinishRoundAny = true
      }
    }
    val game = engine.state
    if (didCollideAny) emitCollisionFeedback()
    _arenaState.update {
      it.copy(game = game, edgeGlowIntensity = glow, isCollisionGlow = isCollisionGlow)
    }
    _uiState.update { chromeStateFor(game, base = it) }
    missedWordAny?.let { missedWord -> celebrate(missedWord, isMissedWord = true) }
    if (didFinishRoundAny) {
      finishRound(game.highScore)
    }
  }

  /**
   * Advances the round to the frame at [frameNanos] (a monotonic frame timestamp). The round waits
   * while the microphone is still starting.
   */
  fun stepFrame(frameNanos: Long) {
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING) return
    val deltaMilliseconds = advanceFrameClock(frameNanos)
    if (deltaMilliseconds <= 0L) return
    val events = engine.step(deltaMilliseconds)
    val game = engine.state
    if (events.didCollide) emitCollisionFeedback()
    _arenaState.update {
      val glow = if (events.didCollide) 1f else it.edgeGlowIntensity
      val newGlow = decayEdgeGlow(glow, deltaMilliseconds)
      it.copy(
        game = game,
        edgeGlowIntensity = newGlow,
        isCollisionGlow = if (newGlow <= 0f) false else (events.didCollide || it.isCollisionGlow),
      )
    }
    _uiState.update { chromeStateFor(game, base = it) }
    events.missedWord?.let { missedWord -> celebrate(missedWord, isMissedWord = true) }
    if (events.didFinishRound) {
      finishRound(game.highScore)
    }
  }

  override fun onTranscript(transcript: String) {
    if (mode != XrLanguageTeacherVocabGameMode.SPEAK) return
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabRunnerPhase.PLAYING) return
    val clearEvent = engine.processTranscript(transcript)
    showHeard(transcript, isUsed = clearEvent != null)
    if (clearEvent == null) return
    val game = engine.state
    soundEffects.playPop()
    persistHighScoreIfNeeded(game.highScore)
    _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isCollisionGlow = false) }
    _uiState.update { chromeStateFor(game, base = it) }
    celebrate(
      word = clearEvent.matchedWord,
      points = clearEvent.pointsAwarded,
      multiplier = clearEvent.multiplier,
      isMissedWord = false,
    )
  }

  /** Starts a new 60-second round with the same words and mode. */
  fun playAgain() {
    restartRound {
      val newEngine = createEngine()
      _arenaState.update {
        it.copy(game = newEngine.state, edgeGlowIntensity = 0f, isCollisionGlow = false)
      }
      _uiState.update { chromeStateFor(newEngine.state, base = it) }
    }
  }

  override fun showMicState(state: XrLanguageTeacherGameMicState) {
    _uiState.update { it.copy(micState = state) }
  }

  override fun showMuted(muted: Boolean) {
    _uiState.update { it.copy(isMuted = muted) }
  }

  override fun showCelebrationWord(
    word: String?,
    points: Int?,
    multiplier: Int,
    isMissedWord: Boolean,
  ) {
    _uiState.update {
      it.copy(
        celebrationWord = word,
        celebrationPoints = if (word != null && !isMissedWord) points else null,
        celebrationMultiplier = if (word != null && !isMissedWord) multiplier else 1,
        isCelebrationMissedWord = word != null && isMissedWord,
      )
    }
  }

  override fun updateStickers(transform: (Map<String, ImageBitmap>) -> Map<String, ImageBitmap>) {
    _arenaState.update { it.copy(stickers = transform(it.stickers)) }
  }

  override fun resetTranscript() {
    engine?.resetTranscript()
  }

  override fun consumePendingTranscript() {
    engine?.consumePendingTranscript()
  }

  override fun dropEngine() {
    engine = null
  }

  private fun createEngine(): XrLanguageTeacherVocabRunnerEngine {
    val highScore = maxOf(engine?.state?.highScore ?: 0, readHighScore())
    resetFrameClock()
    return XrLanguageTeacherVocabRunnerEngine(
        deck = deck,
        mode = mode,
        initialHighScore = highScore,
        randomSeed = randomSeed(),
      )
      .also { engine = it }
  }

  private fun chromeStateFor(
    game: XrLanguageTeacherVocabRunnerState,
    base: XrLanguageTeacherVocabRunnerUiState,
  ): XrLanguageTeacherVocabRunnerUiState =
    base.copy(
      mode = mode,
      phase = game.phase,
      endReason = game.endReason,
      remainingSeconds = remainingSecondsFor(game.remainingMilliseconds),
      score = game.score,
      highScore = game.highScore,
      isNewHighScore = game.isNewHighScore,
      lives = game.lives,
      maxLives = game.maxLives,
      correctCount = game.correctCount,
      uniqueWordCount = game.uniqueWordCount,
      streak = game.streak,
      streakMultiplier = game.streakMultiplier,
      choices = game.choices,
    )
}
