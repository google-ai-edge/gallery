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

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.ui.PlayerView
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.clearFocusOnKeyboardDismiss
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.proto.VideoClip
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.proto.copy
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the progress scrubber is refreshed while the video is actually playing. */
private const val PROGRESS_POLL_INTERVAL_MS = 50L

private val PORTRAIT_PREVIEW_DEFAULT_HEIGHT = 150.dp
private val LANDSCAPE_PREVIEW_DEFAULT_HEIGHT = 90.dp

/**
 * The floating panel shown above the video.
 *
 * [FAILED] is a state of its own rather than a flavour of [SEARCH]: a project whose indexing pass
 * failed has no vectors, so offering the search bar would only ever yield "No moments found".
 */
private enum class ProjectPanel {
  PROCESSING,
  FAILED,
  SEARCH,
}

/**
 * The video length the scrubber maps onto, in milliseconds.
 *
 * [ExoPlayer.getDuration] is `C.TIME_UNSET` until the timeline is ready, and stays unset for the
 * occasional container that declares no duration of its own, so [projectDurationMs], the length
 * measured at import time, stands in for it. Position arithmetic must go through this rather than
 * reading [playerDurationMs] directly: `C.TIME_UNSET` is a large negative number, and multiplying
 * it by a scrubber fraction lands nowhere near the frame the user pointed at.
 */
