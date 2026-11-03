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
import com.google.ai.edge.gallery.proto.DictionaryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pre-bakes the game sticker sprites of a study game's deck before the game starts, so that they
 * are ready on the first frame and no sprite is decoded mid-round. (The saved photos are cut out
 * into stickers before, in the sticker model download dialog; see
 * [XrLanguageTeacherStickerModelDownloadController].)
 *
 * @param prepareGameSprites pre-bakes the sticker sprites of the deck's saved words into the shared
 *   image cache before `onReady` opens the game screen.
 */
class XrLanguageTeacherGameStickerPreparer(
  private val scope: CoroutineScope,
  private val prepareGameSprites: suspend (List<DictionaryEntry>) -> Unit,
) {
  @Volatile private var job: Job? = null
  private var runToken = 0L
  private val _isPreparing = MutableStateFlow(false)

  /** Reactive state of whether a Study game preparation job is currently active. */
  val isPreparingFlow: StateFlow<Boolean> = _isPreparing.asStateFlow()

  /** Whether a Study game preparation job is currently active. */
  val isPreparing: Boolean
    get() = _isPreparing.value || job?.isActive == true

  /**
   * Pre-bakes the game sticker sprites of the deck [entries] and runs [onAfterSpritesPrepared]
   * (such as pre-synthesizing `Vocab Popper` Tap prompt audio), then calls [onReady]. Failures
   * still start the game, which then decodes its sprites or synthesizes prompts as needed. Ignored
   * while a preparation is already running.
   */
  fun prepareThen(
    entries: List<DictionaryEntry>,
    onAfterSpritesPrepared: suspend () -> Unit = {},
    onReady: () -> Unit,
  ) {
    if (isPreparing) return
    val token = ++runToken
    _isPreparing.value = true
    job = scope.launch {
      try {
        try {
          prepareGameSprites(entries)
          onAfterSpritesPrepared()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Failed pre-round preparation before a study game", e)
        }
        onReady()
      } finally {
        if (runToken == token) {
          _isPreparing.value = false
        }
      }
    }
  }

  /** Stops preparing without starting the game. */
  fun cancel() {
    runToken++
    job?.cancel()
    job = null
    _isPreparing.value = false
  }

  companion object {
    private const val TAG = "AGXrLanguageTeacherStickerPrep"
  }
}
