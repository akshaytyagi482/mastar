package com.mastar.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
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
                // Surface establishes LocalContentColor — without it, icons and
                // text default to black-on-black and disappear.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Edge-to-edge is enabled, so inset for the system bars —
                    // otherwise the bottom tool bar hides behind gesture/3-button
                    // navigation and becomes untappable.
                    Box(Modifier.safeDrawingPadding()) {
                        MastarNavGraph()
                    }
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{projectId}"
    fun editor(projectId: Long) = "editor/$projectId"
}

@OptIn(UnstableApi::class)
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
