package com.mochits.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mochits.app.editor.EditorScreen
import com.mochits.app.home.HomeScreen
import com.mochits.app.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder

private val MochiSoftDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8B85FF),
    onPrimary = Color(0xFF0F0E26),
    primaryContainer = Color(0xFF2F2C54),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF003258),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE4E3E9),
    surface = Color(0xFF181A22),
    onSurface = Color(0xFFE4E3E9),
    surfaceVariant = Color(0xFF242733),
    onSurfaceVariant = Color(0xFFC5C6D2),
    surfaceTint = Color(0xFF8B85FF),
    outline = Color(0xFF41445A)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = MochiSoftDarkColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MochiTsNavHost()
                }
            }
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
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
                },
                onOpenSettings = {
                    navController.navigate(ROUTE_SETTINGS)
                }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
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
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("projectName") ?: "", "UTF-8"
            )
            val path = URLDecoder.decode(
                backStackEntry.arguments?.getString("baseImagePath") ?: "", "UTF-8"
            )
            EditorScreen(
                projectId = projectId,
                projectName = name,
                baseImagePath = path,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
    }
}
