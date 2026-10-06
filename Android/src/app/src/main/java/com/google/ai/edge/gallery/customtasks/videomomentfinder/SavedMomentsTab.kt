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

import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.proto.VideoClip
import com.google.ai.edge.gallery.ui.common.EmptyState

@Composable
fun SavedMomentsTab(
  viewModel: VideoMomentFinderViewModel,
  onClipClick: (SavedMomentClipItem) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  var selectedGroup by remember { mutableStateOf<SavedMomentsQueryGroup?>(null) }

  // Group all saved clips across projects by search query, sort the groups alphabetically, and
  // calculate the distinct project count for each query group.
  val queryGroups =
    remember(uiState.projects) {
      val allClipsWithProject =
        uiState.projects.flatMap { project ->
          project.savedClipsList.map { clip ->
            SavedMomentClipItem(
              clip = clip,
              videoDisplayName = project.displayName,
              projectId = project.id,
            )
          }
        }
      val grouped = allClipsWithProject.groupBy { it.clip.searchQuery }
      grouped.toSortedMap().map { (query, items) ->
        val distinctProjectCount = items.map { it.projectId }.distinct().size
        SavedMomentsQueryGroup(
          searchQuery = query,
          clips = items,
          videoCount = distinctProjectCount,
        )
      }
    }

  if (queryGroups.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      // Display empty state placeholder when no saved clips exist across all projects.
      EmptyState(
        icon = Icons.Outlined.VideoLibrary,
        titleResId = R.string.videomomentfinder_tab_moments,
        descriptionResId = R.string.videomomentfinder_saved_moments_empty_description,
      )
    }
  } else {
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // First row: Feature overview and empty state description spanning both columns without icon.
      item(span = { GridItemSpan(maxLineSpan) }) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          contentAlignment = Alignment.Center,
        ) {
          EmptyState(
            titleResId = R.string.videomomentfinder_tab_moments,
            descriptionResId = R.string.videomomentfinder_saved_moments_empty_description,
            horizontalPadding = 8.dp,
          )
        }
      }

      items(queryGroups, key = { it.searchQuery }) { group ->
        key(group.clips) {
          SavedMomentGroupCard(
            group = group,
            onClick = {
              logButtonClick("videomomentfinder_open_moment_group")
              selectedGroup = group
            },
            onDeleteAllClips = { viewModel.deleteClips(group.clips) },
          )
        }
      }
    }
  }

  selectedGroup?.let { group ->
    val currentGroup = queryGroups.find { it.searchQuery == group.searchQuery }
    if (currentGroup != null) {
      SavedMomentsQueryBottomSheet(
        group = currentGroup,
        onClipClick = { item ->
          logButtonClick("videomomentfinder_open_saved_clip_from_group")
          selectedGroup = null
          onClipClick(item)
        },
        onDeleteClip = { item -> viewModel.deleteClips(listOf(item)) },
        onDismiss = { selectedGroup = null },
      )
    } else {
      selectedGroup = null
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedMomentGroupCard(
  group: SavedMomentsQueryGroup,
  onClick: () -> Unit,
  onDeleteAllClips: () -> Unit,
) {
  val displayClips = remember(group.clips) { group.clips.take(3) }
  var isRotated by remember { mutableStateOf(false) }
  var showMenu by remember { mutableStateOf(false) }
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }

  // A group can be saved without a query, in which case a placeholder stands in for it. Both the
  // caption and the long-press menu name the query, so they share one rendering of it.
  val displayQuery =
    if (group.searchQuery.isEmpty()) {
      stringResource(R.string.videomomentfinder_no_query_placeholder)
    } else {
      "\"${group.searchQuery}\""
    }

  LaunchedEffect(Unit) { isRotated = true }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1:1 Aspect ratio container for stacked thumbnail cards with pill badge
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
      contentAlignment = Alignment.Center,
    ) {
      // Stack up to 3 thumbnail cards with 15-degree rotation steps.
      // Render in reverse order so the first clip (0 degrees) is drawn last (on top).
      for (index in (displayClips.size - 1) downTo 0) {
        val clip = displayClips[index].clip
        key(clip.id) { StackedThumbnailCard(clip = clip, index = index, isRotated = isRotated) }
      }

      // Pill at the bottom right corner showing the count of clips for this query
      Box(
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .offset(x = (-8).dp, y = (-8).dp)
            .width(28.dp)
            .background(color = MaterialTheme.colorScheme.inverseSurface, shape = CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "${group.clips.size}",
          modifier = Modifier.padding(vertical = 1.dp),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.inverseOnSurface,
          textAlign = TextAlign.Center,
        )
      }

      DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        DropdownMenuItem(
          text = {
            Text(
              stringResource(R.string.videomomentfinder_delete_all_clips_for_query, displayQuery),
              // The query is user supplied and can be arbitrarily long, so it is clipped here the
              // same way the card caption below clips it.
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          leadingIcon = {
            Icon(imageVector = Icons.Rounded.DeleteSweep, contentDescription = null)
          },
          onClick = {
            logButtonClick("videomomentfinder_delete_all_clips_start")
            showMenu = false
            showConfirmDeleteDialog = true
          },
        )
      }
    }

    // Query string in quotes and video search count description below the box
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = displayQuery,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text =
          pluralStringResource(
            R.plurals.videomomentfinder_searched_in_videos,
            group.videoCount,
            group.videoCount,
          ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }

  if (showConfirmDeleteDialog) {
    ConfirmDeleteDialog(
      message = stringResource(R.string.videomomentfinder_confirm_delete_all_clips),
      onConfirm = {
        logButtonClick("videomomentfinder_delete_all_clips_confirm")
        showConfirmDeleteDialog = false
        onDeleteAllClips()
      },
      onDismiss = { showConfirmDeleteDialog = false },
      onCancelButtonClick = { logButtonClick("videomomentfinder_delete_all_clips_cancel") },
    )
  }
}

@Composable
private fun StackedThumbnailCard(clip: VideoClip, index: Int, isRotated: Boolean) {
  val targetAngle = index * 15f
  val rotationAngle by
    animateFloatAsState(
      targetValue = if (isRotated) targetAngle else 0f,
      animationSpec = tween(durationMillis = 350, delayMillis = 300, easing = FastOutSlowInEasing),
      label = "CardRotation_$index",
    )

  ThumbnailCard(
    relativeThumbnailPath = clip.relativeScreenshotPath,
    showOverlay = false,
    modifier =
      Modifier.fillMaxSize(0.8f)
        .aspectRatio(1f)
        .graphicsLayer(rotationZ = rotationAngle, transformOrigin = TransformOrigin.Center),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedMomentsQueryBottomSheet(
  group: SavedMomentsQueryGroup,
  onClipClick: (SavedMomentClipItem) -> Unit,
  onDeleteClip: (SavedMomentClipItem) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header: Query title and results count
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text =
            if (group.searchQuery.isEmpty()) {
              stringResource(R.string.videomomentfinder_no_query_placeholder)
            } else {
              "\"${group.searchQuery}\""
            },
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        Text(
          text =
            pluralStringResource(
              R.plurals.videomomentfinder_results_count,
              group.clips.size,
              group.clips.size,
            ),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }

      // 2-column grid of saved clips
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        items(group.clips, key = { it.clip.id }) { item ->
          ClipThumbnailCard(
            clip = item.clip,
            videoDisplayName = item.videoDisplayName,
            onClick = { onClipClick(item) },
            onDelete = { onDeleteClip(item) },
          )
        }
      }
    }
  }
}
