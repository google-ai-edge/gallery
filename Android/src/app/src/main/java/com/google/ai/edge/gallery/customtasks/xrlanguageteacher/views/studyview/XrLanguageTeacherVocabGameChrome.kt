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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherGameMicState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherGameHeardWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabGameViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.StudyBackgroundShapes
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import com.google.ai.edge.gallery.ui.common.PermissionAccessKind
import com.google.ai.edge.gallery.ui.common.rememberPermissionAccessState
import com.google.ai.edge.gallery.ui.theme.onPrimaryDark
import com.google.ai.edge.gallery.ui.theme.primaryDark
import com.google.ai.edge.gallery.ui.theme.primaryLight
import java.util.Locale
import kotlin.math.max

/**
 * The colors of the `Vocab Popper` and `Vocab Runner` game arenas. The arena is intentionally
 * always dark, mirroring iOS: it uses this fixed arcade palette in light and dark mode alike and
 * doesn't follow dynamic color, so the stickers and glows pop. Colors that match an app theme token
 * use it.
 */
internal object VocabGameColors {
  val screenBackground: Color = Color(0xFF111318)
  val chromePill: Color = Color(0xFF33353A)
  val chromeText: Color = Color(0xFFE2E2E9)
  val chromeTextMuted: Color = Color(0xFFC4C6D0)
  val scoreGold: Color = Color(0xFFF5B841)
  val popGreen: Color = Color(0xFF5CE196)
  val urgentRed: Color = Color(0xFFFF6B6B)
  /** The fill of the wavy badge behind the final score on the results screen. */
  val resultsScoreBadgeFill: Color = Color(0xFF3A3D45)
  val newHighScoreBadgeFill: Color = Color(0xFF3F2E00)
  val newHighScoreBadgeText: Color = Color(0xFFFFD97D)
  val primaryCtaFill: Color = primaryDark
  val primaryCtaText: Color = onPrimaryDark
  val secondaryCtaStroke: Color = Color(0xFF8E9099)

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
  /** The tile drawn for an obstacle whose sticker isn't ready. */
  val obstacleFallbackFill: Color = Color(0xFF2B2E36)
}

/** Seconds left at which the countdown turns red. */
private const val URGENT_SECONDS = 10

