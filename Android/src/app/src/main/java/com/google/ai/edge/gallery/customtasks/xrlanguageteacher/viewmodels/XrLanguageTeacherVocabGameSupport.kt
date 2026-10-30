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

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabImageCache
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabImageKind
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSoundEffects
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechListener
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameSpeechRecognizer
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persists the best score of each study game. */
interface XrLanguageTeacherGameHighScoreStore {
  /** Returns the best score saved under [key], or 0. */
  fun readHighScore(key: String): Int

  /** Saves [score] as the best score under [key]. */
  fun writeHighScore(key: String, score: Int)
}

/**
 * What the `Vocab Popper` and `Vocab Runner` ViewModels need from the rest of the app.
 *
 * @param createSpeechRecognizer creates the continuous speech recognition of a game round for the
 *   learn language, delivering its events to the listener on the given scope.
 * @param createSoundEffects creates the game's sound effects; called once per ViewModel.
 * @param highScores persists the best scores.
 * @param loadStickerImage loads the image of a saved word (its sticker cutout, or else its photo)
 *   for a sprite at most [GAME_STICKER_MAX_SIDE_PX] large, or returns `null` when the word has no
 *   image.
 * @param imageDispatcher dispatcher on which word images are prepared as game sprites.
 * @param onSessionEnded called when a game screen closes, so the lesson can prepare speech
 *   recognition for the host language again.
 * @param onRoundStarted called whenever a round starts (also on replay), so the teacher model can
 *   be unloaded while the game runs.
 */
class XrLanguageTeacherVocabGameDependencies(
  val createSpeechRecognizer:
    (
      scope: CoroutineScope, learnLanguage: Language, listener: XrLanguageTeacherGameSpeechListener,
    ) -> XrLanguageTeacherGameSpeechRecognizer,
  val createSoundEffects: () -> XrLanguageTeacherGameSoundEffects,
  val highScores: XrLanguageTeacherGameHighScoreStore,
  val loadStickerImage: suspend (word: String, learnLanguage: Language) -> Bitmap?,
  val imageDispatcher: CoroutineDispatcher,
  val onSessionEnded: () -> Unit = {},
  val onRoundStarted: () -> Unit = {},
)

/** Longest frame step fed to a game engine, so a stalled frame never teleports the sprites. */
internal const val GAME_MAX_FRAME_STEP_MS = 100L

/** How long the celebration of a popped or cleared word stays in the status bar. */
internal const val GAME_CELEBRATION_DURATION_MS = 1_600L

/** How fast the screen edge glow of a popped, cleared, or bumped word fades, per second. */
internal const val GAME_EDGE_GLOW_DECAY_PER_SECOND = 2.2f

/** Edge glow below which the glow is hidden. */
private const val GAME_EDGE_GLOW_MIN_VISIBLE = 0.02f

/** Most words a game round uses (and prepares sprites for). */
internal const val GAME_MAX_DECK_SIZE = 30

/** Longest side, in pixels, of a word image prepared as a game sprite. */
internal const val GAME_STICKER_MAX_SIDE_PX = 256

/**
 * Returns the key the study games identify a saved word by: the word's language-scoped image file
 * name, which is also how its sticker cutout is stored.
 */
fun DictionaryEntry.vocabGameEntryKey(): String =
  XrLanguageTeacherVocabImageCache.entryKey(word, learnLanguage)

/**
 * Tracks the time between game frames and turns frame timestamps into engine steps, clamped to
 * `0..`[GAME_MAX_FRAME_STEP_MS] and rounded to whole milliseconds.
 */
internal class XrLanguageTeacherGameFrameClock {
  private var lastFrameNanos: Long? = null

  /** Forgets the last frame, so the next frame only sets the time base. */
  fun reset() {
    lastFrameNanos = null
  }

  /** Returns the step, in milliseconds, from the previous frame to the frame at [frameNanos]. */
  fun advance(frameNanos: Long): Long {
    val previous = lastFrameNanos
    lastFrameNanos = frameNanos
    if (previous == null) return 0L
    val deltaMillis = (frameNanos - previous) / 1_000_000.0
    return deltaMillis.coerceIn(0.0, GAME_MAX_FRAME_STEP_MS.toDouble()).roundToLong()
  }
}

/** Returns the edge glow after [deltaMilliseconds] of fading from [intensity]. */
internal fun decayEdgeGlow(intensity: Float, deltaMilliseconds: Long): Float {
  if (intensity <= 0f) return 0f
  val next = max(0f, intensity - deltaMilliseconds / 1_000f * GAME_EDGE_GLOW_DECAY_PER_SECOND)
  return if (next < GAME_EDGE_GLOW_MIN_VISIBLE) 0f else next
}

