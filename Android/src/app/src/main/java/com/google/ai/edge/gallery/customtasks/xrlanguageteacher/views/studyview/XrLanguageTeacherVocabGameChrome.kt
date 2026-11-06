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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherGameHeardWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabGameViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.StudyBackgroundShapes
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import com.google.ai.edge.gallery.ui.common.PermissionAccessKind
import com.google.ai.edge.gallery.ui.common.rememberPermissionAccessState
import com.google.ai.edge.gallery.ui.theme.VocabGamePalette
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.onPrimaryDark
import com.google.ai.edge.gallery.ui.theme.primaryLight
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The [VocabGamePalette] of the current app theme: the colors of the `Vocab Popper` and `Buddy
 * Runner` game screens, which follow the app's light or dark theme (see `ui/theme`).
 */
internal val vocabGamePalette: VocabGamePalette
  @Composable @ReadOnlyComposable get() = MaterialTheme.customColors.vocabGamePalette

/**
 * Fills a game chrome element with [fill] in [shape], outlined by [stroke] in light mode so it
 * stands apart from the light surface.
 */
internal fun Modifier.vocabGameChromePill(
  palette: VocabGamePalette,
  shape: Shape,
  fill: Color = palette.chromePill,
  stroke: Color = palette.chromePillStroke,
): Modifier {
  val filled = background(fill, shape)
  return if (palette.isDark) filled else filled.border(1.dp, stroke, shape)
}

/**
 * The colors of the `Vocab Popper` and `Buddy Runner` arenas that stay the same in light and dark
 * mode: the vivid flying sticker fallbacks and pop particles, Buddy, and the streak multiplier
 * pills (dark text on a vivid fill).
 */
internal object VocabGameColors {
  /** The colors of the flying sticker fallbacks and pop particles, indexed by color index. */
  val palette: List<Color> =
    listOf(
      Color(0xFF1A73E8),
      Color(0xFF1E8E3E),
      Color(0xFFE8710A),
      Color(0xFF9334E6),
      Color(0xFFD93025),
      Color(0xFF007B83),
    )

  /** Buddy's feet in `Vocab Runner`. */
  val buddyFeet: Color = primaryLight
  /** Buddy's eyes and smile in `Vocab Runner`. */
  val buddyFace: Color = onPrimaryDark
  /** The top-to-bottom gradient of Buddy's body in `Vocab Runner`. */
  val buddyBody: List<Color> = listOf(Color(0xFF7CACF8), Color(0xFF1A73E8))
  /** Buddy's body gradient right after bumping into an obstacle. */
  val buddyStumbledBody: List<Color> = listOf(Color(0xFFFF8A80), Color(0xFFE53935))
  /** The fill of the `2×` streak multiplier pill. */
  val multiplierTwoFill: Color = Color(0xFFF5B841)
  /** The fill of the `3×` streak multiplier pill. */
  val multiplierThreeFill: Color = Color(0xFFFF8A3D)
  /** The fill of the `4×` streak multiplier pill. */
  val multiplierFourFill: Color = Color(0xFFFF6B6B)
}

/** Seconds left at which the countdown turns red. */
private const val URGENT_SECONDS = 10

/** The background and soft background shapes behind a game screen, following light or dark mode. */
@Composable
fun VocabGameBackground(modifier: Modifier = Modifier) {
  val palette = vocabGamePalette
  Box(modifier = modifier.fillMaxSize().background(palette.screenBackground)) {
    Box(modifier = Modifier.fillMaxSize().alpha(palette.gameplayBackgroundShapesAlpha)) {
      StudyBackgroundShapes()
    }
  }
}

/**
 * The top bar of a game screen: the close button on the start side, [centerContent] (the countdown
 * or the hearts) in the middle, and the sound toggle on the end side.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VocabGameTopBar(
  isMuted: Boolean,
  onToggleMute: () -> Unit,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
  centerContent: @Composable () -> Unit = {},
) {
  val palette = vocabGamePalette
  val buttonColors =
    IconButtonDefaults.filledTonalIconButtonColors(
      containerColor = palette.chromePill,
      contentColor = palette.chromeText,
    )
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilledTonalIconButton(
      onClick = onExit,
      colors = buttonColors,
      shapes = IconButtonDefaults.shapes(),
      modifier = Modifier.size(48.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = stringResource(R.string.xrteacher_game_exit),
        modifier = Modifier.size(22.dp),
      )
    }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { centerContent() }
    FilledTonalIconButton(
      onClick = onToggleMute,
      colors = buttonColors,
      shapes = IconButtonDefaults.shapes(),
      modifier = Modifier.size(48.dp),
    ) {
      Icon(
        imageVector =
          if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        contentDescription =
          stringResource(
            if (isMuted) R.string.xrteacher_game_unmute else R.string.xrteacher_game_mute
          ),
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

/**
 * The "Score | Unique | Best" (or two-column "Score | Best") pill under the top bar of a game
 * screen.
 *
 * When [uniqueWordCount] is non-null, a middle column displays how many distinct vocabulary words
 * the player has cleared so far in the round. When [isCompact] is true, narrower columns are used
 * so [VocabGameScoreboard] and [VocabGameStreakBadge] fit side by side on compact screens.
 */
