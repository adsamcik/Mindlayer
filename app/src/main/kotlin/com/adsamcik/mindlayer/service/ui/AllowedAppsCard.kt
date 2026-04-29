package com.adsamcik.mindlayer.service.ui

import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.PendingApproval

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
 * the application context. Intentionally dependency-free of the main
 * [DashboardViewModel] so it can be dropped into the existing screen
 * without refactoring.
 */
@Composable
fun AllowedAppsCard(
    store: AllowlistStore = rememberAllowlistStore(),
) {
    val entries by store.entries.collectAsState()
    val pending by store.pending.collectAsState()

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
                        onApprove = { store.approve(p.packageName, p.signingCertSha256, p.displayName) },
                        onDeny = { store.denyPending(p.packageName) },
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
                    EntryRow(entry = e, onRevoke = { store.revoke(e.packageName) })
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
                    text = "claims to be: ${entry.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Installed from: $installSource",
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDeny) { Text("Deny") }
            OutlinedButton(onClick = { showDialog = true }) { Text("Approve") }
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
        title = { Text("Approve access?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "⚠\uFE0F App labels can be spoofed. Verify the package name and " +
                        "signing fingerprint match the developer you trust before approving. " +
                        "This grants the app on-device LLM access for as long as it's installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text = "Package:", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = packageName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Text(text = "Signing fingerprint (SHA-256):", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = formatCertHash(certHash),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                Text(text = "Install source: $installSource", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Approve $packageName")
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
        title = { Text("Revoke access?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This will immediately block $packageName from accessing on-device LLM.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text = "Package:", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = packageName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Text(text = "Signing fingerprint (SHA-256):", style = MaterialTheme.typography.labelMedium)
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Revoke $packageName")
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
                    text = "claims to be: ${entry.displayName}",
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
        TextButton(onClick = { showDialog = true }) { Text("Revoke") }
    }
}

@Composable
private fun rememberAllowlistStore(): AllowlistStore {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AllowlistStore(ctx) }
}
