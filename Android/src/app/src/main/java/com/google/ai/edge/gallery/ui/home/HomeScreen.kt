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

package com.google.ai.edge.gallery.ui.home

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.RevealingText
import com.google.ai.edge.gallery.ui.common.SwipingText
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.homePageTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreen"
private const val TASK_COUNT_ANIMATION_DURATION = 250
private const val ANIMATION_INIT_DELAY = 0L
private const val TOP_APP_BAR_ANIMATION_DURATION = 600
private const val TITLE_FIRST_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION2 = 800
private const val TITLE_SECOND_LINE_ANIMATION_START =
  ANIMATION_INIT_DELAY + (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.5).toInt()
private const val TASK_LIST_ANIMATION_START = TITLE_SECOND_LINE_ANIMATION_START + 110
private const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
private const val TASK_CARD_ANIMATION_DURATION = 600
private const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
private const val CONTENT_COMPOSABLES_OFFSET_Y = 16

/** Navigation destination data */
private object HomeScreenDestination {
  @StringRes val titleRes = R.string.app_name
}

private val PREDEFINED_CATEGORY_ORDER = listOf(Category.LLM.id, Category.EXPERIMENTAL.id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  onModelsClicked: () -> Unit,
  enableAnimation: Boolean,
  modifier: Modifier = Modifier,
  gm4: Boolean = false,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val isDevBuild = context.packageName.endsWith(".dev")

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
          // Check if both categories are in the predefined order
          if (indexA != -1 && indexB != -1) {
            indexA.compareTo(indexB)
          }
          // Check if only category 'a' is in the predefined order
          else if (indexA != -1) {
            -1
          }
          // Check if only category 'b' is in the predefined order
          else if (indexB != -1) {
            1
          }
          // If neither is in the predefined order, sort by label
          else {
            val ca = categoryMap[a]!!
            val cb = categoryMap[b]!!
            val caLabel = getCategoryLabel(context = context, category = ca)
            val cbLabel = getCategoryLabel(context = context, category = cb)
            caLabel.compareTo(cbLabel)
          }
        }
        .map { categoryMap[it]!! }
    }

  // Show home screen content when TOS has been accepted.
  if (!showTosDialog) {
    // The code below manages the display of the model allowlist loading indicator with a debounced
    // delay. It ensures that a progress indicator is only shown if the loading operation
    // (represented by `uiState.loadingModelAllowlist`) takes longer than 200 milliseconds.
    // If the loading completes within 200ms, the indicator is never shown,
    // preventing a "flicker" and improving the perceived responsiveness of the UI.
    // The `loadingModelAllowlistDelayed` state is used to control the actual
    // visibility of the indicator based on this debounced logic.
    var loadingModelAllowlistDelayed by remember { mutableStateOf(false) }
    // This effect runs whenever uiState.loadingModelAllowlist changes
    LaunchedEffect(uiState.loadingModelAllowlist) {
      if (uiState.loadingModelAllowlist) {
        // If loading starts, wait for 200ms
        delay(200)
        // After 200ms, check if loadingModelAllowlist is still true
        if (uiState.loadingModelAllowlist) {
          loadingModelAllowlistDelayed = true
        }
      } else {
        // If loading finishes, immediately hide the indicator
        loadingModelAllowlistDelayed = false
      }
    }

    // Label and spinner to show when in the process of loading model allowlist.
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
    // Main UI when allowlist is done loading.
    if (!loadingModelAllowlistDelayed && !uiState.loadingModelAllowlist) {
      val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

      val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
          isGranted: Boolean ->
          if (isGranted) {
            // FCM SDK (and your app) can post notifications.
          }
        }

      LaunchedEffect(Unit) {
        delay(2000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
          ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }
      }

      // Close the menu when back button is pressed.
      BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

      ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
          ModalDrawerSheet {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(modifier = Modifier.fillMaxWidth()) {
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_settings_label),
                  description = stringResource(R.string.drawer_settings_description),
                  icon = Icons.Rounded.Settings,
                  onClick = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[2][0],
                          MaterialTheme.customColors.taskBgGradientColors[2][1],
                        )
                    ),
                )
                Spacer(modifier = Modifier.width(16.dp))
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_models_label),
                  description = stringResource(R.string.drawer_models_description),
                  icon = Icons.AutoMirrored.Rounded.ListAlt,
                  onClick = {
                    scope.launch { drawerState.close() }
                    scope.launch {
                      delay(50)
                      onModelsClicked()
                    }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[1][0],
                          MaterialTheme.customColors.taskBgGradientColors[1][1],
                        )
                    ),
                )
              }
            }
          }
        },
        gesturesEnabled = drawerState.isOpen,
      ) {
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          topBar = {
            // Top bar animation:
            //
            // Fade in and move down at the same time.
            val progress =
              if (!enableAnimation) 1f
              else
                rememberDelayedAnimationProgress(
                  initialDelay = ANIMATION_INIT_DELAY - 50,
                  animationDurationMs = TOP_APP_BAR_ANIMATION_DURATION,
                  animationLabel = "top bar",
                )
            Box(
              modifier =
                Modifier.graphicsLayer {
                  alpha = progress
                  translationY = ((-16).dp * (1 - progress)).toPx()
                }
            ) {
              GalleryTopAppBar(
                title = stringResource(HomeScreenDestination.titleRes),
                leftAction =
                  AppBarAction(
                    actionType = AppBarActionType.MENU,
                    actionFn = {
                      scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                    },
                  ),
              )
            }
          },
        ) { innerPadding ->
          // Outer box for coloring the background edge to edge.
          Box(
            contentAlignment = Alignment.TopCenter,
            modifier =
              Modifier.fillMaxSize()
                .background(
                  if (gm4) {
                    MaterialTheme.colorScheme.surface
                  } else {
                    MaterialTheme.colorScheme.surfaceContainer
                  }
                ),
          ) {
            // Inner box to hold content.
            Box(
              contentAlignment = Alignment.TopCenter,
              modifier =
                Modifier.fillMaxSize()
                  .padding(top = innerPadding.calculateTopPadding())
                  .verticalScroll(rememberScrollState()),
            ) {
              // Background star at top.
              if (gm4) {
                val progress =
                  if (!enableAnimation) {
                    1f
                  } else {
                    rememberDelayedAnimationProgress(
                      initialDelay = ANIMATION_INIT_DELAY,
                      animationDurationMs = 2000,
                      animationLabel = "bg star",
                    )
                  }
                val configuration = LocalConfiguration.current
                val screenWidth = configuration.screenWidthDp.dp
                val targetWidth = screenWidth * 1.5f
                Image(
                  painter = painterResource(id = R.drawable.bg_star),
                  contentDescription = null,
                  modifier =
                    Modifier.requiredWidth(targetWidth)
                      .blur(radius = 35.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                      .offset(x = screenWidth * 0.25f, y = -screenWidth * 0.1f)
                      .graphicsLayer {
                        rotationZ = (1f - progress) * 40f
                        scaleX = 0.4f + 0.6f * progress
                        scaleY = 0.4f + 0.6f * progress
                        alpha = progress * 2f
                      },
                  contentScale = ContentScale.Crop,
                  colorFilter = ColorFilter.tint(MaterialTheme.customColors.bgStarColor),
                )
              }

              Column(modifier = Modifier.fillMaxWidth()) {
                var selectedCategoryIndex by remember { mutableIntStateOf(0) }

                // App title and intro text.
                Column(
                  modifier =
                    Modifier.padding(
                        horizontal = if (gm4) 24.dp else 40.dp,
                        vertical = if (gm4) 0.dp else 48.dp,
                      )
                      .padding(top = 24.dp, bottom = 16.dp)
                      .semantics(mergeDescendants = true) {},
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (gm4) {
                    AppTitleGm4(enableAnimation = enableAnimation)
                  } else {
                    AppTitle(enableAnimation = enableAnimation)
                  }
                  IntroText(enableAnimation = enableAnimation, gm4 = gm4)
                  if (gm4) {
                    TryGm4IntroText(enableAnimation = enableAnimation)
                  }
                }

                // Tab header for categories.
                //
                // synchronizes the `pagerState` and the `selectedCategoryIndex` to ensure that
                //  both the tab header and the task list always show the correct category and page.
                val pagerState = rememberPagerState(pageCount = { sortedCategories.size })
                LaunchedEffect(pagerState.settledPage) {
                  selectedCategoryIndex = pagerState.settledPage
                }
                if (sortedCategories.size > 1) {
                  CategoryTabHeader(
                    sortedCategories = sortedCategories,
                    selectedIndex = selectedCategoryIndex,
                    enableAnimation = enableAnimation,
                    onCategorySelected = { index ->
                      selectedCategoryIndex = index
                      scope.launch { pagerState.animateScrollToPage(page = index) }
                    },
                  )
                }

                // Task list in a horizontal pager. Each page shows the list of tasks for the
                // category.
                val grid = gm4
                TaskList(
                  modelManagerViewModel = modelManagerViewModel,
                  pagerState = pagerState,
                  sortedCategories = sortedCategories,
                  tasksByCategories = uiState.tasksByCategory,
                  enableAnimation = enableAnimation,
                  navigateToTaskScreen = navigateToTaskScreen,
                  gm4 = gm4,
                  grid = grid,
                )

                Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding() + 10.dp))
              }
            }

            // Gradient overlay at the bottom.
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
          }
        }
      }
    }
  }

  // Show TOS dialog for users to accept.
  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  // Settings dialog.
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
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
      text = { Text("Please check your internet connection and try again later.") },
      onDismissRequest = { modelManagerViewModel.loadModelAllowlist() },
      confirmButton = {
        TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) { Text("Retry") }
      },
      dismissButton = {
        TextButton(onClick = { modelManagerViewModel.clearLoadModelAllowlistError() }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun AppTitle(enableAnimation: Boolean) {
  val firstLineText = stringResource(R.string.app_name_first_part)
  val secondLineText = stringResource(R.string.app_name_second_part)
  val titleColor = MaterialTheme.customColors.appTitleGradientColors[1]
  val screenWidthInDp = LocalConfiguration.current.screenWidthDp.dp
  val fontSize = with(LocalDensity.current) { (screenWidthInDp.toPx() * 0.12f).toSp() }
  val titleStyle = homePageTitleStyle.copy(fontSize = fontSize, lineHeight = fontSize)

  // First line text "Google AI" and its animation.
  //
  // The animation starts with the first line of text swiping in from left to right, progressively
  // revealing itself in the title color (blue). Then, after a brief delay, the exact same text, but
  // in the onSurface color (which is black in light mode), begins its own left-to-right swiping
  // animation. This second animation is positioned directly on top of the first, appearing just as
  // the initial reveal is finishing or has just completed, creating a layered and dynamic visual
  // effect.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = ANIMATION_INIT_DELAY
    if (enableAnimation) {
      SwipingText(
        text = firstLineText,
        style = titleStyle,
        color = titleColor,
        animationDelay = delay,
        animationDurationMs = TITLE_FIRST_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.3).toLong()
    }
    SwipingText(
      text = firstLineText,
      style = titleStyle,
      color = MaterialTheme.colorScheme.onSurface,
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_FIRST_LINE_ANIMATION_DURATION else 0,
    )
  }
  // Second line text "Edge Gallery" and its animation.
  //
  // The initial animation is the same as the first line text. Right before it is done, the final
  // text with a gradient is revealed.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = TITLE_SECOND_LINE_ANIMATION_START
    if (enableAnimation) {
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = titleColor,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.3).toInt()
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.6).toInt()
    }
    RevealingText(
      text = secondLineText,
      style =
        titleStyle.copy(
          brush = linearGradient(colors = MaterialTheme.customColors.appTitleGradientColors)
        ),
      modifier = Modifier.offset(x = (-16).dp, y = (-16).dp),
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_SECOND_LINE_ANIMATION_DURATION2 else 0,
    )
  }
}

