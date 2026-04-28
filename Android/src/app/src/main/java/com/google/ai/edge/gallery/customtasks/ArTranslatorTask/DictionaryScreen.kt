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

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(entries: List<DictionaryEntry>, onDelete: (String) -> Unit) {
  val languageNames =
    mapOf(
      Language.LANGUAGE_ENGLISH to stringResource(R.string.artranslator_language_english),
      Language.LANGUAGE_CHINESE to stringResource(R.string.artranslator_language_chinese),
      Language.LANGUAGE_FRENCH to stringResource(R.string.artranslator_language_french),
      Language.LANGUAGE_SPANISH to stringResource(R.string.artranslator_language_spanish),
      Language.LANGUAGE_KOREAN to stringResource(R.string.artranslator_language_korean),
      Language.LANGUAGE_HINDI to stringResource(R.string.artranslator_language_hindi),
      Language.LANGUAGE_JAPANESE to stringResource(R.string.artranslator_language_japanese),
      Language.LANGUAGE_GERMAN to stringResource(R.string.artranslator_language_german),
      Language.LANGUAGE_ITALIAN to stringResource(R.string.artranslator_language_italian),
      Language.LANGUAGE_PORTUGUESE to stringResource(R.string.artranslator_language_portuguese),
      Language.LANGUAGE_THAI to stringResource(R.string.artranslator_language_thai),
      Language.LANGUAGE_VIETNAMESE to stringResource(R.string.artranslator_language_vietnamese),
      Language.LANGUAGE_ARABIC to stringResource(R.string.artranslator_language_arabic),
      Language.LANGUAGE_RUSSIAN to stringResource(R.string.artranslator_language_russian),
    )

  val groupedEntries = entries.groupBy {
    if (it.learnLanguage == Language.LANGUAGE_UNSPECIFIED) Language.LANGUAGE_CHINESE
    else it.learnLanguage
  }

  // Track expanded state for each language. Defaults to true (expanded).
  val expandedStates = remember { mutableStateMapOf<Language, Boolean>() }

  Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      for ((lang, filteredEntries) in groupedEntries) {
        val name = languageNames[lang] ?: lang.name
        item {
          val isExpanded = expandedStates[lang] ?: true
          Card(
            modifier =
              Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                expandedStates[lang] = !isExpanded
              }
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
              )
              Icon(
                imageVector =
                  if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
        }

        if (expandedStates[lang] ?: true) {
          items(filteredEntries) { entry ->
            DictionaryEntryItem(entry = entry, onDelete = { onDelete(entry.word) })
            Spacer(modifier = Modifier.height(8.dp))
          }
        }
      }
    }
  }
}

@Composable
fun DictionaryEntryItem(entry: DictionaryEntry, onDelete: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val context = LocalContext.current
      var bitmap by remember(entry.word) { mutableStateOf<ImageBitmap?>(null) }
      LaunchedEffect(entry.word) {
        withContext(Dispatchers.IO) {
          val file = File(File(context.filesDir, "ar_translator_images"), entry.word)
          if (file.exists()) {
            bitmap = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
          }
        }
      }
      val currentBitmap = bitmap
      if (currentBitmap != null) {
        Image(
          bitmap = currentBitmap,
          contentDescription = "Word image",
          modifier = Modifier.height(100.dp).padding(end = 8.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = entry.word, style = MaterialTheme.typography.titleMedium)
        Text(
          text = stringResource(R.string.artranslator_dict_translation, entry.translation),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.artranslator_dict_definition, entry.definition),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
    }
  }
}
