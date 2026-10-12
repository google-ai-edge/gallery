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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch

/** Maximum width of the tooltip bubble so long explanations wrap instead of spanning the screen. */
private val TOOLTIP_MAX_WIDTH = 260.dp

/** Size of the (i) glyph inside the 48dp touch target. */
private val INFO_ICON_SIZE = 20.dp

/** Minimum gap between the tooltip and the window edges. */
private val TOOLTIP_WINDOW_MARGIN = 16.dp

/** Gap between the tooltip and its anchor. */
private val TOOLTIP_ANCHOR_SPACING = 6.dp

/**
 * An (i) icon button that shows [tooltipText] in a persistent plain tooltip when tapped.
 *
 * The tooltip stays until the user taps outside it, so it suits short explanations next to a label
 * or header. It is positioned by [rememberClampedTooltipPositionProvider], so it never overflows
 * the window edges.
 *
 * @param tooltipText The explanation shown in the tooltip.
 * @param contentDescription Accessibility label for the icon button, e.g. "About supported
 *   languages".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTooltipIconButton(
  tooltipText: String,
  contentDescription: String,
  modifier: Modifier = Modifier,
) {
  val tooltipState = rememberTooltipState(isPersistent = true)
  val coroutineScope = rememberCoroutineScope()
  TooltipBox(
    positionProvider = rememberClampedTooltipPositionProvider(),
    tooltip = {
      PlainTooltip(modifier = Modifier.widthIn(max = TOOLTIP_MAX_WIDTH)) {
        Text(text = tooltipText, style = MaterialTheme.typography.bodySmall)
      }
    },
    state = tooltipState,
    modifier = modifier,
  ) {
    IconButton(onClick = { coroutineScope.launch { tooltipState.show() } }) {
      Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(INFO_ICON_SIZE),
      )
    }
  }
}

/**
 * Returns a [PopupPositionProvider] that centers a tooltip horizontally on its anchor and places it
 * above the anchor, or below when there is no room above, while keeping it inside the window.
 */
@Composable
fun rememberClampedTooltipPositionProvider(): PopupPositionProvider {
  val density = LocalDensity.current
  val marginPx = with(density) { TOOLTIP_WINDOW_MARGIN.roundToPx() }
  val spacingPx = with(density) { TOOLTIP_ANCHOR_SPACING.roundToPx() }
  return remember(marginPx, spacingPx) {
    object : PopupPositionProvider {
      override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
      ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = centeredX.coerceIn(marginPx, maxX)
        val aboveY = anchorBounds.top - popupContentSize.height - spacingPx
        val belowY = anchorBounds.bottom + spacingPx
        val y =
          if (aboveY >= marginPx) {
            aboveY
          } else {
            belowY.coerceAtMost(
              (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
            )
          }
        return IntOffset(x, y)
      }
    }
  }
}
