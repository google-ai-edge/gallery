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
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSoundEffects
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechListener
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechRecognizer
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The session core shared by the `Vocab Popper` and `Vocab Runner` ViewModels: the learn-language
 * speech recognizer, the sound effects, the heard words, the round clock, the celebrated word, the
 * word sticker sprites, the best score, and the session teardown. Subclasses keep their game state
 * and engine, and plug them in through the abstract members.
 *
 * A game screen calls [startListening] once the microphone permission is granted (or
 * [onMicPermissionDenied]), the subclass's frame step on every frame, and [endSession] when it
 * closes. The round clock only runs once the microphone is ready (or known to be unavailable).
 *
 * @param highScoreKey the preference key of the game's best score.
 */
abstract class XrLanguageTeacherVocabGameViewModel(
  protected val dependencies: XrLanguageTeacherVocabGameDependencies,
  private val highScoreKey: String,
  protected val randomSeed: () -> Long,
) : ViewModel(), XrLanguageTeacherGameSpeechListener {
  private val heardWordsTracker = XrLanguageTeacherGameHeardWords(autoExpireScope = viewModelScope)

  /** The words heard most recently, shown as chips above the status bar. */
  val heardWords: StateFlow<List<XrLanguageTeacherGameHeardWord>> = heardWordsTracker.words

  protected val soundEffects: XrLanguageTeacherGameSoundEffects = dependencies.createSoundEffects()
  private val frameClock = XrLanguageTeacherGameFrameClock()
  private val sessionTeardown = XrLanguageTeacherGameSessionTeardown(viewModelScope, dependencies)
  private var learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED
  private var speechRecognizer: XrLanguageTeacherGameSpeechRecognizer? = null
  private var celebrationJob: Job? = null
  private var stickerJob: Job? = null

  /** Whether a round is set up and its game screen hasn't ended the session yet. */
  var isSessionActive: Boolean = false
    private set

  /** Whether the current round is being played (not finished or over). */
  protected abstract val isPlaying: Boolean

  /** The microphone state shown in the game chrome. */
  protected abstract val micState: XrLanguageTeacherGameMicState

  /** Shows [state] as the microphone state in the game chrome. */
  protected abstract fun showMicState(state: XrLanguageTeacherGameMicState)

  /** Shows whether the sound effects are [muted] in the game chrome. */
  protected abstract fun showMuted(muted: Boolean)

  /** Celebrates [word] in the status bar, or stops celebrating with `null`. */
  protected abstract fun showCelebrationWord(word: String?)

  /** Replaces the word sticker sprites, keyed by [vocabGameEntryKey], with [transform]'s result. */
  protected abstract fun updateStickers(
    transform: (Map<String, ImageBitmap>) -> Map<String, ImageBitmap>
  )

  /** Starts over the engine's transcript when the recognizer starts a new utterance segment. */
  protected abstract fun resetTranscript()

  /**
   * Handles a speaking pause (or the end of a segment): lets the engine match the now stable
   * transcript, then marks its pending words as used.
   */
  protected abstract fun handleUtterancePause()

  /** Drops the engine when the session ends. */
  protected abstract fun dropEngine()

  /**
   * Starts a session in [learnLanguage] and returns up to [GAME_MAX_DECK_SIZE] words of [entries]
   * for the round. Starts preparing their sticker sprites, publishing each as it is ready.
   * Listening starts with [startListening].
   */
  protected fun beginSession(
    learnLanguage: Language,
    entries: List<DictionaryEntry>,
  ): List<DictionaryEntry> {
    endSession()
    this.learnLanguage = learnLanguage
    val deckEntries = pickGameDeck(entries, Random(randomSeed()))
    isSessionActive = true
    dependencies.onSessionStarted()
    dependencies.onRoundStarted()
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
    val unused = speechRecognizer?.stop()
    heardWordsTracker.clear()
    onMicStateChanged(XrLanguageTeacherGameMicState.STARTING)
  }

  /** Shows that the microphone permission is missing, so the game can't hear the words. */
  fun onMicPermissionDenied() {
    onMicStateChanged(XrLanguageTeacherGameMicState.PERMISSION_DENIED)
  }

  /**
   * Returns the milliseconds since the previous frame at [frameNanos] (a monotonic frame
   * timestamp), or 0 while the microphone is still starting, which holds the round clock.
   */
  protected fun advanceFrameClock(frameNanos: Long): Long {
    if (micState == XrLanguageTeacherGameMicState.STARTING) {
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
    handleUtterancePause()
    heardWordsTracker.clear()
  }

  /** Shows [transcript] in the heard words, marking whether it matched a word ([isUsed]). */
  protected fun showHeard(transcript: String, isUsed: Boolean) {
    heardWordsTracker.onTranscript(transcript, isUsed = isUsed)
  }

  override fun onMicStateChanged(state: XrLanguageTeacherGameMicState) {
    showMicState(state)
  }

  /** Mutes or unmutes the sound effects. */
  fun toggleMute() {
    val muted = !soundEffects.isMuted
    soundEffects.isMuted = muted
    showMuted(muted)
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
    speechRecognizer?.start()
  }

  /** Stops listening and saves [highScore] when the round finishes. */
  protected fun finishRound(highScore: Int) {
    val unused = speechRecognizer?.stop()
    heardWordsTracker.clear()
    persistHighScoreIfNeeded(highScore)
  }

  /** Celebrates [word] in the status bar for a moment. */
  protected fun celebrate(word: String) {
    showCelebrationWord(word)
    celebrationJob?.cancel()
    celebrationJob = viewModelScope.launch {
      delay(GAME_CELEBRATION_DURATION_MS)
      showCelebrationWord(null)
    }
  }

  /** Returns the best score saved for the game. */
  protected fun readHighScore(): Int = dependencies.highScores.readHighScore(highScoreKey)

  /** Restarts the round clock, for a new engine. */
  protected fun resetFrameClock() {
    frameClock.reset()
  }

  /** Saves [highScore] as the game's best score if it is higher. */
  protected fun persistHighScoreIfNeeded(highScore: Int) {
    if (highScore > readHighScore()) {
      dependencies.highScores.writeHighScore(highScoreKey, highScore)
    }
  }

  /**
   * Stops listening and the sound effects and drops the sprites when the game screen closes. Once
   * the recognizer is torn down, the lesson prepares speech recognition for the host language
   * again.
   */
  fun endSession() {
    celebrationJob?.cancel()
    celebrationJob = null
    stickerJob?.cancel()
    stickerJob = null
    val stopJob = speechRecognizer?.stop()
    speechRecognizer = null
    soundEffects.close()
    heardWordsTracker.clear()
    dropEngine()
    updateStickers { emptyMap() }
    if (isSessionActive) {
      isSessionActive = false
      sessionTeardown.restoreLessonSpeechAfter(stopJob)
    }
  }

  override fun onCleared() {
    endSession()
    soundEffects.release()
  }
}
