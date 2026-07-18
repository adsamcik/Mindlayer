package com.adsamcik.mindlayer.service.engine

/**
 * F-077: structured categorisation of the most recent
 * [EngineManager.initialize] failure.
 *
 * Replaces the opaque `lastGpuFailureReason: String?` signal that only
 * captured GPU-backend failures and conflated all other init paths
 * (low-memory pre-flight, missing model file, SHA-256 mismatch, native
 * crashes) into a single string-or-null. The dashboard now renders a
 * specific message + remediation per variant instead of a free-form
 * detail string.
 *
 * Population semantics (see [EngineManager.initialize]):
 *  - The field is reset to `null` at the start of every fresh
 *    initialize() call (after the cached-engine fast-path) so a new
 *    init run never displays stale failure state.
 *  - Each per-backend failure inside the fallback loop OVERWRITES the
 *    field with the latest category, so the value reflects the
 *    most-recently attempted backend's outcome.
 *  - On a successful init via fallback (e.g. GPU fails -> CPU
 *    succeeds), the field is INTENTIONALLY NOT cleared. The dashboard
 *    surfaces the last failure even though the engine is now usable,
 *    so operators can see "running on CPU because GPU failed" — this
 *    matches the previous lastGpuFailureReason behaviour where the
 *    label persisted across CPU-fallback success.
 *  - On full-chain failure the IllegalStateException wrapper still
 *    carries the diagnostic message; this field captures the *typed*
 *    category for the UI.
 *  - [EngineManager.shutdown] clears the field — once the engine is
 *    explicitly torn down, retained init-failure state would mislead
 *    the next initialize() if it succeeds before the loop captures
 *    anything.
 *
 * **Exhaustiveness:** every catchable failure path inside the init
 * loop must map to one of these variants. New failure modes added
 * in the future SHOULD prefer a specific variant over piling onto
 * [NativeError]; the dashboard's "what should the user do about
 * this" mapping is per-variant.
 *
 * **Logging:** the variant is also logged via
 * [com.adsamcik.mindlayer.service.logging.LogRepository.logInitFailureCategorized]
 * under [com.adsamcik.mindlayer.service.logging.LogEvent.INIT_FAILURE_CATEGORIZED.key].
 * The `safeLabel` strings in [BackendUnavailable] and [NativeError]
 * MUST be produced by [com.adsamcik.mindlayer.service.logging.safeLabel]
 * (F-006) so prompt fragments embedded in native exception messages
 * never reach the persisted log row or the dashboard.
 */
sealed class InitFailure {

    /**
     * Pre-flight RAM check failed: the device has less available memory
     * than (model size + 512 MB headroom). Maps from F-071's
     * [LowMemoryException] caught inside the init loop.
     *
     * Dashboard remediation: "Free up memory and retry."
     */
    object LowMemory : InitFailure()

    /**
     * A backend in the fallback chain (NPU / GPU / CPU) refused to
     * initialise — the LiteRT-LM engine constructor or `initialize()`
     * call threw. Replaces the GPU-only `lastGpuFailureReason` string.
     *
     * @param backend Name of the backend that failed (`"NPU"`, `"GPU"`,
     *        `"CPU"`).
     * @param safeLabel Class-name-only description from
     *        [com.adsamcik.mindlayer.service.logging.safeLabel] — never
     *        the raw exception message.
     *
     * Dashboard remediation: "X backend failed (label) — running on
     * CPU" or, if all backends failed, the wrapper IllegalStateException
     * propagates separately.
     */
    data class BackendUnavailable(
        val backend: String,
        val safeLabel: String,
    ) : InitFailure()

    /**
     * No `.litertlm` model file was found in any of the candidate
     * directories (filesDir, externalFilesDir, cacheDir,
     * /data/local/tmp, or on-demand delivery storage). Maps from
     * `EngineManager.noModelFoundException()`.
     *
     * Dashboard remediation: download the model from the Models screen.
     */
    object ModelMissing : InitFailure()

    /**
     * The on-disk model file's SHA-256 did not match
     * `BuildConfig.MODEL_SHA256`. Maps from the SecurityException raised
     * by `verifyModelIntegrity` (F-002 / F-003). The OS pinpoints a
     * tampered or corrupted model — refusing to load is the correct
     * response.
     *
     * Dashboard remediation: "Model file corrupted — reinstall."
     */
    object IntegrityMismatch : InitFailure()

    /**
     * A failure inside the init loop that the orchestrator did not
     * categorise more specifically — typically a native runtime error
     * (driver crash, OOM in native heap, malformed model) bubbling out
     * of LiteRT-LM. The `safeLabel` is the class-name-only description
     * via [com.adsamcik.mindlayer.service.logging.safeLabel].
     *
     * Dashboard remediation: "Native runtime error (label)."
     */
    data class NativeError(val safeLabel: String) : InitFailure()
}
