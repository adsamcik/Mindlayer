package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.OcrSlice
import com.adsamcik.mindlayer.ocrdriver.OcrFixtures

@Composable
fun OcrTab(
    slice: OcrSlice,
    connected: Boolean,
    onRun: (fixture: String, runLlm: Boolean, emitBoundingBoxes: Boolean) -> Unit,
) {
    var fixture by remember { mutableStateOf(OcrFixtures.all[0]) }
    var runLlm by remember { mutableStateOf(false) }
    var emitBbox by remember { mutableStateOf(true) }

    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OCR (single image / async)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Calls `ocr { image(bytes, fixtureMime) }.awaitResult()`. Loads bundled WebP fixtures from the sample app's assets.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (slice.asyncAdvertised) {
                    "\u2705 FEATURE_OCR_IMAGE_ONESHOT advertised"
                } else {
                    "\u274C FEATURE_OCR_IMAGE_ONESHOT not yet advertised (engine warming up?)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Fixture:", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OcrFixtures.all.forEach { name ->
                    FilterChip(
                        selected = fixture == name,
                        onClick = { fixture = name },
                        label = { Text(name) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = emitBbox, onCheckedChange = { emitBbox = it })
                Text("Emit bounding boxes", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = runLlm, onCheckedChange = { runLlm = it })
                Text("Run LLM extraction (Gemma)", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onRun(fixture, runLlm, emitBbox) },
                enabled = connected && !slice.inProgress,
            ) {
                Text(if (slice.inProgress) "Recognising\u2026" else "Run OCR")
            }
            slice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (slice.lastLineCount > 0 || slice.lastFixture.isNotEmpty()) {
                Text("Last result (${slice.lastFixture})", style = MaterialTheme.typography.titleSmall)
                Text(
                    "lines=${slice.lastLineCount}  withBbox=${slice.lastWithBbox}  " +
                        "ocr=${slice.lastOcrMs}ms  llm=${slice.lastLlmMs}ms  " +
                        "fields=${slice.lastFields}  total=${slice.lastTotalMs}ms",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (slice.lastPreview.isNotEmpty()) {
                    Text(
                        slice.lastPreview,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
