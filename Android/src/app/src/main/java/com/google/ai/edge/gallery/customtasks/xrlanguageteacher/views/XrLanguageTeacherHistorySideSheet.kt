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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.LessonHistorySession
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherChatTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayNameResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.findSavedEntry
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.formatLessonSessionTimestamp
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.SideSheetDialog

/**
 * Right-anchored slide-in side sheet displaying the user's AR Language Teacher lesson sessions
 * (newest first, with the active session expanded at the top).
 */
@Composable
fun SessionHistorySideSheet(
  sessions: List<LessonHistorySession>,
  persona: CharacterPersona,
  savedWords: List<DictionaryEntry>,
  loadSessionImageBitmap: suspend (String) -> ImageBitmap?,
  onExpandSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onDeleteAllSessions: () -> Unit,
  onSaveVocabCard: (LessonHistorySession, VocabCardData) -> Unit,
  onUnsaveVocabCard: (VocabCardData, Language) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current
  SideSheetDialog(onDismissed = onDismissRequest) { requestDismiss ->
    SessionHistorySideSheetContent(
      sessions = sessions,
      persona = persona,
      savedWords = savedWords,
      loadSessionImageBitmap = loadSessionImageBitmap,
      onExpandSession = onExpandSession,
      onDeleteSession = onDeleteSession,
      onDeleteAllSessions = onDeleteAllSessions,
      onSaveVocabCard = onSaveVocabCard,
      onUnsaveVocabCard = onUnsaveVocabCard,
      onDismissRequest = requestDismiss,
      formatTimestamp = { timestampMs -> formatLessonSessionTimestamp(context, timestampMs) },
    )
  }
}

/**
 * Content of the AR Language Teacher Session history side sheet.
 *
 * Vocabulary cards in expanded sessions show a bookmark that is filled when the card's word is in
 * [savedWords] for the session's learn language. Tapping it calls [onSaveVocabCard] for an unsaved
 * card, or asks for confirmation and then calls [onUnsaveVocabCard] for a saved one.
 *
 * [formatTimestamp] formats a session's start time in milliseconds for its row.
 */
