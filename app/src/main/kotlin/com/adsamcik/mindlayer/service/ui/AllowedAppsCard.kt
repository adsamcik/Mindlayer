package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.mindlayer.service.BuildConfig
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.debugAutoAcceptAllEnabled
import com.adsamcik.mindlayer.service.security.debugSetAutoAcceptAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Formats a 64-char hex SHA-256 cert fingerprint into spaced 8-char groups
 * for human readability, e.g. "abcdef01 23456789 ...".
 *
 * Extracted so it can be unit-tested without a Compose harness.
 */
internal fun formatCertHash(sha: String): String {
    if (sha.isBlank()) return "(missing)"
    if (sha.length < 64) return sha
    return sha.chunked(8).joinToString(" ")
}

/**
 * DEBUG-only dashboard switch for the "auto-accept all callers" developer
 * toggle. Hidden in release builds (gated by [BuildConfig.DEBUG] at the call
 * site; the release seam is a no-op regardless). Flipping it on lets any
 * bind-authorized app skip the interactive consent prompt — explicitly denied
 * apps stay blocked. The same flag can be flipped headlessly via
 * `DebugAutoAcceptReceiver` (adb broadcast) for CI.
 */
@Composable
private fun DebugAutoAcceptToggle() {
    val ctx = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(debugAutoAcceptAllEnabled(ctx)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_auto_accept_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.debug_auto_accept_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { requested ->
                // The sentinel write is a single tiny file op; reflect the
                // *actual* on-disk result so a failed write never lies.
                enabled = debugSetAutoAcceptAll(ctx, requested)
            },
        )
    }
}

/**
 * Minimal dashboard section showing approved caller apps. Writes directly
 * through an [AllowlistStore] bound to the application context.
 *
 * Sensitive Revoke actions are gated by [SensitiveActionAuthenticator] (F-029).
 */
@Composable
fun AllowedAppsCard(
    store: AllowlistStore = rememberAllowlistStore(),
    authenticator: SensitiveActionAuthenticator = LocalSensitiveAuth.current,
    logRepository: LogRepository? = null,
    /**
     * F-055: when supplied, revoke flows through the cross-process AIDL so
     * the `:ml` service can tear down owned sessions and write the audit log
     * row. If null (e.g. unit tests, preview), the legacy direct-store path
     * is used — the file write still propagates, only session teardown is
     * deferred to the next caller request.
     */
    onRevokeAidl: ((packageName: String) -> Unit)? = null,
) {
    val entries by store.entries.collectAsState()
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // The :ml service process can write allowlist changes through AIDL.
    // Poll the file so this process's StateFlow reflects cross-process updates.
    androidx.compose.runtime.LaunchedEffect(store) {
        while (true) {
            store.refresh()
            kotlinx.coroutines.delay(2_000L)
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.allowed_apps_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (BuildConfig.DEBUG) {
                DebugAutoAcceptToggle()
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.allowed_apps_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { e ->
                    EntryRow(
                        entry = e,
                        onRevoke = {
                            authenticator.authenticate(SensitiveAction.REVOKE_CALLER) { granted, _, _ ->
                                if (!granted) return@authenticate
                                scope.launch(Dispatchers.IO) {
                                    if (onRevokeAidl != null) {
                                        // F-055: cross-process revoke via AIDL.
                                        // The service performs the file write,
                                        // session teardown, and audit logging.
                                        onRevokeAidl(e.packageName)
                                    } else {
                                        store.revoke(e.packageName)
                                        logRepository?.logSecurityDecision(
                                            action = "revoke",
                                            packageName = e.packageName,
                                            sigShaPrefix = e.signingCertSha256.take(8),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RevokeConfirmDialog(
    packageName: String,
    certHash: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.allowlist_revoke_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.allowlist_revoke_body, packageName),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text = stringResource(R.string.allowlist_package_label), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = packageName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Text(text = stringResource(R.string.allowlist_signing_fingerprint_label), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = formatCertHash(certHash),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.allowlist_revoke_package, packageName))
            }
        },
    )
}

@Composable
private fun EntryRow(
    entry: AllowlistEntry,
    onRevoke: () -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        RevokeConfirmDialog(
            packageName = entry.packageName,
            certHash = entry.signingCertSha256,
            onConfirm = {
                showDialog = false
                onRevoke()
            },
            onDismiss = { showDialog = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Package name is the authoritative primary identity.
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            if (entry.displayName != null) {
                Text(
                    text = stringResource(R.string.allowlist_claims_to_be, entry.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = formatCertHash(entry.signingCertSha256),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { showDialog = true }) { Text(stringResource(R.string.allowlist_revoke)) }
    }
}

@Composable
private fun rememberAllowlistStore(): AllowlistStore {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AllowlistStore(ctx) }
}
