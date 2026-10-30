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
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.isVocabCardSaved
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.persistLatestSessionSticker
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.stickerFor
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.stickerKey
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.usesOriginalPhoto
import com.google.ai.edge.gallery.proto.Dictionary
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.proto.dictionaryEntry
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Qualifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGTeacherVocabStore"

/** Hilt qualifier for the AR Language Teacher [Dictionary] DataStore. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class XrLanguageTeacherDictionary

object DictionarySerializer : Serializer<Dictionary> {
  override val defaultValue: Dictionary = Dictionary.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): Dictionary {
    try {
      return Dictionary.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: Dictionary, output: OutputStream) = t.writeTo(output)
}

/**
 * Saves [cardToSave] (or the active session's [VocabCardData]), the frozen camera frame (if any),
 * and the card's sticker cutout (if segmentation already isolated one) to the persistent dictionary
 * and marks `isCurrentCardSaved = true`.
 *
 * A word that is already saved keeps its text, and only gets the photo or sticker it is missing
 * (like iOS). When the sticker of a newly saved word is not ready yet, the word gets it once the
 * session's automatic cutout finishes. A card whose original photo the user chose in the cutout
 * editor is saved with its photo only, marked as segmented so it isn't cut out later.
 */
@Suppress("KotlinArray")
fun XrLanguageTeacherViewModel.saveCurrentVocabCard(cardToSave: VocabCardData? = null) {
  val state = uiState.value
  val card = cardToSave ?: state.vocabCardData ?: return
  if (card.word.isBlank()) return
  if (card == state.vocabCardData && state.isCurrentCardSaved) return
  val isAlreadySaved = isVocabCardSaved(card)

  val personaDisplayName = storageRepository.getString(selectedPersona.value.displayNameResId())
  val entry =
    card.toDictionaryEntry(
      mainLanguage = mainLanguage.value,
      learnLanguage = learnLanguage.value,
      definition =
        storageRepository.getString(R.string.xrteacher_identified_by_persona, personaDisplayName),
    )
  val bitmap = state.capturedBitmap
  val sticker = if (bitmap != null) state.stickerFor(card) else null
  val keepsPhotoOnly = bitmap != null && state.usesOriginalPhoto(stickerKey(card.word))
  if (card == state.vocabCardData) {
    updateUiState { it.copy(isCurrentCardSaved = true) }
  }
  if (isAlreadySaved && bitmap == null) return
  val captureId = stickerCaptureId.get()
  val isNewSessionSave = !isAlreadySaved && bitmap != null
  if (isNewSessionSave) {
    sessionSavedWordKeys.add(stickerKey(card.word))
    if (sticker == null && !keepsPhotoOnly) {
      sessionWordsAwaitingSticker.add(stickerKey(card.word))
    }
  }

  // Queued with the other vocabulary writes, so that unsaving the word right away still unsaves it.
  // The images are encoded in the queued write, off the main thread.
  vocabularyWriter.enqueue {
    withContext(ioDispatcher) {
      saveDictionaryEntryInternal(
        entry = entry,
        imageBytes = bitmap?.toVocabImageBytes(),
        replaceExisting = false,
        stickerBytes = sticker?.let(XrLanguageTeacherStickerImages::encodePng),
        keepsPhotoOnly = keepsPhotoOnly,
      )
      if (isNewSessionSave) {
        persistLatestSessionSticker(
          card = card,
          learnLanguage = entry.learnLanguage,
          captureId = captureId,
          savedSticker = sticker,
        )
      }
    }
  }
}

/**
 * Compresses this camera frame to the JPEG bytes stored as a word image, or returns null when the
 * frame was recycled.
 */
@Suppress("KotlinArray")
private fun Bitmap.toVocabImageBytes(): ByteArray? {
  if (isRecycled) return null
  return try {
    ByteArrayOutputStream().use { stream ->
      compress(Bitmap.CompressFormat.JPEG, 85, stream)
      stream.toByteArray()
    }
  } catch (e: IllegalStateException) {
    // The frame can be recycled concurrently by the camera pipeline.
    Log.w(TAG, "Vocabulary frame was recycled before it could be saved", e)
    null
  }
}

