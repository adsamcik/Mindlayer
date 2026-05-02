package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CertificateMismatchException
import com.adsamcik.mindlayer.service.security.PendingApproval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AllowedAppsCard"

/**
 * Minimal dashboard section showing approved caller apps and pending
 * approval requests. Writes directly through an [AllowlistStore] bound to
 * the application context.
 *
 * Sensitive Approve / Revoke actions are gated by [SensitiveActionAuthenticator]
 * (F-029) and the Approve flow re-verifies the live signing certificate at
 * tap time (F-031). Cert-rotation pending rows render a banner + "I understand"
 * confirmation gate (F-032).
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
    val pending by store.pending.collectAsState()
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // The :ml service process writes pending approvals into the shared file
    // when an un-approved caller tries to connect. Our StateFlow only reflects
    // what this process has written, so poll the file to surface cross-process
    // updates in the dashboard UI.
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
                text = "Allowed apps",
                style = MaterialTheme.typography.titleMedium,
            )

            if (pending.isNotEmpty()) {
                Text(
                    text = "Pending approvals (${pending.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                pending.forEach { p ->
                    PendingRow(
                        entry = p,
                        onApprove = {
                            authenticator.authenticate(SensitiveAction.APPROVE_CALLER) { granted, _, _ ->
                                if (!granted) return@authenticate
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        store.approve(
                                            ctx,
                                            p.packageName,
                                            p.signingCertSha256,
                                            p.displayName,
                                        )
                                        // F-032: distinct audit event when the
                                        // user approves a sig rotation.
                                        val action = if (p.previousSigSha256 == null) {
                                            "approve"
                                        } else {
                                            "approve_after_cert_rotation"
                                        }
                                        logRepository?.logSecurityDecision(
                                            action = action,
                                            packageName = p.packageName,
                                            sigShaPrefix = p.signingCertSha256.take(8),
                                            extra = p.previousSigSha256?.take(8)?.let { "prev=$it" },
                                        )
                                    } catch (e: CertificateMismatchException) {
                                        MindlayerLog.w(
                                            TAG,
                                            "Approve blocked: live sig changed for ${e.pkg}",
                                        )
                                        logRepository?.logSecurityDecision(
                                            action = "approve_blocked_cert_mismatch",
                                            packageName = e.pkg,
                                            sigShaPrefix = e.expectedSig.take(8),
                                            extra = "live=${e.liveSig.take(8)}",
                                        )
                                        store.refresh()
                                    } catch (e: SecurityException) {
                                        MindlayerLog.w(
                                            TAG,
                                            "Approve failed: ${e.javaClass.simpleName}",
                                        )
                                        logRepository?.logSecurityDecision(
                                            action = "approve_blocked",
                                            packageName = p.packageName,
                                            sigShaPrefix = p.signingCertSha256.take(8),
                                            extra = e.javaClass.simpleName,
                                        )
                                    }
                                }
                            }
                        },
                        onDeny = {
                            authenticator.authenticate(SensitiveAction.REVOKE_CALLER) { granted, _, _ ->
                                if (!granted) return@authenticate
                                scope.launch(Dispatchers.IO) {
                                    store.denyPending(p.packageName)
                                    logRepository?.logSecurityDecision(
                                        action = "deny_pending",
                                        packageName = p.packageName,
                                        sigShaPrefix = p.signingCertSha256.take(8),
                                    )
                                }
                            }
                        },
                    )
                }
                HorizontalDivider()
            }

            if (entries.isEmpty()) {
                Text(
                    text = "No apps have been approved yet.",
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
private fun PendingRow(
    entry: PendingApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // F-032: cert-rotation banner.
        if (entry.previousSigSha256 != null) {
            CertRotationBanner(
                previousSig = entry.previousSigSha256,
                newSig = entry.signingCertSha256,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallerIdentityColumn(
                packageName = entry.packageName,
                displayName = entry.displayName,
                signingCertSha256 = entry.signingCertSha256,
                modifier = Modifier.weight(1f),
            )
            ApproveDenyButtons(
                isCertRotation = entry.previousSigSha256 != null,
                onApprove = onApprove,
                onDeny = onDeny,
            )
        }
    }
}

@Composable
private fun ApproveDenyButtons(
    isCertRotation: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    // F-032: gate Approve behind an "I understand" tap when this is a
    // signing-key rotation. Reset to ungated when the entry changes (because
    // `remember` is keyed to the row's own composition).
    var confirmed by remember(isCertRotation) { mutableStateOf(!isCertRotation) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onDeny) { Text("Deny") }
        if (isCertRotation && !confirmed) {
            TextButton(onClick = { confirmed = true }) {
                Text(stringResource(R.string.allowlist_understand_signing_change))
            }
        } else {
            OutlinedButton(
                onClick = onApprove,
                enabled = confirmed,
                colors = if (isCertRotation) {
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(if (isCertRotation) "Approve replacement" else "Approve")
            }
        }
    }
}

@Composable
private fun CertRotationBanner(previousSig: String, newSig: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.allowlist_signing_key_changed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.allowlist_signing_key_changed_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "was: ${previousSig.uppercase().take(16)}…",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "now: ${newSig.uppercase().take(16)}…",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: AllowlistEntry,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallerIdentityColumn(
            packageName = entry.packageName,
            displayName = entry.displayName,
            signingCertSha256 = entry.signingCertSha256,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevoke) { Text("Revoke") }
    }
}

/**
 * F-030: package name as PRIMARY identity (monospace), claimed label as
 * SECONDARY (prefixed `claimed: `), full 64-char SHA-256 in 4-char groups,
 * tap-to-copy via [LocalClipboardManager]. Truncated SHA prefixes (`…`) are
 * a near-miss-detection liability and have been removed.
 */
@Composable
private fun CallerIdentityColumn(
    packageName: String,
    displayName: String?,
    signingCertSha256: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        displayName?.let { name ->
            Text(
                text = stringResource(R.string.allowlist_claimed_label, name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SigFingerprintRow(sha = signingCertSha256)
    }
}

@Composable
private fun SigFingerprintRow(sha: String) {
    val clipboard = LocalClipboardManager.current
    val grouped = remember(sha) {
        sha.uppercase().chunked(4).joinToString(" ")
    }
    val a11y = stringResource(R.string.allowlist_sig_fingerprint_a11y)
    Text(
        text = grouped,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { clipboard.setText(AnnotatedString(sha)) }
            .semantics { contentDescription = a11y },
        softWrap = true,
    )
}

@Composable
private fun rememberAllowlistStore(): AllowlistStore {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AllowlistStore(ctx) }
}
