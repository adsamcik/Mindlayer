package com.adsamcik.mindlayer.ocrdriver.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.ocrdriver.ConnectionSlice
import com.adsamcik.mindlayer.sdk.ConnectionState

private val FEATURE_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Protocol / transport" to listOf(
        ServiceCapabilities.FEATURE_PIPE_PROTO_V1,
        ServiceCapabilities.FEATURE_PIPE_STREAM_V1,
        ServiceCapabilities.FEATURE_SHARED_MEMORY_MEDIA,
        ServiceCapabilities.FEATURE_TYPED_ERRORS,
        ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS,
        ServiceCapabilities.FEATURE_TOKEN_BATCH,
    ),
    "Inference (Gemma)" to listOf(
        ServiceCapabilities.FEATURE_TOOL_RESULTS,
        ServiceCapabilities.FEATURE_STRUCTURED_OUTPUT,
        ServiceCapabilities.FEATURE_MEDIA_LIST,
        ServiceCapabilities.FEATURE_DETAILED_CANCEL,
        ServiceCapabilities.FEATURE_PREWARM_AWAIT,
        ServiceCapabilities.FEATURE_EVICTION_CALLBACK,
        ServiceCapabilities.FEATURE_DEFERRED_INFERENCE,
        ServiceCapabilities.FEATURE_HISTORY_RECOVERY,
    ),
    "Embeddings (EmbeddingGemma)" to listOf(
        ServiceCapabilities.FEATURE_EMBEDDINGS,
    ),
    "OCR (PaddleOCR + Gemma extractor)" to listOf(
        ServiceCapabilities.FEATURE_OCR_SESSION,
        ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT,
        ServiceCapabilities.FEATURE_OCR_PRESORT_SERVICE_SIDE,
        ServiceCapabilities.FEATURE_OCR_BARCODE_ANCHOR,
        ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES,
        ServiceCapabilities.FEATURE_OCR_LOGPROB_FUSION,
        ServiceCapabilities.FEATURE_OCR_TRUE_MULTI_IMAGE,
    ),
    "Health" to listOf(
        ServiceCapabilities.FEATURE_HEALTH_CHECK,
    ),
)

@Composable
fun ConnectionTab(
    slice: ConnectionSlice,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text("state: ${slice.state}")
            if (slice.apiVersion > 0) {
                Text(
                    "apiVersion=${slice.apiVersion} • maxRpm=${slice.maxRequestsPerMinute} • " +
                        "maxSessions=${slice.maxConcurrentSessions}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = slice.state != ConnectionState.CONNECTED) {
                    Text("Connect")
                }
                OutlinedButton(onClick = onDisconnect, enabled = slice.state == ConnectionState.CONNECTED) {
                    Text("Disconnect")
                }
                TextButton(onClick = onRefresh, enabled = slice.state == ConnectionState.CONNECTED) {
                    Text("Refresh caps")
                }
            }
            slice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (slice.allFeatures.isNotEmpty()) {
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                if (slice.lastProbedAtMs > 0) {
                    val ageSec = ((System.currentTimeMillis() - slice.lastProbedAtMs) / 1000)
                        .coerceAtLeast(0)
                    Text(
                        "Probed ${ageSec}s ago — auto-polled at 1 Hz for 30 s after connect to ride out engine warm-up. " +
                            "Tap \u201CRefresh caps\u201D to force-refresh now.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val seen = slice.allFeatures.toHashSet()
                FEATURE_GROUPS.forEach { (groupLabel, features) ->
                    Text(groupLabel, style = MaterialTheme.typography.titleSmall)
                    features.forEach { feature ->
                        val emoji = if (feature in seen) "\u2705" else "\u274C"
                        Text(
                            "  $emoji $feature",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val grouped = FEATURE_GROUPS.flatMap { it.second }.toHashSet()
                val unknown = slice.allFeatures.filter { it !in grouped }
                if (unknown.isNotEmpty()) {
                    Text("Newly added features", style = MaterialTheme.typography.titleSmall)
                    unknown.forEach { feature ->
                        Text(
                            "  \u2705 $feature",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
