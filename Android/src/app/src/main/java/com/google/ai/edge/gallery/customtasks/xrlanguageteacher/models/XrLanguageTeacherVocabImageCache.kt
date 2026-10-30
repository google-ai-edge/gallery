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

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.ai.edge.gallery.proto.Language
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How many bytes of decoded and prepared word images [XrLanguageTeacherVocabImageCache] keeps. */
private const val VOCAB_IMAGE_CACHE_MAX_BYTES = 32 * 1024 * 1024

/** What an image cached in [XrLanguageTeacherVocabImageCache] for a saved word is. */
enum class XrLanguageTeacherVocabImageKind {
  /** The word's sticker cutout or photo, decoded for the vocabulary cards and flashcards. */
  THUMBNAIL,
  /** A sticker with its white outline baked in (see [XrLanguageTeacherStickerImages]). */
  OUTLINED,
  /** The sprite a study game draws for the word. */
  GAME_SPRITE,
}

/**
 * The process-wide memory cache of the saved words' decoded thumbnails, outlined stickers and game
 * sprites, shared by the screens and the study games, at most 32 MiB of pixels.
 *
 * The images of a saved word are cached under its [entryKey] and its version, which [invalidate]
 * bumps whenever the word's images change on disk, so an image decoded before a change is never
 * cached for, or served after, it. That includes the outlined sticker of a cached thumbnail
 * ([getOutlined] / [putOutlined]). Only the outlined stickers of bitmaps that aren't a saved word's
 * cached thumbnail (e.g. the current camera session's cutout) are cached under their source bitmap,
 * which the cache then keeps alive.
 */
object XrLanguageTeacherVocabImageCache {

