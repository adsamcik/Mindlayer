package com.adsamcik.mindlayer.service.engine

import android.os.Build
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single coordination point for LiteRT-family accelerator intent labels.
 *
 * This resolver does not claim exclusive GPU/NPU ownership yet; it centralises
 * the SoC/native-library/API gates and records the downgrade chain so engine
 * status surfaces can explain why a feature selected CPU/GPU/NPU.
 *
 * OCR uses the same NPU-on-explicit-request / GPU-default-with-CPU-fallback
 * policy as chat. The historical CPU lock (reason
 * `OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED`) is removed; callers that need a
 * conservative configuration must pass `preferredBackend = "CPU"` explicitly.
 * Heightened LiteRT issue #5264 hazard from three sequential `CompiledModel`
 * instances (det + rec + cls) is still tracked in `docs/LITERT_COEXISTENCE.md`
 * — validate on real devices before relying on GPU/NPU OCR in production.
 */
internal object LiteRtAcceleratorResolver {
    data class AcceleratorDecision(
        val backend: String,
        val reason: String,
        val attempted: List<Pair<String, String>>,
    )

    private data class ProbeKey(val nativeLibraryDir: String?, val apiLevel: Int, val socModel: String)

    private data class NpuProbe(
        val supported: Boolean,
        val reason: String,
        val libs: List<String>,
    )

    private data class EnvironmentOverride(
        val apiLevel: Int,
        val socModel: String,
        val libs: List<String>,
    )

    private val npuProbeCache = ConcurrentHashMap<ProbeKey, NpuProbe>()
    private val latestDecisions = ConcurrentHashMap<String, AcceleratorDecision>()

    @Volatile
    private var environmentOverride: EnvironmentOverride? = null

    fun resolveBackend(
        requested: String? = null,
        featureName: String,
        nativeLibraryDir: String? = null,
    ): AcceleratorDecision {
        val feature = featureName.lowercase()
        require(feature in SUPPORTED_FEATURES) {
            "Unsupported LiteRT featureName=$featureName"
        }

        val decision = when (feature) {
            "ocr" -> resolveOcr(requested, nativeLibraryDir)
            "embeddings" -> resolveEmbeddings(requested, nativeLibraryDir)
            "chat" -> resolveChat(requested, nativeLibraryDir)
            else -> error("unreachable")
        }
        latestDecisions[feature] = decision
        return decision
    }

    fun latestDecision(featureName: String): AcceleratorDecision? =
        latestDecisions[featureName.lowercase()]

    private fun resolveOcr(requested: String?, nativeLibraryDir: String?): AcceleratorDecision {
        val attempted = mutableListOf<Pair<String, String>>()
        return when (requested.normalizedBackend()) {
            "CPU" -> decision("CPU", "REQUESTED_CPU", attempted + ("CPU" to "selected"))
            "GPU" -> decision("GPU", "REQUESTED_GPU", attempted + ("GPU" to "selected"))
            "NPU" -> {
                val probe = probeNpu(nativeLibraryDir)
                attempted += "NPU" to probe.reason
                if (probe.supported) {
                    decision("NPU", "REQUESTED_NPU_SUPPORTED", attempted)
                } else {
                    attempted += "GPU" to "selected"
                    decision("GPU", "REQUESTED_NPU_UNSUPPORTED_GPU_FALLBACK_${probe.reason}", attempted)
                }
            }
            null -> decision("GPU", "DEFAULT_GPU_THEN_CPU_CHAIN", attempted + ("GPU" to "selected"))
            else -> decision("GPU", "UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", attempted + ("GPU" to "selected"))
        }
    }

    /** Mirrors the pre-existing LiteRtEmbeddingBackend.resolveBackend behaviour byte-for-byte. */
    private fun resolveEmbeddings(requested: String?, nativeLibraryDir: String?): AcceleratorDecision {
        val attempted = mutableListOf<Pair<String, String>>()
        return when (requested.normalizedBackend()) {
            "CPU" -> decision("CPU", "REQUESTED_CPU", attempted + ("CPU" to "selected"))
            "GPU" -> decision("GPU", "REQUESTED_GPU", attempted + ("GPU" to "selected"))
            "NPU" -> {
                val probe = probeNpu(nativeLibraryDir)
                attempted += "NPU" to probe.reason
                if (probe.supported) {
                    decision("NPU", "REQUESTED_NPU_SUPPORTED", attempted)
                } else {
                    attempted += "CPU" to "selected"
                    decision("CPU", "REQUESTED_NPU_UNSUPPORTED_${probe.reason}", attempted)
                }
            }
            null -> {
                val probe = probeNpu(nativeLibraryDir)
                attempted += "NPU" to probe.reason
                if (probe.supported) {
                    decision("NPU", "DEFAULT_NPU_SUPPORTED", attempted)
                } else {
                    attempted += "CPU" to "selected"
                    decision("CPU", "DEFAULT_CPU_NPU_UNSUPPORTED_${probe.reason}", attempted)
                }
            }
            else -> decision("CPU", "UNKNOWN_REQUESTED_BACKEND", attempted + ("CPU" to "selected"))
        }
    }

