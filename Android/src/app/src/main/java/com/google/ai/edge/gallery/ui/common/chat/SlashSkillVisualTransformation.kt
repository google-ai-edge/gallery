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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.skills.SlashSkillMatcher

private val PILL_HORIZONTAL_OUTSET_DP = 4.dp
private val PILL_VERTICAL_INSET_DP = 1.dp
private val PILL_CORNER_RADIUS_DP = 7.dp

/**
 * [OutputTransformation] that styles recognized `/<skill-name>` tokens in the chat input box while
 * preserving plain-text editing, cursor positioning, and IME composition. Rounded pill backgrounds
 * are drawn separately behind the text via [drawSlashSkillPills].
 */
data class SlashSkillOutputTransformation(
  private val skillNames: Set<String>,
  private val pillTextColor: Color,
) : OutputTransformation {

  override fun TextFieldBuffer.transformOutput() {
    val ranges =
      SlashSkillMatcher.recognizedTokenRanges(
        input = asCharSequence().toString(),
        skillNames = skillNames,
      )
    val tokenStyle = SpanStyle(color = pillTextColor, fontWeight = FontWeight.Medium)
    for (range in ranges) {
      addStyle(spanStyle = tokenStyle, start = range.first, end = range.last + 1)
    }
  }
}

/** Draws wider rounded pill backgrounds behind recognized `/<skill-name>` tokens in [layout]. */
internal fun DrawScope.drawSlashSkillPills(
  layout: TextLayoutResult,
  input: String,
  skillNames: Set<String>,
  pillFillColor: Color,
) {
  if (layout.layoutInput.text.text != input) {
    return
  }
  val ranges = SlashSkillMatcher.recognizedTokenRanges(input = input, skillNames = skillNames)

  val horizontalOutsetPx = PILL_HORIZONTAL_OUTSET_DP.toPx()
  val verticalInsetPx = PILL_VERTICAL_INSET_DP.toPx()
  val cornerRadiusPx = PILL_CORNER_RADIUS_DP.toPx()
  val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)

  for (range in ranges) {
    val startLine = layout.getLineForOffset(range.first)
    val endLine = layout.getLineForOffset(range.last)
    for (line in startLine..endLine) {
      val lineStart = maxOf(range.first, layout.getLineStart(line))
      val lineVisibleEnd = layout.getLineEnd(line, visibleEnd = true) - 1
      val lineEnd = minOf(range.last, lineVisibleEnd)
      if (lineStart > lineEnd) {
        continue
      }
      val startBox = layout.getBoundingBox(lineStart)
      val endBox = layout.getBoundingBox(lineEnd)
      val left = minOf(startBox.left, endBox.left) - horizontalOutsetPx
      val right = maxOf(startBox.right, endBox.right) + horizontalOutsetPx
      val top = layout.getLineTop(line) + verticalInsetPx
      val bottom = layout.getLineBottom(line) - verticalInsetPx
      if (right > left && bottom > top) {
        drawRoundRect(
          color = pillFillColor,
          topLeft = Offset(left, top),
          size = Size(right - left, bottom - top),
          cornerRadius = cornerRadius,
        )
      }
    }
  }
}