  /**
   * Returns the key that [XrLanguageTeacherVocabImageCache] identifies the saved [word] in
   * [learnLanguage] by: its language-scoped image file name, which is also how its photo and
   * sticker cutout are stored.
   */
  fun entryKey(word: String, learnLanguage: Language): String =
    XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word, learnLanguage)

  /** Identifies an image cached for a saved word. */
  private data class EntryImageKey(
    val entryKey: String,
    val version: Int,
    val kind: XrLanguageTeacherVocabImageKind,
    val maxSidePx: Int,
  )

  /** Identifies the outlined sticker of an unsaved [source] bitmap. */
  private class SourceImageKey(val source: Bitmap) {
    override fun equals(other: Any?): Boolean = other is SourceImageKey && other.source === source

    override fun hashCode(): Int = System.identityHashCode(source)
  }

  /**
   * A cached [image] and the bytes it counts for, recorded when it was cached (a bitmap recycled
   * later reports 0 bytes, which would corrupt the cache's size).
   */
  private class CachedImage(val image: ImageBitmap, val byteCount: Int)

  private val lock = Any()

  /**
   * The key of each cached thumbnail by its bitmap, so the outlined sticker of a thumbnail is
   * cached under the thumbnail's word and version. Guarded by [lock].
   */
  private val thumbnailKeys = IdentityHashMap<Bitmap, EntryImageKey>()

  // Every change to the cache happens under [lock], so [entryRemoved] runs under it too.
  private val cache =
    object : LruCache<Any, CachedImage>(VOCAB_IMAGE_CACHE_MAX_BYTES) {
      override fun sizeOf(key: Any, value: CachedImage): Int = value.byteCount

      override fun entryRemoved(
        evicted: Boolean,
        key: Any,
        oldValue: CachedImage,
        newValue: CachedImage?,
      ) {
        if (key is EntryImageKey && key.kind == XrLanguageTeacherVocabImageKind.THUMBNAIL) {
          val bitmap = oldValue.image.asAndroidBitmap()
          if (thumbnailKeys[bitmap] == key) thumbnailKeys.remove(bitmap)
        }
      }
    }

  private val _versions = MutableStateFlow<Map<String, Int>>(emptyMap())
  /**
   * The version of each saved word's images by [entryKey], bumped by [invalidate]; words that never
   * changed are at version 0. Versions only grow, so they never repeat.
   */
  val versions: StateFlow<Map<String, Int>> = _versions.asStateFlow()

  /** Returns the current version of the images of the saved word [entryKey]. */
  fun version(entryKey: String): Int = _versions.value[entryKey] ?: 0

  /**
   * Returns the [kind] image of the saved word [entryKey] prepared for [maxSidePx], or loads it
   * with [load] and caches it, unless the word's images changed while it loaded. [load] runs
   * outside the cache's lock (it may suspend when called from a coroutine); only the lookups and
   * the insert take the lock.
   */
  inline fun getOrLoad(
    entryKey: String,
    kind: XrLanguageTeacherVocabImageKind,
    maxSidePx: Int,
    load: () -> ImageBitmap?,
  ): ImageBitmap? {
    val version = version(entryKey)
    get(entryKey = entryKey, version = version, kind = kind, maxSidePx = maxSidePx)?.let {
      return it
    }
    val loaded = load() ?: return null
    put(entryKey = entryKey, version = version, kind = kind, maxSidePx = maxSidePx, image = loaded)
    return loaded
  }

  /**
   * Returns the [kind] image of the saved word [entryKey] at [version] prepared for [maxSidePx], or
   * `null` when it isn't cached.
   */
  fun get(
    entryKey: String,
    version: Int,
    kind: XrLanguageTeacherVocabImageKind,
    maxSidePx: Int,
  ): ImageBitmap? = cache.get(EntryImageKey(entryKey, version, kind, maxSidePx))?.image

  /**
   * Caches [image] as the [kind] image of the saved word [entryKey] prepared for [maxSidePx],
   * loaded at [version]. Does nothing if the word's images changed since then.
   */
  fun put(
    entryKey: String,
    version: Int,
    kind: XrLanguageTeacherVocabImageKind,
    maxSidePx: Int,
    image: ImageBitmap,
  ) {
    val key = EntryImageKey(entryKey, version, kind, maxSidePx)
    val bitmap = image.asAndroidBitmap()
    synchronized(lock) {
      if (version(entryKey) != version) return
      cache.put(key, CachedImage(image, bitmap.allocationByteCount))
      if (kind == XrLanguageTeacherVocabImageKind.THUMBNAIL) thumbnailKeys[bitmap] = key
    }
  }

  /**
   * Returns the outlined sticker cached for [source] (see [putOutlined]), or `null` when it isn't
   * cached.
   */
  fun getOutlined(source: Bitmap): ImageBitmap? =
    synchronized(lock) { cache.get(outlinedKey(source))?.image }

  /**
   * Caches [outlined] as the outlined sticker of [source]: under the saved word and version of
   * [source] when it is a cached thumbnail (so [invalidate] drops it), or else under [source]
   * itself, which the cache keeps alive (and counts) until the entry is evicted.
   */
  fun putOutlined(source: Bitmap, outlined: ImageBitmap) {
    val outlinedBitmap = outlined.asAndroidBitmap()
    synchronized(lock) {
      val key = outlinedKey(source)
      val sourceBytes =
        if (key is SourceImageKey && source !== outlinedBitmap) source.allocationByteCount else 0
      cache.put(key, CachedImage(outlined, outlinedBitmap.allocationByteCount + sourceBytes))
    }
  }

  /** Returns the key of the outlined sticker of [source]. Call under [lock]. */
  private fun outlinedKey(source: Bitmap): Any {
    val thumbnailKey = thumbnailKeys[source] ?: return SourceImageKey(source)
    return thumbnailKey.copy(
      kind = XrLanguageTeacherVocabImageKind.OUTLINED,
      maxSidePx = maxOf(source.width, source.height),
    )
  }

  /** Drops every cached image, keeping the versions (so nothing cached before is served again). */
  fun clear() {
    synchronized(lock) {
      cache.evictAll()
      thumbnailKeys.clear()
    }
  }

  /**
   * Drops every image cached for the saved word [entryKey] (including the outlined stickers of its
   * thumbnails) and bumps its version, because its images changed (or it was deleted). Call after
   * the change is written.
   */
  fun invalidate(entryKey: String) {
    synchronized(lock) {
      _versions.update { versions -> versions + (entryKey to (versions[entryKey] ?: 0) + 1) }
      for (key in cache.snapshot().keys) {
        if (key is EntryImageKey && key.entryKey == entryKey) {
          cache.remove(key)
        }
      }
    }
  }
}
