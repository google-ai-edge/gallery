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
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDeckItem
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDifficulty
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperDuration
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPopEvent
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Preference key of the best `Vocab Popper` score. */
internal const val VOCAB_POPPER_HIGH_SCORE_KEY = "xr_language_teacher_vocab_popper_high_score"

/**
 * The game chrome state of a `Vocab Popper` round. It only changes on a pop, a countdown second, a
 * microphone change, or a setting, so the chrome doesn't recompose on every frame.
 */
data class XrLanguageTeacherVocabPopperUiState(
  val phase: XrLanguageTeacherVocabPopperPhase = XrLanguageTeacherVocabPopperPhase.PLAYING,
  /** The whole seconds left in the round, rounded up. */
  val remainingSeconds: Int = 0,
  val score: Int = 0,
  val highScore: Int = 0,
  val isNewHighScore: Boolean = false,
  val micState: XrLanguageTeacherGameMicState = XrLanguageTeacherGameMicState.STARTING,
  val isMuted: Boolean = false,
  /** The word just popped, celebrated in the status bar for a moment, or `null`. */
  val celebrationWord: String? = null,
)

/** The arena state of a `Vocab Popper` round, published once per frame and read while drawing. */
data class XrLanguageTeacherVocabPopperArenaState(
  /** The engine snapshot: flying stickers, burst particles, and "+1" banners. */
  val game: XrLanguageTeacherVocabPopperState = XrLanguageTeacherVocabPopperState(),
  /** The intensity, in `[0, 1]`, of the green screen edge glow flashed by a pop. */
  val edgeGlowIntensity: Float = 0f,
  /** The word sticker sprites prepared so far, keyed by [vocabGameEntryKey]. */
  val stickers: Map<String, ImageBitmap> = emptyMap(),
)

/**
 * Runs `Vocab Popper` rounds: stickers of saved words fly up and the player pops them by saying the
 * words in the learn language.
 *
 * [startRound] sets up a round; the game screen then calls [startListening] once the microphone
 * permission is granted (or [onMicPermissionDenied]) and [stepFrame] on every frame, and
 * [endSession] when it closes. The round clock only runs once the microphone is ready (or known to
 * be unavailable), so no time is lost to the permission prompt or to starting the recognizer. The
 * ViewModel drives the [XrLanguageTeacherVocabPopperEngine] with frames and transcripts, plays the
 * sound effects, and persists the best score.
 */