@Composable
fun AppTitleGm4(enableAnimation: Boolean) {
  val text1 = "Google"
  val text2 = "AI Edge Gallery"
  val annotatedText = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(text1) }
    append(" ")
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(text2) }
  }

  RevealingText(
    text = "",
    annotatedText = annotatedText,
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
    animationDelay = 0,
    animationDurationMs =
      if (enableAnimation) {
        (TITLE_FIRST_LINE_ANIMATION_DURATION + TITLE_SECOND_LINE_ANIMATION_DURATION)
      } else {
        0
      },
    extraTextPadding = 0.dp,
  )
}

@Composable
private fun IntroText(enableAnimation: Boolean, gm4: Boolean) {
  val litertUrl = "https://huggingface.co/litert-community"

  // Intro text animation:
  //
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }

  val introText = buildAnnotatedString {
    val gemma4Url = "https://ai.google.dev/gemma"
    if (gm4) {
      append("Discover the power of on-device AI models from the ")
      append(buildTrackableUrlAnnotatedString(url = litertUrl, linkText = "LiteRT community"))
      append(", featuring the all-new ")
      append(buildTrackableUrlAnnotatedString(url = gemma4Url, linkText = "Gemma 4"))
      append(".")
    } else {
      append("${stringResource(R.string.app_intro)} ")
      append(
        buildTrackableUrlAnnotatedString(
          url = litertUrl,
          linkText = stringResource(R.string.litert_community_label),
        )
      )
    }
  }
  Text(
    introText,
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}

