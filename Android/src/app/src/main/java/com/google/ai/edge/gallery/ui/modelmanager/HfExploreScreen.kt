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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.huggingface.isCommunityRecommended
import com.google.ai.edge.gallery.huggingface.isDeviceCompatible
import com.google.ai.edge.gallery.huggingface.modelName
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.common.EmptyStateButtonConfig
import com.google.ai.edge.gallery.ui.common.RotationalLoader

private val SORT_OPTIONS: List<Pair<HfSortOptionProto, Int>> =
  listOf(
    HfSortOptionProto.HF_SORT_OPTION_TRENDING to R.string.hf_explore_sort_trending,
    HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS to R.string.hf_explore_sort_downloads,
    HfSortOptionProto.HF_SORT_OPTION_LIKES to R.string.hf_explore_sort_likes,
    HfSortOptionProto.HF_SORT_OPTION_RECENT to R.string.hf_explore_sort_recent,
  )

@Composable
fun HfExploreScreen(
  onNavigateUp: () -> Unit,
  onOpenUrlImportDialog: () -> Unit,
  onModelCardSelected: (HfModelItemProto) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HfExploreViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(uiState.selectedModelForDetails) {
    val selectedModel = uiState.selectedModelForDetails ?: return@LaunchedEffect
    onModelCardSelected(selectedModel)
    viewModel.onModelDetailsShown()
  }

  Dialog(
    onDismissRequest = onNavigateUp,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    HfExploreContent(
      uiState = uiState,
      onNavigateUp = onNavigateUp,
      onOpenUrlImportDialog = onOpenUrlImportDialog,
      onSearchQueryChanged = viewModel::onSearchQueryChanged,
      onSortOptionSelected = viewModel::onSortOptionSelected,
      onRetry = viewModel::refreshModels,
      onModelCardClicked = viewModel::selectModelCard,
      modifier = modifier,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfExploreContent(
  uiState: HfExploreUiState,
  onNavigateUp: () -> Unit,
  onOpenUrlImportDialog: () -> Unit,
  onSearchQueryChanged: (String) -> Unit,
  onSortOptionSelected: (HfSortOptionProto) -> Unit,
  onRetry: () -> Unit,
  onModelCardClicked: (HfModelItemProto) -> Unit,
  modifier: Modifier = Modifier,
) {
  val keyboardController = LocalSoftwareKeyboardController.current

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.hf_explore_title),
        leftAction =
          AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = onNavigateUp),
        rightAction =
          AppBarAction(
            actionType = AppBarActionType.IMPORT_FROM_URL,
            actionFn = onOpenUrlImportDialog,
          ),
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(top = innerPadding.calculateTopPadding())
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Search field.
        OutlinedTextField(
          value = uiState.searchQuery,
          onValueChange = onSearchQueryChanged,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          placeholder = { Text(stringResource(R.string.hf_explore_search_placeholder)) },
          leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
          trailingIcon = {
            if (uiState.searchQuery.isNotEmpty()) {
              IconButton(onClick = { onSearchQueryChanged("") }) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cd_clear_search_button),
                )
              }
            }
          },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
          singleLine = true,
          shape = RoundedCornerShape(24.dp),
        )

        // Sort chips.
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for ((option, labelRes) in SORT_OPTIONS) {
            FilterChip(
              selected = uiState.selectedSort == option,
              onClick = { onSortOptionSelected(option) },
              label = { Text(stringResource(labelRes)) },
            )
          }
        }

        // Model cards or loading/empty state.
        when {
          uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              RotationalLoader(size = 32.dp)
            }
          }
          uiState.hasError || uiState.models.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              EmptyState(
                titleResId = R.string.hf_explore_title,
                descriptionResId =
                  if (uiState.hasError) {
                    R.string.hf_explore_error_loading
                  } else {
                    R.string.hf_explore_empty_results
                  },
                icon =
                  if (uiState.hasError) {
                    Icons.Rounded.ErrorOutline
                  } else {
                    Icons.Rounded.SearchOff
                  },
                buttonConfig =
                  if (uiState.hasError) {
                    EmptyStateButtonConfig(
                      buttonLabelResId = R.string.hf_explore_retry,
                      onButtonClick = onRetry,
                    )
                  } else {
                    null
                  },
              )
            }
          }
          else -> {
            HfExploreModelList(
              models = uiState.models,
              contentPadding =
                PaddingValues(top = 8.dp, bottom = innerPadding.calculateBottomPadding() + 24.dp),
              onModelCardClicked = onModelCardClicked,
            )
          }
        }
      }

      // Gradient overlay at the bottom matching GlobalModelManager.
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(innerPadding.calculateBottomPadding())
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
              )
            )
            .align(Alignment.BottomCenter)
      )

      if (uiState.isFetchingDetails) {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
              .pointerInput(Unit) {},
          contentAlignment = Alignment.Center,
        ) {
          RotationalLoader(size = 32.dp)
        }
      }
    }
  }
}

@Composable
private fun HfExploreModelList(
  models: List<HfModelItemProto>,
  contentPadding: PaddingValues,
  onModelCardClicked: (HfModelItemProto) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  LazyColumn(
    modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    state = listState,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = contentPadding,
  ) {
    items(models, key = { it.id }) { model ->
      HfExploreModelCard(model = model, onClick = { onModelCardClicked(model) })
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HfExploreModelCard(
  model: HfModelItemProto,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isCompatible = model.isDeviceCompatible()
  val isRecommended = model.isCommunityRecommended()
  val authorName = model.author.ifEmpty { model.id.substringBefore("/", "") }

  Card(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = model.modelName,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (authorName.isNotEmpty()) {
          Text(
            text = authorName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (isRecommended) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = stringResource(R.string.hf_explore_recommended_badge),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }

        Surface(
          color =
            if (isCompatible) {
              MaterialTheme.colorScheme.secondaryContainer
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            },
          shape = RoundedCornerShape(6.dp),
        ) {
          Text(
            text =
              stringResource(
                if (isCompatible) {
                  R.string.hf_explore_compatible_badge
                } else {
                  R.string.hf_explore_incompatible_badge
                }
              ),
            style = MaterialTheme.typography.labelSmall,
            color =
              if (isCompatible) {
                MaterialTheme.colorScheme.onSecondaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }

      ModelStatsRow(
        downloads = model.downloads,
        likes = model.likes,
        lastModified = model.lastModified,
      )
    }
  }
}
