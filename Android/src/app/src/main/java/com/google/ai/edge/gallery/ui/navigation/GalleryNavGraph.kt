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

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_IMAGE_CLASSIFICATION
import com.google.ai.edge.gallery.data.TASK_IMAGE_GENERATION
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
// import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE // Removed
// import com.google.ai.edge.gallery.data.TASK_LLM_PROMPT_LAB // Removed
import com.google.ai.edge.gallery.data.TASK_TEXT_CLASSIFICATION
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TaskType
import com.google.ai.edge.gallery.data.getModelByName
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.imageclassification.ImageClassificationDestination
import com.google.ai.edge.gallery.ui.imageclassification.ImageClassificationScreen
import com.google.ai.edge.gallery.ui.imagegeneration.ImageGenerationDestination
import com.google.ai.edge.gallery.ui.imagegeneration.ImageGenerationScreen
// LlmChatDestination will be defined in this file now
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
// import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageDestination // Removed
// import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageScreen // Removed
// import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnDestination // Removed
// import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnScreen // Removed
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.textclassification.TextClassificationDestination
import com.google.ai.edge.gallery.ui.textclassification.TextClassificationScreen
import com.google.ai.edge.gallery.ui.userprofile.UserProfileScreen
import com.google.ai.edge.gallery.ui.persona.PersonaManagementScreen // Added import
import com.google.ai.edge.gallery.ui.conversationhistory.ConversationHistoryScreen // Added import
// ViewModelProvider.Factory will provide UserProfileViewModel
// import com.google.ai.edge.gallery.ui.common.LocalAppContainer // Not needed if VM is through factory
private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_PLACEHOLDER = "placeholder"
internal const val UserProfileRoute = "userProfile" // As per subtask for direct use

object UserProfileDestination {
    const val route = "userProfile"
}

object PersonaManagementDestination {
    const val route = "personaManagement"
}

// Define LlmChatDestination here as per structure in LlmChatScreen.kt modifications
object LlmChatDestination {
  const val routeTemplate = "LlmChatRoute" // Base for non-conversation specific chat
  const val conversationIdArg = "conversationId"
  const val modelNameArg = "modelName" // Added modelNameArg for clarity

  // Route for opening an existing conversation: LlmChatRoute/conversation/{conversationId}?modelName={modelName}
  val routeForConversation = "$routeTemplate/conversation/{$conversationIdArg}?$modelNameArg={$modelNameArg}"

  // Route for starting a new chat with a pre-selected model: LlmChatRoute/new/{modelName}
  val routeForNewChatWithModel = "$routeTemplate/new/{$modelNameArg}"

  // General route for new chat (model selected elsewhere or default): LlmChatRoute
  val routeForNewChat = routeTemplate
}

object ConversationHistoryDestination {
    const val route = "conversationHistory"
}

private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS
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

