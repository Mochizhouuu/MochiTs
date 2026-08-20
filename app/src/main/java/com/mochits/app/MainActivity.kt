package com.mochits.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mochits.app.editor.EditorScreen
import com.mochits.app.home.HomeScreen
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Entry point navigasi utama: Home (list project) <-> Editor.
 * Canvas Editor sesungguhnya akan menggantikan EditorScreen placeholder
 * di tahap pengembangan berikutnya.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MochiTsNavHost()
                }
            }
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_EDITOR = "editor/{projectId}/{projectName}/{baseImagePath}"

@androidx.compose.runtime.Composable
private fun MochiTsNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(
                onOpenProject = { project ->
                    val encodedPath = URLEncoder.encode(project.baseImagePath, "UTF-8")
                    val encodedName = URLEncoder.encode(project.name, "UTF-8")
                    navController.navigate("editor/${project.id}/$encodedName/$encodedPath")
                }
            )
        }
        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("projectName") { type = NavType.StringType },
                navArgument("baseImagePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("projectName") ?: "", "UTF-8"
            )
            val path = URLDecoder.decode(
                backStackEntry.arguments?.getString("baseImagePath") ?: "", "UTF-8"
            )
            EditorScreen(
                projectName = name,
                baseImagePath = path,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
