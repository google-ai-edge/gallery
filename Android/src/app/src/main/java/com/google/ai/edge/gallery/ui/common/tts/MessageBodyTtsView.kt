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

package com.google.ai.edge.gallery.ui.common.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.markdownBodyTextStyle
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * Renders an agent response while it is read aloud, highlighting the sentence being spoken.
 *
 * Prose paragraphs flow as normal text with the active sentence highlighted inline; tapping a
 * sentence plays from it. Fenced code blocks and tables are rendered whole and are not spoken.
 */
@Composable
fun MessageBodyTtsView(
  content: String,
  controller: TtsPlaybackController,
  modifier: Modifier = Modifier,
) {
  val sentences by controller.sentences.collectAsStateWithLifecycle()
  val currentSentenceIndex by controller.currentSentenceIndex.collectAsStateWithLifecycle()
  val currentWordIndex by controller.currentWordIndex.collectAsStateWithLifecycle()
  val inFlightTail by controller.inFlightTailText.collectAsStateWithLifecycle()
  val isAutoScrollEnabled by controller.isAutoScrollEnabled.collectAsStateWithLifecycle()
  val scrollTrigger by controller.scrollTrigger.collectAsStateWithLifecycle()
  val playbackState by controller.playbackState.collectAsStateWithLifecycle()

  // While the response streams in, the unfinished last sentence is shown separately below.
  val isTailInFlight = inFlightTail != null
  val tailRange =
    remember(content, isTailInFlight) {
      if (isTailInFlight) {
        segmentSpeakableText(content, includeIncompleteTail = false).incompleteTail
      } else {
        null
      }
    }
  val blocks =
    remember(content, sentences, tailRange) { splitIntoBlocks(content, sentences, tailRange) }
  val followActiveSentence =
    isAutoScrollEnabled &&
      (playbackState == TtsPlaybackState.PLAYING || playbackState == TtsPlaybackState.PAUSED)

  SelectionContainer {
    Column(
      modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      for (block in blocks) {
        key(block.start) {
          when (block) {
            is TtsMessageBlock.Unspoken ->
              MarkdownText(
                text = block.text,
                modifier = Modifier.fillMaxWidth(),
                textColor = MaterialTheme.colorScheme.onSurface,
              )
            is TtsMessageBlock.Paragraph ->
              TtsParagraph(
                content = content,
                paragraph = block,
                activeSentenceId = currentSentenceIndex,
                activeWordIndex = currentWordIndex,
                followActiveSentence = followActiveSentence,
                scrollTrigger = scrollTrigger,
                onRequestCenterInRootY = controller::requestCenterInRootY,
                onSentenceClick = controller::seekToSentence,
              )
          }
        }
      }

      if (tailRange != null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = content.substring(tailRange.start, tailRange.end),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f, fill = false),
          )
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
      }
    }
  }
}

/**
 * A prose paragraph as a single flowing [Text], with each sentence tappable and the active one
 * highlighted and, when [followActiveSentence] is set, scrolled into view.
 */
@Composable
private fun TtsParagraph(
  content: String,
  paragraph: TtsMessageBlock.Paragraph,
  activeSentenceId: Int,
  activeWordIndex: Int?,
  followActiveSentence: Boolean,
  scrollTrigger: Int,
  onRequestCenterInRootY: (Float) -> Unit,
  onSentenceClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentOnSentenceClick by rememberUpdatedState(onSentenceClick)
  val currentOnRequestCenterInRootY by rememberUpdatedState(onRequestCenterInRootY)
  val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
  val wordHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
  val inlineCodeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
  val linkColor = MaterialTheme.customColors.linkColor
  val rendered =
    remember(
      content,
      paragraph,
      activeSentenceId,
      activeWordIndex,
      highlightColor,
      wordHighlightColor,
      inlineCodeBackground,
      linkColor,
    ) {
      renderParagraph(
        content = content,
        paragraph = paragraph,
        activeSentenceId = activeSentenceId,
        highlightColor = highlightColor,
        inlineCodeBackground = inlineCodeBackground,
        linkColor = linkColor,
        onSentenceClick = { currentOnSentenceClick(it) },
        activeWordIndex = activeWordIndex,
        wordHighlightColor = wordHighlightColor,
      )
    }

  var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
  var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
  val activeRange = rendered.sentenceRanges[activeSentenceId]
  val currentActiveRange by rememberUpdatedState(activeRange)
  LaunchedEffect(activeSentenceId, followActiveSentence, scrollTrigger, activeRange != null) {
    if (!followActiveSentence) return@LaunchedEffect
    val range = currentActiveRange ?: return@LaunchedEffect
    val (layout, coords) =
      snapshotFlow { layoutResult to layoutCoordinates }
        .mapNotNull { (textLayout, nodeCoords) ->
          if (textLayout != null && nodeCoords != null && nodeCoords.isAttached) {
            textLayout to nodeCoords
          } else {
            null
          }
        }
        .first()
    val textLength = layout.layoutInput.text.length
    val start = range.first.coerceIn(0, textLength)
    val end = (range.last + 1).coerceIn(start, textLength)
    if (start == end) return@LaunchedEffect
    val bounds = layout.getPathForRange(start, end).getBounds()
    val centerYInRoot = coords.positionInRoot().y + bounds.center.y
    currentOnRequestCenterInRootY(centerYInRoot)
  }

  Text(
    text = rendered.text,
    style = markdownBodyTextStyle(textColor = MaterialTheme.colorScheme.onSurface),
    onTextLayout = { layoutResult = it },
    modifier = modifier.fillMaxWidth().onGloballyPositioned { layoutCoordinates = it },
  )
}
