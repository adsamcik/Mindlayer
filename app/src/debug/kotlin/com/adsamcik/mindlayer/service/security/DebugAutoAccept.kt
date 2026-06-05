package com.adsamcik.mindlayer.service.security

import android.content.Context

/**
 * Debug-variant seam for the "auto-accept all callers" developer toggle.
 *
 * The production gate ([com.adsamcik.mindlayer.service.ServiceBinder]) and the
 * dashboard UI ([com.adsamcik.mindlayer.service.ui.AllowedAppsCard]) reference
 * these `internal` functions; the build wires the **debug** implementation
 * here (backed by [DebugAutoAcceptStore]) and the **release** no-op in
 * `app/src/release/.../DebugAutoAccept.kt`. The backing store class exists only
 * in the debug source set, so release builds cannot enable the bypass even via
 * reflection.
 *
 * Authorization impact is intentionally narrow: when enabled, the gate treats
 * an *identified, not-user-denied* but unconsented caller as approved
 * (skipping the interactive ConsentActivity flow) so headless CI / instrumented
 * tests can run. Identity verification, explicit denials, and rate limiting all
 * still apply, and the OS-level `signature|knownSigner` bind permission is
 * untouched — only apps that can already bind are affected.
 */
internal fun debugAutoAcceptAllEnabled(context: Context): Boolean =
    DebugAutoAcceptStore(context).isEnabled()

/**
 * Flip the debug auto-accept toggle. Returns the resulting on-disk state
 * (`true` == enabled). Release builds no-op and always return `false`.
 */
internal fun debugSetAutoAcceptAll(context: Context, enabled: Boolean): Boolean =
    DebugAutoAcceptStore(context).setEnabled(enabled)