/**
 * Silently saves [card] with the captured frame while the lesson runs on XR glasses, which have no
 * Save button to tap. Does nothing in phone mode, and leaves words that are already saved
 * untouched.
 */
internal fun XrLanguageTeacherViewModel.autoSaveVocabCardInXrMode(card: VocabCardData) {
  if (uiState.value.isXrMode) {
    saveCurrentVocabCard(card)
  }
}

/**
 * Saves [entry] with its [imageBytes] (the original photo) in the background, after the vocabulary
 * writes requested before it (see [XrLanguageTeacherOrderedWriter]).
 */
@Suppress("KotlinArray")
fun XrLanguageTeacherViewModel.addDictionaryEntry(entry: DictionaryEntry, imageBytes: ByteArray?) {
  vocabularyWriter.enqueue {
    withContext(ioDispatcher) { saveDictionaryEntryInternal(entry, imageBytes) }
  }
}

/**
 * Writes [entry], its [imageBytes] (the original photo) and its [stickerBytes] (the PNG sticker
 * cutout, which marks the entry's sticker segmentation as attempted) to the dictionary, replacing
 * an existing entry for the same word and learn language. When [replaceExisting] is false, an
 * already-saved word (including its images) is left untouched instead.
 *
 * Replacing the photo without a sticker deletes the previous sticker, since it was cut out of the
 * previous photo, so the new photo is segmented again later, unless [keepsPhotoOnly] (the user
 * chose the photo over a sticker cutout), which marks the segmentation as attempted instead.
 * Replacing neither keeps both.
 *
 * Runs after the vocabulary writes requested before it (see [XrLanguageTeacherOrderedWriter]).
 */
@Suppress("KotlinArray")
internal suspend fun XrLanguageTeacherViewModel.saveDictionaryEntryInternal(
  entry: DictionaryEntry,
  imageBytes: ByteArray?,
  replaceExisting: Boolean = true,
  stickerBytes: ByteArray? = null,
  keepsPhotoOnly: Boolean = false,
) {
  if (entry.word.isBlank()) return
  vocabularyWriter.run {
    writeDictionaryEntry(entry, imageBytes, replaceExisting, stickerBytes, keepsPhotoOnly)
  }
}

/** Does the write of [saveDictionaryEntryInternal]. Call through [vocabularyWriter]. */
@Suppress("KotlinArray")
private suspend fun XrLanguageTeacherViewModel.writeDictionaryEntry(
  entry: DictionaryEntry,
  imageBytes: ByteArray?,
  replaceExisting: Boolean,
  stickerBytes: ByteArray?,
  keepsPhotoOnly: Boolean,
) {
  var imagesChanged = false
  var changedEntry: DictionaryEntry = entry
  dictionaryFileMutex.withLock {
    if (!replaceExisting) {
      val existing =
        dictionaryDataStore.data.first().entriesList.findSavedEntry(entry.word, entry.learnLanguage)
      if (existing != null) {
        changedEntry = existing
        if (backfillMissingVocabImages(existing, imageBytes, stickerBytes)) {
          imagesChanged = true
        }
        return@withLock
      }
    }
    val safeName =
      XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word, entry.learnLanguage)
    val hasNewPhoto = imageBytes != null && imageBytes.isNotEmpty()
    if (hasNewPhoto) {
      try {
        storageRepository.writeVocabImage(safeFileName = safeName, imageBytes = imageBytes)
        imagesChanged = true
      } catch (e: Exception) {
        Log.e(TAG, "Failed writing vocabulary image file", e)
        logErrorToFirebase(GalleryEvent.BUTTON_CLICKED, "io_error", e.message)
      }
    }
    val savedSticker =
      if (stickerBytes != null && stickerBytes.isNotEmpty()) {
        try {
          storageRepository.writeVocabStickerImage(safeFileName = safeName, pngBytes = stickerBytes)
          imagesChanged = true
          true
        } catch (e: Exception) {
          Log.e(TAG, "Failed writing vocabulary sticker file", e)
          false
        }
      } else {
        if (hasNewPhoto) {
          storageRepository.deleteVocabStickerImage(safeFileName = safeName)
        }
        false
      }

    dictionaryDataStore.updateData { currentDictionary ->
      val existingIndex =
        currentDictionary.entriesList.indexOfEntry(entry.word, entry.learnLanguage)
      val stickerSegmentationAttempted =
        when {
          savedSticker -> true
          hasNewPhoto -> keepsPhotoOnly
          existingIndex >= 0 ->
            currentDictionary.getEntries(existingIndex).stickerSegmentationAttempted
          else -> entry.stickerSegmentationAttempted
        }
      val entryToSave =
        entry.toBuilder().setStickerSegmentationAttempted(stickerSegmentationAttempted).build()
      if (existingIndex >= 0) {
        currentDictionary.toBuilder().setEntries(existingIndex, entryToSave).build()
      } else {
        currentDictionary.toBuilder().addEntries(entryToSave).build()
      }
    }
  }
  if (imagesChanged) {
    onVocabImagesChanged(changedEntry.word, changedEntry.learnLanguage)
  }
}

