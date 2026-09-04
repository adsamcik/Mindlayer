# Play-oriented on-device memory benchmark

This benchmark answers a narrow question on one sufficiently large physical
device: **what memory does Mindlayer actually use while its real service starts,
warms, grows context, runs all three engines, and moves through Android process
states?**

It replaces PSS-only reasoning with the numerator Google Play documents for its
memory vital: per-process `RssAnon + VmSwap`. PSS and file/shared/graphics memory
remain in the report because they answer different physical-pressure and
attribution questions.

The benchmark has two cooperating pieces:

- `PlayMemoryBenchmarkInstrumentedTest` drives the public SDK against the real
  `com.adsamcik.mindlayer.debug:ml` service.
- [`scripts/benchmark-play-memory.ps1`](../../scripts/benchmark-play-memory.ps1)
  samples both Mindlayer processes and emits CSV, JSON, Markdown, and raw
  platform diagnostics.

## Recommended device and setup

Use one unlocked physical Android 17 (API 37) phone with at least 8 GB-tier
kernel `MemTotal` (6,800 MiB or more). A larger device is fine: this run is for
measuring the current implementation, not proving every RAM tier safe.

Before running:

1. Connect exactly one authorised `adb` device, or pass its serial with
   `-Device`.
2. Push the complete Gemma, EmbeddingGemma, and PaddleOCR set with
   `tools/dev-models/push-models.ps1 -All`.
3. Keep the device unused by other profiling sessions, screen unlocked,
   and near a stable starting temperature.
4. Leave enough free memory for model initialization. The script records
   `MemAvailable` but does not kill unrelated apps.

Run the production-default GPU path from the conservative 8K prewarm context to
the API maximum 32K context:

```powershell
.\scripts\benchmark-play-memory.ps1 -Device <serial> -RequestedBackend GPU
```

For a smaller explicit comparison while debugging:

```powershell
.\scripts\benchmark-play-memory.ps1 -Device <serial> `
  -DefaultContextTokens 4096 -MaxContextTokens 16384
