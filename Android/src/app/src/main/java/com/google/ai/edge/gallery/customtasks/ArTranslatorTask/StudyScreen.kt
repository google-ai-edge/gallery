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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudyScreen(entries: List<DictionaryEntry>) {
  var selectedLanguage by remember { mutableStateOf<Language?>(null) }

  if (selectedLanguage == null) {
    val groupedByLang = entries.groupBy { it.learnLanguage }.filterValues { it.isNotEmpty() }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Column(modifier = Modifier.fillMaxSize()) {
        Text(
          text = stringResource(R.string.artranslator_flashcards),
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(bottom = 16.dp),
        )

        if (groupedByLang.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = stringResource(R.string.artranslator_no_vocabulary),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(32.dp),
            )
          }
        } else {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(groupedByLang.keys.toList()) { lang ->
              val langEntries = groupedByLang[lang] ?: emptyList()
              LanguageCard(
                language = lang,
                wordCount = langEntries.size,
                onClick = { selectedLanguage = lang },
              )
            }
          }
        }
      }
    }
  } else {
    val flashcards = entries.filter { it.learnLanguage == selectedLanguage }
    FlashcardReviewScreen(
      entries = flashcards,
      language = selectedLanguage!!,
      onBack = { selectedLanguage = null },
    )
  }
}

@Composable
fun LanguageCard(language: Language, wordCount: Int, onClick: () -> Unit) {
  val langName = getLanguageName(language)

  Card(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text(
          text = langName,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text =
            pluralStringResource(R.plurals.artranslator_flashcards_count, wordCount, wordCount),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.MenuBook,
        contentDescription = "Study $langName",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
fun FlashcardReviewScreen(entries: List<DictionaryEntry>, language: Language, onBack: () -> Unit) {
  var currentIndex by remember { mutableIntStateOf(0) }
  var isFlipped by remember { mutableStateOf(false) }
  var isAnimating by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val currentEntry = entries.getOrNull(currentIndex)

  val rotation by
    animateFloatAsState(
      targetValue = if (isFlipped) 180f else 0f,
      animationSpec = tween(durationMillis = 400),
      label = "flip card",
    )

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.SpaceBetween,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Top Bar
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
      Text(
        text = getLanguageName(language),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.width(48.dp)) // balance the back button
    }

    // Flashcard Area
    if (currentEntry != null) {
      Text(
        text = stringResource(R.string.artranslator_study_progress, currentIndex + 1, entries.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp)) {
        Card(
          modifier =
            Modifier.fillMaxSize()
              .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
              }
              .clickable { isFlipped = !isFlipped },
          shape = RoundedCornerShape(24.dp),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (rotation <= 90f) {
              // Front of card: display the word in target language
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp),
              ) {
                Text(
                  text = currentEntry.word,
                  style =
                    MaterialTheme.typography.headlineLarge.copy(
                      fontSize = 36.sp,
                      fontWeight = FontWeight.Bold,
                    ),
                  color = MaterialTheme.colorScheme.onSurface,
                  textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                  text = stringResource(R.string.artranslator_tap_to_flip),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            } else {
              // Back of card: display transaction and definition
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp).graphicsLayer { rotationY = 180f },
              ) {
                val context = LocalContext.current
                var bitmap by remember(currentEntry.word) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(currentEntry.word) {
                  withContext(Dispatchers.IO) {
                    val file =
                      File(File(context.filesDir, "ar_translator_images"), currentEntry.word)
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
                    modifier = Modifier.height(200.dp).padding(bottom = 16.dp),
                  )
                }
                Text(
                  text = currentEntry.translation,
                  style =
                    MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                  textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = currentEntry.definition,
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
      }

      // Navigation Controls
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = {
            if (currentIndex > 0 && !isAnimating) {
              if (isFlipped) {
                isFlipped = false
                isAnimating = true
                scope.launch {
                  delay(400)
                  if (currentIndex > 0) currentIndex--
                  isAnimating = false
                }
              } else {
                currentIndex--
              }
            }
          },
          enabled = currentIndex > 0 && !isAnimating,
        ) {
          Text(stringResource(R.string.artranslator_previous))
        }

        TextButton(
          onClick = {
            if (currentIndex < entries.size - 1 && !isAnimating) {
              if (isFlipped) {
                isFlipped = false
                isAnimating = true
                scope.launch {
                  delay(400)
                  if (currentIndex < entries.size - 1) currentIndex++
                  isAnimating = false
                }
              } else {
                currentIndex++
              }
            }
          },
          enabled = currentIndex < entries.size - 1 && !isAnimating,
        ) {
          Text(stringResource(R.string.artranslator_next))
        }
      }
    } else {
      Text(stringResource(R.string.artranslator_no_cards_left))
    }
  }
}

fun getLanguageName(language: Language): String {
  return when (language) {
    Language.LANGUAGE_ENGLISH -> "English"
    Language.LANGUAGE_CHINESE -> "Chinese"
    Language.LANGUAGE_FRENCH -> "French"
    Language.LANGUAGE_SPANISH -> "Spanish"
    Language.LANGUAGE_KOREAN -> "Korean"
    Language.LANGUAGE_HINDI -> "Hindi"
    else -> "Unknown"
  }
}
