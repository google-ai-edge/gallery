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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.sessionStickerForKey
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.stickerKey
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.UnsaveWordConfirmationDialog
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.VOCAB_THUMBNAIL_SIZE
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.VocabCard
import com.google.ai.edge.gallery.ui.common.asr.AsrRecordingPill

private enum class FollowUpSheetMode {
  IDLE,
  RECORDING_ASR,
  THINKING,
}

/**
 * Height shared by "Save to Vocab", "Ask a follow up", and "Ask about something else" buttons so
 * all bottom sheet action pills share the same height.
 */
private val ACTION_BUTTON_HEIGHT = 40.dp
private val FOLLOW_UP_BUTTON_HEIGHT = ACTION_BUTTON_HEIGHT
private val SAVE_BUTTON_HEIGHT = ACTION_BUTTON_HEIGHT

/**
 * Active lesson bottom sheet for Mockups (c), (d), (e), and (f), combining:
 * - `surfaceContainerHigh` rounded sheet container (`RoundedCornerShape(topStart = 28.dp, topEnd =
 *   28.dp)`) with a top-right close button that calls [onScanNewObject], plus a centered title:
 *   `"Vocab found"` when there is a vocabulary card, or a smaller `"Want to learn more about
 *   this?"` otherwise,
 * - [VocabLessonCard] (or a swipeable [HorizontalPager] deck when [vocabCards] contains multiple
 *   vocabulary cards taught during the lesson; omits phonetic text strings), showing each card's
 *   sticker cutout ([editedStickerBitmaps], else [stickerBitmap]) or else the [capturedBitmap]
 *   photo, with a pencil button that calls [onEditCutout] when it is set (greyed out while the
 *   sticker segmentation model isn't ready, see [isStickerModelReady]), and
 * - [FollowUpLessonControls] sitting directly on the sheet surface below the Vocab Card.
 *
 * The sheet extends to the bottom edge of the screen (the host hides its navigation bar while the
 * sheet is shown), so its content is padded above the system navigation bar.
 */
