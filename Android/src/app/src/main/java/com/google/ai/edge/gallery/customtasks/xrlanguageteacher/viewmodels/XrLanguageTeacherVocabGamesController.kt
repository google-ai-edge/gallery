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

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.decodeSavedVocabImage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.hasVocabPhoto
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.hasVocabSticker
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSoundPlayer
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelFillerPresynthesis
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelVocabGamePrewarm
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.createGameSpeechRecognizer
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.playGamePromptWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareAsrEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.presynthesizeGamePromptWords
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.tts.TtsService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val VOCAB_GAMES_TAG = "AGXrltVocabGames"

/**
 * Returns what the `Vocab Popper` and `Vocab Runner` ViewModels need from this ViewModel: the
 * learn-language speech recognition, the sound effects, the best scores, and the saved word images
 * (sticker cutouts first).
 *
 * @param hasMicPermission whether the microphone permission is granted.
 */
fun XrLanguageTeacherViewModel.createVocabGameDependencies(
  hasMicPermission: () -> Boolean
): XrLanguageTeacherVocabGameDependencies =
  XrLanguageTeacherVocabGameDependencies(
    createSpeechRecognizer = { scope, learnLanguage, listener ->
      createGameSpeechRecognizer(
        scope = scope,
        learnLanguage = learnLanguage,
        listener = listener,
        hasMicPermission = hasMicPermission,
      )
    },
    createSoundEffects = { XrLanguageTeacherGameSoundPlayer(audioDispatcher = ioDispatcher) },
    highScores =
      object : XrLanguageTeacherGameHighScoreStore {
        override fun readHighScore(key: String): Int = storageRepository.getIntPreference(key)

        override fun writeHighScore(key: String, score: Int) {
          storageRepository.saveIntPreference(key, score)
        }
      },
    loadStickerImage = { word, learnLanguage ->
      loadSavedVocabImageForGameSprite(word, learnLanguage)?.asAndroidBitmap()
    },
    imageDispatcher = inferenceDispatcher,
    // The games prepared the shared ASR engine for the learn language; prepare it for the host
    // language again.
    onSessionEnded = { prepareAsrEngine() },
    // The teacher model would slow the game down; it is loaded again when the Camera tab opens.
    onRoundStarted = { releaseTeacherModelForGame() },
    onSpeakWord = { word, learnLanguage -> playGamePromptWord(word, learnLanguage) },
    onStopSpeech = { stopTtsPlayback() },
  )

/**
 * Pre-bakes the sticker sprites of the game deck [entries] into [XrLanguageTeacherVocabImageCache]
 * so they are ready on Frame 0 when the game round starts.
 */
internal suspend fun XrLanguageTeacherViewModel.prewarmGameStickerSprites(
  entries: List<DictionaryEntry>
) {
  if (entries.isEmpty()) return
  prepareGameStickers(
    entries = entries,
    loadStickerImage = { word, learnLanguage ->
      loadSavedVocabImageForGameSprite(word, learnLanguage)?.asAndroidBitmap()
    },
    imageDispatcher = inferenceDispatcher,
  )
}

/**
 * Loads the image of the saved [word] in [learnLanguage] (its sticker cutout, or else its photo)
 * decoded downsampled for a game sprite, without caching it: the prepared sprite is cached instead.
 */
private suspend fun XrLanguageTeacherViewModel.loadSavedVocabImageForGameSprite(
  word: String,
  learnLanguage: Language,
): ImageBitmap? =
  withContext(ioDispatcher) {
    decodeSavedVocabImage(word, learnLanguage, maxSidePx = GAME_STICKER_MAX_SIDE_PX)
  }

/** The preference key that remembers that the user declined the sticker model before a game. */
internal const val GAME_STICKER_DOWNLOAD_DECLINED_KEY =
  "xr_language_teacher_game_sticker_download_declined"

/** The study games that show the saved words as stickers. */
enum class XrLanguageTeacherVocabGame {
  VOCAB_POPPER,
  VOCAB_RUNNER,
}

/**
 * A study [game] in [language] that the player asked to start once its stickers are ready, with the
 * saved [entries] it can use. Once [prepareGame] got it ready, [entries] is the round's deck (at
 * most [GAME_MAX_DECK_SIZE] words), so the round uses exactly the words whose sprites were
 * pre-baked.
 */
data class XrLanguageTeacherVocabGameRequest(
  val game: XrLanguageTeacherVocabGame,
  val language: Language,
  val entries: List<DictionaryEntry> = emptyList(),
  val mode: XrLanguageTeacherVocabGameMode = XrLanguageTeacherVocabGameMode.TAP,
)

/**
 * Pre-warms sticker sprites and (for `Vocab Popper` Tap mode) prompt audio in the background as
 * soon as the Study game start screen opens, so tapping `Play game` starts immediately once cached.
 */
