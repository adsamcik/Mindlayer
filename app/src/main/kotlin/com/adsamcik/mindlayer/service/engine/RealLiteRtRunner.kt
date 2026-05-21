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
 * EmbeddingGemma's exported `.tflite` (the seq2048 generic Mixed
 * Precision variant from `huggingface.co/litert-community/embeddinggemma-300m`)
 * expects, by exported signature index order:
 *   - input 0: `input_ids`       — int32, shape `[1, 2048]`
 *   - input 1: `attention_mask`  — int32, shape `[1, 2048]`
 *   - output 0: `sentence_embedding` — float32, shape `[1, 768]`
 *
 * The runner trusts this ordering. If a future model variant reorders
 * the signature, this is the single place to add a name-based lookup.
 */
internal class RealLiteRtRunner private constructor(
    private val compiledModel: CompiledModel,
) : LiteRtRunner {

    override fun runEmbedding(inputIds: IntArray, attentionMask: IntArray): FloatArray {
        val inputBuffers = compiledModel.createInputBuffers()
        try {
            val outputBuffers = compiledModel.createOutputBuffers()
            try {
                inputBuffers[0].writeInt(inputIds)
                inputBuffers[1].writeInt(attentionMask)
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
            val cm = CompiledModel.create(modelPath, CompiledModel.Options(accel))
            return RealLiteRtRunner(cm)
        }
    }
}
