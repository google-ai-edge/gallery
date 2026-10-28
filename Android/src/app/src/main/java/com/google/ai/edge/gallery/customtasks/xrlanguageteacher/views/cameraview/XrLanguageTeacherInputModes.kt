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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.inversePrimaryLight
import com.google.ai.edge.gallery.ui.theme.onSecondaryContainerDark
import com.google.ai.edge.gallery.ui.theme.onSurfaceVariantDark
import com.google.ai.edge.gallery.ui.theme.primaryLight
import com.google.ai.edge.gallery.ui.theme.secondaryContainerDark
import com.google.ai.edge.gallery.ui.theme.surfaceContainerDark

/**
 * Fixed high-contrast foreground color used by camera HUD overlays ([CameraTextPromptBox]), which
 * sit directly on live camera pixels rather than a themed surface in both light and dark themes.
 */
private val CameraHudForegroundColor = Color.White

/** Shared height of the controls in the camera HUD's bottom input row. */
internal val CAMERA_INPUT_ROW_HEIGHT = 56.dp

/**
 * Circular GM3 [FilledTonalIconButton] ([CAMERA_INPUT_ROW_HEIGHT]) for the camera HUD's bottom row:
 * the 1-tap photo button for a vocabulary card, and the switch between typing and speaking.
 *
 * It sits on camera pixels rather than a themed surface, so it uses the dark tonal colors
 * ([secondaryContainerDark] and [onSecondaryContainerDark]) in both light and dark themes.
 *
 * @param appearDisabled When true while [enabled] is true, renders the button in its faded disabled
 *   colors while keeping it clickable, so tapping it can explain why it can't be used.
 */
@Composable
internal fun CameraHudIconButton(
  icon: ImageVector,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  appearDisabled: Boolean = false,
) {
  val fadedContainerColor = secondaryContainerDark.copy(alpha = 0.42f)
  val fadedContentColor = onSecondaryContainerDark.copy(alpha = 0.45f)
  FilledTonalIconButton(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    colors =
      IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = if (appearDisabled) fadedContainerColor else secondaryContainerDark,
        contentColor = if (appearDisabled) fadedContentColor else onSecondaryContainerDark,
        disabledContainerColor = fadedContainerColor,
        disabledContentColor = fadedContentColor,
      ),
    modifier = modifier.size(CAMERA_INPUT_ROW_HEIGHT),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      modifier = Modifier.size(24.dp),
    )
  }
}

/**
 * Text input on the camera, between the photo button and the mic button of the camera HUD's bottom
 * row: the dark translucent prompt box from the design prototype, with a text field ("Ask a
 * question…") and a round send button that turns blue once there is text. Sending clears the box
 * and hides the keyboard.
 *
 * It sits on camera pixels rather than a themed surface, so it uses colors that are the same in
 * light and dark themes.
 *
 * @param focusOnShow whether to focus the box (and open the keyboard) when it appears, as when the
 *   user just chose to type.
 */
@Composable
internal fun CameraTextPromptBox(
  enabled: Boolean,
  onSend: (String) -> Unit,
  modifier: Modifier = Modifier,
  appearDisabled: Boolean = false,
  onDisabledClick: () -> Unit = {},
  focusOnShow: Boolean = false,
  onFocusConsumed: () -> Unit = {},
) {
  TeacherPromptBox(
    placeholder = stringResource(R.string.xrteacher_text_prompt_hint),
    enabled = enabled,
    onSend = onSend,
    containerColor =
      if (appearDisabled) surfaceContainerDark.copy(alpha = 0.55f)
      else surfaceContainerDark.copy(alpha = 0.85f),
    borderColor =
      if (appearDisabled) CameraHudForegroundColor.copy(alpha = 0.1f)
      else CameraHudForegroundColor.copy(alpha = 0.2f),
    textColor =
      if (appearDisabled) CameraHudForegroundColor.copy(alpha = 0.5f) else CameraHudForegroundColor,
    placeholderColor =
      if (appearDisabled) onSurfaceVariantDark.copy(alpha = 0.5f) else onSurfaceVariantDark,
    cursorColor = inversePrimaryLight,
    sendContainerColor = primaryLight,
    sendIconColor = CameraHudForegroundColor,
    idleSendContainerColor = CameraHudForegroundColor.copy(alpha = 0.12f),
    idleSendIconColor =
      if (appearDisabled) CameraHudForegroundColor.copy(alpha = 0.24f)
      else CameraHudForegroundColor.copy(alpha = 0.4f),
    height = CAMERA_PROMPT_HEIGHT,
    // Same shape as the box, so the shadow follows it as it grows to more lines.
    modifier =
      modifier.widthIn(max = 387.dp).shadow(16.dp, RoundedCornerShape(CAMERA_PROMPT_HEIGHT / 2)),
    appearDisabled = appearDisabled,
    onDisabledClick = onDisabledClick,
    focusOnShow = focusOnShow,
    onFocusConsumed = onFocusConsumed,
  )
}

