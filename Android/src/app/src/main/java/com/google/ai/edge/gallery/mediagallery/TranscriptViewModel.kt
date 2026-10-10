/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.ai.edge.gallery.morgenschiss.ApiResult
import com.google.ai.edge.gallery.morgenschiss.MediaSearchApi
import com.google.ai.edge.gallery.morgenschiss.Sentence
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface TranscriptState {
  data object Idle : TranscriptState

  /** step: what happens right now, progress 0..1 or null when unknown. */
  data class Working(val step: String, val progress: Float?) : TranscriptState

  data class Done(val sentences: List<Sentence>) : TranscriptState

  data class Failed(val message: String) : TranscriptState
}

private const val MAX_UPLOAD_BYTES = 60L * 1024 * 1024

/** Transcribes a video's sound with Parakeet on morgenschiss; results stay while the app runs. */
@HiltViewModel
class TranscriptViewModel
@Inject
constructor(@ApplicationContext private val context: Context, private val api: MediaSearchApi) : ViewModel() {
  private val _state = MutableStateFlow<TranscriptState>(TranscriptState.Idle)
  val state: StateFlow<TranscriptState> = _state.asStateFlow()
  private var currentId: Long? = null

  val available: Boolean
    get() = api.client.isLoggedIn

  fun show(item: MediaItem) {
    if (currentId == item.id) return
    currentId = item.id
    _state.value = cache[item.id]?.let { TranscriptState.Done(it) } ?: TranscriptState.Idle
  }

  fun start(item: MediaItem) {
    if (_state.value is TranscriptState.Working) return
    launchSafely {
      _state.value = TranscriptState.Working("Tonspur wird vorbereitet", null)
      val audio = File(context.cacheDir, "transcript-${item.id}.m4a")
      try {
        if (!extractAudio(item, audio)) return@launchSafely fail("Das Video hat keine Tonspur, die sich lesen lässt.")
        if (audio.length() > MAX_UPLOAD_BYTES) return@launchSafely fail("Die Tonspur ist zu lang (höchstens etwa eine Stunde).")
        _state.value = TranscriptState.Working("Wird hochgeladen", null)
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
        val job =
          when (val r = api.transcribe(bytes)) {
            is ApiResult.Ok -> r.value.jobId
            else -> return@launchSafely fail(errorText(r))
          }
        while (true) {
          delay(2_500)
          when (val r = api.transcribeStatus(job)) {
            is ApiResult.Ok -> {
              val st = r.value
              when (st.status) {
                "done" -> {
                  cache[item.id] = st.sentences
                  if (currentId == item.id) _state.value = TranscriptState.Done(st.sentences)
                  return@launchSafely
                }
                "error" ->
                  return@launchSafely fail(
                    if (st.error == "stt_unavailable") "Der Mac ist gerade nicht da, später nochmal."
                    else "Das Transkribieren hat nicht geklappt."
                  )
                else -> if (currentId == item.id) _state.value = TranscriptState.Working("Wird transkribiert", st.progress.toFloat())
              }
            }
            is ApiResult.Unavailable -> {} // brief network hiccup: keep polling
            else -> return@launchSafely fail(errorText(r))
          }
        }
      } finally {
        audio.delete()
      }
    }
  }

  private fun fail(message: String) {
    _state.value = TranscriptState.Failed(message)
  }

  private fun errorText(r: ApiResult<*>): String =
    when (r) {
      is ApiResult.LoggedOut -> "Nicht bei morgenschiss angemeldet."
      is ApiResult.NoAccess -> "Dein Konto hat keinen Zugriff auf die Mediensuche."
      is ApiResult.Unavailable -> "morgenschiss ist nicht erreichbar."
      is ApiResult.Busy -> "Es läuft schon ein Transkript, bitte warten."
      is ApiResult.Failed -> if (r.code == "too_long") "Die Tonspur ist länger als eine Stunde." else "Das hat nicht geklappt (${r.code})."
      else -> "Das hat nicht geklappt."
    }

  /** Audio only, AAC in MP4: small enough to upload (about 1 MB per minute). */
  @OptIn(UnstableApi::class)
  private suspend fun extractAudio(item: MediaItem, out: File): Boolean =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        val transformer =
          Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
              object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                  if (cont.isActive) cont.resume(out.length() > 0)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                  if (cont.isActive) cont.resume(false)
                }
              }
            )
            .build()
        val edited = EditedMediaItem.Builder(ExoMediaItem.fromUri(item.uri)).setRemoveVideo(true).build()
        transformer.start(edited, out.absolutePath)
        cont.invokeOnCancellation { transformer.cancel() }
      }
    }

  private fun launchSafely(block: suspend () -> Unit) =
    viewModelScope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        fail("Das hat nicht geklappt: ${e.message}")
      }
    }

  companion object {
    private val cache = HashMap<Long, List<Sentence>>()
  }
}