@Composable
fun ActiveLessonBottomSheet(
  vocabCardData: VocabCardData?,
  capturedBitmap: Bitmap?,
  isCurrentCardSaved: Boolean,
  isRecordingAsr: Boolean,
  isFollowUpThinking: Boolean,
  liveTranscript: String,
  confirmedTranscript: String,
  lastSpokenQuestion: String,
  audioLevel: Float,
  enabled: Boolean,
  onPlayTargetPhrase: (String) -> Unit,
  onSaveToVocab: () -> Unit,
  onUnsaveCardFromVocab: (VocabCardData) -> Unit,
  onScanNewObject: () -> Unit,
  onStartFollowUpRecording: () -> Unit,
  onCancelFollowUpRecording: () -> Unit,
  onSendFollowUpRecording: () -> Unit,
  modifier: Modifier = Modifier,
  vocabCards: List<VocabCardData> = listOfNotNull(vocabCardData),
  currentVocabCardIndex: Int = 0,
  onSelectVocabCard: (Int) -> Unit = {},
  isCardSaved: (VocabCardData) -> Boolean = { isCurrentCardSaved },
  onSaveCardToVocab: (VocabCardData) -> Unit = { onSaveToVocab() },
  isGeneratingCard: Boolean = false,
  followUpUsesText: Boolean = false,
  onFollowUpUsesTextChange: (Boolean) -> Unit = {},
  onSendFollowUpText: (String) -> Unit = {},
  onStopThinking: () -> Unit = {},
  isMicPermissionDenied: Boolean = false,
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
  stickerBitmap: Bitmap? = null,
  editedStickerBitmaps: Map<String, Bitmap?> = emptyMap(),
  onEditCutout: ((VocabCardData) -> Unit)? = null,
  isStickerModelReady: Boolean = true,
) {
  val effectiveCards =
    remember(vocabCards, vocabCardData) {
      if (vocabCards.isNotEmpty()) vocabCards else listOfNotNull(vocabCardData)
    }
  // Matches the card branches below: the pager (for several cards or a card with trailing shimmer),
  // the single current card, or the shimmering skeleton placeholder while Pass 1 / Pass 2 is
  // generating the card.
  val showsVocabCard = effectiveCards.isNotEmpty() || isGeneratingCard

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      // Mockup (e): Soft periwinkle-blue gradient wash at the bottom of the sheet while
      // follow-up is thinking
      if (isFollowUpThinking) {
        Box(
          modifier =
            Modifier.matchParentSize()
              .background(
                brush =
                  Brush.verticalGradient(
                    colors =
                      listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        MaterialTheme.colorScheme.primaryContainer,
                      )
                  )
              )
        )
      }

      Column(
        modifier =
          Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Header: top-right close button that ends the lesson and returns to the idle camera mic
        // view, with a centered title: "Vocab found" when a vocabulary card was found, or a smaller
        // "Want to learn more about this?" prompt otherwise. The row keeps a fixed height so the
        // collapsed sheet's peek height stays the same in both cases.
        Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
          Text(
            text =
              stringResource(
                if (showsVocabCard) {
                  R.string.xrteacher_vocab_found_title
                } else {
                  R.string.xrteacher_learn_more_title
                }
              ),
            style =
              if (showsVocabCard) {
                MaterialTheme.typography.titleMedium.copy(
                  fontSize = 18.sp,
                  fontWeight = FontWeight.Normal,
                )
              } else {
                MaterialTheme.typography.bodyLarge
              },
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
          )
          IconButton(onClick = onScanNewObject, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.xrteacher_close_vocab_sheet_desc),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        when {
          effectiveCards.isNotEmpty() && (effectiveCards.size > 1 || isGeneratingCard) -> {
            val totalPageCount = effectiveCards.size + if (isGeneratingCard) 1 else 0
            val targetPage =
              if (isGeneratingCard) {
                totalPageCount - 1
              } else {
                currentVocabCardIndex.coerceIn(0, effectiveCards.lastIndex)
              }
            val pagerState = rememberPagerState(initialPage = targetPage) { totalPageCount }
            LaunchedEffect(isGeneratingCard) {
              if (isGeneratingCard) {
                val shimmerPage = totalPageCount - 1
                if (pagerState.currentPage != shimmerPage) {
                  pagerState.animateScrollToPage(shimmerPage)
                }
              }
            }
            LaunchedEffect(currentVocabCardIndex, effectiveCards.size) {
              if (!isGeneratingCard) {
                val safeIndex = currentVocabCardIndex.coerceIn(0, effectiveCards.lastIndex)
                if (pagerState.currentPage != safeIndex) {
                  pagerState.animateScrollToPage(safeIndex)
                }
              }
            }
            LaunchedEffect(pagerState.settledPage, effectiveCards.size) {
              if (
                pagerState.settledPage in effectiveCards.indices &&
                  pagerState.settledPage != currentVocabCardIndex
              ) {
                onSelectVocabCard(pagerState.settledPage)
              }
            }
            val displayedPage = pagerState.currentPage.coerceIn(0, totalPageCount - 1)
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              HorizontalPager(
                state = pagerState,
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth(),
              ) { page ->
                if (page < effectiveCards.size) {
                  val card = effectiveCards[page]
                  VocabLessonCard(
                    vocabCardData = card,
                    capturedBitmap = capturedBitmap,
                    stickerBitmap =
                      sessionStickerForKey(
                        stickerKey(card.word),
                        stickerBitmap,
                        editedStickerBitmaps,
                      ),
                    isSaved = isCardSaved(card),
                    onPlayTargetPhrase = onPlayTargetPhrase,
                    phrasePlaybackState = phrasePlaybackState,
                    onSaveToVocab = { onSaveCardToVocab(card) },
                    onUnsaveFromVocab = { onUnsaveCardFromVocab(card) },
                    onEditCutout = onEditCutout?.let { onEdit -> { onEdit(card) } },
                    isStickerModelReady = isStickerModelReady,
                  )
                } else {
                  VocabLessonCardShimmerPlaceholder(capturedBitmap = capturedBitmap)
                }
              }
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                for (i in 0 until totalPageCount) {
                  val isSelected = i == displayedPage
                  Box(
                    modifier =
                      Modifier.width(if (isSelected) 16.dp else 6.dp)
                        .height(6.dp)
                        .background(
                          color =
                            if (isSelected) {
                              MaterialTheme.colorScheme.primary
                            } else {
                              MaterialTheme.colorScheme.outlineVariant
                            },
                          shape = CircleShape,
                        )
                  )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text =
                    stringResource(
                      R.string.xrteacher_study_progress,
                      displayedPage + 1,
                      totalPageCount,
                    ),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium,
                )
              }
            }
          }
          effectiveCards.isNotEmpty() -> {
            val singleCard = effectiveCards.first()
            VocabLessonCard(
              vocabCardData = singleCard,
              capturedBitmap = capturedBitmap,
              stickerBitmap =
                sessionStickerForKey(
                  stickerKey(singleCard.word),
                  stickerBitmap,
                  editedStickerBitmaps,
                ),
              isSaved = isCardSaved(singleCard),
              onPlayTargetPhrase = onPlayTargetPhrase,
              phrasePlaybackState = phrasePlaybackState,
              onSaveToVocab = { onSaveCardToVocab(singleCard) },
              onUnsaveFromVocab = { onUnsaveCardFromVocab(singleCard) },
              onEditCutout = onEditCutout?.let { onEdit -> { onEdit(singleCard) } },
              isStickerModelReady = isStickerModelReady,
            )
          }
          isGeneratingCard -> VocabLessonCardShimmerPlaceholder(capturedBitmap = capturedBitmap)
        }

        FollowUpLessonControls(
          isRecordingAsr = isRecordingAsr,
          isFollowUpThinking = isFollowUpThinking,
          liveTranscript = liveTranscript,
          confirmedTranscript = confirmedTranscript,
          lastSpokenQuestion = lastSpokenQuestion,
          audioLevel = audioLevel,
          enabled = enabled,
          onStartRecording = onStartFollowUpRecording,
          onCancelRecording = onCancelFollowUpRecording,
          onSendRecording = onSendFollowUpRecording,
          onScanNewObject = onScanNewObject,
          usesText = followUpUsesText,
          onUsesTextChange = onFollowUpUsesTextChange,
          onSendText = onSendFollowUpText,
          onStopThinking = onStopThinking,
          isMicPermissionDenied = isMicPermissionDenied,
        )
      }
    }
  }
}

