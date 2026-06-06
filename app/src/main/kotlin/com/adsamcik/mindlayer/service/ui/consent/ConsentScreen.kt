package com.adsamcik.mindlayer.service.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.ConsentIdentity
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme

/** Result callbacks fired by the consent UI. */
class ConsentActions(
    val onApprove: () -> Unit,
    val onDenyOnce: () -> Unit,
    val onDeny24h: () -> Unit,
    val onDenyPermanent: () -> Unit,
)

@Composable
fun ConsentLoading() {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ConsentError(expired: Boolean, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (expired) R.string.consent_error_expired else R.string.consent_error_generic,
            ),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss) { Text(stringResource(R.string.consent_cancel)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(
    identity: ConsentIdentity,
    submitting: Boolean,
    actions: ConsentActions,
) {
    var showDenySheet by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showFullCert by remember { mutableStateOf(false) }

    val label = identity.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.consent_app_label_fallback)
    val mixedScript = remember(identity.displayName) {
        MixedScriptDetector.isMixedScript(identity.displayName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.consent_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.consent_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        // App label — the primary identity signal.
        Text(text = label, style = MaterialTheme.typography.titleLarge)
        if (mixedScript) {
            Spacer(Modifier.height(8.dp))
            WarningCard(text = stringResource(R.string.consent_mixed_script_warning))
        }
        Spacer(Modifier.height(16.dp))

        // Verified identity details, grouped into one card for scannability.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                LabeledValue(
                    label = stringResource(R.string.consent_package_label),
                    value = identity.packageName,
                    monospace = true,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                LabeledValue(
                    label = stringResource(R.string.consent_source_label),
                    value = installSourceText(identity.installSource),
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                // Cert fingerprint (truncated, expandable)
                Text(
                    text = stringResource(R.string.consent_cert_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val certA11y = stringResource(R.string.consent_cert_a11y)
                Text(
                    text = if (showFullCert) groupHex(identity.signingCertSha256)
                    else identity.signingCertSha256.take(16) + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = certA11y },
                )
                if (!showFullCert) {
                    TextButton(onClick = { showFullCert = true }) {
                        Text(stringResource(R.string.consent_cert_show_full))
                    }
                }
            }
        }

        if (identity.previousSigSha256 != null) {
            Spacer(Modifier.height(16.dp))
            RotationBanner()
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = actions.onApprove,
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.consent_approve))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showDenySheet = true },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.consent_deny))
        }
    }

    if (showDenySheet) {
        ModalBottomSheet(onDismissRequest = { showDenySheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                TextButton(
                    onClick = { showDenySheet = false; actions.onDenyOnce() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.consent_deny_once)) }
                TextButton(
                    onClick = { showDenySheet = false; actions.onDeny24h() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.consent_deny_24h)) }
                TextButton(
                    onClick = { showDenySheet = false; showBlockConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.consent_deny_permanent)) }
            }
        }
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(stringResource(R.string.consent_deny_permanent_confirm_title)) },
            text = { Text(stringResource(R.string.consent_deny_permanent_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showBlockConfirm = false; actions.onDenyPermanent() }) {
                    Text(stringResource(R.string.consent_deny_permanent_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text(stringResource(R.string.consent_cancel))
                }
            },
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun RotationBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.consent_rotation_banner_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.consent_rotation_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun installSourceText(installSource: String?): String = when (installSource) {
    null, "" -> stringResource(R.string.consent_source_unknown)
    "com.android.vending" -> stringResource(R.string.consent_source_play)
    "com.android.shell", "com.google.android.packageinstaller",
    "com.android.packageinstaller" -> stringResource(R.string.consent_source_sideload)
    else -> stringResource(R.string.consent_source_other, installSource)
}

/** Group a 64-char hex string into space-separated quads for readability. */
private fun groupHex(hex: String): String =
    hex.chunked(4).joinToString(" ")

@Preview(showBackground = true, name = "Consent prompt — sideload")
@Composable
private fun ConsentScreenPreview() {
    MindlayerTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ConsentScreen(
                identity = ConsentIdentity(
                    packageName = "com.adsamcik.starlitcoffee.debug",
                    displayName = "Starlit Coffee (Debug)",
                    signingCertSha256 = "664735c79928241a" + "b".repeat(48),
                    installSource = null,
                    previousSigSha256 = null,
                    expiresAtMs = 0L,
                ),
                submitting = false,
                actions = ConsentActions(
                    onApprove = {},
                    onDenyOnce = {},
                    onDeny24h = {},
                    onDenyPermanent = {},
                ),
            )
        }
    }
}

