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

package com.google.ai.edge.gallery.ui.navigation

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.mediagallery.FolderGridScreen
import com.google.ai.edge.gallery.mediagallery.FoldersScreen
import com.google.ai.edge.gallery.mediagallery.GalleryViewModel
import com.google.ai.edge.gallery.mediagallery.MediaPermissionGate
import com.google.ai.edge.gallery.mediagallery.MediaViewerScreen
import com.google.ai.edge.gallery.mediagallery.SearchBarRow
import com.google.ai.edge.gallery.mediagallery.SettingsScreen
import com.google.ai.edge.gallery.mediagallery.OfflineIndexSection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.LocalTestAllowlistDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.MODEL_ALLOWLIST_TEST_FILE_PATH
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_GALLERY = "gallery"
private const val ROUTE_FOLDER = "folder"
private const val ROUTE_VIEWER = "viewer"
/** bucketId placeholder for "all media" in routes. */
private const val ALL_BUCKETS = -1L
/** Viewer over the current search results. */
private const val SEARCH_RESULTS = -2L
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ARG_IMPORT_URL = "import_url"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

/** Navigation routes. */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel = hiltViewModel(),
  galleryViewModel: GalleryViewModel = hiltViewModel(),
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_GALLERY,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    // Gallery: folders, folder grid, full screen viewer, settings.
    composable(route = ROUTE_GALLERY) {
      MediaPermissionGate(galleryViewModel) {
        FoldersScreen(
          viewModel = galleryViewModel,
          onOpenFolder = { navController.navigate("$ROUTE_FOLDER/${it ?: ALL_BUCKETS}") },
          actions = {
            IconButton(onClick = { navController.navigate("$ROUTE_FOLDER/$ALL_BUCKETS") }) {
              Icon(Icons.Filled.Search, contentDescription = "Suchen")
            }
            IconButton(onClick = { navController.navigate(ROUTE_SETTINGS) }) {
              Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
            }
          },
        )
      }
    }

    composable(
      route = "$ROUTE_FOLDER/{bucketId}",
      arguments = listOf(navArgument("bucketId") { type = NavType.LongType }),
    ) { entry ->
      val bucketArg = entry.arguments?.getLong("bucketId") ?: ALL_BUCKETS
      val bucketId = bucketArg.takeIf { it != ALL_BUCKETS }
      val search by galleryViewModel.search.collectAsState()
      val leave = {
        galleryViewModel.clearSearch()
        navController.navigateUp()
      }
      BackHandler(enabled = search.query.isNotEmpty()) { galleryViewModel.clearSearch() }
      FolderGridScreen(
        viewModel = galleryViewModel,
        bucketId = bucketId,
        onOpenItem = {
          val from = if (search.results != null) SEARCH_RESULTS else bucketArg
          navController.navigate("$ROUTE_VIEWER/$from/${it.id}")
        },
        onBack = { leave() },
        header = {
          SearchBarRow(
            state = search,
            showEverywhere = bucketId != null,
            onQuery = { galleryViewModel.search(it, bucketId) },
            onEverywhere = { galleryViewModel.setEverywhere(it, bucketId) },
            onClear = { galleryViewModel.clearSearch() },
          )
        },
        overrideItems = search.results,
      )
    }

    composable(
      route = "$ROUTE_VIEWER/{bucketId}/{mediaId}",
      arguments =
        listOf(
          navArgument("bucketId") { type = NavType.LongType },
          navArgument("mediaId") { type = NavType.LongType },
        ),
    ) { entry ->
      val bucketArg = entry.arguments?.getLong("bucketId") ?: ALL_BUCKETS
      val mediaId = entry.arguments?.getLong("mediaId") ?: 0L
      val library by galleryViewModel.library.collectAsState()
      val search by galleryViewModel.search.collectAsState()
      val items =
        when (bucketArg) {
          SEARCH_RESULTS -> search.results.orEmpty()
          ALL_BUCKETS -> library.items
          else -> library.itemsIn(bucketArg)
        }
      MediaViewerScreen(
        items = items,
        startId = mediaId,
        onBack = { navController.navigateUp() },
        actions = { item ->
          IconButton(
            onClick = {
              galleryViewModel.similar(item)
              navController.navigate("$ROUTE_FOLDER/$ALL_BUCKETS")
            }
          ) {
            Icon(Icons.Filled.ImageSearch, contentDescription = "Ähnliche finden", tint = Color.White)
          }
        },
      )
    }

    composable(route = ROUTE_SETTINGS) {
      SettingsScreen(
        viewModel = galleryViewModel,
        onBack = { navController.navigateUp() },
        extra = { OfflineIndexSection(modelManagerViewModel, galleryViewModel.localSearch) },
      )
    }

    // Model list.
    composable(
      route = ROUTE_MODEL_LIST,
      enterTransition = {
        if (initialState.destination.route == ROUTE_HOMESCREEN) {
          slideEnter()
        } else {
          EnterTransition.None
        }
      },
      exitTransition = {
        if (targetState.destination.route == ROUTE_HOMESCREEN) {
          slideExit()
        } else {
          ExitTransition.None
        }
      },
    ) {
      pickedTask?.let {
        ModelManager(
          viewModel = modelManagerViewModel,
          task = it,
          enableAnimation = enableModelListAnimation,
          onModelClicked = { model ->
            modelManagerViewModel.selectModel(model)
            navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
          },
          onBenchmarkClicked = {},
          navigateUp = {
            enableHomeScreenAnimation = false
            navController.navigateUp()
          },
        )
      }
    }

    // Model page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
          navArgument("query") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val queryParam = backStackEntry.arguments?.getString("query")
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
        LaunchedEffect(modelName) { modelManagerViewModel.selectModel(initialModel) }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          if (isLegacyTasks(customTask.task.id)) {
            customTask.MainScreen(
              data =
                CustomTaskDataForBuiltinTask(
                  modelManagerViewModel = modelManagerViewModel,
                  onNavUp = {
                    enableModelListAnimation = false
                    navController.navigateUp()
                  },
                  initialQuery = queryParam,
                )
            )
          } else {
            var disableAppBarControls by remember { mutableStateOf(false) }
            var hideTopBar by remember { mutableStateOf(false) }
            var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            CustomTaskScreen(
              task = customTask.task,
              initialModel = initialModel,
              modelManagerViewModel = modelManagerViewModel,
              onNavigateUp = {
                if (customNavigateUpCallback != null) {
                  customNavigateUpCallback?.invoke()
                } else {
                  enableModelListAnimation = false
                  navController.navigateUp()

                  if (!customTask.keepModelAlive) {
                    // clean up all models.
                    for (curModel in customTask.task.models) {
                      val instanceToCleanUp = curModel.instance
                      scope.launch(Dispatchers.Default) {
                        modelManagerViewModel.cleanupModel(
                          context = context,
                          task = customTask.task,
                          model = curModel,
                          instanceToCleanUp = instanceToCleanUp,
                        )
                      }
                    }
                  }
                }
              },
              disableAppBarControls = disableAppBarControls,
              hideTopBar = hideTopBar,
              useThemeColor = customTask.task.useThemeColor,
            ) { bottomPadding ->
              customTask.MainScreen(
                data =
                  CustomTaskData(
                    modelManagerViewModel = modelManagerViewModel,
                    bottomPadding = bottomPadding,
                    setAppBarControlsDisabled = { disableAppBarControls = it },
                    setTopBarVisible = { hideTopBar = !it },
                    setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                  )
              )
            }
          }
        }
      }
    }

    // Global model manager page.
    composable(
      route = "$ROUTE_MODEL_MANAGER?$ARG_IMPORT_URL={$ARG_IMPORT_URL}",
      arguments =
        listOf(
          navArgument(ARG_IMPORT_URL) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          }
        ),
      enterTransition = {
        if (
          initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            initialState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideUpEnter()
        }
      },
      exitTransition = {
        if (
          targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            targetState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideDownExit()
        }
      },
    ) { backStackEntry ->
      val importUrl = backStackEntry.arguments?.getString(ARG_IMPORT_URL)
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        initialImportUrl = importUrl,
        navigateUp = {
          enableHomeScreenAnimation = false
          navController.navigateUp()
        },
        onModelSelected = { task, model ->
          navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
        },
        onBenchmarkClicked = {},
      )
    }

  }

  // Shown on top of whichever screen is active, since the app may skip the home screen at launch
  // (e.g. via the STARTUP_SCREEN flag or a deep link).
  if (modelManagerUiState.showLocalTestAllowlistDialog) {
    LocalTestAllowlistDialog(
      filePath = MODEL_ALLOWLIST_TEST_FILE_PATH,
      onDismiss = { modelManagerViewModel.dismissLocalTestAllowlistDialog() },
    )
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  // Wait until the model manager has been initialized and the tasks are available.
  if (data != null && modelManagerUiState.tasks.isNotEmpty()) {
    intent.data = null
    val uriStr = data.toString()
    Log.d(TAG, "navigation link clicked: $data")
    // 1. Precise model deep links: com.google.ai.edge.gallery://model/<taskId>/<modelName>
    if (uriStr.startsWith("com.google.ai.edge.gallery://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        val queryStr = data.getQueryParameter("query")
        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          val route =
            if (!queryStr.isNullOrEmpty()) {
              "$ROUTE_MODEL/${taskId}/${model.name}?query=${Uri.encode(queryStr)}"
            } else {
              "$ROUTE_MODEL/${taskId}/${model.name}"
            }
          navController.navigate(route)
        }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else if (data.host == "global_model_manager" || data.host == "import") {
      val route =
          ROUTE_MODEL_MANAGER
      navController.navigate(route)
    } else {
      // 2. Dynamic task-level deep links: com.google.ai.edge.gallery://<taskId>
      val host = data.host
      if (host != null) {
        val queryStr = data.getQueryParameter("query")
        val task = modelManagerUiState.tasks.find { it.id == host }
        if (task != null) {
          // Pick the first successfully downloaded model or the default active model for this task
          val defaultModel =
            task.models.firstOrNull { model ->
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            } ?: task.models.firstOrNull()

          if (defaultModel != null) {
            val route =
              if (!queryStr.isNullOrEmpty()) {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}?query=${Uri.encode(queryStr)}"
              } else {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}"
              }
            navController.navigate(route)
          } else {
            Log.e(TAG, "No available model found for task: $host")
          }
        }
      }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  initialModel: Model,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  // Use currentModel on initial composition to prevent reading stale selectedModel from
  // ViewModel before selectModel() runs. Once ViewModel synchronizes with the current model,
  // use the live model instance from uiState to capture ongoing configuration/state updates.
  var currentModel by remember(initialModel.name) { mutableStateOf(initialModel) }
  val selectedModel =
    if (modelManagerUiState.selectedModel.name == currentModel.name) {
      modelManagerUiState.selectedModel
    } else {
      currentModel
    }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitStatus by selectedModel.initStatusFlow.collectAsState()
  LaunchedEffect(modelInitStatus) {
    showErrorDialog = modelInitStatus is Model.InitializationStatus.Failed
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          shouldShowHistoryButton = false,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            currentModel = newSelectedModel
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    val initErrorMessage =
      (modelInitStatus as? Model.InitializationStatus.Failed)?.error?.message ?: ""
    ErrorDialog(
      error = initErrorMessage,
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