class XrLanguageTeacherVocabPopperViewModel(
  dependencies: XrLanguageTeacherVocabGameDependencies,
  randomSeed: () -> Long = System::nanoTime,
) :
  XrLanguageTeacherVocabGameViewModel(
    dependencies = dependencies,
    highScoreKey = VOCAB_POPPER_HIGH_SCORE_KEY,
    randomSeed = randomSeed,
  ) {
  private val _uiState = MutableStateFlow(XrLanguageTeacherVocabPopperUiState())
  val uiState: StateFlow<XrLanguageTeacherVocabPopperUiState> = _uiState.asStateFlow()

  private val _arenaState = MutableStateFlow(XrLanguageTeacherVocabPopperArenaState())
  val arenaState: StateFlow<XrLanguageTeacherVocabPopperArenaState> = _arenaState.asStateFlow()

  private var engine: XrLanguageTeacherVocabPopperEngine? = null
  private var deck: List<XrLanguageTeacherVocabPopperDeckItem> = emptyList()
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
   * Listening starts with [startListening].
   */
  fun startRound(
    learnLanguage: Language,
    entries: List<DictionaryEntry>,
    duration: XrLanguageTeacherVocabPopperDuration =
      XrLanguageTeacherVocabPopperDuration.SECONDS_60,
    difficulty: XrLanguageTeacherVocabPopperDifficulty =
      XrLanguageTeacherVocabPopperDifficulty.NORMAL,
  ) {
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
    _uiState.value =
      chromeStateFor(newEngine.state, base = XrLanguageTeacherVocabPopperUiState())
        .copy(isMuted = soundEffects.isMuted)
    _arenaState.update { it.copy(game = newEngine.state, edgeGlowIntensity = 0f) }
  }

  /**
   * Advances the round to the frame at [frameNanos] (a monotonic frame timestamp). The round waits
   * while the microphone is still starting.
   */
  fun stepFrame(frameNanos: Long) {
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING) return
    val deltaMilliseconds = advanceFrameClock(frameNanos)
    if (deltaMilliseconds <= 0L) return
    engine.step(deltaMilliseconds)
    val game = engine.state
    _arenaState.update {
      it.copy(
        game = game,
        edgeGlowIntensity = decayEdgeGlow(it.edgeGlowIntensity, deltaMilliseconds),
      )
    }
    _uiState.update { chromeStateFor(game, base = it) }
    if (game.phase == XrLanguageTeacherVocabPopperPhase.FINISHED) {
      finishRound(game.highScore)
    }
  }

  override fun onTranscript(transcript: String) {
    val engine = engine ?: return
    if (engine.state.phase != XrLanguageTeacherVocabPopperPhase.PLAYING) return
    val popEvent = engine.processTranscript(transcript)
    showHeard(transcript, isUsed = popEvent != null)
    if (popEvent != null) showPops(engine, popEvent)
  }

  override fun handleUtterancePause() {
    val engine = engine ?: return
    engine.processUtterancePause()?.let { showPops(engine, it) }
  }

  /** Plays and shows the pops of [popEvent], which [engine] just made. */
  private fun showPops(
    engine: XrLanguageTeacherVocabPopperEngine,
    popEvent: XrLanguageTeacherVocabPopperPopEvent,
  ) {
    val game = engine.state
    soundEffects.playPop()
    persistHighScoreIfNeeded(game.highScore)
    _arenaState.update { it.copy(game = game, edgeGlowIntensity = 1f) }
    _uiState.update { chromeStateFor(game, base = it) }
    popEvent.poppedWords.firstOrNull()?.let { celebrate(it) }
  }

  /** Starts a new round with the same words, length, and difficulty. */
  fun playAgain() {
    restartRound {
      val newEngine = createEngine()
      _arenaState.update { it.copy(game = newEngine.state, edgeGlowIntensity = 0f) }
      _uiState.update { chromeStateFor(newEngine.state, base = it) }
    }
  }

  override fun showMicState(state: XrLanguageTeacherGameMicState) {
    _uiState.update { it.copy(micState = state) }
  }

  override fun showMuted(muted: Boolean) {
    _uiState.update { it.copy(isMuted = muted) }
  }

  override fun showCelebrationWord(word: String?) {
    _uiState.update { it.copy(celebrationWord = word) }
  }

  override fun updateStickers(transform: (Map<String, ImageBitmap>) -> Map<String, ImageBitmap>) {
    _arenaState.update { it.copy(stickers = transform(it.stickers)) }
  }

  override fun resetTranscript() {
    engine?.resetTranscript()
  }

  override fun dropEngine() {
    engine = null
  }

  private fun createEngine(): XrLanguageTeacherVocabPopperEngine {
    val highScore = maxOf(engine?.state?.highScore ?: 0, readHighScore())
    resetFrameClock()
    return XrLanguageTeacherVocabPopperEngine(
        deck = deck,
        duration = duration,
        difficulty = difficulty,
        initialHighScore = highScore,
        randomSeed = randomSeed(),
      )
      .also { engine = it }
  }

  private fun chromeStateFor(
    game: XrLanguageTeacherVocabPopperState,
    base: XrLanguageTeacherVocabPopperUiState,
  ): XrLanguageTeacherVocabPopperUiState =
    base.copy(
      phase = game.phase,
      remainingSeconds = remainingSecondsFor(game.remainingMilliseconds),
      score = game.score,
      highScore = game.highScore,
      isNewHighScore = game.isNewHighScore,
    )
}
