package com.mindlayer.service.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val historyViewModel: SessionHistoryViewModel by viewModels()
    private val detailViewModel: SessionDetailViewModel by viewModels()
    private val logsViewModel: RecentLogsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                Scaffold { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = SessionHistoryNavigation.DashboardRoute,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(SessionHistoryNavigation.DashboardRoute) {
                            val state by dashboardViewModel.uiState.collectAsState()
                            DashboardScreen(
                                state = state,
                                onTestInference = { dashboardViewModel.runTestInference() },
                                onNavigateToHistory = {
                                    navController.navigate(SessionHistoryNavigation.HistoryRoute)
                                },
                                onNavigateToLogs = {
                                    navController.navigate(SessionHistoryNavigation.LogsRoute)
                                },
                            )
                        }
                        composable(SessionHistoryNavigation.HistoryRoute) {
                            LaunchedEffect(Unit) {
                                historyViewModel.loadSessions()
                            }
                            val state by historyViewModel.uiState.collectAsState()
                            SessionHistoryScreen(
                                state = state,
                                onSessionClick = { sessionId ->
                                    navController.navigate(
                                        SessionHistoryNavigation.detailRoute(sessionId),
                                    )
                                },
                                onBack = { navController.popBackStack() },
                                onRetry = historyViewModel::loadSessions,
                            )
                        }
                        composable(
                            route = SessionHistoryNavigation.DetailRoute,
                            arguments = listOf(
                                navArgument(SessionHistoryNavigation.SessionIdArgument) {
                                    type = NavType.StringType
                                },
                            ),
                        ) { backStackEntry ->
                            val sessionId = SessionHistoryNavigation.decodeSessionId(
                                backStackEntry.arguments?.getString(
                                    SessionHistoryNavigation.SessionIdArgument,
                                ),
                            )
                            if (sessionId == null) {
                                SessionDetailScreen(
                                    state = SessionDetailUiState(
                                        isLoading = false,
                                        errorMessage = "The requested session route is missing a valid session ID. Return to Session History and open the session again.",
                                    ),
                                    onBack = { navController.popBackStack() },
                                )
                            } else {
                                LaunchedEffect(sessionId) {
                                    detailViewModel.loadSession(sessionId)
                                }
                                val state by detailViewModel.uiState.collectAsState()
                                SessionDetailScreen(
                                    state = state,
                                    onBack = { navController.popBackStack() },
                                    onRetry = { detailViewModel.loadSession(sessionId) },
                                )
                            }
                        }
                        composable(SessionHistoryNavigation.LogsRoute) {
                            LaunchedEffect(Unit) {
                                logsViewModel.loadLogs()
                            }
                            val state by logsViewModel.uiState.collectAsState()
                            RecentLogsScreen(
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

internal object SessionHistoryNavigation {
    const val DashboardRoute = "dashboard"
    const val HistoryRoute = "history"
    const val LogsRoute = "logs"
    const val SessionIdArgument = "sessionId"
    const val DetailRoute = "detail/{$SessionIdArgument}"

    private const val DetailPrefix = "detail/"

    fun detailRoute(sessionId: String): String = "$DetailPrefix${Uri.encode(sessionId)}"

    fun decodeSessionId(rawSessionId: String?): String? =
        rawSessionId
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode)
}