/** Returns the whole seconds left in [remainingMilliseconds], rounded up, for the countdown. */
internal fun remainingSecondsFor(remainingMilliseconds: Long): Int =
  max(0, ceil(remainingMilliseconds / 1_000.0).toInt())

/**
 * Returns at most [GAME_MAX_DECK_SIZE] of [entries], picked at random with [random], so a game's
 * sprites stay within a few megabytes however many words are saved.
 */
internal fun <T> pickGameDeck(entries: List<T>, random: Random): List<T> =
  if (entries.size <= GAME_MAX_DECK_SIZE) entries
  else entries.shuffled(random).take(GAME_MAX_DECK_SIZE)

/**
 * Returns the game sprites of [entries] that are already in [XrLanguageTeacherVocabImageCache] (as
 * [XrLanguageTeacherVocabImageKind.GAME_SPRITE]s of the words' current images), keyed by
 * [vocabGameEntryKey].
 */
internal fun cachedGameStickers(entries: List<DictionaryEntry>): Map<String, ImageBitmap> =
  buildMap {
    for (entry in entries) {
      val entryKey = entry.vocabGameEntryKey()
      val cached = cachedGameSticker(entryKey, XrLanguageTeacherVocabImageCache.version(entryKey))
      if (cached != null) put(entryKey, cached)
    }
  }

/** Returns the game sprite of the saved word [entryKey] cached at [version], or `null`. */
private fun cachedGameSticker(entryKey: String, version: Int): ImageBitmap? =
  XrLanguageTeacherVocabImageCache.get(
    entryKey = entryKey,
    version = version,
    kind = XrLanguageTeacherVocabImageKind.GAME_SPRITE,
    maxSidePx = GAME_STICKER_MAX_SIDE_PX,
  )

/**
 * Loads the images of [entries] with [loadStickerImage] one at a time (or reuses sprites already in
 * [XrLanguageTeacherVocabImageCache], shared across rounds and games) and prepares each as a game
 * sprite with a baked-in white sticker outline, caching it and handing it to [onStickerReady] under
 * its [vocabGameEntryKey] as soon as it is ready. Words without an image are skipped. A sprite
 * whose word's images changed while it was prepared is used but not cached.
 */
internal suspend fun prepareGameStickers(
  entries: List<DictionaryEntry>,
  loadStickerImage: suspend (word: String, learnLanguage: Language) -> Bitmap?,
  imageDispatcher: CoroutineDispatcher,
  onStickerReady: (entryKey: String, sticker: ImageBitmap) -> Unit = { _, _ -> },
) {
  for (entry in entries) {
    currentCoroutineContext().ensureActive()
    val entryKey = entry.vocabGameEntryKey()
    val version = XrLanguageTeacherVocabImageCache.version(entryKey)
    val cached = cachedGameSticker(entryKey, version)
    if (cached != null) {
      onStickerReady(entryKey, cached)
      continue
    }
    val source = loadStickerImage(entry.word, entry.learnLanguage) ?: continue
    val sticker =
      withContext(imageDispatcher) {
        XrLanguageTeacherStickerImages.prepareStickerImage(
            source = source,
            maxSidePx = GAME_STICKER_MAX_SIDE_PX,
          )
          .asImageBitmap()
      }
    currentCoroutineContext().ensureActive()
    XrLanguageTeacherVocabImageCache.put(
      entryKey = entryKey,
      version = version,
      kind = XrLanguageTeacherVocabImageKind.GAME_SPRITE,
      maxSidePx = GAME_STICKER_MAX_SIDE_PX,
      image = sticker,
    )
    onStickerReady(entryKey, sticker)
  }
}

/**
 * Hands the shared ASR engine back to the lesson after a game session: calls
 * [XrLanguageTeacherVocabGameDependencies.onSessionEnded] only once the game's recognizer is torn
 * down, so preparing the engine for the host language never races with the game still cancelling
 * its recording.
 */
internal class XrLanguageTeacherGameSessionTeardown(
  private val scope: CoroutineScope,
  private val dependencies: XrLanguageTeacherVocabGameDependencies,
) {
  /** Calls `onSessionEnded` after [recognizerStopJob] completes, or right away without one. */
  fun restoreLessonSpeechAfter(recognizerStopJob: Job?) {
    if (recognizerStopJob == null || !scope.isActive) {
      // Without a running scope (the ViewModel is being cleared) there is nothing left to wait on.
      dependencies.onSessionEnded()
      return
    }
    scope.launch {
      recognizerStopJob.join()
      dependencies.onSessionEnded()
    }
  }
}