@Composable
private fun VocabLessonCardShimmerPlaceholder(capturedBitmap: Bitmap?) {
  val thumbnailImageBitmap =
    remember(capturedBitmap) { capturedBitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
  val transition = rememberInfiniteTransition(label = "vocab_card_shimmer")
  val translateAnim by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1000f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1200, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "shimmer_sweep",
    )
  val shimmerBrush =
    Brush.linearGradient(
      colors =
        listOf(
          MaterialTheme.colorScheme.surfaceContainerHigh,
          MaterialTheme.colorScheme.surfaceContainerLowest,
          MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      start = Offset(translateAnim - 400f, translateAnim - 400f),
      end = Offset(translateAnim, translateAnim),
    )

  // Mirrors the VocabLessonCard layout so nothing jumps when the card arrives.
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (thumbnailImageBitmap != null) {
          Image(
            bitmap = thumbnailImageBitmap,
            contentDescription = stringResource(R.string.xrteacher_identified_object),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(VOCAB_THUMBNAIL_SIZE).clip(RoundedCornerShape(16.dp)),
          )
        } else {
          Box(
            modifier =
              Modifier.size(VOCAB_THUMBNAIL_SIZE)
                .clip(RoundedCornerShape(16.dp))
                .background(shimmerBrush)
          )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(
            modifier =
              Modifier.height(24.dp)
                .width(136.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(shimmerBrush)
          )
          Box(
            modifier =
              Modifier.height(16.dp)
                .width(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmerBrush)
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(shimmerBrush))
      }

      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(shimmerBrush)
      )

      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(SAVE_BUTTON_HEIGHT)
            .clip(CircleShape)
            .background(shimmerBrush)
      )
    }
  }
}

/**
 * Vocab Card inside [ActiveLessonBottomSheet]: the shared [VocabCard] with the object's
 * [stickerBitmap] cutout (or else the [capturedBitmap] photo) as its thumbnail, a pencil button
 * that calls [onEditCutout] when it is set and there is a photo to edit the cutout on (greyed out
 * until [isStickerModelReady]), and a full-width tonal [SaveToVocabButton] at the bottom.
 */
