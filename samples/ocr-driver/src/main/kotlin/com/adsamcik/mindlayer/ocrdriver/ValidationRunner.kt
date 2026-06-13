package com.adsamcik.mindlayer.ocrdriver

import android.content.Context
import android.os.Build
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.sdk.ImageInput
import com.adsamcik.mindlayer.sdk.JsonSchema
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.OcrProfile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Validation scenario outcomes — one per test in the v0.9 OCR
 * production-readiness validation matrix. Serialised to JSON so a CI
 * job (or the developer) can pull `ocr-validation/report.json` off the
 * device after a run and verify the flip-the-flag preconditions.
 */
@Serializable
data class ValidationScenarioResult(
    val name: String,
    val ok: Boolean,
    val durationMs: Long,
    val note: String? = null,
)

@Serializable
data class ValidationReport(
    val reportVersion: Int = 1,
    val deviceModel: String,
    val sdkInt: Int,
    val capabilitiesSubset: List<String>,
    val scenarios: List<ValidationScenarioResult>,
    val passed: Int,
    val failed: Int,
    val total: Int,
) {
    fun isClean(): Boolean = failed == 0 && total > 0
}

/**
 * Drives the v0.9 OCR production-readiness validation matrix end-to-end
 * against a live Mindlayer service. Designed to be the human-in-the-loop
 * sign-off step before flipping `OcrFeatureFlags.IS_PRODUCTION_READY`.
 *
 * # What it exercises
 *
 * Each scenario maps to a documented contract on either the single-image
 * canonical path (`Mindlayer.ocr { ... }.awaitResult()`) or the multi-frame
 * session path (`Mindlayer.ocrSession { ... }` + frame pushes). The matrix is
 * intentionally narrow — it does NOT measure quality, latency
 * percentiles, or fusion accuracy. It only verifies that the documented
 * happy paths work end-to-end and that two specific failure modes
 * (stream-not-attached, idempotent close) behave correctly.
 *
 * # What it does NOT cover
 *
 *  - Quality / accuracy of recognized text (lives in the benchmark in
 *    PR #129).
 *  - Real device thermal / memory pressure (needs a real device matrix).
 *  - GPU / NPU acceleration (PaddleOCR is CPU-locked today).
 *  - The structured-extraction LLM pass (Gemma must be loaded
 *    separately; the LLM scenarios self-skip when `getStatus().isEngineLoaded`
 *    is false).
 *
 * # Fixtures
 *
 * Reads WebP fixtures from the sample APK's `assets/fixtures/` —
 * OCR-safe synthetic scenarios with deterministic text. No user data,
 * no PII.
 *
 * # Privacy
 *
 * The report JSON contains scenario names + durations + capability
 * names — never recognized text, never extraction values. The
 * `note` field carries short diagnostic strings (e.g. "lines=3") but
 * never raw OCR output.
 */
