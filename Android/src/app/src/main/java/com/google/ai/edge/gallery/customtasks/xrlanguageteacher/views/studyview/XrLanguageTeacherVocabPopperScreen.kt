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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
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
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameEndReason
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperParticle
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPhase
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperPopBanner
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperTarget
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherGameHeardWord
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperArenaState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperViewModel
import com.google.ai.edge.gallery.ui.theme.VocabGamePalette
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The size of a flying sticker. */
private val STICKER_SIZE = 118.dp

/** The text of a word pill shown for a flying word without a sticker. */
private val WordPillTextStyle =
  TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

/** Returns the text style of a "+1 word" banner in [palette]. */
private fun popBannerTextStyle(palette: VocabGamePalette): TextStyle =
  TextStyle(color = palette.popGreen, fontSize = 20.sp, fontWeight = FontWeight.Black)

/** The soft drop shadow lifting word pills and framed photo stickers off the light surface. */
private val LightModeLiftShadow = Color.Black.copy(alpha = 0.14f)

/**
 * The full-screen `Vocab Popper` game of [viewModel]: stickers of saved words fly up, and the
 * player pops them by tapping the prompted target word or saying their words. Shows the results
 * once the time is up or all lives are lost.
 *
 * Starts listening once the microphone permission is granted in Speak mode, steps the game on every
 * frame, and ends the session when it leaves the composition (but not on a configuration change,
 * which keeps the round going). [onExit] is called by the close and Exit buttons and the system
 * Back. Only the arena redraws on every frame; the chrome recomposes when the score, timer, lives,
 * prompt, or microphone change.
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
    onArenaTap = viewModel::handleArenaTap,
    onReplayPromptAudio = viewModel::replayPromptAudio,
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
  onArenaTap: (Float, Float, Float) -> Unit = { _, _, _ -> },
  onReplayPromptAudio: () -> Unit = {},
) {
  if (uiState.phase == XrLanguageTeacherVocabPopperPhase.FINISHED) {
    val headlineRes =
      if (uiState.endReason == XrLanguageTeacherVocabGameEndReason.OUT_OF_LIVES) {
        R.string.xrteacher_game_over_headline
      } else {
        R.string.xrteacher_game_times_up
      }
    VocabGameResults(
      headline = stringResource(headlineRes),
      score = uiState.score,
      bestScore = uiState.highScore,
      isNewHighScore = uiState.isNewHighScore,
      primaryButtonTitle = stringResource(R.string.xrteacher_game_play_again),
      onPlayAgain = onPlayAgain,
      onExit = onExit,
      modifier = modifier,
      correctCount = uiState.correctCount,
      uniqueWordCount = uiState.uniqueWordCount,
    )
    return
  }
  val isTapMode = uiState.mode == XrLanguageTeacherVocabGameMode.TAP
  val palette = vocabGamePalette
  Box(modifier = modifier.fillMaxSize()) {
    VocabGameBackground()
    VocabPopperArena(
      arenaState = arenaState,
      isTapMode = isTapMode,
      onArenaTap = onArenaTap,
      modifier = Modifier.fillMaxSize(),
    )
    VocabGameEdgeGlow(
      color = {
        if (arenaState().isWrongTapGlow) palette.urgentEdgeGlow else palette.successEdgeGlow
      },
      intensity = { arenaState().edgeGlowIntensity },
      maxAlpha = palette.edgeGlowMaxAlpha,
    )
    Column(
      modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      VocabGameTopBar(isMuted = uiState.isMuted, onToggleMute = onToggleMute, onExit = onExit) {
        if (isTapMode) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            VocabGameTimer(remainingSeconds = uiState.remainingSeconds)
            VocabGameLivesIndicator(lives = uiState.lives, maxLives = uiState.maxLives)
          }
        } else {
          VocabGameTimer(remainingSeconds = uiState.remainingSeconds)
        }
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VocabGameScoreboard(
          score = uiState.score,
          bestScore = uiState.highScore,
          uniqueWordCount = uiState.uniqueWordCount,
          isCompact = true,
        )
        VocabGameStreakBadge(streak = uiState.streak, multiplier = uiState.streakMultiplier)
      }
      if (isTapMode && uiState.targetWord != null) {
        VocabPopperPromptBanner(word = uiState.targetWord, onReplay = onReplayPromptAudio)
      }
      Spacer(modifier = Modifier.weight(1f))
      if (!isTapMode) {
        VocabGameStatusBar(
          micState = uiState.micState,
          listeningHint = stringResource(R.string.xrteacher_vocab_popper_listening_hint),
          micPermissionMessage =
            stringResource(R.string.xrteacher_vocab_popper_mic_permission_needed),
          celebrationText =
            uiState.celebrationWord?.let { word ->
              val points = uiState.celebrationPoints
              when {
                points != null && uiState.celebrationMultiplier > 1 ->
                  stringResource(
                    R.string.xrteacher_vocab_popper_multiplied_points_pop_banner,
                    points,
                    uiState.celebrationMultiplier,
                    word,
                  )
                points != null ->
                  stringResource(R.string.xrteacher_vocab_popper_points_pop_banner, points, word)
                else -> stringResource(R.string.xrteacher_vocab_popper_popped, word)
              }
            },
          heardWords = heardWords,
        )
      }
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
  isTapMode: Boolean,
  onArenaTap: (Float, Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val resources = LocalResources.current
  val density = LocalDensity.current
  val palette = vocabGamePalette
  val pillStroke = remember(density) { Stroke(width = with(density) { 3.dp.toPx() }) }
  val bannerStroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
  val photoFrame = rememberStickerPhotoFrame(palette)
  // Each banner's text and pill layout are measured once, not on every frame.
  val bannerLayouts =
    remember(resources, textMeasurer, palette) { HashMap<Long, TextLayoutResult>() }
  val pillLayouts = remember(textMeasurer, isTapMode) { HashMap<String, TextLayoutResult>() }
  val tapModifier =
    if (isTapMode) {
      Modifier.pointerInput(onArenaTap) {
        detectTapGestures { offset ->
          val width = max(size.width, 1).toFloat()
          val height = max(size.height, 1).toFloat()
          onArenaTap(offset.x / width, offset.y / height, height / width)
        }
      }
    } else {
      Modifier
    }
  Canvas(modifier = modifier.then(tapModifier).clearAndSetSemantics {}) {
    val arena = arenaState()
    for (particle in arena.game.particles) {
      drawParticle(particle)
    }
    if (arena.game.targets.isEmpty()) pillLayouts.clear()
    for (target in arena.game.targets) {
      drawTarget(
        target = target,
        sticker = arena.stickers[target.item.entryKey],
        isTapMode = isTapMode,
        textMeasurer = textMeasurer,
        pillLayouts = pillLayouts,
        pillStroke = pillStroke,
        palette = palette,
        photoFrame = photoFrame,
      )
    }
    if (arena.game.popBanners.isEmpty()) bannerLayouts.clear()
    for (banner in arena.game.popBanners) {
      val layout =
        bannerLayouts.getOrPut(banner.id) {
          val text =
            if (banner.multiplier > 1) {
              resources.getString(
                R.string.xrteacher_vocab_popper_multiplied_points_pop_banner,
                banner.pointsAwarded,
                banner.multiplier,
                banner.word,
              )
            } else {
              resources.getString(
                R.string.xrteacher_vocab_popper_points_pop_banner,
                banner.pointsAwarded,
                banner.word,
              )
            }
          textMeasurer.measure(text = text, style = popBannerTextStyle(palette), maxLines = 1)
        }
      drawPopBanner(banner = banner, layout = layout, palette = palette, stroke = bannerStroke)
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
  isTapMode: Boolean,
  textMeasurer: TextMeasurer,
  pillLayouts: MutableMap<String, TextLayoutResult>,
  pillStroke: Stroke,
  palette: VocabGamePalette,
  photoFrame: StickerPhotoFrame?,
) {
  val popProgress = target.popProgress ?: 0f
  val dismissProgress = target.dismissProgress ?: 0f
  val scale =
    when {
      target.isPopping -> 1f + popProgress * 0.45f
      target.isDismissing -> max(0.7f, 1f - dismissProgress * 0.25f)
      else -> 1f
    }
  val alpha =
    when {
      target.isPopping -> max(0f, 1f - popProgress)
      target.isDismissing -> max(0f, 1f - dismissProgress)
      else -> 1f
    }
  val center = Offset(target.x * size.width, target.y * size.height)
  translate(left = center.x, top = center.y) {
    rotate(degrees = target.rotationDegrees, pivot = Offset.Zero) {
      scale(scale = scale, pivot = Offset.Zero) {
        if (sticker != null) {
          drawStickerSprite(sticker, STICKER_SIZE.toPx(), alpha, photoFrame)
        } else {
          val pillText =
            if (isTapMode && target.item.meaning.isNotBlank()) {
              target.item.meaning
            } else {
              target.item.displayWord
            }
          val maxWidthPx = (size.width * 0.6f).toInt()
          val layout =
            pillLayouts.getOrPut(target.item.entryKey) {
              textMeasurer.measure(
                text = pillText,
                style = WordPillTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidthPx),
              )
            }
          drawWordPill(
            layout = layout,
            fill = paletteColor(target.colorIndex),
            stroke = pillStroke,
            alpha = alpha,
            palette = palette,
          )
        }
      }
    }
  }
}

/**
 * The soft shadow and hairline outline drawn around an unsegmented photo sticker's white frame in
 * light mode, where the white frame alone blends into the light surface. Segmented cutouts keep
 * only their baked-in drop shadow.
 */
