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
import com.mochits.app.settings.SettingsScreen
import com.mochits.app.ui.theme.MochiTsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPointclass MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stashIncomingBundle(intent)
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
                                onOpenSettings = {
                                    navController.navigate("settings")
                                },
                                viewModel = homeViewModel
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                viewModel = homeViewModel,
                                lamaModelManager = homeViewModel.lamaModelManager
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stashIncomingBundle(intent)
    }

    /**
     * File .mts dibuka dari app lain (VIEW): salin ke cache + tandai agar
     * Home mengimpornya saat tampil.
     */
    private fun stashIncomingBundle(intent: android.content.Intent?) {
        try {
            if (intent?.action != android.content.Intent.ACTION_VIEW) return
            val uri = intent.data ?: return
            val dst = java.io.File(cacheDir, "incoming/incoming.mts")
            dst.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { inp ->
                dst.outputStream().use { out -> inp.copyTo(out) }
            } ?: return
            if (!dst.exists() || dst.length() <= 0L) return
            getSharedPreferences("mochits_bundle_in", MODE_PRIVATE)
                .edit()
                .putString("pending_bundle_path", dst.absolutePath)
                .apply()
        } catch (_: Exception) {
        }
    }
}
