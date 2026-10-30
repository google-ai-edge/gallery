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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperParticle
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPopBanner
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperTarget
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherGameHeardWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperArenaState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperViewModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The size of a flying sticker. */
private val STICKER_SIZE = 118.dp

/** The text of a word pill shown for a flying word without a sticker. */
private val WordPillTextStyle =
  TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

/** The text of a "+1 word" banner. */
private val PopBannerTextStyle =
  TextStyle(color = VocabGameColors.popGreen, fontSize = 20.sp, fontWeight = FontWeight.Black)

/**
 * The full-screen `Vocab Popper` game of [viewModel]: stickers of saved words fly up, and the
 * player pops them by saying their words. Shows the results once the time is up.
 *
 * Starts listening once the microphone permission is granted, steps the game on every frame, and
 * ends the session when it leaves the composition (but not on a configuration change, which keeps
 * the round going). [onExit] is called by the close and Exit buttons and the system Back. Only the
 * arena redraws on every frame; the chrome recomposes when the score, timer, or microphone change.
 */
@Composable
fun VocabPopperScreen(
  viewModel: XrLanguageTeacherVocabPopperViewModel,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val arenaState = viewModel.arenaState.collectAsStateWithLifecycle()
  val heardWords by viewModel.heardWords.collectAsStateWithLifecycle()
  val isPlaying = uiState.phase == XrLanguageTeacherVocabPopperPhase.PLAYING

  VocabGameSessionEffects(
    viewModel = viewModel,
    isPlaying = isPlaying,
    onFrame = viewModel::stepFrame,
    onExit = onExit,
  )

  VocabPopperContent(
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
 * The stateless `Vocab Popper` game screen: the arena and game chrome, or the results. The arena
 * reads [arenaState] while drawing, so a new frame only redraws it.
 */
@Composable
fun VocabPopperContent(
  uiState: XrLanguageTeacherVocabPopperUiState,
  arenaState: () -> XrLanguageTeacherVocabPopperArenaState,
  heardWords: List<XrLanguageTeacherGameHeardWord>,
  onToggleMute: () -> Unit,
  onPlayAgain: () -> Unit,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (uiState.phase == XrLanguageTeacherVocabPopperPhase.FINISHED) {
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
    VocabPopperArena(arenaState = arenaState, modifier = Modifier.fillMaxSize())
    VocabGameEdgeGlow(
      color = { VocabGameColors.popGreen },
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
        listeningHint = stringResource(R.string.xrteacher_vocab_popper_listening_hint),
        micPermissionMessage =
          stringResource(R.string.xrteacher_vocab_popper_mic_permission_needed),
        celebrationText =
          uiState.celebrationWord?.let {
            stringResource(R.string.xrteacher_vocab_popper_popped, it)
          },
        heardWords = heardWords,
      )
    }
  }
}

/**
 * The arena of flying stickers, burst particles, and "+1" banners, drawn in one canvas so a frame
 * only redraws the arena.
 */
@Composable
private fun VocabPopperArena(
  arenaState: () -> XrLanguageTeacherVocabPopperArenaState,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val resources = LocalResources.current
  val density = LocalDensity.current
  val pillStroke = remember(density) { Stroke(width = with(density) { 3.dp.toPx() }) }
  // Each banner's text is formatted once, not on every frame.
  val bannerTexts = remember(resources) { HashMap<Long, String>() }
  Canvas(modifier = modifier.clearAndSetSemantics {}) {
    val arena = arenaState()
    for (particle in arena.game.particles) {
      drawParticle(particle)
    }
    for (target in arena.game.targets) {
      drawTarget(target, arena.stickers[target.item.entryKey], textMeasurer, pillStroke)
    }
    if (arena.game.popBanners.isEmpty()) bannerTexts.clear()
    for (banner in arena.game.popBanners) {
      val text =
        bannerTexts.getOrPut(banner.id) {
          resources.getString(R.string.xrteacher_vocab_popper_pop_banner, 1, banner.word)
        }
      drawPopBanner(banner = banner, text = text, textMeasurer = textMeasurer)
    }
  }
}

private fun DrawScope.drawParticle(particle: XrLanguageTeacherVocabPopperParticle) {
  val alpha = (1f - particle.lifeProgress).coerceIn(0f, 1f)
  val radius = (particle.radiusDp * (1f - particle.lifeProgress * 0.4f)).dp.toPx()
  drawCircle(
    color = paletteColor(particle.colorIndex),
    radius = radius,
    center = Offset(particle.x * size.width, particle.y * size.height),
    alpha = alpha,
  )
}

private fun DrawScope.drawTarget(
  target: XrLanguageTeacherVocabPopperTarget,
  sticker: ImageBitmap?,
  textMeasurer: TextMeasurer,
  pillStroke: Stroke,
) {
  val popProgress = target.popProgress ?: 0f
  val scale = if (target.isPopping) 1f + popProgress * 0.45f else 1f
  val alpha = if (target.isPopping) max(0f, 1f - popProgress) else 1f
  val center = Offset(target.x * size.width, target.y * size.height)
  translate(left = center.x, top = center.y) {
    rotate(degrees = target.rotationDegrees, pivot = Offset.Zero) {
      scale(scale = scale, pivot = Offset.Zero) {
        if (sticker != null) {
          drawStickerSprite(sticker, STICKER_SIZE.toPx(), alpha)
        } else {
          drawWordPill(
            text = target.item.displayWord,
            fill = paletteColor(target.colorIndex),
            textMeasurer = textMeasurer,
            stroke = pillStroke,
            alpha = alpha,
          )
        }
      }
    }
  }
}

/** Draws [sticker] fitted into a [boxSize] square centered on the origin. */
internal fun DrawScope.drawStickerSprite(sticker: ImageBitmap, boxSize: Float, alpha: Float = 1f) {
  val fit = min(boxSize / sticker.width, boxSize / sticker.height)
  val width = (sticker.width * fit).roundToInt()
  val height = (sticker.height * fit).roundToInt()
  drawImage(
    image = sticker,
    dstOffset = IntOffset(-width / 2, -height / 2),
    dstSize = IntSize(width, height),
    alpha = alpha,
  )
}

/** Draws [text] on a white-bordered rounded pill filled with [fill], centered on the origin. */
private fun DrawScope.drawWordPill(
  text: String,
  fill: Color,
  textMeasurer: TextMeasurer,
  stroke: Stroke,
  alpha: Float,
) {
  val layout =
    textMeasurer.measure(
      text = text,
      style = WordPillTextStyle,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      constraints = Constraints(maxWidth = (size.width * 0.6f).toInt()),
    )
  val pillSize = Size(layout.size.width + 36.dp.toPx(), layout.size.height + 24.dp.toPx())
  val topLeft = Offset(-pillSize.width / 2f, -pillSize.height / 2f)
  val cornerRadius = CornerRadius(20.dp.toPx())
  drawRoundRect(
    color = fill,
    topLeft = topLeft,
    size = pillSize,
    cornerRadius = cornerRadius,
    alpha = alpha,
  )
  drawRoundRect(
    color = Color.White,
    topLeft = topLeft,
    size = pillSize,
    cornerRadius = cornerRadius,
    style = stroke,
    alpha = alpha,
  )
  drawText(
    textLayoutResult = layout,
    topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f),
    alpha = alpha,
  )
}

private fun DrawScope.drawPopBanner(
  banner: XrLanguageTeacherVocabPopperPopBanner,
  text: String,
  textMeasurer: TextMeasurer,
) {
  val alpha = (1f - banner.progress).coerceIn(0f, 1f)
  val scale = 0.9f + min(banner.progress * 2f, 0.25f)
  val layout = textMeasurer.measure(text = text, style = PopBannerTextStyle, maxLines = 1)
  val pillSize = Size(layout.size.width + 28.dp.toPx(), layout.size.height + 12.dp.toPx())
  translate(left = banner.x * size.width, top = banner.y * size.height) {
    scale(scale = scale, pivot = Offset.Zero) {
      drawRoundRect(
        color = Color.Black.copy(alpha = 0.72f),
        topLeft = Offset(-pillSize.width / 2f, -pillSize.height / 2f),
        size = pillSize,
        cornerRadius = CornerRadius(pillSize.height / 2f),
        alpha = alpha,
      )
      drawText(
        textLayoutResult = layout,
        topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f),
        alpha = alpha,
      )
    }
  }
}

private fun paletteColor(colorIndex: Int): Color =
  VocabGameColors.palette[Math.floorMod(colorIndex, VocabGameColors.palette.size)]
