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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.ui.theme.onPrimaryDark
import com.google.ai.edge.gallery.ui.theme.primaryDark
import com.google.ai.edge.gallery.ui.theme.surfaceContainerDark
import com.google.ai.edge.gallery.ui.theme.surfaceContainerHighestDark
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGClipTrimmer"

/**
 * A custom, horizontal video trimming control and timeline scrubber.
 *
 * It consists of:
 * 1. **Thumbnail Strip (Track)**: A fixed-height horizontal strip rendering sequential,
 *    keyframe-extracted image thumbnails derived from the source video.
 * 2. **Bounding Bumper / Selection Frame**: A highlighted rectangle with draggable left/right
 *    handles, solid top/bottom rails, and semitransparent dimming masks outside the selected range.
 * 3. **Playhead / Scrubber Needle**: A vertical needle indicating current playback position with a
 *    floating timestamp badge and touch/drag scrubbing.
 *
 * @param project The active [VideoMomentProject] containing the relative video path and metadata.
 * @param modifier The modifier to be applied to the layout.
 * @param startTimeMs The start trim timestamp in milliseconds.
 * @param endTimeMs The end trim timestamp in milliseconds.
 * @param currentPositionMs The current video playback / scrubber timestamp in milliseconds.
 * @param minDurationMs The minimum allowed duration of the selected clip in milliseconds.
 * @param windowDurationMs The duration in milliseconds of the visible trimming window (default: 20
 *   seconds).
 * @param centerTimeMs The timestamp in milliseconds to center the 20-second visible window around.
 * @param onStartTimeChange Callback invoked when the start handle position changes.
 * @param onEndTimeChange Callback invoked when the end handle position changes.
 * @param onSeekRequest Callback invoked when the playhead needle is scrubbed or tapped.
 */