/**
 * Drops the cached images of the saved [word] in [learnLanguage] and bumps their version (see
 * [XrLanguageTeacherVocabImageCache]), then tells the screens showing saved words to reload their
 * images. Call after the word's images changed on disk.
 */
private fun XrLanguageTeacherViewModel.onVocabImagesChanged(word: String, learnLanguage: Language) {
  XrLanguageTeacherVocabImageCache.invalidate(
    XrLanguageTeacherVocabImageCache.entryKey(word, learnLanguage)
  )
  _vocabImageRevision.update { it + 1 }
}

/**
 * Writes the original photo ([imageBytes]) and the sticker cutout ([stickerBytes]) that the saved
 * [existing] entry is missing, like iOS fills in missing images when a saved word is saved again.
 * Marks the entry's sticker segmentation as attempted when a sticker is written. Returns whether
 * any image was written. Call under `dictionaryFileMutex`.
 */
@Suppress("KotlinArray")
private suspend fun XrLanguageTeacherViewModel.backfillMissingVocabImages(
  existing: DictionaryEntry,
  imageBytes: ByteArray?,
  stickerBytes: ByteArray?,
): Boolean {
  val safeName =
    XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(
      existing.word,
      existing.learnLanguage,
    )
  var wroteImage = false
  if (
    imageBytes != null &&
      imageBytes.isNotEmpty() &&
      !storageRepository.hasSavedVocabPhoto(
        scopedFileName = safeName,
        safeFileName = XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(existing.word),
        legacyWord = existing.word,
      )
  ) {
    try {
      storageRepository.writeVocabImage(safeFileName = safeName, imageBytes = imageBytes)
      wroteImage = true
    } catch (e: Exception) {
      Log.e(TAG, "Failed writing vocabulary image file", e)
    }
  }
  if (
    stickerBytes != null &&
      stickerBytes.isNotEmpty() &&
      !storageRepository.hasVocabStickerImage(safeName)
  ) {
    try {
      storageRepository.writeVocabStickerImage(safeFileName = safeName, pngBytes = stickerBytes)
      wroteImage = true
      markStickerSegmentationAttempted(existing.word, existing.learnLanguage)
    } catch (e: Exception) {
      Log.e(TAG, "Failed writing vocabulary sticker file", e)
    }
  }
  return wroteImage
}

/**
 * Marks the sticker segmentation of the saved [word] in [learnLanguage] as attempted, so its photo
 * isn't segmented again. Call under `dictionaryFileMutex`.
 */
