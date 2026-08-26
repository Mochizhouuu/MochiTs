package com.mochits.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mochits.app.editor.EditorScreen
import com.mochits.app.home.HomeScreen
import com.mochits.app.home.HomeViewModel
import com.mochits.app.ui.theme.MochiTsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val homeViewModel: HomeViewModel = hiltViewModel()
            val themeMode by homeViewModel.themeMode.collectAsState()

            MochiTsTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onOpenEditor = { projectId ->
                                    navController.navigate("editor/$projectId")
                                },
                                viewModel = homeViewModel
                            )
                        }
                        composable(
                            route = "editor/{projectId}",
                            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                        ) {
                            EditorScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
