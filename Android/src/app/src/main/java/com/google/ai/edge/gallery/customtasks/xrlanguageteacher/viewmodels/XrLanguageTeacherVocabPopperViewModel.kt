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
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDeckItem
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDifficulty
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDuration
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Preference key of the best `Vocab Popper` score in Speak mode. */
internal const val VOCAB_POPPER_SPEAK_HIGH_SCORE_KEY =
  "xr_language_teacher_vocab_popper_speak_high_score_v2"

/** Alias of [VOCAB_POPPER_SPEAK_HIGH_SCORE_KEY]. */
internal const val VOCAB_POPPER_HIGH_SCORE_KEY = VOCAB_POPPER_SPEAK_HIGH_SCORE_KEY

/** Preference key of the best `Vocab Popper` score in Tap mode. */
internal const val VOCAB_POPPER_TAP_HIGH_SCORE_KEY =
  "xr_language_teacher_vocab_popper_tap_high_score_v2"

/**
 * The game chrome state of a `Vocab Popper` round. It only changes on a pop, a wave spawn, a
 * countdown second, a microphone change, or a setting, so the chrome doesn't recompose on every
 * frame.
 */
data class XrLanguageTeacherVocabPopperUiState(
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP,
  val phase: XrLanguageTeacherVocabPopperPhase = XrLanguageTeacherVocabPopperPhase.PLAYING,
  val endReason: XrLanguageTeacherVocabGameEndReason =
    XrLanguageTeacherVocabGameEndReason.TIME_EXPIRED,
  /** The whole seconds left in the round, rounded up. */
  val remainingSeconds: Int = 0,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val lives: Int = XrLanguageTeacherVocabPopperEngine.INITIAL_LIVES,
  val maxLives: Int = XrLanguageTeacherVocabPopperEngine.INITIAL_LIVES,
  val correctCount: Int = 0,
  val uniqueWordCount: Int = 0,
  val streak: Int = 0,
  val streakMultiplier: Int = 1,
  /** Display word of the current wave's prompted target in Tap mode, or `null`. */
  val targetWord: String? = null,
  val micState: XrLanguageTeacherGameMicState = XrLanguageTeacherGameMicState.STARTING,
  val isMuted: Boolean = false,
  /** The word just popped, celebrated in the status bar for a moment, or `null`. */
  val celebrationWord: String? = null,
  /** Composite points awarded for [celebrationWord], or `null` when no celebration is active. */
  val celebrationPoints: Int? = null,
  /** Streak multiplier applied to [celebrationPoints] when [celebrationWord] is active. */
  val celebrationMultiplier: Int = 1,
)

/** The arena state of a `Vocab Popper` round, published once per frame and read while drawing. */
data class XrLanguageTeacherVocabPopperArenaState(
  /** The engine snapshot: flying stickers, burst particles, and "+1" banners. */
  val game: XrLanguageTeacherVocabPopperState = XrLanguageTeacherVocabPopperState(),
  /** The intensity, in `[0, 1]`, of the screen edge glow flashed by a pop or wrong tap. */
  val edgeGlowIntensity: Float = 0f,
  /** Whether the edge glow is the red glow of a wrong tap rather than the green glow of a pop. */
  val isWrongTapGlow: Boolean = false,
  /** The word sticker sprites prepared so far, keyed by [vocabGameEntryKey]. */
  val stickers: Map<String, ImageBitmap> = emptyMap(),
)

/**
 * Runs `Vocab Popper` rounds: stickers of saved words fly up and the player pops them by tapping
 * the prompted word ([XrLanguageTeacherVocabGameMode.TAP]) or saying the words in the learn
 * language ([XrLanguageTeacherVocabGameMode.SPEAK]).
 *
 * [startRound] sets up a round; in Speak mode, the game screen calls [startListening] once the
 * microphone permission is granted (or [onMicPermissionDenied]). The screen calls [stepFrame] on
 * every frame and [endSession] when it closes. The ViewModel drives the
 * [XrLanguageTeacherVocabPopperEngine] with frames, taps, and transcripts, plays the sound effects
 * and TTS prompts, and persists the best score.
 */