@Composable
private fun TryGm4IntroText(enableAnimation: Boolean) {
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }
  Row(
    modifier =
      Modifier.padding(top = 24.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      ImageVector.vectorResource(R.drawable.gemma_logo),
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = "Try Gemma 4 today",
      style =
        MaterialTheme.typography.headlineSmall.copy(
          fontWeight = FontWeight.Medium,
          fontSize = 20.sp,
          lineHeight = 24.sp,
        ),
      color = MaterialTheme.colorScheme.onSurface,
    )
  }

  Text(
    "Gemma 4 E2B & E4B are here! Try them in AI Chat, Agent Skills, or the use cases below.",
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
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
  modelManagerViewModel: ModelManagerViewModel,
  pagerState: PagerState,
  sortedCategories: List<CategoryInfo>,
  tasksByCategories: Map<String, List<Task>>,
  enableAnimation: Boolean,
  navigateToTaskScreen: (Task) -> Unit,
  gm4: Boolean = false,
  grid: Boolean = false,
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

  // The highlighted tiles at the top.
  if (gm4) {
    Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier =
        Modifier.padding(horizontal = 24.dp).graphicsLayer {
          alpha = progress
          translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
        },
    ) {
      val chatToDescription =
        mapOf(
          BuiltInTaskId.LLM_CHAT to "Chat with the latest Gemma 4 model today",
          // use "\u00a0" to make sure the word before and after it should always be together when
          // wrapping lines.
          BuiltInTaskId.LLM_AGENT_CHAT to "Have Gemma 4 complete agentic tasks for\u00A0you",
        )
      for (task in
        listOf(
          modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)!!,
          modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT)!!,
        )) {
        TaskCard(
          task = task,
          index = 0,
          animate = !initialAnimationDone && enableAnimation,
          onClick = { navigateToTaskScreen(task) },
          modifier = Modifier.fillMaxWidth(),
          description = chatToDescription[task.id]!!,
        )
      }

      Text(
        text = "Explore other use cases",
        style =
          MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 24.sp,
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 22.dp, bottom = 16.dp),
      )
    }
  }

  HorizontalPager(
    state = pagerState,
    verticalAlignment = Alignment.Top,
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) { pageIndex ->
    val tasks = tasksByCategories[sortedCategories[pageIndex].id]!!
    if (grid) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
      ) {
        for (i in tasks.indices step 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            // First item in the row
            TaskCard(
              task = tasks[i],
              index = i,
              animate =
                (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
              onClick = { navigateToTaskScreen(tasks[i]) },
              modifier = Modifier.weight(1f),
              square = true,
            )

            // Second item in the row, if it exists
            if (i + 1 < tasks.size) {
              TaskCard(
                task = tasks[i + 1],
                index = i + 1,
                animate =
                  (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
                onClick = { navigateToTaskScreen(tasks[i + 1]) },
                modifier = Modifier.weight(1f),
                square = true,
              )
            } else {
              // Add a spacer to fill the remaining space if there's only one item in the last row
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    } else {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        for ((index, task) in tasks.withIndex()) {
          TaskCard(
            task = task,
            index = index,
            animate =
              (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
            onClick = { navigateToTaskScreen(task) },
            modifier = Modifier.fillMaxWidth(),
            square = false,
          )
        }
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
  modifier: Modifier = Modifier,
  description: String = "",
  square: Boolean = false,
) {
  // Observes the model count and updates the model count label with a fade-in/fade-out animation
  // whenever the count changes.
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

  // Task card animation:
  //
  // This animation makes the task cards appear with a delayed fade-in effect. Each card will become
  // visible sequentially, starting after an initial delay and then with an additional offset for
  // subsequent cards.
  val progress =
    if (animate)
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START + index * TASK_CARD_ANIMATION_DELAY_OFFSET,
        animationDurationMs = TASK_CARD_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )
    else 1f

  val cbTask = stringResource(R.string.cd_task_card, task.label, task.models.size)
  Card(
    modifier =
      modifier
        .clip(RoundedCornerShape(24.dp))
        .clickable(onClick = onClick)
        .graphicsLayer { alpha = progress }
        .semantics { contentDescription = cbTask },
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (description.isNotEmpty() || square) {
            MaterialTheme.colorScheme.surfaceContainer
          } else {

            MaterialTheme.customColors.taskCardBgColor
          }
      ),
  ) {
    if (square) {
      Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        TaskIcon(task = task, width = 40.dp)
        Column() {
          Text(
            curModelCountLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            modifier = Modifier.clearAndSetSemantics {},
          )
          Text(
            task.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            task.shortDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            modifier = Modifier.clearAndSetSemantics {},
            minLines = 2,
            maxLines = 2,
            autoSize =
              TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp),
          )
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (description.isNotEmpty()) {
          // Icon.
          TaskIcon(task = task, width = 40.dp)

          // Title and description.
          Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.newFeature) {
                Box(
                  modifier =
                    Modifier.offset(y = (-6).dp, x = 6.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MaterialTheme.customColors.newFeatureContainerColor)
                      .padding(horizontal = 12.dp)
                      .height(26.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    "New",
                    color = MaterialTheme.customColors.newFeatureTextColor,
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }
            }
            Text(
              description,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
              modifier = Modifier.clearAndSetSemantics {},
            )
          }
        } else {
          // Title and model count
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.experimental) {
                Icon(
                  painter = painterResource(R.drawable.ic_experiment),
                  contentDescription = "Experimental",
                  modifier = Modifier.size(20.dp).padding(start = 4.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Text(
              curModelCountLabel,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.clearAndSetSemantics {},
            )
          }

          // Icon.
          TaskIcon(task = task, width = 40.dp)
        }
      }
    }
  }
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
