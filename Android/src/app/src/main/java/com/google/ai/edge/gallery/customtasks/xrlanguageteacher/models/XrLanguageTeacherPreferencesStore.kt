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

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGTeacherStorageRepo"
internal const val PERSONA_PREFS_NAME = "xr_language_teacher_persona_prefs"
internal const val PROMPT_KEY_PREFIX = "xr_teacher_persona_prompt_"
internal const val SYSTEM_PROMPT_OVERRIDE_KEY = "xr_teacher_system_prompt_override"
private const val SESSION_IMAGES_DIR_NAME = "xr_language_teacher_session_images"
/** Suffix of the temporary file an image is written to before it is renamed into place. */
private const val TEMP_FILE_SUFFIX = ".tmp"

/**
 * Returns the file name of the transparent PNG sticker cutout saved next to the original photo of
 * the word saved as [safeFileName].
 */
internal fun vocabStickerFileName(safeFileName: String): String = "${safeFileName}_sticker.png"

/**
 * Returns the largest power-of-two `BitmapFactory.Options.inSampleSize` that decodes a [width] x
 * [height] image with its longest side still at least [maxSidePx], or 1 when the size is unknown.
 */
internal fun vocabImageSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
  val longestSide = maxOf(width, height)
  if (longestSide <= 0 || maxSidePx <= 0) return 1
  var sampleSize = 1
  while (longestSide / (sampleSize * 2) >= maxSidePx) {
    sampleSize *= 2
  }
  return sampleSize
}

/**
 * Encapsulates Android [Context]-backed storage and string resources for the XR Language Teacher
 * task so `XrLanguageTeacherViewModel` remains free of platform [Context] references.
 */