/**
 * Text follow-up input in the active lesson sheet, shown instead of the "Ask a follow up" mic
 * button when the user types: the same prompt box as [CameraTextPromptBox] in the sheet's light
 * colors, as tall as the sheet's other buttons.
 *
 * @param focusOnShow whether to focus the box (and open the keyboard) when it appears, as when the
 *   user just chose to type.
 */
@Composable
internal fun FollowUpTextPromptBox(
  enabled: Boolean,
  onSend: (String) -> Unit,
  modifier: Modifier = Modifier,
  focusOnShow: Boolean = false,
  onFocusConsumed: () -> Unit = {},
) {
  TeacherPromptBox(
    placeholder = stringResource(R.string.xrteacher_follow_up_text_hint),
    enabled = enabled,
    onSend = onSend,
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    borderColor = Color.Transparent,
    textColor = MaterialTheme.colorScheme.onSurface,
    placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    sendContainerColor = MaterialTheme.colorScheme.primary,
    sendIconColor = MaterialTheme.colorScheme.onPrimary,
    idleSendContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    idleSendIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    height = 40.dp,
    modifier = modifier,
    focusOnShow = focusOnShow,
    onFocusConsumed = onFocusConsumed,
  )
}

/** The most lines a prompt box grows to before its text scrolls. */
private const val MAX_PROMPT_LINES = 3

/** Height of [CameraTextPromptBox] with one line of text, as tall as the row's other buttons. */
private val CAMERA_PROMPT_HEIGHT = CAMERA_INPUT_ROW_HEIGHT

/**
 * A rounded prompt box with a text field and a send button; see its callers. The box is [height]
 * tall for one line and grows to fit up to [MAX_PROMPT_LINES] lines of text, then scrolls. The send
 * button is `40.dp`, or smaller when needed to leave a `4.dp` margin in a short box, and stays at
 * the bottom of the box as it grows.
 */
@Composable
private fun TeacherPromptBox(
  placeholder: String,
  enabled: Boolean,
  onSend: (String) -> Unit,
  containerColor: Color,
  borderColor: Color,
  textColor: Color,
  placeholderColor: Color,
  cursorColor: Color,
  sendContainerColor: Color,
  sendIconColor: Color,
  idleSendContainerColor: Color,
  idleSendIconColor: Color,
  height: Dp,
  modifier: Modifier = Modifier,
  focusOnShow: Boolean = false,
  onFocusConsumed: () -> Unit = {},
  appearDisabled: Boolean = false,
  onDisabledClick: () -> Unit = {},
) {
  var text by rememberSaveable { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  val inputEnabled = enabled && !appearDisabled
  LaunchedEffect(focusOnShow, inputEnabled) {
    if (focusOnShow && inputEnabled) {
      runCatching { focusRequester.requestFocus() }
      onFocusConsumed()
    }
  }
  val canSend = inputEnabled && text.isNotBlank()
  val sendButtonSize = minOf(40.dp, height - 8.dp)
  // Centers the send button, and a single line of text, in a box of the minimum height.
  val sendButtonMargin = (height - sendButtonSize) / 2
  val textVerticalPadding = minOf(12.dp, (height - 24.dp) / 2)
  fun send() {
    if (!canSend) return
    val question = text.trim()
    text = ""
    focusManager.clearFocus()
    keyboardController?.hide()
    onSend(question)
  }

  Surface(
    shape = RoundedCornerShape(height / 2),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    modifier = modifier.fillMaxWidth().heightIn(min = height),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      Box(
        modifier =
          Modifier.weight(1f)
            .heightIn(min = height)
            .then(
              if (appearDisabled) {
                Modifier.clickable(enabled = enabled, onClick = onDisabledClick).semantics {
                  contentDescription = placeholder
                }
              } else {
                Modifier
              }
            )
            .padding(vertical = textVerticalPadding),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (text.isEmpty() || appearDisabled) {
          Text(
            text = if (appearDisabled) placeholder else placeholder,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = placeholderColor,
            maxLines = 1,
          )
        }
        if (!appearDisabled) {
          @Suppress("DEPRECATION")
          BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = inputEnabled,
            textStyle =
              MaterialTheme.typography.bodyLarge.copy(color = textColor, fontSize = 16.sp),
            cursorBrush = SolidColor(cursorColor),
            singleLine = false,
            maxLines = MAX_PROMPT_LINES,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier =
              Modifier.fillMaxWidth().focusRequester(focusRequester).semantics {
                if (text.isBlank()) {
                  contentDescription = placeholder
                }
              },
          )
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      IconButton(
        onClick = if (appearDisabled) onDisabledClick else ::send,
        enabled = if (appearDisabled) enabled else canSend,
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = if (appearDisabled) idleSendContainerColor else sendContainerColor,
            contentColor = if (appearDisabled) idleSendIconColor else sendIconColor,
            disabledContainerColor = idleSendContainerColor,
            disabledContentColor = idleSendIconColor,
          ),
        modifier = Modifier.padding(bottom = sendButtonMargin).size(sendButtonSize),
      ) {
        Icon(
          imageVector = Icons.Filled.ArrowUpward,
          contentDescription = stringResource(R.string.xrteacher_send_text_desc),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
