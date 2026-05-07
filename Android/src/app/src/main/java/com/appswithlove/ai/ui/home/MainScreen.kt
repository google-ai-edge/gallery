package com.appswithlove.ai.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.appswithlove.ai.R
import com.appswithlove.ai.data.Task
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay

private data class BottomNavItem(
  val label: String,
  val icon: ImageVector,
)

@Composable
fun MainScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  navigateToArticle: (Int) -> Unit,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  modifier: Modifier = Modifier,
) {
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  val context = LocalContext.current

  val bottomNavItems = remember(context) {
    listOf(
      BottomNavItem(context.getString(R.string.nav_use_cases), Icons.Rounded.AutoAwesome),
      BottomNavItem(context.getString(R.string.nav_learnings), Icons.AutoMirrored.Rounded.MenuBook),
      BottomNavItem(context.getString(R.string.nav_settings), Icons.Rounded.Settings),
    )
  }

  val requestPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

  Scaffold(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    bottomBar = {
      NavigationBar {
        bottomNavItems.forEachIndexed { index, item ->
          NavigationBarItem(
            selected = selectedTab == index,
            onClick = { selectedTab = index },
            icon = { Icon(item.icon, contentDescription = item.label) },
            label = { Text(item.label) },
          )
        }
      }
    },
  ) { innerPadding ->
    when (selectedTab) {
      0 -> HomeScreen(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = hiltViewModel(),
        navigateToTaskScreen = navigateToTaskScreen,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = Modifier.padding(innerPadding),
      )
      1 -> LearningsScreen(
        onArticleClick = navigateToArticle,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = Modifier.padding(innerPadding),
      )
      2 -> SettingsScreen(
        modelManagerViewModel = modelManagerViewModel,
        modifier = Modifier.padding(innerPadding),
      )
    }
  }
}