@Singleton
class XrLanguageTeacherPreferencesStore
@Inject
constructor(@ApplicationContext private val context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences(PERSONA_PREFS_NAME, Context.MODE_PRIVATE)

  private val imagesDir: File by lazy {
    File(context.filesDir, "xr_language_teacher_images").apply {
      if (!exists()) {
        mkdirs()
      }
    }
  }

  /**
   * Session snapshot images, kept in permanent app storage so Android does not evict them. Older
   * app versions saved them in `cacheDir`; those keep loading from their stored absolute paths.
   */
  private val sessionImagesDir: File by lazy {
    File(context.filesDir, SESSION_IMAGES_DIR_NAME).apply {
      if (!exists()) {
        mkdirs()
      }
    }
  }

  /** Resolves a localized string resource with optional format arguments. */
  fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
    context.getString(resId, *formatArgs)

  /** Returns the stored custom prompt override for [persona], or `null` if using the default. */
  fun getCustomPersonaPromptTemplate(persona: CharacterPersona): String? =
    prefs.getString(PROMPT_KEY_PREFIX + persona.id, null)

  /** Returns the active prompt template for [persona]. */
  fun getPersonaPromptTemplate(persona: CharacterPersona): String =
    getCustomPersonaPromptTemplate(persona) ?: persona.defaultPromptTemplate

  /** Persists a custom prompt template for [persona]. */
  fun savePersonaPromptTemplate(persona: CharacterPersona, template: String) {
    prefs.edit { putString(PROMPT_KEY_PREFIX + persona.id, template) }
  }

  /** Clears any custom prompt template stored for [persona]. */
  fun resetPersonaPromptTemplate(persona: CharacterPersona) {
    prefs.edit { remove(PROMPT_KEY_PREFIX + persona.id) }
  }

  /** Returns whether a custom prompt template is explicitly stored for [persona]. */
  fun hasCustomPersonaPromptTemplate(persona: CharacterPersona): Boolean =
    (PROMPT_KEY_PREFIX + persona.id) in prefs

  /** Returns the persisted full system prompt override, or `null` if none is set. */
  fun getSystemPromptOverride(): String? =
    prefs.getString(SYSTEM_PROMPT_OVERRIDE_KEY, null)?.takeIf { it.isNotBlank() }

  /** Persists a full system prompt override. */
  fun saveSystemPromptOverride(prompt: String) {
    prefs.edit { putString(SYSTEM_PROMPT_OVERRIDE_KEY, prompt) }
  }

  /** Removes any persisted full system prompt override. */
  fun clearSystemPromptOverride() {
    prefs.edit { remove(SYSTEM_PROMPT_OVERRIDE_KEY) }
  }

  /** Reads a string preference by [key], or `null` if unset. */
  fun getStringPreference(key: String): String? = prefs.getString(key, null)

  /** Reads a boolean preference by [key], returning [defaultValue] if unset. */
  fun getBooleanPreference(key: String, defaultValue: Boolean = false): Boolean =
    prefs.getBoolean(key, defaultValue)

  /** Reads an integer preference by [key], returning [defaultValue] if unset. */
  fun getIntPreference(key: String, defaultValue: Int = 0): Int = prefs.getInt(key, defaultValue)

  /** Persists the integer preference [value] under [key]. */
  fun saveIntPreference(key: String, value: Int) {
    prefs.edit { putInt(key, value) }
  }

  /** Persists the boolean preference [value] under [key]. */
  fun saveBooleanPreference(key: String, value: Boolean) {
    prefs.edit { putBoolean(key, value) }
  }

  /**
   * Saves [bitmap] as a session snapshot PNG for [sessionId] at [timestampMs] under
   * [sessionImagesDir] and returns its absolute path, or `null` on failure.
   */
  fun saveSessionImage(sessionId: String, timestampMs: Long, bitmap: Bitmap): String? {
    val fileName = "img_${sessionId}_${timestampMs}_0.png"
    val file = File(sessionImagesDir, fileName)
    val saved =
      try {
        FileOutputStream(file).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save session image to ${file.absolutePath}", e)
        false
      }
    return if (saved && file.exists()) file.absolutePath else null
  }

  /**
   * Deletes the session snapshot images of [sessionId], including ones that older app versions
   * saved in the cache directory.
   */
  fun deleteSessionImages(sessionId: String) {
    deleteSessionImagesForIds(setOf(sessionId))
  }

  /**
   * Deletes the session snapshot images of every session ID in [sessionIds], including ones that
   * older app versions saved in the cache directory.
   */
  fun deleteSessionImagesForIds(sessionIds: Set<String>) {
    if (sessionIds.isEmpty()) return
    for (dir in listOf(sessionImagesDir, context.cacheDir)) {
      for (file in dir.listFiles().orEmpty()) {
        if (sessionIds.any { id -> file.name.startsWith("img_${id}_") }) {
          file.delete()
        }
      }
    }
  }

  /**
   * Writes JPEG vocabulary thumbnail bytes to disk under [imagesDir], atomically (see
   * [writeFileAtomically]).
   */
  fun writeVocabImage(safeFileName: String, imageBytes: ByteArray) {
    writeFileAtomically(File(imagesDir, safeFileName), imageBytes)
  }

  /**
   * Writes the PNG sticker cutout bytes of the word saved as [safeFileName] to disk under
   * [imagesDir], next to its original photo, atomically (see [writeFileAtomically]).
   */
  fun writeVocabStickerImage(safeFileName: String, pngBytes: ByteArray) {
    writeFileAtomically(File(imagesDir, vocabStickerFileName(safeFileName)), pngBytes)
  }

  /**
   * Writes [bytes] to a temporary file next to [file], syncs it to disk, and renames it to [file],
   * so that a crash, a power loss or a concurrent read never sees a partially written image. Throws
   * [IOException] if the file can't be written or renamed.
   */
  private fun writeFileAtomically(file: File, bytes: ByteArray) {
    val tempFile = File(file.parentFile, "${file.name}$TEMP_FILE_SUFFIX")
    try {
      FileOutputStream(tempFile).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
      }
      if (!tempFile.renameTo(file)) {
        throw IOException("Failed to rename ${tempFile.name} to ${file.name}")
      }
    } catch (e: IOException) {
      tempFile.delete()
      throw e
    }
  }

  /** Deletes the sticker cutout of the word saved as [safeFileName], if any. */
  fun deleteVocabStickerImage(safeFileName: String) {
    val file = File(imagesDir, vocabStickerFileName(safeFileName))
    if (file.exists()) {
      file.delete()
    }
  }

  /** Returns whether the word saved as [safeFileName] has a sticker cutout on disk. */
  fun hasVocabStickerImage(safeFileName: String): Boolean =
    File(imagesDir, vocabStickerFileName(safeFileName)).isFile

  /** Deletes vocabulary image files for [word] and [learnLanguage], including legacy fallback. */
  fun deleteVocabImageFiles(
    scopedFileName: String,
    unscopedSafeFileName: String?,
    legacyWord: String?,
  ) {
    val scopedFile = File(imagesDir, scopedFileName)
    if (scopedFile.exists()) {
      scopedFile.delete()
    }
    deleteVocabStickerImage(scopedFileName)
    if (unscopedSafeFileName != null) {
      val safeFile = File(imagesDir, unscopedSafeFileName)
      if (safeFile.exists()) {
        safeFile.delete()
      }
      deleteVocabStickerImage(unscopedSafeFileName)
    }
    if (legacyWord != null) {
      legacyImageFileOrNull(legacyWord)?.delete()
    }
  }

  /**
   * Loads a persisted vocabulary thumbnail image for [word] / [learnLanguage], if present,
   * downsampled for [maxSidePx] when given (see [decodeSavedVocabPhoto]).
   */
  fun decodeSavedVocabImageBitmap(
    scopedFileName: String?,
    safeFileName: String,
    legacyWord: String,
    maxSidePx: Int? = null,
  ): ImageBitmap? =
    decodeSavedVocabPhoto(
        scopedFileName = scopedFileName,
        safeFileName = safeFileName,
        legacyWord = legacyWord,
        maxSidePx = maxSidePx,
      )
      ?.asImageBitmap()

  /**
   * Loads the original photo saved for a word, checking the language-scoped filename first and
   * falling back to legacy word-only filenames, or returns `null` if there is none or it can't be
   * decoded.
   *
   * With [maxSidePx], decodes the photo downsampled by a power of two while its longest side stays
   * at least [maxSidePx], which is much cheaper than decoding it at full size and scaling it down.
   * Without it, decodes the photo at full size (e.g. for the segmenter and the cutout editor).
   */
  fun decodeSavedVocabPhoto(
    scopedFileName: String?,
    safeFileName: String,
    legacyWord: String,
    maxSidePx: Int? = null,
  ): Bitmap? =
    findVocabPhotoFile(
        scopedFileName = scopedFileName,
        safeFileName = safeFileName,
        legacyWord = legacyWord,
      )
      ?.let { file -> decodeImageFile(file, maxSidePx) }

  /** Returns whether an original photo is saved for a word (see [decodeSavedVocabPhoto]). */
  fun hasSavedVocabPhoto(
    scopedFileName: String?,
    safeFileName: String,
    legacyWord: String,
  ): Boolean =
    findVocabPhotoFile(
      scopedFileName = scopedFileName,
      safeFileName = safeFileName,
      legacyWord = legacyWord,
    ) != null

  /**
   * Loads the sticker cutout saved for a word, checking the language-scoped filename first, or
   * returns `null` if there is none or it can't be decoded. With [maxSidePx], decodes it
   * downsampled like [decodeSavedVocabPhoto].
   */
  fun decodeSavedVocabStickerBitmap(
    scopedFileName: String?,
    safeFileName: String,
    maxSidePx: Int? = null,
  ): ImageBitmap? {
    val file =
      listOfNotNull(scopedFileName, safeFileName)
        .map { File(imagesDir, vocabStickerFileName(it)) }
        .firstOrNull(File::isFile) ?: return null
    return decodeImageFile(file, maxSidePx)?.asImageBitmap()
  }

  /**
   * Decodes the image [file], downsampled by the largest power of two that keeps its longest side
   * at least [maxSidePx] when given, or returns `null` if it can't be decoded.
   */
  private fun decodeImageFile(file: File, maxSidePx: Int?): Bitmap? =
    runCatching {
        val options = BitmapFactory.Options()
        if (maxSidePx != null && maxSidePx > 0) {
          options.inJustDecodeBounds = true
          BitmapFactory.decodeFile(file.absolutePath, options)
          options.inSampleSize =
            vocabImageSampleSize(
              width = options.outWidth,
              height = options.outHeight,
              maxSidePx = maxSidePx,
            )
          options.inJustDecodeBounds = false
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
      }
      .getOrNull()

  private fun findVocabPhotoFile(
    scopedFileName: String?,
    safeFileName: String,
    legacyWord: String,
  ): File? {
    val scopedFile = scopedFileName?.let { File(imagesDir, it).takeIf(File::exists) }
    val safeFile = File(imagesDir, safeFileName).takeIf(File::exists)
    return scopedFile ?: safeFile ?: legacyImageFileOrNull(legacyWord)
  }

  private fun legacyImageFileOrNull(word: String): File? {
    val candidate = File(imagesDir, word)
    return candidate.takeIf {
      it.canonicalFile.parentFile == imagesDir.canonicalFile && it.exists()
    }
  }
}
