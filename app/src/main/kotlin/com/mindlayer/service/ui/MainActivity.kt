package com.mindlayer.service.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val historyViewModel: SessionHistoryViewModel by viewModels()
    private val detailViewModel: SessionDetailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            val state by dashboardViewModel.uiState.collectAsState()
                            DashboardScreen(
                                state = state,
                                onTestInference = { dashboardViewModel.runTestInference() },
                                onNavigateToHistory = { navController.navigate("history") },
                            )
                        }
                        composable("history") {
                            LaunchedEffect(Unit) {
                                historyViewModel.loadSessions()
                            }
                            val state by historyViewModel.uiState.collectAsState()
                            SessionHistoryScreen(
                                state = state,
                                onSessionClick = { sessionId ->
                                    navController.navigate("detail/$sessionId")
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "detail/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                            LaunchedEffect(sessionId) {
                                detailViewModel.loadSession(sessionId)
                            }
                            val state by detailViewModel.uiState.collectAsState()
                            SessionDetailScreen(
                                state = state,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dashboardViewModel.bindService(this)
    }

    override fun onStop() {
        super.onStop()
        dashboardViewModel.unbindService(this)
    }
}
