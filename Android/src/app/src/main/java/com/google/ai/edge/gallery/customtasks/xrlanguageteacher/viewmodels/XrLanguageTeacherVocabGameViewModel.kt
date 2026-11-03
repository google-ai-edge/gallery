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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSoundEffects
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechListener
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechRecognizer
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The session core shared by the `Vocab Popper` and `Vocab Runner` ViewModels: the learn-language
 * speech recognizer, the sound effects, the heard words, the round clock, the celebrated word, the
 * word sticker sprites, the best score, and the session teardown. Subclasses keep their game state
 * and engine, and plug them in through the abstract members.
 *
 * In [XrLanguageTeacherVocabGameMode.SPEAK] mode, a game screen calls [startListening] once the
 * microphone permission is granted (or [onMicPermissionDenied]), the subclass's frame step on every
 * frame, and [endSession] when it closes; the round clock only runs once the microphone is ready
 * (or known to be unavailable). In [XrLanguageTeacherVocabGameMode.TAP] mode, no microphone or
 * speech recognizer is started and the round clock runs immediately.
 *
 * @param speakHighScoreKey preference key of the game's best score in Speak mode.
 * @param tapHighScoreKey preference key of the game's best score in Tap mode.
 */
abstract class XrLanguageTeacherVocabGameViewModel(
  protected val dependencies: XrLanguageTeacherVocabGameDependencies,
  private val speakHighScoreKey: String,
  private val tapHighScoreKey: String = speakHighScoreKey,
  protected val randomSeed: () -> Long,
) : ViewModel(), XrLanguageTeacherGameSpeechListener {
  private val heardWordsTracker = XrLanguageTeacherGameHeardWords(autoExpireScope = viewModelScope)

  /** The words heard most recently, shown as chips above the status bar. */
  val heardWords: StateFlow<List<XrLanguageTeacherGameHeardWord>> = heardWordsTracker.words

  private val _collisionHapticCount = MutableStateFlow(0)

  /**
   * Monotonically increasing count of obstacle collisions and wrong tap selections across the
   * ViewModel's lifetime, observed by the game screen to trigger collision haptic feedback even
   * when sound effects are muted.
   */
  internal val collisionHapticCount: StateFlow<Int> = _collisionHapticCount.asStateFlow()

  protected val soundEffects: XrLanguageTeacherGameSoundEffects = dependencies.createSoundEffects()
  private val frameClock = XrLanguageTeacherGameFrameClock()
  private val sessionTeardown = XrLanguageTeacherGameSessionTeardown(viewModelScope, dependencies)
  protected var learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED
    private set

  private var speechRecognizer: XrLanguageTeacherGameSpeechRecognizer? = null
  private var celebrationJob: Job? = null
  private var stickerJob: Job? = null

  /** Whether a round is set up and its game screen hasn't ended the session yet. */
  var isSessionActive: Boolean = false
    private set

  /** Active play mode of the current round; defaults to [XrLanguageTeacherVocabGameMode.SPEAK]. */
  open val mode: XrLanguageTeacherVocabGameMode
    get() = XrLanguageTeacherVocabGameMode.SPEAK

  /** Whether the current round is being played (not finished or over). */
  protected abstract val isPlaying: Boolean

  /** The microphone state shown in the game chrome. */
  protected abstract val micState: XrLanguageTeacherGameMicState

  /** Shows [state] as the microphone state in the game chrome. */
  protected abstract fun showMicState(state: XrLanguageTeacherGameMicState)

  /** Shows whether the sound effects are [muted] in the game chrome. */
  protected abstract fun showMuted(muted: Boolean)

  /** Celebrates [word] in the status bar, or stops celebrating with `null`. */
  protected abstract fun showCelebrationWord(
    word: String?,
    points: Int? = null,
    multiplier: Int = 1,
    isMissedWord: Boolean = false,
  )

  /** Replaces the word sticker sprites, keyed by [vocabGameEntryKey], with [transform]'s result. */
  protected abstract fun updateStickers(
    transform: (Map<String, ImageBitmap>) -> Map<String, ImageBitmap>
  )

  /** Starts over the engine's transcript when the recognizer starts a new utterance segment. */
  protected abstract fun resetTranscript()

  /** Marks the engine's pending transcript words as used after a speaking pause. */
  protected abstract fun consumePendingTranscript()

  /** Drops the engine when the session ends. */
  protected abstract fun dropEngine()

  /**
   * Starts a session in [learnLanguage] and returns up to [GAME_MAX_DECK_SIZE] words of [entries]
   * for the round. Starts preparing their sticker sprites, publishing each as it is ready.
   * Listening starts with [startListening] in Speak mode.
   */
  protected fun beginSession(
    learnLanguage: Language,
    entries: List<DictionaryEntry>,
  ): List<DictionaryEntry> {
    endSession()
    this.learnLanguage = learnLanguage
    val deckEntries = pickGameDeck(entries, Random(randomSeed()))
    isSessionActive = true
    dependencies.onRoundStarted()
    if (mode == XrLanguageTeacherVocabGameMode.TAP) {
      soundEffects.prepare()
    }
    val cachedStickers = cachedGameStickers(deckEntries)
    if (cachedStickers.isNotEmpty()) {
      updateStickers { cachedStickers }
    }
    val uncachedEntries = deckEntries.filter { it.vocabGameEntryKey() !in cachedStickers }
    if (uncachedEntries.isNotEmpty()) {
      stickerJob = viewModelScope.launch {
        prepareGameStickers(
          entries = uncachedEntries,
          loadStickerImage = dependencies.loadStickerImage,
          imageDispatcher = dependencies.imageDispatcher,
        ) { entryKey, sticker ->
          updateStickers { it + (entryKey to sticker) }
        }
      }
    }
    return deckEntries
  }

  /** Starts continuous speech recognition and warms up the sound effects. */
  fun startListening() {
    if (!isSessionActive) return
    soundEffects.prepare()
    if (mode != XrLanguageTeacherVocabGameMode.SPEAK) return
    val recognizer =
      speechRecognizer
        ?: dependencies.createSpeechRecognizer(viewModelScope, learnLanguage, this).also {
          speechRecognizer = it
        }
    if (isPlaying) {
      recognizer.start()
    }
  }

  /** Stops listening while the app is in the background; [startListening] listens again. */
  fun stopListening() {
    if (mode != XrLanguageTeacherVocabGameMode.SPEAK) return
    val unused = speechRecognizer?.stop()
    heardWordsTracker.clear()
    onMicStateChanged(XrLanguageTeacherGameMicState.STARTING)
  }

  /** Shows that the microphone permission is missing, so the game can't hear the words. */
  fun onMicPermissionDenied() {
    if (mode != XrLanguageTeacherVocabGameMode.SPEAK) return
    onMicStateChanged(XrLanguageTeacherGameMicState.PERMISSION_DENIED)
  }

  /**
   * Returns the milliseconds since the previous frame at [frameNanos] (a monotonic frame
   * timestamp), or 0 while the microphone is still starting in Speak mode, which holds the round
   * clock.
   */
  protected fun advanceFrameClock(frameNanos: Long): Long {
    if (
      mode == XrLanguageTeacherVocabGameMode.SPEAK &&
        micState == XrLanguageTeacherGameMicState.STARTING
    ) {
      // Hold the clock; the first frame after the microphone is ready sets the time base.
      frameClock.reset()
      return 0L
    }
    return frameClock.advance(frameNanos)
  }

  override fun onNewSegment() {
    resetTranscript()
    heardWordsTracker.resetSession()
  }

  override fun onUtterancePause() {
    consumePendingTranscript()
    heardWordsTracker.clear()
  }

  /** Shows [transcript] in the heard words, marking whether it matched a word ([isUsed]). */
  protected fun showHeard(transcript: String, isUsed: Boolean) {
    heardWordsTracker.onTranscript(transcript, isUsed = isUsed)
  }

  override fun onMicStateChanged(state: XrLanguageTeacherGameMicState) {
    showMicState(state)
  }

  /** Mutes or unmutes the sound effects (and any active prompt speech). */
  open fun toggleMute() {
    val muted = !soundEffects.isMuted
    soundEffects.isMuted = muted
    showMuted(muted)
  }

  /**
   * Plays the collision sound effect (when unmuted) and increments [collisionHapticCount] so the
   * game screen can trigger collision vibrotactile feedback.
   */
  protected fun emitCollisionFeedback() {
    soundEffects.playCollision()
    _collisionHapticCount.update { it + 1 }
  }

  /**
   * Starts a new round of the session with [startEngine], which creates the subclass's new engine
   * and shows its state.
   */
  protected fun restartRound(startEngine: () -> Unit) {
    if (!isSessionActive) return
    dependencies.onRoundStarted()
    celebrationJob?.cancel()
    heardWordsTracker.clear()
    startEngine()
    showCelebrationWord(null)
    if (mode == XrLanguageTeacherVocabGameMode.SPEAK) {
      speechRecognizer?.start()
    }
  }

  /** Stops listening and saves [highScore] when the round finishes. */
  protected fun finishRound(highScore: Int) {
    val unused = speechRecognizer?.stop()
    dependencies.onStopSpeech()
    heardWordsTracker.clear()
    persistHighScoreIfNeeded(highScore)
  }

  /** Celebrates [word] in the status bar for a moment. */
  protected fun celebrate(
    word: String,
    points: Int? = null,
    multiplier: Int = 1,
    isMissedWord: Boolean = false,
  ) {
    showCelebrationWord(
      word = word,
      points = points,
      multiplier = multiplier,
      isMissedWord = isMissedWord,
    )
    celebrationJob?.cancel()
    celebrationJob = viewModelScope.launch {
      delay(GAME_CELEBRATION_DURATION_MS)
      showCelebrationWord(null)
    }
  }

  private val activeHighScoreKey: String
    get() =
      when (mode) {
        XrLanguageTeacherVocabGameMode.TAP -> tapHighScoreKey
        XrLanguageTeacherVocabGameMode.SPEAK -> speakHighScoreKey
      }

  /** Returns the best score saved for the game in the current [mode]. */
  protected fun readHighScore(): Int = dependencies.highScores.readHighScore(activeHighScoreKey)

  /** Restarts the round clock, for a new engine. */
  protected fun resetFrameClock() {
    frameClock.reset()
  }

  /** Saves [highScore] as the game's best score if it is higher. */
  protected fun persistHighScoreIfNeeded(highScore: Int) {
    if (highScore > readHighScore()) {
      dependencies.highScores.writeHighScore(activeHighScoreKey, highScore)
    }
  }

  /**
   * Stops listening and the sound effects and drops the sprites when the game screen closes. Once
   * the recognizer is torn down, the lesson prepares speech recognition for the host language
   * again.
   */
  fun endSession() {
    val wasSessionActive = isSessionActive
    celebrationJob?.cancel()
    celebrationJob = null
    stickerJob?.cancel()
    stickerJob = null
    val stopJob = speechRecognizer?.stop()
    speechRecognizer = null
    if (wasSessionActive) {
      dependencies.onStopSpeech()
    }
    soundEffects.close()
    heardWordsTracker.clear()
    dropEngine()
    updateStickers { emptyMap() }
    if (wasSessionActive) {
      isSessionActive = false
      sessionTeardown.restoreLessonSpeechAfter(stopJob)
    }
  }

  override fun onCleared() {
    endSession()
    soundEffects.release()
  }
}
