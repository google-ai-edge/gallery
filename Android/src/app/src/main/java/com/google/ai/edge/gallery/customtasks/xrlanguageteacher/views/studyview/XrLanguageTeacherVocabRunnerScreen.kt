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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerBuddyPose
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerObstacle
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.buddyPose
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherGameHeardWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabRunnerArenaState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabRunnerUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabRunnerViewModel

/** The track's height in the arena, as a fraction of the arena height. */
private const val TRACK_Y_FRACTION = 0.56f

/** How much a cleared obstacle grows as it pops, or a broken one shrinks, over its fade out. */
private const val OBSTACLE_CLEAR_SCALE_CHANGE = 0.35f

/** The smallest scale a broken obstacle shrinks to while it fades out. */
private const val OBSTACLE_BREAK_MIN_SCALE = 0.2f

/** How far a broken obstacle drops while it fades out. */
private val OBSTACLE_BREAK_DROP = 14.dp

private val OBSTACLE_SIZE = 104.dp
private val BUDDY_BODY_SIZE = 68.dp
private val TRACK_DASH_SPACING = 46.dp
private val TRACK_DASH_WIDTH = 18.dp

/**
 * The full-screen `Vocab Runner` game of [viewModel]: Buddy runs along a track of word stickers,
 * and each one pops out of the way when the player says its word. Shows the results once the
 * 60-second round is over.
 *
 * Starts listening once the microphone permission is granted, steps the game on every frame, and
 * ends the session when it leaves the composition (but not on a configuration change, which keeps
 * the round going). [onExit] is called by the close and Exit buttons and the system Back. Only the
 * arena redraws on every frame; the chrome recomposes when the timer, score, or microphone change.
 */
@Composable
fun VocabRunnerScreen(
  viewModel: XrLanguageTeacherVocabRunnerViewModel,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val arenaState = viewModel.arenaState.collectAsStateWithLifecycle()
  val heardWords by viewModel.heardWords.collectAsStateWithLifecycle()
  val isPlaying = uiState.phase == XrLanguageTeacherVocabRunnerPhase.PLAYING

  VocabGameSessionEffects(
    viewModel = viewModel,
    isPlaying = isPlaying,
    onFrame = viewModel::stepFrame,
    onExit = onExit,
  )

  VocabRunnerContent(
    uiState = uiState,
    arenaState = { arenaState.value },
    heardWords = heardWords,
    onToggleMute = viewModel::toggleMute,
    onPlayAgain = viewModel::playAgain,
    onExit = onExit,
    modifier = modifier,
  )
}

/**
 * The stateless `Vocab Runner` game screen: the arena and game chrome, or the results. The arena
 * reads [arenaState] while drawing, so a new frame only redraws it.
 */
@Composable
fun VocabRunnerContent(
  uiState: XrLanguageTeacherVocabRunnerUiState,
  arenaState: () -> XrLanguageTeacherVocabRunnerArenaState,
  heardWords: List<XrLanguageTeacherGameHeardWord>,
  onToggleMute: () -> Unit,
  onPlayAgain: () -> Unit,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (uiState.phase == XrLanguageTeacherVocabRunnerPhase.FINISHED) {
    VocabGameResults(
      headline = stringResource(R.string.xrteacher_game_times_up),
      score = uiState.score,
      bestScore = uiState.highScore,
      isNewHighScore = uiState.isNewHighScore,
      primaryButtonTitle = stringResource(R.string.xrteacher_game_play_again),
      onPlayAgain = onPlayAgain,
      onExit = onExit,
      modifier = modifier,
    )
    return
  }
  Box(modifier = modifier.fillMaxSize()) {
    VocabGameBackground()
    VocabRunnerArena(arenaState = arenaState, modifier = Modifier.fillMaxSize())
    VocabGameEdgeGlow(
      color = {
        if (arenaState().isCollisionGlow) VocabGameColors.urgentRed else VocabGameColors.popGreen
      },
      intensity = { arenaState().edgeGlowIntensity },
    )
    Column(
      modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      VocabGameTopBar(isMuted = uiState.isMuted, onToggleMute = onToggleMute, onExit = onExit) {
        VocabGameTimer(remainingSeconds = uiState.remainingSeconds)
      }
      VocabGameScoreboard(score = uiState.score, bestScore = uiState.highScore)
      Spacer(modifier = Modifier.weight(1f))
      VocabGameStatusBar(
        micState = uiState.micState,
        listeningHint = stringResource(R.string.xrteacher_vocab_runner_listening_hint),
        micPermissionMessage =
          stringResource(R.string.xrteacher_vocab_runner_mic_permission_needed),
        celebrationText =
          uiState.celebrationWord?.let {
            stringResource(
              if (uiState.isCelebrationMissedWord) {
                R.string.xrteacher_vocab_runner_missed_hint
              } else {
                R.string.xrteacher_vocab_runner_passed
              },
              it,
            )
          },
        heardWords = heardWords,
      )
    }
  }
}

