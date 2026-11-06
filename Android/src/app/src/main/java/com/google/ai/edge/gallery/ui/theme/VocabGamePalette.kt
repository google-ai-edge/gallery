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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colors of the XR Language Teacher `Vocab Popper` and `Buddy Runner` game screens, read
 * through [CustomColors.vocabGamePalette] so they follow the app's light or dark theme. They mirror
 * iOS's `XrLanguageTeacherVocabGamePalette`: dark mode keeps the arcade palette that makes stickers
 * and glows pop, and light mode uses the Material 3 surface and container tokens with darker status
 * colors that stay readable on the light surface (Material 3 has no success or warning roles).
 *
 * @property isDark Whether this is the dark arcade palette.
 * @property screenBackground The background behind the arena and the results.
 * @property gameplayBackgroundShapesAlpha The opacity of the background shapes behind the arena.
 * @property resultsBackgroundShapesAlpha The opacity of the background shapes behind the results.
 * @property chromePill The fill of the static HUD pills: timer, lives, scoreboard, streak, status.
 * @property chromePillStroke The hairline around HUD pills, so they stand apart from a light
 *   surface ([Color.Transparent] in dark mode).
 * @property interactivePillFill The fill of tappable pills (the `Tap` prompt and choices), set
 *   apart from static HUD pills.
 * @property interactivePillText The text on [interactivePillFill].
 * @property chromePrimaryValueText The emphasized numbers of the HUD and results: the score, streak
 *   count, and headline.
 * @property chromeText The regular HUD text and icons.
 * @property chromeTextMuted The labels and inactive icons of the HUD.
 * @property chromeDivider The dividers between the scoreboard columns and the outline of the heard
 *   word chips.
 * @property neutralMultiplierPillFill The fill of the `1×` streak multiplier pill.
 * @property scoreGold The best score and the trophy.
 * @property streakOrange The streak flame and border at `3×`.
 * @property popGreen Popped and cleared words, and the unique word count.
 * @property urgentRed The urgent countdown, lives, missed words, and bumped obstacles.
 * @property successEdgeGlow The edge glow flashed when a word is popped or cleared.
 * @property urgentEdgeGlow The edge glow flashed on a wrong tap or when Buddy bumps into an
 *   obstacle.
 * @property edgeGlowMaxAlpha The opacity of the edge glow at full intensity.
 * @property popBannerFill The fill of the floating points banner in `Vocab Popper`.
 * @property popBannerStroke The outline of the floating points banner ([Color.Transparent] in dark
 *   mode).
 * @property runnerTrackLine The track line in `Buddy Runner`.
 * @property runnerTrackDash The scrolling dashes under the track in `Buddy Runner`.
 * @property obstacleCardFill The tile drawn for an obstacle whose sticker isn't ready.
 * @property obstacleCardDefaultBorder The border of an obstacle tile that wasn't bumped into or
 *   cleared.
 * @property obstacleCardText The meaning shown on an obstacle tile.
 * @property stickerFrameOutline The outline around an unsegmented photo sticker's white frame
 *   ([Color.Transparent] in dark mode).
 * @property resultsScoreBadgeFill The fill of the wavy badge behind the final score.
 * @property resultsScoreBadgeStroke The outline of the wavy final score badge ([Color.Transparent]
 *   in dark mode).
 * @property newHighScoreBadgeFill The fill of the "New high score!" badge.
 * @property newHighScoreBadgeText The text and trophy of the "New high score!" badge.
 * @property primaryCtaFill The fill of the "Play again" button.
 * @property primaryCtaText The text of the "Play again" button.
 * @property secondaryCtaText The text of the "Exit" button.
 * @property secondaryCtaStroke The outline of the "Exit" button.
 */
@Immutable
data class VocabGamePalette(
  val isDark: Boolean,
  val screenBackground: Color,
  val gameplayBackgroundShapesAlpha: Float,
  val resultsBackgroundShapesAlpha: Float,
  val chromePill: Color,
  val chromePillStroke: Color,
  val interactivePillFill: Color,
  val interactivePillText: Color,
  val chromePrimaryValueText: Color,
  val chromeText: Color,
  val chromeTextMuted: Color,
  val chromeDivider: Color,
  val neutralMultiplierPillFill: Color,
  val scoreGold: Color,
  val streakOrange: Color,
  val popGreen: Color,
  val urgentRed: Color,
  val successEdgeGlow: Color,
  val urgentEdgeGlow: Color,
  val edgeGlowMaxAlpha: Float,
  val popBannerFill: Color,
  val popBannerStroke: Color,
  val runnerTrackLine: Color,
  val runnerTrackDash: Color,
  val obstacleCardFill: Color,
  val obstacleCardDefaultBorder: Color,
  val obstacleCardText: Color,
  val stickerFrameOutline: Color,
  val resultsScoreBadgeFill: Color,
  val resultsScoreBadgeStroke: Color,
  val newHighScoreBadgeFill: Color,
  val newHighScoreBadgeText: Color,
  val primaryCtaFill: Color,
  val primaryCtaText: Color,
  val secondaryCtaText: Color,
  val secondaryCtaStroke: Color,
)

