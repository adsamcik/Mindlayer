package com.adsamcik.mindlayer.ocrdriver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.Mindlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal Compose UI for the OCR driver / validation harness.
 *
 * Single screen with three sections:
 *  1. **Connection** — bind / unbind from the Mindlayer service, surface
 *     `ConnectionState` and the advertised OCR capability flags.
 *  2. **Run validation** — execute every scenario in [ValidationRunner]
 *     and pull the per-scenario pass/fail + timing inline.
 *  3. **Report** — exposes the path to the persisted `report.json` so
 *     the developer can `adb pull` it for archival.
 *
 * No CameraX preview, no permissions other than `BIND_ML_SERVICE`. The
 * "realtime" scenarios drive the OCR session pipeline with pre-bundled
 * image fixtures pushed as encoded frames — exactly what a CameraX
 * adapter would emit on the wire, but deterministic and emulator-friendly.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: DriverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DriverScreen(viewModel)
                }
            }
        }
        // Headless validation entry point — `adb shell am start -n
        // com.adsamcik.mindlayer.ocrdriver/.MainActivity --ez auto_run true`
        // connects + runs the full validation suite and dumps the report path
        // to logcat tag `OcrDriverAuto` so CI / automation can pull it
        // without driving the Compose UI.
        if (intent?.getBooleanExtra("auto_run", false) == true) {
            viewModel.connectAndRunValidationAutomated()
        }
    }
}

@Composable
private fun DriverScreen(vm: DriverViewModel) {
    val state by vm.uiState.collectAsState()
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mindlayer OCR Driver", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Validation harness for the v0.9 OCR production-readiness flip.",
            style = MaterialTheme.typography.bodySmall,
        )

        ConnectionCard(state, onConnect = vm::connect, onDisconnect = vm::disconnect)

        if (state.connectionState == ConnectionState.CONNECTED) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = vm::runValidation,
                    enabled = !state.validationInProgress,
                ) {
                    Text(if (state.validationInProgress) "Running…" else "Run validation")
                }
                state.reportPath?.let { path ->
                    OutlinedButton(onClick = {}) {
                        Text("Report: $path", maxLines = 1)
                    }
                }
            }
        }

        if (state.scenarios.isNotEmpty()) {
            ScenarioList(state.scenarios)
        }

        state.errorMessage?.let { msg ->
            Card(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: DriverUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text("state: ${state.connectionState}")
            if (state.capabilities.isNotEmpty()) {
                Text("OCR capabilities advertised:")
                state.capabilities.forEach { cap ->
                    val emoji = if (cap.advertised) "✅" else "❌"
                    Text("  $emoji ${cap.name}", fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = state.connectionState != ConnectionState.CONNECTED,
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = state.connectionState == ConnectionState.CONNECTED,
                ) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun ScenarioList(scenarios: List<ValidationScenarioResult>) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Scenarios", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(scenarios) { s ->
                    val mark = if (s.ok) "✓" else "✗"
                    Text(
                        text = "$mark ${s.name}  (${s.durationMs} ms)  ${s.note ?: ""}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.ok) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            val passed = scenarios.count { it.ok }
            Spacer(Modifier.height(8.dp))
            Text(
                "Total: ${scenarios.size}   ✓ $passed   ✗ ${scenarios.size - passed}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

// ── ViewModel ───────────────────────────────────────────────────────────

data class CapabilityRow(val name: String, val advertised: Boolean)

data class DriverUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val capabilities: List<CapabilityRow> = emptyList(),
    val scenarios: List<ValidationScenarioResult> = emptyList(),
    val validationInProgress: Boolean = false,
    val reportPath: String? = null,
    val errorMessage: String? = null,
)

class DriverViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _ui.asStateFlow()

    private var mindlayer: Mindlayer? = null

    fun connect() {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(errorMessage = null)
                val client = Mindlayer.connect(getApplication())
                mindlayer = client
                client.awaitConnected()
                val caps = client.getCapabilities().supportedFeatures
                val rows = listOf(
                    CapabilityRow(
                        ServiceCapabilities.FEATURE_OCR_SESSION,
                        ServiceCapabilities.FEATURE_OCR_SESSION in caps,
                    ),
                    CapabilityRow(
                        ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT,
                        ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT in caps,
                    ),
                    CapabilityRow(
                        ServiceCapabilities.FEATURE_OCR_PRESORT_SERVICE_SIDE,
                        ServiceCapabilities.FEATURE_OCR_PRESORT_SERVICE_SIDE in caps,
                    ),
                    CapabilityRow(
                        ServiceCapabilities.FEATURE_OCR_BARCODE_ANCHOR,
                        ServiceCapabilities.FEATURE_OCR_BARCODE_ANCHOR in caps,
                    ),
                    CapabilityRow(
                        ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES,
                        ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES in caps,
                    ),
                )
                _ui.value = _ui.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    capabilities = rows,
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    errorMessage = "Connect failed: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    fun disconnect() {
        mindlayer = null
        _ui.value = DriverUiState()
    }

    fun runValidation() {
        val client = mindlayer ?: run {
            _ui.value = _ui.value.copy(errorMessage = "Not connected")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                validationInProgress = true,
                scenarios = emptyList(),
                errorMessage = null,
            )
            try {
                val runner = ValidationRunner(getApplication(), client)
                val report = runner.runAll()
                val file = runner.writeReport(report)
                _ui.value = _ui.value.copy(
                    scenarios = report.scenarios,
                    validationInProgress = false,
                    reportPath = file.absolutePath,
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    validationInProgress = false,
                    errorMessage = "Validation aborted: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    /**
     * Headless entry point used by `am start ... --ez auto_run true`.
     * Connects, runs every scenario in [ValidationRunner], writes the JSON
     * report, and logs the final report path + per-scenario outcome to
     * logcat under tag `OcrDriverAuto` so adb-driven automation can pull
     * the report without ever touching the Compose UI.
     */
    fun connectAndRunValidationAutomated() {
        viewModelScope.launch {
            try {
                android.util.Log.i("OcrDriverAuto", "Auto-run: connecting…")
                val client = com.adsamcik.mindlayer.sdk.Mindlayer.connect(getApplication())
                mindlayer = client
                client.awaitConnected()
                val caps = client.getCapabilities().supportedFeatures
                android.util.Log.i(
                    "OcrDriverAuto",
                    "Auto-run: connected. ocr_session=${com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION in caps}, " +
                        "ocr_image_oneshot=${com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT in caps}",
                )
                val runner = ValidationRunner(getApplication(), client)
                val report = runner.runAll()
                val file = runner.writeReport(report)
                android.util.Log.i(
                    "OcrDriverAuto",
                    "Auto-run: complete. report=${file.absolutePath} passed=${report.passed} failed=${report.failed} total=${report.total}",
                )
                report.scenarios.forEach { s ->
                    android.util.Log.i(
                        "OcrDriverAuto",
                        "Auto-run scenario: ${s.name} ok=${s.ok} ${s.durationMs}ms note=${s.note ?: ""}",
                    )
                }
                _ui.value = _ui.value.copy(
                    scenarios = report.scenarios,
                    validationInProgress = false,
                    reportPath = file.absolutePath,
                )
            } catch (t: Throwable) {
                android.util.Log.e(
                    "OcrDriverAuto",
                    "Auto-run aborted: ${t.javaClass.simpleName}: ${t.message}",
                )
                _ui.value = _ui.value.copy(
                    validationInProgress = false,
                    errorMessage = "Auto-run aborted: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }
}
