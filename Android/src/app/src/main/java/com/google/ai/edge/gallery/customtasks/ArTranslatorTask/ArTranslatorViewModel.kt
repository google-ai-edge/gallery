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

package com.google.ai.edge.gallery.customtasks.ArTranslatorTask

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.proto.Dictionary
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArTranslatorUiState(val currentScreen: String = "home")

@HiltViewModel
class ArTranslatorViewModel
@Inject
constructor(
  private val dictionaryDataStore: DataStore<Dictionary>,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ArTranslatorUiState())
  val uiState: StateFlow<ArTranslatorUiState> = _uiState

  val dictionaryEntries: StateFlow<List<DictionaryEntry>> =
    dictionaryDataStore.data
      .map { it.entriesList }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val mainLanguage: StateFlow<Language> =
    dictionaryDataStore.data
      .map {
        if (it.mainLanguage == Language.LANGUAGE_UNSPECIFIED) Language.LANGUAGE_ENGLISH
        else it.mainLanguage
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Language.LANGUAGE_ENGLISH,
      )

  val learnLanguage: StateFlow<Language> =
    dictionaryDataStore.data
      .map {
        if (it.learnLanguage == Language.LANGUAGE_UNSPECIFIED) Language.LANGUAGE_CHINESE
        else it.learnLanguage
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Language.LANGUAGE_CHINESE,
      )

  fun setScreen(screen: String) {
    _uiState.update { it.copy(currentScreen = screen) }
  }

  fun setMainLanguage(language: Language) {
    viewModelScope.launch {
      dictionaryDataStore.updateData { currentDictionary ->
        currentDictionary.toBuilder().setMainLanguage(language).build()
      }
    }
  }

  fun setLearnLanguage(language: Language) {
    viewModelScope.launch {
      dictionaryDataStore.updateData { currentDictionary ->
        currentDictionary.toBuilder().setLearnLanguage(language).build()
      }
    }
  }

  private val imagesDir by lazy {
    File(context.filesDir, "ar_translator_images").apply {
      if (!exists()) {
        mkdirs()
      }
    }
  }

  fun addDictionaryEntry(entry: DictionaryEntry, imageBytes: ByteArray?) {
    viewModelScope.launch(Dispatchers.IO) {
      if (imageBytes != null && imageBytes.isNotEmpty()) {
        val file = File(imagesDir, entry.word)
        try {
          file.writeBytes(imageBytes)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      dictionaryDataStore.updateData { currentDictionary ->
        val existingIndex = currentDictionary.entriesList.indexOfFirst { it.word == entry.word }
        if (existingIndex >= 0) {
          currentDictionary.toBuilder().setEntries(existingIndex, entry).build()
        } else {
          currentDictionary.toBuilder().addEntries(entry).build()
        }
      }
    }
  }

  fun deleteDictionaryEntry(entry: DictionaryEntry) {
    viewModelScope.launch(Dispatchers.IO) {
      val file = File(imagesDir, entry.word)
      if (file.exists()) {
        file.delete()
      }

      dictionaryDataStore.updateData { currentDictionary ->
        val index = currentDictionary.entriesList.indexOfFirst { it.word == entry.word }
        if (index >= 0) {
          currentDictionary.toBuilder().removeEntries(index).build()
        } else {
          currentDictionary
        }
      }
    }
  }
}
