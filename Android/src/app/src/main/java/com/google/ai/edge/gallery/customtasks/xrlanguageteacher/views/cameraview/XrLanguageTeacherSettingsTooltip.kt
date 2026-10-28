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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.inversePrimaryLight

/**
 * The camera screen's one-time tooltip: a dimmed screen, [TeacherSettingsButton] highlighted with a
 * soft glow at [anchorBounds], and a rich tooltip card below it explaining that the button changes
 * the languages and the teacher. "Got it" calls [onGotIt] to dismiss it.
 *
 * Styled like the Audio Config onboarding tooltip on the Model Config button.
 *
 * @param anchorBounds The settings button's bounds in window coordinates.
 */
@Composable
fun TeacherSettingsTooltip(anchorBounds: IntRect, onGotIt: () -> Unit) {
  SpotlightTooltip(
    anchorBounds = anchorBounds,
    spotlightSize = TEACHER_SETTINGS_BUTTON_SIZE,
    title = stringResource(R.string.xrteacher_settings_tooltip_title),
    body = stringResource(R.string.xrteacher_settings_tooltip_body),
    actions =
      listOf(TooltipAction(stringResource(R.string.xrteacher_settings_tooltip_got_it), onGotIt)),
  ) {
    // Only the card's button acts, so the highlighted copy of the button doesn't respond to taps.
    TeacherSettingsButton(onClick = {}, modifier = Modifier.clearAndSetSemantics {})
  }
}

/** A button on a [SpotlightTooltip] card. */
private class TooltipAction(val label: String, val onClick: () -> Unit)

/**
 * Full-window scrim with [spotlight] redrawn over its anchor at [anchorBounds] inside a soft glow,
 * and a rich tooltip card with [title], [body] and [actions] below it, kept within the screen's
 * side margins.
 *
 * The scrim consumes taps, and Back doesn't close it, so only the card's buttons act.
 */
@Composable
private fun SpotlightTooltip(
  anchorBounds: IntRect,
  spotlightSize: Dp,
  title: String,
  body: String,
  actions: List<TooltipAction>,
  spotlight: @Composable () -> Unit,
) {
  if (anchorBounds.width <= 0 || anchorBounds.height <= 0) {
    return
  }
  val density = LocalDensity.current
  val spotlightSizePx = with(density) { spotlightSize.roundToPx() }
  val glowRadiusPx = with(density) { (spotlightSize / 2 + SPOTLIGHT_GLOW_EXTRA_RADIUS).toPx() }
  val cardTopSpacingPx = with(density) { CARD_TOP_SPACING.roundToPx() }
  val horizontalMarginPx = with(density) { CARD_HORIZONTAL_MARGIN.roundToPx() }

  Popup(
    popupPositionProvider = WindowOriginPopupPositionProvider,
    properties =
      PopupProperties(
        focusable = true,
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        clippingEnabled = false,
      ),
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(Color.Black.copy(alpha = SCRIM_ALPHA))
          .semantics { paneTitle = title }
          .pointerInput(Unit) {
            detectTapGestures {
              // Consume taps on the scrim so only the tooltip's actions close it.
            }
          }
    ) {
      // [anchorBounds] is in absolute window coordinates, so the spotlight and the card are placed
      // from the left edge without mirroring for right-to-left layouts.
      val spotlightLeft = anchorBounds.center.x - spotlightSizePx / 2
      val spotlightTop = anchorBounds.center.y - spotlightSizePx / 2
      Box(
        modifier =
          Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(spotlightLeft, spotlightTop) }
            .size(spotlightSize)
            .drawBehind {
              drawCircle(
                brush =
                  Brush.radialGradient(
                    0.0f to inversePrimaryLight.copy(alpha = 0.85f),
                    0.55f to inversePrimaryLight.copy(alpha = 0.55f),
                    0.8f to inversePrimaryLight.copy(alpha = 0.2f),
                    1.0f to Color.Transparent,
                    radius = glowRadiusPx,
                  ),
                radius = glowRadiusPx,
              )
            },
        contentAlignment = Alignment.Center,
      ) {
        spotlight()
      }

      val cardTopPx = spotlightTop + spotlightSizePx + cardTopSpacingPx
      Layout(
        content = { SpotlightTooltipCard(title = title, body = body, actions = actions) },
        modifier = Modifier.fillMaxSize(),
      ) { measurables, constraints ->
        val maxCardWidth = (constraints.maxWidth - 2 * horizontalMarginPx).coerceAtLeast(0)
        val placeable =
          measurables
            .first()
            .measure(constraints.copy(minWidth = 0, maxWidth = maxCardWidth, minHeight = 0))
        val idealLeft = anchorBounds.center.x - placeable.width / 2
        val minLeft = horizontalMarginPx.coerceAtMost(constraints.maxWidth - placeable.width)
        val maxLeft =
          (constraints.maxWidth - horizontalMarginPx - placeable.width).coerceAtLeast(minLeft)
        val cardLeft = idealLeft.coerceIn(minLeft.coerceAtLeast(0), maxLeft.coerceAtLeast(0))
        val cardTop =
          cardTopPx.coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(cardLeft, cardTop) }
      }
    }
  }
}

/** The rich tooltip card of [SpotlightTooltip]: a title, body text and a row of [actions]. */
@Composable
private fun SpotlightTooltipCard(title: String, body: String, actions: List<TooltipAction>) {
  Surface(
    modifier = Modifier.widthIn(max = CARD_MAX_WIDTH),
    shape = RoundedCornerShape(CARD_CORNER_RADIUS),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shadowElevation = 6.dp,
  ) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(4.dp))
      // Shifted left so the first button's text, not its touch area, lines up with the text above.
      Row(
        modifier = Modifier.offset(x = (-8).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (action in actions) {
          TextButton(
            onClick = action.onClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
          ) {
            Text(text = action.label)
          }
        }
      }
    }
  }
}

/** Places the popup at the window's top-left corner, so it covers the whole window. */
private object WindowOriginPopupPositionProvider : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = IntOffset.Zero
}

/** Size of [TeacherSettingsButton], which the spotlight redraws at the same size. */
private val TEACHER_SETTINGS_BUTTON_SIZE = 44.dp

/** How far the glow extends past the spotlight's edge. */
private val SPOTLIGHT_GLOW_EXTRA_RADIUS = 16.dp

/** Space between the spotlight and the card below it. */
private val CARD_TOP_SPACING = 12.dp

/** Smallest space between the card and the screen's left and right edges. */
private val CARD_HORIZONTAL_MARGIN = 28.dp

/** Widest the card gets on large screens. */
private val CARD_MAX_WIDTH = 328.dp

/** Corner radius of the card. */
private val CARD_CORNER_RADIUS = 16.dp

/** Opacity of the black scrim over the rest of the screen. */
private const val SCRIM_ALPHA = 0.6f
