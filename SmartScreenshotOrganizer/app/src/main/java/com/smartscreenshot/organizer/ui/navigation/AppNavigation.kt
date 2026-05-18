package com.smartscreenshot.organizer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartscreenshot.organizer.ui.detail.DetailScreen
import com.smartscreenshot.organizer.ui.home.HomeScreen
import com.smartscreenshot.organizer.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{screenshotId}"
    const val SETTINGS = "settings"

    fun detail(screenshotId: Long) = "detail/$screenshotId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onScreenshotClick = { id ->
                    navController.navigate(Routes.detail(id))
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("screenshotId") { type = NavType.LongType }
            )
        ) {
            DetailScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
