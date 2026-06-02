package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Empirical KV-cache memory benchmark for Gemma 4 E2B via LiteRT-LM.
 *
 * # Why this exists
 *
 * `MemoryBudget.computeDeviceTier` currently caps `maxMaxTokens` at
 * 2048/4096/16384/32768 purely from total system RAM, with no empirical
 * bytes/token grounding. This test produces the missing data so the tier
 * table can be re-derived from measurement instead of guesswork.
 *
 * # What it measures
 *
 * Three phases, each gated on `phase` instrumentation arg so a single
 * `connectedDebugAndroidTest` invocation runs exactly one measurement:
 *
 *  - **smoke**: 3 quick points (1024 / 4096 / 16384) to validate the
 *    pre-allocation hypothesis. If `RSS(16384) - RSS(1024)` is near zero,
 *    KV is not the dominant per-token cost and the full sweep is wasted.
 *  - **sweep**: one point at the given `maxNumTokens`. Driver script
 *    invokes this N times across `[1024, 2048, 4096, 8192, 16384]`.
 *    Per-N replicates come from re-invoking the driver — each gradle
 *    invocation spawns a fresh test process, sidestepping the
 *    LiteRT-LM 0.10.0 close-then-recreate SIGSEGV (carried into 0.12.0
 *    until proven otherwise).
 *  - **multisession**: one engine at a fixed `maxNumTokens`, plus N
 *    `Conversation` instances created sequentially. Determines whether
 *    KV is pooled at the engine level or allocated per-conversation.
 *
 * # Memory metric
 *
 *  - `/proc/self/smaps_rollup` — primary. Provides Rss, Pss,
 *    Private_Dirty, Private_Clean, Shared_Clean, SwapPss. Catches
 *    mmap'd KV that `Debug.getNativeHeapAllocatedSize()` misses.
 *  - `Debug.getNativeHeapAllocatedSize()` — cross-check; expected to
 *    UNDERCOUNT because LiteRT-LM uses ashmem/mmap arenas.
 *  - Peak captured via a background sampler at 250 ms cadence during init.
 *
 * # Methodology notes
 *
 *  - x86_64 emulator. KV bytes/token is a model-geometry property and
 *    transfers to arm64; latency does NOT.
 *  - Constructs `Engine` directly, bypassing `EngineManager`'s 1 GB
 *    headroom floor — the benchmark deliberately wants to stress the
 *    upper tiers and observe native init failure modes.
 *  - Output is appended to `<externalFilesDir>/kv-bench/measurements.csv`
 *    so multiple driver invocations accumulate into one analysable file.
 *
 * # Run
 *
 * ```powershell
 * # Smoke
 * .\gradlew.bat :app:connectedDebugAndroidTest `
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.mindlayer.service.engine.KvCacheMemoryBenchmarkInstrumentedTest" `
 *   "-Pandroid.testInstrumentationRunnerArguments.phase=smoke"
 *
 * # Full sweep — driver shell loop:
 * foreach ($n in 1024,2048,4096,8192,16384) {
 *   foreach ($r in 1..5) {
 *     .\gradlew.bat :app:connectedDebugAndroidTest `
 *       "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.mindlayer.service.engine.KvCacheMemoryBenchmarkInstrumentedTest#measure_one_point" `
 *       "-Pandroid.testInstrumentationRunnerArguments.maxNumTokens=$n" `
 *       "-Pandroid.testInstrumentationRunnerArguments.rep=$r" `
 *       "-Pandroid.testInstrumentationRunnerArguments.phase=sweep"
 *   }
 * }
 * adb pull /sdcard/Android/data/<pkg>/files/kv-bench .\artifacts\
 * ```
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class KvCacheMemoryBenchmarkInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args = InstrumentationRegistry.getArguments()

    private fun arg(key: String): String? = args.getString(key)

    @Test
    fun measure_one_point() {
        val phase = arg("phase") ?: "sweep"
        when (phase) {
            "smoke" -> runSmoke()
            "sweep" -> runSweepPoint()
            "multisession" -> runMultiSession()
            else -> error("Unknown phase '$phase'. Use smoke|sweep|multisession.")
        }
    }

    // --- phase: smoke ---------------------------------------------------------

    private fun runSmoke() {
        val modelFile = locateGemmaModel() ?: skipMissingModel()
        val outFile = openCsv("smoke.csv")
        for (n in intArrayOf(1024, 4096, 16384)) {
            measureOnePoint(
                phase = "smoke",
                maxNumTokens = n,
                rep = 0,
                modelFile = modelFile,
                outFile = outFile,
            )
            // Each measurement closes the engine. Sleep so the sampler thread
            // and any deferred native cleanup settles before the next ctor.
            Thread.sleep(2_000L)
        }
    }

    // --- phase: sweep ---------------------------------------------------------

    private fun runSweepPoint() {
        val modelFile = locateGemmaModel() ?: skipMissingModel()
        val n = arg("maxNumTokens")?.toIntOrNull()
            ?: error("Sweep phase requires -Pandroid.testInstrumentationRunnerArguments.maxNumTokens=N")
        val rep = arg("rep")?.toIntOrNull() ?: 0
        val outFile = openCsv("sweep.csv")
        measureOnePoint(
            phase = "sweep",
            maxNumTokens = n,
            rep = rep,
            modelFile = modelFile,
            outFile = outFile,
        )
    }

    // --- phase: multisession --------------------------------------------------

    private fun runMultiSession() {
        val modelFile = locateGemmaModel() ?: skipMissingModel()
        val maxNumTokens = arg("maxNumTokens")?.toIntOrNull() ?: 4096
        val numConversations = arg("numConversations")?.toIntOrNull() ?: 4
        val outFile = openCsv("multisession.csv")
        if (!outFile.exists()) {
            outFile.writeText(MULTISESSION_HEADER)
        }

        val baseline = readMemorySnapshot()
        Log.i(TAG, "multisession baseline: $baseline")

        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = null,
                maxNumTokens = maxNumTokens,
                maxNumImages = 1,
                cacheDir = File(ctx.cacheDir, "kv-bench-cache").apply { mkdirs() }.absolutePath,
            )
        )
        try {
            val initStart = SystemClock.elapsedRealtime()
            engine.initialize()
            val initMs = SystemClock.elapsedRealtime() - initStart
            Thread.sleep(2_000L)
            val postInit = readMemorySnapshot()
            outFile.appendText(formatMultisessionRow(maxNumTokens, 0, "after_init", initMs, baseline, postInit))

            val convs = mutableListOf<com.google.ai.edge.litertlm.Conversation>()
            for (i in 1..numConversations) {
                val conv = engine.createConversation(ConversationConfig())
                convs += conv
                Thread.sleep(500L)
                val afterCreate = readMemorySnapshot()
                outFile.appendText(formatMultisessionRow(maxNumTokens, i, "after_create", 0L, baseline, afterCreate))
            }

            convs.forEachIndexed { idx, conv ->
                runBlocking {
                    val msg = Message.user(Contents.of("Hi"))
                    conv.sendMessageAsync(msg.contents).collectIgnoringOutput()
                }
                Thread.sleep(500L)
                val afterSend = readMemorySnapshot()
                outFile.appendText(formatMultisessionRow(maxNumTokens, idx + 1, "after_send", 0L, baseline, afterSend))
            }
        } finally {
            try {
                engine.close()
            } catch (t: Throwable) {
                Log.w(TAG, "engine.close threw (known LiteRT-LM hazard): ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // --- core measurement ------------------------------------------------------

    private fun measureOnePoint(
        phase: String,
        maxNumTokens: Int,
        rep: Int,
        modelFile: File,
        outFile: File,
    ) {
        if (!outFile.exists()) {
            outFile.writeText(MEASURE_HEADER)
        }

        val baseline = readMemorySnapshot()
        Log.i(TAG, "phase=$phase n=$maxNumTokens rep=$rep baseline=$baseline")

        val peakRss = AtomicLong(baseline.rssKb)
        val peakPss = AtomicLong(baseline.pssKb)
        val samplerActive = AtomicBoolean(true)
        val sampler = Thread({
            while (samplerActive.get()) {
                runCatching {
                    val s = readMemorySnapshot()
                    peakRss.updateAndGet { max(it, s.rssKb) }
                    peakPss.updateAndGet { max(it, s.pssKb) }
                }
                try {
                    Thread.sleep(SAMPLER_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "kv-bench-sampler")
        sampler.isDaemon = true
        sampler.start()

        var engine: Engine? = null
        var initFailure: Throwable? = null
        val initStart = SystemClock.elapsedRealtime()
        try {
            engine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = null,
                    maxNumTokens = maxNumTokens,
                    maxNumImages = 1,
                    cacheDir = File(ctx.cacheDir, "kv-bench-cache").apply { mkdirs() }.absolutePath,
                )
            )
            engine.initialize()
        } catch (t: Throwable) {
            initFailure = t
            Log.w(TAG, "Engine init FAILED at maxNumTokens=$maxNumTokens: $t")
        }
        val initMs = SystemClock.elapsedRealtime() - initStart

        Thread.sleep(QUIESCE_MS)
        val postInit = readMemorySnapshot()
        samplerActive.set(false)
        sampler.interrupt()
        sampler.join(1_000L)

        Log.i(
            TAG,
            "phase=$phase n=$maxNumTokens rep=$rep initMs=$initMs ok=${initFailure == null} " +
                "postInit=$postInit peakRss=${peakRss.get()} peakPss=${peakPss.get()}",
        )

        outFile.appendText(
            formatMeasureRow(
                phase = phase,
                maxNumTokens = maxNumTokens,
                rep = rep,
                initMs = initMs,
                initOk = initFailure == null,
                initError = initFailure?.let { "${it.javaClass.simpleName}:${it.message?.take(120)}" } ?: "",
                baseline = baseline,
                postInit = postInit,
                peakRssKb = peakRss.get(),
                peakPssKb = peakPss.get(),
            )
        )

        // Clean up — close may itself throw on a partially-initialised handle
        // (LiteRT-LM hazard). Swallow so the CSV row is the test's verdict.
        engine?.let {
            try {
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG, "engine.close threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // --- helpers --------------------------------------------------------------

    private fun openCsv(name: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "kv-bench").apply { mkdirs() }
        return File(dir, name)
    }

    private fun skipMissingModel(): Nothing {
        val expected = listOf(
            File(ctx.getExternalFilesDir(null), GEMMA_MODEL_FILENAME).absolutePath,
            File(ctx.filesDir, GEMMA_MODEL_FILENAME).absolutePath,
            "/data/local/tmp/$GEMMA_MODEL_FILENAME",
        )
        assumeTrue(
            "Gemma 4 E2B model not found. Push it with tools/dev-models/push-models.ps1 -Gemma. " +
                "Searched: $expected",
            false,
        )
        error("unreachable")
    }

    private fun locateGemmaModel(): File? {
        val candidates = listOf(
            File(ctx.getExternalFilesDir(null), GEMMA_MODEL_FILENAME),
            File(ctx.filesDir, GEMMA_MODEL_FILENAME),
            File("/data/local/tmp/$GEMMA_MODEL_FILENAME"),
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0L }
    }

    private data class MemorySnapshot(
        val rssKb: Long,
        val pssKb: Long,
        val privateDirtyKb: Long,
        val privateCleanKb: Long,
        val sharedCleanKb: Long,
        val swapPssKb: Long,
        val nativeHeapKb: Long,
        val totalPssAndroidKb: Long,
    ) {
        override fun toString(): String =
            "Rss=${rssKb}kB Pss=${pssKb}kB PDirty=${privateDirtyKb}kB " +
                "PClean=${privateCleanKb}kB SClean=${sharedCleanKb}kB SwapPss=${swapPssKb}kB " +
                "nativeHeap=${nativeHeapKb}kB totalPss(Debug)=${totalPssAndroidKb}kB"
    }

    private fun readMemorySnapshot(): MemorySnapshot {
        val rollup = runCatching { File("/proc/self/smaps_rollup").readText() }.getOrNull().orEmpty()
        fun extract(key: String): Long {
            val re = Regex("^$key:\\s+(\\d+)\\s+kB", RegexOption.MULTILINE)
            return re.find(rollup)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
        val debugInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return MemorySnapshot(
            rssKb = extract("Rss"),
            pssKb = extract("Pss"),
            privateDirtyKb = extract("Private_Dirty"),
            privateCleanKb = extract("Private_Clean"),
            sharedCleanKb = extract("Shared_Clean"),
            swapPssKb = extract("SwapPss"),
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
            totalPssAndroidKb = debugInfo.totalPss.toLong(),
        )
    }

    private fun formatMeasureRow(
        phase: String,
        maxNumTokens: Int,
        rep: Int,
        initMs: Long,
        initOk: Boolean,
        initError: String,
        baseline: MemorySnapshot,
        postInit: MemorySnapshot,
        peakRssKb: Long,
        peakPssKb: Long,
    ): String = buildString {
        append(phase); append(',')
        append(maxNumTokens); append(',')
        append(rep); append(',')
        append(initMs); append(',')
        append(if (initOk) 1 else 0); append(',')
        append('"'); append(initError.replace('"', '\'')); append('"'); append(',')
        appendSnapshot(baseline)
        appendSnapshot(postInit)
        append(peakRssKb); append(',')
        append(peakPssKb); append(',')
        append(System.currentTimeMillis())
        append('\n')
    }

    private fun formatMultisessionRow(
        maxNumTokens: Int,
        numConversations: Int,
        stage: String,
        initMs: Long,
        baseline: MemorySnapshot,
        snapshot: MemorySnapshot,
    ): String = buildString {
        append(maxNumTokens); append(',')
        append(numConversations); append(',')
        append(stage); append(',')
        append(initMs); append(',')
        appendSnapshot(baseline)
        appendSnapshot(snapshot)
        append(System.currentTimeMillis())
        append('\n')
    }

    private fun StringBuilder.appendSnapshot(s: MemorySnapshot) {
        append(s.rssKb); append(',')
        append(s.pssKb); append(',')
        append(s.privateDirtyKb); append(',')
        append(s.privateCleanKb); append(',')
        append(s.sharedCleanKb); append(',')
        append(s.swapPssKb); append(',')
        append(s.nativeHeapKb); append(',')
        append(s.totalPssAndroidKb); append(',')
    }

    private suspend fun Flow<*>.collectIgnoringOutput() = collect { /* drain */ }

    companion object {
        private const val TAG = "KvCacheBench"
        private const val GEMMA_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val SAMPLER_INTERVAL_MS = 250L
        private const val QUIESCE_MS = 2_000L

        private const val MEASURE_HEADER =
            "phase,max_num_tokens,rep,init_ms,init_ok,init_error," +
                "base_rss_kb,base_pss_kb,base_pdirty_kb,base_pclean_kb,base_sclean_kb,base_swap_kb,base_nheap_kb,base_total_pss_kb," +
                "post_rss_kb,post_pss_kb,post_pdirty_kb,post_pclean_kb,post_sclean_kb,post_swap_kb,post_nheap_kb,post_total_pss_kb," +
                "peak_rss_kb,peak_pss_kb,timestamp_ms\n"

        private const val MULTISESSION_HEADER =
            "max_num_tokens,num_conversations,stage,init_ms," +
                "base_rss_kb,base_pss_kb,base_pdirty_kb,base_pclean_kb,base_sclean_kb,base_swap_kb,base_nheap_kb,base_total_pss_kb," +
                "snap_rss_kb,snap_pss_kb,snap_pdirty_kb,snap_pclean_kb,snap_sclean_kb,snap_swap_kb,snap_nheap_kb,snap_total_pss_kb," +
                "timestamp_ms\n"
    }
}
