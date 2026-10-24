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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.errorDark
import com.google.ai.edge.gallery.ui.theme.inversePrimaryLight
import com.google.ai.edge.gallery.ui.theme.surfaceContainerDark

/** Test tag for the full-screen scrim of [AudioConfigOnboardingTooltip]. */
const val AUDIO_CONFIG_ONBOARDING_SCRIM_TEST_TAG = "audio_config_onboarding_scrim"

/** Test tag for the highlighted Config button spotlight in [AudioConfigOnboardingTooltip]. */
const val AUDIO_CONFIG_ONBOARDING_SPOTLIGHT_TEST_TAG = "audio_config_onboarding_spotlight"

private val SPOTLIGHT_BUTTON_SIZE = 40.dp
private val SPOTLIGHT_ICON_SIZE = 20.dp
private val SPOTLIGHT_GLOW_EXTRA_RADIUS = 16.dp
private val SPOTLIGHT_BADGE_SIZE = 8.dp
private val TOOLTIP_TOP_SPACING = 12.dp
private val TOOLTIP_HORIZONTAL_MARGIN = 28.dp
private val TOOLTIP_MAX_WIDTH = 328.dp
private val TOOLTIP_CORNER_RADIUS = 16.dp
private const val SCRIM_ALPHA = 0.6f

private object WindowOriginPopupPositionProvider : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = IntOffset.Zero
}

/**
 * Full-screen onboarding overlay and rich tooltip anchored to the top app bar's Model Config
 * (`Tune`) button on speech-enabled task screens.
 *
 * Renders a full-window translucent scrim that blocks outside taps while allowing system Back to
 * dismiss, redraws the highlighted Config button at [anchorBounds] with a soft radial glow and
 * notification dot (tapping the spotlight triggers [onSpotlightClick]), and positions a rich
 * tooltip card below it with a **"Got it"** dismissal action.
 */
@Composable
fun AudioConfigOnboardingTooltip(
  anchorBounds: IntRect,
  onGotIt: () -> Unit,
  onSpotlightClick: () -> Unit = onGotIt,
) {
  if (anchorBounds.width <= 0 || anchorBounds.height <= 0) {
    return
  }
  val density = LocalDensity.current
  val spotlightSizePx = with(density) { SPOTLIGHT_BUTTON_SIZE.roundToPx() }
  val glowRadiusPx =
    with(density) { (SPOTLIGHT_BUTTON_SIZE / 2 + SPOTLIGHT_GLOW_EXTRA_RADIUS).toPx() }
  val tooltipTopSpacingPx = with(density) { TOOLTIP_TOP_SPACING.roundToPx() }
  val horizontalMarginPx = with(density) { TOOLTIP_HORIZONTAL_MARGIN.roundToPx() }

  Popup(
    popupPositionProvider = WindowOriginPopupPositionProvider,
    onDismissRequest = onGotIt,
    properties =
      PopupProperties(
        focusable = true,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        clippingEnabled = false,
      ),
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(Color.Black.copy(alpha = SCRIM_ALPHA))
          .testTag(AUDIO_CONFIG_ONBOARDING_SCRIM_TEST_TAG)
          .pointerInput(Unit) {
            detectTapGestures {
              // Consume taps on the scrim so only the explicit action button dismisses the tooltip.
            }
          }
    ) {
      // Highlighted Config button spotlight centered over the real Config button. `anchorBounds` is
      // in absolute window coordinates, so the spotlight and the tooltip card below are positioned
      // from the left edge without RTL mirroring. Because the spotlight sits directly on the fixed
      // dark scrim in both light and dark modes, it uses the dark-palette theme tokens
      // (`surfaceContainerDark`, `inversePrimaryLight`, `errorDark`) for consistent contrast.
      val spotlightLeft = anchorBounds.center.x - spotlightSizePx / 2
      val spotlightTop = anchorBounds.center.y - spotlightSizePx / 2
      Box(
        modifier =
          Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(spotlightLeft, spotlightTop) }
            .size(SPOTLIGHT_BUTTON_SIZE)
            .testTag(AUDIO_CONFIG_ONBOARDING_SPOTLIGHT_TEST_TAG)
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
            }
            .clickable(onClick = onSpotlightClick)
      ) {
        Box(
          modifier =
            Modifier.fillMaxSize().background(color = surfaceContainerDark, shape = CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(SPOTLIGHT_ICON_SIZE),
          )
        }
        Box(
          modifier =
            Modifier.align(Alignment.TopEnd)
              .offset(x = (-2).dp, y = 2.dp)
              .size(SPOTLIGHT_BADGE_SIZE)
              .background(color = errorDark, shape = CircleShape)
        )
      }

      // Rich tooltip card positioned below the spotlight circle and clamped within horizontal
      // screen margins.
      val cardTopPx = spotlightTop + spotlightSizePx + tooltipTopSpacingPx
      Layout(
        content = { AudioConfigOnboardingTooltipCard(onGotIt = onGotIt) },
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

@Composable
private fun AudioConfigOnboardingTooltipCard(onGotIt: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.widthIn(max = TOOLTIP_MAX_WIDTH),
    shape = RoundedCornerShape(TOOLTIP_CORNER_RADIUS),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shadowElevation = 6.dp,
  ) {
    Column(modifier = Modifier.padding(start = 8.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
      Text(
        text = stringResource(R.string.audio_config_onboarding_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.audio_config_onboarding_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onGotIt,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
          Text(text = stringResource(R.string.audio_config_onboarding_got_it))
        }
      }
    }
  }
}
