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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Language

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  currentMainLanguage: Language,
  currentLearnLanguage: Language,
  onMainLanguageSelected: (Language) -> Unit,
  onLearnLanguageSelected: (Language) -> Unit,
  onNavigateToCamera: () -> Unit,
) {
  var mainExpanded by remember { mutableStateOf(false) }
  var learnExpanded by remember { mutableStateOf(false) }

  val languages =
    listOf(
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

  Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      // Main Language Dropdown
      Text(
        stringResource(R.string.artranslator_main_lang),
        style = MaterialTheme.typography.titleMedium,
      )
      ExposedDropdownMenuBox(
        expanded = mainExpanded,
        onExpandedChange = { mainExpanded = !mainExpanded },
      ) {
        OutlinedTextField(
          value = languages.firstOrNull { it.first == currentMainLanguage }?.second ?: "Unknown",
          onValueChange = {},
          readOnly = true,
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mainExpanded) },
          modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = mainExpanded, onDismissRequest = { mainExpanded = false }) {
          for ((lang, name) in languages) {
            DropdownMenuItem(
              text = { Text(name) },
              onClick = {
                onMainLanguageSelected(lang)
                mainExpanded = false
              },
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Learn Language Dropdown
      Text(
        stringResource(R.string.artranslator_learn_lang),
        style = MaterialTheme.typography.titleMedium,
      )
      ExposedDropdownMenuBox(
        expanded = learnExpanded,
        onExpandedChange = { learnExpanded = !learnExpanded },
      ) {
        OutlinedTextField(
          value = languages.firstOrNull { it.first == currentLearnLanguage }?.second ?: "Unknown",
          onValueChange = {},
          readOnly = true,
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = learnExpanded) },
          modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
          expanded = learnExpanded,
          onDismissRequest = { learnExpanded = false },
        ) {
          for ((lang, name) in languages) {
            DropdownMenuItem(
              text = { Text(name) },
              onClick = {
                onLearnLanguageSelected(lang)
                learnExpanded = false
              },
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Button(onClick = onNavigateToCamera) {
        Text(stringResource(R.string.artranslator_start_translating))
      }
    }
  }
}
