$ErrorActionPreference = 'Continue'
Set-Location 'C:\Users\adam-\GitHub\Mindlayer-2c-trust-lifecycle'
$log = 'C:\Users\adam-\GitHub\Mindlayer-2c-trust-lifecycle\do-pr.log'
'STEP=start' | Out-File $log
'STEP=add' | Add-Content $log
git add -A 2>&1 | Add-Content $log
git status --short 2>&1 | Add-Content $log
'STEP=commit' | Add-Content $log
$body = @"
Phase 2 cleanup of trust + lifecycle gaps found by followup-wave audit.

- H-T1 Revoked first-party seeds now persist past the 7-day TTL (revoke is permanent; seedIfEmpty skips permanent denials).
- M-T1 API <31 cross-app cross-key bind: when bindService returns false, SDK surfaces typed UNSUPPORTED_ANDROID_VERSION instead of generic DISCONNECTED.
- M-T2 SDK manifest now declares BIND_ML_SERVICE (signature-protected, no security cost; removes host-app friction). SDK_INTEGRATION.md updated.
- M-T3 DebugAllowlistSeeder wraps getInstalledPackages in try/catch -- debug-only crash-protection.
- L-T1 AUTHORIZATION.md: cert-rotation runbook covers old-builds-keep-old-knownCerts; sideloading section documents debug-keystore-trust caveat.

Behavior change: revoke() now persists permanently (was 7-day TTL).

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
"@
git -c user.name="Copilot" -c user.email="223556219+Copilot@users.noreply.github.com" commit -m "fix(security): permanent denial + bindService==false API<31 + SDK manifest decl + debug seeder hardening" -m $body 2>&1 | Add-Content $log
'STEP=log-after-commit' | Add-Content $log
git log --oneline -3 2>&1 | Add-Content $log
'STEP=push' | Add-Content $log
git push -u origin fix/trust-lifecycle-hardening 2>&1 | Add-Content $log
"STEP=push-exit EXIT=$LASTEXITCODE" | Add-Content $log
'STEP=pr-create' | Add-Content $log
$prbody = @"
## Summary
Phase 2 cleanup of trust + lifecycle gaps found by followup-wave audit.

## Findings addressed
- **H-T1** Revoked first-party seeds now persist past the 7-day TTL. ``revoke()`` writes a permanent denial tombstone (``permanent=true``, ``expiresAtMs=Long.MAX_VALUE``); ``writeDenied`` skips permanent rows when pruning; ``seedIfEmpty`` rejects packages with a surviving permanent denial. ``denyPending`` keeps its 7-day TTL semantics so mistaken pending-row denials aren't permanent.
- **M-T1** API <31 cross-app cross-key bind: when ``bindService`` returns false (in addition to the existing ``SecurityException`` branch), the SDK now surfaces typed ``UNSUPPORTED_ANDROID_VERSION`` instead of leaving the state machine silently ``DISCONNECTED``.
- **M-T2** SDK manifest now declares ``<uses-permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" />``. The permission is ``signature|knownSigner`` so attacker inheritance via manifest merger is a no-op; host apps no longer have to remember to add the line. ``SDK_INTEGRATION.md`` step 3 updated.
- **M-T3** ``DebugAllowlistSeeder`` wraps ``getInstalledPackages`` in try/catch and logs via ``MindlayerLog.w(..., throwable = null)`` with ``e.safeLabel()`` — debug-only crash-protection against misbehaving OEM ``PackageManager`` impls / Robolectric edge cases.
- **L-T1** ``docs/AUTHORIZATION.md`` cert-rotation runbook now documents the old-builds-keep-old-``knownCerts`` constraint; sideloading section adds the debug-keystore-trust caveat.

## Behavior changes
- ``AllowlistStore.revoke()`` is now **permanent** (was 7-day TTL). Re-approval requires explicit user action through the dashboard.

## AIDL impact
**NONE.** No AIDL surface changes; no client recompile required.

## Tests
- ``:sdk:testDebugUnitTest`` ✅ (new ``ConnectionManagerBindReturnsFalseTest``)
- ``:shared:testDebugUnitTest`` ✅
- ``:app:testDebugUnitTest`` ✅ (new H-T1 cases in ``AllowlistStoreTest``)
- ``:app:testReleaseUnitTest`` ✅
- ``:app:lintDebug`` ✅

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
"@
$prbody | Out-File -FilePath C:\Users\adam-\GitHub\Mindlayer-2c-trust-lifecycle\pr-body.md -Encoding utf8
gh pr create --base main --head fix/trust-lifecycle-hardening --title "fix(security): permanent denial + bindService==false API<31 + SDK manifest decl (PR-2C)" --body-file C:\Users\adam-\GitHub\Mindlayer-2c-trust-lifecycle\pr-body.md 2>&1 | Add-Content $log
"STEP=pr-exit EXIT=$LASTEXITCODE" | Add-Content $log
'STEP=done' | Add-Content $log