/**
 * Navigation routes.
 */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel = viewModel(factory = ViewModelProvider.Factory)
) {
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }

  HomeScreen(
    modelManagerViewModel = modelManagerViewModel,
    navigateToTaskScreen = { task ->
      pickedTask = task
      showModelManager = true
    },
    navController = navController // Pass NavController to HomeScreen
  )

  // Model manager.
  AnimatedVisibility(
    visible = showModelManager,
    enter = slideInHorizontally(initialOffsetX = { it }),
    exit = slideOutHorizontally(targetOffsetX = { it }),
  ) {
    val curPickedTask = pickedTask
    if (curPickedTask != null) {
      ModelManager(viewModel = modelManagerViewModel,
        task = curPickedTask,
        onModelClicked = { model ->
          navigateToTaskScreen(
            navController = navController, taskType = curPickedTask.type, model = model
          )
        },
        navigateUp = { showModelManager = false })
    }
  }

  NavHost(
    navController = navController,
    // Default to open home screen.
    startDestination = ROUTE_PLACEHOLDER,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    modifier = modifier.zIndex(1f)
  ) {
    // Placeholder root screen
    composable(
      route = ROUTE_PLACEHOLDER,
    ) {
      Text("")
    }

    // Text classification.
    composable(
      route = "${TextClassificationDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_TEXT_CLASSIFICATION)?.let { defaultModel ->
        modelManagerViewModel.selectModel(defaultModel)

        TextClassificationScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }

    // Image classification.
    composable(
      route = "${ImageClassificationDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_IMAGE_CLASSIFICATION)?.let { defaultModel ->
        modelManagerViewModel.selectModel(defaultModel)

        ImageClassificationScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }

    // Image generation.
    composable(
      route = "${ImageGenerationDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_IMAGE_GENERATION)?.let { defaultModel ->
        modelManagerViewModel.selectModel(defaultModel)

        ImageGenerationScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }

    // LLM chat demos.

    // Route for starting a new chat with a selected model (current primary way to enter chat)
    composable(
      route = LlmChatDestination.routeForNewChatWithModel,
      arguments = listOf(navArgument(LlmChatDestination.modelNameArg) { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { navBackStackEntry ->
      val modelName = navBackStackEntry.arguments?.getString(LlmChatDestination.modelNameArg)
      getModelByName(modelName ?: "")?.let { model -> // Use getModelByName from Tasks.kt
          modelManagerViewModel.selectModel(model)
          LlmChatScreen(
              modelManagerViewModel = modelManagerViewModel,
              navigateUp = { navController.navigateUp() },
              conversationId = null // Explicitly null for new chat
          )
      } ?: run {
          // Handle model not found, perhaps navigate back or show error
          Text("Model $modelName not found.")
      }
    }

    // Route for opening an existing conversation
    composable(
      route = LlmChatDestination.routeForConversation,
      arguments = listOf(
          navArgument(LlmChatDestination.conversationIdArg) { type = NavType.StringType },
          navArgument(LlmChatDestination.modelNameArg) { type = NavType.StringType } // Model name is mandatory here
      ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { navBackStackEntry ->
      val conversationId = navBackStackEntry.arguments?.getString(LlmChatDestination.conversationIdArg)
      val modelName = navBackStackEntry.arguments?.getString(LlmChatDestination.modelNameArg)

      getModelByName(modelName ?: "")?.let { model ->
          modelManagerViewModel.selectModel(model) // Ensure this model is selected
          LlmChatScreen(
              modelManagerViewModel = modelManagerViewModel,
              navigateUp = { navController.navigateUp() },
              conversationId = conversationId
          )
      } ?: run {
          Text("Model $modelName not found for conversation $conversationId.")
      }
    }

    // Optional: General route for new chat if model is already selected in ViewModel (less explicit)
    // composable(
    //   route = LlmChatDestination.routeForNewChat,
    //   enterTransition = { slideEnter() },
    //   exitTransition = { slideExit() },
    // ) {
    //   // This assumes a model is already selected in modelManagerViewModel for TASK_LLM_CHAT
    //   // Or LlmChatScreen/ViewModel can handle model selection if none is active
    //   LlmChatScreen(
    //       modelManagerViewModel = modelManagerViewModel,
    //       navigateUp = { navController.navigateUp() },
    //       conversationId = null
    //   )
    // }

    // LLM single turn. - REMOVED

    // LLM image to text. - REMOVED

    // User Profile Screen
    composable(
      route = UserProfileDestination.route,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      // val appContainer = LocalAppContainer.current // Not strictly needed if factory handles it
      // val factory = ViewModelProvider.Factory(appContainer) // Factory is global
      UserProfileScreen(
        navController = navController,
        viewModelFactory = ViewModelProvider.Factory
      )
    }

    composable(PersonaManagementDestination.route) {
      PersonaManagementScreen(
        navController = navController,
        viewModelFactory = ViewModelProvider.Factory
      )
    }

    composable(ConversationHistoryDestination.route) {
      ConversationHistoryScreen(
        navController = navController,
        viewModelFactory = ViewModelProvider.Factory
      )
    }
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  if (data != null) {
    intent.data = null
    Log.d(TAG, "navigation link clicked: $data")
    if (data.toString().startsWith("com.google.ai.edge.gallery://model/")) {
      val modelName = data.pathSegments.last()
      getModelByName(modelName)?.let { model ->
        // TODO(jingjin): need to show a list of possible tasks for this model.
        navigateToTaskScreen(
          navController = navController, taskType = TaskType.LLM_CHAT, model = model
        )
      }
    }
  }
}

fun navigateToTaskScreen(
  navController: NavHostController, taskType: TaskType, model: Model? = null
) {
  val modelName = model?.name ?: ""
  when (taskType) {
    // Removed UserProfileRoute from here as it's not a task-based navigation
    TaskType.TEXT_CLASSIFICATION -> navController.navigate("${TextClassificationDestination.route}/${modelName}")
    TaskType.IMAGE_CLASSIFICATION -> navController.navigate("${ImageClassificationDestination.route}/${modelName}")
    TaskType.LLM_CHAT -> {
        // This is for starting a new chat from task selection, so use modelName
        if (modelName.isNotEmpty()) {
            navController.navigate("${LlmChatDestination.routeTemplate}/new/${modelName}")
        } else {
            // Fallback or error: model name is expected here
            Log.e(TAG, "LLM_CHAT navigation attempted without a model name.")
            // Optionally navigate to a generic new chat route if one exists and handles model selection
            // navController.navigate(LlmChatDestination.routeForNewChat)
        }
    }
    // TaskType.LLM_ASK_IMAGE removed
    // TaskType.LLM_PROMPT_LAB removed
    TaskType.IMAGE_GENERATION -> navController.navigate("${ImageGenerationDestination.route}/${modelName}")
    // TaskType.USER_PROFILE -> navController.navigate(UserProfileDestination.route) // Example if it were a task
    TaskType.TEST_TASK_1 -> {}
    TaskType.TEST_TASK_2 -> {}
  }
}

fun getModelFromNavigationParam(entry: NavBackStackEntry, task: Task): Model? {
  var modelName = entry.arguments?.getString("modelName") ?: ""
  if (modelName.isEmpty()) {
    modelName = task.models[0].name
  }
  val model = getModelByName(modelName)
  return model
}
