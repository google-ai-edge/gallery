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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.StudyBackgroundShapes
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.TtsLanguageUnsupportedDialog
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.random.Random

/** The card counts offered on the Flash cards start screen, besides all the cards. */
private val FLASHCARDS_CARD_COUNTS = listOf(5, 10, 20)

/** The card count picked on the Flash cards start screen until the player picks another. */
private const val FLASHCARDS_DEFAULT_CARD_COUNT = 10

/**
 * The Study tab: a centered header, the Flash cards activity card ([FlashcardsStudyCard]), and a
 * note that more minigames are coming, over decorative background shapes ([StudyBackgroundShapes]).
 *
 * Tapping "Open flashcards" opens the reusable [VocabGameStartScreen], where the player reads how
 * it works and picks the language and number of cards. Tapping "Start review" launches
 * [FlashcardReviewScreen] with the picked number of cards; leaving the review comes straight back
 * to the Study cards.
 *
 * Everything but the Study cards is reported through [onReviewModeChanged], so the caller can hide
 * its navigation meanwhile.
 */
@Composable
fun StudyScreen(
  entries: List<DictionaryEntry>,
  modifier: Modifier = Modifier,
  onReviewModeChanged: (Boolean) -> Unit = {},
  onPlayPhrase: ((String, Language) -> Unit)? = null,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap? = { _, _ -> null },
  isTtsLanguageSupported: (Language) -> Boolean = { true },
  onOpenAudioSettings: (() -> Unit)? = null,
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
) {
  var reviewLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var isFlashcardsStartOpen by rememberSaveable { mutableStateOf(false) }
  var flashcardsLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var flashcardsCardCount by rememberSaveable { mutableIntStateOf(FLASHCARDS_DEFAULT_CARD_COUNT) }
  var reviewSessionSeed by rememberSaveable { mutableIntStateOf(0) }
  var unsupportedTtsLanguage by remember { mutableStateOf<Language?>(null) }

  val isAwayFromCards = reviewLanguage != null || isFlashcardsStartOpen
  LaunchedEffect(isAwayFromCards) { onReviewModeChanged(isAwayFromCards) }

  DisposableEffect(Unit) { onDispose { onReviewModeChanged(false) } }

  val languages = remember(entries) { entries.map { it.displayLearnLanguage() }.distinct() }
  val supportedLanguages =
    remember(languages, isTtsLanguageSupported) { languages.filter { isTtsLanguageSupported(it) } }

  // Closing the review goes straight back to the Study cards, like leaving a game.
  val activeReviewLanguage = reviewLanguage
  if (activeReviewLanguage != null) {
    val deck =
      remember(entries, activeReviewLanguage, flashcardsCardCount, reviewSessionSeed) {
        val shuffled =
          entries
            .filter { it.displayLearnLanguage() == activeReviewLanguage }
            .shuffled(Random(reviewSessionSeed))
        if (flashcardsCardCount > 0) shuffled.take(flashcardsCardCount) else shuffled
      }
    FlashcardReviewScreen(
      entries = deck,
      language = activeReviewLanguage,
      onPlayPhrase = onPlayPhrase,
      loadVocabImageBitmap = loadVocabImageBitmap,
      phrasePlaybackState = phrasePlaybackState,
      onBack = {
        reviewLanguage = null
        isFlashcardsStartOpen = false
      },
      modifier = modifier,
    )
    return
  }

  if (isFlashcardsStartOpen) {
    BackHandler { isFlashcardsStartOpen = false }
    val language = resolveStudyLanguage(flashcardsLanguage, languages, supportedLanguages)
    val deckSize =
      if (language != null) {
        entries.count { it.displayLearnLanguage() == language }
      } else {
        0
      }
    // Only counts smaller than the deck, then "All" (0), which shows the whole deck.
    val cardCounts = FLASHCARDS_CARD_COUNTS.filter { it < deckSize } + 0
    val cardCount = flashcardsCardCount.takeIf { it in cardCounts } ?: 0
    VocabGameStartScreen(
      title = stringResource(R.string.xrteacher_flashcards_title),
      subtitle = stringResource(R.string.xrteacher_flashcards_subtitle),
      steps =
        listOf(
          stringResource(R.string.xrteacher_flashcards_step_1),
          stringResource(R.string.xrteacher_flashcards_step_2),
          stringResource(R.string.xrteacher_flashcards_step_3),
        ),
      isPlayEnabled = deckSize > 0 && language != null && isTtsLanguageSupported(language),
      lockedMessage = null,
      onBack = { isFlashcardsStartOpen = false },
      onPlay = {
        if (language != null && !isTtsLanguageSupported(language)) {
          unsupportedTtsLanguage = language
        } else {
          flashcardsCardCount = cardCount
          reviewSessionSeed++
          reviewLanguage = language
        }
      },
      modifier = modifier,
      playLabel = stringResource(R.string.xrteacher_flashcards_start_review),
      playIcon = Icons.Filled.School,
      languageSetting = {
        VocabGameLanguageDropdown(
          languages = languages,
          selected = language,
          onSelect = { flashcardsLanguage = it },
          isLanguageEnabled = isTtsLanguageSupported,
        )
      },
    ) {
      VocabGameDropdown(
        label = stringResource(R.string.xrteacher_flashcards_setting_count),
        options = cardCounts,
        selected = cardCount,
        optionLabel = { count ->
          if (count > 0) {
            pluralStringResource(R.plurals.xrteacher_flashcards_card_count, count, count)
          } else {
            stringResource(R.string.xrteacher_flashcards_card_count_all, deckSize)
          }
        },
        placeholder = "",
        onSelect = { flashcardsCardCount = it },
      )
    }
    val currentUnsupportedLanguage = unsupportedTtsLanguage
    if (currentUnsupportedLanguage != null) {
      TtsLanguageUnsupportedDialog(
        language = currentUnsupportedLanguage,
        onDismiss = { unsupportedTtsLanguage = null },
        onOpenAudioSettings = onOpenAudioSettings,
      )
    }
    return
  }

  Box(modifier = modifier.fillMaxSize().clipToBounds()) {
    StudyBackgroundShapes()
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.xrteacher_study_header),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.xrteacher_study_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(24.dp))
      val noSupportedLanguagesWarning =
        if (languages.isNotEmpty() && supportedLanguages.isEmpty()) {
          stringResource(R.string.xrteacher_study_tts_unsupported_warning)
        } else {
          null
        }
      FlashcardsStudyCard(
        isOpenEnabled = supportedLanguages.isNotEmpty(),
        onOpen = { isFlashcardsStartOpen = true },
        lockedMessage = noSupportedLanguagesWarning,
        onOpenAudioSettings = onOpenAudioSettings,
      )
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text =
          stringResource(
            if (languages.isEmpty()) {
              R.string.xrteacher_no_vocabulary
            } else {
              R.string.xrteacher_minigames_coming_soon
            }
          ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/**
 * The language a Study card uses: [selected] if it still has saved words and is supported by the
 * active TTS engine, otherwise the first TTS-supported language in [supported], otherwise the first
 * language in [available].
 */
private fun resolveStudyLanguage(
  selected: Language?,
  available: List<Language>,
  supported: List<Language>,
): Language? =
  selected?.takeIf { it in available && it in supported }
    ?: supported.firstOrNull()
    ?: available.firstOrNull()