/** The dark background and soft background shapes behind a game screen. */
@Composable
fun VocabGameBackground(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize().background(VocabGameColors.screenBackground)) {
    Box(modifier = Modifier.fillMaxSize().alpha(0.62f)) { StudyBackgroundShapes() }
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
  val buttonColors =
    IconButtonDefaults.filledTonalIconButtonColors(
      containerColor = VocabGameColors.chromePill,
      contentColor = VocabGameColors.chromeText,
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

/** The "Score | Best" pill under the top bar of a game screen. */
@Composable
fun VocabGameScoreboard(score: Int, bestScore: Int, modifier: Modifier = Modifier) {
  val scoreContentDescription = stringResource(R.string.xrteacher_vocab_game_score, score)
  val bestContentDescription = stringResource(R.string.xrteacher_vocab_game_best, bestScore)
  Row(
    modifier =
      modifier
        .background(VocabGameColors.chromePill.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.width(92.dp).clearAndSetSemantics { contentDescription = scoreContentDescription },
    ) {
      Text(
        text = stringResource(R.string.xrteacher_game_score_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = VocabGameColors.chromeTextMuted,
      )
      Text(
        text = score.toString(),
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
      )
    }
    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.14f)))
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.width(92.dp).clearAndSetSemantics { contentDescription = bestContentDescription },
    ) {
      Text(
        text = stringResource(R.string.xrteacher_game_best_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = VocabGameColors.chromeTextMuted,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.EmojiEvents,
          contentDescription = null,
          tint = VocabGameColors.scoreGold,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = bestScore.toString(),
          fontSize = 24.sp,
          lineHeight = 32.sp,
          fontWeight = FontWeight.Bold,
          color = VocabGameColors.scoreGold,
        )
      }
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
  Text(
    text = formattedTime,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    color = if (isUrgent) VocabGameColors.urgentRed else VocabGameColors.chromeText,
    modifier =
      modifier
        .background(VocabGameColors.chromePill, CircleShape)
        .padding(horizontal = 20.dp, vertical = 10.dp)
        .clearAndSetSemantics { contentDescription = description },
  )
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
        XrLanguageTeacherGameMicState.STARTING ->
          stringResource(R.string.xrteacher_game_starting_mic)
        XrLanguageTeacherGameMicState.LISTENING -> listeningHint
        XrLanguageTeacherGameMicState.PERMISSION_DENIED -> micPermissionMessage
        XrLanguageTeacherGameMicState.UNAVAILABLE ->
          stringResource(R.string.xrteacher_game_asr_unavailable)
      }
    }
  val accentColor =
    when {
      isCelebrating -> VocabGameColors.popGreen
      isError -> VocabGameColors.urgentRed
      else -> VocabGameColors.chromeText
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
            color = VocabGameColors.chromeText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.widthIn(max = 140.dp)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), CircleShape)
                .background(VocabGameColors.chromePill.copy(alpha = 0.92f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
          )
        }
      }
    }
    Row(
      modifier =
        Modifier.background(VocabGameColors.chromePill.copy(alpha = 0.95f), CircleShape)
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
 * ([VocabGameColors.popGreen]), or when Buddy bumps into an obstacle ([VocabGameColors.urgentRed]).
 * [color] and [intensity] are read while drawing, so a fading glow only redraws.
 */
@Composable
fun VocabGameEdgeGlow(color: () -> Color, intensity: () -> Float, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val glowIntensity = intensity()
    if (glowIntensity <= 0.01f) return@Canvas
    val radius = max(size.width, size.height) * 0.55f
    val glow = color().copy(alpha = (0.55f * glowIntensity).coerceIn(0f, 1f))
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
 * follows the microphone permission and the app lifecycle ([VocabGameListeningEffects]), and
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
  VocabGameListeningEffects(
    onStartListening = viewModel::startListening,
    onStopListening = viewModel::stopListening,
    onMicPermissionDenied = viewModel::onMicPermissionDenied,
  )
  val latestOnFrame by rememberUpdatedState(onFrame)
  LaunchedEffect(viewModel, isPlaying) {
    if (!isPlaying) return@LaunchedEffect
    while (true) {
      withFrameNanos { frameNanos -> latestOnFrame(frameNanos) }
    }
  }
}

/**
 * The end-of-round screen of a game: the [headline], the final [score] on a wavy cookie badge, a
 * "New high score!" badge or the [bestScore], and buttons to play again or exit.
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
) {
  Box(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize().background(VocabGameColors.screenBackground)) {
      Box(modifier = Modifier.fillMaxSize().alpha(0.6f)) { StudyBackgroundShapes() }
    }
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        FilledTonalIconButton(
          onClick = onExit,
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = VocabGameColors.chromePill,
              contentColor = VocabGameColors.chromeText,
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
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
      )
      val finalScoreDescription = stringResource(R.string.xrteacher_game_final_score, score)
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.size(240.dp)
            .background(
              VocabGameColors.resultsScoreBadgeFill,
              MaterialShapes.Cookie9Sided.toShape(),
            )
            .clearAndSetSemantics { contentDescription = finalScoreDescription },
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = stringResource(R.string.xrteacher_game_your_score_label),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = VocabGameColors.chromeTextMuted,
          )
          Text(
            text = score.toString(),
            fontSize = 72.sp,
            lineHeight = 80.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
        }
      }
      if (isNewHighScore) {
        Row(
          modifier =
            Modifier.padding(top = 16.dp)
              .background(VocabGameColors.newHighScoreBadgeFill, CircleShape)
              .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = VocabGameColors.scoreGold,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = stringResource(R.string.xrteacher_game_new_high_score),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VocabGameColors.newHighScoreBadgeText,
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
            color = VocabGameColors.chromeTextMuted,
          )
          Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = VocabGameColors.scoreGold,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = bestScore.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = VocabGameColors.scoreGold,
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
              containerColor = VocabGameColors.primaryCtaFill,
              contentColor = VocabGameColors.primaryCtaText,
            ),
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
          Text(text = primaryButtonTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onExit,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = VocabGameColors.chromeText),
          border = BorderStroke(1.dp, VocabGameColors.secondaryCtaStroke),
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