/** The track, the scrolling sticker obstacles, and Buddy, drawn in one canvas. */
@Composable
private fun VocabRunnerArena(
  arenaState: () -> XrLanguageTeacherVocabRunnerArenaState,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val drawTools = remember(density) { RunnerDrawTools(density) }
  Canvas(modifier = modifier.clearAndSetSemantics {}) {
    val arena = arenaState()
    val trackY = size.height * TRACK_Y_FRACTION
    drawTrack(trackY = trackY, trackDistance = arena.game.trackDistance)
    for (obstacle in arena.game.obstacles) {
      drawObstacle(
        obstacle = obstacle,
        sticker = arena.stickers[obstacle.item.entryKey],
        trackY = trackY,
        textMeasurer = textMeasurer,
        tools = drawTools,
      )
    }
    drawBuddy(game = arena.game, pose = arena.game.buddyPose(), trackY = trackY, tools = drawTools)
  }
}

/** The strokes and path the Runner arena reuses on every frame instead of allocating them. */
private class RunnerDrawTools(density: Density) {
  val obstacleBorder = Stroke(width = with(density) { 3.dp.toPx() })
  val buddyOutline = Stroke(width = with(density) { 2.5.dp.toPx() })
  val smile = Stroke(width = with(density) { 3.dp.toPx() }, cap = StrokeCap.Round)
  val smilePath = Path()
}

/** The meaning shown on an obstacle tile without a sticker. */
private val ObstacleTextStyle =
  TextStyle(
    color = Color.White,
    fontSize = 15.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
  )

private fun DrawScope.drawTrack(trackY: Float, trackDistance: Float) {
  drawLine(
    color = Color.White.copy(alpha = 0.28f),
    start = Offset(0f, trackY),
    end = Offset(size.width, trackY),
    strokeWidth = 3.dp.toPx(),
  )
  val dashSpacing = TRACK_DASH_SPACING.toPx()
  val dashSize = Size(TRACK_DASH_WIDTH.toPx(), 3.dp.toPx())
  val dashY = trackY + 10.dp.toPx()
  var x = -((trackDistance * size.width) % dashSpacing)
  while (x < size.width + dashSpacing) {
    drawRoundRect(
      color = Color.White.copy(alpha = 0.16f),
      topLeft = Offset(x, dashY),
      size = dashSize,
      cornerRadius = CornerRadius(1.5.dp.toPx()),
    )
    x += dashSpacing
  }
}

