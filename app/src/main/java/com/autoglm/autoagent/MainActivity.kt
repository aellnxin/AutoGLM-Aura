package com.autoglm.autoagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.autoglm.autoagent.ui.HomeScreen
import com.autoglm.autoagent.ui.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var shizukuManager: com.autoglm.autoagent.shizuku.ShizukuManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启动高级模式自动注入引擎
        shizukuManager.initialize()
        
        // setContent...
        setContent {
            com.autoglm.autoagent.ui.theme.AutoDroidTheme {
                AutoGLMApp()
            }
        }
    }
}

@Composable
fun AutoGLMApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = hiltViewModel(),
                onOpenSettings = { navController.navigate("settings") },
                onOpenAdvancedMode = { navController.navigate("advanced_mode") }
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onNavigateToLogViewer = { navController.navigate("log_viewer") }
            )
        }
        composable("advanced_mode") {
            com.autoglm.autoagent.ui.ShellServiceActivationScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("log_viewer") {
            com.autoglm.autoagent.ui.LogViewerScreen(
                fileLogger = hiltViewModel<com.autoglm.autoagent.ui.SettingsViewModel>().run {
                    // LogViewerScreen expects a FileLogger, which we can get via Hilt or manually if needed
                    // For simplicity, let's assume we can inject it or get it from a common place
                    // In this project it seems expected to be passed. 
                    // Let's check how to best provide FileLogger here.
                    // Actually, LogViewerScreen takes FileLogger as a parameter.
                    // Let's use a workaround to get FileLogger. 
                    hiltViewModel<LogViewModel>().fileLogger
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// Helper ViewModel to inject FileLogger for LogViewerScreen
@HiltViewModel
class LogViewModel @Inject constructor(
    val fileLogger: com.autoglm.autoagent.utils.FileLogger
) : androidx.lifecycle.ViewModel()
