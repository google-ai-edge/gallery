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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.vocabview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.SavedBackgroundShapes
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.TtsLanguageUnsupportedDialog
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.UnsaveWordConfirmationDialog
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.VocabCard
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.rememberVocabThumbnailBitmap
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.EmptyState

/** Vertical gap between saved vocabulary cards in the single-column list. */
private val SAVED_CARD_SPACING = 12.dp

/** Size of the ⋮ button on the cards, which also sets the height of the card header row. */
private val SAVED_CARD_BUTTON_SIZE = 32.dp

private val SavedMenuShape = RoundedCornerShape(16.dp)

/** 12sp card text for the language chip. Figma drops the M3 letter spacing. */
private val SavedCardBodyStyle: TextStyle
  @Composable
  @ReadOnlyComposable
  get() = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp)

/**
 * Saved tab: centered "Saved" title and subtitle, word count, "All languages" filter dropdown, and
 * a single-column list of saved vocabulary cards ([DictionaryEntryItem]) over decorative background
 * shapes ([SavedBackgroundShapes]).
 */
@Composable
fun DictionaryScreen(
  entries: List<DictionaryEntry>,
  onDelete: (DictionaryEntry) -> Unit,
  onPlayPhrase: (String, Language) -> Unit,
  modifier: Modifier = Modifier,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap? = { _, _ -> null },
  isTtsLanguageSupported: (Language) -> Boolean = { true },
  onOpenAudioSettings: (() -> Unit)? = null,
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
) {
  val languages = remember(entries) { entries.map { it.displayLearnLanguage() }.distinct() }
  // Null means "All languages".
  var selectedLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var unsupportedTtsLanguage by remember { mutableStateOf<Language?>(null) }
  val activeLanguage = selectedLanguage?.takeIf { it in languages }
  val visibleEntries =
    if (activeLanguage == null) {
      entries
    } else {
      entries.filter { it.displayLearnLanguage() == activeLanguage }
    }
  val handlePlayPhrase: (String, Language) -> Unit = { phrase, language ->
    if (!isTtsLanguageSupported(language)) {
      unsupportedTtsLanguage = language
    } else {
      onPlayPhrase(phrase, language)
    }
  }

  Box(modifier = modifier.fillMaxSize().clipToBounds()) {
    SavedBackgroundShapes()
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(SAVED_CARD_SPACING),
    ) {
      item(key = "header") {
        SavedHeader(
          count = visibleEntries.size,
          selectedLanguage = activeLanguage,
          languages = languages,
          onLanguageChange = { selectedLanguage = it },
          // Together with the list spacing, this gives Figma's 16dp gap above the first card.
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      if (visibleEntries.isEmpty()) {
        item(key = "empty") {
          Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
          ) {
            EmptyState(
              titleResId = R.string.xrteacher_tab_vocabulary,
              descriptionResId = R.string.xrteacher_no_vocabulary,
            )
          }
        }
      } else {
        items(
          items = visibleEntries,
          key = { "${it.learnLanguage.name}_${it.word.lowercase()}" },
        ) { entry ->
          DictionaryEntryItem(
            entry = entry,
            onDelete = { onDelete(entry) },
            onPlayPhrase = handlePlayPhrase,
            loadVocabImageBitmap = loadVocabImageBitmap,
            phrasePlaybackState = phrasePlaybackState,
          )
        }
      }
    }
  }

  val currentUnsupportedLanguage = unsupportedTtsLanguage
  if (currentUnsupportedLanguage != null) {
    TtsLanguageUnsupportedDialog(
      language = currentUnsupportedLanguage,
      onDismiss = { unsupportedTtsLanguage = null },
      onOpenAudioSettings = onOpenAudioSettings,
    )
  }
}

/** "Saved" title, subtitle, and the row with the word count and the language filter dropdown. */
@Composable
private fun SavedHeader(
  count: Int,
  selectedLanguage: Language?,
  languages: List<Language>,
  onLanguageChange: (Language?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val allLanguagesLabel = stringResource(R.string.xrteacher_saved_all_languages)
  val options = remember(languages) { listOf<Language?>(null) + languages }

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.xrteacher_saved_header),
      style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = stringResource(R.string.xrteacher_saved_desc),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(27.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = pluralStringResource(R.plurals.xrteacher_saved_words_count, count, count),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      SavedFilterDropdown(
        selected = selectedLanguage,
        options = options,
        optionLabel = { lang -> if (lang == null) allLanguagesLabel else getLanguageName(lang) },
        onSelect = onLanguageChange,
      )
    }
  }
}

/**
 * Light rounded filter pill ("All languages ▾") that opens a menu of [options], with the current
 * option highlighted.
 */
