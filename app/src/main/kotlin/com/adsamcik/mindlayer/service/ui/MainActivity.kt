package com.adsamcik.mindlayer.service.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme

/**
 * F-029: ComponentActivity is sufficient for biometric auth now that AndroidX
 * Biometric 1.4 scopes registerForAuthenticationResult() to ComponentActivity.
 */
class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val historyViewModel: SessionHistoryViewModel by viewModels()
    private val detailViewModel: SessionDetailViewModel by viewModels()
    private val logsViewModel: RecentLogsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        window.decorView.filterTouchesWhenObscured = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S_V2) {
            try {
                window.setHideOverlayWindows(true)
            } catch (_: Throwable) {
                // OEM may have stripped the API — best-effort.
            }
        }
        enableEdgeToEdge()

        val authenticator: SensitiveActionAuthenticator =
            BiometricSensitiveActionAuthenticator(this)

        setContent {
            MindlayerTheme {
                CompositionLocalProvider(LocalSensitiveAuth provides authenticator) {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    val showBottomBar = currentRoute in MindlayerNavigation.TopLevelRoutes
                    Scaffold(
                        bottomBar = {
                            if (showBottomBar) {
                                MindlayerBottomBar(navController, currentRoute)
                            }
                        },
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = MindlayerNavigation.StatusRoute,
                            modifier = Modifier.padding(innerPadding),
                        ) {
                            composable(MindlayerNavigation.StatusRoute) {
                                val state by dashboardViewModel.uiState.collectAsState()
                                val logRepository = remember {
                                    LogRepository(LogDatabase.getInstance(this@MainActivity).logDao())
                                }
                                StatusScreen(
                                    state = state,
                                    onNavigateToHistory = {
                                        navController.navigate(MindlayerNavigation.HistoryRoute)
                                    },
                                    onNavigateToLogs = {
                                        navController.navigate(MindlayerNavigation.LogsRoute)
                                    },
                                    logRepository = logRepository,
                                    onRevokeApp = { pkg -> dashboardViewModel.revokeApp(pkg) },
                                )
                            }
                            composable(MindlayerNavigation.ModelsRoute) {
                                val state by dashboardViewModel.uiState.collectAsState()
                                ModelsScreen(state = state)
                            }
                            composable(MindlayerNavigation.TestsRoute) {
                                val state by dashboardViewModel.uiState.collectAsState()
                                val context = LocalContext.current
                                TestsScreen(
                                    state = state,
                                    onTestInference = { dashboardViewModel.runTestInference() },
                                    onTestEmbeddings = { dashboardViewModel.runEmbeddingTest() },
                                    onTestOcr = { dashboardViewModel.runOcrTest(context) },
                                    onTestImageInference = { dashboardViewModel.runImageInferenceTest(context) },
                                    onTestSdkInferAsync = { dashboardViewModel.runSdkInferAsyncTest(context) },
                                    onTestSdkInferRealtime = { dashboardViewModel.runSdkInferRealtimeTest(context) },
                                    onTestSdkGenerateWithImage = { dashboardViewModel.runSdkGenerateWithImageTest(context) },
                                    onTestOcrLlmExtraction = { dashboardViewModel.runOcrLlmExtractionTest(context) },
                                    onClearOcrFailureCache = { dashboardViewModel.clearOcrFailureCache() },
                                    onRunAllVerifications = { dashboardViewModel.runAllVerifications(context) },
                                )
                            }
                            composable(MindlayerNavigation.HistoryRoute) {
                                LaunchedEffect(Unit) {
                                    historyViewModel.loadSessions()
                                }
                                val state by historyViewModel.uiState.collectAsState()
                                SessionHistoryScreen(
                                    state = state,
                                    onSessionClick = { sessionId ->
                                        navController.navigate(
                                            MindlayerNavigation.detailRoute(sessionId),
                                        )
                                    },
                                    onBack = { navController.popBackStack() },
                                    onRetry = historyViewModel::loadSessions,
                                )
                            }
                            composable(
                                route = MindlayerNavigation.DetailRoute,
                                arguments = listOf(
                                    navArgument(MindlayerNavigation.SessionIdArgument) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) { detailBackStackEntry ->
                                val sessionId = MindlayerNavigation.decodeSessionId(
                                    detailBackStackEntry.arguments?.getString(
                                        MindlayerNavigation.SessionIdArgument,
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
                            composable(MindlayerNavigation.LogsRoute) {
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

@Composable
private fun MindlayerBottomBar(
    navController: NavHostController,
    currentRoute: String?,
) {
    NavigationBar {
        MindlayerNavigation.TopLevelTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val tabContentDescription = stringResource(tab.contentDescriptionRes)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.semantics { contentDescription = tabContentDescription },
            )
        }
    }
}

internal data class BottomNavTab(
    val route: String,
    val labelRes: Int,
    val contentDescriptionRes: Int,
    val icon: ImageVector,
)

internal object MindlayerNavigation {
    const val StatusRoute = "status"
    const val ModelsRoute = "models"
    const val TestsRoute = "tests"
    const val HistoryRoute = "history"
    const val LogsRoute = "logs"
    const val SessionIdArgument = "sessionId"
    const val DetailRoute = "detail/{$SessionIdArgument}"

    private const val DetailPrefix = "detail/"

    val TopLevelRoutes: Set<String> = setOf(StatusRoute, ModelsRoute, TestsRoute)

    internal val TopLevelTabs: List<BottomNavTab> = listOf(
        BottomNavTab(StatusRoute, R.string.nav_status, R.string.nav_a11y_status_tab, Icons.Filled.Info),
        BottomNavTab(ModelsRoute, R.string.nav_models, R.string.nav_a11y_models_tab, Icons.Filled.Build),
        BottomNavTab(TestsRoute, R.string.nav_tests, R.string.nav_a11y_tests_tab, Icons.Filled.PlayArrow),
    )

    fun detailRoute(sessionId: String): String = "$DetailPrefix${Uri.encode(sessionId)}"

    fun decodeSessionId(rawSessionId: String?): String? =
        rawSessionId
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode)
}