internal class StickerPhotoFrame(
  val outlineColor: Color,
  val outlineStroke: Stroke,
  val shadowOffsetPx: Float,
) {
  // Whether each sticker is a framed photo never changes, so it is read once, not on every frame.
  private val isFramedPhotoBySticker = WeakHashMap<ImageBitmap, Boolean>()

  /**
   * Returns whether [sticker] is drawn in this frame (see
   * [XrLanguageTeacherStickerImages.isFramedPhotoSticker]).
   */
  fun isFramedPhoto(sticker: ImageBitmap): Boolean =
    isFramedPhotoBySticker.getOrPut(sticker) {
      XrLanguageTeacherStickerImages.isFramedPhotoSticker(sticker)
    }
}

/** Returns the [StickerPhotoFrame] of [palette], or `null` in dark mode, which needs none. */
@Composable
internal fun rememberStickerPhotoFrame(palette: VocabGamePalette): StickerPhotoFrame? {
  val density = LocalDensity.current
  return remember(palette, density) {
    if (palette.isDark) {
      null
    } else {
      with(density) {
        StickerPhotoFrame(
          outlineColor = palette.stickerFrameOutline,
          outlineStroke = Stroke(width = 1.dp.toPx()),
          shadowOffsetPx = 2.5.dp.toPx(),
        )
      }
    }
  }
}

