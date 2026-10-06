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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.ui.common.EmptyState

@Composable
fun ProjectListScreen(
  projects: List<VideoMomentProject>,
  onProjectClick: (VideoMomentProject) -> Unit,
  onDeleteClick: (VideoMomentProject) -> Unit,
  onClickAdd: () -> Unit,
) {
  val projectToDelete = remember { mutableStateOf<VideoMomentProject?>(null) }

  LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Header: Feature overview and empty state description spanning both columns.
    item(span = { GridItemSpan(2) }) {
      Box(modifier = Modifier.padding(bottom = 8.dp)) {
        EmptyState(
          icon = ImageVector.vectorResource(R.drawable.video_search),
          titleResId = R.string.videomomentfinder_feature_name,
          descriptionResId = R.string.videomomentfinder_project_list_empty_description,
          buttonConfig = null,
          horizontalPadding = 8.dp,
          iconSize = 32.dp,
          iconTint = MaterialTheme.colorScheme.primary,
        )
      }
    }

    // First Grid Item: Persistent button to import and add another video.
    item {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .aspectRatio(1f)
              .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp))
              .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
              )
              .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
              )
              .clip(RoundedCornerShape(16.dp))
              .clickable {
                logButtonClick("videomomentfinder_add_video")
                onClickAdd()
              },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.videomomentfinder_add_another_video),
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        Text(
          text = stringResource(R.string.videomomentfinder_add_another_video),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }

    // Grid Items: List of existing video projects.
    items(projects) { project ->
      ProjectCard(
        project = project,
        onClick = {
          logButtonClick("videomomentfinder_open_project")
          onProjectClick(project)
        },
        onDeleteClick = { projectToDelete.value = it },
      )
    }
  }

  // Dialog: Confirm deletion of a project.
  val toDelete = projectToDelete.value
  if (toDelete != null) {
    ConfirmDeleteProjectDialog(
      onConfirm = {
        logButtonClick("videomomentfinder_delete_project_confirm")
        onDeleteClick(toDelete)
        projectToDelete.value = null
      },
      onDismiss = { projectToDelete.value = null },
      onCancelButtonClick = { logButtonClick("videomomentfinder_delete_project_cancel") },
    )
  }
}

@Composable
fun ProjectCard(
  project: VideoMomentProject,
  onClick: () -> Unit,
  onDeleteClick: (VideoMomentProject) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Video thumbnail container supporting click to open and long-press for menu.
    ThumbnailCard(
      relativeThumbnailPath = project.relativeThumbnailPath,
      durationInSeconds = project.durationMs / 1000L,
      onClick = onClick,
      onLongClick = { expanded = true },
    ) {
      // Context menu displayed upon long pressing the project thumbnail.
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
          text = { Text(stringResource(R.string.delete)) },
          leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) },
          onClick = {
            logButtonClick("videomomentfinder_delete_project_start")
            expanded = false
            onDeleteClick(project)
          },
        )
      }
    }

    // Project title label rendered below the thumbnail.
    Text(
      text = project.displayName,
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
