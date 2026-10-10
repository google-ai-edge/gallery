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
import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/** Grid thumbnail: MediaStore keeps these cached, and it covers videos without a decoder. */
data class MediaThumb(val uri: Uri, val px: Int = 384)

private class MediaThumbFetcher(
  private val context: Context,
  private val thumb: MediaThumb,
) : Fetcher {
  override suspend fun fetch(): FetchResult {
    val bitmap = context.contentResolver.loadThumbnail(thumb.uri, Size(thumb.px, thumb.px), null)
    return DrawableResult(
      drawable = bitmap.toDrawable(context.resources),
      isSampled = true,
      dataSource = DataSource.DISK,
    )
  }

  class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
    override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
      MediaThumbFetcher(context, data)
  }
}

private var sharedLoader: ImageLoader? = null

/** One loader for the whole app, so the memory cache is shared between screens. */
fun galleryImageLoader(context: Context): ImageLoader =
  sharedLoader
    ?: ImageLoader.Builder(context.applicationContext)
      .components { add(MediaThumbFetcher.Factory(context.applicationContext)) }
      .crossfade(true)
      .build()
      .also { sharedLoader = it }

@Composable
fun rememberGalleryImageLoader(): ImageLoader {
  val context = LocalContext.current
  return remember { galleryImageLoader(context) }
}