private suspend fun XrLanguageTeacherViewModel.markStickerSegmentationAttempted(
  word: String,
  learnLanguage: Language,
) {
  dictionaryDataStore.updateData { currentDictionary ->
    val index = currentDictionary.entriesList.indexOfEntry(word, learnLanguage)
    if (index < 0) return@updateData currentDictionary
    val updated =
      currentDictionary.getEntries(index).toBuilder().setStickerSegmentationAttempted(true).build()
    currentDictionary.toBuilder().setEntries(index, updated).build()
  }
}

/**
 * Saves [stickerBytes] (a PNG sticker cutout, or `null` when segmentation found no foreground
 * object) for the saved [word] in [learnLanguage] and marks its sticker segmentation as attempted.
 * Writes on [XrLanguageTeacherViewModel.ioDispatcher], so it is safe to call from the main thread.
 *
 * [shouldWrite] is checked under the dictionary file lock right before writing, so that a sticker
 * that was superseded meanwhile (edited, or replaced by the original photo) doesn't overwrite the
 * newer choice.
 *
 * Returns `false` when the word is not saved, [shouldWrite] declined, or the sticker could not be
 * written. Runs after the vocabulary writes requested before it (see
 * [XrLanguageTeacherOrderedWriter]).
 */
@Suppress("KotlinArray")
internal suspend fun XrLanguageTeacherViewModel.persistVocabSticker(
  word: String,
  learnLanguage: Language,
  stickerBytes: ByteArray?,
  shouldWrite: () -> Boolean = { true },
): Boolean {
  val savedEntry =
    vocabularyWriter.run {
      withContext(ioDispatcher) {
        dictionaryFileMutex.withLock {
          if (!shouldWrite()) return@withLock null
          val current =
            dictionaryDataStore.data.first().entriesList.findSavedEntry(word, learnLanguage)
              ?: return@withLock null
          if (stickerBytes != null) {
            val safeName =
              XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(
                current.word,
                current.learnLanguage,
              )
            try {
              storageRepository.writeVocabStickerImage(
                safeFileName = safeName,
                pngBytes = stickerBytes,
              )
            } catch (e: Exception) {
              Log.e(TAG, "Failed writing vocabulary sticker file", e)
              return@withLock null
            }
          }
          markStickerSegmentationAttempted(current.word, current.learnLanguage)
          current
        }
      }
    } ?: return false
  if (stickerBytes != null) {
    onVocabImagesChanged(savedEntry.word, savedEntry.learnLanguage)
  }
  return true
}

/**
 * Deletes the sticker cutout of the saved [word] in [learnLanguage] so that its original photo
 * shows, keeping its sticker segmentation marked as attempted so that the photo isn't segmented
 * again automatically. Returns `false` when the word is not saved. Runs after the vocabulary writes
 * requested before it (see [XrLanguageTeacherOrderedWriter]).
 */
internal suspend fun XrLanguageTeacherViewModel.revertVocabStickerToPhoto(
  word: String,
  learnLanguage: Language,
): Boolean {
  val revertedEntry =
    vocabularyWriter.run {
      withContext(ioDispatcher) {
        dictionaryFileMutex.withLock {
          val current =
            dictionaryDataStore.data.first().entriesList.findSavedEntry(word, learnLanguage)
              ?: return@withLock null
          storageRepository.deleteVocabStickerImage(
            safeFileName =
              XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(
                current.word,
                current.learnLanguage,
              )
          )
          markStickerSegmentationAttempted(current.word, current.learnLanguage)
          current
        }
      }
    } ?: return false
  onVocabImagesChanged(revertedEntry.word, revertedEntry.learnLanguage)
  return true
}

/**
 * Removes the saved [entry] (matched by its word, case-insensitively, and learn language) and its
 * images in the background, after the vocabulary writes requested before it (see
 * [XrLanguageTeacherOrderedWriter]), and clears `isCurrentCardSaved` when [entry] saves the active
 * card.
 */
