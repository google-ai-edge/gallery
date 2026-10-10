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

package com.google.ai.edge.gallery.morgenschiss

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The sound of a video as AAC in MP4 (about 1 MB per minute), for Parakeet on morgenschiss. */
object AudioExtractor {
  /** False when the video has no readable sound track. Transformer needs the main thread. */
  @OptIn(UnstableApi::class)
  suspend fun extract(context: Context, uri: Uri, out: File): Boolean =
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
        transformer.start(EditedMediaItem.Builder(ExoMediaItem.fromUri(uri)).setRemoveVideo(true).build(), out.absolutePath)
        cont.invokeOnCancellation { transformer.cancel() }
      }
    }
}
