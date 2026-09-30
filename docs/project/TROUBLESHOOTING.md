# Troubleshooting Mindlayer

Open **Troubleshoot** and choose **Run checks**. Each check expands to show its
result, last run and output. **Request log** has All / Errors filters; expand
Details for exact timestamps and request/session IDs, which can be selected and
copied. Refresh the log after reproducing a problem.

Choose **Share report**, then **Review and share**. Tracebox prepares the exact
package for review before opening Android sharing. Save a copy is available
after review. Reports stay on the device until the user chooses a destination.

Advanced options control recording, log levels, inference timings and managed
crash / handled exception / ANR capture. The main dashboard and `:ml` service
have distinct process roles (1 and 3); role 2 is reserved by Tracebox. Controls
persist across restart. Deleting Tracebox data does not delete the independent
encrypted Room usage history shown by Request log and History.

Reports include request correlation, backend, token rates, duration, memory,
thermal band, typed initialization context, and a snapshot of dashboard check
outcomes and typed model delivery issues at report entry. Reopen Share report
after rerunning a check to include the new snapshot. Frequent OCR-frame events
require the Debug log level; ordinary request boundaries use Info, and failures
use Error. Performance timings use their independent switch and minimum duration.

The diagnostic bridge rejects arbitrary IDs and unknown string values. It
forwards generated UUIDs, closed event/feature/failure labels and allowlisted
numeric fields. Free-form exception messages, test output, prompts, recognized
text, media and model output are excluded. Handled exceptions retain redacted
frame identity through Tracebox. Native capture and OS-exit raw trace ingestion
are disabled because raw dumps can retain inference memory.

## Building

Mindlayer pins the owner's Apache-2.0 Tracebox `0.1.0-alpha.7` artifacts. The
managed runtime uses a small vendored repair for restart and service-polling quota bugs; source
patch, build instructions and hashes are in `third_party/tracebox`. Supporting
artifacts come from GitHub Packages. Configure `gpr.user` / `gpr.key` in your user Gradle properties,
or `GITHUB_ACTOR` and `TRACEBOX_PACKAGES_READ_TOKEN` (falling back to
`GITHUB_TOKEN`). The token needs access to the Tracebox packages. CI uses its
read-packages token; a `TRACEBOX_PACKAGES_READ_TOKEN` repository secret can supply
cross-repository access when the package does not grant this repository access.
Local candidate validation can use `-PtraceboxLocalRepository=<repository>`;
that override is rejected in CI. No runtime network permission or uploader is
added to the app.

Build/unit tests establish host wiring and metadata privacy. Emulator checks
establish navigation, controls and package review. Actual ARM64 inference,
device/OEM hangs and fatal-crash capture require separate workload validation.

Validated on 2026-09-30: debug APK build, lint (zero errors), 38 focused dashboard
and logging tests, nine release tests, and 75 repaired Tracebox tests passed.
The full app run completed 2,312 tests with one existing failure: the asset-pack
test still expects LiteRT-LM 0.16.1 while the dependency file uses 0.17.1.
SDK, shared and lint-rule tests passed. On the emulator, recording recovered from
the exhausted 32-segment state, stayed ready past the original failure window,
and produced reviewed packages before and after a full app restart. The readable
review opened Android sharing; no recipient was selected. Raw artifacts were
excluded. App data and installed models were preserved.