internal fun playbackDurationMs(playerDurationMs: Long, projectDurationMs: Long): Long =
  if (playerDurationMs > 0L) playerDurationMs else projectDurationMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailsScreen(
  project: VideoMomentProject,
  viewModel: VideoMomentFinderViewModel,
  bottomPadding: Dp,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  onBackClick: () -> Unit,
  onOpenClipEditor:
    (savedClip: VideoClip?, selectedResult: MomentSearchResult?, searchQuery: String) -> Unit =
    { _, _, _ ->
    },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val processProgress = uiState.projectProcessProgress
  val context = LocalContext.current

  // Keyed on the project so switching projects rebuilds the player instead of replaying the
  // previous video. The DisposableEffect below is keyed on the player, so the old one is released.
  val exoPlayer =
    remember(project.id) {
      // Loop the video so playing past the last frame wraps back to the start instead of parking
      // the player in the ENDED state, which would force the user to hit play again.
      ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }

  // Resolving the video path is filesystem work (getExternalFilesDir creates the directory if it is
  // missing), so it stays out of composition. The player itself must be touched from the main
  // thread, so only the path resolution hops to the IO dispatcher.
  LaunchedEffect(exoPlayer, project.relativeVideoPath) {
    val videoUri =
      withContext(Dispatchers.IO) {
        Uri.fromFile(File(context.getExternalFilesDir(null), project.relativeVideoPath))
      }
    exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
    exoPlayer.prepare()
  }

  var isPlaying by remember(exoPlayer) { mutableStateOf(exoPlayer.isPlaying) }
  var isMuted by remember(exoPlayer) { mutableStateOf(exoPlayer.volume == 0f) }
  var progress by remember(exoPlayer) { mutableFloatStateOf(0f) }
  var searchQuery by remember { mutableStateOf("") }
  var showSavedMomentsDrawer by remember { mutableStateOf(false) }
  var activePlaybackClipStop by remember { mutableStateOf<PlayerMessage?>(null) }

  /**
   * Cancels the auto-stop scheduled for the clip that is currently playing, if any, and hands the
   * player back to whole-video looping.
   *
   * Safe to call at any time: cancelling an already delivered message is a no-op.
   */
  fun resetClipPlayback() {
    activePlaybackClipStop?.cancel()
    activePlaybackClipStop = null
    exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
  }

  fun syncProgressWithPlayer() {
    val durationMs =
      playbackDurationMs(
        playerDurationMs = exoPlayer.duration,
        projectDurationMs = project.durationMs,
      )
    if (durationMs > 0) {
      progress = (exoPlayer.currentPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
  }

  /**
   * Seeks to [startTimeMs] and plays until [endTimeMs], where playback is paused automatically with
   * the playhead clamped to [endTimeMs].
   */
  fun playClip(startTimeMs: Long, endTimeMs: Long) {
    resetClipPlayback()
    exoPlayer.seekTo(startTimeMs)
    syncProgressWithPlayer()
    if (endTimeMs <= startTimeMs) {
      // Zero-length moment: there is nothing to play, so just show the frame at `startTimeMs`.
      exoPlayer.pause()
      return
    }
    // A moment that runs to the end of the video would otherwise wrap and replay the whole video:
    // the auto-stop below is only delivered when the playhead crosses `endTimeMs`, and looping
    // moves it back to 0 without ever crossing it. Playing a clip therefore suspends the loop,
    // which `resetClipPlayback` and the auto-stop both restore.
    exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
    // Schedule the auto-stop on ExoPlayer's own playback timeline instead of polling
    // `currentPosition`. The message is queued behind the `seekTo()` above, so it can never be
    // triggered early by a stale position from a seek that has not been applied yet.
    activePlaybackClipStop =
      exoPlayer
        .createMessage { _, _ ->
          exoPlayer.pause()
          exoPlayer.seekTo(endTimeMs)
          syncProgressWithPlayer()
          exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
          activePlaybackClipStop = null
        }
        .setPosition(endTimeMs)
        // The target touches Compose state and the player, so it must run on the Main thread
        // rather than on the default playback thread.
        .setLooper(Looper.getMainLooper())
        .send()
    exoPlayer.play()
  }

  /**
   * Opens the clip editor after stopping playback here.
   *
   * The editor slides in over this screen without disposing it, so this player would otherwise keep
   * playing underneath the editor. Any pending clip auto-stop is cancelled too, so it cannot fire
   * later and move the playhead while the editor is open.
   */
  fun openClipEditor(savedClip: VideoClip?, selectedResult: MomentSearchResult?, query: String) {
    resetClipPlayback()
    exoPlayer.pause()
    onOpenClipEditor(savedClip, selectedResult, query)
  }

  // Keep the progress scrubber in sync with video playback. Keying on `isPlaying` and bailing out
  // when paused avoids waking up the Main thread while the video sits idle.
  LaunchedEffect(exoPlayer, isPlaying) {
    // Sync once so the scrubber settles on the final position when playback stops.
    syncProgressWithPlayer()
    if (!isPlaying) return@LaunchedEffect
    while (true) {
      delay(PROGRESS_POLL_INTERVAL_MS)
      syncProgressWithPlayer()
    }
  }

  // Editing or clearing the search query drops the results the active clip came from, so any
  // pending auto-stop must be cancelled to avoid pausing playback at a stale clip boundary.
  LaunchedEffect(uiState.searchResults) { resetClipPlayback() }

  // Listen for playback and volume changes and clean up the listener/player properly on exit.
  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
          isPlaying = isPlayingNow
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          // Looping is the resting state, so the end of the video is only reachable while a clip
          // has suspended it. Handing the loop back here covers the clip whose auto-stop never
          // arrives because its end sits at the end of the video, which the playhead reaches
          // without ever crossing.
          if (playbackState == Player.STATE_ENDED) {
            resetClipPlayback()
            // Reaching STATE_ENDED leaves playWhenReady true, so pause before seeking to 0;
            // otherwise seeking leaves STATE_ENDED and immediately resumes looping the full video.
            exoPlayer.pause()
            exoPlayer.seekTo(0)
            syncProgressWithPlayer()
          }
        }

        override fun onVolumeChanged(volume: Float) {
          isMuted = volume == 0f
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      activePlaybackClipStop?.cancel()
      activePlaybackClipStop = null
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  val currentProject = uiState.selectedProject ?: project
  // Tracked in the view model, not in composition state: the processing job outlives any single
  // composition, so a rotation mid-pass would otherwise lose the outcome and strand this screen.
  val processFailed = uiState.projectProcessFailed

  val isProcessing = processProgress != null || (!currentProject.isProcessed && !processFailed)

  // A failed pass leaves the project unindexed, so the search bar would silently return nothing.
  // Surface the failure and a retry instead.
  val panel =
    when {
      isProcessing -> ProjectPanel.PROCESSING
      !currentProject.isProcessed -> ProjectPanel.FAILED
      else -> ProjectPanel.SEARCH
    }

  /** Indexes the project with its own stored settings, falling back to the defaults. */
  fun processWithProjectSettings() {
    viewModel.reprocessProject(
      project = currentProject,
      framesPerWindow =
        currentProject.framesPerWindow.takeIf { it > 0 } ?: DEFAULT_FRAMES_PER_WINDOW,
      windowDurationSec =
        currentProject.windowDurationSec.takeIf { it > 0 } ?: DEFAULT_WINDOW_DURATION_SEC,
      overlapDurationSec = currentProject.overlapDurationSec,
      topK = currentProject.topK.takeIf { it > 0 } ?: DEFAULT_TOP_K,
      includeAudio = currentProject.effectiveIncludeAudio(),
    )
  }

  // A project can be unprocessed on open because its vectors were reconciled away (for example the
  // embedding model was deleted, taking the vector database in its directory with it). Kick off
  // indexing automatically so it recovers without the user having to discover the config dialog.
  //
  // The failure flag doubles as the retry guard. It is cleared in selectProject, so re-entering the
  // screen retries once, while a recomposition after a failed pass does not spin the device: from
  // there recovery is driven by the retry button on the failure panel.
  LaunchedEffect(currentProject.id, currentProject.isProcessed) {
    if (!currentProject.isProcessed && processProgress == null && !processFailed) {
      processWithProjectSettings()
    }
  }

  fun cleanUp() {
    if (isProcessing) return
    searchQuery = ""
    viewModel.clearSearchResults()
    onBackClick()
  }

  // Hook into the OS back gesture / hardware back button. The handler stays enabled while the video
  // is being processed so it swallows the event (cleanUp() is a no-op then); disabling it would let
  // the event fall through to the outer navigation handler and leave the screen mid-pass.
  BackHandler(enabled = showSavedMomentsDrawer) { showSavedMomentsDrawer = false }
  BackHandler(enabled = !showSavedMomentsDrawer) { cleanUp() }

  // Hook into the App Bar's "Up" button to trigger the same logic. A null callback would make the
  // nav graph fall back to navigateUp(), so a callback is registered even while processing. Keyed
  // on isProcessing so the registered lambda always sees the current value.
  LaunchedEffect(isProcessing) { setCustomNavigateUpCallback { cleanUp() } }

  // Clear tracking of the App Bar's "Up" button when this screen is destroyed.
  DisposableEffect(Unit) { onDispose { setCustomNavigateUpCallback(null) } }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
      // Top app bar with 56dp height
      Box(
        modifier =
          Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)
      ) {
        // Close button on the left
        IconButton(
          onClick = {
            logButtonClick("videomomentfinder_project_details_close")
            cleanUp()
          },
          enabled = !isProcessing,
          modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close_icon),
            tint =
              if (isProcessing) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              } else {
                MaterialTheme.colorScheme.onSurface
              },
          )
        }

        // Title in the center
        Text(
          text = currentProject.displayName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
          modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )

        // History / Saved moments button on the right
        val hasSavedMoments = currentProject.savedClipsCount > 0
        IconButton(
          onClick = {
            logButtonClick("videomomentfinder_open_saved_moments_drawer")
            showSavedMomentsDrawer = true
          },
          enabled = hasSavedMoments,
          modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = stringResource(R.string.videomomentfinder_tab_moments),
            tint =
              if (hasSavedMoments) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Space below the top bar containing the video player and overlays
      Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
        // Full screen video container filling the space below the top bar
        AndroidView(
          factory = { ctx ->
            PlayerView(ctx).apply {
              player = exoPlayer
              useController = false
            }
          },
          modifier = Modifier.fillMaxSize(),
        )

        // Top floating panel that switches between the processing progress indicator
        // and the video moment search bar, depending on whether the project is
        // fully processed or not.
        AnimatedContent(
          targetState = panel,
          label = "Processing, failure or search",
          transitionSpec = {
            (slideInVertically(initialOffsetY = { -it }) + fadeIn()) togetherWith
              (slideOutVertically(targetOffsetY = { -it }) + fadeOut())
          },
          modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) { targetPanel ->
          when (targetPanel) {
            ProjectPanel.PROCESSING -> ProcessingPanel(progress = processProgress ?: 0f)
            ProjectPanel.FAILED ->
              AnalysisFailedPanel(onRetryClick = { processWithProjectSettings() })
            ProjectPanel.SEARCH ->
              MomentSearchPanel(
                project = currentProject,
                viewModel = viewModel,
                uiState = uiState,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onResultClick = { result ->
                  // Select the clicked moment and start auto-playback from its start time until the
                  // end of the containing merged interval.
                  viewModel.selectResult(result.id)
                  val targetInterval = uiState.mergedIntervals.find { result.id in it.resultIds }
                  playClip(
                    startTimeMs = result.startTimeMs,
                    endTimeMs = targetInterval?.endTimeMs ?: result.endTimeMs,
                  )
                },
                onSaveOrEditClipClick = {
                  val selectedResult =
                    uiState.searchResults.find { it.id == uiState.selectedResultId }
                  openClipEditor(null, selectedResult, searchQuery)
                },
              )
          }
        }

        // Action row & scrubber directly on top of the video at the bottom
        ScrubberAndControls(
          project = currentProject,
          exoPlayer = exoPlayer,
          uiState = uiState,
          isPlaying = isPlaying,
          isMuted = isMuted,
          progress = progress,
          onProgressChange = {
            progress = it
            resetClipPlayback()
          },
          onUserPlaybackInteraction = { resetClipPlayback() },
          bottomPadding = bottomPadding,
        )
      }
    }

    // Scrim overlay for Saved Moments Drawer
    AnimatedVisibility(visible = showSavedMomentsDrawer, enter = fadeIn(), exit = fadeOut()) {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = { showSavedMomentsDrawer = false },
            )
      )
    }

    // Saved Moments Drawer sliding in from right
    AnimatedVisibility(
      visible = showSavedMomentsDrawer,
      enter = slideInHorizontally(initialOffsetX = { it }),
      exit = slideOutHorizontally(targetOffsetX = { it }),
      modifier = Modifier.align(Alignment.CenterEnd),
    ) {
      SavedMomentsDrawer(
        project = currentProject,
        bottomPadding = bottomPadding,
        onClipClick = { clip ->
          showSavedMomentsDrawer = false
          openClipEditor(clip, null, clip.searchQuery)
        },
        onDeleteClip = { clip -> viewModel.deleteClip(currentProject, clip) },
        onClose = { showSavedMomentsDrawer = false },
      )
    }
  }
}