fun XrLanguageTeacherViewModel.deleteDictionaryEntry(entry: DictionaryEntry) {
  deleteDictionaryEntry(entry, matchesSameVocabularyWord = false)
}

/**
 * Removes the saved [entry] like the public [deleteDictionaryEntry]. With
 * [matchesSameVocabularyWord], removes the saved entry that [findSavedEntry] finds for its word
 * when no entry has exactly its word, e.g. to unsave a word whose save hadn't landed when the user
 * unsaved it.
 */
private fun XrLanguageTeacherViewModel.deleteDictionaryEntry(
  entry: DictionaryEntry,
  matchesSameVocabularyWord: Boolean,
) {
  val activeCard = uiState.value.vocabCardData
  if (
    activeCard != null &&
      learnLanguage.value == entry.learnLanguage &&
      (activeCard.word.equals(entry.word, ignoreCase = true) ||
        XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(activeCard.word, entry.word))
  ) {
    updateUiState { it.copy(isCurrentCardSaved = false) }
  }

  vocabularyWriter.enqueue {
    val deleted =
      withContext(ioDispatcher) {
        dictionaryFileMutex.withLock {
          val entries = dictionaryDataStore.data.first().entriesList
          val target =
            entries.getOrNull(entries.indexOfEntry(entry.word, entry.learnLanguage))
              ?: entries.findSavedEntry(entry.word, entry.learnLanguage)?.takeIf {
                matchesSameVocabularyWord
              }
              ?: entry
          val scopedFileName =
            XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(
              target.word,
              target.learnLanguage,
            )
          val otherLangHasSameWord = entries.any {
            it.learnLanguage != target.learnLanguage &&
              it.word.equals(target.word, ignoreCase = true)
          }
          storageRepository.deleteVocabImageFiles(
            scopedFileName = scopedFileName,
            unscopedSafeFileName =
              if (!otherLangHasSameWord)
                XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(target.word)
              else null,
            legacyWord = if (!otherLangHasSameWord) target.word else null,
          )

          dictionaryDataStore.updateData { currentDictionary ->
            val index =
              currentDictionary.entriesList.indexOfEntry(target.word, target.learnLanguage)
            if (index >= 0) {
              currentDictionary.toBuilder().removeEntries(index).build()
            } else {
              currentDictionary
            }
          }
          target
        }
      }
    onVocabImagesChanged(deleted.word, deleted.learnLanguage)
  }
}

/**
 * Removes the saved word matching [card] in the active learn language (see
 * [XrLanguageTeacherViewModel.findSavedDictionaryEntry]) along with its image, and clears
 * `isCurrentCardSaved` when [card] is the active card.
 *
 * The removal is queued after the vocabulary writes requested before it, so a word whose save
 * hasn't landed yet (it isn't in [XrLanguageTeacherViewModel.dictionaryEntries] yet) is removed
 * once its save lands.
 */
fun XrLanguageTeacherViewModel.unsaveVocabCard(card: VocabCardData) {
  unsaveVocabCardForLanguage(card, learnLanguage.value)
}

/**
 * Saves [card], shown in the history of [session], to the dictionary under the languages the
 * session was recorded in (not the current ones), with the session persona as the definition and a
 * copy of the session image (or, for the active session, its in-memory frame) as the word image. A
 * word that is already saved is left untouched.
 *
 * Does nothing for sessions saved before cards stored their languages, since the learn language of
 * those cards is unknown.
 */
