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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.studyview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.VocabSpeaker
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.XrLanguageTeacherVocabStickerView
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.isLoading
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.rememberIsStickerCutout
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.rememberVocabThumbnailBitmap
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Maximum width and height of the uncropped photo on the answer side of a flash card. */
private val FLASHCARD_IMAGE_SIZE = 160.dp

@Composable
fun FlashcardReviewScreen(
  entries: List<DictionaryEntry>,
  language: Language,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onPlayPhrase: ((String, Language) -> Unit)? = null,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap? = { _, _ -> null },
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
) {
  var currentIndex by rememberSaveable { mutableIntStateOf(0) }
  var isFlipped by rememberSaveable { mutableStateOf(false) }
  var isAnimating by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val safeIndex = currentIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
  val currentEntry = entries.getOrNull(safeIndex)
  // Which speaker button of the current card was tapped: a word can be its own example sentence,
  // and only the tapped button shows the spinner.
  var tappedSpeaker by remember(currentEntry) { mutableStateOf<VocabSpeaker?>(null) }

  val rotation by
    animateFloatAsState(
      targetValue = if (isFlipped) 180f else 0f,
      animationSpec = tween(durationMillis = 400),
      label = "flip card",
    )

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
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
        Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.xrteacher_previous),
        )
      }
      Text(
        text = stringResource(R.string.xrteacher_flashcards_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.width(48.dp))
    }

    // Flashcard Area
    if (currentEntry != null) {
      Text(
        text =
          stringResource(R.string.xrteacher_flashcards_review_header, getLanguageName(language)),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
      )

      Text(
        text = stringResource(R.string.xrteacher_study_progress, safeIndex + 1, entries.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
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
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (rotation <= 90f) {
              // Front of card: display the word in the native language (no pronunciation button)
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp),
              ) {
                Text(
                  text = currentEntry.translation,
                  style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                  textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                  text = stringResource(R.string.xrteacher_tap_to_flip),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else {
              // Back of card: display image, learn-language word + play button, and example
              // sentence
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp).graphicsLayer { rotationY = 180f },
              ) {
                val currentBitmap =
                  rememberVocabThumbnailBitmap(
                    currentEntry.word,
                    currentEntry.learnLanguage,
                    loadVocabImageBitmap,
                  )
                if (currentBitmap != null) {
                  if (rememberIsStickerCutout(currentBitmap)) {
                    XrLanguageTeacherVocabStickerView(
                      image = currentBitmap,
                      contentDescription = stringResource(R.string.xrteacher_identified_object),
                      outlineWidth = 4.5.dp,
                      tiltDegrees = -6f,
                      cornerRadius = 16.dp,
                      modifier =
                        Modifier.padding(bottom = 12.dp)
                          .sizeIn(maxWidth = FLASHCARD_IMAGE_SIZE, maxHeight = FLASHCARD_IMAGE_SIZE),
                    )
                  } else {
                    // Show the full photo, uncropped.
                    Image(
                      bitmap = currentBitmap,
                      contentDescription = stringResource(R.string.xrteacher_identified_object),
                      contentScale = ContentScale.Fit,
                      modifier =
                        Modifier.padding(bottom = 12.dp)
                          .sizeIn(maxWidth = FLASHCARD_IMAGE_SIZE, maxHeight = FLASHCARD_IMAGE_SIZE)
                          .clip(RoundedCornerShape(16.dp)),
                    )
                  }
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                ) {
                  Text(
                    text = currentEntry.word,
                    style =
                      MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                  )
                  if (onPlayPhrase != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                      shape = CircleShape,
                      color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                      IconButton(
                        onClick = {
                          tappedSpeaker = VocabSpeaker.WORD
                          onPlayPhrase(currentEntry.word, language)
                        },
                        modifier = Modifier.size(40.dp),
                      ) {
                        if (
                          tappedSpeaker == VocabSpeaker.WORD &&
                            phrasePlaybackState.isLoading(currentEntry.word)
                        ) {
                          CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            strokeWidth = 2.dp,
                          )
                        } else {
                          Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.xrteacher_pronounce_word),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                          )
                        }
                      }
                    }
                  }
                }

                if (currentEntry.targetSentence.isNotEmpty()) {
                  Spacer(modifier = Modifier.height(12.dp))
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                  ) {
                    Text(
                      text = currentEntry.targetSentence,
                      style =
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                      color = MaterialTheme.colorScheme.primary,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onPlayPhrase != null) {
                      Spacer(modifier = Modifier.width(8.dp))
                      Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                      ) {
                        IconButton(
                          onClick = {
                            tappedSpeaker = VocabSpeaker.SENTENCE
                            onPlayPhrase(currentEntry.targetSentence, language)
                          },
                          modifier = Modifier.size(34.dp),
                        ) {
                          if (
                            tappedSpeaker == VocabSpeaker.SENTENCE &&
                              phrasePlaybackState.isLoading(currentEntry.targetSentence)
                          ) {
                            CircularProgressIndicator(
                              modifier = Modifier.size(18.dp),
                              color = MaterialTheme.colorScheme.onPrimaryContainer,
                              strokeWidth = 2.dp,
                            )
                          } else {
                            Icon(
                              imageVector = Icons.Default.PlayArrow,
                              contentDescription =
                                stringResource(R.string.xrteacher_pronounce_sentence),
                              tint = MaterialTheme.colorScheme.onPrimaryContainer,
                              modifier = Modifier.size(18.dp),
                            )
                          }
                        }
                      }
                    }
                  }
                  if (currentEntry.nativeSentence.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = currentEntry.nativeSentence,
                      style =
                        MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      textAlign = TextAlign.Center,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Navigation Controls
      fun navigateToCard(targetIndex: Int) {
        if (targetIndex !in entries.indices || isAnimating) return
        if (isFlipped) {
          isFlipped = false
          isAnimating = true
          scope.launch {
            delay(400)
            if (targetIndex in entries.indices) {
              currentIndex = targetIndex
            }
            isAnimating = false
          }
        } else {
          currentIndex = targetIndex
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = { navigateToCard(safeIndex - 1) },
          enabled = safeIndex > 0 && !isAnimating,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.xrteacher_previous))
        }

        Button(
          onClick = { navigateToCard(safeIndex + 1) },
          enabled = safeIndex < entries.size - 1 && !isAnimating,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
          Text(stringResource(R.string.xrteacher_next))
          Spacer(modifier = Modifier.width(8.dp))
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    } else {
      Text(stringResource(R.string.xrteacher_no_cards_left))
    }
  }
}
