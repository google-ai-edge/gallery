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

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.proto.VideoClip
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.ui.theme.onPrimaryContainerDark
import com.google.ai.edge.gallery.ui.theme.onSecondaryContainerDark
import com.google.ai.edge.gallery.ui.theme.primaryContainerDark
import com.google.ai.edge.gallery.ui.theme.secondaryContainerDark
import com.google.ai.edge.gallery.ui.theme.surfaceDark
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ClipEditorScreen(
  project: VideoMomentProject,
  selectedResult: MomentSearchResult? = null,
  savedClip: VideoClip? = null,
  searchQuery: String = "",
  viewModel: VideoMomentFinderViewModel? = null,
  bottomPadding: Dp = 0.dp,
  onClose: () -> Unit,
) {
  // Handle device back gesture / button to close the clip editor.
  BackHandler(onBack = onClose)

  val uiState = viewModel?.uiState?.collectAsState()?.value
  val searchResults =
    uiState?.searchResults ?: (if (selectedResult != null) listOf(selectedResult) else emptyList())

  val initialIndex =
    if (selectedResult != null) {
      searchResults.indexOfFirst { it.id == selectedResult.id }.coerceAtLeast(0)
    } else {
      0
    }

  var currentClipIndex by
    remember(searchResults, selectedResult) { mutableIntStateOf(initialIndex) }

  val activeIndex =
    if (searchResults.isNotEmpty()) {
      currentClipIndex.coerceIn(0, searchResults.size - 1)
    } else {
      0
    }

  val context = LocalContext.current

  // Initialize ExoPlayer and prepare it with the project's video file.
  val exoPlayer = remember {
    ExoPlayer.Builder(context).build().apply {
      val projectFile = File(context.getExternalFilesDir(null), project.relativeVideoPath)
      setMediaItem(MediaItem.fromUri(Uri.fromFile(projectFile)))
      prepare()
    }
  }

  var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }

  // Listen for playback changes and clean up the listener/player properly on exit.
  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
          isPlaying = isPlayingNow
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  val initialStart = savedClip?.startTimeMs ?: 0L
  val initialEnd =
    savedClip?.endTimeMs
      ?: (if (project.durationMs > 0) (project.durationMs).coerceAtMost(5000L) else 5000L)

  var startTimeMs by remember(project.id, savedClip?.id) { mutableLongStateOf(initialStart) }
  var endTimeMs by
    remember(project.id, savedClip?.id, project.durationMs) { mutableLongStateOf(initialEnd) }
  var currentPositionMs by remember(savedClip?.id) { mutableLongStateOf(initialStart) }

  // Synchronize trim range when active clip changes from stepper or saved clip input
  LaunchedEffect(activeIndex, searchResults, savedClip) {
    if (savedClip != null) {
      startTimeMs = savedClip.startTimeMs
      endTimeMs = savedClip.endTimeMs
      currentPositionMs = savedClip.startTimeMs
      exoPlayer.seekTo(savedClip.startTimeMs)
      return@LaunchedEffect
    }
    val clip = searchResults.getOrNull(activeIndex)
    if (clip != null) {
      val dur = if (project.durationMs > 0) project.durationMs else 10000L
      // The handles sit exactly on the matched clip's bounds. The end is only pushed out when the
      // clip is shorter than the trimmer's 1s minimum (e.g. a zero-length moment), since
      // ClipTrimmer would otherwise draw the handles somewhere other than the saved range.
      val start = clip.startTimeMs.coerceIn(0L, dur)
      val end = clip.endTimeMs.coerceIn((start + 1000L).coerceAtMost(dur), dur)
      startTimeMs = start
      endTimeMs = end
      currentPositionMs = clip.timeMs
      exoPlayer.seekTo(clip.timeMs)
    }
  }

  // Loop playback within [startTimeMs, endTimeMs] and sync needle position
  LaunchedEffect(exoPlayer, isPlaying, startTimeMs, endTimeMs) {
    while (isPlaying) {
      val pos = exoPlayer.currentPosition
      currentPositionMs = pos
      if (pos >= endTimeMs || pos < startTimeMs) {
        exoPlayer.seekTo(startTimeMs)
      }
      delay(33L)
    }
  }

  val clipDurationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)

  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()
  var isExporting by remember { mutableStateOf(false) }
  var isSaving by remember { mutableStateOf(false) }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(surfaceDark)
        // Consume tap events across the full screen so they do not leak through to
        // the underlying video player or controls.
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        )
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Top app bar with 56dp height
      Box(modifier = Modifier.fillMaxWidth().height(56.dp).background(surfaceDark)) {
        Text(
          text = stringResource(R.string.videomomentfinder_save_or_edit_clips),
          style = MaterialTheme.typography.titleMedium,
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
          modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
        IconButton(
          onClick = {
            logButtonClick("videomomentfinder_clip_editor_close")
            onClose()
          },
          modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close_icon),
            tint = Color.White,
          )
        }
      }

      // Clip stepper row under the title bar (or single clip header if opening a saved clip)
      if (savedClip != null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              text =
                if (savedClip.searchQuery.isNotEmpty()) {
                  "\"${savedClip.searchQuery}\""
                } else {
                  stringResource(R.string.videomomentfinder_tab_moments)
                },
              style = MaterialTheme.typography.titleMedium,
              color = Color.White,
            )
            Text(
              text = formatTime(clipDurationMs),
              style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
              color = Color.White.copy(alpha = 0.7f),
            )
          }
        }
      } else if (searchResults.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          // Left: go to previous clip. Disabled when at the first clip.
          StepperIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Previous clip",
            enabled = activeIndex > 0,
            onClick = {
              logButtonClick("videomomentfinder_clip_editor_previous_clip")
              if (activeIndex > 0) {
                currentClipIndex = activeIndex - 1
                searchResults.getOrNull(activeIndex - 1)?.let { prevClip ->
                  viewModel?.selectResult(prevClip.id)
                }
              }
            },
          )

          // Center: a column with "Clip N" and duration of the clip.
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              text = "Clip ${activeIndex + 1}",
              style = MaterialTheme.typography.titleMedium,
              color = Color.White,
            )
            Text(
              text = formatTime(clipDurationMs),
              style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
              color = Color.White.copy(alpha = 0.7f),
            )
          }

          // Right: go to next clip. Disabled when at the last clip.
          StepperIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = "Next clip",
            enabled = activeIndex < searchResults.size - 1,
            onClick = {
              logButtonClick("videomomentfinder_clip_editor_next_clip")
              if (activeIndex < searchResults.size - 1) {
                currentClipIndex = activeIndex + 1
                searchResults.getOrNull(activeIndex + 1)?.let { nextClip ->
                  viewModel?.selectResult(nextClip.id)
                }
              }
            },
          )
        }
      }

      // Video container column holding the video player and overlay controls.
      Column(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        // Rounded viewport for the video player with an 8dp corner radius.
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          // AndroidView wrapping ExoPlayer's PlayerView to render the imported video.
          AndroidView(
            factory = { ctx ->
              PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
              }
            },
            modifier = Modifier.fillMaxSize(),
          )

          // Center play/pause overlay button to toggle video playback.
          IconButton(
            onClick = {
              logButtonClick(
                if (isPlaying) {
                  "videomomentfinder_clip_editor_pause"
                } else {
                  "videomomentfinder_clip_editor_play"
                }
              )
              if (isPlaying) {
                exoPlayer.pause()
              } else {
                if (
                  exoPlayer.currentPosition >= endTimeMs ||
                    exoPlayer.playbackState == Player.STATE_ENDED
                ) {
                  exoPlayer.seekTo(startTimeMs)
                }
                exoPlayer.play()
              }
            },
            modifier = Modifier.size(48.dp),
            colors =
              IconButtonDefaults.filledIconButtonColors(
                containerColor = surfaceDark,
                contentColor = Color.White,
              ),
          ) {
            Icon(
              imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
              contentDescription = if (isPlaying) "Pause video" else "Play video",
              tint = Color.White,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      val activeClip = searchResults.getOrNull(activeIndex)
      val clipCenterTimeMs =
        remember(activeIndex, activeClip?.id, savedClip?.id) {
          if (savedClip != null) {
            (savedClip.startTimeMs + savedClip.endTimeMs) / 2L
          } else {
            activeClip?.timeMs ?: ((startTimeMs + endTimeMs) / 2L)
          }
        }

      // Video moment trimmer control & scrubber
      ClipTrimmer(
        project = project,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        currentPositionMs = currentPositionMs,
        windowDurationMs = 20000L,
        centerTimeMs = clipCenterTimeMs,
        onStartTimeChange = { newStart ->
          startTimeMs = newStart
          exoPlayer.seekTo(newStart)
          currentPositionMs = newStart
        },
        onEndTimeChange = { newEnd ->
          endTimeMs = newEnd
          exoPlayer.seekTo(newEnd)
          currentPositionMs = newEnd
        },
        onSeekRequest = { seekMs ->
          currentPositionMs = seekMs
          exoPlayer.seekTo(seekMs)
        },
        modifier = Modifier.padding(horizontal = 16.dp),
      )

      // Button row: Save clip & Download clip
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val clipSavedMessage = stringResource(R.string.videomomentfinder_clip_saved)
        val clipUpdatedMessage = stringResource(R.string.videomomentfinder_clip_updated)
        val clipSavedToAlbumMessage = stringResource(R.string.videomomentfinder_clip_saved_to_album)
        val failedToSaveMessage = stringResource(R.string.videomomentfinder_failed_to_save_clip)

        val effectiveQuery =
          if (searchQuery.isNotEmpty()) searchQuery else (savedClip?.searchQuery ?: "")

        // Save clip button
        Button(
          onClick = {
            // Logged inside the guard: `enabled` lags a frame behind `isSaving`, so a double tap
            // can reach here twice while only the first tap actually saves.
            if (!isSaving && !isExporting && viewModel != null) {
              logButtonClick(
                if (savedClip != null) "videomomentfinder_update_clip"
                else "videomomentfinder_save_clip"
              )
              isSaving = true
              val scope = viewModel.viewModelScope
              scope.launch {
                val success =
                  viewModel.saveClip(
                    project = project,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    searchQuery = effectiveQuery,
                    clipId = savedClip?.id,
                  )
                isSaving = false
                if (success) {
                  snackbarHostState.showSnackbar(
                    if (savedClip != null) clipUpdatedMessage else clipSavedMessage
                  )
                } else {
                  logErrorToFirebase(
                    GalleryEvent.BUTTON_CLICKED,
                    if (savedClip != null) "videomomentfinder_update_clip_error"
                    else "videomomentfinder_save_clip_error",
                    null,
                  )
                  snackbarHostState.showSnackbar(failedToSaveMessage)
                }
              }
            }
          },
          enabled = !isSaving && !isExporting && viewModel != null,
          modifier = Modifier.weight(1f),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = secondaryContainerDark,
              contentColor = onSecondaryContainerDark,
              disabledContainerColor = secondaryContainerDark.copy(alpha = 0.5f),
              disabledContentColor = onSecondaryContainerDark.copy(alpha = 0.5f),
            ),
        ) {
          if (isSaving) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = onSecondaryContainerDark,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.videomomentfinder_processing))
          } else {
            Icon(
              imageVector = Icons.Rounded.Save,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                stringResource(
                  if (savedClip != null) {
                    R.string.videomomentfinder_update_clip
                  } else {
                    R.string.videomomentfinder_save_clip
                  }
                )
            )
          }
        }

        // Download clip button
        Button(
          onClick = {
            // Logged inside the guard, for the same reason as the save button above.
            if (!isExporting && !isSaving) {
              logButtonClick("videomomentfinder_download_clip")
              isExporting = true
              val scope = viewModel?.viewModelScope ?: coroutineScope
              scope.launch {
                val success =
                  exportVideoClipToMediaStore(
                    context = context,
                    project = project,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                  )
                isExporting = false
                if (success) {
                  snackbarHostState.showSnackbar(clipSavedToAlbumMessage)
                } else {
                  logErrorToFirebase(
                    GalleryEvent.BUTTON_CLICKED,
                    "videomomentfinder_download_clip_error",
                    null,
                  )
                  snackbarHostState.showSnackbar(failedToSaveMessage)
                }
              }
            }
          },
          enabled = !isExporting && !isSaving,
          modifier = Modifier.weight(1f),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = primaryContainerDark,
              contentColor = onPrimaryContainerDark,
              disabledContainerColor = primaryContainerDark.copy(alpha = 0.5f),
              disabledContentColor = onPrimaryContainerDark.copy(alpha = 0.5f),
            ),
        ) {
          if (isExporting) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = onPrimaryContainerDark,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.videomomentfinder_processing))
          } else {
            Icon(
              imageVector = Icons.Outlined.FileDownload,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.videomomentfinder_download_clip))
          }
        }
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 80.dp),
    )
  }
}

@Composable
private fun StepperIconButton(
  icon: ImageVector,
  contentDescription: String,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier,
    colors =
      IconButtonDefaults.filledIconButtonColors(
        containerColor = secondaryContainerDark,
        contentColor = onSecondaryContainerDark,
        disabledContainerColor = secondaryContainerDark.copy(alpha = 0.38f),
        disabledContentColor = onSecondaryContainerDark.copy(alpha = 0.38f),
      ),
  ) {
    Icon(imageVector = icon, contentDescription = contentDescription)
  }
}