fun XrLanguageTeacherViewModel.saveVocabCardFromHistory(
  session: LessonHistorySession,
  card: VocabCardData,
) {
  val sessionMainLanguage = session.mainLanguage ?: return
  val sessionLearnLanguage = session.learnLanguage ?: return
  if (card.word.isBlank()) return
  val personaDisplayName = storageRepository.getString(session.persona.displayNameResId())
  val entry =
    card.toDictionaryEntry(
      mainLanguage = sessionMainLanguage,
      learnLanguage = sessionLearnLanguage,
      definition =
        storageRepository.getString(R.string.xrteacher_identified_by_persona, personaDisplayName),
    )
  val imageFilePath = session.imageFilePath
  // The active session may not have written its image file yet; fall back to its in-memory frame.
  val inMemoryBitmap = session.inMemoryBitmap
  vocabularyWriter.enqueue {
    withContext(ioDispatcher) {
      val imageFileBytes = imageFilePath?.let { path ->
        try {
          File(path).takeIf { it.isFile }?.readBytes()
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read the session image for a word saved from history", e)
          null
        }
      }
      val imageBytes = imageFileBytes ?: inMemoryBitmap?.toVocabImageBytes()
      saveDictionaryEntryInternal(entry, imageBytes, replaceExisting = false)
    }
  }
}

/**
 * Removes the saved word matching [card] in [learnLanguage] (see [findSavedEntry]) along with its
 * image, and clears `isCurrentCardSaved` when [card] is the active card in that language.
 *
 * Like [unsaveVocabCard], also removes a word whose save hasn't landed yet once it lands.
 */
fun XrLanguageTeacherViewModel.unsaveVocabCardForLanguage(
  card: VocabCardData,
  learnLanguage: Language,
) {
  if (card.word.isBlank()) return
  val savedEntry = dictionaryEntries.value.findSavedEntry(card.word, learnLanguage)
  if (savedEntry != null) {
    deleteDictionaryEntry(savedEntry)
    return
  }
  // The word's save may not have landed yet: remove whatever it saves once the queued writes ran.
  val pendingEntry = dictionaryEntry {
    word = card.word
    this.learnLanguage = learnLanguage
  }
  deleteDictionaryEntry(pendingEntry, matchesSameVocabularyWord = true)
}

/**
 * Returns the index of the entry in this list that saves [word] for [learnLanguage], matching the
 * word case-insensitively, or -1 when there is none.
 */
private fun List<DictionaryEntry>.indexOfEntry(word: String, learnLanguage: Language): Int =
  indexOfFirst {
    it.learnLanguage == learnLanguage && it.word.equals(word, ignoreCase = true)
  }

/**
 * Returns the entry in this list that saves [word] for [learnLanguage], matching the word
 * case-insensitively or as the same vocabulary word, or null when [word] is not saved.
 */
internal fun List<DictionaryEntry>.findSavedEntry(
  word: String,
  learnLanguage: Language,
): DictionaryEntry? {
  if (word.isBlank()) return null
  return firstOrNull {
    it.learnLanguage == learnLanguage &&
      (it.word.equals(word, ignoreCase = true) ||
        XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(it.word, word))
  }
}

/**
 * Longest side, in pixels, that saved word thumbnails are decoded for: about twice the largest
 * thumbnail on screen (the 160dp flashcard image), so they stay sharp without decoding full-size
 * photos.
 */
internal const val VOCAB_THUMBNAIL_MAX_SIDE_PX = 512

/**
 * Loads a saved vocabulary thumbnail on [ioDispatcher]: the word's sticker cutout when it has one,
 * or else its original photo, decoded downsampled for [VOCAB_THUMBNAIL_MAX_SIDE_PX]. Checks the
 * language-scoped filename first and falls back to legacy word-only filenames if necessary.
 *
 * Thumbnails of words with a learn language come from [XrLanguageTeacherVocabImageCache] when they
 * didn't change since they were last loaded, so reloading every thumbnail is cheap.
 */