@Composable
fun SessionHistorySideSheetContent(
  sessions: List<LessonHistorySession>,
  persona: CharacterPersona,
  savedWords: List<DictionaryEntry>,
  loadSessionImageBitmap: suspend (String) -> ImageBitmap?,
  onExpandSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onDeleteAllSessions: () -> Unit,
  onSaveVocabCard: (LessonHistorySession, VocabCardData) -> Unit,
  onUnsaveVocabCard: (VocabCardData, Language) -> Unit,
  onDismissRequest: () -> Unit,
  formatTimestamp: (Long) -> String,
  modifier: Modifier = Modifier,
) {
  val expandedBySessionId = remember { mutableStateMapOf<String, Boolean>() }
  var sessionIdPendingDelete by remember { mutableStateOf<String?>(null) }
  var showClearAllDialog by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize()) {
    // Header: Title + session count subtitle + Clear all TextButton + Close IconButton
    Row(
      modifier =
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.xrteacher_session_history_title),
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text =
            pluralStringResource(
              R.plurals.xrteacher_session_history_count,
              sessions.size,
              sessions.size,
            ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (sessions.isNotEmpty()) {
        TextButton(onClick = { showClearAllDialog = true }) {
          Text(stringResource(R.string.clear_all))
        }
      }
      IconButton(onClick = onDismissRequest) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_icon),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    if (sessions.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = stringResource(R.string.xrteacher_session_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(sessions, key = { it.sessionId }) { session ->
          val isExpanded = expandedBySessionId[session.sessionId] ?: session.isActiveSession
          LessonHistorySessionCard(
            session = session,
            formattedTimestamp = formatTimestamp(session.timestampMs),
            persona = if (session.isActiveSession) persona else session.persona,
            savedWords = savedWords,
            loadSessionImageBitmap = loadSessionImageBitmap,
            isExpanded = isExpanded,
            onToggleExpand = {
              val nextExpanded = !isExpanded
              expandedBySessionId[session.sessionId] = nextExpanded
              if (nextExpanded) {
                onExpandSession(session.sessionId)
              }
            },
            onRequestDelete = { sessionIdPendingDelete = session.sessionId },
            onSaveVocabCard = { card -> onSaveVocabCard(session, card) },
            onUnsaveVocabCard = onUnsaveVocabCard,
          )
        }
      }
    }
  }

  val pendingDeleteId = sessionIdPendingDelete
  if (pendingDeleteId != null) {
    AlertDialog(
      onDismissRequest = { sessionIdPendingDelete = null },
      title = { Text(stringResource(R.string.clear_history_dialog_title)) },
      text = { Text(stringResource(R.string.clear_history_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            sessionIdPendingDelete = null
            expandedBySessionId.remove(pendingDeleteId)
            onDeleteSession(pendingDeleteId)
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { sessionIdPendingDelete = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (showClearAllDialog) {
    AlertDialog(
      onDismissRequest = { showClearAllDialog = false },
      title = { Text(stringResource(R.string.clear_history_dialog_title)) },
      text = { Text(stringResource(R.string.clear_history_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            showClearAllDialog = false
            expandedBySessionId.clear()
            onDeleteAllSessions()
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearAllDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

@Composable
private fun LessonHistorySessionCard(
  session: LessonHistorySession,
  formattedTimestamp: String,
  persona: CharacterPersona,
  savedWords: List<DictionaryEntry>,
  loadSessionImageBitmap: suspend (String) -> ImageBitmap?,
  isExpanded: Boolean,
  onToggleExpand: () -> Unit,
  onRequestDelete: () -> Unit,
  onSaveVocabCard: (VocabCardData) -> Unit,
  onUnsaveVocabCard: (VocabCardData, Language) -> Unit,
) {
  val chevronRotation by
    animateFloatAsState(
      targetValue = if (isExpanded) 180f else 0f,
      animationSpec = tween(durationMillis = 200),
      label = "SessionCardChevronRotation",
    )
  val primaryVocabCard = session.primaryVocabCard?.takeIf { it.word.isNotBlank() }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (session.isActiveSession) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
          } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
          },
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SessionThumbnail(
          inMemoryBitmap = session.inMemoryBitmap,
          imageFilePath = session.imageFilePath,
          contentDescription =
            primaryVocabCard?.word ?: stringResource(R.string.xrteacher_session_photo_desc),
          loadSessionImageBitmap = loadSessionImageBitmap,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = session.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val meaning = primaryVocabCard?.meaning
          if (!meaning.isNullOrBlank()) {
            Text(
              text = meaning,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = formattedTimestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        IconButton(onClick = onRequestDelete, modifier = Modifier.size(36.dp)) {
          Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.xrteacher_session_delete_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }

        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription =
              stringResource(
                if (isExpanded) {
                  R.string.xrteacher_session_collapse_desc
                } else {
                  R.string.xrteacher_session_expand_desc
                }
              ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).rotate(chevronRotation),
          )
        }
      }

      AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150)),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

          if (session.greeting.isNotBlank()) {
            SessionTurnBubble(
              senderLabel = stringResource(persona.displayNameResId()),
              text = session.greeting,
              isUser = false,
            )
          }

          val sessionLearnLanguage = session.learnLanguage
          for (item in session.transcriptItems()) {
            when (item) {
              is SessionTranscriptItem.Turn ->
                SessionTurnBubble(
                  senderLabel =
                    if (item.turn.isUser) {
                      stringResource(R.string.xrteacher_chat_history_you)
                    } else {
                      stringResource(persona.displayNameResId())
                    },
                  text = item.turn.text,
                  isUser = item.turn.isUser,
                )
              is SessionTranscriptItem.Card ->
                SessionVocabCardRow(
                  card = item.card,
                  // Cards of sessions saved before languages were stored can't be bookmarked.
                  isSaved =
                    sessionLearnLanguage?.let {
                      savedWords.findSavedEntry(item.card.word, it) != null
                    },
                  onSave = { onSaveVocabCard(item.card) },
                  onUnsave = {
                    if (sessionLearnLanguage != null) {
                      onUnsaveVocabCard(item.card, sessionLearnLanguage)
                    }
                  },
                )
            }
          }
        }
      }
    }
  }
}

/** An entry in the expanded transcript of a lesson session. */
private sealed interface SessionTranscriptItem {
  /** A user question or teacher reply. */
  data class Turn(val turn: TeacherChatTurn) : SessionTranscriptItem

  /** A vocabulary card the preceding teacher reply produced. */
  data class Card(val card: VocabCardData) : SessionTranscriptItem
}

/**
 * Lists the turns of this session in order, each teacher reply followed by the vocabulary card it
 * produced. The primary card belongs to the first teacher reply and is skipped when that reply
 * already carries a card for the same word; without any teacher reply it comes last.
 */
private fun LessonHistorySession.transcriptItems(): List<SessionTranscriptItem> {
  val primaryCard = primaryVocabCard?.takeIf { it.word.isNotBlank() }
  val firstTeacherTurnIndex = turns.indexOfFirst { !it.isUser }
  return buildList {
    turns.forEachIndexed { index, turn ->
      add(SessionTranscriptItem.Turn(turn))
      if (turn.isUser) return@forEachIndexed
      val turnCard = turn.vocabCard?.takeIf { it.word.isNotBlank() }
      if (
        index == firstTeacherTurnIndex &&
          primaryCard != null &&
          (turnCard == null || !isSameVocabWord(turnCard.word, primaryCard.word))
      ) {
        add(SessionTranscriptItem.Card(primaryCard))
      }
      if (turnCard != null) {
        add(SessionTranscriptItem.Card(turnCard))
      }
    }
    if (firstTeacherTurnIndex < 0 && primaryCard != null) {
      add(SessionTranscriptItem.Card(primaryCard))
    }
  }
}

private fun isSameVocabWord(wordA: String, wordB: String): Boolean =
  wordA.equals(wordB, ignoreCase = true) ||
    XrLanguageTeacherDialogueFormatter.isSameVocabularyWord(wordA, wordB)

/**
 * 56dp rounded-square, center-cropped thumbnail of the session image: [inMemoryBitmap] for the
 * active session, else the image loaded from [imageFilePath]. Shows a neutral image tile when there
 * is no image or it fails to load (for example, after the file was evicted).
 */
@Composable
private fun SessionThumbnail(
  inMemoryBitmap: Bitmap?,
  imageFilePath: String?,
  contentDescription: String,
  loadSessionImageBitmap: suspend (String) -> ImageBitmap?,
) {
  val inMemoryImageBitmap =
    remember(inMemoryBitmap) { inMemoryBitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
  val imageBitmap by
    produceState<ImageBitmap?>(
      initialValue = inMemoryImageBitmap,
      inMemoryImageBitmap,
      imageFilePath,
    ) {
      if (inMemoryImageBitmap != null) {
        value = inMemoryImageBitmap
        return@produceState
      }
      val path = imageFilePath
      value = if (path.isNullOrBlank()) null else loadSessionImageBitmap(path)
    }

  val thumbnailModifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
  val resolvedBitmap = imageBitmap
  if (resolvedBitmap != null) {
    Image(
      bitmap = resolvedBitmap,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = thumbnailModifier,
    )
  } else {
    Box(
      modifier = thumbnailModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Outlined.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SessionTurnBubble(senderLabel: String, text: String, isUser: Boolean) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    Text(
      text = senderLabel,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
    )
    Surface(
      shape = RoundedCornerShape(16.dp),
      color =
        if (isUser) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceContainerHigh
        },
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }
  }
}

/**
 * A vocabulary card chip in the expanded session transcript with a bookmark toggle next to it.
 *
 * [isSaved] is null when the card can't be saved (no stored learn language), which hides the
 * bookmark. Tapping an unsaved bookmark calls [onSave]; tapping a saved one opens
 * [UnsaveWordConfirmationDialog] and calls [onUnsave] only on confirm.
 */
@Composable
private fun SessionVocabCardRow(
  card: VocabCardData,
  isSaved: Boolean?,
  onSave: () -> Unit,
  onUnsave: () -> Unit,
) {
  // Keyed by word so a pending confirmation never carries over to a different card.
  var showUnsaveConfirmation by rememberSaveable(card.word) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
      modifier = Modifier.weight(1f, fill = false),
    ) {
      Text(
        text =
          if (card.meaning.isNotBlank()) {
            stringResource(R.string.xrteacher_session_detected_vocab, card.word, card.meaning)
          } else {
            card.word
          },
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    if (isSaved != null) {
      IconToggleButton(
        checked = isSaved,
        onCheckedChange = { checked ->
          if (checked) {
            onSave()
          } else {
            showUnsaveConfirmation = true
          }
        },
        modifier = Modifier.semantics { role = Role.Switch },
      ) {
        Icon(
          imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
          contentDescription = stringResource(R.string.xrteacher_session_save_word_desc, card.word),
          tint =
            if (isSaved) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }

  if (showUnsaveConfirmation) {
    UnsaveWordConfirmationDialog(
      word = card.word,
      onConfirm = {
        showUnsaveConfirmation = false
        onUnsave()
      },
      onDismiss = { showUnsaveConfirmation = false },
    )
  }
}
