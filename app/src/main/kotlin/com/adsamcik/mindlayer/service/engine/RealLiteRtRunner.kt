package com.adsamcik.mindlayer.service.engine

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * Production [LiteRtRunner] backed by the LiteRT 2.1.5 `CompiledModel`
 * API. Kept in a separate file from [LiteRtEmbeddingBackend] so the
 * backend's pure-Kotlin logic stays unit-testable without triggering
 * `System.loadLibrary("litert")` at class-init time.
 *
 * # I/O shape contract
 *
 * EmbeddingGemma on `huggingface.co/litert-community/embeddinggemma-300m`
 * ships in two on-device signature shapes that this runner supports:
 *   - **standard 2-input** (`input_ids` + `attention_mask`): seq2048 variants
 *     exported with both tensors visible
 *   - **mixed-precision 1-input** (`input_ids` only): the
 *     `embeddinggemma-300M_seq2048_mixed-precision.tflite` variant bakes
 *     attention_mask handling into the graph and uses the pad token (0)
 *     to detect padding
 *
 * Both surface a single `[1, 768]` float32 output (`sentence_embedding`).
 * [runEmbedding] adapts to the actual `inputBuffers.size` rather than
 * hardcoding 2 — see F-079b in the code below.
 */
internal class RealLiteRtRunner private constructor(
    private val compiledModel: CompiledModel,
) : LiteRtRunner {

    override fun runEmbedding(inputIds: IntArray, attentionMask: IntArray): FloatArray {
        val inputBuffers = compiledModel.createInputBuffers()
        try {
            val outputBuffers = compiledModel.createOutputBuffers()
            try {
                // LiteRT-community ships EmbeddingGemma in two on-device
                // signature shapes:
                //   * standard 2-input: [input_ids, attention_mask]
                //   * mixed-precision 1-input: [input_ids] — the variant
                //     bakes attention_mask handling into the graph and
                //     uses pad-token (0) positions to detect padding.
                // Both surface the same [1, 2048] int32 input shape and
                // a single [1, 768] float32 output; differ only in input
                // count. Adapt rather than crash so we work across model
                // variants without an integrity-manifest schema bump.
                inputBuffers[0].writeInt(inputIds)
                if (inputBuffers.size >= 2) {
                    inputBuffers[1].writeInt(attentionMask)
                }
                compiledModel.run(inputBuffers, outputBuffers)
                return outputBuffers[0].readFloat()
            } finally {
                outputBuffers.forEach { runCatching { it.close() } }
            }
        } finally {
            // CompiledModel.createInputBuffers / createOutputBuffers each
            // allocate native backing storage that must be closed to
            // avoid leaking AHardwareBuffer / NPU memory. Nest the
            // finalizers so input buffers are still closed if output
            // buffer creation fails.
            inputBuffers.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { compiledModel.close() }
    }

    companion object {
        fun create(modelPath: String, acceleratorLabel: String): LiteRtRunner {
            val accel = when (acceleratorLabel) {
                "NPU" -> Accelerator.NPU
                "GPU" -> Accelerator.GPU
                else -> Accelerator.CPU
            }
            val options = CompiledModel.Options(accel)
            if (accel == Accelerator.GPU) {
                // EmbeddingGemma-300M's transformer attention produces
                // softmax logits and layer-norm variances that overflow
                // FP16. On Snapdragon 8 Gen 3 (Adreno 750, LITERT_CL
                // OpenCL backend) the GPU delegate's FP16-by-default
                // path returns all-NaN output even though the model
                // compiles cleanly (verified 2026-05-31 on Samsung
                // S928B). Forcing FP32 keeps the computation
                // numerically stable at the cost of ~2x throughput
                // relative to FP16, which is still ~12x faster than
                // CPU XNNPACK on the same device. infiniteFloatCapping
                // is a defence-in-depth NaN/Inf clamp for any op that
                // still overflows under FP32 (rare but possible on
                // long sequences).
                //
                // The numerical guard in LiteRtEmbeddingBackend.attempt
                // Init catches any residual NaN at warm-up time and
                // falls back to CPU before any caller sees a bad
                // vector, so a future GPU regression here is
                // user-invisible — only the latency degrades.
                options.gpuOptions = CompiledModel.GpuOptions(
                    constantTensorSharing = null,
                    infiniteFloatCapping = true,
                    allowSrcQuantizedFcConvOps = null,
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    bufferStorageType = null,
                    preferTextureWeights = null,
                    serializationDir = null,
                    modelCacheKey = null,
                    serializeProgramCache = null,
                    serializeExternalTensors = null,
                    externalTensorsMode = null,
                    externalTensorPattern = null,
                    backend = null,
                    priority = null,
                    numStepsOfCommandBufferPreparations = null,
                )
            }
            val cm = CompiledModel.create(modelPath, options)
            return RealLiteRtRunner(cm)
        }
    }
}
