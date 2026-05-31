package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.InferenceSlice

@Composable
fun InferenceTab(
    slice: InferenceSlice,
    connected: Boolean,
    onRun: (prompt: String, maxTokens: Int, temperature: Float) -> Unit,
    onCancel: () -> Unit,
) {
    var prompt by remember { mutableStateOf("Say hi in five words.") }
    var maxTokens by remember { mutableIntStateOf(256) }
    var temperature by remember { mutableFloatStateOf(0.7f) }

    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Inference (Gemma)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Creates a short-lived session, runs `inferAsync`, destroys the session. Times out after 120 s.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Text("maxTokens = $maxTokens (SDK accepts 128 \u2013 8192)", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = maxTokens.toFloat(),
                onValueChange = { maxTokens = it.toInt() },
                valueRange = 128f..2048f,
                steps = 14,
            )
            Text("temperature = %.2f".format(temperature), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..1.5f,
                steps = 14,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRun(prompt, maxTokens, temperature) },
                    enabled = connected && !slice.inProgress,
                ) {
                    Text(if (slice.inProgress) "Running\u2026" else "Generate")
                }
                OutlinedButton(onClick = onCancel, enabled = slice.inProgress) {
                    Text("Cancel")
                }
            }
        }
    }

    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Output", style = MaterialTheme.typography.titleMedium)
            if (slice.durationMs > 0L) {
                Text("\u23F1 ${slice.durationMs} ms", style = MaterialTheme.typography.bodySmall)
            }
            slice.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (slice.response.isNotEmpty()) {
                Text(
                    text = slice.response,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!slice.inProgress && slice.error == null) {
                Text(
                    "(no response yet \u2014 connect + press Generate)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