@Composable
fun VocabGameScoreboard(
  score: Int,
  bestScore: Int,
  modifier: Modifier = Modifier,
  uniqueWordCount: Int? = null,
  isCompact: Boolean = false,
) {
  val hasUniqueColumn = uniqueWordCount != null
  val columnWidth =
    when {
      isCompact && hasUniqueColumn -> 66.dp
      hasUniqueColumn -> 80.dp
      else -> 92.dp
    }
  val scoreFontSize =
    when {
      isCompact && hasUniqueColumn -> 22.sp
      hasUniqueColumn -> 26.sp
      else -> 28.sp
    }
  val secondaryFontSize =
    when {
      isCompact && hasUniqueColumn -> 19.sp
      hasUniqueColumn -> 22.sp
      else -> 24.sp
    }
  val trophyIconSize =
    when {
      isCompact && hasUniqueColumn -> 14.dp
      hasUniqueColumn -> 16.dp
      else -> 18.dp
    }
  val horizontalPadding =
    when {
      isCompact && hasUniqueColumn -> 8.dp
      hasUniqueColumn -> 10.dp
      else -> 12.dp
    }
  val verticalPadding = if (isCompact && hasUniqueColumn) 6.dp else 8.dp
  val palette = vocabGamePalette
  val scoreContentDescription = stringResource(R.string.xrteacher_vocab_game_score, score)
  val bestContentDescription = stringResource(R.string.xrteacher_vocab_game_best, bestScore)
  Row(
    modifier =
      modifier
        .vocabGameChromePill(
          palette = palette,
          shape = RoundedCornerShape(20.dp),
          fill = palette.chromePill.copy(alpha = 0.92f),
        )
        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.width(columnWidth).clearAndSetSemantics {
          contentDescription = scoreContentDescription
        },
    ) {
      Text(
        text = stringResource(R.string.xrteacher_game_score_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = palette.chromeTextMuted,
      )
      Text(
        text = score.toString(),
        fontSize = scoreFontSize,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        color = palette.chromePrimaryValueText,
        maxLines = 1,
      )
    }
    if (uniqueWordCount != null) {
      val uniqueContentDescription =
        stringResource(R.string.xrteacher_game_unique_score, uniqueWordCount)
      Box(modifier = Modifier.width(1.dp).height(36.dp).background(palette.chromeDivider))
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
          Modifier.width(columnWidth).clearAndSetSemantics {
            contentDescription = uniqueContentDescription
          },
      ) {
        Text(
          text = stringResource(R.string.xrteacher_game_unique_label),
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = palette.chromeTextMuted,
        )
        Text(
          text = uniqueWordCount.toString(),
          fontSize = secondaryFontSize,
          lineHeight = 30.sp,
          fontWeight = FontWeight.Bold,
          color = palette.popGreen,
          maxLines = 1,
        )
      }
    }
    Box(modifier = Modifier.width(1.dp).height(36.dp).background(palette.chromeDivider))
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.width(columnWidth).clearAndSetSemantics {
          contentDescription = bestContentDescription
        },
    ) {
      Text(
        text = stringResource(R.string.xrteacher_game_best_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = palette.chromeTextMuted,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.EmojiEvents,
          contentDescription = null,
          tint = palette.scoreGold,
          modifier = Modifier.size(trophyIconSize),
        )
        Text(
          text = bestScore.toString(),
          fontSize = secondaryFontSize,
          lineHeight = 30.sp,
          fontWeight = FontWeight.Bold,
          color = palette.scoreGold,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * Live consecutive-answer streak and `1×`-`4×` score-multiplier badge paired horizontally next to
 * [VocabGameScoreboard] in `Vocab Popper` and `Buddy Runner`.
 */
@Composable
fun VocabGameStreakBadge(streak: Int, multiplier: Int, modifier: Modifier = Modifier) {
  val safeStreak = max(0, streak)
  val clampedMultiplier = max(1, multiplier)
  val palette = vocabGamePalette
  val flameColor =
    when {
      clampedMultiplier >= 4 -> palette.urgentRed
      clampedMultiplier == 3 -> palette.streakOrange
      safeStreak > 0 -> palette.scoreGold
      else -> palette.chromeTextMuted.copy(alpha = 0.55f)
    }
  val multiplierBackgroundColor =
    when {
      clampedMultiplier >= 4 -> VocabGameColors.multiplierFourFill
      clampedMultiplier == 3 -> VocabGameColors.multiplierThreeFill
      clampedMultiplier == 2 -> VocabGameColors.multiplierTwoFill
      else -> palette.neutralMultiplierPillFill
    }
  val multiplierForegroundColor =
    when {
      clampedMultiplier >= 4 -> Color(0xFF2C0505)
      clampedMultiplier == 3 -> Color(0xFF2B0E00)
      clampedMultiplier == 2 -> Color(0xFF261900)
      else -> palette.chromeTextMuted
    }
  // A tier border from `2×`; below that, the light mode hairline of every HUD pill.
  val badgeBorder =
    when {
      clampedMultiplier >= 4 -> BorderStroke(1.5.dp, palette.urgentRed.copy(alpha = 0.75f))
      clampedMultiplier == 3 -> BorderStroke(1.5.dp, palette.streakOrange.copy(alpha = 0.65f))
      clampedMultiplier == 2 -> BorderStroke(1.5.dp, palette.scoreGold.copy(alpha = 0.55f))
      !palette.isDark -> BorderStroke(1.dp, palette.chromePillStroke)
      else -> null
    }
  val countColor = if (safeStreak > 0) palette.chromePrimaryValueText else palette.chromeTextMuted
  val accessibilityDescription =
    stringResource(R.string.xrteacher_game_streak_accessibility, safeStreak, clampedMultiplier)
  val badgeShape = RoundedCornerShape(20.dp)
  Column(
    modifier =
      modifier
        .widthIn(min = 96.dp)
        .background(palette.chromePill.copy(alpha = 0.92f), badgeShape)
        .then(if (badgeBorder != null) Modifier.border(badgeBorder, badgeShape) else Modifier)
        .padding(horizontal = 11.dp, vertical = 8.dp)
        .clearAndSetSemantics { contentDescription = accessibilityDescription },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = stringResource(R.string.xrteacher_game_streak_label),
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      color = palette.chromeTextMuted,
      maxLines = 1,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.LocalFireDepartment,
        contentDescription = null,
        tint = flameColor,
        modifier = Modifier.size(17.dp),
      )
      Text(
        text = safeStreak.toString(),
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        color = countColor,
        maxLines = 1,
      )
      Text(
        text = stringResource(R.string.xrteacher_game_streak_multiplier, clampedMultiplier),
        fontSize = 13.sp,
        fontWeight = FontWeight.Black,
        color = multiplierForegroundColor,
        maxLines = 1,
        modifier =
          Modifier.background(multiplierBackgroundColor, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
  }
}

/** Returns [totalSeconds] as `m:ss`, for example `1:05`. */
internal fun formatGameTime(totalSeconds: Int): String {
  val seconds = max(0, totalSeconds)
  return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** The countdown pill of `Vocab Popper`, red during the last [URGENT_SECONDS] seconds. */
@Composable
fun VocabGameTimer(remainingSeconds: Int, modifier: Modifier = Modifier) {
  val formattedTime = formatGameTime(remainingSeconds)
  val isUrgent = remainingSeconds in 1..URGENT_SECONDS
  val description = stringResource(R.string.xrteacher_vocab_popper_time_left, formattedTime)
  val palette = vocabGamePalette
  Text(
    text = formattedTime,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    color = if (isUrgent) palette.urgentRed else palette.chromeText,
    modifier =
      modifier
        .vocabGameChromePill(palette = palette, shape = CircleShape)
        .padding(horizontal = 20.dp, vertical = 10.dp)
        .clearAndSetSemantics { contentDescription = description },
  )
}

/**
 * Three-heart lives pill paired next to [VocabGameTimer] in touch-mode study games (`Vocab Popper`
 * and `Buddy Runner`).
 */
@Composable
fun VocabGameLivesIndicator(
  lives: Int,
  modifier: Modifier = Modifier,
  maxLives: Int = XrLanguageTeacherVocabPopperEngine.INITIAL_LIVES,
) {
  val safeLives = max(0, lives)
  val safeMaxLives = max(0, maxLives)
  val description =
    stringResource(
      R.string.xrteacher_game_lives_remaining_accessibility,
      min(safeLives, safeMaxLives),
      safeMaxLives,
    )
  val palette = vocabGamePalette
  Row(
    modifier =
      modifier
        .vocabGameChromePill(palette = palette, shape = CircleShape)
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (index in 0 until safeMaxLives) {
      val isFilled = index < safeLives
      Icon(
        imageVector = if (isFilled) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = null,
        tint =
          if (isFilled) {
            palette.urgentRed
          } else {
            palette.chromeTextMuted.copy(alpha = 0.45f)
          },
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * Interactive target-word prompt pill displayed beneath the scoreboard in `Vocab Popper`
 * [XrLanguageTeacherVocabGameMode.TAP] mode, allowing the player to read the target vocabulary word
 * and tap to replay its spoken audio.
 */
@Composable
fun VocabPopperPromptBanner(word: String, onReplay: () -> Unit, modifier: Modifier = Modifier) {
  val description =
    stringResource(R.string.xrteacher_vocab_popper_replay_prompt_accessibility, word)
  val palette = vocabGamePalette
  Row(
    modifier =
      modifier
        .padding(horizontal = 16.dp)
        .clip(CircleShape)
        .background(palette.interactivePillFill)
        .clickable(onClick = onReplay, role = Role.Button)
        .padding(horizontal = 20.dp, vertical = 10.dp)
        .semantics(mergeDescendants = true) { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    Text(
      text = word,
      fontSize = 28.sp,
      lineHeight = 32.sp,
      fontWeight = FontWeight.Bold,
      color = palette.interactivePillText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The bottom bar of a game screen: the recently heard word chips above a pill with the microphone
 * state, which shows [listeningHint] while listening, [micPermissionMessage] without the microphone
 * permission, and [celebrationText] right after a hit.
 */
@Composable
fun VocabGameStatusBar(
  micState: XrLanguageTeacherGameMicState,
  listeningHint: String,
  micPermissionMessage: String,
  celebrationText: String?,
  heardWords: List<XrLanguageTeacherGameHeardWord>,
  modifier: Modifier = Modifier,
) {
  val isCelebrating = !celebrationText.isNullOrEmpty()
  val isError =
    micState == XrLanguageTeacherGameMicState.PERMISSION_DENIED ||
      micState == XrLanguageTeacherGameMicState.UNAVAILABLE
  val statusMessage =
    if (!celebrationText.isNullOrEmpty()) {
      celebrationText
    } else {
      when (micState) {
        XrLanguageTeacherGameMicState.STOPPED -> listeningHint
        XrLanguageTeacherGameMicState.STARTING ->
          stringResource(R.string.xrteacher_game_starting_mic)
        XrLanguageTeacherGameMicState.LISTENING -> listeningHint
        XrLanguageTeacherGameMicState.PERMISSION_DENIED -> micPermissionMessage
        XrLanguageTeacherGameMicState.UNAVAILABLE ->
          stringResource(R.string.xrteacher_game_asr_unavailable)
      }
    }
  val palette = vocabGamePalette
  val accentColor =
    when {
      isCelebrating -> palette.popGreen
      isError -> palette.urgentRed
      else -> palette.chromeText
    }
  Column(
    modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (chip in heardWords) {
        key(chip.id) {
          Text(
            text = chip.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.chromeText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.widthIn(max = 140.dp)
                .border(BorderStroke(1.dp, palette.chromeDivider), CircleShape)
                .background(palette.chromePill.copy(alpha = 0.92f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
          )
        }
      }
    }
    Row(
      modifier =
        Modifier.vocabGameChromePill(
            palette = palette,
            shape = CircleShape,
            fill = palette.chromePill.copy(alpha = 0.95f),
          )
          .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
          .semantics { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(34.dp).background(accentColor.copy(alpha = 0.18f), CircleShape),
      ) {
        Icon(
          imageVector = if (isError) Icons.Filled.MicOff else Icons.Filled.Mic,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(18.dp),
        )
      }
      AnimatedContent(
        targetState = statusMessage,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "gameStatusMessage",
      ) { message ->
        Text(
          text = message,
          fontSize = 15.sp,
          fontWeight = if (isCelebrating) FontWeight.Bold else FontWeight.Medium,
          color = accentColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * The glow flashed from the top and bottom screen edges when a word is popped or cleared
 * ([VocabGamePalette.successEdgeGlow]), or on a wrong tap or when Buddy bumps into an obstacle
 * ([VocabGamePalette.urgentEdgeGlow]), at up to [maxAlpha] opacity. [color] and [intensity] are
 * read while drawing, so a fading glow only redraws.
 */
@Composable
fun VocabGameEdgeGlow(
  color: () -> Color,
  intensity: () -> Float,
  maxAlpha: Float,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val glowIntensity = intensity()
    if (glowIntensity <= 0.01f) return@Canvas
    val radius = max(size.width, size.height) * 0.55f
    val glow = color().copy(alpha = (maxAlpha * glowIntensity).coerceIn(0f, 1f))
    val transparent = glow.copy(alpha = 0f)
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(glow, transparent),
          center = Offset(size.width / 2f, 0f),
          radius = radius,
        )
    )
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(glow, transparent),
          center = Offset(size.width / 2f, size.height),
          radius = radius,
        )
    )
  }
}

/**
 * Ends a game session with [onEndSession] when the game screen leaves the composition, except when
 * the activity is only being recreated for a configuration change, so a rotation keeps the round
 * going.
 */
@Composable
internal fun VocabGameSessionEndEffect(onEndSession: () -> Unit) {
  val activity = LocalActivity.current
  val latestOnEndSession by rememberUpdatedState(onEndSession)
  DisposableEffect(Unit) {
    onDispose {
      if (activity?.isChangingConfigurations != true) {
        latestOnEndSession()
      }
    }
  }
}

/**
 * The session effects shared by the game screens of [viewModel]: the system Back calls [onExit],
 * the session ends when the screen leaves the composition ([VocabGameSessionEndEffect]), listening
 * follows the microphone permission and the app lifecycle ([VocabGameListeningEffects]), live
 * obstacle collisions and wrong taps trigger [HapticFeedbackType.Reject] vibrotactile feedback, and
 * [onFrame] is called with the frame time on every frame while [isPlaying].
 */
@Composable
internal fun VocabGameSessionEffects(
  viewModel: XrLanguageTeacherVocabGameViewModel,
  isPlaying: Boolean,
  onFrame: (frameNanos: Long) -> Unit,
  onExit: () -> Unit,
) {
  BackHandler(onBack = onExit)
  VocabGameSessionEndEffect(onEndSession = viewModel::endSession)
  if (viewModel.mode == XrLanguageTeacherVocabGameMode.SPEAK) {
    VocabGameListeningEffects(
      onStartListening = viewModel::startListening,
      onStopListening = viewModel::stopListening,
      onMicPermissionDenied = viewModel::onMicPermissionDenied,
    )
  }
  val latestHaptic by rememberUpdatedState(LocalHapticFeedback.current)
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      var lastHandledCollisionCount = viewModel.collisionHapticCount.value
      viewModel.collisionHapticCount.collect { count ->
        if (count > lastHandledCollisionCount) {
          lastHandledCollisionCount = count
          latestHaptic.performHapticFeedback(HapticFeedbackType.Reject)
        }
      }
    }
  }
  val latestOnFrame by rememberUpdatedState(onFrame)
  LaunchedEffect(viewModel, isPlaying) {
    if (!isPlaying) return@LaunchedEffect
    while (true) {
      withFrameNanos { frameNanos -> latestOnFrame(frameNanos) }
    }
  }
}

/**
 * The end-of-round screen of a game: the [headline], the final [score] on a wavy cookie badge, an
 * optional breakdown pill when [correctCount] and [uniqueWordCount] are provided, a "New high
 * score!" badge or the [bestScore], and buttons to play again or exit.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VocabGameResults(
  headline: String,
  score: Int,
  bestScore: Int,
  isNewHighScore: Boolean,
  primaryButtonTitle: String,
  onPlayAgain: () -> Unit,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
  correctCount: Int? = null,
  uniqueWordCount: Int? = null,
) {
  val palette = vocabGamePalette
  Box(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize().background(palette.screenBackground)) {
      Box(modifier = Modifier.fillMaxSize().alpha(palette.resultsBackgroundShapesAlpha)) {
        StudyBackgroundShapes()
      }
    }
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        FilledTonalIconButton(
          onClick = onExit,
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = palette.chromePill,
              contentColor = palette.chromeText,
            ),
          shapes = IconButtonDefaults.shapes(),
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.xrteacher_game_exit),
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = headline,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        color = palette.chromePrimaryValueText,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
      )
      val finalScoreDescription = stringResource(R.string.xrteacher_game_final_score, score)
      val scoreBadgeShape = MaterialShapes.Cookie9Sided.toShape()
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.size(240.dp)
            .vocabGameChromePill(
              palette = palette,
              shape = scoreBadgeShape,
              fill = palette.resultsScoreBadgeFill,
              stroke = palette.resultsScoreBadgeStroke,
            )
            .clearAndSetSemantics { contentDescription = finalScoreDescription },
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = stringResource(R.string.xrteacher_game_your_score_label),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = palette.chromeTextMuted,
          )
          Text(
            text = score.toString(),
            fontSize = 72.sp,
            lineHeight = 80.sp,
            fontWeight = FontWeight.Bold,
            color = palette.chromePrimaryValueText,
          )
        }
      }
      if (correctCount != null && uniqueWordCount != null) {
        val correctText =
          stringResource(R.string.xrteacher_game_results_correct_count, correctCount)
        val uniqueText =
          pluralStringResource(
            R.plurals.xrteacher_game_results_unique_words,
            uniqueWordCount,
            uniqueWordCount,
          )
        Text(
          text = "$correctText • $uniqueText",
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          color = palette.chromeText,
          modifier =
            Modifier.padding(top = 12.dp)
              .vocabGameChromePill(palette = palette, shape = CircleShape)
              .padding(horizontal = 16.dp, vertical = 7.dp),
        )
      }
      if (isNewHighScore) {
        Row(
          modifier =
            Modifier.padding(top = 16.dp)
              .background(palette.newHighScoreBadgeFill, CircleShape)
              .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = palette.scoreGold,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = stringResource(R.string.xrteacher_game_new_high_score),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = palette.newHighScoreBadgeText,
          )
        }
      } else {
        Row(
          modifier = Modifier.padding(top = 18.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = stringResource(R.string.xrteacher_game_best_label),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = palette.chromeTextMuted,
          )
          Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = palette.scoreGold,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = bestScore.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = palette.scoreGold,
          )
        }
      }
      Spacer(modifier = Modifier.weight(1f))
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(
          onClick = onPlayAgain,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = palette.primaryCtaFill,
              contentColor = palette.primaryCtaText,
            ),
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
          Text(text = primaryButtonTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onExit,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.secondaryCtaText),
          border = BorderStroke(1.dp, palette.secondaryCtaStroke),
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
          Text(
            text = stringResource(R.string.xrteacher_game_exit),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

/**
 * Starts a game's speech recognition once the microphone permission is granted, requesting it first
 * if it was never asked, and reports a denied permission (the shared permission state shows the
 * permission denied dialog). Stops listening while the app is in the background and listens again
 * when it comes back, or once the permission is granted in Settings.
 */
@Composable
internal fun VocabGameListeningEffects(
  onStartListening: () -> Unit,
  onStopListening: () -> Unit,
  onMicPermissionDenied: () -> Unit,
) {
  val micAccess = rememberPermissionAccessState(PermissionAccessKind.MICROPHONE)
  val isGranted = micAccess.isGranted
  val latestIsGranted by rememberUpdatedState(isGranted)
  val latestOnStartListening by rememberUpdatedState(onStartListening)
  val latestOnStopListening by rememberUpdatedState(onStopListening)
  val latestOnMicPermissionDenied by rememberUpdatedState(onMicPermissionDenied)
  // Asks for the permission, or shows the denied dialog, when it isn't granted.
  LaunchedEffect(Unit) { if (!latestIsGranted) micAccess.requestOrRun(onGranted = {}) }
  LaunchedEffect(isGranted) { if (isGranted) latestOnStartListening() }
  LaunchedEffect(micAccess.isDenied) { if (micAccess.isDenied) latestOnMicPermissionDenied() }
  var wasBackgrounded by remember { mutableStateOf(false) }
  OnAppBackgroundedEffect {
    wasBackgrounded = true
    latestOnStopListening()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_START) {
    if (wasBackgrounded) {
      wasBackgrounded = false
      // A permission granted in Settings meanwhile starts listening through `isGranted` instead.
      if (latestIsGranted) latestOnStartListening()
    }
  }
}
