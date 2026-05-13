package com.adsamcik.mindlayer.service.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Resolves the install source for [packageName], or returns a fallback string. */
private fun resolveInstallSource(pm: PackageManager, packageName: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown / sideload (API<30)"
    return try {
        pm.getInstallSourceInfo(packageName).initiatingPackageName
            ?: "unknown / sideload"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown (package not found)"
    } catch (_: SecurityException) {
        "unknown (permission denied)"
    }
}

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
                text = stringResource(R.string.allowed_apps_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (pending.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.pending_approvals_count, pending.size),
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
private fun PendingRow(
    entry: PendingApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val installSource = remember(entry.packageName) {
        resolveInstallSource(context.packageManager, entry.packageName)
    }

    if (showDialog) {
        ApproveConfirmDialog(
            packageName = entry.packageName,
            certHash = entry.signingCertSha256,
            installSource = installSource,
            onConfirm = {
                showDialog = false
                onApprove()
            },
            onDismiss = { showDialog = false },
        )
    }

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
                Text(
                    text = stringResource(R.string.allowlist_installed_from, installSource),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = formatCertHash(entry.signingCertSha256),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ApproveDenyButtons(
                isCertRotation = entry.previousSigSha256 != null,
                onApprove = {
                    if (entry.previousSigSha256 != null) {
                        // Cert rotation: user already passed the "I understand" gate.
                        onApprove()
                    } else {
                        showDialog = true
                    }
                },
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
        TextButton(onClick = onDeny) { Text(stringResource(R.string.allowlist_deny)) }
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
                text = stringResource(R.string.allowlist_previous_sig, previousSig.uppercase().take(16)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.allowlist_new_sig, newSig.uppercase().take(16)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ApproveConfirmDialog(
    packageName: String,
    certHash: String,
    installSource: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.allowlist_approve_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.allowlist_spoof_warning),
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
                Text(text = stringResource(R.string.allowlist_install_source, installSource), style = MaterialTheme.typography.bodySmall)
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
                Text(stringResource(R.string.allowlist_approve_package, packageName))
            }
        },
    )
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
