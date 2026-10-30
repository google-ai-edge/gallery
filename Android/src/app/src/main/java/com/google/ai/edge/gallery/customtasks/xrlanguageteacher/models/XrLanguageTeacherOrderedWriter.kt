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

import android.util.Log
import com.google.ai.edge.gallery.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val TAG = "AGTeacherOrderedWriter"

/**
 * Runs the saved-vocabulary writes one at a time, in the order they were requested, on [scope] (the
 * application scope, so that a write requested right before the screen closes still lands).
 *
 * Saving a word and then unsaving it right away therefore always ends with the word unsaved, even
 * though both writes run in the background.
 *
 * Writes are reentrant: a write that runs another one through [run] (e.g. saving a card, which then
 * saves the card's latest sticker) runs it right away instead of queueing it behind itself.
 *
 * If [scope] is cancelled, the writes still queued are dropped and their [run] callers are
 * cancelled.
 */
internal class XrLanguageTeacherOrderedWriter(private val scope: CoroutineScope) {

  /** A queued write, and the [result] that [run] waits for, if any. */
  private class Task(val block: suspend () -> Unit, val result: CompletableDeferred<Unit>?)

  /** Marks the coroutine context of the writes this writer runs, to make [run] reentrant. */
  private class RunningWrite(val writer: XrLanguageTeacherOrderedWriter) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunningWrite>
  }

  private val tasks = Channel<Task>(Channel.UNLIMITED)

  init {
    scope
      .launch {
        for (task in tasks) {
          try {
            withContext(RunningWrite(this@XrLanguageTeacherOrderedWriter)) { task.block() }
            task.result?.complete(Unit)
          } catch (e: CancellationException) {
            task.result?.cancel(e)
            // Only the scope's cancellation stops the writer; a write that was cancelled by itself
            // doesn't stop the writes queued after it.
            if (!isActive) throw e
            Log.w(TAG, "A vocabulary write was cancelled", e)
          } catch (e: Throwable) {
            // Also catches errors, so that the caller of [run] never waits forever and the writes
            // queued after this one still run.
            Log.e(TAG, "A vocabulary write failed", e)
            task.result?.completeExceptionally(e)
          }
        }
      }
      .invokeOnCompletion { cause ->
        // The writer was closed or its scope cancelled: fail the writes still queued (only after a
        // cancellation) so that nothing waits for them forever.
        tasks.close(cause)
        while (true) {
          val task = tasks.tryReceive().getOrNull() ?: break
          task.result?.cancel(CancellationException("The vocabulary writer stopped", cause))
        }
      }
  }

  /** Queues [block] to run after every write requested before it, without waiting for it. */
  fun enqueue(block: suspend () -> Unit) {
    val sent = tasks.trySend(Task(block = block, result = null))
    if (sent.isFailure) {
      Log.w(TAG, "Dropped a vocabulary write requested after the writer stopped")
    }
  }

  /**
   * Stops accepting writes. The writes already requested still run, then the writer's coroutine
   * ends. Writes requested afterwards through [run] run right away, and through [enqueue] are
   * dropped. The app's shared writer (see [XrLanguageTeacherVocabularyWrites]) is never closed.
   */
  fun close() {
    tasks.close()
  }

  /**
   * Runs [block] after every write requested before it and returns its result. Inside a write of
   * this writer, runs [block] right away (it is already ordered).
   */
  suspend fun <T> run(block: suspend () -> T): T {
    if (currentCoroutineContext()[RunningWrite]?.writer === this) return block()
    var value: Result<T>? = null
    val result = CompletableDeferred<Unit>()
    val sent = tasks.trySend(Task(block = { value = Result.success(block()) }, result = result))
    if (sent.isFailure) {
      // The writer stopped: write directly rather than not at all.
      return block()
    }
    result.await()
    return checkNotNull(value).getOrThrow()
  }
}

/**
 * The app-wide ordering of the saved-vocabulary writes: one [writer] and one [fileMutex] shared by
 * every AR Language Teacher ViewModel, so that writes of a ViewModel that is being cleared and of
 * the next one (e.g. after leaving and reopening the task) never interleave.
 */
@Singleton
class XrLanguageTeacherVocabularyWrites
@Inject
constructor(@ApplicationScope applicationScope: CoroutineScope) {
  /** Runs the saved-vocabulary writes one at a time, in request order. */
  internal val writer = XrLanguageTeacherOrderedWriter(applicationScope)

  /** Guards the saved words' image files and dictionary reads against the writes. */
  internal val fileMutex = Mutex()
}
