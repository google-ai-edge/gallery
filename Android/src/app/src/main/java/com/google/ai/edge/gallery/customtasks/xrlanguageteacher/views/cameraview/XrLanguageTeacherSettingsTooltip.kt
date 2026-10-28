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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
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
 * Step 1 of the camera screen's one-time tooltip tour: a dimmed screen, [TeacherSettingsButton]
 * highlighted with a soft glow at [anchorBounds], and a rich tooltip card below it explaining that
 * the button changes the languages and the teacher. "Next" calls [onNext] to go to
 * [AskThreeWaysTooltip].
 *
 * Styled like the Audio Config onboarding tooltip on the Model Config button.
 *
 * @param anchorBounds The settings button's bounds in window coordinates.
 */
@Composable
fun TeacherSettingsTooltip(anchorBounds: IntRect, onNext: () -> Unit) {
  SpotlightTooltip(
    anchorBounds = anchorBounds,
    spotlightSize = DpSize(TEACHER_SETTINGS_BUTTON_SIZE, TEACHER_SETTINGS_BUTTON_SIZE),
    title = stringResource(R.string.xrteacher_settings_tooltip_title),
    body = stringResource(R.string.xrteacher_settings_tooltip_body),
    actions = listOf(TooltipAction(stringResource(R.string.xrteacher_tooltip_next), onNext)),
  ) {
    // Only the card's button acts, so the highlighted copy of the button doesn't respond to taps.
    TeacherSettingsButton(onClick = {}, modifier = Modifier.clearAndSetSemantics {})
  }
}

/**
 * Step 2 of the camera screen's one-time tooltip tour, in the same style as
 * [TeacherSettingsTooltip]: the camera HUD's whole input row (camera, "Ask a question" and keyboard
 * buttons) highlighted at [anchorBounds], and a card above it titled "Ask in 3 ways" that explains
 * each button. "Previous" calls [onPrevious] to go back to [TeacherSettingsTooltip], and "Dismiss"
 * calls [onDismiss] to end the tour.
 *
 * @param anchorBounds The input row's bounds in window coordinates.
 */
@Composable
fun AskThreeWaysTooltip(anchorBounds: IntRect, onPrevious: () -> Unit, onDismiss: () -> Unit) {
  val rowWidth = with(LocalDensity.current) { anchorBounds.width.toDp() }
  SpotlightTooltip(
    anchorBounds = anchorBounds,
    spotlightSize = DpSize(rowWidth, CAMERA_INPUT_ROW_HEIGHT),
    title = pluralStringResource(R.plurals.xrteacher_ask_ways_tooltip_title, 3, 3),
    body = stringResource(R.string.xrteacher_ask_ways_tooltip_body),
    actions =
      listOf(
        TooltipAction(stringResource(R.string.xrteacher_tooltip_previous), onPrevious),
        TooltipAction(stringResource(R.string.xrteacher_tooltip_dismiss), onDismiss),
      ),
  ) {
    // A copy of the row; only the card's buttons act, so it doesn't respond to taps.
    Row(
      modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      CameraHudIconButton(icon = Icons.Filled.PhotoCamera, enabled = true, onClick = {})
      CameraHudAskButton(enabled = true, onClick = {}, modifier = Modifier.weight(1f))
      CameraHudIconButton(icon = Icons.Default.Keyboard, enabled = true, onClick = {})
    }
  }
}

/** A button on a [SpotlightTooltip] card. */
private class TooltipAction(val label: String, val onClick: () -> Unit)

/**
 * Full-window scrim with [spotlight] redrawn over its anchor at [anchorBounds] inside a soft glow,
 * and a rich tooltip card with [title], [body] and [actions] below it (or above it when there isn't
 * room below, as for an anchor at the bottom of the screen), kept within the screen's side margins.
 *
 * The scrim consumes taps, and Back doesn't close it, so only the card's buttons (and the
 * [spotlight], if it is clickable) act.
 */
@Composable
private fun SpotlightTooltip(
  anchorBounds: IntRect,
  spotlightSize: DpSize,
  title: String,
  body: String,
  actions: List<TooltipAction>,
  spotlight: @Composable () -> Unit,
) {
  if (anchorBounds.width <= 0 || anchorBounds.height <= 0) {
    return
  }
  val density = LocalDensity.current
  val spotlightWidthPx = with(density) { spotlightSize.width.roundToPx() }
  val spotlightHeightPx = with(density) { spotlightSize.height.roundToPx() }
  val glowRadiusPx =
    with(density) { (spotlightSize.height / 2 + SPOTLIGHT_GLOW_EXTRA_RADIUS).toPx() }
  // A wide spotlight (such as the whole input row) gets an elliptical glow.
  val glowScaleX =
    with(density) { (spotlightSize.width / 2 + SPOTLIGHT_GLOW_EXTRA_RADIUS).toPx() } / glowRadiusPx
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
      val spotlightLeft = anchorBounds.center.x - spotlightWidthPx / 2
      val spotlightTop = anchorBounds.center.y - spotlightHeightPx / 2
      Box(
        modifier =
          Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(spotlightLeft, spotlightTop) }
            .size(spotlightSize)
            .drawBehind {
              scale(scaleX = glowScaleX, scaleY = 1f, pivot = center) {
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
              }
            },
        contentAlignment = Alignment.Center,
      ) {
        spotlight()
      }

      val cardTopPx = spotlightTop + spotlightHeightPx + cardTopSpacingPx
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
        val cardAbovePx = spotlightTop - cardTopSpacingPx - placeable.height
        val fitsBelow = cardTopPx + placeable.height <= constraints.maxHeight
        val cardTop =
          (if (fitsBelow || cardAbovePx < 0) cardTopPx else cardAbovePx).coerceIn(
            0,
            (constraints.maxHeight - placeable.height).coerceAtLeast(0),
          )
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

/** Space between the spotlight and the card below (or above) it. */
private val CARD_TOP_SPACING = 12.dp

/** Smallest space between the card and the screen's left and right edges. */
private val CARD_HORIZONTAL_MARGIN = 28.dp

/** Widest the card gets on large screens. */
private val CARD_MAX_WIDTH = 328.dp

/** Corner radius of the card. */
private val CARD_CORNER_RADIUS = 16.dp

/** Opacity of the black scrim over the rest of the screen. */
private const val SCRIM_ALPHA = 0.6f
