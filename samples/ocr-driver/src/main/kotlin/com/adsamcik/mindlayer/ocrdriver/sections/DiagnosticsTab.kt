package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.DiagnosticsSlice

@Composable
fun DiagnosticsTab(
    slice: DiagnosticsSlice,
    connected: Boolean,
    onRefresh: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aggregates `getStatus()`, `ping()` (health check), `getDiagnosticsTyped()`, and `listSessions()`.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRefresh, enabled = connected && !slice.inProgress) {
                Text(if (slice.inProgress) "Refreshing\u2026" else "Refresh diagnostics")
            }
            slice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    slice.status?.let { st ->
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ServiceStatus", style = MaterialTheme.typography.titleSmall)
                val lines = listOf(
                    "engineLoaded=${st.isEngineLoaded}  warming=${st.engineWarming}",
                    "activeSessions=${st.activeSessionCount}/${st.maxSessions}  inflightInf=${st.activeInferenceCount}",
                    "backend=${st.backend}  thermal=${st.thermalBand}  foreground=${st.isForeground}",
                    "uptime=${st.uptimeMs}ms  memPressure=${st.memoryPressure}",
                    "ram avail=${st.availableRamMb}MB  total=${st.totalRamMb}MB",
                )
                lines.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    slice.ping?.let { hc ->
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("HealthCheck (ping)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "apiVersion=${hc.apiVersion}  uptime=${hc.serviceUptimeMs}ms  " +
                        "allReady=${hc.allEnginesReady}  anyReady=${hc.anyEngineReady}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "engines  llm=${engineLabel(hc.llmEngineState)}  " +
                        "emb=${engineLabel(hc.embeddingEngineState)}  ocr=${engineLabel(hc.ocrEngineState)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (slice.sessionPreview.isNotEmpty() || slice.sessionCount > 0) {
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sessions (${slice.sessionCount})", style = MaterialTheme.typography.titleSmall)
                if (slice.sessionPreview.isEmpty()) {
                    Text("(none active)", style = MaterialTheme.typography.bodySmall)
                } else {
                    slice.sessionPreview.forEach { row ->
                        Text(row, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    slice.typedDiagJson?.let { json ->
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DiagnosticsSnapshot (typed)", style = MaterialTheme.typography.titleSmall)
                Text(json, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun engineLabel(state: Int): String = when (state) {
    0 -> "idle"
    1 -> "warming"
    2 -> "ready"
    3 -> "failed"
    else -> "?$state"
}