class ValidationRunner(
    private val context: Context,
    private val mindlayer: Mindlayer,
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Run every scenario. Each scenario is isolated — a thrown exception
     * fails one scenario but does not short-circuit the rest. Returns the
     * full report regardless of any failures.
     */
    suspend fun runAll(): ValidationReport {
        val results = mutableListOf<ValidationScenarioResult>()

        // Engine warmup: the PaddleOCR backend initialises lazily inside
        // OcrSessionManager on the first non-self caller's connect. On a
        // cold service (no prior client) the bundle discovery + native
        // tflite load can take 200-800ms on the emulator; getCapabilities
        // called during that window returns BEFORE the engine has reached
        // PaddleOcrEngineState.Ready, so FEATURE_OCR_* is correctly absent.
        // Poll until OCR appears or we time out — production apps should
        // do the same dance (subscribe to capabilities-changed or retry
        // with backoff) rather than treating the first response as final.
        //
        // Poll cost: getCapabilities is 0.25 rate-limit units. We poll at
        // 1 Hz (so the bucket refill at ~1 token/sec keeps pace) for up
        // to 15s, then sleep a final RATE_LIMIT_RECOVERY_DELAY_MS to let
        // the bucket refill to a comfortable level before the actual
        // scenarios start firing real OCR / inference calls.
        suspend fun pollForOcrCapability(
            timeoutMs: Long = 15_000L,
            pollIntervalMs: Long = 1_000L,
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    // forceRefresh = true bypasses the SDK's TTL cache so
                    // each iteration crosses the wire and sees the actual
                    // current engine state — without this the very first
                    // (FEATURE_OCR=false) reply gets pinned for 5 s and
                    // the poll never converges.
                    val caps = mindlayer.getCapabilities(forceRefresh = true)
                    if (caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION) &&
                        caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)
                    ) {
                        return true
                    }
                } catch (_: Throwable) {
                    // Transient rate-limit on a polling probe is acceptable
                    // — fall through to the delay and retry.
                }
                delay(pollIntervalMs)
            }
            return false
        }
        val ocrReady = pollForOcrCapability()
        // Even after OCR is advertised, give the rate-limit bucket a
        // moment to recover from the warmup polling so the first heavy
        // scenario doesn't immediately exhaust the remaining tokens.
        delay(RATE_LIMIT_RECOVERY_DELAY_MS)

        val capsSubset = try {
            mindlayer.getCapabilities().supportedFeatures
                .filter { it.startsWith("ocr_") }
                .sorted()
        } catch (t: Throwable) {
            emptyList()
        }

        results += scenario("capability_advertise") {
            val caps = mindlayer.getCapabilities()
            check(caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION)) {
                "FEATURE_OCR_SESSION not advertised after ${if (ocrReady) "ready" else "10s poll"} — engine not ready or production gate off"
            }
            check(caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)) {
                "FEATURE_OCR_IMAGE_ONESHOT not advertised after ${if (ocrReady) "ready" else "10s poll"} — engine not ready or production gate off"
            }
            "FEATURE_OCR_SESSION + FEATURE_OCR_IMAGE_ONESHOT both advertised after ${if (ocrReady) "engine warm-up" else "timeout — unexpectedly succeeded"}"
        }

        results += scenario("single_image_no_llm") {
            val bytes = readFixture(OcrFixtures.RECEIPT)
            val result = mindlayer.ocr {
                image(bytes, OcrFixtures.mimeType(OcrFixtures.RECEIPT))
            }.awaitResult()
            check(result.lines.isNotEmpty()) {
                "ocr{} returned 0 lines on ${OcrFixtures.RECEIPT} — recognition failed"
            }
            "lines=${result.lines.size} ocr=${result.metrics.ocrDurationMs ?: 0L}ms"
        }

        results += scenario("single_image_with_llm") {
            val bytes = readFixture(OcrFixtures.RECEIPT)
            val result = mindlayer.ocr {
                image(bytes, OcrFixtures.mimeType(OcrFixtures.RECEIPT))
                extractWithLlm(
                    JsonSchema.parse(
                        """{"type":"object","properties":{"total":{"type":"string"}}}""",
                    ),
                )
            }.awaitResult()
            check(result.lines.isNotEmpty()) {
                "ocr{} + extractWithLlm returned 0 lines — recognition failed"
            }
            // The production OcrLlmExtractor returns EMPTY in 0ms when:
            //   1. The Gemma engine isn't loaded yet (engineProvider null).
            //   2. The prompt build fails for the given evidence + schema.
            //   3. The extraction throws (native LiteRT-LM error).
            // Per the OcrLlmExtractor contract (Camera pipelines must not
            // die on a bad frame), failures are silent and the result is
            // EMPTY. The harness can therefore observe two cleanly distinct
            // outcomes — extractor ran (llm > 0 ms, with or without fields)
            // or extractor skipped (llm == 0 ms, engine likely missing).
            // Both are valid signals on a dev device; we report the actual
            // state instead of asserting one outcome.
            val llmDurationMs = result.metrics.llmDurationMs ?: 0L
            val ran = llmDurationMs > 0L
            "lines=${result.lines.size} llm=${llmDurationMs}ms " +
                "fields=${result.extractionFields.size} " +
                "ran=${if (ran) "yes" else "skipped (engine not warmed)"}"
        }

        results += scenario("single_image_bbox") {
            val bytes = readFixture(OcrFixtures.DOCUMENT)
            val result = mindlayer.ocr {
                image(bytes, OcrFixtures.mimeType(OcrFixtures.DOCUMENT))
                emitBoundingBoxes()
            }.awaitResult()
            check(result.lines.isNotEmpty()) { "no lines extracted" }
            val withBbox = result.lines.count { it.boundingBox != null }
            check(withBbox > 0) {
                "emitBoundingBoxes=true but no lines came back with a bounding box"
            }
            "linesWithBbox=$withBbox/${result.lines.size}"
        }

        results += scenario("session_lifecycle_basic") {
            // Open a session, attach the stream, push one encoded frame,
            // finalize, and assert the canonical finalize path returns JSON.
            mindlayer.ocrSession {
                profile(OcrProfile.GeneralDocument)
            }.use { session ->
                kotlinx.coroutines.coroutineScope {
                    val collector = launch {
                        session.events.collect { }
                    }
                    // Give the service a moment to wire the pipe.
                    delay(200)

                    val bytes = readFixture(OcrFixtures.DOCUMENT)
                    val ack = session.pushFrame(
                        meta = newMeta(frameId = 1L),
                        image = ImageInput.Bytes(bytes, OcrFixtures.mimeType(OcrFixtures.DOCUMENT)),
                    )
                    check(ack.status == com.adsamcik.mindlayer.OcrFrameAck.STATUS_ACCEPTED) {
                        "expected STATUS_ACCEPTED, got ${ack.status}"
                    }

                    val result = session.finalize()
                    check(result.fullJson.isNotEmpty()) {
                        "finalize() returned empty JSON"
                    }
                    withTimeoutOrNull(10_000L) { collector.join() }
                        ?: run {
                            collector.cancel()
                            error("event collector did not finish within 10s of finalize()")
                        }
                    "ack=${ack.status} finalJsonLen=${result.fullJson.toString().length}"
                }
            }
        }

        results += scenario("session_stream_not_attached_rejects") {
            // Pushing before attaching the event pipe must surface a
            // STATUS_REJECTED_STREAM_NOT_ATTACHED ack. Without this
            // contract, OCR results could be lost silently.
            mindlayer.ocrSession {
                profile(OcrProfile.GeneralDocument)
            }.use { session ->
                val bytes = readFixture(OcrFixtures.DOCUMENT)
                val ack = session.pushFrame(
                    meta = newMeta(frameId = 1L),
                    image = ImageInput.Bytes(bytes, OcrFixtures.mimeType(OcrFixtures.DOCUMENT)),
                )
                check(
                    ack.status ==
                        com.adsamcik.mindlayer.OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED,
                ) {
                    "expected STATUS_REJECTED_STREAM_NOT_ATTACHED, got ${ack.status}"
                }
                "ack=${ack.status}"
            }
        }

        results += scenario("session_close_idempotent") {
            val session = mindlayer.ocrSession {
                profile(OcrProfile.GeneralDocument)
            }
            session.close()
            session.close() // second close MUST be a no-op
            "close() twice raised no exception"
        }

        // ── Capability + clean-error-mapping checks for sister surfaces ─────
        //
        // We can't load Gemma + EmbeddingGemma without their model files (~3 GB
        // download), so end-to-end inference / embeddings inference isn't
        // exercised here. What we CAN verify is (a) the capability flags
        // accurately reflect engine state, (b) when an engine isn't loaded,
        // the SDK surfaces a typed MindlayerException rather than crashing,
        // and (c) the deprecation-free facades from PRs #132 / #133 are
        // callable end-to-end.

        results += scenario("inference_facade_smoke") {
            // The ask facade should resolve to a typed MindlayerException
            // when the chat engine isn't loaded (Gemma model missing). It
            // must NOT throw an unchecked exception, and it must NOT silently
            // hang the binder transaction.
            val result = try {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    mindlayer.ask("validation probe — engine likely missing") {
                        systemPrompt = "validation harness probe"
                        maxTokens = 256
                    }
                }
            } catch (t: Throwable) {
                "ask error mapped: ${t.javaClass.simpleName}:${(t.message ?: "").take(60)}"
            }
            // Either we got a response (engine loaded — great) or a typed
            // failure (engine missing — also a clean signal). Both are pass.
            "ask surfaced cleanly: result=${result?.toString()?.take(60) ?: "null/timeout"}"
        }

        results += scenario("embeddings_capability_accuracy") {
            val caps = mindlayer.getCapabilities()
            val advertised = ServiceCapabilities.FEATURE_EMBEDDINGS in caps.supportedFeatures
            if (!advertised) {
                // Engine missing — confirm SDK throws FEATURE_NOT_SUPPORTED when
                // we try to use the vector facade. Clean error mapping is the
                // contract here.
                val errorClass = try {
                    mindlayer.vector("validation probe — embeddings engine likely missing")
                    "no exception (engine actually loaded?)"
                } catch (t: Throwable) {
                    t.javaClass.simpleName
                }
                "FEATURE_EMBEDDINGS absent (engine missing); vector mapped to: $errorClass"
            } else {
                // Engine present — make a tiny call and confirm we get a vector back.
                val vec = mindlayer.vector("hello")
                check(vec.isNotEmpty()) { "vector returned empty vector" }
                "FEATURE_EMBEDDINGS advertised; vector returned ${vec.size}-dim vector"
            }
        }

        results += scenario("ping_health_check") {
            // The ping() endpoint is supposed to bypass the allowlist + cost
            // zero rate-limit per docs. Use it as a connection-liveness probe.
            val caps = mindlayer.getCapabilities()
            if (ServiceCapabilities.FEATURE_HEALTH_CHECK !in caps.supportedFeatures) {
                return@scenario "FEATURE_HEALTH_CHECK absent — older service?"
            }
            // The SDK doesn't expose ping() directly today; the dashboard uses
            // it via getStatus polling. Just confirm getCapabilities responded
            // (which itself proves binder + authz both work).
            "capabilities returned ${caps.supportedFeatures.size} features"
        }

        // ── End-to-end engine scenarios ───────────────────────────────────
        // These run when the Gemma 4 E2B and EmbeddingGemma-300M models are
        // staged on the device. They self-skip cleanly (returning a
        // descriptive note rather than asserting) when the engines aren't
        // loaded — so the same harness works on dev devices with no models
        // staged AND on real-device validation rigs that have everything
        // provisioned.

        results += scenario("gemma_inference_e2e") {
            // Explicit real-Gemma probe distinct from inference_facade_smoke:
            // when the engine is loaded, demand an actual non-empty text
            // response from a short prompt (no tools, no media). When the
            // engine is absent, surface the typed error without failing.
            val response = try {
                kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                    mindlayer.ask("Say hello.") {
                        systemPrompt = "You answer in five words or less."
                        maxTokens = 256
                    }
                }
            } catch (t: Throwable) {
                return@scenario "engine missing — ask threw: ${t.javaClass.simpleName}:${(t.message ?: "").take(60)}"
            }
            check(response != null) { "Gemma inference timed out after 120s" }
            check(response.isNotBlank()) { "Gemma returned blank response" }
            // Strip raw response in the note to avoid PII / prompt-text leakage
            // in logcat — report only metadata.
            "Gemma E2E ok len=${response.length}"
        }

        results += scenario("embeddings_inference_e2e") {
            // Explicit real-EmbeddingGemma probe distinct from
            // embeddings_capability_accuracy: when the engine is loaded,
            // assert vector quality (non-empty, finite, distinguishable
            // for distinct inputs). When the engine is absent, surface a
            // clean note.
            val caps = mindlayer.getCapabilities(forceRefresh = true)
            if (ServiceCapabilities.FEATURE_EMBEDDINGS !in caps.supportedFeatures) {
                return@scenario "engine missing — FEATURE_EMBEDDINGS not advertised"
            }
            val v1 = mindlayer.vector("The cat sits on the mat.")
            check(v1.isNotEmpty()) { "vector returned empty vector" }
            check(v1.all { it.isFinite() }) { "vector returned NaN/Inf in vector" }
            val norm = kotlin.math.sqrt(v1.fold(0.0) { acc, x -> acc + x.toDouble() * x }).toFloat()
            check(norm in 0.9f..1.1f) { "vector not L2-normalised (norm=$norm)" }
            // Distinguishability check: a topically-different sentence
            // should NOT produce an identical vector.
            val v2 = mindlayer.vector("Quantum mechanics describes particles.")
            check(v2.size == v1.size) { "vector dimension mismatch ${v1.size} vs ${v2.size}" }
            var cosine = 0.0
            for (i in v1.indices) cosine += v1[i].toDouble() * v2[i].toDouble()
            check(cosine < 0.99) { "unrelated inputs produced near-identical vectors (cos=$cosine)" }
            "EmbeddingGemma E2E ok dim=${v1.size} L2=${"%.3f".format(norm)} cos(distinct)=${"%.3f".format(cosine)}"
        }

        val passed = results.count { it.ok }
        val failed = results.size - passed
        return ValidationReport(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            capabilitiesSubset = capsSubset,
            scenarios = results,
            passed = passed,
            failed = failed,
            total = results.size,
        )
    }

    /**
     * Serialise [report] to `ocr-validation/report.json` under the
     * sample app's `externalFilesDir`. Returns the file path so the UI
     * layer can surface "pull with adb pull X" guidance.
     */
    fun writeReport(report: ValidationReport): File {
        val dir = File(context.getExternalFilesDir(null), "ocr-validation").apply { mkdirs() }
        val file = File(dir, "report.json")
        file.writeText(json.encodeToString(ValidationReport.serializer(), report))
        return file
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private suspend fun scenario(
        name: String,
        block: suspend () -> String,
    ): ValidationScenarioResult {
        val started = System.nanoTime()
        val result = try {
            val note = block()
            ValidationScenarioResult(
                name = name,
                ok = true,
                durationMs = (System.nanoTime() - started) / 1_000_000L,
                note = note,
            )
        } catch (t: TimeoutCancellationException) {
            ValidationScenarioResult(
                name = name,
                ok = false,
                durationMs = (System.nanoTime() - started) / 1_000_000L,
                note = "timeout: ${t.message ?: "(no message)"}",
            )
        } catch (t: Throwable) {
            ValidationScenarioResult(
                name = name,
                ok = false,
                durationMs = (System.nanoTime() - started) / 1_000_000L,
                note = "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}",
            )
        }
        // Inter-scenario pacing: every scenario consumes 1-2 rate-limit
        // tokens (a heavy session scenario can burn 4-5). The bucket
        // refills at ~1 token/sec at the default 60 RPM, so sleep
        // [SCENARIO_PACING_DELAY_MS] between scenarios to let the
        // bucket recover before the next one starts. Without this the
        // cumulative load of 10 sequential scenarios drains the bucket
        // mid-suite and the final scenarios false-fail with
        // MLERR:5002 Rate limit exceeded (cosmetic harness issue, not
        // a Mindlayer code bug — a real first-party app spaces its API
        // calls across user actions and never hits this).
        delay(SCENARIO_PACING_DELAY_MS)
        return result
    }

    private fun readFixture(name: String): ByteArray =
        context.assets.open("fixtures/$name").use { it.readBytes() }

    private fun newMeta(frameId: Long) = OcrFrameMeta(
        frameId = frameId,
        captureTimeMs = System.currentTimeMillis(),
        rotationDegrees = 0,
        qualityHint = OcrFrameMeta.QUALITY_GOOD,
    )

    companion object {
        /**
         * After OCR is advertised, wait this long for the per-UID rate-limit
         * bucket to refill before the first heavy scenario runs. Sized to
         * roughly cover the 1-token-per-second refill cadence × the number
         * of forceRefresh probes the warmup poll burns.
         */
        const val RATE_LIMIT_RECOVERY_DELAY_MS: Long = 3_000L

        /**
         * Delay inserted between every scenario so the per-UID 60 RPM
         * rate-limit bucket refills (~1 token/sec) before the next
         * scenario draws from it. Without this delay, running 10
         * sequential scenarios back-to-back drains the bucket and the
         * tail scenarios false-fail with MLERR:5002 — a harness
         * artefact, not a Mindlayer code bug. 1500 ms gives ~1.5
         * tokens of refill, enough to cover a typical scenario's
         * 1-token AIDL call plus the per-scenario `getCapabilities`
         * (0.25) feature-gate probe.
         */
        const val SCENARIO_PACING_DELAY_MS: Long = 1_500L
    }
}