/** The light theme [VocabGamePalette], matching iOS's light mode game palette. */
val lightVocabGamePalette =
  VocabGamePalette(
    isDark = false,
    screenBackground = surfaceLight,
    gameplayBackgroundShapesAlpha = 0.85f,
    resultsBackgroundShapesAlpha = 0.85f,
    chromePill = surfaceContainerHighLight,
    chromePillStroke = outlineVariantLight.copy(alpha = 0.6f),
    interactivePillFill = secondaryContainerLight.copy(alpha = 0.96f),
    interactivePillText = onSecondaryContainerLight,
    chromePrimaryValueText = onSurfaceLight,
    chromeText = onSurfaceLight,
    chromeTextMuted = onSurfaceVariantLight,
    chromeDivider = outlineVariantLight,
    neutralMultiplierPillFill = surfaceContainerHighestLight,
    scoreGold = Color(0xFFB06000),
    streakOrange = Color(0xFFC25100),
    popGreen = Color(0xFF137333),
    urgentRed = Color(0xFFC5221F),
    successEdgeGlow = Color(0xFF1E8E3E),
    urgentEdgeGlow = Color(0xFFD93025),
    edgeGlowMaxAlpha = 0.38f,
    popBannerFill = Color(0xFFE6F4EA).copy(alpha = 0.96f),
    popBannerStroke = Color(0xFF137333).copy(alpha = 0.30f),
    runnerTrackLine = onSurfaceLight.copy(alpha = 0.22f),
    runnerTrackDash = onSurfaceLight.copy(alpha = 0.13f),
    obstacleCardFill = surfaceContainerLowestLight,
    obstacleCardDefaultBorder = outlineVariantLight,
    obstacleCardText = onSurfaceLight,
    stickerFrameOutline = outlineVariantLight.copy(alpha = 0.55f),
    resultsScoreBadgeFill = surfaceContainerHighLight,
    resultsScoreBadgeStroke = outlineVariantLight.copy(alpha = 0.5f),
    newHighScoreBadgeFill = Color(0xFFFEF0C7),
    newHighScoreBadgeText = Color(0xFF7A4100),
    primaryCtaFill = primaryLight,
    primaryCtaText = onPrimaryLight,
    secondaryCtaText = primaryLight,
    secondaryCtaStroke = outlineLight,
  )

/** The dark theme [VocabGamePalette]: the arcade palette, matching iOS's dark mode game palette. */
val darkVocabGamePalette =
  VocabGamePalette(
    isDark = true,
    screenBackground = Color(0xFF111318),
    gameplayBackgroundShapesAlpha = 0.62f,
    resultsBackgroundShapesAlpha = 0.6f,
    chromePill = Color(0xFF33353A),
    chromePillStroke = Color.Transparent,
    interactivePillFill = Color(0xFF33353A).copy(alpha = 0.95f),
    interactivePillText = Color.White,
    chromePrimaryValueText = Color.White,
    chromeText = Color(0xFFE2E2E9),
    chromeTextMuted = Color(0xFFC4C6D0),
    chromeDivider = Color.White.copy(alpha = 0.14f),
    neutralMultiplierPillFill = Color.White.copy(alpha = 0.10f),
    scoreGold = Color(0xFFF5B841),
    streakOrange = Color(0xFFFF8A3D),
    popGreen = Color(0xFF5CE196),
    urgentRed = Color(0xFFFF6B6B),
    successEdgeGlow = Color(0xFF5CE196),
    urgentEdgeGlow = Color(0xFFFF6B6B),
    edgeGlowMaxAlpha = 0.55f,
    popBannerFill = Color.Black.copy(alpha = 0.72f),
    popBannerStroke = Color.Transparent,
    runnerTrackLine = Color.White.copy(alpha = 0.28f),
    runnerTrackDash = Color.White.copy(alpha = 0.16f),
    obstacleCardFill = Color(0xFF2B2E36),
    obstacleCardDefaultBorder = Color.White,
    obstacleCardText = Color.White,
    stickerFrameOutline = Color.Transparent,
    resultsScoreBadgeFill = Color(0xFF3A3D45),
    resultsScoreBadgeStroke = Color.Transparent,
    newHighScoreBadgeFill = Color(0xFF3F2E00),
    newHighScoreBadgeText = Color(0xFFFFD97D),
    primaryCtaFill = primaryDark,
    primaryCtaText = onPrimaryDark,
    secondaryCtaText = Color(0xFFE2E2E9),
    secondaryCtaStroke = Color(0xFF8E9099),
  )
