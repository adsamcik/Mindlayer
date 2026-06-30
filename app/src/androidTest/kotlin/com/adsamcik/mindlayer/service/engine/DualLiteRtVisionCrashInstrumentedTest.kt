package com.adsamcik.mindlayer.service.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DIFFERENTIAL repro for the LiteRT-LM #2028 / #2211 dual-`libLiteRt.so` theory.
 *
 * The minimal standalone harness (`G:\Github\litertlm-2028-repro`) depends on
 * `litertlm-android` ONLY, so its APK packages litertlm's OWN `libLiteRt.so`
 * (the core that `liblitertlm_jni.so` was built against). It runs two vision
 * inferences — and N close/recreate cycles — with NO crash.
 *
 * The Mindlayer APK additionally depends on `com.google.ai.edge.litert:litert`
 * for EmbeddingGemma + PaddleOCR. BOTH AARs ship `libLiteRt.so` under the same
 * SONAME at DIFFERENT builds; `packaging { jniLibs { pickFirsts +=
 * "lib/<abi>/libLiteRt*.so" } }` keeps exactly one, and APK inspection shows the
 * one kept is **litert's** copy (5,328,296 B), NOT litertlm's (5,064,144 B).
 * So in this APK, `liblitertlm_jni.so` dlopen()s a FOREIGN `libLiteRt.so` —
 * the "loser stack" hazard called out in docs/architecture/LITERT_COEXISTENCE.md
 * checklist item #7.
 *
 * This test runs the EXACT SAME raw litertlm double-vision logic as the
 * standalone harness, but inside the Mindlayer APK. If it SIGSEGVs here while
 * the standalone harness does not, the only changed variable is which
 * `libLiteRt.so` is packaged — which would prove the dual-litert mismatch is the
 * crash cause, independent of Mindlayer's higher-level orchestration.
 *
 * EMPIRICAL RESULT (litertlm 0.13.1 + litert 2.1.5, Gemma 4 E2B, CPU, x86_64
 * Android-36 emulator, 2026-06-30): the crash does NOT reproduce here. All three
 * variants SURVIVED with no SIGSEGV / tombstone, even though `liblitertlm_jni.so`
 * verifiably dlopen()'d the FOREIGN (litert 2.1.5) `libLiteRt.so`:
 *   1. [twoVisionInferencesInSameProcess_onMindlayerPackagedLibLiteRt] — 2 vision OK.
 *   2. [gemmaDoubleVision_whileBaseLiteRtEmbeddingActive] — base litert (XNNPACK
 *      CompiledModel) held active + 2 Gemma vision OK.
 *   3. [gemmaCloseRecreateCycles_onMindlayerPackagedLibLiteRt] — base litert active
 *      + 5 Gemma close/recreate cycles OK.
 *
 * CONCLUSION: the static two-`libLiteRt.so` packaging collision is REAL and
 * confirmed (see docs/architecture/LITERT_COEXISTENCE.md), but on the CPU path it
 * is NOT sufficient to trigger #2028 — not even with both runtimes initialised or
 * across close/recreate. The original Mindlayer-service crash therefore needs a
 * condition this harness can't exercise on a SwiftShader emulator: the GPU/NPU
 * accelerator route (where the cited upstream issues #5264 / #2211 / #2292 all
 * live and two LiteRT runtimes genuinely contend for OpenCL/delegate/environment
 * state), or a service-orchestration specific path (warm slots, structured output).
 *
 * @Ignore: heavy diagnostic harness (needs a pushed ~2.4GB model). Run explicitly:
 *   adb shell am instrument -w -e class \
 *     com.adsamcik.mindlayer.service.engine.DualLiteRtVisionCrashInstrumentedTest \
 *     com.adsamcik.mindlayer.debug.test/androidx.test.runner.AndroidJUnitRunner
 * Pull the marker logs:
 *   adb shell run-as com.adsamcik.mindlayer.debug cat files/dual-litert-repro.log
 *   adb shell run-as com.adsamcik.mindlayer.debug cat files/dual-litert-coexist.log
 *   adb shell run-as com.adsamcik.mindlayer.debug cat files/dual-litert-recreate.log
 */