private fun DrawScope.drawObstacle(
  obstacle: XrLanguageTeacherVocabRunnerObstacle,
  sticker: ImageBitmap?,
  trackY: Float,
  textMeasurer: TextMeasurer,
  tools: RunnerDrawTools,
) {
  val obstacleSize = OBSTACLE_SIZE.toPx()
  // A cleared obstacle pops (grows) and fades out; a broken one shrinks, drops, and fades out.
  val progress = obstacle.clearProgress ?: 0f
  val alpha = if (obstacle.isClearing) (1f - progress).coerceAtLeast(0f) else 1f
  val scale =
    when {
      !obstacle.isClearing -> 1f
      obstacle.didBreakOnMaxBumps ->
        (1f - progress * OBSTACLE_CLEAR_SCALE_CHANGE).coerceAtLeast(OBSTACLE_BREAK_MIN_SCALE)
      else -> 1f + progress * OBSTACLE_CLEAR_SCALE_CHANGE
    }
  val drop = if (obstacle.didBreakOnMaxBumps) progress * OBSTACLE_BREAK_DROP.toPx() else 0f
  translate(left = obstacle.x * size.width, top = trackY - obstacleSize * 0.48f + drop) {
    scale(scale = scale, pivot = Offset.Zero) {
      drawObstacleContent(obstacle, sticker, obstacleSize, alpha, textMeasurer, tools)
    }
  }
}

/** Draws [obstacle]'s sticker, or its meaning on a tile, centered on the origin. */
private fun DrawScope.drawObstacleContent(
  obstacle: XrLanguageTeacherVocabRunnerObstacle,
  sticker: ImageBitmap?,
  obstacleSize: Float,
  alpha: Float,
  textMeasurer: TextMeasurer,
  tools: RunnerDrawTools,
) {
  if (sticker != null) {
    drawStickerSprite(sticker, obstacleSize, alpha)
    return
  }
  val borderColor =
    when {
      obstacle.didBreakOnMaxBumps || obstacle.bumpCount > 0 -> VocabGameColors.urgentRed
      obstacle.isClearing -> VocabGameColors.popGreen
      else -> Color.White
    }
  val topLeft = Offset(-obstacleSize / 2f, -obstacleSize / 2f)
  val tileSize = Size(obstacleSize, obstacleSize)
  val cornerRadius = CornerRadius(20.dp.toPx())
  drawRoundRect(
    VocabGameColors.obstacleFallbackFill,
    topLeft,
    tileSize,
    cornerRadius,
    alpha = alpha,
  )
  drawRoundRect(
    borderColor,
    topLeft,
    tileSize,
    cornerRadius,
    style = tools.obstacleBorder,
    alpha = alpha,
  )
  val layout =
    textMeasurer.measure(
      text = obstacle.item.meaning,
      style = ObstacleTextStyle,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      constraints = Constraints.fixedWidth((obstacleSize - 16.dp.toPx()).toInt()),
    )
  drawText(
    textLayoutResult = layout,
    topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f),
    alpha = alpha,
  )
}

