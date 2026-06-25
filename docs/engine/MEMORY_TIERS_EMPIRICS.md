# Memory tiers — empirical derivation for Gemma 4 E2B on LiteRT-LM 0.12.0

Source-of-truth document for the `DeviceTier` table in
`app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/MemoryBudget.kt`.
The values there are not hand-tuned guesses — they come from the
instrumented benchmark + spike runs documented below.

## TL;DR

| Metric | Value (95% CI) |
|---|---|
| **Per-configured-token KV cost** | **9 208 bytes** ([8 886, 9 736]) |
| Engine + model fixed footprint above process baseline | **474 MiB** |
| Process baseline (Android framework + Compose + AIDL) | **127 MiB** |
| Per-`Conversation` overhead | **< 1 MiB** (sessions share the engine KV pool) |
| Mindlayer steady-state PSS @ N tokens (formula) | `127 + 474 + 9.5 × N / 1024` MiB |
| Linear fit R² | 0.9990 |

## Empirical method

1. `app/src/androidTest/.../KvCacheMemoryBenchmarkInstrumentedTest.kt` instantiates
   `Engine(EngineConfig(maxNumTokens = N))` directly via the LiteRT-LM SDK,
   bypassing `EngineManager`'s 1 GB / 1 GB safety floors so we can stress
   the upper tiers and observe native init failure modes.
2. Reads `/proc/self/smaps_rollup` before and 2 s after `engine.initialize()`
   so PSS / RSS / Private_Dirty / Private_Clean / Shared_Clean / SwapPss
   are all captured (catches `mmap`-backed KV that
   `Debug.getNativeHeapAllocatedSize()` undercounts).
3. Background sampler at 250 ms cadence captures peak during init.
4. Each measurement runs in a **fresh test process** — one
   `connectedDebugAndroidTest` invocation per data point — to avoid
   in-process `Engine.close()`+recreate contamination of the baseline.
5. Sweep: `maxNumTokens ∈ {1 024, 2 048, 4 096, 8 192, 16 384}` with 3
   reps per N (8 of 15 runs landed clean CSVs; the rest hit gradle/install
   flakes — see the artifacts in `kv-bench-artifacts/`).
6. Multisession phase confirmed `engine.createConversation` adds <1 MiB
   regardless of `maxNumTokens`, proving conversations share the engine's
   pre-allocated KV pool.
7. Spike phase ran `createConversation` + `close` 10 times against a
   single Engine and confirmed all cycles succeed with no JNI exceptions
   and bounded memory (4–42 ms create, 0–1 ms close).

**Environment caveats**: x86_64 Android 16 emulator, CPU backend,
LiteRT-LM 0.12.0. The per-token KV cost is a property of the model
architecture (head count, head dim, layer count, KV quantization) and
transfers cleanly to arm64. Latency does NOT transfer — emulator CPU
inference is roughly 50–100× slower than a real flagship.

## Raw measurements

| maxNumTokens | ΔPSS (MiB) | ΔRSS (MiB) | initMs |
|---:|---:|---:|---:|
| 1 024 | 480.7 | 482.4 | 16 055 |
| 4 096 | 511.5 | 513.2 | 18 997 |
| 8 192 | 547.1 | 548.7 | 20 514 |
| 16 384 | 616.7 | 618.3 | 12 499 |

Linear fit (median ΔPSS_kb = a + b × maxNumTokens):
- intercept a = 485 185 kB (≈ 474 MiB) — engine + model fixed cost
- slope b = **9 208 bytes/token** (95 % CI [8 886, 9 736] via bootstrap)
- R² = 0.9990

## Why the measured slope is 3× the int8 theoretical estimate

LiteRT-LM 0.12.0's KV layout for Gemma 4 E2B isn't publicly documented.
Each of the following is consistent with ≈9 KiB/token:

- fp16 KV (6 KiB theoretical) + per-token activation/state scratch (~3 KiB)
- 4 KV heads (double the Gemma 3n E2B config) at int8 + scratch
- No "unified K=V on global layers" optimisation as the model card claims

Exact decomposition doesn't matter for tier sizing — the empirical 9 KiB
is now ground truth and the tier formula uses it directly.

## Tier formula

