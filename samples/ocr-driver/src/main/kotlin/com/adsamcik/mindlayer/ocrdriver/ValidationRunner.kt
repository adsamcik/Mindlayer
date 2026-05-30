package com.adsamcik.mindlayer.ocrdriver

import android.content.Context
import android.os.Build
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrImageOptions
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.OcrEvent
import com.adsamcik.mindlayer.sdk.OcrProfile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
 * async path (`Mindlayer.ocrImage`) or the multi-frame realtime path
 * (`Mindlayer.ocrRealtime` + frame pushes + event stream). The matrix is
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
 * Reads PNG fixtures from the sample APK's `assets/fixtures/` — synthetic
 * black-text-on-white images generated procedurally at build time. No
 * user data, no PII.
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
                "FEATURE_OCR_SESSION not advertised — engine not ready or production gate off"
            }
            check(caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)) {
                "FEATURE_OCR_IMAGE_ONESHOT not advertised — engine not ready or production gate off"
            }
            "FEATURE_OCR_SESSION + FEATURE_OCR_IMAGE_ONESHOT both advertised"
        }

        results += scenario("single_image_no_llm") {
            val bytes = readFixture("receipt.png")
            val result = mindlayer.ocrAsync(bytes = bytes, mimeType = "image/png")
            check(result.lines.isNotEmpty()) {
                "ocrAsync returned 0 lines on receipt.png — recognition failed"
            }
            "lines=${result.lines.size} ocr=${result.ocrDurationMs}ms"
        }

        results += scenario("single_image_with_llm") {
            val bytes = readFixture("receipt.png")
            val result = mindlayer.ocrAsync(
                bytes = bytes,
                mimeType = "image/png",
                options = OcrImageOptions(
                    runLlmExtraction = true,
                    extractionSchemaJson =
                        """{"type":"object","properties":{"total":{"type":"string"}}}""",
                ),
            )
            check(result.lines.isNotEmpty()) {
                "ocrAsync(runLlm=true) returned 0 lines — recognition failed"
            }
            // LLM may legitimately return no fields if Gemma isn't loaded.
            // We only assert that the LLM stage RAN (llmDurationMs > 0).
            check(result.llmDurationMs > 0L) {
                "LLM extraction stage did not run despite runLlmExtraction=true"
            }
            "lines=${result.lines.size} llm=${result.llmDurationMs}ms " +
                "fields=${result.extractionFields.size}"
        }

        results += scenario("single_image_bbox") {
            val bytes = readFixture("document.png")
            val result = mindlayer.ocrAsync(
                bytes = bytes,
                mimeType = "image/png",
                options = OcrImageOptions(emitBoundingBoxes = true),
            )
            check(result.lines.isNotEmpty()) { "no lines extracted" }
            val withBbox = result.lines.count { it.boundingBox != null }
            check(withBbox > 0) {
                "emitBoundingBoxes=true but no lines came back with a bounding box"
            }
            "linesWithBbox=$withBbox/${result.lines.size}"
        }

        results += scenario("session_lifecycle_basic") {
            // Open a session, attach event stream, push one encoded frame,
            // finalize, assert we see a RESULT_FINALIZED event.
            mindlayer.ocrRealtime(OcrProfile.GeneralDocument).use { session ->
                val events = mutableListOf<OcrEvent>()
                // The events Flow is cold + attaches the pipe on first
                // collect. We need to start collecting BEFORE pushing
                // any frame (the service rejects with
                // STATUS_REJECTED_STREAM_NOT_ATTACHED otherwise — see
                // `session_stream_not_attached_rejects` below).
                kotlinx.coroutines.coroutineScope {
                    val collector = launch {
                        session.events.toList(events)
                    }
                    // Give the service a moment to wire the pipe.
                    delay(200)

                    val bytes = readFixture("document.png")
                    val ack = session.pushEncodedFrame(
                        meta = newMeta(frameId = 1L),
                        bytes = bytes,
                        mimeType = "image/png",
                    )
                    check(ack.status == com.adsamcik.mindlayer.OcrFrameAck.STATUS_ACCEPTED) {
                        "expected STATUS_ACCEPTED, got ${ack.status}"
                    }

                    session.finalize()

                    // Bound the wait — the pipe closes when the service
                    // emits the terminal event, so the collector finishes.
                    withTimeoutOrNull(10_000L) { collector.join() }
                        ?: run {
                            collector.cancel()
                            error("event collector did not finish within 10s of finalize()")
                        }
                }

                val finalized = events.filterIsInstance<OcrEvent.ResultFinalized>().firstOrNull()
                check(finalized != null) {
                    "no ResultFinalized in event stream (events=${events.size})"
                }
                "events=${events.size} finalJsonLen=${finalized.fullJson.length}"
            }
        }

        results += scenario("session_stream_not_attached_rejects") {
            // Pushing before attaching the event pipe must surface a
            // STATUS_REJECTED_STREAM_NOT_ATTACHED ack. Without this
            // contract, OCR results could be lost silently.
            mindlayer.ocrRealtime(OcrProfile.GeneralDocument).use { session ->
                val bytes = readFixture("document.png")
                val ack = session.pushEncodedFrame(
                    meta = newMeta(frameId = 1L),
                    bytes = bytes,
                    mimeType = "image/png",
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
            val session = mindlayer.ocrRealtime(OcrProfile.GeneralDocument)
            session.close()
            session.close() // second close MUST be a no-op
            "close() twice raised no exception"
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
        return try {
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
    }

    private fun readFixture(name: String): ByteArray =
        context.assets.open("fixtures/$name").use { it.readBytes() }

    private fun newMeta(frameId: Long) = OcrFrameMeta(
        frameId = frameId,
        captureTimeMs = System.currentTimeMillis(),
        rotationDegrees = 0,
        qualityHint = OcrFrameMeta.QUALITY_GOOD,
    )
}
