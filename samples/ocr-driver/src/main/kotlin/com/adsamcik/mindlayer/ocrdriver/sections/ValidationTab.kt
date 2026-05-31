package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.ValidationSlice

@Composable
fun ValidationTab(
    slice: ValidationSlice,
    connected: Boolean,
    onRun: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Validation harness", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs every scenario in `ValidationRunner` (12 cases). Persists a JSON report.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = connected && !slice.inProgress) {
                    Text(if (slice.inProgress) "Running\u2026" else "Run validation")
                }
                slice.reportPath?.let { path ->
                    Text(
                        text = "report: $path",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
            slice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (slice.scenarios.isNotEmpty()) {
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Scenarios", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                slice.scenarios.forEach { s ->
                    val mark = if (s.ok) "\u2713" else "\u2717"
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
                val passed = slice.scenarios.count { it.ok }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Total: ${slice.scenarios.size}   \u2713 $passed   \u2717 ${slice.scenarios.size - passed}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}