suspend fun XrLanguageTeacherViewModel.loadSavedVocabImageBitmap(
  word: String,
  learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
): ImageBitmap? =
  withContext(ioDispatcher) {
    if (learnLanguage == Language.LANGUAGE_UNSPECIFIED) {
      return@withContext decodeSavedVocabThumbnail(word, learnLanguage)
    }
    XrLanguageTeacherVocabImageCache.getOrLoad(
      entryKey = XrLanguageTeacherVocabImageCache.entryKey(word, learnLanguage),
      kind = XrLanguageTeacherVocabImageKind.THUMBNAIL,
      maxSidePx = VOCAB_THUMBNAIL_MAX_SIDE_PX,
    ) {
      decodeSavedVocabThumbnail(word, learnLanguage)
    }
  }

/**
 * Decodes the thumbnail of [loadSavedVocabImageBitmap] from disk, downsampled for
 * [VOCAB_THUMBNAIL_MAX_SIDE_PX].
 */
private suspend fun XrLanguageTeacherViewModel.decodeSavedVocabThumbnail(
  word: String,
  learnLanguage: Language,
): ImageBitmap? {
  val scopedFileName = scopedVocabFileName(word, learnLanguage)
  val safeFileName = XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word)
  return dictionaryFileMutex.withLock {
    storageRepository.decodeSavedVocabStickerBitmap(
      scopedFileName = scopedFileName,
      safeFileName = safeFileName,
      maxSidePx = VOCAB_THUMBNAIL_MAX_SIDE_PX,
    )
      ?: storageRepository.decodeSavedVocabImageBitmap(
        scopedFileName = scopedFileName,
        safeFileName = safeFileName,
        legacyWord = word,
        maxSidePx = VOCAB_THUMBNAIL_MAX_SIDE_PX,
      )
  }
}

/**
 * Loads the original photo saved for [word] in [learnLanguage] on [ioDispatcher] (never the sticker
 * cutout), or returns `null` when there is none.
 */
suspend fun XrLanguageTeacherViewModel.loadSavedVocabPhoto(
  word: String,
  learnLanguage: Language,
): Bitmap? =
  withContext(ioDispatcher) {
    dictionaryFileMutex.withLock {
      storageRepository.decodeSavedVocabPhoto(
        scopedFileName = scopedVocabFileName(word, learnLanguage),
        safeFileName = XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word),
        legacyWord = word,
      )
    }
  }

/**
 * Returns the saved entries for [learnLanguage] (or for every learn language when it is `null`)
 * whose photo has never been segmented into a sticker cutout, read on [ioDispatcher].
 */
internal suspend fun XrLanguageTeacherViewModel.findUnsegmentedStickerEntries(
  learnLanguage: Language?
): List<DictionaryEntry> =
  withContext(ioDispatcher) {
    dictionaryFileMutex.withLock {
      dictionaryDataStore.data.first().entriesList.filter { entry ->
        (learnLanguage == null || entry.learnLanguage == learnLanguage) &&
          !entry.stickerSegmentationAttempted &&
          !hasVocabSticker(entry) &&
          hasVocabPhoto(entry)
      }
    }
  }

/** Returns whether a sticker cutout is saved for [entry]. Call under `dictionaryFileMutex`. */
internal fun XrLanguageTeacherViewModel.hasVocabSticker(entry: DictionaryEntry): Boolean =
  storageRepository.hasVocabStickerImage(
    scopedVocabFileName(entry.word, entry.learnLanguage)
      ?: XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word)
  )

/**
 * Returns whether the original photo of [entry] is saved, under its language-scoped, word-only or
 * legacy file name. Call under `dictionaryFileMutex`.
 */
internal fun XrLanguageTeacherViewModel.hasVocabPhoto(entry: DictionaryEntry): Boolean =
  storageRepository.hasSavedVocabPhoto(
    scopedFileName = scopedVocabFileName(entry.word, entry.learnLanguage),
    safeFileName = XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word),
    legacyWord = entry.word,
  )

/** Returns the language-scoped image file name of [word], or `null` without a learn language. */
private fun scopedVocabFileName(word: String, learnLanguage: Language): String? =
  if (learnLanguage != Language.LANGUAGE_UNSPECIFIED) {
    XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word, learnLanguage)
  } else {
    null
  }