@Composable
private fun <T> SavedFilterDropdown(
  selected: T,
  options: List<T>,
  optionLabel: @Composable (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    Row(
      modifier =
        Modifier.height(32.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .clickable(role = Role.DropdownList) { expanded = true }
          .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = optionLabel(selected),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
      Icon(
        imageVector = Icons.Filled.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
    SavedMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      for (option in options) {
        SavedMenuOption(
          text = optionLabel(option),
          isSelected = option == selected,
          onClick = {
            expanded = false
            onSelect(option)
          },
        )
      }
    }
  }
}

/** Dropdown menu styled like the Figma Saved/Study menus: light container, rounded corners. */
@Composable
private fun SavedMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    offset = DpOffset(x = 0.dp, y = 4.dp),
    shape = SavedMenuShape,
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    content = content,
  )
}

/** One filter option. The selected option gets a primaryContainer pill and a check mark. */
@Composable
private fun SavedMenuOption(text: String, isSelected: Boolean, onClick: () -> Unit) {
  DropdownMenuItem(
    text = {
      Text(
        text = text,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    },
    onClick = onClick,
    modifier =
      Modifier.padding(horizontal = 4.dp)
        .clip(CircleShape)
        .background(
          if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        )
        .semantics { selected = isSelected },
    trailingIcon =
      if (isSelected) {
        {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      } else {
        null
      },
  )
}

/**
 * A saved vocabulary card on the Saved tab, rendered as a full [VocabCard] with:
 * - Header row: language pill on the left and a ⋮ menu button on the right that opens Delete.
 * - Optional `96.dp` captured photo thumbnail, full non-truncated word, translation, and a `40.dp`
 *   primary speaker button.
 * - Full-width example sentence box with target word highlighting, native translation, and a
 *   `32.dp` speaker button.
 *
 * Selecting Delete in the ⋮ menu opens [UnsaveWordConfirmationDialog] before calling [onDelete].
 */
@Composable
fun DictionaryEntryItem(
  entry: DictionaryEntry,
  onDelete: () -> Unit,
  onPlayPhrase: (String, Language) -> Unit,
  modifier: Modifier = Modifier,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap? = { _, _ -> null },
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
) {
  val savedImageBitmap =
    rememberVocabThumbnailBitmap(entry.word, entry.learnLanguage, loadVocabImageBitmap)
  val language = entry.displayLearnLanguage()
  // Keyed by the entry so a pending confirmation never carries over to a different card.
  var showDeleteConfirmation by
    rememberSaveable(entry.learnLanguage, entry.word) { mutableStateOf(false) }

  VocabCard(
    vocabCardData =
      VocabCardData(
        word = entry.word,
        meaning = entry.translation,
        example = entry.targetSentence,
        exampleTranslation = entry.nativeSentence,
      ),
    thumbnail = savedImageBitmap,
    onPlayTargetPhrase = { phrase -> onPlayPhrase(phrase, language) },
    modifier = modifier,
    phrasePlaybackState = phrasePlaybackState,
    header = {
      Row(
        modifier = Modifier.fillMaxWidth().height(SAVED_CARD_BUTTON_SIZE),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LanguagePill(language = language)
        Spacer(modifier = Modifier.weight(1f))
        SavedCardMenuButton(word = entry.word, onDelete = { showDeleteConfirmation = true })
      }
    },
  )

  if (showDeleteConfirmation) {
    UnsaveWordConfirmationDialog(
      word = entry.word,
      titleResId = R.string.xrteacher_delete_vocab_card_dialog_title,
      bodyResId = R.string.xrteacher_delete_vocab_card_dialog_body,
      confirmResId = R.string.xrteacher_delete_vocab_card_confirm,
      onConfirm = {
        showDeleteConfirmation = false
        onDelete()
      },
      onDismiss = { showDeleteConfirmation = false },
    )
  }
}

/**
 * ⋮ button on a saved vocab card that opens a menu with "Delete". Drawn at 32dp to match the Figma
 * card header row height.
 */
@Composable
private fun SavedCardMenuButton(word: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    Box(
      modifier =
        Modifier.size(SAVED_CARD_BUTTON_SIZE).clip(CircleShape).clickable(role = Role.Button) {
          expanded = true
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.MoreVert,
        contentDescription = stringResource(R.string.xrteacher_saved_card_more_options_desc, word),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
    SavedMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = {
          Text(
            text = stringResource(R.string.xrteacher_delete_vocab_card_confirm),
            color = MaterialTheme.colorScheme.onSurface,
          )
        },
        onClick = {
          expanded = false
          onDelete()
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
      )
    }
  }
}

/** The language of a saved word, as a small `surface` pill at the top left of its card. */
@Composable
private fun LanguagePill(language: Language, modifier: Modifier = Modifier) {
  Text(
    text = getLanguageName(language),
    style = SavedCardBodyStyle,
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 1,
    modifier =
      modifier
        .background(MaterialTheme.colorScheme.surface, CircleShape)
        .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}