```

The requested backend is passed through instrumentation and recorded separately
from the observed active backend. By default, a missing observation or any
fallback fails the run; this prevents CPU measurements from being mislabeled as
GPU/NPU evidence. Use `-AllowBackendFallback` only for an explicitly diagnostic
run whose summary should preserve, rather than accept, the mismatch.

The default synthetic label is 2048 x 2048 ARGB_8888: 16 MiB of touched bitmap
pixels, large enough to expose the client-to-service media path without using
Mindlayer's artificial 64-megapixel protocol ceiling. Override
`-BitmapWidth/-BitmapHeight` with a measured source resolution when reproducing
a particular consumer app.

The script builds code-only APKs and installs with `adb install -r`. It never
uninstalls the app or clears package data, so locally pushed models survive. It
does force-stop the debug package before the run to obtain a fresh process
baseline; pass `-SkipForceStop` only when deliberately measuring reuse.

## Workload phases

| Phase | What is live |
|---|---|
| `cold_process_baseline` | Fresh instrumented client process; service not yet bound |
| `service_connect_begin` | Cold Binder/service-process creation begins |
| `service_bound_unloaded` | Real `:ml` process and Binder connection; no LLM engine |
| `default_prewarm_begin` -> `default_prewarm_ready` | Explicit 8K context-aware prewarm and verified active engine |
| `default_context_idle` | One real 8K session before inference |
| `first_inference_begin` -> `first_inference_complete` | First 12,000-character prefill/decode |
| `multimodal_*` | Touched bitmap held, transferred, and used by text-plus-image inference |
| `embedding_first_request_*` | First real EmbeddingGemma request |
| `ocr_first_request_*` | First real PaddleOCR one-shot request |
| `coexistence_idle` | Chat session, embedding engine, and OCR engine resident together |
| `coexistence_active_*` | Chat, embedding, and OCR requests executing concurrently |
| `max_context_resize_begin` -> `max_context_prewarm_ready` | 8K -> 32K safe `:ml` process replacement and rewarm |
| `max_context_first_inference_*` | First request on a 32K session |
| `lifecycle_top` | Real Mindlayer dashboard activity resumed at the top |
| `lifecycle_fgs_top` | Long LLM request active while the dashboard remains top |
| `lifecycle_fgs_background` | Home launched while inference is still active; service remains FGS |
| `lifecycle_background*` | Inference complete, FGS demoted, activity stopped, client unbound, then `:ml` exit observed |
| cached tail | Instrumentation has ended; the host continues sampling until the main process is cached and holds that state |

Each steady phase is held for four seconds by default. `/proc` is sampled every
250 ms. More intrusive `dumpsys meminfo -a`, `dmabuf_dump`, and activity/service
snapshots run every two seconds per phase by default. After instrumentation
ends, sampling continues for up to 30 seconds and holds an observed cached state
for four seconds.

## Captured metrics

| Output column | Source | Meaning |
|---|---|---|
| `play_anon_rss_swap_kb` | `/proc/<pid>/status` | Exact local Play-oriented numerator: `RssAnon + VmSwap` |
| `rss_file_kb`, `rss_shmem_kb` | `/proc/<pid>/status` | Resident file-backed and shared-memory pages, kept separate from the Play numerator |
| `pss_kb`, `pss_anon_kb`, `pss_file_kb`, `pss_shmem_kb` | `/proc/<pid>/smaps_rollup` | Proportional attribution and backing breakdown |
| `native_heap_*` | `dumpsys meminfo -a <pid>` | Native-heap PSS/RSS and allocator-reported allocated bytes |
| `graphics_*`, `*_mtrack_*` | `dumpsys meminfo -a <pid>` | Graphics and driver/mtrack attribution |
| `bitmap_kb` | `Bitmap (malloced)` + `Bitmap (nonmalloced)` under `Native Allocations` | Process-wide live bitmap memory exposed by current Android platform accounting |
| `tracked_workload_bitmap_kb` | Workload phase marker | Exact allocation size of the benchmark-owned input bitmap; cross-check only |
| `hardware_buffer_kb` | `Native Allocations` | HardwareBuffer native-registry total when exposed |
| `dmabuf_rss_kb`, `dmabuf_pss_kb` | `dmabuf_dump <pid>` | Per-process mapped DMA-BUF RSS/PSS when the device permits it |
| `uid_state_code`, `uid_state_name` | `am get-uid-state <uid>` | Android's app-UID process state; retained in CSV and raw snapshots rather than inferred from benchmark phases |
| `top_activity` | `dumpsys activity activities <package>` | Whether Mindlayer owns the resumed/top activity |
| `foreground_service` | `dumpsys activity services <package>` | Whether the real ML service reports foreground state |
| `oom_score_adj`, `lifecycle_bucket` | `/proc/<pid>/oom_score_adj` plus the platform evidence above | Per-process kill priority and normalized `TOP`/`FGS`/`BACKGROUND`/`CACHED` classification |

`dumpsys meminfo -a` asks the app process for detailed allocation information
and may trigger a GC. The high-rate `/proc` series therefore remains the source
for transient `RssAnon + VmSwap` and PSS peaks; the held phases make detailed
bitmap/graphics snapshots repeatable.

DMA-BUF and graphics counters can overlap. **Do not add them together.** The
benchmark reports each source independently and records an unavailable
`dmabuf_dump` as missing, never as zero.

## Results

Each run creates `artifacts/play-memory/<timestamp>/`:

- `samples.csv` — per-process samples;
- `aggregate-samples.csv` — simultaneous package sum for diagnosis only;
- `summary.json` — machine-readable peaks, local sampled P90s, and coverage;
- `summary.md` — compact human report;
- `phase-events.tsv` and `phase-timings.csv` — lossless device-side transition
  journal and measured duration to the next transition;
- `pid-transitions.csv` — process creation and replacement events, including
  the expected context-growth restart;
- `metadata.json` — device/build/workload identity and Memory Limiter status;
- `instrumentation.txt` and `phase-logcat.txt` — workload verdict and markers;
- `raw/` — detailed `meminfo`, DMA-BUF, process-state, and device snapshots.

Treat a run as complete only when:

- instrumentation ends in phase `instrumentation_complete`;
- `summary.backendIdentity.passed` is true for both prewarm milestones;
- the `:ml` process has exact `RssAnon + VmSwap` samples;
- default-to-max context growth shows a new `:ml` PID when the requested sizes
  differ;
- `summary.lifecycle.transitionPassed` is true, proving TOP -> FGS -> background
  -> cached in order from Android state rather than marker names;
- `summary.hostMilestones.idleServiceExitObserved` is true, proving the final
  hidden idle unbind actually removed the isolated `:ml` process;
- `coverage.bitmapNativeAllocationCounters` is `true` for Play-like bitmap
  accounting;
- `coverage.dmaBufPerProcess` is `true`, or the missing OEM/kernel facility is
  explicitly accepted alongside the retained raw diagnostic.

Use `-RequireDmaBuf` when missing DMA-BUF accounting should fail the run. Adjust
`-CachedWaitMs` only when an OEM takes longer to demote the stopped app; the
script never forces cached state or manufactures it with a process-state test
override.

## Interpretation boundary

The summary's local P90 is the 90th percentile of samples in this single run.
It is useful for comparing revisions but is **not** Google Play's rolling field
P90 over opted-in devices, RAM tiers, versions, and application states. Google
Play also exposes a `processName` breakdown but does not currently define public
package-sum compliance semantics. Consequently:

- use the `:ml` row as the primary inference-process result;
- inspect the main/client row for SDK/media/UI costs;
- keep the package aggregate diagnostic-only;
- use Play Console/Reporting API data as the eventual policy authority.

Android may keep a bound service at the importance of its client, while cached
processes are explicitly expendable. For that reason the workload disconnects
all SDK/UI bindings before the cached tail, records both PIDs independently, and
accepts a killed `:ml` process as a separate final disposition rather than
calling its absence zero memory. See [Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle)
and [Service bindings and process states](https://developer.android.com/topic/performance/memory/guide/service-bindings).

The current Google documentation defines anonymous RSS + swap and notes that
Bitmap pixels on Android 8+ can live in unmanaged native heap, while the bitmap
vital can additionally include shared- or graphics-backed bitmaps:

- [Memory usage (anonymous RSS + swap)](https://developer.android.com/topic/performance/vitals/memory-usage)
- [Bitmap memory usage](https://developer.android.com/topic/performance/vitals/bitmap-memory-usage)
- [Play Console technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799?hl=en-GB)

## Harness validation without a device

The PowerShell parsers have an embedded fixture check:

```powershell
.\scripts\benchmark-play-memory.ps1 -SelfTest
```

This validates field extraction only. It does not validate an Android runtime,
device driver accounting, model initialization, or inference.
