/*
 * Copyright 2025 Google LLC
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

package com.appswithlove.ai.ui.home

// import androidx.compose.ui.tooling.preview.Preview
// import com.appswithlove.ai.ui.theme.GalleryTheme
// import com.appswithlove.ai.ui.preview.PreviewModelManagerViewModel
import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appswithlove.ai.R
import com.appswithlove.ai.data.Category
import com.appswithlove.ai.data.CategoryInfo
import com.appswithlove.ai.data.Task
import com.appswithlove.ai.ui.common.TaskIcon
import com.appswithlove.ai.ui.common.rememberDelayedAnimationProgress
import com.appswithlove.ai.ui.common.tos.AppTosDialog
import com.appswithlove.ai.ui.common.tos.TosViewModel
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import com.appswithlove.ai.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreen"
private const val TASK_COUNT_ANIMATION_DURATION = 250
private const val TASK_LIST_ANIMATION_START = 410L
private const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
private const val TASK_CARD_ANIMATION_DURATION = 600
private const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
private const val CONTENT_COMPOSABLES_OFFSET_Y = 16

private val PREDEFINED_CATEGORY_ORDER = listOf(Category.LLM.id, Category.EXPERIMENTAL.id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  modifier: Modifier = Modifier,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  var tasks = uiState.tasks

  val categoryMap: Map<String, CategoryInfo> =
    remember(tasks) { tasks.associateBy { it.category.id }.mapValues { it.value.category } }
  val sortedCategories =
    remember(categoryMap) {
      categoryMap.keys
        .toList()
        .sortedWith { a, b ->
          val indexA = PREDEFINED_CATEGORY_ORDER.indexOf(a)
          val indexB = PREDEFINED_CATEGORY_ORDER.indexOf(b)
          if (indexA != -1 && indexB != -1) {
            indexA.compareTo(indexB)
          } else if (indexA != -1) {
            -1
          } else if (indexB != -1) {
            1
          } else {
            val ca = categoryMap[a]!!
            val cb = categoryMap[b]!!
            val caLabel = getCategoryLabel(context = context, category = ca)
            val cbLabel = getCategoryLabel(context = context, category = cb)
            caLabel.compareTo(cbLabel)
          }
        }
        .map { categoryMap[it]!! }
    }

  if (!showTosDialog) {
    var loadingModelAllowlistDelayed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.loadingModelAllowlist) {
      if (uiState.loadingModelAllowlist) {
        delay(200)
        if (uiState.loadingModelAllowlist) {
          loadingModelAllowlistDelayed = true
        }
      } else {
        loadingModelAllowlistDelayed = false
      }
    }

    if (loadingModelAllowlistDelayed) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(
          stringResource(R.string.loading_model_list),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    if (!loadingModelAllowlistDelayed && !uiState.loadingModelAllowlist) {
      Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
      ) {
        Column(
          modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
          // Large title
          Text(
            text = stringResource(R.string.home_app_title),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
          )

          var selectedCategoryIndex by remember { mutableIntStateOf(0) }

          val pagerState = rememberPagerState(pageCount = { sortedCategories.size })
          LaunchedEffect(pagerState.settledPage) {
            selectedCategoryIndex = pagerState.settledPage
          }
          if (sortedCategories.size > 1) {
            CategoryTabHeader(
              sortedCategories = sortedCategories,
              selectedIndex = selectedCategoryIndex,
              enableAnimation = false,
              onCategorySelected = { index ->
                selectedCategoryIndex = index
                scope.launch { pagerState.animateScrollToPage(page = index) }
              },
            )
          }

          TaskList(
            pagerState = pagerState,
            sortedCategories = sortedCategories,
            tasksByCategories = uiState.tasksByCategory,
            enableAnimation = false,
            navigateToTaskScreen = navigateToTaskScreen,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
          )

          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }
  }

  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  if (uiState.loadingModelAllowlistError.isNotEmpty()) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = { Text(uiState.loadingModelAllowlistError) },
      text = { Text(stringResource(R.string.home_loading_error_message)) },
      onDismissRequest = { modelManagerViewModel.loadModelAllowlist() },
      confirmButton = {
        TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) {
          Text(stringResource(R.string.home_retry))
        }
      },
      dismissButton = {
        TextButton(onClick = { modelManagerViewModel.clearLoadModelAllowlistError() }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

@Composable
private fun CategoryTabHeader(
  sortedCategories: List<CategoryInfo>,
  selectedIndex: Int,
  enableAnimation: Boolean,
  onCategorySelected: (Int) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  LazyRow(
    state = listState,
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 32.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item(key = "spacer_start") { Spacer(modifier = Modifier.width(8.dp)) }
    itemsIndexed(items = sortedCategories) { index, category ->
      Row(
        modifier =
          Modifier.height(40.dp)
            .clip(CircleShape)
            .background(
              color =
                if (selectedIndex == index) MaterialTheme.customColors.tabHeaderBgColor
                else Color.Transparent
            )
            .clickable {
              onCategorySelected(index)

              // Scroll to clicked item when the item is not fully inside view.
              scope.launch {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val targetItem = visibleItems.find {
                  // +1 because the first item is the item keyed at spacer_start.
                  it.index == index + 1
                }
                if (
                  targetItem == null ||
                    targetItem.offset < 0 ||
                    targetItem.offset + targetItem.size > listState.layoutInfo.viewportSize.width
                ) {
                  listState.animateScrollToItem(index = index)
                }
              }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Text(
          getCategoryLabel(context = context, category = category),
          modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelLarge,
          color =
            if (selectedIndex == index) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    item(key = "spacer_end") { Spacer(modifier = Modifier.width(8.dp)) }
  }
}

@Composable
private fun TaskList(
  pagerState: PagerState,
  sortedCategories: List<CategoryInfo>,
  tasksByCategories: Map<String, List<Task>>,
  enableAnimation: Boolean,
  navigateToTaskScreen: (Task) -> Unit,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
) {
  // Model list animation:
  //
  // 1.  Slide Up: The entire column of task cards translates upwards,
  // 2.  Fade in one by one: The task card fade in one by one. See TaskCard for details.
  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  // Tracks when the initial animation is done.
  //
  var initialAnimationDone by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    // Use 5 iterations to make sure all visible task cards are animated.
    delay(((TASK_CARD_ANIMATION_DURATION + TASK_CARD_ANIMATION_DELAY_OFFSET) * 5).toLong())
    initialAnimationDone = true
  }

  HorizontalPager(
    state = pagerState,
    verticalAlignment = Alignment.Top,
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) { pageIndex ->
    val tasks = tasksByCategories[sortedCategories[pageIndex].id]!!
    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier =
        Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
          translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
        },
    ) {
      for ((index, task) in tasks.withIndex()) {
        TaskCard(
          task = task,
          index = index,
          animate =
            (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
          onClick = { navigateToTaskScreen(task) },
          sharedTransitionScope = sharedTransitionScope,
          animatedVisibilityScope = animatedVisibilityScope,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun TaskCard(
  task: Task,
  index: Int,
  animate: Boolean,
  onClick: () -> Unit,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  modifier: Modifier = Modifier,
) {
  val modelCount by remember {
    derivedStateOf {
      val trigger = task.updateTrigger.value
      if (trigger >= 0) {
        task.models.size
      } else {
        0
      }
    }
  }
  val modelCountLabel by remember {
    derivedStateOf {
      when (modelCount) {
        1 -> "1 Model"
        else -> "%d Models".format(modelCount)
      }
    }
  }
  var curModelCountLabel by remember { mutableStateOf("") }
  var modelCountLabelVisible by remember { mutableStateOf(true) }

  LaunchedEffect(modelCountLabel) {
    if (curModelCountLabel.isEmpty()) {
      curModelCountLabel = modelCountLabel
    } else {
      modelCountLabelVisible = false
      delay(TASK_COUNT_ANIMATION_DURATION.toLong())
      curModelCountLabel = modelCountLabel
      modelCountLabelVisible = true
    }
  }

  val progress =
    if (animate)
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START + index * TASK_CARD_ANIMATION_DELAY_OFFSET,
        animationDurationMs = TASK_CARD_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )
    else 1f

  val cbTask = stringResource(R.string.cd_task_card, task.label, task.models.size)
  with(sharedTransitionScope) {
  Card(
    modifier =
      modifier
        .sharedBounds(
          rememberSharedContentState(key = "task-card-${task.id}"),
          animatedVisibilityScope = animatedVisibilityScope,
          enter = fadeIn(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)),
        )
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .graphicsLayer { alpha = progress }
        .semantics { contentDescription = cbTask },
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      TaskIcon(
        task = task,
        width = 40.dp,
        modifier = Modifier.sharedBounds(
          rememberSharedContentState(key = "task-icon-${task.id}"),
          animatedVisibilityScope = animatedVisibilityScope,
        ),
      )

      Column(modifier = Modifier.weight(1f)) {
        Text(
          task.label,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.sharedBounds(
            rememberSharedContentState(key = "task-title-${task.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        )
        Text(
          task.shortDescription,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
          maxLines = 2,
          modifier = Modifier.sharedBounds(
            rememberSharedContentState(key = "task-desc-${task.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        )
        Text(
          curModelCountLabel,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.padding(top = 4.dp).sharedBounds(
            rememberSharedContentState(key = "task-count-${task.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        )
      }
    }
  }
  } // with(sharedTransitionScope)
}

private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) {
    return context.getString(stringRes)
  } else if (label != null) {
    return label
  }
  return context.getString(R.string.category_unlabeled)
}