/** Floating card shown while the project is being indexed. */
@Composable
private fun ProcessingPanel(progress: Float) {
  PanelCard(borderColor = MaterialTheme.colorScheme.primary) {
    Text(
      text = stringResource(R.string.videomomentfinder_analyzing_video),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Text(
      text = stringResource(R.string.videomomentfinder_analyzing_description),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
  }
}

/**
 * Floating card shown when indexing failed.
 *
 * Without this the screen falls through to the search bar over a project that has no vectors, so
 * every search reports "No moments found" and the user has no way to tell that indexing broke.
 */
@Composable
private fun AnalysisFailedPanel(onRetryClick: () -> Unit) {
  PanelCard(borderColor = MaterialTheme.colorScheme.error) {
    Text(
      text = stringResource(R.string.videomomentfinder_analysis_failed),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.error,
    )

    Text(
      text = stringResource(R.string.videomomentfinder_analysis_failed_description),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    FilledTonalButton(
      onClick = {
        logButtonClick("videomomentfinder_retry_analysis")
        onRetryClick()
      },
      contentPadding = SMALL_BUTTON_CONTENT_PADDING,
    ) {
      Text(stringResource(R.string.retry))
    }
  }
}

/** Shared shell for the floating cards that overlay the top of the video. */
@Composable
private fun PanelCard(borderColor: Color, content: @Composable ColumnScope.() -> Unit) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(2.dp, borderColor),
    modifier = Modifier.padding(top = 16.dp).padding(horizontal = 16.dp).fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      content = content,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentSearchPanel(
  project: VideoMomentProject,
  viewModel: VideoMomentFinderViewModel,
  uiState: VideoMomentFinderUiState,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onResultClick: (MomentSearchResult) -> Unit,
  onSaveOrEditClipClick: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .then(
          if (
            uiState.searchResults.isNotEmpty() ||
              (uiState.hasPerformedSearch && !uiState.isSearching)
          ) {
            Modifier.background(
              brush =
                Brush.verticalGradient(
                  colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                )
            )
          } else {
            Modifier
          }
        )
  ) {
    var showConfigDialog by remember { mutableStateOf(false) }
    var showTopKSheet by remember { mutableStateOf(false) }

    if (showConfigDialog) {
      val initialFrames =
        if (project.framesPerWindow > 0) project.framesPerWindow else DEFAULT_FRAMES_PER_WINDOW
      val initialWindow =
        if (project.windowDurationSec > 0) {
          project.windowDurationSec
        } else {
          DEFAULT_WINDOW_DURATION_SEC
        }
      val initialOverlap =
        if (project.overlapDurationSec >= 0 && project.framesPerWindow > 0) {
          project.overlapDurationSec
        } else {
          DEFAULT_OVERLAP_DURATION_SEC
        }
      val initialIncludeAudio = project.effectiveIncludeAudio()
      ProcessingConfigDialog(
        initialFramesPerWindow = initialFrames,
        initialWindowDurationSec = initialWindow,
        initialOverlapDurationSec = initialOverlap,
        initialIncludeAudio = initialIncludeAudio,
        supportsAudio = uiState.supportsAudio,
        onDismiss = { showConfigDialog = false },
        // Only reachable once something changed, because the dialog keeps its confirm button
        // disabled until then, so there is no "nothing to do" case to handle here.
        onApply = { frames, windowDuration, overlap, includeAudio ->
          showConfigDialog = false
          val updatedProject = project.copy { this.includeAudio = includeAudio }
          viewModel.reprocessProject(
            project = updatedProject,
            framesPerWindow = frames,
            windowDurationSec = windowDuration,
            overlapDurationSec = overlap,
            // Carried over rather than taken from the dialog: top-K is adjusted from the search
            // results, and it does not affect how the video is indexed.
            topK = project.topK.takeIf { it > 0 } ?: DEFAULT_TOP_K,
            includeAudio = includeAudio,
            searchQueryAfterProcess = searchQuery.trim().takeIf { it.isNotEmpty() },
          )
        },
      )
    }

    if (showTopKSheet) {
      TopKBottomSheet(
        initialTopK = project.topK.takeIf { it > 0 } ?: DEFAULT_TOP_K,
        onDismiss = { showTopKSheet = false },
        onApply = { topK ->
          showTopKSheet = false
          viewModel.setTopK(project, topK)
          // Top-K only changes how the already-indexed windows are ranked, so re-running the
          // current query is enough; the video does not need to be indexed again.
          //
          // The new value is threaded through explicitly rather than left for the search to pick
          // up from the view model state, so the search does not depend on setTopK having already
          // published its update.
          val query = searchQuery.trim()
          if (query.isNotEmpty()) {
            viewModel.searchForMoment(project.copy { this.topK = topK }, query)
          }
        },
      )
    }

    Column(modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
      // A search view consisting of an outlined text field for users to submit text queries
      // and a config button to tune processing parameters.
      val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = {
            onSearchQueryChange(it)
            viewModel.clearSearchResults()
          },
          enabled = !uiState.isSearching,
          modifier =
            Modifier.weight(1f)
              .drawBehind {
                drawIntoCanvas { canvas ->
                  val paint =
                    AndroidPaint().apply {
                      color = primaryContainerColor.copy(alpha = 0.85f).toArgb()
                      maskFilter = BlurMaskFilter(16.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                    }
                  val cornerRadius = size.height / 2f
                  canvas.nativeCanvas.drawRoundRect(
                    0f,
                    0f,
                    size.width,
                    size.height,
                    cornerRadius,
                    cornerRadius,
                    paint,
                  )
                }
              }
              .clearFocusOnKeyboardDismiss(),
          shape = CircleShape,
          placeholder = { Text(stringResource(R.string.videomomentfinder_search_placeholder)) },
          leadingIcon = {
            if (uiState.isSearching) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
              )
            } else {
              Icon(Icons.Rounded.Search, contentDescription = null)
            }
          },
          trailingIcon = {
            if (searchQuery.trim().isNotEmpty()) {
              IconButton(
                onClick = {
                  logButtonClick("videomomentfinder_clear_search_query")
                  onSearchQueryChange("")
                  viewModel.clearSearchResults()
                },
                enabled = !uiState.isSearching,
              ) {
                Icon(Icons.Outlined.Cancel, contentDescription = null)
              }
            }
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions =
            KeyboardActions(
              onSearch = {
                focusManager.clearFocus()
                if (searchQuery.trim().isNotEmpty()) {
                  viewModel.searchForMoment(project, searchQuery)
                }
              }
            ),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedContainerColor = MaterialTheme.colorScheme.surface,
              unfocusedContainerColor = MaterialTheme.colorScheme.surface,
              disabledContainerColor = MaterialTheme.colorScheme.surface,
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              unfocusedBorderColor = Color.Transparent,
              disabledBorderColor = Color.Transparent,
              disabledTextColor = MaterialTheme.colorScheme.onSurface,
              disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
              disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        FilledTonalIconButton(
          onClick = {
            logButtonClick("videomomentfinder_open_config_dialog")
            showConfigDialog = true
          },
          enabled = !uiState.isSearching,
          modifier = Modifier.size(56.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = stringResource(R.string.videomomentfinder_config_button_cd),
          )
        }
      }

      if (uiState.searchResults.isNotEmpty()) {
        // The whole label is the tap target rather than just the number: keeping it as one
        // translatable sentence lets other languages reorder it freely.
        AssistChip(
          onClick = {
            logButtonClick("videomomentfinder_open_top_k_sheet")
            showTopKSheet = true
          },
          label = {
            val topK = project.topK.takeIf { it > 0 } ?: DEFAULT_TOP_K
            Text(
              text =
                pluralStringResource(R.plurals.videomomentfinder_showing_top_results, topK, topK)
            )
          },
          trailingIcon = {
            Icon(
              imageVector = Icons.Rounded.Tune,
              contentDescription = null,
              modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
          },
          colors =
            AssistChipDefaults.assistChipColors(
              labelColor = Color.White,
              trailingIconContentColor = Color.White,
            ),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
          modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
        )

        // Renders the horizontal scrollable thumbnail list of moments found by the search
        // model.
        // Thumbnails size dynamically to maintain intrinsic aspect ratio for a fixed height.
        //
        val lazyListState = rememberLazyListState()
        LaunchedEffect(uiState.searchResults) { lazyListState.scrollToItem(0) }

        // Track animated scale per result item ID across lazy item recompositions and scrolling.
        val itemScales =
          remember(uiState.searchResults) {
            uiState.searchResults.associate { it.id to Animatable(0f) }
          }
        var isEntryAnimationFinished by remember(uiState.searchResults) { mutableStateOf(false) }

        LaunchedEffect(uiState.searchResults) {
          if (uiState.searchResults.isEmpty()) return@LaunchedEffect
          // Animate each thumbnail card with a staggered scale-in and subtle spring effect.
          uiState.searchResults.forEachIndexed { index, result ->
            launch {
              delay(index * 60L)
              itemScales[result.id]?.animateTo(
                targetValue = 1f,
                animationSpec =
                  spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                  ),
              )
            }
          }
          // After all items complete their staggered entrance, permanently finish the entry
          // animation so newly scrolled items or recycled items never replay an animation.
          delay(uiState.searchResults.size * 60L + 500L)
          isEntryAnimationFinished = true
        }

        LazyRow(
          state = lazyListState,
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(uiState.searchResults, key = { it.id }) { result ->
            val animatable = itemScales[result.id]

            // Cached so that recompositions (e.g. when the selection border changes) do not
            // allocate a new wrapper around the same underlying bitmap.
            val imageBitmap = remember(result.frameBitmap) { result.frameBitmap.asImageBitmap() }
            val aspectRatio =
              result.frameBitmap.width.toFloat() / result.frameBitmap.height.toFloat()
            val isLandscape = result.frameBitmap.width >= result.frameBitmap.height
            val defaultHeight =
              if (isLandscape) LANDSCAPE_PREVIEW_DEFAULT_HEIGHT else PORTRAIT_PREVIEW_DEFAULT_HEIGHT
            Box(
              modifier =
                Modifier.height(defaultHeight)
                  .width(defaultHeight * aspectRatio)
                  .graphicsLayer {
                    val scale = if (isEntryAnimationFinished) 1f else (animatable?.value ?: 1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = scale.coerceIn(0f, 1f)
                  }
                  .clip(RoundedCornerShape(8.dp))
                  .border(
                    if (uiState.selectedResultId == result.id) 4.dp else 2.dp,
                    if (uiState.selectedResultId == result.id) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      Color.White
                    },
                    RoundedCornerShape(8.dp),
                  )
                  .clickable {
                    logButtonClick("videomomentfinder_select_search_result")
                    onResultClick(result)
                  }
            ) {
              Image(
                bitmap = imageBitmap,
                contentDescription =
                  stringResource(R.string.videomomentfinder_search_result_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
              // Top overlay: Cosine similarity score (e.g. 0.45) formatted to 2 decimal places.
              if (result.similarityScore != null) {
                Box(
                  modifier =
                    Modifier.align(Alignment.TopCenter)
                      .fillMaxWidth()
                      .background(
                        brush =
                          Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                          )
                      )
                      .padding(top = 4.dp, bottom = 16.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text =
                      stringResource(
                        R.string.videomomentfinder_similarity_score_format,
                        result.similarityScore,
                      ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                  )
                }
              }

              // Bottom overlay: Video moment timestamp range (e.g. 00:02 - 00:06).
              Box(
                modifier =
                  Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                      brush =
                        Brush.verticalGradient(
                          colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
                    .padding(top = 16.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
              ) {
                val startMin = (result.startTimeMs / 1000) / 60
                val startSec = (result.startTimeMs / 1000) % 60
                val timeText =
                  if (result.startTimeMs == result.endTimeMs) {
                    "${startMin.toString().padStart(2, '0')}:${startSec.toString().padStart(2, '0')}"
                  } else {
                    val endMin = (result.endTimeMs / 1000) / 60
                    val endSec = (result.endTimeMs / 1000) % 60
                    "${startMin.toString().padStart(2, '0')}:${startSec.toString().padStart(2, '0')} - ${endMin.toString().padStart(2, '0')}:${endSec.toString().padStart(2, '0')}"
                  }
                Text(
                  text = timeText,
                  style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                  color = Color.White,
                )
              }
            }
          }
        }
      } else if (uiState.hasPerformedSearch && !uiState.isSearching) {
        Text(
          text = stringResource(R.string.videomomentfinder_no_moments_found),
          color = Color.White,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
          text = stringResource(R.string.videomomentfinder_tune_parameters_hint),
          color = Color.White,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
      }
      if (uiState.selectedResultId != null) {
        FilledTonalButton(
          onClick = {
            logButtonClick("videomomentfinder_save_or_edit_clip")
            onSaveOrEditClipClick()
          },
          modifier =
            Modifier.align(Alignment.CenterHorizontally)
              .padding(top = 16.dp)
              .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
              ),
        ) {
          Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.videomomentfinder_save_or_edit_this_clip))
        }
      }
    }
  }
}

@Composable
private fun BoxScope.ScrubberAndControls(
  project: VideoMomentProject,
  exoPlayer: ExoPlayer,
  uiState: VideoMomentFinderUiState,
  isPlaying: Boolean,
  isMuted: Boolean,
  progress: Float,
  onProgressChange: (Float) -> Unit,
  onUserPlaybackInteraction: () -> Unit = {},
  bottomPadding: Dp,
) {
  val totalDurationMs =
    playbackDurationMs(
      playerDurationMs = exoPlayer.duration,
      projectDurationMs = project.durationMs,
    )
  val currentPositionMs = (progress * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)

  /** Moves the playhead to [fraction] of the video, keeping the scrubber and the player in step. */
  fun seekToFraction(fraction: Float) {
    onUserPlaybackInteraction()
    val clampedFraction = fraction.coerceIn(0f, 1f)
    onProgressChange(clampedFraction)
    exoPlayer.seekTo((clampedFraction * totalDurationMs).toLong().coerceIn(0L, totalDurationMs))
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .align(Alignment.BottomCenter)
        .background(
          Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
        )
        .padding(bottom = bottomPadding, top = 8.dp)
  ) {
    // Control row with play/pause button, current/total time display, and mute button.
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      // Play / Pause toggle button.
      IconButton(
        onClick = {
          logButtonClick(if (isPlaying) "videomomentfinder_pause" else "videomomentfinder_play")
          onUserPlaybackInteraction()
          if (isPlaying) {
            exoPlayer.pause()
          } else {
            exoPlayer.play()
          }
        }
      ) {
        Icon(
          if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
          // Names the action the button performs, so it stays accurate as the state toggles.
          contentDescription =
            if (isPlaying) {
              stringResource(R.string.videomomentfinder_cd_pause)
            } else {
              stringResource(R.string.videomomentfinder_cd_play)
            },
          tint = Color.White,
        )
      }

      // Current playback time and total video length display.
      Text(
        text = "${formatTime(currentPositionMs)} / ${formatTime(totalDurationMs)}",
        style =
          MaterialTheme.typography.labelMedium.copy(
            // This stops numbers from "jumping around" when being updated.
            fontFeatureSettings = "tnum"
          ),
        color = Color.White,
      )

      // Mute / Unmute toggle button.
      IconButton(
        onClick = {
          logButtonClick(if (isMuted) "videomomentfinder_unmute" else "videomomentfinder_mute")
          exoPlayer.volume = if (isMuted) 1f else 0f
        }
      ) {
        Icon(
          if (isMuted) {
            Icons.AutoMirrored.Outlined.VolumeOff
          } else {
            Icons.AutoMirrored.Outlined.VolumeUp
          },
          contentDescription =
            if (isMuted) {
              stringResource(R.string.videomomentfinder_cd_unmute)
            } else {
              stringResource(R.string.videomomentfinder_cd_mute)
            },
          tint = Color.White,
        )
      }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondaryContainer

    // Custom video progress bar.
    Box(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(48.dp),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(
        modifier =
          Modifier.fillMaxWidth()
            .height(48.dp)
            .pointerInput(totalDurationMs) {
              detectTapGestures { offset -> seekToFraction(offset.x / size.width.toFloat()) }
            }
            .pointerInput(totalDurationMs) {
              detectDragGestures { change, _ ->
                seekToFraction(change.position.x / size.width.toFloat())
              }
            }
      ) {
        val trackHeight = 8.dp.toPx()
        val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val yOffset = (size.height - trackHeight) / 2f
        val currentX = size.width * progress

        // Base track (inactive progress)
        drawRoundRect(
          color = Color.LightGray,
          topLeft = Offset(0f, yOffset),
          size = Size(size.width, trackHeight),
          cornerRadius = cornerRadius,
        )

        // Active progress track
        if (currentX > 0f) {
          drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, yOffset),
            size = Size(currentX, trackHeight),
            cornerRadius = cornerRadius,
          )
        }

        // Search result marker overlays (rectangles covering merged intervals)
        if (totalDurationMs > 0) {
          val totalDuration = totalDurationMs.toFloat()
          val minMarkerWidth = 6.dp.toPx()
          val markerCornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

          for (interval in uiState.mergedIntervals) {
            val startProgress = (interval.startTimeMs.toFloat() / totalDuration).coerceIn(0f, 1f)
            val endProgress = (interval.endTimeMs.toFloat() / totalDuration).coerceIn(0f, 1f)

            val startX = size.width * startProgress
            val endX = size.width * endProgress

            val rectWidth = maxOf(minMarkerWidth, endX - startX)
            val markerX =
              (if (startX == endX) startX - minMarkerWidth / 2f else startX).coerceIn(
                0f,
                size.width - rectWidth,
              )

            val isSelected = uiState.selectedResultId in interval.resultIds
            val markerHeight =
              if (isSelected) trackHeight + 10.dp.toPx() else trackHeight + 6.dp.toPx()
            val markerY = (size.height - markerHeight) / 2f
            val markerColor = if (isSelected) primaryColor else secondaryColor

            // Draw filled rectangle covering the moment interval
            drawRoundRect(
              color = markerColor,
              topLeft = Offset(markerX, markerY),
              size = Size(rectWidth, markerHeight),
              cornerRadius = markerCornerRadius,
            )

            // If selected, draw outline highlight for clear visibility
            if (isSelected) {
              drawRoundRect(
                color = Color.White,
                topLeft = Offset(markerX, markerY),
                size = Size(rectWidth, markerHeight),
                cornerRadius = markerCornerRadius,
                style = Stroke(width = 1.5.dp.toPx()),
              )
            }
          }
        }

        // Scrubber drag handle
        val handleWidth = 4.dp.toPx()
        val handleHeight = trackHeight + 16.dp.toPx()
        val handleY = (size.height - handleHeight) / 2f
        val handleX = (currentX - handleWidth / 2f).coerceIn(0f, size.width - handleWidth)

        val handleCornerRadius = CornerRadius(handleWidth / 2f, handleWidth / 2f)

        drawRoundRect(
          color = Color.White,
          topLeft = Offset(handleX, handleY),
          size = Size(handleWidth, handleHeight),
          cornerRadius = handleCornerRadius,
        )
        drawRoundRect(
          color = Color.Gray,
          topLeft = Offset(handleX, handleY),
          size = Size(handleWidth, handleHeight),
          cornerRadius = handleCornerRadius,
          style = Stroke(width = 1.dp.toPx()),
        )
      }
    }
  }
}

@Composable
private fun SavedMomentsDrawer(
  project: VideoMomentProject,
  bottomPadding: Dp,
  onClipClick: (VideoClip) -> Unit,
  onDeleteClip: (VideoClip) -> Unit,
  onClose: () -> Unit,
) {
  val shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
  Surface(
    modifier = Modifier.fillMaxHeight().widthIn(max = 400.dp).fillMaxWidth(0.85f),
    shape = shape,
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shadowElevation = 16.dp,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
      // Top bar of the drawer
      Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.videomomentfinder_tab_moments),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
          onClick = {
            logButtonClick("videomomentfinder_close_saved_moments_drawer")
            onClose()
          },
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close_icon),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      // Group saved clips by search query (e.g., the search prompt used when saving the moment).
      val groupedClips =
        remember(project.savedClipsList) {
          project.savedClipsList.groupBy { it.searchQuery }.toSortedMap()
        }

      // Vertically scrollable 2-column grid of saved clip thumbnails grouped by search query.
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        for ((query, clips) in groupedClips) {
          // Search query section header spanning both columns
          item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
              text =
                if (query.isEmpty()) {
                  stringResource(R.string.videomomentfinder_no_query_placeholder)
                } else {
                  "\"$query\""
                },
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
          }
          items(clips, key = { it.id }) { clip ->
            ClipThumbnailCard(
              clip = clip,
              onClick = {
                logButtonClick("videomomentfinder_open_saved_clip_from_drawer")
                onClipClick(clip)
              },
              onDelete = { onDeleteClip(clip) },
            )
          }
        }
      }
    }
  }
}
