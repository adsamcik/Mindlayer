# Thermal policy when telemetry is unavailable (F-073)

> Phase 2 of the reliability remediation plan. Builds on F-070 (`91afbb5` —
> `telemetryAvailable` signal in `ThermalSample`).

## Problem statement

The original audit finding:

> **Bug**: On Android 8 / 8.1 (API 26-28) `ThermalMonitor.takeSample` silently
> falls back to `PowerManager.THERMAL_STATUS_NONE` because
> `getCurrentThermalStatus` is API 29+ and `getThermalHeadroom` is API 30+.
> The band always computes COOL on those devices — masking actual thermal
> stress on ~15-20 % of the install base, with no signal to consumers
> (dashboard, RequestTrace, future thermal policy) that the silence was due to
> missing telemetry rather than a healthy device.

`91afbb5` ("fix(thermal): explicit `telemetryAvailable` signal in
`ThermalSample`") landed Phase 1: distinguishing "device reports COOL"
from "device cannot report at all." Its commit message explicitly defers
the policy decision:

> NO behaviour change in `computeBand` or policy emission this PR — wiring a
> conservative duty-cycle / token cap on telemetry-blind hardware is the next
> reliability item (`thermal-policy-on-unavailable`, P2).

This document is that next item. The signal is wired; what does the
orchestrator **do** with it?

## Constraints

- **`minSdk 26`**. We must support Android 8.0 onward; we cannot drop the
  telemetry-blind tier.
- **No per-device exception list.** The audit explicitly rejected SoC
  allowlists as unmaintainable; the fix has to derive from observable
  capabilities, not device identity.
- **Must not regress API 29+ devices.** 35 `ThermalBandTest` cases plus
  `ThermalStateMachineTest` and `ThermalPolicyOrchestratorWiringTest` lock
  the existing 4-band behaviour. Default policy values for the
  `OBSERVED` confidence path must be byte-identical to today.
- **Must not require model-pack changes.** This decision lives entirely in
  `:app`; the Gemma `:gemma_model` Play AI Pack is not in scope.
- **Wire stability.** `ServiceStatus` is a frozen Parcelable per
  `docs/architecture/AIDL_STABILITY.md`. We must not add a field to it.
- **Coordination.** `kv-budget-orch` (F-072) holds
  `InferenceOrchestrator.kt`. The chosen design must avoid touching that
  file unless strictly necessary.

## Options considered

### A. Force CPU backend on telemetry-blind devices

Set `recommendedBackend = "CPU"` whenever `telemetryAvailable = false`.
The orchestrator and `EngineManager` both already handle backend choice
at request boundaries.

- **Pros:** Trivial. Removes the worst failure mode (sustained GPU under
  unknown thermal stress) outright.
- **Cons:** Loses the GPU speedup for *every* Android 8/8.1 device,
  including the ~80 % that are actually cool. Decoder-side throughput on
  a 4 GB / Snapdragon 660-class device drops 3–5×, pushing first-token
  latency past the 2 s product target. Prefill on CPU is also markedly
  worse. We would be over-correcting based on the conservative quintile.

### B. Conservative GPU duty-cycle

Keep `recommendedBackend = "GPU"` but tighten the duty cycle on
telemetry-blind devices: `burstSeconds = 6`, `restSeconds = 4` (vs.
`12` / `0` on COOL today). Fall back to CPU only if the engine's existing
GPU→CPU fallback chain fires twice in a row (already implemented by
`EngineManager.lastGpuFailureReason`).

- **Pros:** Preserves GPU throughput on healthy devices in this tier;
  the duty cycle limits sustained heat without forcing CPU. The
  fallback-on-failure path already exists — no new state machine.
- **Cons:** Still slower than today on healthy hardware (rest cycles add
  latency to long completions). The "two-failure trigger" leaves a
  window where a thermally-blind device can still hit shutdown on a
  single very long inference.

### C. Reduced token cap

Halve `chunkTokens` (128 → 64) on telemetry-blind devices. Smaller
chunks mean more frequent yield points where the policy is re-read at
request boundaries — the orchestrator can react sooner to a `cancel` or
`memory pressure` signal even though it can't react to thermal signals
(there are none).

- **Pros:** Limits burst heat by spreading the same total work across
  more, smaller compute windows. Composes with B.
- **Cons:** Token-cap reduction alone is not enough — without a duty
  cycle, the overall heat envelope is unchanged for long completions.

### D. Combination: B + C

Conservative GPU duty cycle (`burstSeconds = 6`, `restSeconds = 4`) AND
halved `chunkTokens` (64). This is the middle of the road.

- **Pros:** Mitigates burst heat (C) and total heat envelope (B) without
  forcing CPU on every Android 8/8.1 device. Composes naturally with
  the existing `ThermalPolicy` shape — no new fields beyond a
  `confidence` marker, no new control loop. Existing `ThermalPolicy`
  consumers (orchestrator duty-cycle code at line 530, dashboard
  display, `ServiceStatus`) read the same fields and just see different
  numbers.
- **Cons:** Two values to tune at once; harder to A/B-test individually.
  Mitigated by feature-flag-by-confidence (see Rollout).

## Recommendation: **Option D (B + C)** — conservative duty cycle plus halved chunk tokens, gated by a new `ThermalPolicy.confidence` field.

### Why D

1. **Calibrates risk to evidence.** On a device that *can* report
   thermal state, we trust the reading and run the existing policy.
   On a device that *cannot*, we apply a uniformly more conservative
   policy that survives a worst-case burst without crippling
   best-case workloads.
2. **Preserves existing code paths.** The 4-band logic
   (`computeBand`) is untouched. The policy table grows from
   "4 entries" to "4 entries × 2 confidence states" — but only the
   `OBSERVED` half is exercised on API 29+ hardware, so the 35
   existing band tests stay valid byte-for-byte.
3. **No new orchestrator surface.** The orchestrator already reads
   `chunkTokens`, `burstSeconds`, `restSeconds` from
   `currentPolicy.value` at request boundaries. The conservative
   variant just feeds smaller numbers through the same channel —
   `InferenceOrchestrator.kt` does not need to change.
   *(This is also what lets us race-free with `kv-budget-orch`,
   which holds that file.)*
4. **Wire-stable.** No `ServiceStatus` parameter shape change. The
   sentinel `"UNAVAILABLE"` is written into the existing
   `thermalBand: String` field — `docs/architecture/AIDL_STABILITY.md` § "frozen"
   permits new opaque values in existing String fields.
5. **Reversible.** The conservative policy is a pure function of
   `confidence`. A hot rollback is one constant change.

### What changes

**Engine layer (`ThermalMonitor`):**

```kotlin
enum class ThermalConfidence { OBSERVED, INFERRED }

data class ThermalPolicy(
    val band: ThermalBand,
    val recommendedBackend: String,
    val burstSeconds: Int,
    val restSeconds: Int,
    val chunkTokens: Int,
    val confidence: ThermalConfidence = ThermalConfidence.OBSERVED, // new, defaulted
)
```

`policyForBand(band, confidence)` returns the existing values when
`confidence == OBSERVED` and a conservative variant when
`confidence == INFERRED`. Per band, conservative reduces:

| Band | Burst (s) | Rest (s) | chunkTokens | Backend |
|---|---|---|---|---|
| COOL  / OBSERVED | 12 |  0 | 128 | GPU |
| COOL  / INFERRED |  6 |  4 |  64 | GPU |
| WARM  / OBSERVED |  8 |  3 |  64 | GPU |
| WARM  / INFERRED |  4 |  5 |  32 | GPU |
| HOT   / OBSERVED |  4 |  5 |  32 | CPU |
| HOT   / INFERRED |  3 |  6 |  16 | CPU |
| CRIT. / OBSERVED |  0 |  8 |  16 | CPU |
| CRIT. / INFERRED |  0 | 10 |  16 | CPU |

In practice, telemetry-blind devices stay in COOL band (no signal
elevates them) — so only the `COOL / INFERRED` row matters at runtime.
The other `INFERRED` rows are filled in for completeness so the table
can't trip a `NullPointerException` if a future code path forces a band
elevation under inferred confidence.

`processSample()` derives confidence from `sample.telemetryAvailable`:

```kotlin
val newConfidence = if (sample.telemetryAvailable)
    ThermalConfidence.OBSERVED else ThermalConfidence.INFERRED
val newPolicy = policyForBand(newBand, newConfidence)
if (newPolicy != _currentPolicy.value) _currentPolicy.value = newPolicy
```

This replaces the old "only update policy when band changes" behaviour,
which was a latent bug in any case: if `telemetryAvailable` flipped
mid-session (e.g. an OEM driver started reporting), the policy would
remain stale until the band changed.

**Wire layer (`ServiceBinder.kt`):**

When `thermalPolicy.confidence == INFERRED`, write `"UNAVAILABLE"` into
`ServiceStatus.thermalBand` instead of `policy.band.name`. This is the
sentinel that the dashboard reads. Wire-stable (no parameter shape
change); SDK clients that pattern-match `"HOT"`/`"CRITICAL"` keep
working (they see an unknown value and treat the device as healthy —
which is fine, the orchestrator is already applying conservative pacing
on their behalf).

**Dashboard layer (`DashboardViewModel`, `DashboardScreen`):**

`DashboardUiState` gains `val thermalTelemetryAvailable: Boolean = true`.
`DashboardViewModel` sets it from
`status.thermalBand != "UNAVAILABLE"`. `DashboardScreen.ThermalMiniCard`
renders a small "Telemetry unavailable — conservative policy" line and
swaps the headroom bar (which is null anyway) for an explanatory text.

## Rollout plan

1. **Feature flag**: implicit via the `confidence` field. To force the
   old behaviour we patch `processSample` to always pass `OBSERVED`.
   No build-config gymnastics; one constant.
2. **Telemetry**: `LogRepository.logThermalBandChange` already records
   band transitions. Extend the existing record to include a
   `confidence` token in the existing message string (free-form, so
   wire-stable) so we can A/B the rate of `INFERRED` policy emission
   on canary builds vs. shipped builds.
3. **Kill switch**: if conservative pacing causes user-visible
   regressions on Android 9+ devices that *report* OBSERVED — meaning
   the change has a bug — revert is one commit (revert the
   `policyForBand` overload and the `confidence` constructor field).
   The `telemetryAvailable` signal stays in place; only its
   consequences are rolled back.
4. **Acceptance**: `ThermalPolicyTelemetryUnavailableTest`
   (Robolectric, `@Config(sdk = [33])`) drives the API-level branch
   via the `readThermalStatus` / `readThermalHeadroomRaw` injection
   seams and asserts the conservative policy values. Existing 35
   `ThermalBandTest` cases plus `ThermalStateMachineTest` plus
   `ThermalPolicyOrchestratorWiringTest` continue to pass unchanged
   (they exercise `OBSERVED`).

## Open questions (deferred — do not block this PR)

1. **Should we surface `confidence` to clients via a typed channel?**
   Today only the dashboard consumes the indicator (via the wire
   sentinel). If client SDKs need it, we add a capability flag
   (`ServiceCapabilities.supportedFeatures += "thermal.confidence"`)
   and a getter; out of scope for F-073.
2. **Should conservative policy escalate after N requests on a
   telemetry-blind device?** Without telemetry there is no signal to
   escalate from. We could approximate via a request-count proxy
   (long-running session = more heat) but this is genuinely a
   product call — defer.
3. **Should we widen `INFERRED` to also cover transient telemetry
   gaps on API 29+ hardware?** E.g. `headroomNow` returns infinite
   for several seconds. Today F-070 marks those samples as
   `telemetryAvailable = true` because `status` is still present.
   Reconsidering would push us into "noisy oscillation" territory —
   probably not worth it.
4. **Do we want the dashboard's `serviceHealth()` to mark the device
   `DEGRADED` when telemetry is unavailable?** Currently `UNAVAILABLE`
   doesn't match the `HOT`/`CRITICAL` pattern, so health stays
   `HEALTHY`. Arguments both ways (it's a real reduction in
   observability, but the conservative policy mitigates it). Keeping
   `HEALTHY` for now; a subsequent PR can change the tone if product
   wants it.

## References

- `91afbb5` — Phase 1: explicit `telemetryAvailable` signal.
- `.github/instructions/engine.instructions.md` § Thermal — request-boundary
  rule for policy changes.
- `docs/architecture/AIDL_STABILITY.md` § "frozen" — wire stability for `ServiceStatus`.
- `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/ThermalMonitor.kt`
  — implementation seam.
