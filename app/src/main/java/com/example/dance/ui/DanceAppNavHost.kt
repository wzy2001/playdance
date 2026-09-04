package com.example.dance.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.dance.ui.home.HomeScreen
import com.example.dance.ui.player.PlayerScreen

/** Navigation routes for the single-activity app. */
object Routes {
    const val HOME = "home"
    const val PLAYER = "player/{videoId}"

    fun player(videoId: Long) = "player/$videoId"
}

@Composable
fun DanceAppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPlayVideo = { videoId -> navController.navigate(Routes.player(videoId)) }
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("videoId") { type = NavType.LongType })
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getLong("videoId") ?: return@composable
            PlayerScreen(videoId = videoId, onBack = { navController.popBackStack() })
        }
    }
}