internal fun XrLanguageTeacherViewModel.prewarmVocabGame(
  request: XrLanguageTeacherVocabGameRequest
) {
  if (gameStickerPreparer.isPreparing || stickerModelDownload.hasPendingGameAction.value) return
  val previousPrewarmJob = vocabGamePrewarmJob
  cancelVocabGamePrewarm()
  val entriesToPrewarm = request.entries.take(GAME_MAX_DECK_SIZE)
  if (entriesToPrewarm.isEmpty()) return
  if (
    request.game == XrLanguageTeacherVocabGame.VOCAB_POPPER &&
      request.mode == XrLanguageTeacherVocabGameMode.TAP
  ) {
    // Free the TTS engine from idle Camera-tab filler synthesis right away on the start screen.
    cancelFillerPresynthesis()
  }
  val stopSignal = AtomicBoolean(false)
  vocabGamePrewarmStopSignal = stopSignal
  vocabGamePrewarmJob =
    viewModelScope.launch(ioDispatcher) {
      try {
        previousPrewarmJob?.cancelAndJoin()
        for (entry in entriesToPrewarm) {
          ensureActive()
          if (stopSignal.get()) return@launch
          prewarmGameStickerSprites(listOf(entry))
        }
        if (
          !stopSignal.get() &&
            request.game == XrLanguageTeacherVocabGame.VOCAB_POPPER &&
            request.mode == XrLanguageTeacherVocabGameMode.TAP
        ) {
          var activeService: TtsService? = null
          presynthesizeGamePromptWords(
            entries = entriesToPrewarm,
            learnLanguage = request.language,
            shouldStopEarly = { stopSignal.get() },
            onActiveServiceChanged = { service ->
              if (service != null) {
                activeService = service
                if (!stopSignal.get()) vocabGamePrewarmService = service
              } else if (vocabGamePrewarmService === activeService) {
                vocabGamePrewarmService = null
              }
            },
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(VOCAB_GAMES_TAG, "Background pre-warm on Study game start screen failed", e)
      }
    }
}

/**
 * Gets the study game of [request] ready, then publishes it as
 * [XrLanguageTeacherViewModel.readyVocabGame] for the Study tab to start: first offers to download
 * the sticker segmentation model if it isn't downloaded and the user hasn't declined it before (see
 * [XrLanguageTeacherStickerModelDownloadController]), cuts stickers out of the saved photos that
 * never were, picks the round's deck from the request's entries, and pre-bakes the deck's sticker
 * sprites so none is decoded mid-round. "Play without stickers" in the dialog gets the game ready
 * with the saved photos; Cancel and Back don't.
 *
 * The request lives in this ViewModel rather than in the screen, so a request still pending when
 * the start screen goes away never starts a game; see [cancelVocabGamePreparation].
 */
fun XrLanguageTeacherViewModel.prepareGame(request: XrLanguageTeacherVocabGameRequest) {
  _readyVocabGame.value = null
  vocabGamePrewarmStopSignal?.set(true)
  val inFlightPrewarmJob = vocabGamePrewarmJob
  stickerModelDownload.requireModel(XrLanguageTeacherStickerModelDownloadReason.GAME) {
    val readyRequest =
      request.copy(entries = pickGameDeck(request.entries, Random(System.nanoTime())))
    gameStickerPreparer.prepareThen(
      entries = readyRequest.entries,
      onAfterSpritesPrepared = {
        inFlightPrewarmJob?.join()
        if (
          readyRequest.game == XrLanguageTeacherVocabGame.VOCAB_POPPER &&
            readyRequest.mode == XrLanguageTeacherVocabGameMode.TAP
        ) {
          presynthesizeGamePromptWords(readyRequest.entries, readyRequest.language)
        }
      },
    ) {
      _readyVocabGame.value = readyRequest
    }
  }
}

/** Reports that the Study tab started the [XrLanguageTeacherViewModel.readyVocabGame]. */
fun XrLanguageTeacherViewModel.onVocabGameStarted() {
  _readyVocabGame.value = null
}

/**
 * Drops the game request of [prepareGame] when its start screen goes away: a request waiting for
 * the allowlist or the download dialog is forgotten (closing a confirmation without declining the
 * download), sprite pre-baking stops, and a ready game isn't started.
 */
fun XrLanguageTeacherViewModel.cancelVocabGamePreparation() {
  cancelVocabGamePrewarm()
  stickerModelDownload.cancelPendingAction(XrLanguageTeacherStickerModelDownloadReason.GAME)
  gameStickerPreparer.cancel()
  _readyVocabGame.value = null
}

/**
 * Offers to download the sticker segmentation model from the Study tab, then turns the saved photos
 * of every learn language into stickers. Also asks after the user declined it before a game.
 */
fun XrLanguageTeacherViewModel.convertToStickers() {
  stickerModelDownload.requireModel(XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS) {}
}

/**
 * Returns the [vocabGameEntryKey]s of the [entries] that have an image saved (a sticker cutout or a
 * photo), which `Vocab Runner` needs for its obstacles. Reads the disk on [ioDispatcher].
 */
suspend fun XrLanguageTeacherViewModel.findVocabGameEntryKeysWithImages(
  entries: List<DictionaryEntry>
): Set<String> =
  withContext(ioDispatcher) {
    dictionaryFileMutex.withLock {
      entries
        .filter { entry -> hasVocabSticker(entry) || hasVocabPhoto(entry) }
        .map { it.vocabGameEntryKey() }
        .toSet()
    }
  }