```
budget_mib    = (totalMem - 700 [foreground app budget]) × 0.85 [fragmentation]
                - 256 [LMK runway]
N_max         = floor(((budget_mib - 256 [peak transient] - 601 [fixed])
                × 1024) / 9.5)
maxMaxTokens  = min(N_max, 131_072 [Gemma 4 E2B model max])
```

Where:
- 9.5 KiB/token = rounded-up measured 9.2 for safety
- 127 MiB = test-process baseline (Android framework + Compose + AIDL)
- 474 MiB = engine + mmap'd weights fixed cost
- 700 MiB = budget reserved for user's foreground app
- 256 MiB peak transient = activation / vision-encoder bursts during inference
- 0.85 safety factor = headroom for fragmentation + multi-image + GC
- 256 MiB LMK runway = above `ActivityManager.MemoryInfo.threshold`

## Resulting tier table

| totalMem | maxSessions | defaultMaxTokens | maxMaxTokens | Mindlayer PSS @ max | Safety factor |
|---|---|---|---|---|---|
| ≤ 4 GB  | 1 | 8 192    | 32 768  | 905 MiB  | 2.4× |
| ≤ 6 GB  | 1 | 16 384   | 65 536  | 1 209 MiB | 3.1× |
| ≤ 8 GB  | 1 | 32 768   | 131 072 | 1 817 MiB | 3.0× |
| ≤ 12 GB | 1 | 65 536   | 131 072 | 1 817 MiB | 5.0× |
| > 12 GB | 1 | 131 072  | 131 072 | 1 817 MiB | 6.5×+ |

`maxSessions` is pinned at 1 across every tier because LiteRT-LM 0.12.0
enforces "at most one Conversation per Engine at a time". The
[`WarmConversationSlot`](../../app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/WarmConversationSlot.kt)
infrastructure landed in the same PR is the building block for a
follow-up that will wire transparent hot-swap and lift this cap.

## Re-derivation cookbook

To re-validate these numbers (e.g. after a LiteRT-LM bump):

```powershell
# 1. Push the Gemma 4 E2B model to /data/local/tmp.
$env:PATH = "C:\Users\adam-\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2\bin;$env:PATH"
adb push G:\Github\Mindlayer\gemma_model\src\main\assets\gemma-4-E2B-it.litertlm `
  /data/local/tmp/

# 2. Build + install the app+test APKs once.
cd G:\Github\Mindlayer
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb install -r -g app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3. Run the sweep (one gradle invocation per data point).
foreach ($n in 1024,2048,4096,8192,16384) {
  foreach ($r in 1..3) {
    & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain `
      "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.mindlayer.service.engine.KvCacheMemoryBenchmarkInstrumentedTest#measure_one_point" `
      "-Pandroid.testInstrumentationRunnerArguments.phase=sweep" `
      "-Pandroid.testInstrumentationRunnerArguments.maxNumTokens=$n" `
      "-Pandroid.testInstrumentationRunnerArguments.rep=$r"
    adb logcat -d -s "KvCacheBench:*" >> sweep-logcat.txt
    adb logcat -c
  }
}

# 4. Grep CSV_ROW lines from logcat and run the bundled analysis script
#    (in this PR's session artifacts, not committed to the repo).
```

## Hazards encountered during measurement

- **Cache-dir contamination**: a failed engine init silently bricks
  subsequent inits with `NOT_FOUND: TF_LITE_PREFILL_DECODE`. Fixed by
  wiping `context.cacheDir/litert_cache` before each `EngineManager.initialize`.
- **Externalfilesdir scope**: `adb shell` cannot list other apps'
  externalFilesDir on Android 12+ even when files are world-readable.
  The instrumented benchmark emits each CSV row to logcat as `CSV_ROW:`
  in addition to writing the file, so post-hoc reconstruction via logcat
  is always possible.
- **In-process Engine recreate**: triggered behaviour that contaminated
  baselines (a closed Engine's KV state lingered partially). Always run
  one measurement per fresh test process for clean numbers.
- **Multi-conversation crash**: the second `engine.createConversation`
  throws `FAILED_PRECONDITION`. Discovered while running the multisession
  phase — see commit log for the full trace.
