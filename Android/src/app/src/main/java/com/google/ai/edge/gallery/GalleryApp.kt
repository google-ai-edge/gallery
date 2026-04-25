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

package com.google.ai.edge.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.openai.OpenAiServerState
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.ui.server.ServerScreen
import com.google.ai.edge.gallery.ui.home.SettingsScreen

private data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(
        label = "Chats",
        icon = Icons.Rounded.ChatBubbleOutline,
        selectedIcon = Icons.Rounded.ChatBubble,
    ),
    BottomNavItem(
        label = "Models",
        icon = Icons.Outlined.Widgets,
        selectedIcon = Icons.Rounded.Widgets,
    ),
    BottomNavItem(
        label = "Server",
        icon = Icons.Outlined.Dns,
        selectedIcon = Icons.Rounded.Dns,
    ),
    BottomNavItem(
        label = "Settings",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Rounded.Settings,
    ),
)

/** Top level composable representing the main screen of the application. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  val openServerScreenRequest by OpenAiServerState.openServerScreenRequest.collectAsState()

  LaunchedEffect(openServerScreenRequest) {
      if (openServerScreenRequest != 0L) {
          selectedTab = 2
      }
  }

  Scaffold(
      bottomBar = {
          NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              contentColor = MaterialTheme.colorScheme.onSurface,
          ) {
              bottomNavItems.forEachIndexed { index, item ->
                  NavigationBarItem(
                      selected = selectedTab == index,
                      onClick = { selectedTab = index },
                      icon = {
                          Icon(
                              imageVector = if (selectedTab == index) item.selectedIcon else item.icon,
                              contentDescription = item.label,
                          )
                      },
                      label = { Text(item.label) },
                      colors = NavigationBarItemDefaults.colors(
                          selectedIconColor = MaterialTheme.colorScheme.primary,
                          selectedTextColor = MaterialTheme.colorScheme.primary,
                          indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                          unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                          unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
                  )
              }
          }
      }
  ) { innerPadding ->
      AnimatedContent(
          targetState = selectedTab,
          label = "TabContent",
          transitionSpec = { fadeIn() togetherWith fadeOut() },
      ) { tab ->
          when (tab) {
              0 -> GalleryNavHost(
                  navController = navController,
                  modifier = Modifier.padding(innerPadding),
                  modelManagerViewModel = modelManagerViewModel,
                  onGoToModels = { selectedTab = 1 },
              )
              1 -> {
                  // Models tab — render GlobalModelManager directly
                  GlobalModelManager(
                      viewModel = modelManagerViewModel,
                      navigateUp = { selectedTab = 0 },
                      onModelSelected = { task, model -> /* handled internally */ },
                      onBenchmarkClicked = { model -> /* optional */ },
                      modifier = Modifier.padding(innerPadding),
                  )
              }
              2 -> ServerScreen(modifier = Modifier.padding(innerPadding))
              3 -> SettingsScreen(
                  modelManagerViewModel = modelManagerViewModel,
                  modifier = Modifier.padding(innerPadding),
              )
          }
      }
  }
}
