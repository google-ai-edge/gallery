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

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun CursorTrackingTextField(
  @StringRes labelResId: Int? = null,
  @StringRes supportingTextResId: Int? = null,
  initialValue: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  minLines: Int = 1,
  extraOffset: Dp = 56.dp,
  monoFont: Boolean = false,
  extraBottomComposable: @Composable () -> Unit = {},
) {
  val interactionSource = remember { MutableInteractionSource() }
  var textFieldValue by remember { mutableStateOf(TextFieldValue(initialValue)) }
  var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(initialValue) {
    if (initialValue != textFieldValue.text) {
      textFieldValue =
        textFieldValue.copy(
          text = initialValue,
          // Keep the cursor where it is, but make sure it's not
          // out of bounds if the new text is shorter.
          selection = textFieldValue.selection.coerceIn(0, initialValue.length),
        )
    }
  }

  // Track cursor position and make sure it is visible during instructions editing.
  LaunchedEffect(textFieldValue.selection, textLayoutResult) {
    val layout = textLayoutResult ?: return@LaunchedEffect
    val selection = textFieldValue.selection

    // Safety check: ensure the layout actually matches the current text length
    if (selection.start <= layout.layoutInput.text.length) {
      val cursorRect = layout.getCursorRect(selection.start)
      val bufferPx = with(density) { extraOffset.toPx() }
      val biggerCursorRect = cursorRect.copy(bottom = cursorRect.bottom + bufferPx)
      scope.launch { bringIntoViewRequester.bringIntoView(biggerCursorRect) }
    }
  }
  BasicTextField(
    value = textFieldValue,
    enabled = enabled,
    onValueChange = { newValue ->
      textFieldValue = newValue
      onValueChange(newValue.text)
    },
    onTextLayout = { textLayoutResult = it },
    modifier = modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester),
    textStyle =
      (if (monoFont) {
          MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
          MaterialTheme.typography.bodyMedium
        })
        .copy(
          // BasicTextField needs explicit color
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.7f)
        ),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    minLines = minLines,
    interactionSource = interactionSource,
    decorationBox = { innerTextField ->
      OutlinedTextFieldDefaults.DecorationBox(
        value = textFieldValue.text,
        innerTextField = innerTextField,
        enabled = true,
        singleLine = false,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        label = { if (labelResId != null) Text(stringResource(labelResId)) },
        supportingText = {
          if (supportingTextResId != null) {
            Column() {
              Text(stringResource(supportingTextResId))
              extraBottomComposable()
            }
          }
        },
        // The ContainerBox draws the actual border/outline
        container = {
          OutlinedTextFieldDefaults.Container(
            enabled = true,
            isError = false,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(),
            shape = OutlinedTextFieldDefaults.shape,
            focusedBorderThickness = 2.dp,
            unfocusedBorderThickness = 1.dp,
          )
        },
      )
    },
  )
}