/**
 * Draws [sticker] fitted into a [boxSize] square centered on the origin, inside [photoFrame] when
 * it is given and [sticker] is an unsegmented photo (see [StickerPhotoFrame.isFramedPhoto]).
 */
internal fun DrawScope.drawStickerSprite(
  sticker: ImageBitmap,
  boxSize: Float,
  alpha: Float = 1f,
  photoFrame: StickerPhotoFrame? = null,
) {
  val fit = min(boxSize / sticker.width, boxSize / sticker.height)
  val width = (sticker.width * fit).roundToInt()
  val height = (sticker.height * fit).roundToInt()
  val dstOffset = IntOffset(-width / 2, -height / 2)
  val frame = photoFrame?.takeIf { it.isFramedPhoto(sticker) }
  val frameTopLeft = Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat())
  val frameSize = Size(width.toFloat(), height.toFloat())
  val frameCornerRadius =
    CornerRadius(max(width, height) * XrLanguageTeacherStickerImages.PHOTO_FRAME_CORNER_FRACTION)
  if (frame != null) {
    drawRoundRect(
      color = LightModeLiftShadow,
      topLeft = frameTopLeft + Offset(0f, frame.shadowOffsetPx),
      size = frameSize,
      cornerRadius = frameCornerRadius,
      // Fades faster than the photo so it doesn't show through while the sticker fades out.
      alpha = alpha * alpha,
    )
  }
  drawImage(image = sticker, dstOffset = dstOffset, dstSize = IntSize(width, height), alpha = alpha)
  if (frame != null) {
    drawRoundRect(
      color = frame.outlineColor,
      topLeft = frameTopLeft,
      size = frameSize,
      cornerRadius = frameCornerRadius,
      style = frame.outlineStroke,
      alpha = alpha,
    )
  }
}

/**
 * Draws [layout] on a white-bordered rounded pill filled with [fill], centered on the origin, over
 * a soft drop shadow in light mode.
 */
private fun DrawScope.drawWordPill(
  layout: TextLayoutResult,
  fill: Color,
  stroke: Stroke,
  alpha: Float,
  palette: VocabGamePalette,
) {
  val pillSize = Size(layout.size.width + 36.dp.toPx(), layout.size.height + 24.dp.toPx())
  val topLeft = Offset(-pillSize.width / 2f, -pillSize.height / 2f)
  val cornerRadius = CornerRadius(20.dp.toPx())
  if (!palette.isDark) {
    drawRoundRect(
      color = LightModeLiftShadow,
      topLeft = topLeft + Offset(0f, 2.dp.toPx()),
      size = pillSize,
      cornerRadius = cornerRadius,
      alpha = alpha,
    )
  }
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
  layout: TextLayoutResult,
  palette: VocabGamePalette,
  stroke: Stroke,
) {
  val alpha = (1f - banner.progress).coerceIn(0f, 1f)
  val scale = 0.9f + min(banner.progress * 2f, 0.25f)
  val pillSize = Size(layout.size.width + 28.dp.toPx(), layout.size.height + 12.dp.toPx())
  val halfPillWidth = (pillSize.width * scale) / 2f + 8.dp.toPx()
  val clampedX =
    (banner.x * size.width).coerceIn(halfPillWidth, max(halfPillWidth, size.width - halfPillWidth))
  translate(left = clampedX, top = banner.y * size.height) {
    scale(scale = scale, pivot = Offset.Zero) {
      val pillTopLeft = Offset(-pillSize.width / 2f, -pillSize.height / 2f)
      val pillCornerRadius = CornerRadius(pillSize.height / 2f)
      drawRoundRect(
        color = palette.popBannerFill,
        topLeft = pillTopLeft,
        size = pillSize,
        cornerRadius = pillCornerRadius,
        alpha = alpha,
      )
      if (!palette.isDark) {
        drawRoundRect(
          color = palette.popBannerStroke,
          topLeft = pillTopLeft,
          size = pillSize,
          cornerRadius = pillCornerRadius,
          style = stroke,
          alpha = alpha,
        )
      }
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
