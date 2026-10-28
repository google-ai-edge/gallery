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
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.findSavedDictionaryEntry
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.isVocabCardSaved
import com.google.ai.edge.gallery.proto.Dictionary
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Qualifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * Saves [cardToSave] (or the active session's [VocabCardData]) and frozen camera frame (if any) to
 * the persistent dictionary and marks `isCurrentCardSaved = true`. A word that is already saved is
 * left untouched.
 */
@Suppress("KotlinArray")
fun XrLanguageTeacherViewModel.saveCurrentVocabCard(cardToSave: VocabCardData? = null) {
  val state = uiState.value
  val card = cardToSave ?: state.vocabCardData ?: return
  if (card.word.isBlank()) return
  if (card == state.vocabCardData && state.isCurrentCardSaved) return
  if (isVocabCardSaved(card)) {
    if (card == state.vocabCardData) {
      updateUiState { it.copy(isCurrentCardSaved = true) }
    }
    return
  }

  val personaDisplayName = storageRepository.getString(selectedPersona.value.displayNameResId())
  val entry =
    card.toDictionaryEntry(
      mainLanguage = mainLanguage.value,
      learnLanguage = learnLanguage.value,
      definition =
        storageRepository.getString(R.string.xrteacher_identified_by_persona, personaDisplayName),
    )
  val bitmap = state.capturedBitmap
  if (card == state.vocabCardData) {
    updateUiState { it.copy(isCurrentCardSaved = true) }
  }

  viewModelScope.launch(ioDispatcher) {
    saveDictionaryEntryInternal(entry, bitmap?.toVocabImageBytes(), replaceExisting = false)
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

@Suppress("KotlinArray")
fun XrLanguageTeacherViewModel.addDictionaryEntry(entry: DictionaryEntry, imageBytes: ByteArray?) {
  viewModelScope.launch(ioDispatcher) { saveDictionaryEntryInternal(entry, imageBytes) }
}

/**
 * Writes [entry] and its [imageBytes] to the dictionary, replacing an existing entry for the same
 * word and learn language. When [replaceExisting] is false, an already-saved word (including its
 * image) is left untouched instead.
 */
@Suppress("KotlinArray")
internal suspend fun XrLanguageTeacherViewModel.saveDictionaryEntryInternal(
  entry: DictionaryEntry,
  imageBytes: ByteArray?,
  replaceExisting: Boolean = true,
) {
  if (entry.word.isBlank()) return
  dictionaryFileMutex.withLock {
    if (
      !replaceExisting &&
        dictionaryDataStore.data
          .first()
          .entriesList
          .findSavedEntry(entry.word, entry.learnLanguage) != null
    ) {
      return@withLock
    }
    if (imageBytes != null && imageBytes.isNotEmpty()) {
      val safeName =
        XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word, entry.learnLanguage)
      try {
        storageRepository.writeVocabImage(safeFileName = safeName, imageBytes = imageBytes)
      } catch (e: Exception) {
        Log.e(TAG, "Failed writing vocabulary image file", e)
        logErrorToFirebase(GalleryEvent.BUTTON_CLICKED, "io_error", e.message)
      }
    }

    dictionaryDataStore.updateData { currentDictionary ->
      val existingIndex =
        currentDictionary.entriesList.indexOfFirst {
          it.learnLanguage == entry.learnLanguage && it.word.equals(entry.word, ignoreCase = true)
        }
      if (existingIndex >= 0) {
        currentDictionary.toBuilder().setEntries(existingIndex, entry).build()
      } else {
        currentDictionary.toBuilder().addEntries(entry).build()
      }
    }
  }
}

fun XrLanguageTeacherViewModel.deleteDictionaryEntry(entry: DictionaryEntry) {
  val activeCard = uiState.value.vocabCardData
  if (
    activeCard != null &&
      learnLanguage.value == entry.learnLanguage &&
      (activeCard.word.equals(entry.word, ignoreCase = true) ||
        XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(activeCard.word, entry.word))
  ) {
    updateUiState { it.copy(isCurrentCardSaved = false) }
  }

  viewModelScope.launch(ioDispatcher) {
    dictionaryFileMutex.withLock {
      val scopedFileName =
        XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word, entry.learnLanguage)
      val otherLangHasSameWord =
        dictionaryDataStore.data.first().entriesList.any {
          it.learnLanguage != entry.learnLanguage && it.word.equals(entry.word, ignoreCase = true)
        }
      storageRepository.deleteVocabImageFiles(
        scopedFileName = scopedFileName,
        unscopedSafeFileName =
          if (!otherLangHasSameWord)
            XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(entry.word)
          else null,
        legacyWord = if (!otherLangHasSameWord) entry.word else null,
      )

      dictionaryDataStore.updateData { currentDictionary ->
        val index =
          currentDictionary.entriesList.indexOfFirst {
            it.learnLanguage == entry.learnLanguage && it.word.equals(entry.word, ignoreCase = true)
          }
        if (index >= 0) {
          currentDictionary.toBuilder().removeEntries(index).build()
        } else {
          currentDictionary
        }
      }
    }
  }
}

/**
 * Removes the saved word matching [card] in the active learn language (see
 * [XrLanguageTeacherViewModel.findSavedDictionaryEntry]) along with its image, and clears
 * `isCurrentCardSaved` when [card] is the active card. Does nothing if [card] is not saved.
 */
fun XrLanguageTeacherViewModel.unsaveVocabCard(card: VocabCardData) {
  val entry = findSavedDictionaryEntry(card) ?: return
  deleteDictionaryEntry(entry)
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
  viewModelScope.launch(ioDispatcher) {
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

/**
 * Removes the saved word matching [card] in [learnLanguage] (see [findSavedEntry]) along with its
 * image. Does nothing if [card] is not saved for [learnLanguage].
 */
fun XrLanguageTeacherViewModel.unsaveVocabCardForLanguage(
  card: VocabCardData,
  learnLanguage: Language,
) {
  val entry = dictionaryEntries.value.findSavedEntry(card.word, learnLanguage) ?: return
  deleteDictionaryEntry(entry)
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
 * Loads a saved vocabulary thumbnail from disk on [ioDispatcher], checking the language-scoped
 * filename first and falling back to legacy word-only filenames if necessary.
 */
suspend fun XrLanguageTeacherViewModel.loadSavedVocabImageBitmap(
  word: String,
  learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
): ImageBitmap? =
  withContext(ioDispatcher) {
    dictionaryFileMutex.withLock {
      val scopedFileName =
        if (learnLanguage != Language.LANGUAGE_UNSPECIFIED) {
          XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word, learnLanguage)
        } else {
          null
        }
      val safeFileName = XrLanguageTeacherDialogueFormatter.sanitizeWordForFilename(word)
      storageRepository.decodeSavedVocabImageBitmap(
        scopedFileName = scopedFileName,
        safeFileName = safeFileName,
        legacyWord = word,
      )
    }
  }