@Composable
private fun VocabLessonCard(
  vocabCardData: VocabCardData,
  capturedBitmap: Bitmap?,
  stickerBitmap: Bitmap?,
  isSaved: Boolean,
  onPlayTargetPhrase: (String) -> Unit,
  phrasePlaybackState: PhrasePlaybackState,
  onSaveToVocab: () -> Unit,
  onUnsaveFromVocab: () -> Unit,
  onEditCutout: (() -> Unit)?,
  isStickerModelReady: Boolean,
) {
  val photoImageBitmap =
    remember(capturedBitmap) { capturedBitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
  val stickerImageBitmap =
    remember(stickerBitmap) { stickerBitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }

  VocabCard(
    vocabCardData = vocabCardData,
    thumbnail = stickerImageBitmap ?: photoImageBitmap,
    onPlayTargetPhrase = onPlayTargetPhrase,
    phrasePlaybackState = phrasePlaybackState,
    onEditThumbnail = onEditCutout?.takeIf { photoImageBitmap != null },
    isEditThumbnailDimmed = !isStickerModelReady,
    footer = {
      SaveToVocabButton(
        word = vocabCardData.word,
        isSaved = isSaved,
        onSaveToVocab = onSaveToVocab,
        onUnsaveFromVocab = onUnsaveFromVocab,
      )
    },
  )
}

/**
 * Full-width tonal "Save to Vocab" pill (`secondaryContainer` with a filled bookmark icon). Once
 * saved it switches to `tertiaryContainer` with a check icon and "Saved" so the two states stay
 * distinguishable.
 *
 * The pill is a switch: tapping it while unsaved calls [onSaveToVocab], and tapping it while saved
 * opens [UnsaveWordConfirmationDialog] for [word], calling [onUnsaveFromVocab] only on confirm.
 */
@Composable
private fun SaveToVocabButton(
  word: String,
  isSaved: Boolean,
  onSaveToVocab: () -> Unit,
  onUnsaveFromVocab: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Keyed by word so a pending confirmation never carries over to a different card.
  var showUnsaveConfirmation by rememberSaveable(word) { mutableStateOf(false) }
  val containerColor =
    if (isSaved) {
      MaterialTheme.colorScheme.tertiaryContainer
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    }
  val contentColor =
    if (isSaved) {
      MaterialTheme.colorScheme.onTertiaryContainer
    } else {
      MaterialTheme.colorScheme.onSecondaryContainer
    }
  Surface(
    checked = isSaved,
    onCheckedChange = { checked ->
      if (checked) {
        onSaveToVocab()
      } else {
        showUnsaveConfirmation = true
      }
    },
    shape = CircleShape,
    color = containerColor,
    contentColor = contentColor,
    modifier = modifier.fillMaxWidth().height(SAVE_BUTTON_HEIGHT).semantics { role = Role.Switch },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Bookmark,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text =
          stringResource(
            if (isSaved) R.string.xrteacher_saved_to_vocab else R.string.xrteacher_save_to_vocab
          ),
        style =
          MaterialTheme.typography.labelLarge.copy(
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
          ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }

  if (showUnsaveConfirmation) {
    UnsaveWordConfirmationDialog(
      word = word,
      onConfirm = {
        showUnsaveConfirmation = false
        onUnsaveFromVocab()
      },
      onDismiss = { showUnsaveConfirmation = false },
    )
  }
}

/**
 * Inline follow-up action controls sitting directly on the [ActiveLessonBottomSheet] surface below
 * [VocabLessonCard]:
 * - **Idle state (Mockups c, f)**: Full-width solid `primary` `"Ask a follow up"` pill button
 *   (`onPrimary` `Mic` icon + `16.sp` regular-weight `onPrimary` text) stacked above a full-width
 *   `surfaceContainerHighest` `"Ask about something else"` pill button (`16.sp` regular-weight
 *   `onSurface` text, no icon) that ends the lesson, both [FOLLOW_UP_BUTTON_HEIGHT] tall. When
 *   [usesText], a [FollowUpTextPromptBox] replaces the mic button. Next to either sits a small
 *   tonal switch button: a keyboard to type instead, or a mic to speak instead.
 * - **Follow-up ASR recording state (Mockup d)**: Live transcript (`onSurface`) + elevated
 *   [AsrRecordingPill].
 * - **Follow-up processing state (Mockup e)**: User's follow-up question (`onSurface`); the top
 *   `"Thinking..."` header signals that the response is being generated.
 */
@Composable
private fun FollowUpLessonControls(
  isRecordingAsr: Boolean,
  isFollowUpThinking: Boolean,
  liveTranscript: String,
  confirmedTranscript: String,
  lastSpokenQuestion: String,
  audioLevel: Float,
  enabled: Boolean,
  onStartRecording: () -> Unit,
  onCancelRecording: () -> Unit,
  onSendRecording: () -> Unit,
  onScanNewObject: () -> Unit,
  usesText: Boolean,
  onUsesTextChange: (Boolean) -> Unit,
  onSendText: (String) -> Unit,
  onStopThinking: () -> Unit = {},
  isMicPermissionDenied: Boolean = false,
) {
  // Opens the keyboard only when the user just tapped the keyboard button, not after a typed
  // question.
  var focusTextOnShow by remember { mutableStateOf(false) }
  val mode =
    when {
      isRecordingAsr -> FollowUpSheetMode.RECORDING_ASR
      isFollowUpThinking -> FollowUpSheetMode.THINKING
      else -> FollowUpSheetMode.IDLE
    }

  AnimatedContent(
    targetState = mode,
    transitionSpec = {
      fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(150))
    },
    label = "FollowUpSheetModeTransition",
    modifier = Modifier.fillMaxWidth(),
  ) { currentMode ->
    when (currentMode) {
      FollowUpSheetMode.IDLE -> {
        // The sheet's navigationBarsPadding() consumes the navigation bar inset, so imePadding()
        // only lifts the input by the part of the keyboard above the navigation bar.
        Column(
          modifier = Modifier.fillMaxWidth().imePadding(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // The follow-up input (text box or mic button), and a small switch to the other one.
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (usesText) {
              FollowUpTextPromptBox(
                enabled = enabled,
                onSend = { question ->
                  focusTextOnShow = false
                  onSendText(question)
                },
                modifier = Modifier.weight(1f),
                focusOnShow = focusTextOnShow,
                onFocusConsumed = { focusTextOnShow = false },
              )
            } else {
              val micVisuallyEnabled = enabled && !isMicPermissionDenied
              val followUpContainerColor =
                if (micVisuallyEnabled) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                }
              val followUpContentColor =
                if (micVisuallyEnabled) {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
              Surface(
                onClick = onStartRecording,
                enabled = enabled,
                shape = CircleShape,
                color = followUpContainerColor,
                modifier = Modifier.weight(1f).height(FOLLOW_UP_BUTTON_HEIGHT),
              ) {
                Row(
                  modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                ) {
                  Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = followUpContentColor,
                    modifier = Modifier.size(22.dp),
                  )
                  Spacer(modifier = Modifier.width(10.dp))
                  Text(
                    text = stringResource(R.string.xrteacher_ask_follow_up),
                    style =
                      MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                      ),
                    color = followUpContentColor,
                  )
                }
              }
            }
            FilledTonalIconButton(
              onClick = {
                focusTextOnShow = !usesText
                onUsesTextChange(!usesText)
                if (usesText && isMicPermissionDenied) {
                  onStartRecording()
                }
              },
              enabled = enabled,
              modifier = Modifier.size(FOLLOW_UP_BUTTON_HEIGHT),
            ) {
              Icon(
                imageVector = if (usesText) Icons.Default.Mic else Icons.Default.Keyboard,
                contentDescription =
                  stringResource(
                    if (usesText) {
                      R.string.xrteacher_speak_follow_up_desc
                    } else {
                      R.string.xrteacher_type_follow_up_desc
                    }
                  ),
                tint =
                  if (usesText && isMicPermissionDenied) {
                    LocalContentColor.current.copy(alpha = 0.38f)
                  } else {
                    LocalContentColor.current
                  },
                modifier = Modifier.size(20.dp),
              )
            }
          }

          Surface(
            onClick = onScanNewObject,
            enabled = enabled,
            shape = CircleShape,
            color =
              if (enabled) {
                MaterialTheme.colorScheme.surfaceContainerHighest
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
              },
            modifier = Modifier.fillMaxWidth().height(FOLLOW_UP_BUTTON_HEIGHT),
          ) {
            Box(
              modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.xrteacher_ask_about_something_else),
                style =
                  MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                  ),
                color =
                  if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                  } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      FollowUpSheetMode.RECORDING_ASR -> {
        val displayTranscript = liveTranscript.ifBlank { confirmedTranscript }.trim()
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text =
              displayTranscript.ifBlank { stringResource(R.string.xrteacher_asr_listening_hint) },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color =
              if (displayTranscript.isNotBlank()) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          )

          AsrRecordingPill(
            audioLevel = audioLevel,
            cancelContentDescription = stringResource(R.string.xrteacher_asr_cancel_desc),
            sendContentDescription = stringResource(R.string.xrteacher_asr_send_desc),
            onCancel = onCancelRecording,
            onSend = onSendRecording,
            colors = teacherAsrRecordingPillColors(),
          )
        }
      }

      FollowUpSheetMode.THINKING -> {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (lastSpokenQuestion.isNotBlank()) {
            Text(
              text = lastSpokenQuestion,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
          }
          Surface(
            onClick = onStopThinking,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp),
          ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.cd_stop_icon),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }
    }
  }
}
