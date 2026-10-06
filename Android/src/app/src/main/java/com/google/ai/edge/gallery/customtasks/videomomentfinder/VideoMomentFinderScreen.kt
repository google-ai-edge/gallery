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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.VideoClip
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

data class ClipEditorArgs(
  val project: VideoMomentProject,
  val selectedResult: MomentSearchResult? = null,
  val savedClip: VideoClip? = null,
  val searchQuery: String = "",
)

@Composable
fun VideoMomentFinderScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setTopBarVisible: (Boolean) -> Unit = {},
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  viewModel: VideoMomentFinderViewModel = hiltViewModel(),
  showOnboarding: Boolean = true,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initStatus by model.initStatusFlow.collectAsStateWithLifecycle()

  if (showOnboarding) {
    VideoMomentFinderOnboardingDialog()
  }

  LaunchedEffect(model.instance) {
    viewModel.setSemanticRetrievalService(model.instance as? SemanticRetrievalService)
  }

  val selectedProject = uiState.selectedProject
  var selectedTab by remember { mutableIntStateOf(0) }
  var clipEditorArgs by remember { mutableStateOf<ClipEditorArgs?>(null) }

  LaunchedEffect(selectedProject, clipEditorArgs) {
    setTopBarVisible(selectedProject == null && clipEditorArgs == null)
  }

  DisposableEffect(Unit) { onDispose { setTopBarVisible(true) } }

  val isModelInitialized = initStatus is Model.InitializationStatus.Initialized
  // Fade in the main screen once the model is initialized.
  AnimatedContent(
    targetState = isModelInitialized,
    transitionSpec = {
      fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(200))
    },
    modifier = Modifier.fillMaxSize(),
    label = "ModelInitializationTransition",
  ) { initialized ->
    if (!initialized) {
      // Show a loading indicator before the model is initialized.
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.size(24.dp),
        )
      }
    } else {
      // Main screen.
      Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          bottomBar = {
            // Bottom navigation bar to switch between two tabs.
            NavigationBar {
              // Tab 0: Videos.
              NavigationBarItem(
                icon = {
                  Icon(
                    ImageVector.vectorResource(R.drawable.video_search),
                    contentDescription = stringResource(R.string.videomomentfinder_tab_videos),
                  )
                },
                label = { Text(stringResource(R.string.videomomentfinder_tab_videos)) },
                selected = selectedTab == 0,
                onClick = {
                  logButtonClick("videomomentfinder_tab_videos")
                  selectedTab = 0
                },
              )
              // Tab 1: Moments.
              NavigationBarItem(
                icon = {
                  Icon(
                    Icons.Outlined.VideoLibrary,
                    contentDescription = stringResource(R.string.videomomentfinder_tab_moments),
                  )
                },
                label = { Text(stringResource(R.string.videomomentfinder_tab_moments)) },
                selected = selectedTab == 1,
                onClick = {
                  logButtonClick("videomomentfinder_tab_moments")
                  selectedTab = 1
                },
              )
            }
          },
        ) { innerPadding ->
          Box(
            modifier =
              Modifier.fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .background(
                  brush =
                    Brush.verticalGradient(
                      colors =
                        listOf(
                          MaterialTheme.colorScheme.surface,
                          MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                )
          ) {
            // Smooth vertical slide and crossfade transition when switching bottom navigation tabs.
            AnimatedContent(
              targetState = selectedTab,
              transitionSpec = {
                slideInVertically(animationSpec = tween(delayMillis = 50)) { 20 } +
                  fadeIn(animationSpec = tween(delayMillis = 50)) togetherWith
                  fadeOut(animationSpec = tween(100))
              },
            ) { tabIndex ->
              when (tabIndex) {
                0 -> {
                  VideosTab(viewModel = viewModel)
                }
                1 -> {
                  SavedMomentsTab(
                    viewModel = viewModel,
                    onClipClick = { item ->
                      val parentProject = uiState.projects.find { it.id == item.projectId }
                      if (parentProject != null) {
                        clipEditorArgs =
                          ClipEditorArgs(
                            project = parentProject,
                            savedClip = item.clip,
                            searchQuery = item.clip.searchQuery,
                          )
                      }
                    },
                  )
                }
              }
            }
          }
        }

        // Cache the active project state. This pattern guarantees the data instantly
        // populates the frame being painted for the `enter` animation as well as remaining
        // safely un-cleared for the `exit` animation when selectedProject becomes null.
        val projectToShow = remember { mutableStateOf(selectedProject) }
        if (selectedProject != null) {
          projectToShow.value = selectedProject
        }

        AnimatedVisibility(
          visible = selectedProject != null,
          enter = slideInVertically(initialOffsetY = { it }),
          exit = slideOutVertically(targetOffsetY = { it }),
        ) {
          val project = projectToShow.value
          if (project != null) {
            ProjectDetailsScreen(
              project = project,
              viewModel = viewModel,
              bottomPadding = bottomPadding,
              setCustomNavigateUpCallback = setCustomNavigateUpCallback,
              onBackClick = { viewModel.closeSelectedProject() },
              onOpenClipEditor = { savedClip, selectedResult, query ->
                clipEditorArgs =
                  ClipEditorArgs(
                    project = project,
                    savedClip = savedClip,
                    selectedResult = selectedResult,
                    searchQuery = query,
                  )
              },
            )
          }
        }

        // Cache the active editing clip args state for smooth exit animation.
        val editorArgsToShow = remember { mutableStateOf(clipEditorArgs) }
        if (clipEditorArgs != null) {
          editorArgsToShow.value = clipEditorArgs
        }

        AnimatedVisibility(
          visible = clipEditorArgs != null,
          enter = slideInVertically(initialOffsetY = { it }),
          exit = slideOutVertically(targetOffsetY = { it }),
        ) {
          val args = editorArgsToShow.value
          if (args != null) {
            ClipEditorScreen(
              project = args.project,
              selectedResult = args.selectedResult,
              savedClip = args.savedClip,
              searchQuery = args.searchQuery,
              viewModel = viewModel,
              bottomPadding = bottomPadding,
              onClose = { clipEditorArgs = null },
            )
          }
        }
      }
    }
  }
}
