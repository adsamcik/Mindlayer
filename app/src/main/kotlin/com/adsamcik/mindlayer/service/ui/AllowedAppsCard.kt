package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.PendingApproval

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName ?: entry.packageName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "sig " + entry.signingCertSha256.take(16) + "…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDeny) { Text("Deny") }
            OutlinedButton(onClick = onApprove) { Text("Approve") }
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName ?: entry.packageName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "sig " + entry.signingCertSha256.take(16) + "…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke) { Text("Revoke") }
    }
}

@Composable
private fun rememberAllowlistStore(): AllowlistStore {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AllowlistStore(ctx) }
}