class XrLanguageTeacherVocabPopperViewModel(
  dependencies: XrLanguageTeacherVocabGameDependencies,
  randomSeed: () -> Long = System::nanoTime,
) :
  XrLanguageTeacherVocabGameViewModel(
    dependencies = dependencies,
    speakHighScoreKey = VOCAB_POPPER_HIGH_SCORE_KEY,
    tapHighScoreKey = VOCAB_POPPER_TAP_HIGH_SCORE_KEY,
    randomSeed = randomSeed,
  ) {
  private val _uiState = MutableStateFlow(XrLanguageTeacherVocabPopperUiState())
  val uiState: StateFlow<XrLanguageTeacherVocabPopperUiState> = _uiState.asStateFlow()

  private val _arenaState = MutableStateFlow(XrLanguageTeacherVocabPopperArenaState())
  val arenaState: StateFlow<XrLanguageTeacherVocabPopperArenaState> = _arenaState.asStateFlow()

  private var engine: XrLanguageTeacherVocabPopperEngine? = null
  private var deck: List<XrLanguageTeacherVocabPopperDeckItem> = emptyList()

  override var mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP
    private set

  var duration: XrLanguageTeacherVocabPopperDuration =
    XrLanguageTeacherVocabPopperDuration.SECONDS_60
    private set

  var difficulty: XrLanguageTeacherVocabPopperDifficulty =
    XrLanguageTeacherVocabPopperDifficulty.NORMAL
    private set

  override val isPlaying: Boolean
    get() = _uiState.value.phase == XrLanguageTeacherVocabPopperPhase.PLAYING

  override val micState: XrLanguageTeacherGameMicState
    get() = _uiState.value.micState

  /**
   * Sets up a round with up to [GAME_MAX_DECK_SIZE] words of [entries] (all saved for
   * [learnLanguage]) and starts preparing their sticker sprites, publishing each as it is ready.
   * Listening starts with [startListening] in Speak mode.
   */
  fun startRound(
    learnLanguage: Language,
    entries: List<DictionaryEntry>,
    mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP,
    duration: XrLanguageTeacherVocabPopperDuration =
      XrLanguageTeacherVocabPopperDuration.SECONDS_60,
    difficulty: XrLanguageTeacherVocabPopperDifficulty =
      XrLanguageTeacherVocabPopperDifficulty.NORMAL,
  ) {
    endSession()
    this.mode = mode
    this.duration = duration
    this.difficulty = difficulty
    deck =
      beginSession(learnLanguage, entries).map { entry ->
        XrLanguageTeacherVocabPopperDeckItem(
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
      chromeStateFor(
          newEngine.state,
          base = XrLanguageTeacherVocabPopperUiState(micState = initialMicState),
        )
        .copy(isMuted = soundEffects.isMuted)
    _arenaState.update {
      it.copy(game = newEngine.state, edgeGlowIntensity = 0f, isWrongTapGlow = false)
    }
    speakInitialWaveIfNeeded(newEngine)
  }

  /**
   * Advances the round to the frame at [frameNanos] (a monotonic frame timestamp). The round waits
   * while the microphone is still starting in Speak mode.
   */
  fun stepFrame(frameNanos: Long) {
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING) return
    val deltaMilliseconds = advanceFrameClock(frameNanos)
    if (deltaMilliseconds <= 0L) return
    stepEngine(engine, deltaMilliseconds)
  }

  /**
   * Advances the round by [deltaMilliseconds] directly (breaking steps larger than
   * [GAME_MAX_FRAME_STEP_MS] into chunks), for deterministic unit and screenshot tests.
   */
  fun stepSimulation(deltaMilliseconds: Long) {
    val engine = engine ?: return
    if (deltaMilliseconds <= 0L) return
    var remaining = deltaMilliseconds
    while (remaining > 0L && engine.state.phase == XrLanguageTeacherVocabPopperPhase.PLAYING) {
      val chunk = min(remaining, GAME_MAX_FRAME_STEP_MS)
      remaining -= chunk
      stepEngine(engine, chunk)
    }
  }

  private fun stepEngine(engine: XrLanguageTeacherVocabPopperEngine, deltaMilliseconds: Long) {
    val events = engine.step(deltaMilliseconds)
    val game = engine.state
    _arenaState.update {
      val nextGlow = decayEdgeGlow(it.edgeGlowIntensity, deltaMilliseconds)
      it.copy(
        game = game,
        edgeGlowIntensity = nextGlow,
        isWrongTapGlow = it.isWrongTapGlow && nextGlow > 0f,
      )
    }
    _uiState.update { chromeStateFor(game, base = it) }
    if (events.didStartNewWave && game.phase == XrLanguageTeacherVocabPopperPhase.PLAYING) {
      game.currentTarget?.displayWord?.let { speakPromptIfUnmuted(it) }
    }
    if (events.didFinishRound || game.phase == XrLanguageTeacherVocabPopperPhase.FINISHED) {
      finishRound(game.highScore)
    }
  }

  /**
   * Handles a tap on the arena at normalized coordinates `([normalizedX], [normalizedY])` in `[0,
   * 1]` during [XrLanguageTeacherVocabGameMode.TAP] mode.
   */
  fun handleArenaTap(
    normalizedX: Float,
    normalizedY: Float,
    arenaAspectRatio: Float = XrLanguageTeacherVocabPopperEngine.VERTICAL_TO_HORIZONTAL_SCALE,
  ) {
    val engine = engine ?: return
    if (
      mode != XrLanguageTeacherVocabGameMode.TAP ||
        engine.state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING
    ) {
      return
    }
    val outcome = engine.tap(normalizedX, normalizedY, arenaAspectRatio) ?: return
    val game = engine.state
    if (outcome.isCorrect) {
      soundEffects.playPop()
      persistHighScoreIfNeeded(game.highScore)
      _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isWrongTapGlow = false) }
      _uiState.update { chromeStateFor(game, base = it) }
    } else {
      emitCollisionFeedback()
      _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isWrongTapGlow = true) }
      _uiState.update { chromeStateFor(game, base = it) }
      if (outcome.didFinishRound) {
        finishRound(game.highScore)
      }
    }
  }

  /** Replays the current wave's prompted word in [XrLanguageTeacherVocabGameMode.TAP] mode. */
  fun replayPromptAudio() {
    if (mode != XrLanguageTeacherVocabGameMode.TAP || !isPlaying) return
    val targetWord = engine?.state?.currentTarget?.displayWord?.takeIf { it.isNotEmpty() } ?: return
    speakPromptIfUnmuted(targetWord)
  }

  override fun toggleMute() {
    super.toggleMute()
    if (soundEffects.isMuted) {
      dependencies.onStopSpeech()
    } else if (mode == XrLanguageTeacherVocabGameMode.TAP && isPlaying) {
      replayPromptAudio()
    }
  }

  override fun onTranscript(transcript: String) {
    if (mode != XrLanguageTeacherVocabGameMode.SPEAK) return
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING) return
    val popEvent = engine.processTranscript(transcript)
    showHeard(transcript, isUsed = popEvent != null)
    if (popEvent == null) return
    val game = engine.state
    soundEffects.playPop()
    persistHighScoreIfNeeded(game.highScore)
    _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f, isWrongTapGlow = false) }
    _uiState.update { chromeStateFor(game, base = it) }
    popEvent.poppedWords.firstOrNull()?.let { word ->
      celebrate(word = word, points = popEvent.pointsAwarded, multiplier = popEvent.multiplier)
    }
  }

  /** Starts a new round with the same words, mode, length, and difficulty. */
  fun playAgain() {
    restartRound {
      val newEngine = createEngine()
      _arenaState.update {
        it.copy(game = newEngine.state, edgeGlowIntensity = 0f, isWrongTapGlow = false)
      }
      _uiState.update { chromeStateFor(newEngine.state, base = it) }
      speakInitialWaveIfNeeded(newEngine)
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
        celebrationPoints = if (word != null) points else null,
        celebrationMultiplier = if (word != null) multiplier else 1,
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

  private fun createEngine(): XrLanguageTeacherVocabPopperEngine {
    val highScore = maxOf(engine?.state?.highScore ?: 0, readHighScore())
    resetFrameClock()
    return XrLanguageTeacherVocabPopperEngine(
        deck = deck,
        mode = mode,
        duration = duration,
        difficulty = difficulty,
        initialHighScore = highScore,
        randomSeed = randomSeed(),
      )
      .also { engine = it }
  }

  private fun speakInitialWaveIfNeeded(engine: XrLanguageTeacherVocabPopperEngine) {
    if (mode != XrLanguageTeacherVocabGameMode.TAP) return
    engine.state.currentTarget
      ?.displayWord
      ?.takeIf { it.isNotEmpty() }
      ?.let { speakPromptIfUnmuted(it) }
  }

  private fun speakPromptIfUnmuted(word: String) {
    if (!soundEffects.isMuted) {
      dependencies.onSpeakWord(word, learnLanguage)
    }
  }

  private fun chromeStateFor(
    game: XrLanguageTeacherVocabPopperState,
    base: XrLanguageTeacherVocabPopperUiState,
  ): XrLanguageTeacherVocabPopperUiState =
    base.copy(
      mode = game.mode,
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
      targetWord = game.currentTarget?.displayWord,
    )
}
