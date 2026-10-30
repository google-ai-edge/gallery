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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Releases and reloads the teacher (Gemma) model for [XrLanguageTeacherTeacherModelSuspension]. */
interface XrLanguageTeacherTeacherModelHost {
  /**
   * Stops all lesson work on the teacher model and releases its engine, returning once it is
   * released.
   */
  suspend fun release()

  /** Reloads the teacher model, returning once it is ready or failed to load. */
  suspend fun initialize()
}

/**
 * Unloads the teacher model while a Study game runs, so its engine doesn't compete with the game
 * for the GPU, CPU, and memory, and loads it again once a screen that needs it opens.
 *
 * [releaseForGame] is called when a game round starts (also on replay); [restoreIfReleased] when
 * the Camera tab (or anything else that runs lessons) opens. Releases and reloads run one at a time
 * in request order against the latest wanted state, so quick back-and-forth never overlaps them, a
 * request that is already satisfied does nothing, and the model is only reloaded if a game released
 * it.
 */
class XrLanguageTeacherTeacherModelSuspension(private val scope: CoroutineScope) {
  private val mutex = Mutex()

  /** Whether the latest request wants the model released. */
  @Volatile private var isReleaseWanted = false

  /** Whether the model is released (only changed under [mutex]). */
  @Volatile private var isReleased = false

  /** The host that releases and reloads the model, attached while the feature's screen is shown. */
  @Volatile
  var host: XrLanguageTeacherTeacherModelHost? = null
    set(value) {
      field = value
      if (value != null && isReleaseWanted != isReleased) {
        reconcileLater()
      }
    }

  /** Whether a game released (or is releasing) the model, so lessons need it reloaded first. */
  val isReleasedForGame: Boolean
    get() = isReleaseWanted || isReleased

  /** Releases the teacher model for a game round, unless it is already released. */
  fun releaseForGame() {
    isReleaseWanted = true
    reconcileLater()
  }

  /** Reloads the teacher model if a game released it; does nothing otherwise. */
  fun restoreIfReleased() {
    if (!isReleasedForGame) return
    isReleaseWanted = false
    reconcileLater()
  }

  private fun reconcileLater() {
    scope.launch { mutex.withLock { reconcile() } }
  }

  private suspend fun reconcile() {
    val currentHost = host ?: return
    when {
      isReleaseWanted && !isReleased -> {
        currentHost.release()
        isReleased = true
      }
      !isReleaseWanted && isReleased -> {
        currentHost.initialize()
        isReleased = false
      }
    }
  }
}

private const val MODEL_RELEASE_TIMEOUT_MS = 15_000L

/**
 * Releases and reloads the teacher [model] through the feature's existing model lifecycle: lesson
 * work is stopped through the lesson controller, the engine is cleaned up through
 * [ModelManagerViewModel.cleanupModel], and it is reloaded through
 * [ModelManagerViewModel.initializeModel], the same path the screen uses on first entry, so the
 * Camera tab shows its usual loading state meanwhile. Speech recognition and TTS are untouched.
 *
 * Pass the application [context]: the ViewModel's suspension holds this host, so it must not keep
 * an activity alive.
 */
class XrLanguageTeacherModelManagerTeacherModelHost(
  private val viewModel: XrLanguageTeacherViewModel,
  private val modelManagerViewModel: ModelManagerViewModel,
  private val context: Context,
  private val task: Task,
  private val model: () -> Model,
) : XrLanguageTeacherTeacherModelHost {
  override suspend fun release() {
    val target = model()
    withContext(Dispatchers.Main) { viewModel.stopLessonForTeacherModelRelease(target) }
    val released =
      withTimeoutOrNull(MODEL_RELEASE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
          modelManagerViewModel.cleanupModel(
            context = context,
            task = task,
            model = target,
            onDone = { if (continuation.isActive) continuation.resume(Unit) },
          )
        }
      }
    if (released == null) Log.w(TAG, "Releasing the teacher model for a game timed out")
  }

  override suspend fun initialize() {
    val target = model()
    withContext(Dispatchers.Main) {
      modelManagerViewModel.initializeModel(context = context, task = task, model = target)
    }
    target.initStatusFlow.first {
      it is Model.InitializationStatus.Initialized || it is Model.InitializationStatus.Failed
    }
  }
}

/**
 * Stops all lesson work on [model] before a game releases it: cancels any in-flight lesson turn,
 * card extraction, and conversation pre-warm (stopping native generation), and ends the camera
 * lesson session without pre-warming a new conversation. Speech recognition and TTS engines stay
 * prepared.
 */
internal fun XrLanguageTeacherViewModel.stopLessonForTeacherModelRelease(model: Model) {
  resetLessonSessionInternal(model = model, prewarmSession = false)
}