@Composable
fun ClipTrimmer(
  project: VideoMomentProject,
  modifier: Modifier = Modifier,
  startTimeMs: Long = 0L,
  endTimeMs: Long = if (project.durationMs > 0) project.durationMs else 5000L,
  currentPositionMs: Long = startTimeMs,
  minDurationMs: Long = 1000L,
  windowDurationMs: Long = 20000L,
  centerTimeMs: Long = (startTimeMs + endTimeMs) / 2L,
  onStartTimeChange: (Long) -> Unit = {},
  onEndTimeChange: (Long) -> Unit = {},
  onSeekRequest: (Long) -> Unit = {},
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current

  val frameCount = 20

  // Video duration state (fallback to project metadata or media metadata retriever)
  var totalDurationMs by
    remember(project.durationMs) {
      mutableLongStateOf(if (project.durationMs > 0) project.durationMs else 5000L)
    }

  val duration = totalDurationMs.coerceAtLeast(1L)
  val clampedCenterMs = centerTimeMs.coerceIn(0L, duration)
  val effectiveWindowDuration = windowDurationMs.coerceAtLeast(minDurationMs).coerceAtMost(duration)

  // Anchor the visible window around centerTimeMs, clamped within [0, duration]
  val idealWindowStart = clampedCenterMs - (effectiveWindowDuration / 2)
  val windowStartMs =
    idealWindowStart.coerceIn(0L, (duration - effectiveWindowDuration).coerceAtLeast(0L))
  val windowEndMs = (windowStartMs + effectiveWindowDuration).coerceAtMost(duration)
  val windowLengthMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

  // Cached list of thumbnail bitmaps extracted from the video window, progressively populated
  var thumbnails by
    remember(project.id, project.relativeVideoPath, windowStartMs, windowEndMs) {
      mutableStateOf<List<Bitmap?>>(List(frameCount) { null })
    }

  // Asynchronously extract keyframes in parallel across 5 background workers
  LaunchedEffect(project.id, project.relativeVideoPath, windowStartMs, windowEndMs) {
    withContext(Dispatchers.IO) {
      val videoFile = File(context.getExternalFilesDir(null), project.relativeVideoPath)
      if (!videoFile.exists()) return@withContext

      // Check / update video duration once
      try {
        val initialRetriever = MediaMetadataRetriever()
        try {
          initialRetriever.setDataSource(videoFile.absolutePath)
          val durStr =
            initialRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          val dur = durStr?.toLongOrNull() ?: project.durationMs
          if (dur > 0) {
            totalDurationMs = dur
          }
        } finally {
          initialRetriever.release()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to retrieve video duration for thumbnail generation", e)
      }

      val stepUs = ((windowEndMs - windowStartMs) * 1000L) / frameCount.coerceAtLeast(1)
      val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
      val numWorkers = 10.coerceAtMost(availableCores).coerceAtMost(frameCount)
      val indicesByWorker = (0 until frameCount).groupBy { it % numWorkers }

      coroutineScope {
        for (frameIndices in indicesByWorker.values) {
          launch {
            val retriever = MediaMetadataRetriever()
            try {
              retriever.setDataSource(videoFile.absolutePath)
              for (i in frameIndices) {
                val timeUs = (windowStartMs * 1000L) + (i * stepUs)
                val bitmap =
                  retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    160,
                    90,
                  )
                if (bitmap != null) {
                  withContext(Dispatchers.Main) {
                    thumbnails = thumbnails.toMutableList().apply { set(i, bitmap) }
                  }
                }
              }
            } catch (e: Exception) {
              Log.w(TAG, "Failed to extract thumbnail frame", e)
            } finally {
              retriever.release()
            }
          }
        }
      }
    }
  }

  val clampedStartMs =
    startTimeMs.coerceIn(windowStartMs, (windowEndMs - minDurationMs).coerceAtLeast(windowStartMs))
  val clampedEndMs =
    endTimeMs.coerceIn(
      clampedStartMs + minDurationMs.coerceAtMost(windowEndMs - clampedStartMs),
      windowEndMs,
    )

  val currentStartTime by rememberUpdatedState(clampedStartMs)
  val currentEndTime by rememberUpdatedState(clampedEndMs)

  val handleWidthDp = 18.dp
  val handleWidthPx = with(density) { handleWidthDp.toPx() }
  val trackHeightDp = 56.dp
  val railThicknessDp = 4.dp

  // Trimmer Container
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val totalWidthPx = constraints.maxWidth.toFloat()
    val usableTrackWidthPx = (totalWidthPx - (2 * handleWidthPx)).coerceAtLeast(1f)

    val startFraction =
      ((clampedStartMs - windowStartMs).toFloat() / windowLengthMs).coerceIn(0f, 1f)
    val endFraction = ((clampedEndMs - windowStartMs).toFloat() / windowLengthMs).coerceIn(0f, 1f)
    val playheadFraction =
      ((currentPositionMs - windowStartMs).toFloat() / windowLengthMs).coerceIn(0f, 1f)

    val playheadViewportPx = handleWidthPx + (playheadFraction * usableTrackWidthPx)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      // Floating timestamp badge above the scrubber needle
      val badgeWidthDp = 48.dp
      val badgeWidthPx = with(density) { badgeWidthDp.toPx() }
      val clampedBadgeX =
        (playheadViewportPx - (badgeWidthPx / 2f)).coerceIn(
          0f,
          (totalWidthPx - badgeWidthPx).coerceAtLeast(0f),
        )

      Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
        if (playheadViewportPx in 0f..totalWidthPx) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = surfaceContainerHighestDark,
            modifier =
              Modifier.offset { IntOffset(clampedBadgeX.roundToInt(), 0) }
                .width(badgeWidthDp)
                .height(20.dp),
          ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = formatTime(currentPositionMs),
                style =
                  MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                  ),
                color = Color.White,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      // Trimmer Timeline Track Container
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(trackHeightDp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceContainerDark)
      ) {
        // Main interactive canvas box
        Box(
          modifier =
            Modifier.fillMaxSize()
              .pointerInput(
                windowStartMs,
                windowLengthMs,
                usableTrackWidthPx,
                handleWidthPx,
                clampedStartMs,
                clampedEndMs,
              ) {
                detectTapGestures(
                  onTap = { tapOffset ->
                    val effectiveX = tapOffset.x - handleWidthPx
                    val tappedFraction = (effectiveX / usableTrackWidthPx).coerceIn(0f, 1f)
                    val targetTimeMs =
                      (windowStartMs + (tappedFraction * windowLengthMs).toLong()).coerceIn(
                        clampedStartMs,
                        clampedEndMs,
                      )
                    onSeekRequest(targetTimeMs)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  }
                )
              }
              .pointerInput(
                windowStartMs,
                windowLengthMs,
                usableTrackWidthPx,
                handleWidthPx,
                clampedStartMs,
                clampedEndMs,
              ) {
                detectHorizontalDragGestures(
                  onDragStart = { startOffset ->
                    val effectiveX = startOffset.x - handleWidthPx
                    val startFraction = (effectiveX / usableTrackWidthPx).coerceIn(0f, 1f)
                    val targetTimeMs =
                      (windowStartMs + (startFraction * windowLengthMs).toLong()).coerceIn(
                        clampedStartMs,
                        clampedEndMs,
                      )
                    onSeekRequest(targetTimeMs)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  },
                  onHorizontalDrag = { change, _ ->
                    change.consume()
                    val effectiveX = change.position.x - handleWidthPx
                    val fraction = (effectiveX / usableTrackWidthPx).coerceIn(0f, 1f)
                    val targetTimeMs =
                      (windowStartMs + (fraction * windowLengthMs).toLong()).coerceIn(
                        clampedStartMs,
                        clampedEndMs,
                      )
                    onSeekRequest(targetTimeMs)
                  },
                )
              }
        ) {
          // Thumbnail Strip Track (inset by handleWidthPx so handles sit flush at edges)
          Row(
            modifier =
              Modifier.fillMaxHeight().width(with(density) { usableTrackWidthPx.toDp() }).offset {
                IntOffset(handleWidthPx.roundToInt(), 0)
              }
          ) {
            for (bitmap in thumbnails) {
              ThumbnailSlot(bitmap = bitmap, modifier = Modifier.weight(1f).fillMaxHeight())
            }
          }

          // Relative coordinate positions of start & end within current viewport
          val startViewportPx = handleWidthPx + (startFraction * usableTrackWidthPx)
          val endViewportPx = handleWidthPx + (endFraction * usableTrackWidthPx)

          val leftOverlayWidth = startViewportPx.coerceAtLeast(0f)
          val rightOverlayStart = endViewportPx.coerceAtLeast(0f)
          val rightOverlayWidth = (totalWidthPx - rightOverlayStart).coerceAtLeast(0f)

          // 1. Unselected Area Overlay (Left Dim Mask)
          if (leftOverlayWidth > 0f) {
            Box(
              modifier =
                Modifier.fillMaxHeight()
                  .width(with(density) { leftOverlayWidth.toDp() })
                  .background(Color.Black.copy(alpha = 0.65f))
            )
          }

          // 2. Unselected Area Overlay (Right Dim Mask)
          if (rightOverlayWidth > 0f) {
            Box(
              modifier =
                Modifier.fillMaxHeight()
                  .width(with(density) { rightOverlayWidth.toDp() })
                  .offset { IntOffset(rightOverlayStart.roundToInt(), 0) }
                  .background(Color.Black.copy(alpha = 0.65f))
            )
          }

          // 3. Selection Bounding Frame Rails (Top and Bottom connecting bars)
          val selectionWidth = (endViewportPx - startViewportPx).coerceAtLeast(0f)
          if (selectionWidth > 0f) {
            // Top Rail
            Box(
              modifier =
                Modifier.height(railThicknessDp)
                  .width(with(density) { selectionWidth.toDp() })
                  .offset { IntOffset(startViewportPx.roundToInt(), 0) }
                  .background(primaryDark)
            )
            // Bottom Rail
            Box(
              modifier =
                Modifier.height(railThicknessDp)
                  .width(with(density) { selectionWidth.toDp() })
                  .offset {
                    IntOffset(
                      startViewportPx.roundToInt(),
                      with(density) { (trackHeightDp - railThicknessDp).toPx() }.roundToInt(),
                    )
                  }
                  .background(primaryDark)
            )
          }

          // 4. Left Handle (Start Bumper)
          val leftHandleOffsetPx = startViewportPx - handleWidthPx
          Box(
            modifier =
              Modifier.fillMaxHeight()
                .width(handleWidthDp)
                .offset { IntOffset(leftHandleOffsetPx.roundToInt(), 0) }
                .clip(
                  RoundedCornerShape(
                    topStart = 8.dp,
                    bottomStart = 8.dp,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp,
                  )
                )
                .background(primaryDark)
                .pointerInput(
                  windowStartMs,
                  windowEndMs,
                  windowLengthMs,
                  usableTrackWidthPx,
                  minDurationMs,
                ) {
                  detectHorizontalDragGestures(
                    onDragStart = {
                      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onHorizontalDrag = { _, dragAmount ->
                      val deltaFraction = dragAmount / usableTrackWidthPx
                      val deltaMs = (deltaFraction * windowLengthMs).toLong()
                      val maxAllowedStartMs = currentEndTime - minDurationMs
                      val newStartMs =
                        (currentStartTime + deltaMs).coerceIn(
                          windowStartMs,
                          maxAllowedStartMs.coerceAtLeast(windowStartMs),
                        )
                      if (newStartMs != currentStartTime) {
                        onStartTimeChange(newStartMs)
                        onSeekRequest(newStartMs)
                      }
                    },
                  )
                },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
              contentDescription = "Trim start handle",
              tint = onPrimaryDark,
              modifier = Modifier.size(16.dp),
            )
          }

          // 5. Right Handle (End Bumper)
          val rightHandleOffsetPx = endViewportPx
          Box(
            modifier =
              Modifier.fillMaxHeight()
                .width(handleWidthDp)
                .offset { IntOffset(rightHandleOffsetPx.roundToInt(), 0) }
                .clip(
                  RoundedCornerShape(
                    topStart = 0.dp,
                    bottomStart = 0.dp,
                    topEnd = 8.dp,
                    bottomEnd = 8.dp,
                  )
                )
                .background(primaryDark)
                .pointerInput(
                  windowStartMs,
                  windowEndMs,
                  windowLengthMs,
                  usableTrackWidthPx,
                  minDurationMs,
                ) {
                  detectHorizontalDragGestures(
                    onDragStart = {
                      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onHorizontalDrag = { _, dragAmount ->
                      val deltaFraction = dragAmount / usableTrackWidthPx
                      val deltaMs = (deltaFraction * windowLengthMs).toLong()
                      val minAllowedEndMs = currentStartTime + minDurationMs
                      val newEndMs =
                        (currentEndTime + deltaMs).coerceIn(minAllowedEndMs, windowEndMs)
                      if (newEndMs != currentEndTime) {
                        onEndTimeChange(newEndMs)
                        onSeekRequest(newEndMs)
                      }
                    },
                  )
                },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
              contentDescription = "Trim end handle",
              tint = onPrimaryDark,
              modifier = Modifier.size(16.dp),
            )
          }

          // 6. Playhead Scrubber Needle (rendered on top of rails and handles with a soft shadow)
          val playheadWidthDp = 4.dp
          val playheadHalfWidthPx = with(density) { (playheadWidthDp / 2).toPx() }
          if (playheadViewportPx in 0f..totalWidthPx) {
            Box(
              modifier =
                Modifier.fillMaxHeight()
                  .width(playheadWidthDp)
                  .offset { IntOffset((playheadViewportPx - playheadHalfWidthPx).roundToInt(), 0) }
                  .shadow(
                    elevation = 3.dp,
                    shape = RoundedCornerShape(2.dp),
                    ambientColor = Color.Black.copy(alpha = 0.8f),
                    spotColor = Color.Black.copy(alpha = 1f),
                  )
                  .clip(RoundedCornerShape(2.dp))
                  .background(Color.White)
            )
          }
        }
      }
    }
  }
}

/** Renders a single thumbnail slot with a smooth fade-in animation when the bitmap loads. */
@Composable
private fun ThumbnailSlot(bitmap: Bitmap?, modifier: Modifier = Modifier) {
  val alpha by
    animateFloatAsState(
      targetValue = if (bitmap != null) 1f else 0f,
      animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
      label = "ThumbnailFadeIn",
    )

  Box(
    modifier = modifier.background(Color.DarkGray.copy(alpha = 0.35f)),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      // Cached so the fade-in animation, which recomposes this slot every frame, does not
      // allocate a new wrapper around the same underlying bitmap on each frame.
      val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
      Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
