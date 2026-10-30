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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.onTertiaryDark
import com.google.ai.edge.gallery.ui.theme.tertiaryContainerDark
import com.google.ai.edge.gallery.ui.theme.tertiaryDark

/**
 * Colors of a Study activity card. The cards keep these saturated fills in both light and dark
 * mode, and their white text reads on both.
 */
data class StudyCardPalette(val container: Color, val button: Color, val onButton: Color)

/** Dark green "Flash cards" card. */
val StudyFlashcardsPalette =
  StudyCardPalette(
    container = tertiaryContainerDark,
    button = tertiaryDark,
    onButton = onTertiaryDark,
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
  )
}

/**
 * One Study activity card: the title and description, an optional [lockedMessage], and an open
 * button that opens the activity's [VocabGameStartScreen].
 *
 * @param isOpenEnabled whether the open button can be tapped.
 * @param lockedMessage if not null, why the activity cannot be opened yet; it is shown above the
 *   disabled button.
 * @param lockedActionLabel optional label for an action button shown beneath [lockedMessage] (for
 *   example, "Open Audio settings").
 * @param onLockedAction called when the locked action button is tapped.
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
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .background(palette.container)
        .padding(horizontal = 24.dp, vertical = 20.dp)
  ) {
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
