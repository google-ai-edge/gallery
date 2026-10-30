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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.onTertiaryDark
import com.google.ai.edge.gallery.ui.theme.primaryDark
import com.google.ai.edge.gallery.ui.theme.tertiaryContainerDark
import com.google.ai.edge.gallery.ui.theme.tertiaryDark

/**
 * Colors of a Study activity card. The cards keep these saturated fills in both light and dark
 * mode, and their white text reads on both.
 */
data class StudyCardPalette(
  val container: Color,
  val button: Color,
  val onButton: Color,
  /** The fill of the decorative shapes in the card's top end corner. */
  val decorativeFill: Color,
)

/** Dark green "Flash cards" card. */
private val StudyFlashcardsPalette =
  StudyCardPalette(
    container = tertiaryContainerDark,
    button = tertiaryDark,
    onButton = onTertiaryDark,
    // A lighter shade of the container.
    decorativeFill = Color(0xFF206A36),
  )

/** Blue "Vocab Popper" card. */
private val StudyVocabPopperPalette =
  StudyCardPalette(
    // No theme token has these exact shades (mirrors iOS).
    container = Color(0xFF1157CE),
    button = primaryDark,
    onButton = Color(0xFF001944),
    decorativeFill = Color(0xFF2569DE),
  )

/** Burnt orange "Buddy Runner" card (no theme token has these shades; mirrors iOS). */
private val StudyVocabRunnerPalette =
  StudyCardPalette(
    container = Color(0xFF8F2D00),
    button = Color(0xFFFFB59D),
    onButton = Color(0xFF5A1900),
    decorativeFill = Color(0xFFAB3D0B),
  )

/** White title and description on the Study activity cards. */
private val StudyCardContentColor = Color.White

/**
 * The Flash cards activity card of the Study tab: a dark green card with the title, description,
 * and an "Open flashcards" button that opens the Flash cards start screen ([VocabGameStartScreen]).
 *
 * @param isOpenEnabled whether the "Open flashcards" button can be tapped (true once at least one
 *   word is saved).
 * @param onOpen called when the button is tapped to open the Flash cards start screen.
 */
@Composable
fun FlashcardsStudyCard(
  isOpenEnabled: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  lockedMessage: String? = null,
  onOpenAudioSettings: (() -> Unit)? = null,
  lockedActionLabel: String? =
    if (lockedMessage != null && onOpenAudioSettings != null) {
      stringResource(R.string.xrteacher_tts_unsupported_open_settings)
    } else {
      null
    },
) {
  StudyActivityCard(
    title = stringResource(R.string.xrteacher_flashcards_title),
    description = stringResource(R.string.xrteacher_flashcards_desc),
    buttonLabel = stringResource(R.string.xrteacher_open_flashcards),
    palette = StudyFlashcardsPalette,
    isOpenEnabled = isOpenEnabled,
    onOpen = onOpen,
    modifier = modifier,
    lockedMessage = lockedMessage,
    lockedActionLabel = lockedActionLabel,
    onLockedAction = onOpenAudioSettings,
    artwork = { FlashcardsCardShapes(fill = StudyFlashcardsPalette.decorativeFill) },
  )
}

/**
 * The `Vocab Popper` activity card of the Study tab: a blue card with the title, description, and
 * an "Open Vocab Popper" button that opens the game's start screen ([VocabGameStartScreen]).
 *
 * @param isOpenEnabled whether the button can be tapped (true once enough words are saved).
 * @param lockedMessage if not null, why the game can't be played yet.
 */
@Composable
fun VocabPopperStudyCard(
  isOpenEnabled: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  lockedMessage: String? = null,
) {
  StudyActivityCard(
    title = stringResource(R.string.xrteacher_vocab_popper_title),
    description = stringResource(R.string.xrteacher_vocab_popper_desc),
    buttonLabel = stringResource(R.string.xrteacher_open_vocab_popper),
    palette = StudyVocabPopperPalette,
    isOpenEnabled = isOpenEnabled,
    onOpen = onOpen,
    modifier = modifier,
    lockedMessage = lockedMessage,
    buttonIcon = Icons.Filled.SportsEsports,
    artwork = { VocabPopperCardShapes(fill = StudyVocabPopperPalette.decorativeFill) },
  )
}

/**
 * The `Buddy Runner` activity card of the Study tab: a burnt orange card with the title,
 * description, and an "Open Buddy Runner" button that opens the game's start screen
 * ([VocabGameStartScreen]).
 *
 * @param isOpenEnabled whether the button can be tapped (true once enough words with images are
 *   saved).
 * @param lockedMessage if not null, why the game can't be played yet.
 */
@Composable
fun VocabRunnerStudyCard(
  isOpenEnabled: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  lockedMessage: String? = null,
) {
  StudyActivityCard(
    title = stringResource(R.string.xrteacher_vocab_runner_title),
    description = stringResource(R.string.xrteacher_vocab_runner_desc),
    buttonLabel = stringResource(R.string.xrteacher_open_vocab_runner),
    palette = StudyVocabRunnerPalette,
    isOpenEnabled = isOpenEnabled,
    onOpen = onOpen,
    modifier = modifier,
    lockedMessage = lockedMessage,
    buttonIcon = Icons.AutoMirrored.Filled.DirectionsRun,
    artwork = { VocabRunnerCardShapes(fill = StudyVocabRunnerPalette.decorativeFill) },
  )
}