@RunWith(AndroidJUnit4::class)
class DualLiteRtVisionCrashInstrumentedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Ignore("Diagnostic harness — needs a pushed 2.4GB Gemma model; run explicitly, see class KDoc")
    @Test
    fun twoVisionInferencesInSameProcess_onMindlayerPackagedLibLiteRt() {
        val log = File(context.filesDir, "dual-litert-repro.log").apply { writeText("") }
        fun mark(line: String) {
            val stamped = "${System.currentTimeMillis()} $line"
            log.appendText(stamped + "\n")
            Log.i(TAG, stamped)
        }

        // The native loader needs a real ext4 path. Mindlayer resolves models
        // from filesDir; if not present, copy from the (adb-pushable) external
        // files dir — same trick the standalone harness uses.
        val internalModel = File(context.filesDir, MODEL_FILE)
        if (!internalModel.isFile) {
            val pushed = File(context.getExternalFilesDir(null), MODEL_FILE)
            assumeTrue(
                "Model missing — push it: adb push gemma-4-E2B-it.litertlm " +
                    "/sdcard/Android/data/com.adsamcik.mindlayer.debug/files/$MODEL_FILE",
                pushed.isFile,
            )
            mark("Copying model to internal filesDir (${pushed.length()} bytes)…")
            pushed.copyTo(internalModel, overwrite = true)
            mark("Copy done")
        }
        val modelPath = internalModel.absolutePath
        val imagePath = syntheticLabel().absolutePath

        mark("Engine init… (litertlm_jni dlopen()s the APK-packaged libLiteRt.so)")
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = 2048,
                maxNumImages = 1,
                cacheDir = context.cacheDir.absolutePath,
            ),
        )
        try {
            engine.initialize()
            mark("Engine init ok")

            mark("VISION #1…")
            val first = visionInfer(engine, imagePath)
            mark("VISION #1 ok chars=${first.length}")

            // The documented #2028 trigger: a SECOND multimodal inference in the
            // same process. On a matching libLiteRt.so (standalone harness) this
            // succeeds; on the mismatched one (this APK) it is expected to SIGSEGV.
            mark("VISION #2  <-- expected SIGSEGV point if libLiteRt.so is mismatched")
            val second = visionInfer(engine, imagePath)
            mark("VISION #2 ok chars=${second.length}")
            mark("DONE — second vision inference SURVIVED; no dual-litert crash in this APK")
        } catch (t: Throwable) {
            mark("EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * COEXISTENCE variant: initialise **base litert** (`CompiledModel`, the
     * EmbeddingGemma path) and keep it ALIVE while running the Gemma litertlm
     * double-vision — replicating the real Mindlayer service, which holds the
     * embedding + OCR base-litert runtimes active in the same process as the
     * Gemma LiteRT-LM engine. This is the actual condition
     * docs/architecture/LITERT_COEXISTENCE.md + LiteRT #5264 warn about:
     * two LiteRT-family runtimes initialised together in one Android process.
     *
     * If the pure-litertlm test above survives but THIS one SIGSEGVs, the
     * trigger is runtime coexistence of both literts, not the static packaging.
     */
    @Ignore("Diagnostic harness — needs pushed Gemma + embedding models; run explicitly, see class KDoc")
    @Test
    fun gemmaDoubleVision_whileBaseLiteRtEmbeddingActive() {
        val log = File(context.filesDir, "dual-litert-coexist.log").apply { writeText("") }
        fun mark(line: String) {
            val stamped = "${System.currentTimeMillis()} $line"
            log.appendText(stamped + "\n")
            Log.i(TAG, stamped)
        }

        val gemmaPath = ensureInternal(MODEL_FILE, ::mark) ?: return
        val embedPath = ensureInternal(EMBED_FILE, ::mark) ?: return
        val imagePath = syntheticLabel().absolutePath

        // 1) Bring up base litert FIRST (matches LITERT_COEXISTENCE.md step 8
        //    ordering: a process that already holds a base-LiteRT classloader +
        //    native delegate). Keep the CompiledModel OPEN for the whole test.
        mark("base-litert CompiledModel.create(embedding, CPU)…")
        val embedModel = CompiledModel.create(embedPath, CompiledModel.Options(Accelerator.CPU))
        mark("base-litert CompiledModel ready (libLiteRt.so now active for BOTH runtimes)")
        runCatching {
            val ins = embedModel.createInputBuffers()
            val outs = embedModel.createOutputBuffers()
            ins[0].writeInt(IntArray(EMBED_SEQ))
            if (ins.size >= 2) ins[1].writeInt(IntArray(EMBED_SEQ))
            embedModel.run(ins, outs)
            ins.forEach { runCatching { it.close() } }
            outs.forEach { runCatching { it.close() } }
            mark("base-litert one embedding inference ok")
        }.onFailure { mark("base-litert embedding run skipped: ${it.javaClass.simpleName}: ${it.message}") }

        // 2) Now the Gemma litertlm double-vision, with base litert still alive.
        mark("Gemma Engine init… (both runtimes now coexisting)")
        val engine = Engine(
            EngineConfig(
                modelPath = gemmaPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = 2048,
                maxNumImages = 1,
                cacheDir = context.cacheDir.absolutePath,
            ),
        )
        try {
            engine.initialize()
            mark("Gemma init ok")
            mark("VISION #1…")
            val first = visionInfer(engine, imagePath)
            mark("VISION #1 ok chars=${first.length}")
            mark("VISION #2  <-- expected SIGSEGV point under coexistence")
            val second = visionInfer(engine, imagePath)
            mark("VISION #2 ok chars=${second.length}")
            mark("DONE — coexistence double-vision SURVIVED; no crash on CPU")
        } catch (t: Throwable) {
            mark("EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { engine.close() }
            runCatching { embedModel.close() }
        }
    }

    /**
     * The #2028 CLOSE/RECREATE path, in the dual-litert (mismatched libLiteRt.so)
     * Mindlayer APK. The standalone harness loops this with litertlm's MATCHING
     * libLiteRt.so and never crashes; Mindlayer's real EngineManager closes +
     * recreates the engine as its #2028 mitigation. This is the untested
     * combination: the documented "SIGSEGV on the 2nd Engine.close()+new Engine()"
     * but against the FOREIGN core that this APK actually packages. Optionally
     * keeps base litert active to also exercise full coexistence.
     */
    @Ignore("Diagnostic harness — needs pushed Gemma + embedding models; run explicitly, see class KDoc")
    @Test
    fun gemmaCloseRecreateCycles_onMindlayerPackagedLibLiteRt() {
        val log = File(context.filesDir, "dual-litert-recreate.log").apply { writeText("") }
        fun mark(line: String) {
            val stamped = "${System.currentTimeMillis()} $line"
            log.appendText(stamped + "\n")
            Log.i(TAG, stamped)
        }

        val gemmaPath = ensureInternal(MODEL_FILE, ::mark) ?: return
        val embedPath = ensureInternal(EMBED_FILE, ::mark) ?: return
        val imagePath = syntheticLabel().absolutePath

        // Keep base litert active for the whole run (full three-runtime-style coexistence).
        mark("base-litert CompiledModel.create(embedding, CPU)…")
        val embedModel = CompiledModel.create(embedPath, CompiledModel.Options(Accelerator.CPU))
        mark("base-litert ready; now looping Gemma close/recreate cycles")

        try {
            for (i in 1..RECREATE_CYCLES) {
                mark("CYCLE $i/$RECREATE_CYCLES: new Engine + init…")
                val engine = Engine(
                    EngineConfig(
                        modelPath = gemmaPath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 2048,
                        maxNumImages = 1,
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                )
                try {
                    engine.initialize()
                    mark("CYCLE $i: init ok")
                    val out = visionInfer(engine, imagePath)
                    mark("CYCLE $i: vision ok chars=${out.length}")
                } catch (t: Throwable) {
                    mark("CYCLE $i: EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
                    runCatching { engine.close() }
                    return
                }
                mark("CYCLE $i: close… <-- documented #2028 close/recreate boundary")
                engine.close()
                mark("CYCLE $i: closed")
            }
            mark("DONE — $RECREATE_CYCLES close/recreate cycles SURVIVED on the mismatched core")
        } finally {
            runCatching { embedModel.close() }
        }
    }

    /** Copy [name] from the external files dir into internal filesDir (native open() needs a real path). */
    private fun ensureInternal(name: String, mark: (String) -> Unit): String? {
        val internal = File(context.filesDir, name)
        if (!internal.isFile) {
            val pushed = File(context.getExternalFilesDir(null), name)
            if (!pushed.isFile) {
                mark("MODEL MISSING — push $name to ${pushed.absolutePath} (chmod 666)")
                return null
            }
            mark("Copying $name to internal filesDir (${pushed.length()} bytes)…")
            pushed.copyTo(internal, overwrite = true)
            mark("Copy done: $name")
        }
        return internal.absolutePath
    }

    private fun visionInfer(engine: Engine, imagePath: String): String {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of("You read coffee bag labels."),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.1),
            ),
        )
        try {
            val response: Message = conversation.sendMessage(
                Message.user(
                    Contents.of(
                        Content.Text("List the brand and roast level you can read on this label."),
                        Content.ImageFile(imagePath),
                    ),
                ),
            )
            val parts = response.contents.contents.filterIsInstance<Content.Text>()
            return parts.joinToString("") { it.text }
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun syntheticLabel(): File {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(244, 236, 222))
            val paint = Paint().apply { color = Color.rgb(40, 30, 20); textSize = 54f; isAntiAlias = true }
            drawText("STARLIT COFFEE", 40f, 200f, paint)
            paint.textSize = 40f
            drawText("Medium Roast", 40f, 280f, paint)
        }
        val out = File(context.cacheDir, "synthetic_label.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return out
    }

    private companion object {
        private const val TAG = "DualLiteRt2028"
        private const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val EMBED_FILE = "embedding-gemma-300m-v1.tflite"
        private const val EMBED_SEQ = 2048
        private const val RECREATE_CYCLES = 5
    }
}
