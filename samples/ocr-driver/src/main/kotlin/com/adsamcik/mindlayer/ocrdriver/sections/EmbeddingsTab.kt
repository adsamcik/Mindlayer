package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ocrdriver.EmbeddingsSlice

@Composable
fun EmbeddingsTab(
    slice: EmbeddingsSlice,
    connected: Boolean,
    onRun: (text: String) -> Unit,
) {
    var text by remember { mutableStateOf("The cat sits on the mat.") }

    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Embeddings (EmbeddingGemma)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Calls `embedOne(text)` and reports vector dim, L2 norm, and the first 8 values.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (slice.advertised) {
                    "\u2705 FEATURE_EMBEDDINGS advertised \u2014 models=${slice.embeddingModelIds} dims=${slice.embeddingDims}"
                } else {
                    "\u274C FEATURE_EMBEDDINGS not yet advertised (engine warming up?)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = { onRun(text) },
                enabled = connected && !slice.inProgress,
            ) {
                Text(if (slice.inProgress) "Embedding\u2026" else "Embed")
            }
            slice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (slice.lastDim > 0 && slice.error == null) {
                Text("Last result", style = MaterialTheme.typography.titleSmall)
                Text(
                    "dim=${slice.lastDim}  L2=%.4f  duration=${slice.lastDurationMs}ms".format(slice.lastL2Norm),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "first8 = [${slice.lastFirstFew}]",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