/** Draws Buddy, the round blue mascot, running or stumbling (and recoiling) on the track. */
private fun DrawScope.drawBuddy(
  game: XrLanguageTeacherVocabRunnerState,
  pose: XrLanguageTeacherVocabRunnerBuddyPose,
  trackY: Float,
  tools: RunnerDrawTools,
) {
  val bodySize = BUDDY_BODY_SIZE.toPx()
  val center =
    Offset(
      x = (XrLanguageTeacherVocabRunnerEngine.BUDDY_X + game.buddyRecoilOffset) * size.width,
      y = trackY - bodySize * 0.52f - pose.bobOffsetDp.dp.toPx(),
    )
  translate(left = center.x, top = center.y) {
    rotate(degrees = pose.tiltDegrees, pivot = Offset.Zero) {
      // Running feet below Buddy's body.
      val footSize = Size(16.dp.toPx(), 10.dp.toPx())
      val footY = bodySize * 0.44f - footSize.height / 2f
      for ((baseX, swing) in listOf(-12f to pose.leftFootOffsetDp, 12f to pose.rightFootOffsetDp)) {
        drawRoundRect(
          color = VocabGameColors.buddyFeet,
          topLeft = Offset((baseX + swing).dp.toPx() - footSize.width / 2f, footY),
          size = footSize,
          cornerRadius = CornerRadius(footSize.height / 2f),
        )
      }
      // Buddy's rounded body with a soft shadow.
      val bodyTopLeft = Offset(-bodySize / 2f, -bodySize / 2f)
      val bodyRectSize = Size(bodySize, bodySize)
      val bodyCorner = CornerRadius(24.dp.toPx())
      drawRoundRect(
        color = Color.Black.copy(alpha = 0.32f),
        topLeft = bodyTopLeft + Offset(0f, 4.dp.toPx()),
        size = bodyRectSize,
        cornerRadius = bodyCorner,
      )
      drawRoundRect(
        brush =
          Brush.linearGradient(
            colors =
              if (game.isStumbled) VocabGameColors.buddyStumbledBody else VocabGameColors.buddyBody,
            start = bodyTopLeft,
            end = bodyTopLeft + Offset(bodySize, bodySize),
          ),
        topLeft = bodyTopLeft,
        size = bodyRectSize,
        cornerRadius = bodyCorner,
      )
      drawRoundRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = bodyTopLeft,
        size = bodyRectSize,
        cornerRadius = bodyCorner,
        style = tools.buddyOutline,
      )
      drawBuddyFace(
        isStumbled = game.isStumbled,
        showsOpenMouth = pose.showsOpenMouth,
        tools = tools,
      )
    }
  }
}

/** Draws Buddy's eyes and mouth, centered slightly up and to the right of the origin. */
private fun DrawScope.drawBuddyFace(
  isStumbled: Boolean,
  showsOpenMouth: Boolean,
  tools: RunnerDrawTools,
) {
  val eyeSize = 10.dp.toPx()
  val mouthHeight = if (showsOpenMouth) 10.dp.toPx() else 8.dp.toPx()
  val faceHeight = eyeSize + 6.dp.toPx() + mouthHeight
  val faceCenter = Offset(4.dp.toPx(), -2.dp.toPx())
  val eyeY = faceCenter.y - faceHeight / 2f + eyeSize / 2f
  for (eyeX in listOf(faceCenter.x - 12.dp.toPx(), faceCenter.x + 12.dp.toPx())) {
    if (isStumbled) {
      // A small "x" for each dazed eye.
      val arm = 3.5.dp.toPx()
      val stroke = 2.5.dp.toPx()
      drawLine(
        VocabGameColors.buddyFace,
        Offset(eyeX - arm, eyeY - arm),
        Offset(eyeX + arm, eyeY + arm),
        stroke,
        StrokeCap.Round,
      )
      drawLine(
        VocabGameColors.buddyFace,
        Offset(eyeX - arm, eyeY + arm),
        Offset(eyeX + arm, eyeY - arm),
        stroke,
        StrokeCap.Round,
      )
    } else {
      drawCircle(VocabGameColors.buddyFace, radius = eyeSize / 2f, center = Offset(eyeX, eyeY))
      drawCircle(
        Color.White,
        radius = 1.75.dp.toPx(),
        center = Offset(eyeX + eyeSize / 2f - 3.25.dp.toPx(), eyeY - eyeSize / 2f + 3.25.dp.toPx()),
      )
    }
  }
  val mouthCenterY = faceCenter.y + faceHeight / 2f - mouthHeight / 2f
  if (showsOpenMouth) {
    drawCircle(
      VocabGameColors.buddyFace,
      radius = 5.dp.toPx(),
      center = Offset(faceCenter.x, mouthCenterY),
    )
  } else {
    val smileWidth = 18.dp.toPx()
    val top = mouthCenterY - mouthHeight / 2f
    val smile =
      tools.smilePath.apply {
        reset()
        moveTo(faceCenter.x - smileWidth / 2f, top)
        quadraticTo(faceCenter.x, top + mouthHeight, faceCenter.x + smileWidth / 2f, top)
      }
    drawPath(path = smile, color = VocabGameColors.buddyFace, style = tools.smile)
  }
}
