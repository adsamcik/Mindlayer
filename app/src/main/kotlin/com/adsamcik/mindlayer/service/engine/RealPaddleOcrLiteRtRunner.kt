package com.adsamcik.mindlayer.service.engine

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * Production PP-OCRv5 mobile runner backed by LiteRT 2.1.5 `CompiledModel`.
 *
 * Kept out of [LiteRtPaddleOcrBackend] so Robolectric tests can exercise the
 * Kotlin pipeline without touching LiteRT native class initializers.
 */
internal class RealPaddleOcrLiteRtRunner private constructor(
    private val detectionModel: CompiledModel,
    private val recognitionModel: CompiledModel,
    private val orientationModel: CompiledModel?,
) : PaddleOcrLiteRtRunner {

    override fun runDetection(input: FloatArray): FloatArray =
        runFloatModel(detectionModel, input)

    override fun runOrientation(input: FloatArray): FloatArray? =
        orientationModel?.let { runFloatModel(it, input) }

    override fun runRecognition(input: FloatArray): FloatArray =
        runFloatModel(recognitionModel, input)

    override fun close() {
        runCatching { detectionModel.close() }
        runCatching { recognitionModel.close() }
        orientationModel?.let { runCatching { it.close() } }
    }

    private fun runFloatModel(model: CompiledModel, input: FloatArray): FloatArray {
        val inputBuffers = model.createInputBuffers()
        try {
            val outputBuffers = model.createOutputBuffers()
            try {
                inputBuffers[0].writeFloat(input)
                model.run(inputBuffers, outputBuffers)
                return outputBuffers[0].readFloat()
            } finally {
                outputBuffers.forEach { runCatching { it.close() } }
            }
        } finally {
            inputBuffers.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        /**
         * Creates the three-model OCR pipeline. Creating det/rec/cls
         * `CompiledModel` instances sequentially on the same accelerator
         * crosses the LiteRT issue #5264 hazard surface — this is the
         * highest-exposure caller of the resolver. The resolver picks
         * the accelerator (default GPU, NPU on explicit opt-in with
         * SoC + native-library probe); coexistence with LiteRT-LM
         * remains real-device-gated. See
         * docs/LITERT_COEXISTENCE.md for the validation checklist.
         */
        fun create(bundle: PaddleOcrModelInfo, acceleratorLabel: String): PaddleOcrLiteRtRunner {
            val accel = when (acceleratorLabel) {
                "NPU" -> Accelerator.NPU
                "GPU" -> Accelerator.GPU
                else -> Accelerator.CPU
            }
            var detectionModel: CompiledModel? = null
            var recognitionModel: CompiledModel? = null
            var orientationModel: CompiledModel? = null
            try {
                detectionModel = CompiledModel.create(bundle.detectionPath, CompiledModel.Options(accel))
                recognitionModel = CompiledModel.create(bundle.recognitionPath, CompiledModel.Options(accel))
                orientationModel = bundle.classifierPath?.let { path ->
                    CompiledModel.create(path, CompiledModel.Options(accel))
                }
                return RealPaddleOcrLiteRtRunner(
                    detectionModel = detectionModel,
                    recognitionModel = recognitionModel,
                    orientationModel = orientationModel,
                )
            } catch (t: Throwable) {
                detectionModel?.runCatching { close() }
                recognitionModel?.runCatching { close() }
                orientationModel?.runCatching { close() }
                throw t
            }
        }
    }
}
