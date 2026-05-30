package com.adsamcik.mindlayer.service.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogRepository
import androidx.compose.ui.res.stringResource

/**
 * F-029: ComponentActivity is sufficient for biometric auth now that AndroidX
 * Biometric 1.4 scopes registerForAuthenticationResult() to ComponentActivity.
 * `viewModels()`, `enableEdgeToEdge()`, and `setContent` keep working while we
 * avoid carrying a fragment host only for the Approve/Revoke gate.
 */
class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val historyViewModel: SessionHistoryViewModel by viewModels()
    private val detailViewModel: SessionDetailViewModel by viewModels()
    private val logsViewModel: RecentLogsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // F-023: prevent screen recording / screenshot capture of the
        // dashboard. The dashboard renders session/request IDs and (until
        // F-006 fully lands) raw `errorMessage` strings that may contain
        // prompt fragments.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        // F-029: refuse touches that arrive through transparent overlays
        // — defends the (Approve/Revoke) buttons against tap-jacking.
        window.decorView.filterTouchesWhenObscured = true
        // F-029: hide system overlay windows from the auth surface (Android
        // 12 S_V2+). Keeps screen-overlay attacks from drawing arbitrary
        // graphics on top of the BiometricPrompt or the Approve/Revoke row.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S_V2) {
            try {
                window.setHideOverlayWindows(true)
            } catch (_: Throwable) {
                // OEM may have stripped the API — best-effort.
            }
        }
        enableEdgeToEdge()

        // F-029: the dashboard is the only place that triggers Approve/Revoke,
        // so we install one authenticator per Activity instance and provide
        // it via a CompositionLocal.
        val authenticator: SensitiveActionAuthenticator =
            BiometricSensitiveActionAuthenticator(this)

        setContent {
            MindlayerTheme {
                CompositionLocalProvider(LocalSensitiveAuth provides authenticator) {
                    val navController = rememberNavController()
                    Scaffold { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = SessionHistoryNavigation.DashboardRoute,
                            modifier = Modifier.padding(innerPadding),
                        ) {
                            composable(SessionHistoryNavigation.DashboardRoute) {
                                val state by dashboardViewModel.uiState.collectAsState()
                                val logRepository = remember {
                                    LogRepository(LogDatabase.getInstance(this@MainActivity).logDao())
                                }
                                val context = LocalContext.current
                                DashboardScreen(
                                    state = state,
                                    onTestInference = { dashboardViewModel.runTestInference() },
                                    onTestEmbeddings = { dashboardViewModel.runEmbeddingTest() },
                                    onTestOcr = { dashboardViewModel.runOcrTest(context) },
                                    onClearOcrFailureCache = { dashboardViewModel.clearOcrFailureCache() },
                                    onRunAllVerifications = { dashboardViewModel.runAllVerifications(context) },
                                    onNavigateToHistory = {
                                        navController.navigate(SessionHistoryNavigation.HistoryRoute)
                                    },
                                    onNavigateToLogs = {
                                        navController.navigate(SessionHistoryNavigation.LogsRoute)
                                    },
                                    logRepository = logRepository,
                                    onRevokeApp = { pkg -> dashboardViewModel.revokeApp(pkg) },
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
                                            errorMessage = stringResource(R.string.session_detail_invalid_route),
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
