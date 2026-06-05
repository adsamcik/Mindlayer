package com.adsamcik.mindlayer.ocrdriver

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.sections.ConnectionTab
import com.adsamcik.mindlayer.ocrdriver.sections.DiagnosticsTab
import com.adsamcik.mindlayer.ocrdriver.sections.EmbeddingsTab
import com.adsamcik.mindlayer.ocrdriver.sections.InferenceTab
import com.adsamcik.mindlayer.ocrdriver.sections.OcrTab
import com.adsamcik.mindlayer.ocrdriver.sections.ValidationTab

/**
 * Mindlayer driver — a hand-driven testbed for every AIDL surface the
 * SDK exposes. Six tabs map to the major capability groups:
 *
 *  1. **Connection** — bind/unbind, live capability listing with
 *     forceRefresh polling so the UI converges past cold-engine state.
 *  2. **Inference** — Gemma chat (text → text), maxTokens + temperature.
 *  3. **Embeddings** — text → vector (dim, L2, preview).
 *  4. **OCR** — single-image `ocrAsync` with bbox + optional LLM
 *     extraction toggle.
 *  5. **Diagnostics** — `getStatus`, `ping`, `getDiagnosticsTyped`,
 *     `listSessions`.
 *  6. **Validation** — batch [ValidationRunner] harness with persisted
 *     JSON report.
 *
 * Headless `am start --ez auto_run true` keeps working for adb-driven
 * CI — it runs the validation tab's flow without ever touching Compose.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: DriverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DriverScaffold(viewModel)
                }
            }
        }
        if (intent?.getBooleanExtra("auto_run", false) == true) {
            viewModel.connectAndRunValidationAutomated()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverScaffold(vm: DriverViewModel) {
    val state by vm.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Connection", "Inference", "Embeddings", "OCR", "Diagnostics", "Validation")

    // Launch the Mindlayer consent screen when the ViewModel asks, and feed the
    // result (approved / declined) back so it can retry the connection.
    val consentLauncher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        vm.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.consentPrompts.collect { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mindlayer driver") })
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.globalError?.let { msg ->
                        Card {
                            Text(
                                text = msg,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    when (selectedTab) {
                        0 -> ConnectionTab(
                            slice = state.connection,
                            onConnect = vm::connect,
                            onDisconnect = vm::disconnect,
                            onRefresh = vm::refreshCapabilities,
                            onRequestConsent = vm::requestConsent,
                        )
                        1 -> InferenceTab(
                            slice = state.inference,
                            connected = state.connection.state.isConnected,
                            onRun = vm::runInference,
                            onCancel = vm::cancelInference,
                        )
                        2 -> EmbeddingsTab(
                            slice = state.embeddings,
                            connected = state.connection.state.isConnected,
                            onRun = vm::runEmbed,
                        )
                        3 -> OcrTab(
                            slice = state.ocr,
                            connected = state.connection.state.isConnected,
                            onRun = vm::runOcrAsync,
                        )
                        4 -> DiagnosticsTab(
                            slice = state.diagnostics,
                            connected = state.connection.state.isConnected,
                            onRefresh = vm::refreshDiagnostics,
                        )
                        5 -> ValidationTab(
                            slice = state.validation,
                            connected = state.connection.state.isConnected,
                            onRun = vm::runValidation,
                        )
                    }
                }
            }
        }
    }
}

/** Tiny extension so each tab can gate its action buttons on connect. */
private val com.adsamcik.mindlayer.sdk.ConnectionState.isConnected: Boolean
    get() = this == com.adsamcik.mindlayer.sdk.ConnectionState.CONNECTED
