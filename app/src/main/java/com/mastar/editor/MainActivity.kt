package com.mastar.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mastar.editor.ui.editor.EditorScreen
import com.mastar.editor.ui.home.HomeScreen
import com.mastar.editor.ui.theme.MastarTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MastarTheme {
                MastarNavGraph()
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{projectId}"
    fun editor(projectId: Long) = "editor/$projectId"
}

@Composable
private fun MastarNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenProject = { id -> navController.navigate(Routes.editor(id)) })
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getLong("projectId") ?: return@composable
            EditorScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