/**
 * One Study activity card: the title and description, an optional [lockedMessage], and an open
 * button that opens the activity's [VocabGameStartScreen], over decorative [artwork] in the top end
 * corner.
 *
 * @param isOpenEnabled whether the open button can be tapped.
 * @param lockedMessage if not null, why the activity cannot be opened yet; it is shown above the
 *   disabled button.
 * @param lockedActionLabel optional label for an action button shown beneath [lockedMessage] (for
 *   example, "Open Audio settings").
 * @param onLockedAction called when the locked action button is tapped.
 * @param artwork decorative shapes drawn in a [STUDY_CARD_ARTWORK_WIDTH] x
 *   [STUDY_CARD_ARTWORK_HEIGHT] box in the top end corner, behind the text.
 */
@Composable
fun StudyActivityCard(
  title: String,
  description: String,
  buttonLabel: String,
  palette: StudyCardPalette,
  isOpenEnabled: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  lockedMessage: String? = null,
  lockedActionLabel: String? = null,
  onLockedAction: (() -> Unit)? = null,
  buttonIcon: ImageVector = Icons.Filled.School,
  artwork: @Composable () -> Unit = {},
) {
  Box(
    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.container)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.align(Alignment.TopEnd)
          .size(width = STUDY_CARD_ARTWORK_WIDTH, height = STUDY_CARD_ARTWORK_HEIGHT)
          .clearAndSetSemantics {},
    ) {
      artwork()
    }
    StudyActivityCardContent(
      title = title,
      description = description,
      buttonLabel = buttonLabel,
      palette = palette,
      isOpenEnabled = isOpenEnabled,
      onOpen = onOpen,
      lockedMessage = lockedMessage,
      lockedActionLabel = lockedActionLabel,
      onLockedAction = onLockedAction,
      buttonIcon = buttonIcon,
    )
  }
}

/** The width of the decorative artwork box of a Study activity card. */
private val STUDY_CARD_ARTWORK_WIDTH = 156.dp

/** The height of the decorative artwork box of a Study activity card. */
private val STUDY_CARD_ARTWORK_HEIGHT = 140.dp

/** The text and buttons of a [StudyActivityCard]. */
@Composable
private fun StudyActivityCardContent(
  title: String,
  description: String,
  buttonLabel: String,
  palette: StudyCardPalette,
  isOpenEnabled: Boolean,
  onOpen: () -> Unit,
  lockedMessage: String?,
  lockedActionLabel: String?,
  onLockedAction: (() -> Unit)?,
  buttonIcon: ImageVector,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
      color = StudyCardContentColor,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
      color = StudyCardContentColor,
    )
    if (lockedMessage != null) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = lockedMessage,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
        color = StudyCardContentColor,
      )
      if (lockedActionLabel != null && onLockedAction != null) {
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
          onClick = onLockedAction,
          colors = ButtonDefaults.textButtonColors(contentColor = palette.button),
          contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
          Text(
            text = lockedActionLabel,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
      onClick = onOpen,
      enabled = isOpenEnabled && lockedMessage == null,
      shape = CircleShape,
      colors =
        ButtonDefaults.buttonColors(
          containerColor = palette.button,
          contentColor = palette.onButton,
          // The disabled button fades, together with its label, into the card color.
          disabledContainerColor = palette.button.copy(alpha = 0.8f),
          disabledContentColor = palette.container.copy(alpha = 0.4f),
        ),
      contentPadding = PaddingValues(horizontal = 20.dp),
      modifier = Modifier.height(48.dp),
    ) {
      Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = buttonLabel, style = MaterialTheme.typography.labelLarge)
    }
  }
}

/** One decorative shape of a Study activity card, placed relative to the artwork box center. */
@Composable
private fun StudyCardShape(
  shape: Shape,
  color: Color,
  width: Dp,
  height: Dp,
  offsetX: Dp,
  offsetY: Dp,
  rotationDegrees: Float = 0f,
) {
  Box(
    modifier =
      Modifier.offset(x = offsetX, y = offsetY)
        .rotate(rotationDegrees)
        .size(width = width, height = height)
        .background(color, shape)
  )
}

/** A tilted rounded square and a small circle, behind the Flash cards card title. */
@Composable
private fun FlashcardsCardShapes(fill: Color) {
  StudyCardShape(
    shape = RoundedCornerShape(26.dp),
    color = fill.copy(alpha = 0.65f),
    width = 112.dp,
    height = 112.dp,
    offsetX = 34.dp,
    offsetY = (-22).dp,
    rotationDegrees = 16f,
  )
  StudyCardShape(
    shape = CircleShape,
    color = fill.copy(alpha = 0.45f),
    width = 56.dp,
    height = 56.dp,
    offsetX = (-18).dp,
    offsetY = 26.dp,
  )
}

/** A large circle and a tilted pill, behind the Vocab Popper card title. */
@Composable
private fun VocabPopperCardShapes(fill: Color) {
  StudyCardShape(
    shape = CircleShape,
    color = fill.copy(alpha = 0.65f),
    width = 116.dp,
    height = 116.dp,
    offsetX = 36.dp,
    offsetY = (-26).dp,
  )
  StudyCardShape(
    shape = CircleShape,
    color = fill.copy(alpha = 0.48f),
    width = 68.dp,
    height = 34.dp,
    offsetX = (-14).dp,
    offsetY = 24.dp,
    rotationDegrees = -28f,
  )
}

/** A tilted wide pill and a tilted rounded square, behind the Buddy Runner card title. */
@Composable
private fun VocabRunnerCardShapes(fill: Color) {
  StudyCardShape(
    shape = CircleShape,
    color = fill.copy(alpha = 0.65f),
    width = 124.dp,
    height = 84.dp,
    offsetX = 34.dp,
    offsetY = (-20).dp,
    rotationDegrees = -18f,
  )
  StudyCardShape(
    shape = RoundedCornerShape(18.dp),
    color = fill.copy(alpha = 0.48f),
    width = 58.dp,
    height = 58.dp,
    offsetX = (-16).dp,
    offsetY = 26.dp,
    rotationDegrees = 22f,
  )
}