    private fun resolveChat(requested: String?, nativeLibraryDir: String?): AcceleratorDecision {
        val attempted = mutableListOf<Pair<String, String>>()
        return when (requested.normalizedBackend()) {
            "CPU" -> decision("CPU", "REQUESTED_CPU", attempted + ("CPU" to "selected"))
            "GPU" -> decision("GPU", "REQUESTED_GPU", attempted + ("GPU" to "selected"))
            "NPU" -> {
                val probe = probeNpu(nativeLibraryDir)
                attempted += "NPU" to probe.reason
                if (probe.supported) {
                    decision("NPU", "REQUESTED_NPU_SUPPORTED", attempted)
                } else {
                    attempted += "GPU" to "selected"
                    decision("GPU", "REQUESTED_NPU_UNSUPPORTED_GPU_FALLBACK_${probe.reason}", attempted)
                }
            }
            null -> decision("GPU", "DEFAULT_GPU_THEN_CPU_CHAIN", attempted + ("GPU" to "selected"))
            else -> decision("GPU", "UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", attempted + ("GPU" to "selected"))
        }
    }

    private fun decision(
        backend: String,
        reason: String,
        attempted: List<Pair<String, String>>,
    ): AcceleratorDecision = AcceleratorDecision(backend, reason, attempted)

    private fun probeNpu(nativeLibraryDir: String?): NpuProbe {
        val override = environmentOverride
        val apiLevel = override?.apiLevel ?: Build.VERSION.SDK_INT
        @Suppress("InlinedApi")
        val soc = (override?.socModel ?: Build.SOC_MODEL.orEmpty()).lowercase()
        val key = ProbeKey(nativeLibraryDir, apiLevel, soc)
        return npuProbeCache.getOrPut(key) {
            val libs = override?.libs ?: nativeLibraryDir
                ?.let(::File)
                ?.list()
                .orEmpty()
                .toList()
            computeNpuProbe(apiLevel, soc, libs)
        }
    }

    private fun computeNpuProbe(apiLevel: Int, soc: String, libs: List<String>): NpuProbe {
        if (apiLevel < 31) return NpuProbe(false, "NPU_API_BELOW_31", libs)
        if (soc.isBlank()) return NpuProbe(false, "NPU_SOC_UNKNOWN", libs)
        if (soc !in QUALCOMM_NPU_SOCS && soc !in MEDIATEK_NPU_SOCS) {
            return NpuProbe(false, "NPU_SOC_NOT_ALLOWLISTED", libs)
        }
        val hasQnn = libs.any {
            it.startsWith("libQnn") ||
                it.contains("HtpV", ignoreCase = true) ||
                it.equals("libLiteRtQnnAccelerator.so", ignoreCase = true)
        }
        val hasMediaTek = libs.any {
            it.contains("mediatek", ignoreCase = true) ||
                it.contains("dispatch", ignoreCase = true) ||
                it.contains("apuware", ignoreCase = true)
        }
        if (!hasQnn && !hasMediaTek) return NpuProbe(false, "NPU_NATIVE_LIB_MISSING", libs)
        return NpuProbe(true, "NPU_SUPPORTED", libs)
    }

    private fun String?.normalizedBackend(): String? = this?.uppercase()

    @VisibleForTesting
    internal fun resetForTesting() {
        npuProbeCache.clear()
        latestDecisions.clear()
        environmentOverride = null
    }

    @VisibleForTesting
    internal fun setEnvironmentForTesting(apiLevel: Int, socModel: String, libs: List<String>) {
        npuProbeCache.clear()
        environmentOverride = EnvironmentOverride(apiLevel, socModel, libs)
    }

    private val SUPPORTED_FEATURES = setOf("embeddings", "ocr", "chat")

    private val QUALCOMM_NPU_SOCS = setOf(
        "sm8450", "sm8475", "sm8550", "sm8650", "sm8750", "sm8850",
    )
    private val MEDIATEK_NPU_SOCS = setOf(
        "mt6878", "mt6897", "mt6983", "mt6985", "mt6989", "mt6990", "mt6991",
    )
}
