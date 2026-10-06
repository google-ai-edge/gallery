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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.common.EmptyStateButtonConfig
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Maximum video duration allowed.
const val MAX_VIDEO_DURATION_MS = 5 * 60 * 1000L

@Composable
fun VideosTab(viewModel: VideoMomentFinderViewModel) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val projects = uiState.projects
  var showDurationLimitDialog by remember { mutableStateOf(false) }
  var showUnsupportedVideoDialog by remember { mutableStateOf(false) }
  // Held aside rather than imported straight away: the user configures how the video will be
  // processed first, and the import only starts once they confirm.
  // Saveable, not plain remember: the configuration dialog below stays open across a rotation, and
  // losing the pick would send the user back to the photo picker. Uri is Parcelable.
  var pendingVideoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }
  val alreadyImportedMessage = stringResource(R.string.videomomentfinder_video_already_imported)
  val alreadyImportedOpenLabel =
    stringResource(R.string.videomomentfinder_video_already_imported_open)

  val photoPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia(),
      onResult = { uri ->
        if (uri != null) {
          coroutineScope.launch {
            // Checked before anything else so the user is not asked to configure processing for a
            // video that would only produce a second copy of an existing project.
            val existingProject = viewModel.findProjectImportedFrom(uri)
            if (existingProject != null) {
              val result =
                snackbarHostState.showSnackbar(
                  message = alreadyImportedMessage,
                  actionLabel = alreadyImportedOpenLabel,
                  duration = SnackbarDuration.Short,
                )
              if (result == SnackbarResult.ActionPerformed) {
                logButtonClick("videomomentfinder_open_already_imported_video")
                // Re-resolved because the project may have been deleted or updated while the
                // snackbar was showing.
                viewModel.uiState.value.projects
                  .find { it.id == existingProject.id }
                  ?.let { viewModel.selectProject(it) }
              }
              return@launch
            }
            val durationMs = withContext(Dispatchers.IO) { getVideoDurationMs(context, uri) }
            if (durationMs > MAX_VIDEO_DURATION_MS) {
              showDurationLimitDialog = true
              return@launch
            }
            // Checked before import rather than left to fail during indexing: a video this phone
            // cannot decode (typically 8K on hardware that tops out at 4K) otherwise shows up as
            // undecodable frames, which is indistinguishable from a corrupt file.
            val decoderSupport =
              withContext(Dispatchers.IO) { checkVideoDecoderSupport(context, uri) }
            if (decoderSupport is VideoDecoderSupport.Unsupported) {
              val format = decoderSupport.format
              logErrorToFirebase(
                GalleryEvent.BUTTON_CLICKED,
                "videomomentfinder_add_video_unsupported_decoder",
                "${format.mimeType} ${format.width}x${format.height}",
              )
              showUnsupportedVideoDialog = true
              return@launch
            }
            pendingVideoUri = uri
          }
        }
      },
    )

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (projects.isEmpty()) {
      EmptyState(
        icon = ImageVector.vectorResource(R.drawable.video_search),
        titleResId = R.string.videomomentfinder_feature_name,
        descriptionResId = R.string.videomomentfinder_project_list_empty_description,
        buttonConfig =
          EmptyStateButtonConfig(
            buttonLabelResId = R.string.videomomentfinder_add_video_button,
            buttonIcon = Icons.Outlined.FileUpload,
            onButtonClick = {
              logButtonClick("videomomentfinder_add_video_empty_state")
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
              )
            },
          ),
      )
    } else {
      ProjectListScreen(
        projects = projects,
        onProjectClick = { viewModel.selectProject(it) },
        onDeleteClick = { viewModel.deleteProject(it) },
        onClickAdd = {
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
          )
        },
      )
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
    )
  }

  // Configuration dialog shown between picking a video and importing it, so the first indexing
  // pass already uses the settings the user wants instead of running once on the defaults.
  val videoToConfigure = pendingVideoUri
  if (videoToConfigure != null) {
    ProcessingConfigDialog(
      supportsAudio = uiState.supportsAudio,
      confirmLabelResId = R.string.videomomentfinder_config_start,
      // Starting with the defaults untouched is a normal choice for a new video, so the button
      // must not depend on the user having changed something first.
      confirmEnabledWhenUnchanged = true,
      onDismiss = { pendingVideoUri = null },
      onApply = { frames, windowDuration, overlap, includeAudio ->
        pendingVideoUri = null
        viewModel.importVideo(
          uri = videoToConfigure,
          framesPerWindow = frames,
          windowDurationSec = windowDuration,
          overlapDurationSec = overlap,
          includeAudio = includeAudio,
        )
      },
    )
  }

  // Dialog shown when the selected video exceeds the duration limit.
  if (showDurationLimitDialog) {
    AlertDialog(
      onDismissRequest = { showDurationLimitDialog = false },
      title = { Text(stringResource(R.string.videomomentfinder_video_duration_limit_title)) },
      text = { Text(stringResource(R.string.videomomentfinder_video_duration_limit_message)) },
      confirmButton = {
        Button(
          onClick = {
            logButtonClick("videomomentfinder_video_duration_limit_ok")
            showDurationLimitDialog = false
          },
          contentPadding = SMALL_BUTTON_CONTENT_PADDING,
        ) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // Dialog shown when this phone has no decoder for the selected video's codec and resolution.
  if (showUnsupportedVideoDialog) {
    AlertDialog(
      onDismissRequest = { showUnsupportedVideoDialog = false },
      title = { Text(stringResource(R.string.videomomentfinder_video_unsupported_title)) },
      text = { Text(stringResource(R.string.videomomentfinder_video_unsupported_message)) },
      confirmButton = {
        Button(
          onClick = {
            logButtonClick("videomomentfinder_video_unsupported_ok")
            showUnsupportedVideoDialog = false
          },
          contentPadding = SMALL_BUTTON_CONTENT_PADDING,
        ) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}
