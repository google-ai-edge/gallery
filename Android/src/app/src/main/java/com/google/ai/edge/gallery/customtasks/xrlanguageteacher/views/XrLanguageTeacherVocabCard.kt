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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData

/** Size of the captured object thumbnail on the vocab card and its loading placeholder. */
internal val VOCAB_THUMBNAIL_SIZE = 96.dp

/**
 * Vocab Card drawn flat on a `surfaceContainerLow` card (no shadow) in the camera lesson sheet and
 * the expanded Saved card dialog:
 * - Optional [header] (the expanded Saved card dialog shows the language pill and a close button).
 * - Top row: `96.dp` object [thumbnail], the target word (`22.sp` bold, `onSurface`) above its
 *   muted translation (`15.sp`, `onSurfaceVariant`), and a `40.dp` solid `primary` circular "Play
 *   word" speaker button at the far right.
 * - Full-width `surfaceContainerHigh` example sentence box ([ExampleSentenceBox]).
 * - Optional [footer] (the camera lesson sheet shows its Save to Vocab button there).
 *
 * The speaker button the user tapped shows a spinner while its phrase loads
 * ([phrasePlaybackState]). The other button doesn't, even when the word and its example sentence
 * are the same text.
 */
@Composable
internal fun VocabCard(
  vocabCardData: VocabCardData,
  thumbnail: ImageBitmap?,
  onPlayTargetPhrase: (String) -> Unit,
  modifier: Modifier = Modifier,
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
  header: (@Composable () -> Unit)? = null,
  footer: (@Composable () -> Unit)? = null,
  isPlayEnabled: Boolean = true,
) {
  val textMaxLines = if (header != null) Int.MAX_VALUE else 2
  // Disabled speakers use the standard GM3 disabled colors.
  val disabledContainer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
  val disabledContent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  // The playback state only names the text, and a word can be its own example sentence, so the
  // card remembers which of its buttons was tapped.
  var tappedSpeaker by remember(vocabCardData) { mutableStateOf<VocabSpeaker?>(null) }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      header?.invoke()

      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (thumbnail != null) {
          Image(
            bitmap = thumbnail,
            contentDescription = stringResource(R.string.xrteacher_identified_object),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(VOCAB_THUMBNAIL_SIZE).clip(RoundedCornerShape(16.dp)),
          )
          Spacer(modifier = Modifier.width(14.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = vocabCardData.word,
            style =
              MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
              ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = textMaxLines,
            overflow = TextOverflow.Ellipsis,
          )
          if (vocabCardData.meaning.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = vocabCardData.meaning,
              style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = textMaxLines,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          onClick = {
            tappedSpeaker = VocabSpeaker.WORD
            onPlayTargetPhrase(vocabCardData.word)
          },
          enabled = isPlayEnabled,
          shape = CircleShape,
          color = if (isPlayEnabled) MaterialTheme.colorScheme.primary else disabledContainer,
          modifier = Modifier.size(40.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            PhraseSpeakerIcon(
              isLoading =
                tappedSpeaker == VocabSpeaker.WORD &&
                  phrasePlaybackState.isLoading(vocabCardData.word),
              contentDescription = stringResource(R.string.xrteacher_pronounce_word),
              tint = if (isPlayEnabled) MaterialTheme.colorScheme.onPrimary else disabledContent,
              size = 20.dp,
            )
          }
        }
      }

      if (vocabCardData.example.isNotBlank()) {
        ExampleSentenceBox(
          vocabCardData = vocabCardData,
          isLoading =
            tappedSpeaker == VocabSpeaker.SENTENCE &&
              phrasePlaybackState.isLoading(vocabCardData.example),
          onPlaySentence = {
            tappedSpeaker = VocabSpeaker.SENTENCE
            onPlayTargetPhrase(vocabCardData.example)
          },
          isPlayEnabled = isPlayEnabled,
        )
      }

      footer?.invoke()
    }
  }
}

/**
 * Full-width `surfaceContainerHigh` box with the bold example sentence (the target word highlighted
 * in `primary`), its muted native translation, and a `32.dp` `secondaryContainer` speaker button
 * that shows a spinner while [isLoading].
 */
@Composable
private fun ExampleSentenceBox(
  vocabCardData: VocabCardData,
  isLoading: Boolean,
  onPlaySentence: () -> Unit,
  isPlayEnabled: Boolean = true,
) {
  val highlightColor = MaterialTheme.colorScheme.primary
  val highlightedExample =
    remember(vocabCardData.example, vocabCardData.word, highlightColor) {
      highlightTargetWord(vocabCardData.example, vocabCardData.word, highlightColor)
    }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = highlightedExample,
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
            ),
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (vocabCardData.exampleTranslation.isNotBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = vocabCardData.exampleTranslation,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Surface(
        onClick = onPlaySentence,
        enabled = isPlayEnabled,
        shape = CircleShape,
        color =
          if (isPlayEnabled) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          PhraseSpeakerIcon(
            isLoading = isLoading,
            contentDescription = stringResource(R.string.xrteacher_pronounce_sentence),
            tint =
              if (isPlayEnabled) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            size = 16.dp,
          )
        }
      }
    }
  }
}

/** A speaker button of a vocabulary card: the word's, or the example sentence's. */
internal enum class VocabSpeaker {
  WORD,
  SENTENCE,
}

/** Whether [phrase] is the vocabulary phrase that is loading, before its audio starts. */
internal fun PhrasePlaybackState.isLoading(phrase: String): Boolean =
  this is PhrasePlaybackState.Loading && this.phrase == phrase

/**
 * The icon of a vocabulary speaker button: a speaker, or a spinner of the same [size] and [tint]
 * while the phrase loads ([isLoading]). Tapping the button while it loads stops the phrase.
 */
@Composable
internal fun PhraseSpeakerIcon(
  isLoading: Boolean,
  contentDescription: String,
  tint: Color,
  size: Dp,
) {
  if (isLoading) {
    CircularProgressIndicator(
      modifier = Modifier.size(size).semantics { this.contentDescription = contentDescription },
      color = tint,
      strokeWidth = 2.dp,
    )
  } else {
    Icon(
      imageVector = Icons.AutoMirrored.Filled.VolumeUp,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(size),
    )
  }
}

/**
 * Returns [example] with the first case-insensitive occurrence of [word] colored [highlightColor],
 * or [example] unchanged when [word] is blank or does not appear literally in it.
 */
internal fun highlightTargetWord(
  example: String,
  word: String,
  highlightColor: Color,
): AnnotatedString {
  val target = word.trim()
  val start = if (target.isEmpty()) -1 else example.indexOf(target, ignoreCase = true)
  if (start < 0) return AnnotatedString(example)
  return buildAnnotatedString {
    append(example)
    addStyle(SpanStyle(color = highlightColor), start, start + target.length)
  }
}
